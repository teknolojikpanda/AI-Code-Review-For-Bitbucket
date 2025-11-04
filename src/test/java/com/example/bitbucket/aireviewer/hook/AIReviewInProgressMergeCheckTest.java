package com.example.bitbucket.aireviewer.hook;

import com.atlassian.bitbucket.hook.repository.PreRepositoryHookContext;
import com.atlassian.bitbucket.hook.repository.PullRequestMergeHookRequest;
import com.atlassian.bitbucket.hook.repository.RepositoryHookResult;
import com.atlassian.bitbucket.hook.repository.RepositoryHookVeto;
import com.atlassian.bitbucket.project.Project;
import com.atlassian.bitbucket.pull.PullRequest;
import com.atlassian.bitbucket.pull.PullRequestRef;
import com.atlassian.bitbucket.repository.Repository;
import com.example.bitbucket.aireviewer.dto.ReviewResult;
import com.example.bitbucket.aireviewer.progress.ProgressRegistry;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AIReviewInProgressMergeCheckTest {

    private ProgressRegistry progressRegistry;
    private AIReviewInProgressMergeCheck mergeCheck;
    private PreRepositoryHookContext context;
    private PullRequestMergeHookRequest request;
    private PullRequest pullRequest;
    private PullRequestRef toRef;
    private Repository repository;
    private Project project;

    @Before
    public void setUp() {
        progressRegistry = new ProgressRegistry();
        mergeCheck = new AIReviewInProgressMergeCheck(progressRegistry);

        context = mock(PreRepositoryHookContext.class);
        request = mock(PullRequestMergeHookRequest.class);
        pullRequest = mock(PullRequest.class);
        toRef = mock(PullRequestRef.class);
        repository = mock(Repository.class);
        project = mock(Project.class);

        when(request.getPullRequest()).thenReturn(pullRequest);
        when(pullRequest.getId()).thenReturn(42L);
        when(pullRequest.getToRef()).thenReturn(toRef);
        when(toRef.getRepository()).thenReturn(repository);
        when(repository.getSlug()).thenReturn("repo");
        when(repository.getProject()).thenReturn(project);
        when(project.getKey()).thenReturn("PROJ");
    }

    @Test
    public void acceptsWhenNoActiveReview() {
        RepositoryHookResult result = mergeCheck.preUpdate(context, request);

        assertThat(result.isAccepted(), is(true));
        assertThat(result.getVetoes(), notNullValue());
        assertThat(result.getVetoes().isEmpty(), is(true));
    }

    @Test
    public void rejectsWhenReviewInProgress() {
        ProgressRegistry.ProgressMetadata metadata =
                new ProgressRegistry.ProgressMetadata("PROJ", "repo", 42L, "run-1", false, false, false);
        progressRegistry.start(metadata);

        RepositoryHookResult result = mergeCheck.preUpdate(context, request);

        assertThat(result.isRejected(), is(true));
        List<RepositoryHookVeto> vetoes = result.getVetoes();
        assertThat(vetoes.isEmpty(), is(false));
        RepositoryHookVeto veto = vetoes.get(0);
        assertThat(veto.getSummaryMessage(), equalTo("AI review in progress"));
    }

    @Test
    public void acceptsWhenReviewCompleted() {
        ProgressRegistry.ProgressMetadata metadata =
                new ProgressRegistry.ProgressMetadata("PROJ", "repo", 42L, "run-1", false, false, false);
        progressRegistry.start(metadata);
        progressRegistry.complete(metadata, ReviewResult.Status.SUCCESS);

        RepositoryHookResult result = mergeCheck.preUpdate(context, request);

        assertThat(result.isAccepted(), is(true));
    }

    @Test
    public void acceptsWhenRepositoryMissing() {
        when(pullRequest.getToRef()).thenReturn(null);

        RepositoryHookResult result = mergeCheck.preUpdate(context, request);

        assertThat(result.isAccepted(), is(true));
    }
}
