package com.teknolojikpanda.bitbucket.aicode.core;

import com.atlassian.bitbucket.pull.PullRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Lightweight in-memory cache for AI overview responses keyed by commit hash.
 */
@Named
@Singleton
public class OverviewCache {

    private static final Logger log = LoggerFactory.getLogger(OverviewCache.class);
    private static final long TTL_MS = TimeUnit.MINUTES.toMillis(15);

    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();

    @Inject
    public OverviewCache() {
    }

    public String getOrCompute(@Nullable String key, @Nullable Supplier<String> supplier) {
        if (key == null || supplier == null) {
            return supplier != null ? supplier.get() : null;
        }
        long now = System.currentTimeMillis();
        Entry entry = cache.get(key);
        if (entry != null && !entry.isExpired(now)) {
            return entry.value;
        }
        String value = supplier.get();
        if (value != null && !value.isEmpty()) {
            cache.put(key, new Entry(value, now));
        }
        purgeExpired(now);
        return value;
    }

    @Nullable
    public String buildKey(@Nullable PullRequest pullRequest) {
        if (pullRequest == null || pullRequest.getFromRef() == null) {
            return null;
        }
        String commitId = pullRequest.getFromRef().getLatestCommit();
        if (commitId == null || commitId.isBlank()) {
            return null;
        }
        String projectKey = pullRequest.getToRef() != null
                && pullRequest.getToRef().getRepository() != null
                && pullRequest.getToRef().getRepository().getProject() != null
                ? pullRequest.getToRef().getRepository().getProject().getKey()
                : "unknown";
        String repoSlug = pullRequest.getToRef() != null
                && pullRequest.getToRef().getRepository() != null
                ? pullRequest.getToRef().getRepository().getSlug()
                : "unknown";
        return String.format("%s/%s#%s#overview", projectKey, repoSlug, commitId);
    }

    private void purgeExpired(long now) {
        cache.forEach((key, entry) -> {
            if (entry.isExpired(now)) {
                cache.remove(key, entry);
            }
        });
    }

    private static final class Entry {
        final String value;
        final long createdAt;

        Entry(String value, long createdAt) {
            this.value = Objects.requireNonNull(value, "value");
            this.createdAt = createdAt;
        }

        boolean isExpired(long now) {
            return now - createdAt > TTL_MS;
        }
    }
}
