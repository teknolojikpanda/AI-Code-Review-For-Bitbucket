package com.teknolojikpanda.bitbucket.aireviewer.service;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teknolojikpanda.bitbucket.aireviewer.ao.GuardrailsAlertChannel;
import com.teknolojikpanda.bitbucket.aireviewer.service.GuardrailsAlertDeliveryService;
import net.java.ao.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Named
@Singleton
public class GuardrailsAlertChannelService {

    private static final Logger log = LoggerFactory.getLogger(GuardrailsAlertChannelService.class);
    private static final String SIGNATURE_HEADER = "X-Guardrails-Signature";
    private static final String SIGNED_AT_HEADER = "X-Guardrails-Signed-At";
    private static final int DEFAULT_MAX_RETRIES = 2;
    private static final int DEFAULT_BACKOFF_SECONDS = 5;
    private static final int MAX_RETRIES_LIMIT = 5;
    private static final int MAX_BACKOFF_SECONDS = 60;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ActiveObjects ao;
    private final GuardrailsAlertDeliveryService deliveryService;
    private final ObjectMapper mapper = new ObjectMapper();

    @Inject
    public GuardrailsAlertChannelService(@ComponentImport ActiveObjects ao,
                                         GuardrailsAlertDeliveryService deliveryService) {
        this.ao = Objects.requireNonNull(ao, "activeObjects");
        this.deliveryService = Objects.requireNonNull(deliveryService, "deliveryService");
    }

    public List<Channel> listChannels() {
        GuardrailsAlertChannel[] rows = ao.executeInTransaction(() ->
                ao.find(GuardrailsAlertChannel.class));
        if (rows.length == 0) {
            return Collections.emptyList();
        }
        List<Channel> channels = new ArrayList<>(rows.length);
        for (GuardrailsAlertChannel row : rows) {
            channels.add(toValue(row));
        }
        return channels;
    }

    public Page<Channel> listChannels(int offset, int limit) {
        final int safeOffset = Math.max(0, offset);
        final int safeLimit = Math.min(Math.max(limit, 1), 200);
        return ao.executeInTransaction(() -> {
            int total = ao.count(GuardrailsAlertChannel.class);
            GuardrailsAlertChannel[] rows = ao.find(GuardrailsAlertChannel.class,
                    Query.select()
                            .order("ID ASC")
                            .limit(safeLimit)
                            .offset(safeOffset));
            List<Channel> values = new ArrayList<>(rows.length);
            for (GuardrailsAlertChannel row : rows) {
                values.add(toValue(row));
            }
            return new Page<>(values, total, safeLimit, safeOffset);
        });
    }

    public Channel createChannel(String url,
                                 String description,
                                 boolean enabled,
                                 boolean signRequests,
                                 String sharedSecret,
                                 Integer maxRetries,
                                 Integer retryBackoffSeconds) {
        String sanitizedUrl = sanitize(url);
        validateUrl(sanitizedUrl);
        int retries = normalizeRetries(maxRetries);
        int backoff = normalizeBackoff(retryBackoffSeconds);
        String providedSecret = sanitize(sharedSecret);
        if (!signRequests) {
            providedSecret = null;
        }
        final String secret = (signRequests && (providedSecret == null || providedSecret.length() < 16))
                ? generateSecret()
                : providedSecret;
        return ao.executeInTransaction(() -> {
            GuardrailsAlertChannel row = ao.create(GuardrailsAlertChannel.class);
            row.setUrl(sanitizedUrl);
            row.setDescription(sanitize(description));
            row.setEnabled(enabled);
            row.setSignRequests(signRequests);
            row.setSecret(secret);
            row.setMaxRetries(retries);
            row.setRetryBackoffSeconds(backoff);
            row.setCreatedAt(System.currentTimeMillis());
            row.setUpdatedAt(row.getCreatedAt());
            row.save();
            log.info("Created guardrails alert channel {}", sanitizedUrl);
            return toValue(row);
        });
    }

