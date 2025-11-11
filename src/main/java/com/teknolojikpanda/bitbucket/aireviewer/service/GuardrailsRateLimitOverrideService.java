package com.teknolojikpanda.bitbucket.aireviewer.service;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.teknolojikpanda.bitbucket.aireviewer.ao.GuardrailsRateOverride;
import net.java.ao.DBParam;
import net.java.ao.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

@Named
@Singleton
public class GuardrailsRateLimitOverrideService {

    private static final Logger log = LoggerFactory.getLogger(GuardrailsRateLimitOverrideService.class);

    private final ActiveObjects ao;

    @Inject
    public GuardrailsRateLimitOverrideService(@ComponentImport ActiveObjects ao) {
        this.ao = Objects.requireNonNull(ao, "activeObjects");
    }

    public OverrideRecord upsertOverride(GuardrailsRateLimitScope scope,
                                         @Nullable String identifier,
                                         int limitPerHour,
                                         long expiresAt,
                                         @Nullable String reason,
                                         @Nullable String createdBy,
                                         @Nullable String createdByDisplayName) {
        Objects.requireNonNull(scope, "scope");
        final String normalized = normalizeIdentifier(scope, identifier);
        final String scopeKey = scopeKey(scope, normalized);
        final int sanitizedLimit = Math.max(0, limitPerHour);
        final long sanitizedExpires = Math.max(0L, expiresAt);
        final long now = System.currentTimeMillis();

        return ao.executeInTransaction(() -> {
            GuardrailsRateOverride row = findRow(scopeKey);
            if (row == null) {
                try {
                    row = ao.create(GuardrailsRateOverride.class,
                            new DBParam("SCOPE_KEY", scopeKey),
                            new DBParam("SCOPE", scope.name()),
                            new DBParam("IDENTIFIER", normalized),
                            new DBParam("LIMIT_PER_HOUR", sanitizedLimit),
                            new DBParam("CREATED_AT", now),
                            new DBParam("EXPIRES_AT", sanitizedExpires),
                            new DBParam("CREATED_BY", sanitize(createdBy)),
                            new DBParam("CREATED_BY_DISPLAY_NAME", sanitize(createdByDisplayName)),
                            new DBParam("REASON", sanitize(reason)));
                } catch (RuntimeException ex) {
                    GuardrailsRateOverride retry = findRow(scopeKey);
                    if (retry != null) {
                        row = retry;
                    } else {
                        throw ex;
                    }
                }
            }
            if (row != null) {
                row.setScope(scope.name());
                row.setIdentifier(normalized);
                row.setLimitPerHour(sanitizedLimit);
                row.setCreatedAt(now);
                row.setExpiresAt(sanitizedExpires);
                row.setCreatedBy(sanitize(createdBy));
                row.setCreatedByDisplayName(sanitize(createdByDisplayName));
                row.setReason(sanitize(reason));
                row.save();
            }
            log.info("Configured rate-limit override scope={} identifier={} limit={} expiresAt={}",
                    scope, normalized != null ? normalized : "*", sanitizedLimit, sanitizedExpires);
            return toRecord(row);
        });
    }

    public void deleteOverride(int id) {
        ao.executeInTransaction(() -> {
            GuardrailsRateOverride row = ao.get(GuardrailsRateOverride.class, id);
            if (row != null) {
                log.info("Deleted rate-limit override {}", id);
                ao.delete(row);
            }
            return null;
        });
    }

    public List<OverrideRecord> listOverrides(boolean includeExpired) {
        long now = System.currentTimeMillis();
        return ao.executeInTransaction(() -> {
            Query query = Query.select().order("CREATED_AT DESC");
            if (!includeExpired) {
                query = query.where("EXPIRES_AT = 0 OR EXPIRES_AT >= ?", now);
            }
            GuardrailsRateOverride[] rows = ao.find(GuardrailsRateOverride.class, query);
            if (rows.length == 0) {
                return Collections.emptyList();
            }
            List<OverrideRecord> result = new ArrayList<>(rows.length);
            for (GuardrailsRateOverride row : rows) {
                if (includeExpired || !isExpired(row, now)) {
                    result.add(toRecord(row));
                }
            }
            return result;
        });
    }

    public int resolveRepoLimit(@Nullable String projectKey,
                                @Nullable String repositorySlug,
                                int defaultLimit) {
        long now = System.currentTimeMillis();
        Optional<OverrideRecord> repoOverride = findActiveOverride(GuardrailsRateLimitScope.REPOSITORY, repositorySlug, now);
        if (repoOverride.isPresent()) {
            return repoOverride.get().getLimitPerHour();
        }
        Optional<OverrideRecord> projectOverride = findActiveOverride(GuardrailsRateLimitScope.PROJECT, projectKey, now);
        if (projectOverride.isPresent()) {
            return projectOverride.get().getLimitPerHour();
        }
        Optional<OverrideRecord> globalOverride = findActiveOverride(GuardrailsRateLimitScope.GLOBAL, null, now);
        return globalOverride.map(OverrideRecord::getLimitPerHour).orElse(defaultLimit);
    }

