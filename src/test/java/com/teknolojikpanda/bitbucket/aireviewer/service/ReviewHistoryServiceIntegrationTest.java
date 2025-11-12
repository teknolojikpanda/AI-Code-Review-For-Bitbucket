package com.teknolojikpanda.bitbucket.aireviewer.service;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.activeobjects.internal.EntityManagedActiveObjects;
import com.atlassian.activeobjects.internal.TransactionManager;
import com.atlassian.activeobjects.spi.DatabaseType;
import com.atlassian.sal.api.transaction.TransactionCallback;
import com.teknolojikpanda.bitbucket.aireviewer.ao.AIReviewChunk;
import com.teknolojikpanda.bitbucket.aireviewer.ao.AIReviewHistory;
import com.teknolojikpanda.bitbucket.aireviewer.util.LargeFieldCompression;
import net.java.ao.DBParam;
import net.java.ao.EntityManager;
import net.java.ao.test.jdbc.H2Memory;
import net.java.ao.test.jdbc.Jdbc;
import net.java.ao.test.junit.ActiveObjectsJUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(ActiveObjectsJUnitRunner.class)
@Jdbc(H2Memory.class)
public class ReviewHistoryServiceIntegrationTest {

    private static final String METRICS_JSON = "{"
            + "\"ai.chunk.invocations\":["
            + "{\"chunkId\":\"chunk-a\",\"role\":\"system\",\"model\":\"llama3\",\"endpoint\":\"/v1/chat\","
            + "\"attempts\":2,\"retries\":1,\"durationMs\":1500,\"success\":true,\"modelNotFound\":false,"
            + "\"requestBytes\":512,\"responseBytes\":1024,\"statusCode\":200,\"timeout\":false},"
            + "{\"chunkId\":\"chunk-b\",\"role\":\"assistant\",\"model\":\"llama3\",\"endpoint\":\"/v1/chat\","
            + "\"attempts\":1,\"retries\":0,\"durationMs\":800,\"success\":false,\"modelNotFound\":false,"
            + "\"requestBytes\":256,\"responseBytes\":384,\"statusCode\":504,\"timeout\":true},"
            + "{\"chunkId\":\"\",\"durationMs\":10}"
            + "]}";

    private EntityManager entityManager;
    private ActiveObjects activeObjects;
    private ReviewHistoryService service;

    @Before
    public void setUp() {
        activeObjects = new TestActiveObjects(entityManager);
        activeObjects.migrate(AIReviewHistory.class, AIReviewChunk.class);
        service = new ReviewHistoryService(activeObjects);
    }

    @Test
    public void backfillChunkTelemetryPopulatesMissingChunkRows() {
        AIReviewHistory needsBackfill = createHistory(1L, "WOR", "demo", METRICS_JSON);
        AIReviewHistory alreadyPopulated = createHistory(2L, "WOR", "demo", METRICS_JSON);
        createChunk(alreadyPopulated, "existing-chunk", 0);
        AIReviewHistory noMetrics = createHistory(3L, "WOR", "demo", null);

        Map<String, Object> result = service.backfillChunkTelemetry(0);

        assertEquals(3, ((Number) result.get("historiesScanned")).intValue());
        assertEquals(1, ((Number) result.get("historiesUpdated")).intValue());
        assertEquals(2, ((Number) result.get("chunksCreated")).intValue());

        AIReviewChunk[] backfilledChunks = needsBackfill.getChunks();
        Arrays.sort(backfilledChunks, Comparator.comparingInt(AIReviewChunk::getSequence));
        assertEquals(2, backfilledChunks.length);

        AIReviewChunk first = backfilledChunks[0];
        assertEquals("chunk-a", first.getChunkId());
        assertEquals(0, first.getSequence());
        assertEquals(2, first.getAttempts());
        assertEquals(1, first.getRetries());
        assertEquals(1500L, first.getDurationMs());
        assertTrue(first.isSuccess());
        assertEquals(512L, first.getRequestBytes());
        assertEquals(1024L, first.getResponseBytes());
        assertEquals(200, first.getStatusCode());
        assertFalse(first.isTimeout());

        AIReviewChunk second = backfilledChunks[1];
        assertEquals("chunk-b", second.getChunkId());
        assertEquals(1, second.getSequence());
        assertEquals(1, second.getAttempts());
        assertEquals(0, second.getRetries());
        assertEquals(800L, second.getDurationMs());
        assertFalse(second.isSuccess());
        assertEquals(256L, second.getRequestBytes());
        assertEquals(384L, second.getResponseBytes());
        assertEquals(504, second.getStatusCode());
        assertTrue(second.isTimeout());

        AIReviewChunk[] existing = alreadyPopulated.getChunks();
        assertEquals(1, existing.length);
        assertEquals("existing-chunk", existing[0].getChunkId());

        assertEquals(0, noMetrics.getChunks().length);
    }

