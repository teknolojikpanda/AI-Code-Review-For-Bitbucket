package com.teknolojikpanda.bitbucket.aireviewer.service;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.teknolojikpanda.bitbucket.aireviewer.ao.AIReviewSchedulerState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.util.Locale;
import java.util.Objects;

@Named
@Singleton
public class ReviewSchedulerStateService {

    private static final Logger log = LoggerFactory.getLogger(ReviewSchedulerStateService.class);

    private final ActiveObjects ao;

    @Inject
    public ReviewSchedulerStateService(@ComponentImport ActiveObjects ao) {
        this.ao = Objects.requireNonNull(ao, "activeObjects");
    }

    @Nonnull
    public SchedulerState getState() {
        return ao.executeInTransaction(() -> toValue(loadOrCreate()));
    }

    @Nonnull
    public SchedulerState updateState(@Nonnull SchedulerState.Mode mode,
                                      @Nullable String updatedBy,
                                      @Nullable String updatedByDisplayName,
                                      @Nullable String reason) {
        Objects.requireNonNull(mode, "mode");
        return ao.executeInTransaction(() -> {
            AIReviewSchedulerState entity = loadOrCreate();
            entity.setState(mode.name());
            entity.setUpdatedBy(sanitize(updatedBy));
            entity.setUpdatedByDisplayName(sanitize(updatedByDisplayName));
            entity.setReason(reason != null ? reason.trim() : null);
            entity.setUpdatedAt(System.currentTimeMillis());
            entity.save();
            log.info("AI review scheduler state changed to {} by {} ({})",
                    mode,
                    updatedBy != null ? updatedBy : "system",
                    updatedByDisplayName != null ? updatedByDisplayName : "-");
            return toValue(entity);
        });
    }

    private AIReviewSchedulerState loadOrCreate() {
        AIReviewSchedulerState[] rows = ao.find(AIReviewSchedulerState.class);
        if (rows.length > 0) {
            return rows[0];
        }
        AIReviewSchedulerState created = ao.create(AIReviewSchedulerState.class);
        created.setState(SchedulerState.Mode.ACTIVE.name());
        created.setUpdatedAt(System.currentTimeMillis());
        created.save();
        return created;
    }

    private SchedulerState toValue(AIReviewSchedulerState entity) {
        SchedulerState.Mode mode = SchedulerState.Mode.fromString(entity.getState());
        return new SchedulerState(
                mode,
                entity.getUpdatedBy(),
                entity.getUpdatedByDisplayName(),
                entity.getReason(),
                entity.getUpdatedAt());
    }

    @Nullable
    private String sanitize(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static final class SchedulerState {
        public enum Mode {
            ACTIVE,
            PAUSED,
            DRAINING;

            public static Mode fromString(@Nullable String raw) {
                if (raw == null) {
                    return ACTIVE;
                }
                try {
                    return Mode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
                } catch (Exception ex) {
                    return ACTIVE;
                }
            }
        }

        private final Mode mode;
        private final String updatedBy;
        private final String updatedByDisplayName;
        private final String reason;
        private final long updatedAt;

        public SchedulerState(@Nonnull Mode mode,
                              @Nullable String updatedBy,
                              @Nullable String updatedByDisplayName,
                              @Nullable String reason,
                              long updatedAt) {
            this.mode = Objects.requireNonNull(mode, "mode");
            this.updatedBy = updatedBy;
            this.updatedByDisplayName = updatedByDisplayName;
            this.reason = reason;
            this.updatedAt = updatedAt;
        }

        @Nonnull
        public Mode getMode() {
            return mode;
        }

        @Nullable
        public String getUpdatedBy() {
            return updatedBy;
        }

        @Nullable
        public String getUpdatedByDisplayName() {
            return updatedByDisplayName;
        }

        @Nullable
        public String getReason() {
            return reason;
        }

        public long getUpdatedAt() {
            return updatedAt;
        }

        public boolean isAcceptingNewRuns() {
            return mode == Mode.ACTIVE;
        }

        public boolean isDraining() {
            return mode == Mode.DRAINING;
        }
    }
}
