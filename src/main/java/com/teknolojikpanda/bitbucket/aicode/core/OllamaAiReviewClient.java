package com.teknolojikpanda.bitbucket.aicode.core;

import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.teknolojikpanda.bitbucket.aicode.api.AiReviewClient;
import com.teknolojikpanda.bitbucket.aicode.api.MetricsRecorder;
import com.teknolojikpanda.bitbucket.aicode.model.ChunkReviewResult;
import com.teknolojikpanda.bitbucket.aicode.model.IssueCategory;
import com.teknolojikpanda.bitbucket.aicode.model.LineRange;
import com.teknolojikpanda.bitbucket.aicode.model.PromptTemplates;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewChunk;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewConfig;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewContext;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewFinding;
import com.teknolojikpanda.bitbucket.aicode.model.ReviewPreparation;
import com.teknolojikpanda.bitbucket.aicode.model.SeverityLevel;
import com.teknolojikpanda.bitbucket.aireviewer.util.CircuitBreaker;
import com.teknolojikpanda.bitbucket.aireviewer.util.RateLimiter;
import com.teknolojikpanda.bitbucket.aireviewer.util.CircuitBreaker.CircuitBreakerOpenException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.inject.Inject;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final Pattern LINE_MARKER_PATTERN = Pattern.compile("\\[Line\\s+(\\d+)]");

    private final CircuitBreaker circuitBreaker = new CircuitBreaker("ollama-client", 5, Duration.ofMinutes(1));
    private final RateLimiter rateLimiter = new RateLimiter("ollama-client", 10, Duration.ofSeconds(1));
    private final Set<String> unavailableModels =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final OverviewCache overviewCache;
    private final AtomicInteger circuitOpenEvents = new AtomicInteger();
    private final AtomicInteger circuitBlockedCalls = new AtomicInteger();
    private final AtomicInteger hardFailures = new AtomicInteger();

    @Inject
    public OllamaAiReviewClient(OverviewCache overviewCache) {
        this.overviewCache = overviewCache;
    }

    @Nonnull
    @Override
    public String generateOverview(@Nonnull ReviewPreparation preparation, @Nonnull MetricsRecorder metrics) {
        PromptTemplates templates = preparation.getContext().getConfig().getPromptTemplates();
        String cacheKey = overviewCache.buildKey(preparation.getContext().getPullRequest());
        return overviewCache.getOrCompute(cacheKey, () -> PromptRenderer.renderOverview(preparation, templates));
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
        } catch (CircuitBreakerOpenException ex) {
            circuitBlockedCalls.incrementAndGet();
            metrics.increment("ai.model.circuit.blocked");
            metrics.recordEnd("ai.chunk.call", start);
            return failureResult(chunk, "AI model recovery in progress; please retry shortly");
        } catch (VendorServerException ex) {
            metrics.increment("ai.model.vendor5xx");
            metrics.recordEnd("ai.chunk.call", start);
            log.warn("AI vendor server error for chunk {}: {}", chunk.getId(), ex.getMessage());
            return failureResult(chunk, ex.getMessage());
        } catch (Exception ex) {
            hardFailures.incrementAndGet();
            metrics.increment("ai.model.unhandledFailures");
            metrics.recordEnd("ai.chunk.call", start);
            log.error("AI model invocation failed for chunk {}: {}", chunk.getId(), ex.getMessage(), ex);
            return failureResult(chunk, "AI model invocation failed: " + ex.getMessage());
        }
    }

    private ChunkReviewResult failureResult(ReviewChunk chunk, String message) {
        return ChunkReviewResult.builder()
                .chunk(chunk)
                .success(false)
                .error(message)
                .build();
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
                "primary",
                metrics);

        if (findings == null) {
            metrics.increment("ai.model.fallback.triggered");
            findings = invokeModelWithRetry(
                    chunk,
                    overview,
                    context,
                    config.getFallbackModelEndpoint().toString(),
                    config.getFallbackModel(),
                    config,
                    "fallback",
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
                                                     String modelRole,
                                                     MetricsRecorder metrics) {
        int attempts = 0;
        int maxRetries = Math.max(1, config.getMaxRetries());
        int backoff = Math.max(500, config.getBaseRetryDelayMs());
        Exception lastError = null;
        boolean modelNotFound = false;
        String modelKey = modelKey(baseUrl, model);
        Instant invocationStart = Instant.now();
        metrics.increment("ai.model." + modelRole + ".invocations");

        if (unavailableModels.contains(modelKey)) {
            if (log.isDebugEnabled()) {
                log.debug("Skipping model {} at {} for chunk {} because it was previously reported missing",
                        model, baseUrl, chunk.getId());
            }
            return null;
        }

        String originalContent = chunk.getContent() != null ? chunk.getContent() : "";
        String diffContent = originalContent;
        int originalLength = originalContent.length();
        String lastErrorMessage = null;
        long lastRequestBytes = 0;
        long lastResponseBytes = 0;
        Integer lastStatusCode = null;
        boolean timeoutOccurred = false;
        boolean vendorThrottled = false;
        boolean serverSideFailure = false;
        long penaltyBackoffMs = 0L;

        while (attempts < maxRetries) {
            try {
                attempts++;
                metrics.increment("ai.chunk.attempt");
                String payload = buildChunkRequest(chunk, overview, context, model, diffContent, false);
                byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
                lastRequestBytes = payloadBytes.length;

                ChatResponse response = executeChat(baseUrl, payloadBytes, config);
                List<ReviewFinding> parsed = parseFindings(response.body, chunk);
                lastResponseBytes = response.responseBytes;
                lastStatusCode = response.statusCode;
                timeoutOccurred = false;
                lastErrorMessage = null;
                metrics.increment("ai.model." + modelRole + ".success");
                recordChunkInvocation(metrics,
                        chunk,
                        model,
                        baseUrl,
                        modelRole,
                        attempts,
                        invocationStart,
                        true,
                        modelNotFound,
                        null,
                        lastRequestBytes,
                        lastResponseBytes,
                        lastStatusCode,
                        false,
                        false);
                metrics.increment("ai.model." + modelRole + ".success");
                recordBreakerMetrics(metrics);
                return parsed;
            } catch (SocketTimeoutException ex) {
                log.warn("Model {} timeout on attempt {}: {}", model, attempts, ex.getMessage());
                lastError = ex;
                lastErrorMessage = ex.getMessage();
                timeoutOccurred = true;
                 serverSideFailure = true;
                lastResponseBytes = 0;
                lastStatusCode = null;
            } catch (Exception ex) {
                log.warn("Model {} failed on attempt {}: {}", model, attempts, ex.getMessage());
                lastError = ex;
                lastErrorMessage = ex.getMessage();
                timeoutOccurred = false;
                if (isModelNotFound(ex)) {
                    modelNotFound = true;
                    break;
                }
                if (ex instanceof OllamaHttpException) {
                    OllamaHttpException httpEx = (OllamaHttpException) ex;
                    lastStatusCode = httpEx.statusCode;
                    lastRequestBytes = httpEx.requestBytes;
                    lastResponseBytes = httpEx.responseBytes;
                    if (httpEx.statusCode == 429) {
                        vendorThrottled = true;
                        penaltyBackoffMs = Math.max(penaltyBackoffMs, backoff * (attempts + 1L));
                    } else if (httpEx.statusCode >= 500) {
                        serverSideFailure = true;
                    }
                }
                if (!(ex instanceof OllamaHttpException) && !(ex instanceof SocketTimeoutException)) {
                    lastStatusCode = null;
                    lastResponseBytes = 0;
                }
            }

            if (attempts < maxRetries) {
                try {
                    long sleepMs = (long) Math.pow(2, attempts - 1) * backoff + penaltyBackoffMs;
                    penaltyBackoffMs = 0L;
                    Thread.sleep(Math.min(60_000L, sleepMs));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    lastError = ie;
                    break;
                }
            }
        }
        if (modelNotFound) {
            log.error("Model {} not found at {} for chunk {}. Skipping analysis for this chunk.",
                    model, baseUrl, chunk.getId());
            unavailableModels.add(modelKey);
            metrics.increment("ai.model." + modelRole + ".failures");
            recordBreakerMetrics(metrics);
            recordChunkInvocation(metrics,
                    chunk,
                    model,
                    baseUrl,
                    modelRole,
                    attempts,
                    invocationStart,
                    false,
                    true,
                    lastErrorMessage != null ? lastErrorMessage : "model not found",
                    lastRequestBytes,
                    lastResponseBytes,
                    lastStatusCode,
                    timeoutOccurred,
                    vendorThrottled);
            return null;
        }
        log.error("Model {} failed after {} attempt(s) for chunk {}: {}",
                model, attempts, chunk.getId(), lastError != null ? lastError.getMessage() : "unknown error");
        log.warn("Chunk {} retried without payload reduction ({} chars)", chunk.getId(), originalLength);
        metrics.increment("ai.model." + modelRole + ".failures");
        recordBreakerMetrics(metrics);
        recordChunkInvocation(metrics,
                chunk,
                model,
                baseUrl,
                modelRole,
                attempts,
                invocationStart,
                false,
                modelNotFound,
                lastErrorMessage,
                lastRequestBytes,
                lastResponseBytes,
                lastStatusCode,
                timeoutOccurred,
                vendorThrottled);
        if (vendorThrottled) {
            metrics.increment("ai.model." + modelRole + ".throttled");
        }
        if (serverSideFailure || timeoutOccurred || (lastStatusCode != null && lastStatusCode >= 500)) {
            throw new VendorServerException(model, baseUrl, lastStatusCode, lastErrorMessage);
        }
        return null;
    }

    private void recordChunkInvocation(MetricsRecorder metrics,
                                       ReviewChunk chunk,
                                       String model,
                                       String baseUrl,
                                       String modelRole,
                                       int attempts,
                                       Instant invocationStart,
                                       boolean success,
                                       boolean modelNotFound,
                                       String lastErrorMessage,
                                       long requestBytes,
                                       long responseBytes,
                                       Integer statusCode,
                                       boolean timeout,
                                       boolean throttled) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("chunkId", chunk.getId());
        entry.put("role", modelRole);
        entry.put("model", model);
        entry.put("endpoint", baseUrl);
        entry.put("attempts", attempts);
        entry.put("retries", Math.max(0, attempts - 1));
        entry.put("durationMs", Math.max(0, Duration.between(invocationStart, Instant.now()).toMillis()));
        entry.put("success", success);
        entry.put("requestBytes", Math.max(0, requestBytes));
        entry.put("responseBytes", Math.max(0, responseBytes));
        entry.put("timeout", timeout);
        entry.put("throttled", throttled);
        if (statusCode != null) {
            entry.put("statusCode", statusCode);
        }
        if (modelNotFound) {
            entry.put("modelNotFound", Boolean.TRUE);
        }
        if (lastErrorMessage != null && !lastErrorMessage.trim().isEmpty()) {
            entry.put("lastError", abbreviate(lastErrorMessage, 512));
        }
        metrics.addListEntry("ai.chunk.invocations", entry);
    }

    private void recordBreakerMetrics(MetricsRecorder metrics) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("state", circuitBreaker.getState());
        snapshot.put("failureCount", circuitBreaker.getFailureCount());
        snapshot.put("openEvents", circuitBreaker.getOpenEvents());
        snapshot.put("blockedCalls", circuitBreaker.getBlockedCalls());
        snapshot.put("succeededCalls", circuitBreaker.getSucceededCalls());
        snapshot.put("failedCalls", circuitBreaker.getFailedCalls());
        snapshot.put("clientBlockedCalls", circuitBlockedCalls.get());
        snapshot.put("clientHardFailures", hardFailures.get());
        metrics.recordMetric("ai.model.circuit.snapshot", snapshot);
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, Math.max(0, maxLength));
    }

    private static final class ChatResponse {
        private final String body;
        private final int statusCode;
        private final long requestBytes;
        private final long responseBytes;

        private ChatResponse(String body, int statusCode, long requestBytes, long responseBytes) {
            this.body = body;
            this.statusCode = statusCode;
            this.requestBytes = requestBytes;
            this.responseBytes = responseBytes;
        }
    }

    private static final class OllamaHttpException extends RuntimeException {
        private final int statusCode;
        private final long requestBytes;
        private final long responseBytes;

        private OllamaHttpException(int statusCode, String message, long requestBytes, long responseBytes) {
            super("Ollama returned HTTP " + statusCode + ": " + (message != null ? message : ""));
            this.statusCode = statusCode;
            this.requestBytes = requestBytes;
            this.responseBytes = responseBytes;
        }
    }

    private static final class VendorServerException extends RuntimeException {
        private VendorServerException(String model, String endpoint, Integer statusCode, String detail) {
            super(buildMessage(model, endpoint, statusCode, detail));
        }

        private static String buildMessage(String model, String endpoint, Integer statusCode, String detail) {
            StringBuilder builder = new StringBuilder("AI vendor endpoint failure");
            if (model != null && !model.isEmpty()) {
                builder.append(" [model=").append(model).append("]");
            }
            if (endpoint != null && !endpoint.isEmpty()) {
                builder.append(" at ").append(endpoint);
            }
            if (statusCode != null) {
                builder.append(" returned ").append(statusCode);
            }
            if (detail != null && !detail.isEmpty()) {
                builder.append(": ").append(detail);
            }
            return builder.toString();
        }
    }

    private String modelKey(String baseUrl, String model) {
        return baseUrl + "|" + model;
    }

    private boolean isModelNotFound(Exception ex) {
        String message = ex.getMessage();
        return message != null && message.contains("model '") && message.contains("not found");
    }

    private String buildChunkRequest(ReviewChunk chunk,
                                     String overview,
                                     ReviewContext context,
                                     String model,
                                     String diffContent,
                                     boolean truncated) {
        if (diffContent == null) {
            diffContent = "";
        }
        String annotatedDiff = annotateWithLineNumbers(diffContent);
        if (truncated) {
            annotatedDiff = "(Diff truncated for transport limits)\n" + annotatedDiff;
        }
        PromptTemplates templates = context.getConfig().getPromptTemplates();
        String userPrompt = PromptRenderer.renderChunkInstructions(
                templates,
                context.getConfig(),
                context,
                chunk,
                overview,
                annotatedDiff);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("stream", Boolean.FALSE);
        request.put("format", buildResponseFormat(chunk));

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("system", templates.getSystemPrompt()));
        messages.add(message("user", userPrompt));
        request.put("messages", messages);

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

    private Map<String, Object> buildResponseFormat(ReviewChunk chunk) {
        Map<String, Object> format = new LinkedHashMap<>();
        format.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("issues", buildIssuesArraySchema(chunk));

        Map<String, Object> truncatedProp = new LinkedHashMap<>();
        truncatedProp.put("type", "boolean");
        properties.put("truncated", truncatedProp);

        format.put("properties", properties);
        format.put("required", List.of("issues"));
        return format;
    }

    private Map<String, Object> buildIssuesArraySchema(ReviewChunk chunk) {
        Map<String, Object> severityProp = new LinkedHashMap<>();
        severityProp.put("type", "string");
        severityProp.put("enum", List.of("critical", "high", "medium", "low"));

        Map<String, Object> categoryProp = new LinkedHashMap<>();
        categoryProp.put("type", "string");
        categoryProp.put("enum", List.of(
                "security", "bug", "performance", "reliability",
                "maintainability", "testing", "style", "documentation", "other"
        ));

        Map<String, Object> pathProp = new LinkedHashMap<>();
        pathProp.put("type", "string");
        List<String> availablePaths = chunk.getFiles();
        if (!availablePaths.isEmpty()) {
            pathProp.put("enum", new ArrayList<>(availablePaths));
        }

        Map<String, Object> issueProperties = new LinkedHashMap<>();
        issueProperties.put("path", pathProp);
        issueProperties.put("severity", severityProp);
        issueProperties.put("category", categoryProp);
        issueProperties.put("summary", typeProperty("string"));
        issueProperties.put("details", typeProperty("string"));
        issueProperties.put("fix", typeProperty("string"));

        Map<String, Object> problematicCodeObject = new LinkedHashMap<>();
        problematicCodeObject.put("type", "object");
        Map<String, Object> pcProps = new LinkedHashMap<>();

        Map<String, Object> snippetProp = typeProperty("string");
        snippetProp.put("description", "Exact added diff lines (with [Line N] markers) demonstrating the issue.");
        pcProps.put("snippet", snippetProp);
        Map<String, Object> lineStartProp = typeProperty("integer");
        lineStartProp.put("description", "Smallest [Line N] value appearing in snippet.");
        pcProps.put("lineStart", lineStartProp);

        Map<String, Object> lineEndProp = typeProperty("integer");
        lineEndProp.put("description", "Largest [Line N] value appearing in snippet.");
        pcProps.put("lineEnd", lineEndProp);

        problematicCodeObject.put("properties", pcProps);
        problematicCodeObject.put("required", List.of("snippet", "lineStart", "lineEnd"));
        issueProperties.put("problematicCode", problematicCodeObject);

        Map<String, Object> issueObject = new LinkedHashMap<>();
        issueObject.put("type", "object");
        issueObject.put("properties", issueProperties);
        issueObject.put("required", List.of("path",
                                            "severity",
                                            "category",
                                            "problematicCode",
                                            "details",
                                            "summary"));

        Map<String, Object> issuesArray = new LinkedHashMap<>();
        issuesArray.put("type", "array");
        issuesArray.put("items", issueObject);
        return issuesArray;
    }

    private Map<String, Object> typeProperty(String type) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type);
        return map;
    }

    private ChatResponse executeChat(String baseUrl,
                                     byte[] payloadBytes,
                                     ReviewConfig config) throws Exception {
        String normalized = baseUrl.endsWith("/") ? baseUrl + "api/chat" : baseUrl + "/api/chat";
        URI chatUri = URI.create(normalized);
        HttpURLConnection connection = (HttpURLConnection) chatUri.toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Connection", "close");
        connection.setRequestProperty("Content-Length", Integer.toString(payloadBytes.length));
        connection.setConnectTimeout(Math.max(5_000, config.getConnectTimeoutMs()));
        connection.setReadTimeout(Math.max(30_000, config.getRequestTimeoutMs()));
        connection.setDoOutput(true);
        connection.setDoInput(true);
        connection.setFixedLengthStreamingMode(payloadBytes.length);

        try (OutputStream os = connection.getOutputStream()) {
            os.write(payloadBytes);
            os.flush();
        }

        int status = connection.getResponseCode();
        if (status >= 400) {
            String error = readStream(connection, true);
            long errorBytes = error != null ? error.getBytes(StandardCharsets.UTF_8).length : 0;
            throw new OllamaHttpException(status, error, payloadBytes.length, errorBytes);
        }

        String body = readStream(connection, false);
        long responseBytes = body != null ? body.getBytes(StandardCharsets.UTF_8).length : 0;
        return new ChatResponse(body, status, payloadBytes.length, responseBytes);
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
            ReviewFinding finding = toFinding((Map<?, ?>) obj, chunk);
            if (finding != null) {
                findings.add(finding);
            }
        }
        return findings;
    }

    private ReviewFinding toFinding(Map<?, ?> map, ReviewChunk chunk) {
        try {
            Object pathObj = map.get("path");
            String path = pathObj != null ? pathObj.toString() : "";
            if (path.isEmpty()) {
                return null;
            }

            if (!chunk.getFiles().contains(path)) {
                log.warn("Discarding issue for chunk {}: path '{}' not present in chunk files {}",
                        chunk.getId(), path, chunk.getFiles());
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
            Object problematicObj = map.get("problematicCode");
            if (!(problematicObj instanceof Map)) {
                log.warn("Discarding issue for chunk {}: problematicCode object missing for {}",
                        chunk.getId(), path);
                return null;
            }
            Map<?, ?> problematic = (Map<?, ?>) problematicObj;
            String snippet = optionalString(problematic.get("snippet"));
            if (snippet == null || snippet.trim().isEmpty()) {
                log.warn("Discarding issue for chunk {}: missing problematicCode.snippet for {}",
                        chunk.getId(), path);
                return null;
            }

            int lineStart = parseInt(problematic.get("lineStart"), -1);
            int lineEnd = parseInt(problematic.get("lineEnd"), lineStart);

            if (!snippetMatchesChunk(snippet, chunk.getContent())) {
                log.warn("Discarding issue for chunk {}: problematicCode does not match diff content for {}",
                        chunk.getId(), path);
                return null;
            }

            LineRange inferredRange = extractLineRange(snippet);
            if (inferredRange != null) {
                if (lineStart != inferredRange.getStart() || lineEnd != inferredRange.getEnd()) {
                    String previous = (lineStart > 0 && lineEnd >= lineStart)
                            ? LineRange.of(lineStart, lineEnd).asDisplay()
                            : lineStart + "-" + lineEnd;
                    log.debug("Adjusted line range for chunk {} path {} based on snippet markers ({}->{})",
                            chunk.getId(),
                            path,
                            previous,
                            inferredRange.asDisplay());
                }
                lineStart = inferredRange.getStart();
                lineEnd = inferredRange.getEnd();
            }

            if (lineStart <= 0) {
                log.warn("Discarding issue for chunk {}: invalid problematicCode.lineStart {} after adjustments for {}",
                        chunk.getId(), lineStart, path);
                return null;
            }
            if (lineEnd < lineStart) {
                lineEnd = lineStart;
            }

            LineRange primary = chunk.getPrimaryRanges().get(path);
            if (primary != null && (lineStart < primary.getStart() || lineStart > primary.getEnd()
                    || lineEnd < primary.getStart() || lineEnd > primary.getEnd())) {
                log.warn("Discarding issue for chunk {}: line range {} outside primary range {} for {}",
                        chunk.getId(), LineRange.of(lineStart, lineEnd), primary, path);
                return null;
            }

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

    private boolean snippetMatchesChunk(String snippet, String chunkContent) {
        String normalizedChunk = chunkContent == null ? "" : chunkContent;
        for (String line : snippet.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!normalizedChunk.contains(trimmed)) {
                return false;
            }
        }
        return true;
    }

    private LineRange extractLineRange(String snippet) {
        if (snippet == null || snippet.isEmpty()) {
            return null;
        }
        Matcher matcher = LINE_MARKER_PATTERN.matcher(snippet);
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        while (matcher.find()) {
            try {
                int value = Integer.parseInt(matcher.group(1));
                min = Math.min(min, value);
                max = Math.max(max, value);
            } catch (NumberFormatException ignored) {
                // Ignore malformed markers and continue scanning
            }
        }
        if (min == Integer.MAX_VALUE || max == Integer.MIN_VALUE) {
            return null;
        }
        return LineRange.of(min, max);
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