    @Test
    public void metricsSummaryAggregatesChunkTelemetry() {
        AIReviewHistory successHistory = createHistory(10L, "WOR", "demo", null);
        successHistory.setReviewStatus("SUCCESS");
        successHistory.setReviewEndTime(successHistory.getReviewStartTime() + 4_000);
        successHistory.setTotalIssuesFound(5);
        successHistory.setCriticalIssues(1);
        successHistory.setHighIssues(2);
        successHistory.setMediumIssues(1);
        successHistory.setLowIssues(1);
        successHistory.setTotalChunks(3);
        successHistory.setSuccessfulChunks(2);
        successHistory.setFailedChunks(1);
        successHistory.setPrimaryModelInvocations(3);
        successHistory.setPrimaryModelSuccesses(2);
        successHistory.setPrimaryModelFailures(1);
        successHistory.setFallbackModelInvocations(1);
        successHistory.setFallbackModelSuccesses(1);
        successHistory.setFallbackModelFailures(0);
        successHistory.setFallbackTriggered(1);
        successHistory.save();

        createChunkWithTelemetry(successHistory, "succ-0", 0, 100, 200, 200, false);
        createChunkWithTelemetry(successHistory, "succ-1", 1, 150, 50, 504, true);

        AIReviewHistory failedHistory = createHistory(11L, "WOR", "demo", null);
        failedHistory.setReviewStatus("FAILED");
        failedHistory.setReviewEndTime(failedHistory.getReviewStartTime() + 2_500);
        failedHistory.setTotalIssuesFound(2);
        failedHistory.setHighIssues(1);
        failedHistory.setMediumIssues(1);
        failedHistory.setTotalChunks(2);
        failedHistory.setSuccessfulChunks(1);
        failedHistory.setFailedChunks(1);
        failedHistory.setPrimaryModelInvocations(1);
        failedHistory.setPrimaryModelSuccesses(0);
        failedHistory.setPrimaryModelFailures(1);
        failedHistory.setFallbackModelInvocations(2);
        failedHistory.setFallbackModelSuccesses(1);
        failedHistory.setFallbackModelFailures(1);
        failedHistory.setFallbackTriggered(1);
        failedHistory.save();

        createChunkWithTelemetry(failedHistory, "fail-0", 0, 250, 300, 200, false);
        createChunkWithTelemetry(failedHistory, "fail-1", 1, 75, 125, 429, true);

        Map<String, Object> summary = service.getMetricsSummary("WOR", "demo", null, null, null);

        AIReviewChunk[] chunks = activeObjects.find(AIReviewChunk.class);
        long totalReq = 0;
        long totalResp = 0;
        for (AIReviewChunk chunk : chunks) {
            totalReq += chunk.getRequestBytes();
            totalResp += chunk.getResponseBytes();
        }
        assertEquals("sanity", 575L, totalReq);
        assertEquals("sanity", 675L, totalResp);

        @SuppressWarnings("unchecked")
        Map<String, Long> statusCounts = (Map<String, Long>) summary.get("statusCounts");
        assertEquals(Long.valueOf(1), statusCounts.get("SUCCESS"));
        assertEquals(Long.valueOf(1), statusCounts.get("FAILED"));

        @SuppressWarnings("unchecked")
        Map<String, Object> chunkTotals = (Map<String, Object>) summary.get("chunkTotals");
        assertEquals(5L, ((Number) chunkTotals.get("totalChunks")).longValue());
        assertEquals(3L, ((Number) chunkTotals.get("successfulChunks")).longValue());
        assertEquals(2L, ((Number) chunkTotals.get("failedChunks")).longValue());

        @SuppressWarnings("unchecked")
        Map<String, Object> ioTotals = (Map<String, Object>) summary.get("ioTotals");
        assertEquals(575L, ((Number) ioTotals.get("requestBytes")).longValue());
        assertEquals(675L, ((Number) ioTotals.get("responseBytes")).longValue());
        assertEquals(4L, ((Number) ioTotals.get("chunkCount")).longValue());
        assertEquals(2L, ((Number) ioTotals.get("timeoutCount")).longValue());

        @SuppressWarnings("unchecked")
        Map<Integer, Long> statusCodes = (Map<Integer, Long>) ioTotals.get("statusCounts");
        assertEquals(Long.valueOf(2), statusCodes.get(200));
        assertEquals(Long.valueOf(1), statusCodes.get(504));
        assertEquals(Long.valueOf(1), statusCodes.get(429));
    }

