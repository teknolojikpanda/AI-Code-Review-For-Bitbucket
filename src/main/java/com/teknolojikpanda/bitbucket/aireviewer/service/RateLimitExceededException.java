package com.teknolojikpanda.bitbucket.aireviewer.service;

public class RateLimitExceededException extends RuntimeException {

    public enum Scope {
        REPOSITORY,
        PROJECT
    }

    private final Scope scope;
    private final String identifier;
    private final int limitPerHour;
    private final long retryAfterMillis;

    private RateLimitExceededException(String message,
                                       Scope scope,
                                       String identifier,
                                       int limitPerHour,
                                       long retryAfterMillis) {
        super(message);
        this.scope = scope;
        this.identifier = identifier;
        this.limitPerHour = limitPerHour;
        this.retryAfterMillis = retryAfterMillis;
    }

    public static RateLimitExceededException repository(String repoSlug, int limit, long retryAfter) {
        String message = String.format("Repository %s reached %d AI reviews per hour.", repoSlug, limit);
        return new RateLimitExceededException(message, Scope.REPOSITORY, repoSlug, limit, retryAfter);
    }

    public static RateLimitExceededException project(String projectKey, int limit, long retryAfter) {
        String message = String.format("Project %s reached %d AI reviews per hour.", projectKey, limit);
        return new RateLimitExceededException(message, Scope.PROJECT, projectKey, limit, retryAfter);
    }

    public Scope getScope() {
        return scope;
    }

    public String getIdentifier() {
        return identifier;
    }

    public int getLimitPerHour() {
        return limitPerHour;
    }

    public long getRetryAfterMillis() {
        return Math.max(retryAfterMillis, 0L);
    }
}
