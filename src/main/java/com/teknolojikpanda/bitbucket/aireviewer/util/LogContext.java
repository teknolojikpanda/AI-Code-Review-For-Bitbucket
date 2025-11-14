package com.teknolojikpanda.bitbucket.aireviewer.util;

import com.atlassian.bitbucket.pull.PullRequest;
import com.atlassian.bitbucket.repository.Repository;
import org.slf4j.MDC;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Utility helper that scopes MDC logging context for pull-request driven reviews.
 *
 * <p>All log statements emitted while an instance is active will automatically include
 * PR metadata (project key, repository slug, branches, author) and a stable correlation id
 * so that operations teams can trace an entire review across async boundaries.</p>
 */
public final class LogContext implements AutoCloseable {

    private static final String CORRELATION_KEY = "review.correlationId";

    private final List<String> keys = new ArrayList<>();
    private final Map<String, String> previousValues = new LinkedHashMap<>();

    private LogContext(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        values.forEach((key, value) -> {
            if (value == null || value.isBlank()) {
                return;
            }
            keys.add(key);
            previousValues.put(key, MDC.get(key));
            MDC.put(key, value);
        });
    }

    public static LogContext forPullRequest(PullRequest pullRequest) {
        Objects.requireNonNull(pullRequest, "pullRequest");
        Map<String, String> values = new LinkedHashMap<>();
        values.put(CORRELATION_KEY, correlationId());
        values.put("pr.id", String.valueOf(pullRequest.getId()));
        values.put("pr.title", trim(pullRequest.getTitle()));
        if (pullRequest.getAuthor() != null && pullRequest.getAuthor().getUser() != null) {
            values.put("pr.author", trim(pullRequest.getAuthor().getUser().getSlug()));
        }
        values.put("branch.from", pullRequest.getFromRef() != null
                ? trim(pullRequest.getFromRef().getDisplayId())
                : null);
        values.put("branch.to", pullRequest.getToRef() != null
                ? trim(pullRequest.getToRef().getDisplayId())
                : null);
        Repository repository = pullRequest.getToRef() != null ? pullRequest.getToRef().getRepository() : null;
        if (repository != null) {
            if (repository.getProject() != null) {
                values.put("repo.project", trim(repository.getProject().getKey()));
            }
            values.put("repo.slug", trim(repository.getSlug()));
        }
        return new LogContext(values);
    }

    public static LogContext scoped(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return new LogContext(Collections.emptyMap());
        }
        Map<String, String> sanitized = new LinkedHashMap<>();
        values.forEach((key, value) -> sanitized.put(key, trim(value)));
        return new LogContext(sanitized);
    }

    public static LogContext scoped(String key, String value) {
        if (key == null || key.isBlank()) {
            return new LogContext(Collections.emptyMap());
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put(key, trim(value));
        return new LogContext(values);
    }

    public static String correlationId() {
        String current = MDC.get(CORRELATION_KEY);
        if (current != null && !current.isBlank()) {
            return current;
        }
        String generated = UUID.randomUUID().toString();
        MDC.put(CORRELATION_KEY, generated);
        return generated;
    }

    @Override
    public void close() {
        for (int i = keys.size() - 1; i >= 0; i--) {
            String key = keys.get(i);
            String previous = previousValues.get(key);
            if (previous == null || previous.isBlank()) {
                MDC.remove(key);
            } else {
                MDC.put(key, previous);
            }
        }
    }

    @Nullable
    private static String trim(String input) {
        return input == null ? null : input.trim();
    }
}
