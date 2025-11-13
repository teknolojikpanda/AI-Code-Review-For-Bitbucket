package com.teknolojikpanda.bitbucket.aireviewer.service;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.features.DarkFeatureManager;
import com.teknolojikpanda.bitbucket.aireviewer.ao.AIReviewRolloutCohort;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages rollout cohorts so guardrails can be staged per customer group with dark feature toggles.
 */
@Named
@Singleton
public class GuardrailsRolloutService {

    private final ActiveObjects ao;
    private final DarkFeatureManager darkFeatureManager;
    private final ConcurrentHashMap<String, CohortTelemetry> telemetry = new ConcurrentHashMap<>();
    private volatile List<CohortRecord> cachedCohorts = Collections.emptyList();

    @Inject
    public GuardrailsRolloutService(@ComponentImport ActiveObjects ao,
                                    @ComponentImport DarkFeatureManager darkFeatureManager) {
        this.ao = Objects.requireNonNull(ao, "activeObjects");
        this.darkFeatureManager = Objects.requireNonNull(darkFeatureManager, "darkFeatureManager");
    }

    @Nonnull
    public synchronized CohortRecord createCohort(@Nonnull CohortMutation mutation) {
        Objects.requireNonNull(mutation, "mutation");
        CohortMutation sanitized = mutation.sanitized();
        ensureKeyAvailable(sanitized.key, -1);
        long now = System.currentTimeMillis();
        AIReviewRolloutCohort entity = ao.create(AIReviewRolloutCohort.class);
        applyMutation(entity, sanitized, now);
        entity.save();
        refreshCache();
        return toRecord(entity);
    }

    @Nonnull
    public synchronized CohortRecord updateCohort(int id, @Nonnull CohortMutation mutation) {
        Objects.requireNonNull(mutation, "mutation");
        AIReviewRolloutCohort entity = Objects.requireNonNull(ao.get(AIReviewRolloutCohort.class, id), "cohort");
        CohortMutation sanitized = mutation.sanitized();
        ensureKeyAvailable(sanitized.key, id);
        long now = System.currentTimeMillis();
        applyMutation(entity, sanitized, now);
        entity.save();
        refreshCache();
        return toRecord(entity);
    }

    public synchronized boolean deleteCohort(int id) {
        AIReviewRolloutCohort entity = ao.get(AIReviewRolloutCohort.class, id);
        if (entity == null) {
            return false;
        }
        ao.delete(entity);
        refreshCache();
        return true;
    }

    @Nonnull
    public List<CohortRecord> listCohorts() {
        return new ArrayList<>(getCachedCohorts());
    }

    @Nonnull
    public Evaluation evaluate(@Nullable String projectKey,
                               @Nullable String repositorySlug,
                               @Nullable String runId) {
        List<CohortRecord> cohorts = getCachedCohorts();
        boolean hasCohorts = !cohorts.isEmpty();
        CohortRecord matched = findMatchingCohort(cohorts, projectKey, repositorySlug);
        if (matched == null) {
            boolean enabled = !hasCohorts;
            Evaluation evaluation = new Evaluation(RolloutMode.FALLBACK, null, enabled,
                    enabled ? "default-enforced" : "default-shadow");
            recordTelemetry(evaluation);
            return evaluation;
        }
        boolean darkEnabled = isDarkFeatureEnabled(matched.getDarkFeatureKey());
        boolean sampledIn = isSampledIn(matched.getRolloutPercent(), matched.getCohortKey(), runId);
        boolean enforcing = matched.isEnabled() && darkEnabled && sampledIn;
        String reason;
        if (!matched.isEnabled()) {
            reason = "cohort-disabled";
        } else if (!darkEnabled) {
            reason = "dark-feature-disabled";
        } else if (!sampledIn) {
            reason = "sampled-out";
        } else {
            reason = "cohort-enforced";
        }
        RolloutMode mode = enforcing ? RolloutMode.ENFORCED : RolloutMode.SHADOW;
        Evaluation evaluation = new Evaluation(mode, matched, enforcing, reason);
        recordTelemetry(evaluation);
        return evaluation;
    }

    public void recordCompletion(@Nullable String cohortKey,
                                 @Nullable RolloutMode mode,
                                 @Nullable Object status) {
        if (cohortKey == null) {
            return;
        }
        CohortTelemetry telem = telemetry.computeIfAbsent(cohortKey, CohortTelemetry::new);
        telem.recordCompletion(mode);
    }

