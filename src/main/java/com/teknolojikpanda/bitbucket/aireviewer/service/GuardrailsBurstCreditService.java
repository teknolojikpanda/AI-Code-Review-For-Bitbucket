package com.teknolojikpanda.bitbucket.aireviewer.service;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.teknolojikpanda.bitbucket.aireviewer.ao.GuardrailsBurstCredit;
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
import java.util.concurrent.TimeUnit;

@Named
@Singleton
public class GuardrailsBurstCreditService {

    private static final Logger log = LoggerFactory.getLogger(GuardrailsBurstCreditService.class);
    private static final int MAX_FETCH = 500;

    private final ActiveObjects ao;

    @Inject
    public GuardrailsBurstCreditService(@ComponentImport ActiveObjects ao) {
        this.ao = Objects.requireNonNull(ao, "activeObjects");
    }

    public BurstCredit grantCredit(GuardrailsRateLimitScope scope,
                                   String identifier,
                                   int tokens,
                                   long durationMinutes,
                                   @Nullable String reason,
                                   @Nullable String note,
                                   @Nullable String createdBy,
                                   @Nullable String createdByDisplayName) {
        Objects.requireNonNull(scope, "scope");
        String normalizedIdentifier = normalizeIdentifier(scope, identifier);
        int sanitizedTokens = Math.max(1, tokens);
        long durationMs = Math.max(TimeUnit.MINUTES.toMillis(1), TimeUnit.MINUTES.toMillis(Math.max(1, durationMinutes)));
        long now = System.currentTimeMillis();
        long expiresAt = now + durationMs;
        GuardrailsBurstCredit credit = ao.executeInTransaction(() -> {
            GuardrailsBurstCredit entity = ao.create(GuardrailsBurstCredit.class);
            entity.setScope(scope.name());
            entity.setIdentifier(normalizedIdentifier);
            entity.setTokensGranted(sanitizedTokens);
            entity.setTokensConsumed(0);
            entity.setCreatedAt(now);
            entity.setExpiresAt(expiresAt);
            entity.setActive(true);
            entity.setReason(sanitize(reason));
            entity.setNote(sanitize(note));
            entity.setCreatedBy(sanitize(createdBy));
            entity.setCreatedByDisplayName(sanitize(createdByDisplayName));
            entity.save();
            return entity;
        });
        log.info("Granted burst credit scope={} identifier={} tokens={} expiresAt={}",
                scope, normalizedIdentifier, sanitizedTokens, expiresAt);
        return toValue(credit);
    }

    public boolean consumeCredit(GuardrailsRateLimitScope scope, @Nullable String identifier) {
        if (scope == null || identifier == null || identifier.trim().isEmpty()) {
            return false;
        }
        String normalizedIdentifier = normalizeIdentifier(scope, identifier);
        return ao.executeInTransaction(() -> {
            long now = System.currentTimeMillis();
            GuardrailsBurstCredit[] credits = ao.find(
                    GuardrailsBurstCredit.class,
                    "SCOPE = ? AND IDENTIFIER = ? AND ACTIVE = ? AND EXPIRES_AT > ? AND TOKENS_CONSUMED < TOKENS_GRANTED",
                    scope.name(),
                    normalizedIdentifier,
                    true,
                    now);
            if (credits.length == 0) {
                deactivateExpired(scope, normalizedIdentifier, now);
                return false;
            }
            GuardrailsBurstCredit credit = selectCredit(credits);
            credit.setTokensConsumed(credit.getTokensConsumed() + 1);
            credit.setLastConsumedAt(now);
            if (credit.getTokensConsumed() >= credit.getTokensGranted()) {
                credit.setActive(false);
            }
            credit.save();
            return true;
        });
    }

    public List<BurstCredit> listCredits(boolean includeExpired) {
        long now = System.currentTimeMillis();
        return ao.executeInTransaction(() -> {
            String clause = includeExpired ? "" : " WHERE ACTIVE = ? AND EXPIRES_AT > ? ";
            Object[] params = includeExpired ? new Object[]{} : new Object[]{true, now};
            GuardrailsBurstCredit[] rows = ao.find(
                    GuardrailsBurstCredit.class,
                    " " + clause + " ORDER BY ACTIVE DESC, EXPIRES_AT ASC, ID ASC",
                    params);
            if (rows.length == 0) {
                return Collections.emptyList();
            }
            List<BurstCredit> credits = new ArrayList<>(rows.length);
            for (GuardrailsBurstCredit row : rows) {
                credits.add(toValue(row));
            }
            return credits;
        });
    }

    public Optional<BurstCredit> getCredit(int id) {
        return ao.executeInTransaction(() -> {
            GuardrailsBurstCredit row = ao.get(GuardrailsBurstCredit.class, id);
            if (row == null) {
                return Optional.empty();
            }
            if (row.isActive() && row.getExpiresAt() <= System.currentTimeMillis()) {
                row.setActive(false);
                row.save();
            }
            return Optional.of(toValue(row));
        });
    }