    public int resolveProjectLimit(@Nullable String projectKey, int defaultLimit) {
        long now = System.currentTimeMillis();
        Optional<OverrideRecord> projectOverride = findActiveOverride(GuardrailsRateLimitScope.PROJECT, projectKey, now);
        if (projectOverride.isPresent()) {
            return projectOverride.get().getLimitPerHour();
        }
        Optional<OverrideRecord> globalOverride = findActiveOverride(GuardrailsRateLimitScope.GLOBAL, null, now);
        return globalOverride.map(OverrideRecord::getLimitPerHour).orElse(defaultLimit);
    }

    public Optional<OverrideRecord> getOverride(int id) {
        long now = System.currentTimeMillis();
        return ao.executeInTransaction(() -> {
            GuardrailsRateOverride row = ao.get(GuardrailsRateOverride.class, id);
            if (row == null || isExpired(row, now)) {
                if (row != null && isExpired(row, now)) {
                    ao.delete(row);
                }
                return Optional.empty();
            }
            return Optional.of(toRecord(row));
        });
    }

    private Optional<OverrideRecord> findActiveOverride(GuardrailsRateLimitScope scope,
                                                        @Nullable String identifier,
                                                        long now) {
        if (scope != GuardrailsRateLimitScope.GLOBAL &&
                (identifier == null || identifier.trim().isEmpty())) {
            return Optional.empty();
        }
        String normalized = normalizeIdentifier(scope, identifier);
        GuardrailsRateLimitScope effectiveScope = scope;
        if (scope == GuardrailsRateLimitScope.GLOBAL) {
            normalized = "*";
        }
        String key = scopeKey(effectiveScope, normalized);
        return ao.executeInTransaction(() -> {
            GuardrailsRateOverride row = findRow(key);
            if (row == null) {
                return Optional.empty();
            }
            if (isExpired(row, now)) {
                ao.delete(row);
                return Optional.empty();
            }
            return Optional.of(toRecord(row));
        });
    }

    private GuardrailsRateOverride findRow(String scopeKey) {
        GuardrailsRateOverride[] rows = ao.find(GuardrailsRateOverride.class,
                Query.select().where("SCOPE_KEY = ?", scopeKey));
        return rows.length > 0 ? rows[0] : null;
    }

    private boolean isExpired(GuardrailsRateOverride row, long now) {
        long expiresAt = row.getExpiresAt();
        return expiresAt > 0 && expiresAt < now;
    }

    private OverrideRecord toRecord(GuardrailsRateOverride row) {
        GuardrailsRateLimitScope scope = GuardrailsRateLimitScope.fromString(row.getScope());
        return new OverrideRecord(
                row.getID(),
                scope,
                row.getIdentifier(),
                row.getLimitPerHour(),
                row.getCreatedAt(),
                row.getExpiresAt(),
                row.getCreatedBy(),
                row.getCreatedByDisplayName(),
                row.getReason());
    }

    private String scopeKey(GuardrailsRateLimitScope scope, @Nullable String identifier) {
        return scope.name() + ":" + (identifier != null ? identifier : "*");
    }

    private String normalizeIdentifier(GuardrailsRateLimitScope scope, @Nullable String identifier) {
        if (scope == GuardrailsRateLimitScope.GLOBAL) {
            return "*";
        }
        if (identifier == null) {
            return null;
        }
        return identifier.trim().toLowerCase(Locale.ROOT);
    }

    private String sanitize(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static final class OverrideRecord {
        private final int id;
        private final GuardrailsRateLimitScope scope;
        private final String identifier;
        private final int limitPerHour;
        private final long createdAt;
        private final long expiresAt;
        private final String createdBy;
        private final String createdByDisplayName;
        private final String reason;

        public OverrideRecord(int id,
                              GuardrailsRateLimitScope scope,
                              String identifier,
                              int limitPerHour,
                              long createdAt,
                              long expiresAt,
                              String createdBy,
                              String createdByDisplayName,
                              String reason) {
            this.id = id;
            this.scope = scope;
            this.identifier = identifier;
            this.limitPerHour = limitPerHour;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
            this.createdBy = createdBy;
            this.createdByDisplayName = createdByDisplayName;
            this.reason = reason;
        }

        public int getId() {
            return id;
        }

        public GuardrailsRateLimitScope getScope() {
            return scope;
        }

        public String getIdentifier() {
            return identifier;
        }

        public int getLimitPerHour() {
            return limitPerHour;
        }

        public long getCreatedAt() {
            return createdAt;
        }

        public long getExpiresAt() {
            return expiresAt;
        }

        public String getCreatedBy() {
            return createdBy;
        }

        public String getCreatedByDisplayName() {
            return createdByDisplayName;
        }

        public String getReason() {
            return reason;
        }

        public boolean isExpired(long now) {
            return expiresAt > 0 && expiresAt < now;
        }
    }
}
