package com.teknolojikpanda.bitbucket.aireviewer.service;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.teknolojikpanda.bitbucket.aireviewer.ao.AIReviewQueueAudit;
import net.java.ao.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Persists queue management actions so administrators have an audit trail for overrides.
 */
@Named
@Singleton
public class ReviewQueueAuditService {

    private static final Logger log = LoggerFactory.getLogger(ReviewQueueAuditService.class);
    private static final int DEFAULT_FETCH_LIMIT = 100;
    private static final int MAX_FETCH_LIMIT = 500;
    private static final int MAX_ROWS = 2000;

    private final ActiveObjects ao;

    @Inject
    public ReviewQueueAuditService(@ComponentImport ActiveObjects ao) {
        this.ao = Objects.requireNonNull(ao, "activeObjects");
    }

    public void recordAction(@Nullable ReviewConcurrencyController.QueueStats.QueueAction action) {
        if (action == null) {
            return;
        }
        try {
            ao.executeInTransaction(() -> {
                AIReviewQueueAudit entity = ao.create(AIReviewQueueAudit.class);
                entity.setTimestamp(action.getTimestamp());
                entity.setAction(sanitize(action.getAction(), 32));
                entity.setRunId(sanitize(action.getRunId(), 255));
                entity.setProjectKey(sanitize(action.getProjectKey(), 64));
                entity.setRepositorySlug(sanitize(action.getRepositorySlug(), 128));
                entity.setPullRequestId(action.getPullRequestId());
                entity.setManual(action.isManual());
                entity.setUpdate(action.isUpdate());
                entity.setForce(action.isForce());
                entity.setActor(sanitize(action.getActor(), 255));
                entity.setNote(sanitize(action.getNote(), 2000));
                entity.setRequestedBy(sanitize(action.getRequestedBy(), 255));
                entity.save();
                trimExcessRows(MAX_ROWS);
                return null;
            });
        } catch (Exception ex) {
            log.warn("Failed to persist queue audit action {} for run {}: {}", action.getAction(), action.getRunId(), ex.getMessage());
        }
    }

    @Nonnull
    public List<ReviewConcurrencyController.QueueStats.QueueAction> listRecentActions(int limit) {
        int fetch = Math.min(Math.max(limit, 1), MAX_FETCH_LIMIT);
        return ao.executeInTransaction(() -> {
            AIReviewQueueAudit[] rows = ao.find(
                    AIReviewQueueAudit.class,
                    Query.select().order("TIMESTAMP DESC").limit(fetch));
            if (rows.length == 0) {
                return List.of();
            }
            List<ReviewConcurrencyController.QueueStats.QueueAction> actions = new ArrayList<>(rows.length);
            for (AIReviewQueueAudit row : rows) {
                actions.add(toAction(row));
            }
            actions.sort(Comparator.comparingLong(ReviewConcurrencyController.QueueStats.QueueAction::getTimestamp).reversed());
            return actions;
        });
    }

    private void trimExcessRows(int maxRows) {
        int target = Math.max(maxRows, 0);
        int total = ao.count(AIReviewQueueAudit.class);
        if (total <= target) {
            return;
        }
        int toDelete = total - target;
        AIReviewQueueAudit[] stale = ao.find(
                AIReviewQueueAudit.class,
                Query.select()
                        .order("TIMESTAMP ASC")
                        .limit(toDelete));
        if (stale.length > 0) {
            ao.delete(stale);
        }
    }

    private ReviewConcurrencyController.QueueStats.QueueAction toAction(AIReviewQueueAudit entity) {
        return new ReviewConcurrencyController.QueueStats.QueueAction(
                entity.getAction(),
                entity.getTimestamp(),
                entity.getRunId(),
                entity.getProjectKey(),
                entity.getRepositorySlug(),
                entity.getPullRequestId(),
                entity.isManual(),
                entity.isUpdate(),
                entity.isForce(),
                entity.getActor(),
                entity.getNote(),
                entity.getRequestedBy());
    }

    @Nullable
    private String sanitize(@Nullable String value, int maxLen) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() <= maxLen) {
            return trimmed;
        }
        return trimmed.substring(0, maxLen);
    }

    @Nonnull
    public List<ReviewConcurrencyController.QueueStats.QueueAction> listRecentActions() {
        return listRecentActions(DEFAULT_FETCH_LIMIT);
    }
}
