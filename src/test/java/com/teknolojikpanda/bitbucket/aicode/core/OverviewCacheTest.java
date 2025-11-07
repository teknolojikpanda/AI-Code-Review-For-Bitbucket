package com.teknolojikpanda.bitbucket.aicode.core;

import com.atlassian.bitbucket.project.Project;
import com.atlassian.bitbucket.pull.PullRequest;
import com.atlassian.bitbucket.pull.PullRequestRef;
import com.atlassian.bitbucket.repository.Repository;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class OverviewCacheTest {

    private OverviewCache cache;

    @Before
    public void setUp() {
        cache = new OverviewCache();
    }

    @Test
    public void buildKeyIncludesProjectRepoAndCommit() {
        PullRequest pullRequest = mockPullRequest("PRJ", "repo", "abc123");
        String key = cache.buildKey(pullRequest);
        assertEquals("PRJ/repo#abc123#overview", key);
    }

    @Test
    public void getOrComputeCachesValue() {
        String key = "sample";
        String value1 = cache.getOrCompute(key, () -> "first");
        String value2 = cache.getOrCompute(key, () -> "second");
        assertEquals("first", value1);
        assertEquals("first", value2);
    }

    private PullRequest mockPullRequest(String projectKey, String slug, String commit) {
        PullRequest pullRequest = mock(PullRequest.class);
        PullRequestRef toRef = mock(PullRequestRef.class);
        PullRequestRef fromRef = mock(PullRequestRef.class);
        Repository repository = mock(Repository.class);
        Project project = mock(Project.class);
        when(project.getKey()).thenReturn(projectKey);
        when(repository.getProject()).thenReturn(project);
        when(repository.getSlug()).thenReturn(slug);
        when(toRef.getRepository()).thenReturn(repository);
        when(pullRequest.getToRef()).thenReturn(toRef);
        when(fromRef.getLatestCommit()).thenReturn(commit);
        when(pullRequest.getFromRef()).thenReturn(fromRef);
        return pullRequest;
    }
}
