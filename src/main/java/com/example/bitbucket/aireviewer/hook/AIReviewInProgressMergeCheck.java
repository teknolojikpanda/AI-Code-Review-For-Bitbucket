package com.example.bitbucket.aireviewer.hook;

import com.atlassian.bitbucket.hook.repository.PreRepositoryHookContext;
import com.atlassian.bitbucket.hook.repository.PullRequestMergeHookRequest;
import com.atlassian.bitbucket.hook.repository.RepositoryHookResult;
import com.atlassian.bitbucket.hook.repository.RepositoryMergeCheck;
import com.atlassian.bitbucket.project.Project;
import com.atlassian.bitbucket.pull.PullRequest;
import com.atlassian.bitbucket.pull.PullRequestRef;
import com.atlassian.bitbucket.repository.Repository;
import com.example.bitbucket.aireviewer.progress.ProgressRegistry;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.util.Optional;

/**
 * Prevents merges while an AI review run is still active for the pull request.
 */
@Named("aiReviewInProgressMergeCheck")
@Singleton
public class AIReviewInProgressMergeCheck implements RepositoryMergeCheck {

    public static final String MODULE_KEY = "com.example.bitbucket.ai-code-reviewer:ai-reviewer-in-progress-merge-check";

    private final ProgressRegistry progressRegistry;

    @Inject
    public AIReviewInProgressMergeCheck(@Nonnull ProgressRegistry progressRegistry) {
        this.progressRegistry = progressRegistry;
    }

    @Nonnull
    @Override
    public RepositoryHookResult preUpdate(@Nonnull PreRepositoryHookContext context,
                                          @Nonnull PullRequestMergeHookRequest request) {
        PullRequest pullRequest = request.getPullRequest();
        if (pullRequest == null) {
            return RepositoryHookResult.accepted();
        }

        PullRequestRef toRef = pullRequest.getToRef();
        Repository repository = toRef != null ? toRef.getRepository() : null;
        Project project = repository != null ? repository.getProject() : null;
        if (repository == null || project == null) {
            return RepositoryHookResult.accepted();
        }

        String projectKey = project.getKey();
        String repositorySlug = repository.getSlug();
        if (projectKey == null || repositorySlug == null) {
            return RepositoryHookResult.accepted();
        }

        Optional<ProgressRegistry.ProgressSnapshot> snapshot =
                progressRegistry.getActive(projectKey, repositorySlug, pullRequest.getId());

        if (snapshot.isPresent() && !snapshot.get().isCompleted()) {
            return RepositoryHookResult.rejected(
                    "AI review in progress",
                    "AI Code Reviewer is still analyzing this pull request. Wait for the AI review to finish before merging.");
        }

        return RepositoryHookResult.accepted();
    }
}