    public Channel updateChannel(int id,
                                 String description,
                                 Boolean enabled,
                                 Boolean signRequests,
                                 Boolean rotateSecret,
                                 String secretOverride,
                                 Integer maxRetries,
                                 Integer retryBackoffSeconds) {
        return ao.executeInTransaction(() -> {
            GuardrailsAlertChannel row = ao.get(GuardrailsAlertChannel.class, id);
            if (row == null) {
                throw new IllegalArgumentException("Channel " + id + " not found");
            }
            if (description != null) {
                row.setDescription(sanitize(description));
            }
            if (enabled != null) {
                row.setEnabled(enabled);
            }
            if (signRequests != null) {
                row.setSignRequests(signRequests);
            }
            if (Boolean.TRUE.equals(rotateSecret)) {
                row.setSecret(generateSecret());
            } else if (secretOverride != null && !secretOverride.trim().isEmpty()) {
                row.setSecret(secretOverride.trim());
            }
            if (maxRetries != null) {
                row.setMaxRetries(normalizeRetries(maxRetries));
            }
            if (retryBackoffSeconds != null) {
                row.setRetryBackoffSeconds(normalizeBackoff(retryBackoffSeconds));
            }
            row.setUpdatedAt(System.currentTimeMillis());
            row.save();
            return toValue(row);
        });
    }

    public Channel getChannel(int id) {
        Channel channel = ao.executeInTransaction(() -> {
            GuardrailsAlertChannel row = ao.get(GuardrailsAlertChannel.class, id);
            return row != null ? toValue(row) : null;
        });
        if (channel == null) {
            throw new IllegalArgumentException("Channel " + id + " not found");
        }
        return channel;
    }

    public void deleteChannel(int id) {
        ao.executeInTransaction(() -> {
            GuardrailsAlertChannel row = ao.get(GuardrailsAlertChannel.class, id);
            if (row != null) {
                log.info("Deleting guardrails alert channel {}", row.getUrl());
                ao.delete(row);
            }
            return null;
        });
    }

    public boolean sendTestAlert(int id) {
        Channel channel = getChannel(id);
        GuardrailsAlertingService.AlertSnapshot snapshot = GuardrailsAlertingService.AlertSnapshot.sample(channel.getDescription());
        return deliverSnapshot(channel, snapshot, true);
    }

    public void notifyChannels(GuardrailsAlertingService.AlertSnapshot snapshot) {
        List<Channel> channels = listChannels();
        if (channels.isEmpty() || snapshot.getAlerts().isEmpty()) {
            return;
        }
        for (Channel channel : channels) {
            if (!channel.isEnabled()) {
                continue;
            }
            deliverSnapshot(channel, snapshot, false);
        }
    }