    @Nonnull
    public Map<String, Object> describeTelemetry() {
        List<CohortRecord> records = getCachedCohorts();
        List<Map<String, Object>> cohortStates = new ArrayList<>(records.size());
        for (CohortRecord record : records) {
            CohortTelemetry telem = telemetry.get(record.getCohortKey());
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("id", record.getId());
            state.put("key", record.getCohortKey());
            state.put("displayName", record.getDisplayName());
            state.put("scopeMode", record.getScopeMode().name().toLowerCase(Locale.ROOT));
            state.put("projectKey", record.getProjectKey());
            state.put("repositorySlug", record.getRepositorySlug());
            state.put("enabled", record.isEnabled());
            state.put("rolloutPercent", record.getRolloutPercent());
            state.put("darkFeatureKey", record.getDarkFeatureKey());
            state.put("metrics", telem != null ? telem.toMap() : Map.of());
            cohortStates.add(state);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cohorts", cohortStates);
        payload.put("defaultMode", records.isEmpty() ? "enforced" : "shadow");
        return payload;
    }

    private boolean isDarkFeatureEnabled(@Nullable String featureKey) {
        if (featureKey == null || featureKey.trim().isEmpty()) {
            return true;
        }
        try {
            return darkFeatureManager.isFeatureEnabledForAllUsers(featureKey.trim());
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean isSampledIn(int percent, @Nullable String cohortKey, @Nullable String runId) {
        int clamped = Math.max(0, Math.min(percent, 100));
        if (clamped >= 100) {
            return true;
        }
        if (clamped <= 0) {
            return false;
        }
        String seed = (cohortKey != null ? cohortKey : "cohort") + ":" + (runId != null ? runId : "run");
        int bucket = Math.abs(seed.hashCode()) % 100;
        return bucket < clamped;
    }

    private List<CohortRecord> getCachedCohorts() {
        List<CohortRecord> snapshot = cachedCohorts;
        if (snapshot == null || snapshot.isEmpty()) {
            return refreshCache();
        }
        return snapshot;
    }

    private synchronized List<CohortRecord> refreshCache() {
        AIReviewRolloutCohort[] rows = ao.find(AIReviewRolloutCohort.class);
        if (rows.length == 0) {
            cachedCohorts = Collections.emptyList();
            return cachedCohorts;
        }
        List<CohortRecord> records = new ArrayList<>(rows.length);
        for (AIReviewRolloutCohort row : rows) {
            records.add(toRecord(row));
        }
        records.sort(Comparator
                .comparingInt((CohortRecord r) -> r.getScopeMode().priority)
                .thenComparing(CohortRecord::getCohortKey));
        cachedCohorts = Collections.unmodifiableList(records);
        return cachedCohorts;
    }

    private CohortRecord toRecord(AIReviewRolloutCohort entity) {
        ScopeMode scope = ScopeMode.fromString(entity.getScopeMode());
        return new CohortRecord(
                entity.getID(),
                entity.getCohortKey(),
                entity.getDisplayName(),
                entity.getDescription(),
                scope,
                entity.getProjectKey(),
                entity.getRepositorySlug(),
                entity.getRolloutPercent(),
                entity.getDarkFeatureKey(),
                entity.isEnabled(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getUpdatedBy());
    }

    private void applyMutation(AIReviewRolloutCohort entity,
                               CohortMutation mutation,
                               long timestamp) {
        entity.setCohortKey(mutation.key);
        entity.setDisplayName(mutation.displayName);
        entity.setDescription(mutation.description);
        entity.setScopeMode(mutation.scopeMode.name());
        entity.setProjectKey(mutation.projectKey);
        entity.setRepositorySlug(mutation.repositorySlug);
        entity.setRolloutPercent(mutation.rolloutPercent);
        entity.setDarkFeatureKey(mutation.darkFeatureKey);
        entity.setEnabled(mutation.enabled);
        if (entity.getCreatedAt() <= 0L) {
            entity.setCreatedAt(timestamp);
        }
        entity.setUpdatedAt(timestamp);
        entity.setUpdatedBy(mutation.updatedBy);
    }

    private CohortRecord findMatchingCohort(List<CohortRecord> cohorts,
                                            @Nullable String projectKey,
                                            @Nullable String repositorySlug) {
        CohortRecord global = null;
        CohortRecord projectMatch = null;
        CohortRecord repoMatch = null;
        for (CohortRecord cohort : cohorts) {
            switch (cohort.getScopeMode()) {
                case GLOBAL:
                    if (global == null) {
                        global = cohort;
                    }
                    break;
                case PROJECT:
                    if (projectMatch == null && cohort.matchesProject(projectKey)) {
                        projectMatch = cohort;
                    }
                    break;
                case REPOSITORY:
                    if (cohort.matchesRepository(projectKey, repositorySlug)) {
                        repoMatch = cohort;
                        break;
                    }
                    break;
            }
            if (repoMatch != null) {
                break;
            }
        }
        if (repoMatch != null) {
            return repoMatch;
        }
        if (projectMatch != null) {
            return projectMatch;
        }
        return global;
    }

    private void ensureKeyAvailable(String key, int currentId) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("cohortKey is required");
        }
        AIReviewRolloutCohort[] rows = ao.find(
                AIReviewRolloutCohort.class,
                "COHORT_KEY = ?",
                key);
        for (AIReviewRolloutCohort row : rows) {
            if (row.getID() != currentId) {
                throw new IllegalArgumentException("Duplicate cohort key: " + key);
            }
        }
    }

    private void recordTelemetry(Evaluation evaluation) {
        String key = evaluation.getCohortKey();
        if (key == null) {
            return;
        }
        CohortTelemetry telem = telemetry.computeIfAbsent(key, CohortTelemetry::new);
        telem.recordEvaluation(evaluation.getMode());
    }

    private static final class CohortTelemetry {
        private final String key;
        private final AtomicLong enforced = new AtomicLong();
        private final AtomicLong shadow = new AtomicLong();
        private final AtomicLong fallback = new AtomicLong();
        private final AtomicLong completions = new AtomicLong();
        private final AtomicLong lastEvaluationAt = new AtomicLong();

        private CohortTelemetry(String key) {
            this.key = key;
        }

        void recordEvaluation(RolloutMode mode) {
            lastEvaluationAt.set(System.currentTimeMillis());
            switch (mode) {
                case ENFORCED:
                    enforced.incrementAndGet();
                    break;
                case SHADOW:
                    shadow.incrementAndGet();
                    break;
                default:
                    fallback.incrementAndGet();
            }
        }

        void recordCompletion(@Nullable RolloutMode mode) {
            completions.incrementAndGet();
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("startedEnforced", enforced.get());
            map.put("startedShadow", shadow.get());
            map.put("startedFallback", fallback.get());
            map.put("completed", completions.get());
            map.put("lastEvaluationAt", lastEvaluationAt.get());
            return map;
        }
    }

    public enum RolloutMode {
        ENFORCED,
        SHADOW,
        FALLBACK
    }

    public enum ScopeMode {
        GLOBAL(3),
        PROJECT(2),
        REPOSITORY(1);

        private final int priority;

        ScopeMode(int priority) {
            this.priority = priority;
        }

        public static ScopeMode fromString(@Nullable String raw) {
            if (raw == null) {
                return GLOBAL;
            }
            try {
                return ScopeMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (Exception ex) {
                return GLOBAL;
            }
        }
    }

    public static final class CohortRecord {
        private final int id;
        private final String cohortKey;
        private final String displayName;
        private final String description;
        private final ScopeMode scopeMode;
        private final String projectKey;
        private final String repositorySlug;
        private final int rolloutPercent;
        private final String darkFeatureKey;
        private final boolean enabled;
        private final long createdAt;
        private final long updatedAt;
        private final String updatedBy;

        CohortRecord(int id,
                     String cohortKey,
                     String displayName,
                     String description,
                     ScopeMode scopeMode,
                     String projectKey,
                     String repositorySlug,
                     int rolloutPercent,
                     String darkFeatureKey,
                     boolean enabled,
                     long createdAt,
                     long updatedAt,
                     String updatedBy) {
            this.id = id;
            this.cohortKey = cohortKey;
            this.displayName = displayName;
            this.description = description;
            this.scopeMode = scopeMode;
            this.projectKey = projectKey;
            this.repositorySlug = repositorySlug;
            this.rolloutPercent = rolloutPercent;
            this.darkFeatureKey = darkFeatureKey;
            this.enabled = enabled;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.updatedBy = updatedBy;
        }

        public int getId() {
            return id;
        }

        public String getCohortKey() {
            return cohortKey;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getDescription() {
            return description;
        }

        public ScopeMode getScopeMode() {
            return scopeMode;
        }

        public String getProjectKey() {
            return projectKey;
        }

        public String getRepositorySlug() {
            return repositorySlug;
        }

        public int getRolloutPercent() {
            return rolloutPercent;
        }

        public String getDarkFeatureKey() {
            return darkFeatureKey;
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

        public String getUpdatedBy() {
            return updatedBy;
        }

        boolean matchesProject(@Nullable String projectKey) {
            return projectKey != null && projectKey.equalsIgnoreCase(this.projectKey);
        }

        boolean matchesRepository(@Nullable String projectKey, @Nullable String repositorySlug) {
            return projectKey != null
                    && repositorySlug != null
                    && projectKey.equalsIgnoreCase(this.projectKey)
                    && repositorySlug.equalsIgnoreCase(this.repositorySlug);
        }
    }

    public static final class Evaluation {
        private final RolloutMode mode;
        @Nullable
        private final CohortRecord cohort;
        private final boolean guardrailsEnabled;
        private final String reason;

        Evaluation(@Nonnull RolloutMode mode,
                   @Nullable CohortRecord cohort,
                   boolean guardrailsEnabled,
                   @Nonnull String reason) {
            this.mode = Objects.requireNonNull(mode, "mode");
            this.cohort = cohort;
            this.guardrailsEnabled = guardrailsEnabled;
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        public RolloutMode getMode() {
            return mode;
        }

        @Nullable
        public CohortRecord getCohort() {
            return cohort;
        }

        @Nullable
        public String getCohortKey() {
            return cohort != null ? cohort.getCohortKey() : null;
        }

        public boolean isGuardrailsEnabled() {
            return guardrailsEnabled;
        }

        public String getReason() {
            return reason;
        }
    }

    public static final class CohortMutation {
        private final String key;
        private final String displayName;
        private final String description;
        private final ScopeMode scopeMode;
        private final String projectKey;
        private final String repositorySlug;
        private final int rolloutPercent;
        private final String darkFeatureKey;
        private final boolean enabled;
        private final String updatedBy;

        public CohortMutation(String key,
                              String displayName,
                              String description,
                              ScopeMode scopeMode,
                              String projectKey,
                              String repositorySlug,
                              int rolloutPercent,
                              String darkFeatureKey,
                              boolean enabled,
                              String updatedBy) {
            this.key = key;
            this.displayName = displayName;
            this.description = description;
            this.scopeMode = scopeMode;
            this.projectKey = projectKey;
            this.repositorySlug = repositorySlug;
            this.rolloutPercent = rolloutPercent;
            this.darkFeatureKey = darkFeatureKey;
            this.enabled = enabled;
            this.updatedBy = updatedBy;
        }

        CohortMutation sanitized() {
            ScopeMode scope = scopeMode != null ? scopeMode : ScopeMode.GLOBAL;
            int percent = Math.max(0, Math.min(rolloutPercent, 100));
            String sanitizedKey = sanitizeKey(key);
            String sanitizedDisplay = safeTrim(displayName);
            String sanitizedDesc = safeTrim(description);
            String sanitizedProject = safeTrim(projectKey);
            String sanitizedRepo = safeTrim(repositorySlug);
            String sanitizedDark = safeTrim(darkFeatureKey);
            if (scope == ScopeMode.PROJECT && sanitizedProject == null) {
                throw new IllegalArgumentException("projectKey is required for project cohorts");
            }
            if (scope == ScopeMode.REPOSITORY && (sanitizedProject == null || sanitizedRepo == null)) {
                throw new IllegalArgumentException("projectKey and repositorySlug are required for repository cohorts");
            }
            return new CohortMutation(
                    sanitizedKey,
                    sanitizedDisplay,
                    sanitizedDesc,
                    scope,
                    sanitizedProject,
                    sanitizedRepo,
                    percent,
                    sanitizedDark,
                    enabled,
                    safeTrim(updatedBy));
        }

        private String sanitizeKey(String value) {
            if (value == null) {
                throw new IllegalArgumentException("cohort key is required");
            }
            String trimmed = value.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("cohort key is required");
            }
            if (!trimmed.matches("^[a-zA-Z0-9._-]{2,50}$")) {
                throw new IllegalArgumentException("cohort key may only contain alphanumerics, '.', '-', '_'");
            }
            return trimmed.toLowerCase(Locale.ROOT);
        }

        private String safeTrim(String value) {
            if (value == null) {
                return null;
            }
            String trimmed = value.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
    }
}
