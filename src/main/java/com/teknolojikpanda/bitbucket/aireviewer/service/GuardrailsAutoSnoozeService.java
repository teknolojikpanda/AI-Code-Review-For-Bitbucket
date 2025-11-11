package com.teknolojikpanda.bitbucket.aireviewer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Named
@Singleton
public class GuardrailsAutoSnoozeService {

    private static final Logger log = LoggerFactory.getLogger(GuardrailsAutoSnoozeService.class);
    private static final long MIN_DURATION_MS = TimeUnit.MINUTES.toMillis(1);

    private final AIReviewerConfigService configService;
    private final GuardrailsRateLimitOverrideService overrideService;

    @Inject
    public GuardrailsAutoSnoozeService(AIReviewerConfigService configService,
                                       GuardrailsRateLimitOverrideService overrideService) {
        this.configService = Objects.requireNonNull(configService, "configService");
        this.overrideService = Objects.requireNonNull(overrideService, "overrideService");
    }

    public void ensurePriorityCapacity(@Nullable String projectKey,
                                       @Nullable String repositorySlug) {
        boolean repoPriority = configService.isPriorityRepository(projectKey, repositorySlug);
        boolean projectPriority = configService.isPriorityProject(projectKey);
        if (!repoPriority && !projectPriority) {
            return;
        }
        long durationMinutes = Math.max(1, configService.getPriorityRateLimitSnoozeMinutes());
        long durationMs = Math.max(MIN_DURATION_MS, TimeUnit.MINUTES.toMillis(durationMinutes));

        if (repoPriority && repositorySlug != null && !repositorySlug.trim().isEmpty()) {
            int limit = configService.getPriorityRepoRateLimitPerHour();
            String reason = buildReason("repository", projectKey, repositorySlug);
            boolean applied = overrideService.ensureAutoSnooze(
                    GuardrailsRateLimitScope.REPOSITORY,
                    repositorySlug,
                    limit,
                    durationMs,
                    reason);
            if (applied && log.isDebugEnabled()) {
                log.debug("Auto-snoozed repository limiter for {}/{} (limit={}, duration={}ms)",
                        projectKey,
                        repositorySlug,
                        limit,
                        durationMs);
            }
        }
        if (projectPriority && projectKey != null && !projectKey.trim().isEmpty()) {
            int limit = configService.getPriorityProjectRateLimitPerHour();
            String reason = buildReason("project", projectKey, null);
            boolean applied = overrideService.ensureAutoSnooze(
                    GuardrailsRateLimitScope.PROJECT,
                    projectKey,
                    limit,
                    durationMs,
                    reason);
            if (applied && log.isDebugEnabled()) {
                log.debug("Auto-snoozed project limiter for {} (limit={}, duration={}ms)",
                        projectKey,
                        limit,
                        durationMs);
            }
        }
    }

    private String buildReason(String scope,
                               @Nullable String projectKey,
                               @Nullable String repositorySlug) {
        StringBuilder builder = new StringBuilder("priority-auto-snooze");
        if (projectKey != null && !projectKey.trim().isEmpty()) {
            builder.append(" ").append(projectKey.trim());
        }
        if (repositorySlug != null && !repositorySlug.trim().isEmpty()) {
            builder.append("/").append(repositorySlug.trim());
        }
        builder.append(" ").append(scope);
        return builder.toString().trim();
    }
}