    private boolean deliverSnapshot(Channel channel,
                                    GuardrailsAlertingService.AlertSnapshot snapshot,
                                    boolean test) {
        URL target = toUrl(channel.getUrl());
        if (target == null) {
            log.warn("Skipping alert delivery; invalid URL {}", channel.getUrl());
            recordDelivery(channel, snapshot, false, 0, "Invalid URL", test);
            return false;
        }
        int attempts = 0;
        int maxRetries = channel.getMaxRetries() >= 0 ? channel.getMaxRetries() : DEFAULT_MAX_RETRIES;
        int backoffSeconds = channel.getRetryBackoffSeconds() > 0 ? channel.getRetryBackoffSeconds() : DEFAULT_BACKOFF_SECONDS;
        boolean success = false;
        int status = 0;
        String error = null;
        byte[] payload;
        try {
            payload = mapper.writeValueAsBytes(snapshot);
        } catch (IOException e) {
            log.warn("Failed to serialize alert snapshot: {}", e.getMessage());
            recordDelivery(channel, snapshot, false, 0, "Serialization failure", test);
            return false;
        }
        do {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) target.openConnection();
                connection.setDoOutput(true);
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setRequestProperty("Content-Type", "application/json");
                if (channel.isSignRequests() && channel.getSecret() != null && !channel.getSecret().isEmpty()) {
                    String timestamp = String.valueOf(System.currentTimeMillis());
                    String signature = computeSignature(channel.getSecret(), payload, timestamp);
                    if (signature != null) {
                        connection.setRequestProperty(SIGNED_AT_HEADER, timestamp);
                        connection.setRequestProperty(SIGNATURE_HEADER, signature);
                    }
                }
                try (OutputStream os = connection.getOutputStream()) {
                    os.write(payload);
                }
                status = connection.getResponseCode();
                if (status >= 400) {
                    error = "HTTP " + status;
                    log.warn("Guardrails alert delivery to {} failed with HTTP {}", channel.getUrl(), status);
                } else {
                    success = true;
                }
            } catch (IOException ex) {
                error = ex.getMessage();
                log.warn("Failed to deliver guardrails alert to {} attempt {}: {}", channel.getUrl(), attempts + 1, ex.getMessage());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            if (success) {
                break;
            }
            attempts++;
            if (attempts <= maxRetries) {
                try {
                    TimeUnit.SECONDS.sleep((long) backoffSeconds * attempts);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } while (attempts <= maxRetries);

        recordDelivery(channel, snapshot, success, status, error, test);
        return success;
    }

    private String computeSignature(String secret, byte[] payload, String timestamp) {
        try {
            String material = timestamp + "." + Base64.getEncoder().encodeToString(payload);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(material.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception ex) {
            log.warn("Failed to compute webhook signature: {}", ex.getMessage());
            return null;
        }
    }

    private void recordDelivery(Channel channel,
                                GuardrailsAlertingService.AlertSnapshot snapshot,
                                boolean success,
                                int httpStatus,
                                String errorMessage,
                                boolean test) {
        String json = null;
        try {
            json = mapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            json = "{\"error\":\"Failed to serialize snapshot\"}";
        }
        GuardrailsAlertDeliveryService.DeliveryRequest request =
                new GuardrailsAlertDeliveryService.DeliveryRequest(
                        channel.getId(),
                        channel.getUrl(),
                        channel.getDescription(),
                        System.currentTimeMillis(),
                        success,
                        test,
                        httpStatus,
                        json,
                        errorMessage);
        try {
            deliveryService.recordDelivery(request);
        } catch (Exception ex) {
            log.warn("Failed to record guardrails alert delivery for channel {}: {}", channel.getUrl(), ex.getMessage());
        }
    }

    private URL toUrl(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return new URL(value.trim());
        } catch (MalformedURLException e) {
            return null;
        }
    }

    private Channel toValue(GuardrailsAlertChannel row) {
        return new Channel(
                row.getID(),
                row.getUrl(),
                row.getDescription(),
                row.isEnabled(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.isSignRequests(),
                row.getSecret(),
                normalizeRetries(row.getMaxRetries()),
                normalizeBackoff(row.getRetryBackoffSeconds()));
    }

    private String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void validateUrl(String url) {
        if (url == null) {
            throw new IllegalArgumentException("Channel URL is required");
        }
        URL parsed = toUrl(url);
        if (parsed == null || parsed.getHost() == null) {
            throw new IllegalArgumentException("Invalid channel URL: " + url);
        }
        String scheme = parsed.getProtocol();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Unsupported URL scheme: " + scheme);
        }
    }

    private String generateSecret() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private int normalizeRetries(Integer retries) {
        if (retries == null) {
            return DEFAULT_MAX_RETRIES;
        }
        return Math.max(0, Math.min(retries, MAX_RETRIES_LIMIT));
    }

    private int normalizeBackoff(Integer backoffSeconds) {
        if (backoffSeconds == null) {
            return DEFAULT_BACKOFF_SECONDS;
        }
        return Math.max(1, Math.min(backoffSeconds, MAX_BACKOFF_SECONDS));
    }

    public static final class Channel {
        private final int id;
        private final String url;
        private final String description;
        private final boolean enabled;
        private final long createdAt;
        private final long updatedAt;
        private final boolean signRequests;
        private final String secret;
        private final int maxRetries;
        private final int retryBackoffSeconds;

        public Channel(int id,
                       String url,
                       String description,
                       boolean enabled,
                       long createdAt,
                       long updatedAt,
                       boolean signRequests,
                       String secret,
                       int maxRetries,
                       int retryBackoffSeconds) {
            this.id = id;
            this.url = url;
            this.description = description;
            this.enabled = enabled;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.signRequests = signRequests;
            this.secret = secret;
            this.maxRetries = maxRetries;
            this.retryBackoffSeconds = retryBackoffSeconds;
        }

        public int getId() {
            return id;
        }

        public String getUrl() {
            return url;
        }

        public String getDescription() {
            return description;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public long getCreatedAt() {
            return createdAt;
        }

        public long getUpdatedAt() {
            return updatedAt;
        }

        public boolean isSignRequests() {
            return signRequests;
        }

        public String getSecret() {
            return secret;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public int getRetryBackoffSeconds() {
            return retryBackoffSeconds;
        }
    }
}