    private AIReviewHistory createHistory(long prId,
                                          String projectKey,
                                          String repository,
                                          String metricsJson) {
        long startTime = 1_700_000_000_000L + prId;
        AIReviewHistory history = activeObjects.create(
                AIReviewHistory.class,
                new DBParam("PULL_REQUEST_ID", prId),
                new DBParam("PROJECT_KEY", projectKey),
                new DBParam("REPOSITORY_SLUG", repository),
                new DBParam("REVIEW_START_TIME", startTime),
                new DBParam("REVIEW_STATUS", "COMPLETED"));
        history.setModelUsed("llama3");
        history.setMetricsJson(LargeFieldCompression.compress(metricsJson));
        history.save();
        return history;
    }

    private void createChunk(AIReviewHistory history, String chunkId, int sequence) {
        createChunkWithTelemetry(history, chunkId, sequence, 0, 0, 0, false);
    }

    private AIReviewChunk createChunkWithTelemetry(AIReviewHistory history,
                                                   String chunkId,
                                                   int sequence,
                                                   long requestBytes,
                                                   long responseBytes,
                                                   int statusCode,
                                                   boolean timeout) {
        AIReviewChunk chunk = activeObjects.create(
                AIReviewChunk.class,
                new DBParam("HISTORY_ID", history.getID()),
                new DBParam("CHUNK_ID", chunkId));
        chunk.setHistory(history);
        chunk.setChunkId(chunkId);
        chunk.setSequence(sequence);
        chunk.setDurationMs(50L + sequence);
        chunk.setAttempts(1);
        chunk.setRetries(0);
        chunk.setSuccess(!timeout && statusCode >= 200 && statusCode < 400);
        chunk.setRequestBytes(requestBytes);
        chunk.setResponseBytes(responseBytes);
        chunk.setStatusCode(statusCode);
        chunk.setTimeout(timeout);
        chunk.save();
        assertEquals("chunk request persisted", requestBytes, chunk.getRequestBytes());
        return chunk;
    }

    private static final class TestActiveObjects extends EntityManagedActiveObjects {
        TestActiveObjects(EntityManager entityManager) {
            super(entityManager, new ImmediateTransactionManager(), DatabaseType.H2);
        }
    }

    private static final class ImmediateTransactionManager implements TransactionManager {
        @Override
        public <T> T doInTransaction(TransactionCallback<T> callback) {
            return callback.doInTransaction();
        }
    }
}
