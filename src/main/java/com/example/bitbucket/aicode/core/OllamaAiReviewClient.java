package com.example.bitbucket.aicode.core;

import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.example.bitbucket.aicode.api.AiReviewClient;
import com.example.bitbucket.aicode.api.MetricsRecorder;
import com.example.bitbucket.aicode.model.ChunkReviewResult;
import com.example.bitbucket.aicode.model.IssueCategory;
import com.example.bitbucket.aicode.model.LineRange;
import com.example.bitbucket.aicode.model.ReviewChunk;
import com.example.bitbucket.aicode.model.ReviewConfig;
import com.example.bitbucket.aicode.model.ReviewContext;
import com.example.bitbucket.aicode.model.ReviewFinding;
import com.example.bitbucket.aicode.model.ReviewPreparation;
import com.example.bitbucket.aicode.model.SeverityLevel;
import com.example.bitbucket.aireviewer.util.CircuitBreaker;
import com.example.bitbucket.aireviewer.util.RateLimiter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.inject.Named;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ollama-based AI client performing overview and chunk analysis.
 */
@Named
@ExportAsService(AiReviewClient.class)
public class OllamaAiReviewClient implements AiReviewClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaAiReviewClient.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature(), true)
            .configure(JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature(), true);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {};

    private final CircuitBreaker circuitBreaker = new CircuitBreaker("ollama-client", 5, Duration.ofMinutes(1));
    private final RateLimiter rateLimiter = new RateLimiter("ollama-client", 10, Duration.ofSeconds(1));

    @Nonnull
    @Override
    public String generateOverview(@Nonnull ReviewPreparation preparation, @Nonnull MetricsRecorder metrics) {
        ReviewConfig config = preparation.getContext().getConfig();
        StringBuilder builder = new StringBuilder();
        builder.append("Repository: ")
                .append(preparation.getContext().getPullRequest().getToRef().getRepository().getProject().getKey())
                .append("/")
                .append(preparation.getContext().getPullRequest().getToRef().getRepository().getSlug())
                .append("\n");
        builder.append("Pull Request: ").append(preparation.getContext().getPullRequest().getId()).append("\n");
        builder.append("Total files: ").append(preparation.getOverview().getTotalFiles()).append("\n");
        builder.append("Total additions: ").append(preparation.getOverview().getTotalAdditions())
                .append(", deletions: ").append(preparation.getOverview().getTotalDeletions()).append("\n");
        builder.append("Files:\n");
        preparation.getOverview().getFileStats().forEach((path, stats) -> builder
                .append("- ").append(path)
                .append(" (+").append(stats.getAdditions())
                .append("/-").append(stats.getDeletions()).append(")\n"));
        builder.append("Min severity: ").append(config.getProfile().getMinSeverity()).append("\n");
        return builder.toString();
    }

    @Nonnull
    @Override
    public ChunkReviewResult reviewChunk(@Nonnull ReviewChunk chunk,
                                         @Nonnull String overview,
                                         @Nonnull ReviewContext context,
                                         @Nonnull MetricsRecorder metrics) {
        ReviewConfig config = context.getConfig();
        Instant start = metrics.recordStart("ai.chunk.call");
        try {
            ChunkReviewResult result = circuitBreaker.execute(() -> doReview(chunk, overview, context, config, metrics));
            metrics.recordEnd("ai.chunk.call", start);
            return result;
        } catch (Exception ex) {
            metrics.recordEnd("ai.chunk.call", start);
            return ChunkReviewResult.builder()
                    .chunk(chunk)
                    .success(false)
                    .error(ex.getMessage())
                    .build();
        }
    }

    private ChunkReviewResult doReview(ReviewChunk chunk,
                                       String overview,
                                       ReviewContext context,
                                       ReviewConfig config,
                                       MetricsRecorder metrics) {
        try {
            rateLimiter.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ChunkReviewResult.builder()
                    .chunk(chunk)
                    .success(false)
                    .error("Interrupted while waiting for rate limiter")
                    .build();
        }

        List<ReviewFinding> findings = invokeModelWithRetry(
                chunk,
                overview,
                context,
                config.getPrimaryModelEndpoint().toString(),
                config.getPrimaryModel(),
                config,
                metrics);

        if (findings == null || findings.isEmpty()) {
            findings = invokeModelWithRetry(
                    chunk,
                    overview,
                    context,
                    config.getFallbackModelEndpoint().toString(),
                    config.getFallbackModel(),
                    config,
                    metrics);
        }

        if (findings == null) {
            return ChunkReviewResult.builder()
                    .chunk(chunk)
                    .success(false)
                    .error("All model invocations failed")
                    .build();
        }

        return ChunkReviewResult.builder()
                .chunk(chunk)
                .findings(findings)
                .success(true)
                .build();
    }

    private List<ReviewFinding> invokeModelWithRetry(ReviewChunk chunk,
                                                     String overview,
                                                     ReviewContext context,
                                                     String baseUrl,
                                                     String model,
                                                     ReviewConfig config,
                                                     MetricsRecorder metrics) {
        int attempts = 0;
        int maxRetries = Math.max(1, config.getMaxRetries());
        int backoff = Math.max(500, config.getBaseRetryDelayMs());

        while (attempts < maxRetries) {
            try {
                attempts++;
                metrics.increment("ai.chunk.attempt");
                String payload = buildChunkRequest(chunk, overview, context, model);
                String response = executeChat(baseUrl, payload, config);
                return parseFindings(response, chunk);
            } catch (SocketTimeoutException ex) {
                log.warn("Model {} timeout on attempt {}: {}", model, attempts, ex.getMessage());
            } catch (Exception ex) {
                log.warn("Model {} failed on attempt {}: {}", model, attempts, ex.getMessage());
            }

            if (attempts < maxRetries) {
                try {
                    Thread.sleep((long) Math.pow(2, attempts - 1) * backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return Collections.emptyList();
                }
            }
        }
        return null;
    }

    private String buildChunkRequest(ReviewChunk chunk,
                                     String overview,
                                     ReviewContext context,
                                     String model) {
        String annotatedDiff = annotateWithLineNumbers(chunk.getContent());
        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("You are reviewing a pull request. Overview:\n")
                .append(overview)
                .append("\n\nAnalyze this diff chunk. Only report issues where added lines include [Line N] markers.")
                .append("\n\nCRITICAL ANALYSIS AREAS:\n")
                .append("🔴 SECURITY: SQL injection, XSS, CSRF, authentication/authorization flaws, input validation, data exposure, cryptography\n")
                .append("🔴 BUGS & LOGIC: Null dereferences, array bounds, race conditions, deadlocks, infinite loops, incorrect algorithms, edge cases\n")
                .append("🔴 PERFORMANCE: Memory leaks, inefficient queries, quadratic or worse algorithms, redundant computation, resource exhaustion\n")
                .append("🔴 RELIABILITY: Error handling gaps, exception swallowing, transactional integrity, data consistency, retries/backoff\n")
                .append("🔴 MAINTAINABILITY: Excessive complexity, duplication, tight coupling, missing docs, obscure naming\n")
                .append("🔴 TESTING: Missing coverage, weak assertions, brittle fixtures, missing mocks/stubs\n\n")
                .append("SEVERITY GUIDELINES:\n")
                .append("- CRITICAL: Security vulnerabilities, data corruption, crashes, production outages\n")
                .append("- HIGH: Logic flaws, major performance regressions, reliability risks, significant bugs\n")
                .append("- MEDIUM: Maintainability problems, moderate performance issues, code-quality gaps\n")
                .append("- LOW: Style inconsistencies, documentation gaps, minor optimisations\n\n")
                .append("CRITICAL INSTRUCTIONS:\n")
                .append("1. Hunt for subtle bugs that can trigger runtime failures in NEW code.\n")
                .append("2. Flag any security vulnerabilities introduced on ADDED lines.\n")
                .append("3. Evaluate performance impact of the NEW changes (algorithmic or resource usage).\n")
                .append("4. Skip reporting if confidence is low or evidence is missing.\n")
                .append("5. Never infer line numbers—only use explicit [Line N] tags.\n\n")
                .append("Return JSON object { \"issues\": [ ... ] } with fields: path, lineStart, lineEnd, severity (critical/high/medium/low), ")
                .append("category, summary, details, fix, problematicCode.\n")
                .append("Diff chunk:\n")
                .append(annotatedDiff);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("stream", Boolean.FALSE);

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("system",
                "You are an AI code reviewer. Follow all policy rules:\n" +
                "- Only reference lines that include [Line N] markers from the diff.\n" +
                "- Severity must be one of: critical, high, medium, low (see definitions in user prompt).\n" +
                "- Categories must be one of: security, bug, performance, reliability, maintainability, testing, style, documentation, other.\n" +
                "- Always prefer actionable, evidence-based findings. If unsure, omit the issue.\n" +
                "- Provide concise summaries and practical fixes."));
        messages.add(message("user", userPrompt.toString()));
        request.put("messages", messages);

        // Temporary Request Debug Log
        log.info("DEBUG: " + request);

        try {
            return OBJECT_MAPPER.writeValueAsString(request);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialise request", ex);
        }
    }

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("role", role);
        map.put("content", content);
        return map;
    }

    private String executeChat(String baseUrl,
                               String payload,
                               ReviewConfig config) throws Exception {
        String normalized = baseUrl.endsWith("/") ? baseUrl + "api/chat" : baseUrl + "/api/chat";
        URI chatUri = URI.create(normalized);
        HttpURLConnection connection = (HttpURLConnection) chatUri.toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setConnectTimeout(Math.max(5_000, config.getConnectTimeoutMs()));
        connection.setReadTimeout(Math.max(30_000, config.getRequestTimeoutMs()));
        connection.setDoOutput(true);

        try (OutputStream os = connection.getOutputStream()) {
            os.write(payload.getBytes(StandardCharsets.UTF_8));
        }

        int status = connection.getResponseCode();
        if (status >= 400) {
            String error = readStream(connection, true);
            throw new IllegalStateException("Ollama returned HTTP " + status + ": " + error);
        }

        return readStream(connection, false);
    }

    private List<ReviewFinding> parseFindings(String response, ReviewChunk chunk) throws Exception {
        Map<String, Object> envelope = parseJsonMap(response, "response envelope", chunk);
        if (envelope.isEmpty()) {
            log.warn("Model response envelope empty for chunk {}", chunk.getId());
            return Collections.emptyList();
        }
        String content = extractContent(envelope);
        if (content == null || content.trim().isEmpty()) {
            log.warn("Empty content from model for chunk {}", chunk.getId());
            return Collections.emptyList();
        }

        Map<String, Object> parsed = parseJsonMap(content, "content", chunk);
        if (parsed.isEmpty()) {
            log.warn("Model response content empty for chunk {}", chunk.getId());
            return Collections.emptyList();
        }
        Object issuesObj = parsed.get("issues");
        if (!(issuesObj instanceof List)) {
            log.warn("Model response missing issues array");
            return Collections.emptyList();
        }

        List<?> rawIssues = (List<?>) issuesObj;
        List<ReviewFinding> findings = new ArrayList<>();
        for (Object obj : rawIssues) {
            if (!(obj instanceof Map)) {
                continue;
            }
            ReviewFinding finding = toFinding((Map<?, ?>) obj);
            if (finding != null) {
                findings.add(finding);
            }
        }
        return findings;
    }

    private ReviewFinding toFinding(Map<?, ?> map) {
        try {
            Object pathObj = map.get("path");
            String path = pathObj != null ? pathObj.toString() : "";
            if (path.isEmpty()) {
                return null;
            }

            int lineStart = parseInt(map.get("lineStart"), -1);
            int lineEnd = parseInt(map.get("lineEnd"), lineStart);
            if (lineStart <= 0) {
                return null;
            }

            String severityStr = map.get("severity") != null ? map.get("severity").toString() : "medium";
            SeverityLevel severity = SeverityLevel.fromString(severityStr);
            String categoryStr = map.get("category") != null ? map.get("category").toString() : "other";
            IssueCategory category = IssueCategory.fromString(categoryStr);
            String summary = map.get("summary") != null ? map.get("summary").toString() : "";
            if (summary.isEmpty()) {
                return null;
            }

            String details = optionalString(map.get("details"));
            String fix = optionalString(map.get("fix"));
            String snippet = optionalString(map.get("problematicCode"));

            return ReviewFinding.builder()
                    .filePath(path)
                    .lineRange(LineRange.of(lineStart, lineEnd))
                    .severity(severity)
                    .category(category)
                    .summary(summary)
                    .details(details)
                    .fix(fix)
                    .snippet(snippet)
                    .build();
        } catch (Exception ex) {
            log.warn("Failed to parse finding from {}: {}", map, ex.getMessage());
            return null;
        }
    }

    private int parseInt(Object value, int defaultValue) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private String optionalString(Object value) {
        if (value == null) {
            return null;
        }
        String str = value.toString();
        return str.isEmpty() ? null : str;
    }

    private String annotateWithLineNumbers(String diffContent) {
        StringBuilder annotated = new StringBuilder();
        String[] lines = diffContent.split("\n");
        int currentDestLine = 0;
        boolean inHunk = false;

        for (String line : lines) {
            if (line.startsWith("@@")) {
                String[] parts = line.split("\\s+");
                for (String part : parts) {
                    if (part.startsWith("+")) {
                        String numPart = part.substring(1);
                        if (numPart.contains(",")) {
                            currentDestLine = Integer.parseInt(numPart.split(",")[0]);
                        } else {
                            currentDestLine = Integer.parseInt(numPart);
                        }
                        break;
                    }
                }
                annotated.append(line).append("\n");
                inHunk = true;
                continue;
            }

            if (line.startsWith("diff --git") || line.startsWith("index ") ||
                    line.startsWith("---") || line.startsWith("+++") || line.startsWith("\\")) {
                annotated.append(line).append("\n");
                inHunk = false;
                continue;
            }

            if (!inHunk) {
                annotated.append(line).append("\n");
                continue;
            }

            if (line.startsWith("+")) {
                annotated.append(String.format("[Line %d] %s\n", currentDestLine, line));
                currentDestLine++;
            } else if (line.startsWith("-")) {
                annotated.append(line).append("\n");
            } else {
                annotated.append(String.format("[Line %d] %s\n", currentDestLine, line));
                currentDestLine++;
            }
        }
        return annotated.toString();
    }

    private String extractContent(Map<String, Object> envelope) {
        Object messageObj = envelope.get("message");
        if (messageObj instanceof Map) {
            Map<?, ?> message = (Map<?, ?>) messageObj;
            Object content = message.get("content");
            if (content instanceof String) {
                return (String) content;
            }
        }
        Object content = envelope.get("content");
        if (content instanceof String) {
            return (String) content;
        }
        return null;
    }

    private Map<String, Object> parseJsonMap(String raw,
                                             String context,
                                             ReviewChunk chunk) throws JsonProcessingException {
        if (raw == null) {
            return Collections.emptyMap();
        }
        try {
            return OBJECT_MAPPER.readValue(raw, MAP_TYPE);
        } catch (JsonProcessingException first) {
            String sanitized = sanitizeJson(raw);
            if (sanitized.equals(raw)) {
                throw first;
            }
            if (sanitized.isEmpty()) {
                log.warn("Sanitized {} for chunk {} resulted in empty payload", context, chunk.getId());
                return Collections.emptyMap();
            }
            try {
                Map<String, Object> parsed = OBJECT_MAPPER.readValue(sanitized, MAP_TYPE);
                log.debug("Sanitized {} for chunk {} before parsing ({} -> {} chars)",
                        context, chunk.getId(), raw.length(), sanitized.length());
                return parsed;
            } catch (JsonProcessingException second) {
                log.warn("Failed to parse {} for chunk {} after sanitizing: {}",
                        context, chunk.getId(), second.getOriginalMessage());
                throw second;
            }
        }
    }

    private String sanitizeJson(String raw) {
        if (raw == null) {
            return "";
        }
        String sanitized = raw.trim();

        if (sanitized.startsWith("```")) {
            int firstNewline = sanitized.indexOf('\n');
            if (firstNewline >= 0) {
                sanitized = sanitized.substring(firstNewline + 1);
            } else {
                sanitized = sanitized.substring(3);
            }
        }

        sanitized = sanitized.trim();

        if (!sanitized.isEmpty() && sanitized.charAt(0) != '{' && sanitized.charAt(0) != '[') {
            int braceIndex = sanitized.indexOf('{');
            int bracketIndex = sanitized.indexOf('[');
            int startIndex = -1;
            if (braceIndex >= 0 && bracketIndex >= 0) {
                startIndex = Math.min(braceIndex, bracketIndex);
            } else if (braceIndex >= 0) {
                startIndex = braceIndex;
            } else if (bracketIndex >= 0) {
                startIndex = bracketIndex;
            }
            if (startIndex > 0) {
                sanitized = sanitized.substring(startIndex);
            }
        }

        while (!sanitized.isEmpty() && sanitized.charAt(0) != '{' && sanitized.charAt(0) != '[') {
            sanitized = sanitized.substring(1);
        }

        if (sanitized.endsWith("```")) {
            sanitized = sanitized.substring(0, sanitized.length() - 3);
        }

        sanitized = sanitized.trim();

        while (!sanitized.isEmpty()) {
            char last = sanitized.charAt(sanitized.length() - 1);
            if (last == '}' || last == ']') {
                break;
            }
            sanitized = sanitized.substring(0, sanitized.length() - 1).trim();
        }

        int lastBrace = sanitized.lastIndexOf('}');
        int lastBracket = sanitized.lastIndexOf(']');
        int endIndex = Math.max(lastBrace, lastBracket);
        if (endIndex >= 0 && endIndex < sanitized.length() - 1) {
            sanitized = sanitized.substring(0, endIndex + 1);
        }

        return sanitized.trim();
    }

    private String readStream(HttpURLConnection connection, boolean error) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                error ? connection.getErrorStream() : connection.getInputStream(),
                StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }
}
