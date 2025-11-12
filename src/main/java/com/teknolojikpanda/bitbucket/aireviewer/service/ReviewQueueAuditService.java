package com.teknolojikpanda.bitbucket.aireviewer.service;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.audit.api.AuditService;
import com.atlassian.audit.entity.AuditAttribute;
import com.atlassian.audit.entity.AuditEvent;
import com.atlassian.audit.entity.AuditResource;
import com.atlassian.audit.entity.CoverageArea;
import com.atlassian.audit.entity.CoverageLevel;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.teknolojikpanda.bitbucket.aireviewer.ao.AIReviewQueueAudit;
import net.java.ao.DBParam;
import net.java.ao.Query;
import java.util.Locale;
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
    private static final String AUDIT_CATEGORY = "AI Review Queue";

    private final ActiveObjects ao;
    private final AuditService auditService;

    @Inject
    public ReviewQueueAuditService(@ComponentImport ActiveObjects ao,
                                   @ComponentImport @Nullable AuditService auditService) {
        this.ao = Objects.requireNonNull(ao, "activeObjects");
        this.auditService = auditService;
    }

    public void recordAction(@Nullable ReviewConcurrencyController.QueueStats.QueueAction action) {
        if (action == null) {
            return;
        }
        final long timestamp = action.getTimestamp() > 0 ? action.getTimestamp() : System.currentTimeMillis();
        final String actionName = defaultString(sanitize(action.getAction(), 32), "unknown");
        try {
            ao.executeInTransaction(() -> {
                AIReviewQueueAudit entity = ao.create(AIReviewQueueAudit.class,
                        new DBParam("CREATED_AT", timestamp),
                        new DBParam("ACTION", actionName));
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
                emitAuditEntry(action);
                return null;
            });
        } catch (Exception ex) {
            log.warn("Failed to persist queue audit action {} for run {}: {}", action.getAction(), action.getRunId(), ex.getMessage());
        }
    }

    public void recordSchedulerStateChange(@Nullable ReviewSchedulerStateService.SchedulerState state) {
        if (state == null) {
            return;
        }
        String action = "scheduler-" + state.getMode().name().toLowerCase(Locale.ROOT);
        String actor = firstNonBlank(state.getUpdatedByDisplayName(), state.getUpdatedBy(), "system");
        String requestedBy = firstNonBlank(state.getUpdatedBy(), state.getUpdatedByDisplayName());
        String note = buildSchedulerNote(state);
        ReviewConcurrencyController.QueueStats.QueueAction queueAction =
                new ReviewConcurrencyController.QueueStats.QueueAction(
                        action,
                        System.currentTimeMillis(),
                        null,
                        null,
                        null,
                        -1,
                        false,
                        false,
                        false,
                        actor,
                        note,
                        requestedBy);
        recordAction(queueAction);
    }

    @Nonnull
    public List<ReviewConcurrencyController.QueueStats.QueueAction> listRecentActions(int limit) {
        int fetch = Math.min(Math.max(limit, 1), MAX_FETCH_LIMIT);
        return ao.executeInTransaction(() -> {
            AIReviewQueueAudit[] rows = ao.find(
                    AIReviewQueueAudit.class,
                    Query.select().order("CREATED_AT DESC").limit(fetch));
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
                        .order("CREATED_AT ASC")
                        .limit(toDelete));
        if (stale.length > 0) {
            ao.delete(stale);
        }
    }

    private ReviewConcurrencyController.QueueStats.QueueAction toAction(AIReviewQueueAudit entity) {
        return new ReviewConcurrencyController.QueueStats.QueueAction(
                entity.getAction(),
                entity.getCreatedAt(),
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

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return null;
    }

    private String defaultString(@Nullable String value, String fallback) {
        return value != null ? value : fallback;
    }

    private String buildSchedulerNote(ReviewSchedulerStateService.SchedulerState state) {
        StringBuilder builder = new StringBuilder("mode=").append(state.getMode().name());
        if (state.getReason() != null && !state.getReason().trim().isEmpty()) {
            builder.append(" reason=").append(state.getReason().trim());
        }
        return builder.toString();
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

    private void emitAuditEntry(@Nonnull ReviewConcurrencyController.QueueStats.QueueAction action) {
        if (auditService == null) {
            return;
        }
        try {
            String normalizedAction = normalizeAction(action.getAction());
            AuditEvent.Builder builder = AuditEvent.builder(
                            "ai.review.queue." + normalizedAction,
                            "AI review queue " + normalizedAction,
                            CoverageLevel.ADVANCED)
                    .category(AUDIT_CATEGORY)
                    .area(CoverageArea.LOCAL_CONFIG_AND_ADMINISTRATION);

            appendResources(builder, action);
            appendAttributes(builder, action);
            auditService.audit(builder.build());
        } catch (Exception ex) {
            log.debug("Unable to emit audit entry for queue action {}: {}", action.getAction(), ex.getMessage());
        }
    }

    private void appendResources(AuditEvent.Builder builder,
                                 ReviewConcurrencyController.QueueStats.QueueAction action) {
        List<AuditResource> resources = new ArrayList<>();
        if (action.getProjectKey() != null && action.getRepositorySlug() != null) {
            String repoName = action.getProjectKey() + "/" + action.getRepositorySlug();
            resources.add(AuditResource.builder(repoName, "repository")
                    .id(repoName)
                    .build());
        }
        if (action.getProjectKey() != null
                && action.getRepositorySlug() != null
                && action.getPullRequestId() >= 0) {
            String prName = action.getProjectKey() + "/" + action.getRepositorySlug() + "#" + action.getPullRequestId();
            resources.add(AuditResource.builder(prName, "pull-request")
                    .id(String.valueOf(action.getPullRequestId()))
                    .build());
        }
        if (!resources.isEmpty()) {
            builder.appendAffectedObjects(resources);
        }
    }

    private void appendAttributes(AuditEvent.Builder builder,
                                  ReviewConcurrencyController.QueueStats.QueueAction action) {
        addAttribute(builder, "runId", action.getRunId());
        addAttribute(builder, "requestedBy", action.getRequestedBy());
        addAttribute(builder, "actor", action.getActor());
        addAttribute(builder, "projectKey", action.getProjectKey());
        addAttribute(builder, "repositorySlug", action.getRepositorySlug());
        if (action.getPullRequestId() >= 0) {
            addAttribute(builder, "pullRequestId", String.valueOf(action.getPullRequestId()));
        }
        addAttribute(builder, "manual", String.valueOf(action.isManual()));
        addAttribute(builder, "update", String.valueOf(action.isUpdate()));
        addAttribute(builder, "force", String.valueOf(action.isForce()));
        addAttribute(builder, "note", action.getNote());
    }

    private void addAttribute(AuditEvent.Builder builder, String key, @Nullable String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        builder.extraAttribute(new AuditAttribute(key, value));
    }

    private String normalizeAction(@Nullable String action) {
        if (action == null || action.trim().isEmpty()) {
            return "event";
        }
        return action.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    }
}
