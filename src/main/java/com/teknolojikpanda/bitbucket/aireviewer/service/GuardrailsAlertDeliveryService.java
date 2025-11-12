package com.teknolojikpanda.bitbucket.aireviewer.service;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.teknolojikpanda.bitbucket.aireviewer.ao.GuardrailsAlertDelivery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Named
@Singleton
public class GuardrailsAlertDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(GuardrailsAlertDeliveryService.class);
    private static final int MAX_PAYLOAD_LENGTH = 8192;
    private static final int MAX_ERROR_LENGTH = 1024;
    private static final int MAX_ACK_NOTE_LENGTH = 1024;

    private final ActiveObjects ao;

    @Inject
    public GuardrailsAlertDeliveryService(@ComponentImport ActiveObjects ao) {
        this.ao = Objects.requireNonNull(ao, "activeObjects");
    }

    public Delivery recordDelivery(DeliveryRequest request) {
        Objects.requireNonNull(request, "request");
        return ao.executeInTransaction(() -> {
            GuardrailsAlertDelivery entity = ao.create(GuardrailsAlertDelivery.class);
            entity.setChannelId(request.channelId);
            entity.setChannelUrl(trim(request.channelUrl));
            entity.setChannelDescription(trim(request.channelDescription));
            entity.setDeliveredAt(request.deliveredAt);
            entity.setSuccess(request.success);
            entity.setTest(request.test);
            entity.setHttpStatus(request.httpStatus);
            entity.setPayload(truncate(request.payloadJson, MAX_PAYLOAD_LENGTH));
            entity.setErrorMessage(truncate(request.errorMessage, MAX_ERROR_LENGTH));
            entity.setAcknowledged(false);
            entity.save();
            return toValue(entity);
        });
    }

    public Page<Delivery> listDeliveries(int offset, int limit) {
        final int safeOffset = Math.max(0, offset);
        final int safeLimit = Math.min(Math.max(limit, 1), 200);
        return ao.executeInTransaction(() -> {
            int total = ao.count(GuardrailsAlertDelivery.class);
            GuardrailsAlertDelivery[] rows = ao.find(GuardrailsAlertDelivery.class,
                    net.java.ao.Query.select()
                            .order("DELIVERED_AT DESC, ID DESC")
                            .offset(safeOffset)
                            .limit(safeLimit));
            List<Delivery> deliveries = new ArrayList<>(rows.length);
            for (GuardrailsAlertDelivery row : rows) {
                deliveries.add(toValue(row));
            }
            return new Page<>(deliveries, total, safeLimit, safeOffset);
        });
    }

    public Aggregates aggregateRecentDeliveries(int limit) {
        final int safeLimit = Math.min(Math.max(limit, 1), 500);
        return ao.executeInTransaction(() -> {
            GuardrailsAlertDelivery[] rows = ao.find(GuardrailsAlertDelivery.class,
                    net.java.ao.Query.select()
                            .order("DELIVERED_AT DESC, ID DESC")
                            .limit(safeLimit));
            if (rows.length == 0) {
                return Aggregates.empty();
            }
            int total = rows.length;
            int success = 0;
            int failures = 0;
            int tests = 0;
            for (GuardrailsAlertDelivery row : rows) {
                if (row.isTest()) {
                    tests++;
                }
                if (row.isSuccess()) {
                    success++;
                } else {
                    failures++;
                }
            }
            double failureRate = total == 0 ? 0d : (double) failures / (double) total;
            return new Aggregates(total, success, failures, tests, failureRate);
        });
    }

    public Delivery acknowledge(int id, String userKey, String displayName, @Nullable String note) {
        return ao.executeInTransaction(() -> {
            GuardrailsAlertDelivery entity = ao.get(GuardrailsAlertDelivery.class, id);
            if (entity == null) {
                throw new IllegalArgumentException("Delivery " + id + " not found");
            }
            entity.setAcknowledged(true);
            entity.setAckTimestamp(System.currentTimeMillis());
            entity.setAckUserKey(trim(userKey));
            entity.setAckUserDisplayName(trim(displayName));
            entity.setAckNote(truncate(note, MAX_ACK_NOTE_LENGTH));
            entity.save();
            return toValue(entity);
        });
    }

    public AcknowledgementStats computeAcknowledgementStats(int sampleLimit) {
        final int limit = Math.min(Math.max(sampleLimit, 1), 500);
        return ao.executeInTransaction(() -> {
            int pending = ao.count(GuardrailsAlertDelivery.class,
                    net.java.ao.Query.select().where("ACKNOWLEDGED = ?", false));
            long oldestPendingMs = 0L;
            if (pending > 0) {
                GuardrailsAlertDelivery[] oldest = ao.find(GuardrailsAlertDelivery.class,
                        net.java.ao.Query.select()
                                .where("ACKNOWLEDGED = ?", false)
                                .order("DELIVERED_AT ASC")
                                .limit(1));
                if (oldest.length > 0) {
                    oldestPendingMs = Math.max(0L, System.currentTimeMillis() - oldest[0].getDeliveredAt());
                }
            }
            GuardrailsAlertDelivery[] ackedRows = ao.find(GuardrailsAlertDelivery.class,
                    net.java.ao.Query.select()
                            .where("ACKNOWLEDGED = ? AND ACK_TIMESTAMP > 0", true)
                            .order("ACK_TIMESTAMP DESC, ID DESC")
                            .limit(limit));
            long totalLatency = 0L;
            int samples = 0;
            for (GuardrailsAlertDelivery row : ackedRows) {
                long latency = row.getAckTimestamp() - row.getDeliveredAt();
                if (latency >= 0) {
                    totalLatency += latency;
                    samples++;
                }
            }
            double averageLatencyMs = samples == 0 ? 0d : (double) totalLatency / samples;
            return new AcknowledgementStats(pending, oldestPendingMs, averageLatencyMs, samples);
        });
    }

    private Delivery toValue(GuardrailsAlertDelivery entity) {
        return new Delivery(
                entity.getID(),
                entity.getChannelId(),
                entity.getChannelUrl(),
                entity.getChannelDescription(),
                entity.getDeliveredAt(),
                entity.isSuccess(),
                entity.isTest(),
                entity.getHttpStatus(),
                entity.getPayload(),
                entity.getErrorMessage(),
                entity.isAcknowledged(),
                entity.getAckUserKey(),
                entity.getAckUserDisplayName(),
                entity.getAckTimestamp(),
                entity.getAckNote());
    }

    private String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(maxLength, 0));
    }

    public static final class DeliveryRequest {
        private final int channelId;
        private final String channelUrl;
        private final String channelDescription;
        private final long deliveredAt;
        private final boolean success;
        private final boolean test;
        private final int httpStatus;
        private final String payloadJson;
        private final String errorMessage;

        public DeliveryRequest(int channelId,
                               String channelUrl,
                               String channelDescription,
                               long deliveredAt,
                               boolean success,
                               boolean test,
                               int httpStatus,
                               String payloadJson,
                               String errorMessage) {
            this.channelId = channelId;
            this.channelUrl = channelUrl;
            this.channelDescription = channelDescription;
            this.deliveredAt = deliveredAt;
            this.success = success;
            this.test = test;
            this.httpStatus = httpStatus;
            this.payloadJson = payloadJson;
            this.errorMessage = errorMessage;
        }
    }

    public static final class Delivery {
        private final int id;
        private final int channelId;
        private final String channelUrl;
        private final String channelDescription;
        private final long deliveredAt;
        private final boolean success;
        private final boolean test;
        private final int httpStatus;
        private final String payloadJson;
        private final String errorMessage;
        private final boolean acknowledged;
        private final String ackUserKey;
        private final String ackUserDisplayName;
        private final long ackTimestamp;
        private final String ackNote;

        public Delivery(int id,
                        int channelId,
                        String channelUrl,
                        String channelDescription,
                        long deliveredAt,
                        boolean success,
                        boolean test,
                        int httpStatus,
                        String payloadJson,
                        String errorMessage,
                        boolean acknowledged,
                        String ackUserKey,
                        String ackUserDisplayName,
                        long ackTimestamp,
                        String ackNote) {
            this.id = id;
            this.channelId = channelId;
            this.channelUrl = channelUrl;
            this.channelDescription = channelDescription;
            this.deliveredAt = deliveredAt;
            this.success = success;
            this.test = test;
            this.httpStatus = httpStatus;
            this.payloadJson = payloadJson;
            this.errorMessage = errorMessage;
            this.acknowledged = acknowledged;
            this.ackUserKey = ackUserKey;
            this.ackUserDisplayName = ackUserDisplayName;
            this.ackTimestamp = ackTimestamp;
            this.ackNote = ackNote;
        }

        public int getId() {
            return id;
        }

        public int getChannelId() {
            return channelId;
        }

        public String getChannelUrl() {
            return channelUrl;
        }

        public String getChannelDescription() {
            return channelDescription;
        }

        public long getDeliveredAt() {
            return deliveredAt;
        }

        public boolean isSuccess() {
            return success;
        }

        public boolean isTest() {
            return test;
        }

        public int getHttpStatus() {
            return httpStatus;
        }

        public String getPayloadJson() {
            return payloadJson;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public boolean isAcknowledged() {
            return acknowledged;
        }

        public String getAckUserKey() {
            return ackUserKey;
        }

        public String getAckUserDisplayName() {
            return ackUserDisplayName;
        }

        public long getAckTimestamp() {
            return ackTimestamp;
        }

        public String getAckNote() {
            return ackNote;
        }
    }

    public static final class AcknowledgementStats {
        private final int pendingCount;
        private final long oldestPendingMillis;
        private final double averageAckMillis;
        private final int ackSamples;

        public AcknowledgementStats(int pendingCount,
                                    long oldestPendingMillis,
                                    double averageAckMillis,
                                    int ackSamples) {
            this.pendingCount = pendingCount;
            this.oldestPendingMillis = Math.max(0L, oldestPendingMillis);
            this.averageAckMillis = averageAckMillis < 0 ? 0d : averageAckMillis;
            this.ackSamples = ackSamples;
        }

        public int getPendingCount() {
            return pendingCount;
        }

        public long getOldestPendingMillis() {
            return oldestPendingMillis;
        }

        public double getAverageAckMillis() {
            return averageAckMillis;
        }

        public int getAckSamples() {
            return ackSamples;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("pendingCount", pendingCount);
            map.put("oldestPendingMillis", oldestPendingMillis);
            map.put("averageAckMillis", averageAckMillis);
            map.put("ackSamples", ackSamples);
            return map;
        }
    }

    public static final class Aggregates {
        private final int samples;
        private final int successes;
        private final int failures;
        private final int tests;
        private final double failureRate;

        Aggregates(int samples,
                   int successes,
                   int failures,
                   int tests,
                   double failureRate) {
            this.samples = samples;
            this.successes = successes;
            this.failures = failures;
            this.tests = tests;
            this.failureRate = failureRate;
        }

        public int getSamples() {
            return samples;
        }

        public int getSuccesses() {
            return successes;
        }

        public int getFailures() {
            return failures;
        }

        public int getTests() {
            return tests;
        }

        public double getFailureRate() {
            return failureRate;
        }

        public static Aggregates empty() {
            return new Aggregates(0, 0, 0, 0, 0d);
        }
    }
}