    public boolean revokeCredit(int id, @Nullable String note) {
        return ao.executeInTransaction(() -> {
            GuardrailsBurstCredit row = ao.get(GuardrailsBurstCredit.class, id);
            if (row == null) {
                return false;
            }
            row.setActive(false);
            row.setNote(sanitize(note));
            row.save();
            return true;
        });
    }

    public int purgeExpired() {
        return ao.executeInTransaction(() -> {
            long now = System.currentTimeMillis();
            GuardrailsBurstCredit[] rows = ao.find(
                    GuardrailsBurstCredit.class,
                    "ACTIVE = ? AND EXPIRES_AT <= ?",
                    true,
                    now);
            if (rows.length == 0) {
                return 0;
            }
            for (GuardrailsBurstCredit row : rows) {
                row.setActive(false);
                row.save();
            }
            return rows.length;
        });
    }

    private void deactivateExpired(GuardrailsRateLimitScope scope,
                                   String identifier,
                                   long now) {
        GuardrailsBurstCredit[] rows = ao.find(
                GuardrailsBurstCredit.class,
                "SCOPE = ? AND IDENTIFIER = ? AND ACTIVE = ? AND EXPIRES_AT <= ?",
                scope.name(),
                identifier,
                true,
                now);
        for (GuardrailsBurstCredit row : rows) {
            row.setActive(false);
            row.save();
        }
    }

    private GuardrailsBurstCredit selectCredit(GuardrailsBurstCredit[] rows) {
        if (rows.length == 1) {
            return rows[0];
        }
        GuardrailsBurstCredit selected = rows[0];
        for (GuardrailsBurstCredit row : rows) {
            if (row.getExpiresAt() < selected.getExpiresAt()) {
                selected = row;
                continue;
            }
            if (row.getExpiresAt() == selected.getExpiresAt()
                    && row.getID() < selected.getID()) {
                selected = row;
            }
        }
        return selected;
    }

    private String normalizeIdentifier(GuardrailsRateLimitScope scope, String identifier) {
        if (identifier == null) {
            return "";
        }
        String trimmed = identifier.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (scope == GuardrailsRateLimitScope.PROJECT) {
            return trimmed.toUpperCase(Locale.ROOT);
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private String sanitize(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private BurstCredit toValue(GuardrailsBurstCredit entity) {
        int tokensGranted = entity.getTokensGranted();
        int consumed = Math.max(0, entity.getTokensConsumed());
        int remaining = Math.max(0, tokensGranted - consumed);
        return new BurstCredit(
                entity.getID(),
                GuardrailsRateLimitScope.fromString(entity.getScope()),
                entity.getIdentifier(),
                tokensGranted,
                consumed,
                remaining,
                entity.getCreatedAt(),
                entity.getExpiresAt(),
                entity.isActive(),
                entity.getCreatedBy(),
                entity.getCreatedByDisplayName(),
                entity.getReason(),
                entity.getNote(),
                entity.getLastConsumedAt());
    }

    public static final class BurstCredit {
        private final int id;
        private final GuardrailsRateLimitScope scope;
        private final String identifier;
        private final int tokensGranted;
        private final int tokensConsumed;
        private final int tokensRemaining;
        private final long createdAt;
        private final long expiresAt;
        private final boolean active;
        private final String createdBy;
        private final String createdByDisplayName;
        private final String reason;
        private final String note;
        private final long lastConsumedAt;

        public BurstCredit(int id,
                           GuardrailsRateLimitScope scope,
                           String identifier,
                           int tokensGranted,
                           int tokensConsumed,
                           int tokensRemaining,
                           long createdAt,
                           long expiresAt,
                           boolean active,
                           String createdBy,
                           String createdByDisplayName,
                           String reason,
                           String note,
                           long lastConsumedAt) {
            this.id = id;
            this.scope = scope;
            this.identifier = identifier;
            this.tokensGranted = tokensGranted;
            this.tokensConsumed = tokensConsumed;
            this.tokensRemaining = tokensRemaining;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
            this.active = active;
            this.createdBy = createdBy;
            this.createdByDisplayName = createdByDisplayName;
            this.reason = reason;
            this.note = note;
            this.lastConsumedAt = lastConsumedAt;
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

        public int getTokensGranted() {
            return tokensGranted;
        }

        public int getTokensConsumed() {
            return tokensConsumed;
        }

        public int getTokensRemaining() {
            return tokensRemaining;
        }

        public long getCreatedAt() {
            return createdAt;
        }

        public long getExpiresAt() {
            return expiresAt;
        }

        public boolean isActive() {
            return active;
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

        public String getNote() {
            return note;
        }

        public long getLastConsumedAt() {
            return lastConsumedAt;
        }
    }
}
