package com.teknolojikpanda.bitbucket.aireviewer.service;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.teknolojikpanda.bitbucket.aireviewer.ao.AIReviewCleanupAudit;
import com.teknolojikpanda.bitbucket.aireviewer.ao.AIReviewCleanupStatus;
import net.java.ao.Query;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Named
@Singleton
public class ReviewHistoryCleanupAuditService {

    private final ActiveObjects ao;

    @Inject
    public ReviewHistoryCleanupAuditService(@ComponentImport ActiveObjects ao) {
        this.ao = Objects.requireNonNull(ao, "activeObjects");
    }

    public void recordRun(long runTimestamp,
                          long durationMs,
                          int deletedHistories,
                          int deletedChunks,
                          boolean manual,
                          String actorUserKey,
                          String actorDisplayName) {
        ao.executeInTransaction(() -> {
            AIReviewCleanupAudit audit = ao.create(AIReviewCleanupAudit.class);
            audit.setRunTimestamp(runTimestamp);
            audit.setDurationMs(Math.max(0, durationMs));
            audit.setDeletedHistories(deletedHistories);
            audit.setDeletedChunks(deletedChunks);
            audit.setManual(manual);
            audit.setSuccess(true);
            audit.setActorUserKey(actorUserKey);
            audit.setActorDisplayName(actorDisplayName);
            audit.save();
            return null;
        });
    }

    public void recordFailure(long runTimestamp,
                              boolean manual,
                              String actorUserKey,
                              String actorDisplayName,
                              String errorMessage) {
        ao.executeInTransaction(() -> {
            AIReviewCleanupAudit audit = ao.create(AIReviewCleanupAudit.class);
            audit.setRunTimestamp(runTimestamp);
            audit.setDurationMs(0);
            audit.setDeletedHistories(0);
            audit.setDeletedChunks(0);
            audit.setManual(manual);
            audit.setSuccess(false);
            audit.setActorUserKey(actorUserKey);
            audit.setActorDisplayName(actorDisplayName);
            audit.setErrorMessage(trim(errorMessage));
            audit.save();
            return null;
        });
    }

    public List<Map<String, Object>> listRecent(int limit) {
        int rowLimit = limit <= 0 ? 10 : limit;
        AIReviewCleanupAudit[] rows = ao.executeInTransaction(() ->
                ao.find(AIReviewCleanupAudit.class,
                        Query.select().order("RUN_TIMESTAMP DESC").limit(rowLimit)));
        if (rows.length == 0) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> list = new ArrayList<>(rows.length);
        for (AIReviewCleanupAudit audit : rows) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("runTimestamp", audit.getRunTimestamp());
            map.put("durationMs", audit.getDurationMs());
            map.put("deletedHistories", audit.getDeletedHistories());
            map.put("deletedChunks", audit.getDeletedChunks());
            map.put("manual", audit.isManual());
            map.put("success", audit.isSuccess());
            map.put("actorUserKey", audit.getActorUserKey());
            map.put("actorDisplayName", audit.getActorDisplayName());
            map.put("errorMessage", audit.getErrorMessage());
            list.add(map);
        }
        return list;
    }

    private String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.substring(0, Math.min(trimmed.length(), 4000));
    }
}
