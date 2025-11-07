package com.teknolojikpanda.bitbucket.aireviewer.service;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.bitbucket.comment.CommentService;
import com.atlassian.bitbucket.hook.repository.EnableRepositoryHookRequest;
import com.atlassian.bitbucket.hook.repository.RepositoryHook;
import com.atlassian.bitbucket.hook.repository.RepositoryHookService;
import com.atlassian.bitbucket.pull.PullRequestService;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.project.Project;
import com.atlassian.bitbucket.server.ApplicationPropertiesService;
import com.atlassian.bitbucket.user.SecurityService;
import com.atlassian.bitbucket.user.UserService;
import com.teknolojikpanda.bitbucket.aicode.api.ChunkPlanner;
import com.teknolojikpanda.bitbucket.aicode.api.DiffProvider;
import com.teknolojikpanda.bitbucket.aicode.api.ReviewOrchestrator;
import com.teknolojikpanda.bitbucket.aicode.core.ReviewConfigFactory;
import com.teknolojikpanda.bitbucket.aireviewer.hook.AIReviewInProgressMergeCheck;
import com.teknolojikpanda.bitbucket.aireviewer.progress.ProgressRegistry;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Method;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class AIReviewServiceMergeCheckTest {

    private PullRequestService pullRequestService;
    private CommentService commentService;
    private ActiveObjects activeObjects;
    private ApplicationPropertiesService applicationPropertiesService;
    private AIReviewerConfigService configService;
    private DiffProvider diffProvider;
    private ChunkPlanner chunkPlanner;
    private ReviewOrchestrator reviewOrchestrator;
    private ReviewConfigFactory configFactory;
    private ReviewHistoryService reviewHistoryService;
    private ProgressRegistry progressRegistry;
    private RepositoryHookService repositoryHookService;
    private UserService userService;
    private SecurityService securityService;
    private ReviewConcurrencyController concurrencyController;
    private ReviewRateLimiter rateLimiter;

    private AIReviewServiceImpl service;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        pullRequestService = mock(PullRequestService.class);
        commentService = mock(CommentService.class);
        activeObjects = mock(ActiveObjects.class);
        applicationPropertiesService = mock(ApplicationPropertiesService.class);
        configService = mock(AIReviewerConfigService.class);
        diffProvider = mock(DiffProvider.class);
        chunkPlanner = mock(ChunkPlanner.class);
        reviewOrchestrator = mock(ReviewOrchestrator.class);
        configFactory = mock(ReviewConfigFactory.class);
        reviewHistoryService = mock(ReviewHistoryService.class);
        progressRegistry = mock(ProgressRegistry.class);
        repositoryHookService = mock(RepositoryHookService.class);
        userService = mock(UserService.class);
        securityService = mock(SecurityService.class);
        when(configService.getConfigurationAsMap()).thenReturn(Collections.emptyMap());
        concurrencyController = new ReviewConcurrencyController(configService);
        rateLimiter = new ReviewRateLimiter(configService);

        service = new AIReviewServiceImpl(
                pullRequestService,
                commentService,
                activeObjects,
                applicationPropertiesService,
                configService,
                diffProvider,
                chunkPlanner,
                reviewOrchestrator,
                configFactory,
                userService,
                securityService,
                reviewHistoryService,
                progressRegistry,
                repositoryHookService,
                concurrencyController,
                rateLimiter);
    }

    @Test
    public void ensureMergeCheckEnabled_autoEnablesWhenMissing() throws Exception {
        Repository repository = mock(Repository.class);
        Project project = mock(Project.class);
        when(project.getKey()).thenReturn("PROJ");
        when(repository.getProject()).thenReturn(project);
        when(repository.getSlug()).thenReturn("repo");
        when(repositoryHookService.getByKey(any(), eq(AIReviewInProgressMergeCheck.MODULE_KEY))).thenReturn(null);

        invokeEnsureMergeCheck(repository);

        verify(repositoryHookService).enable(any(EnableRepositoryHookRequest.class));
    }

    @Test
    public void ensureMergeCheckEnabled_skipsWhenAlreadyEnabled() throws Exception {
        Repository repository = mock(Repository.class);
        Project project = mock(Project.class);
        when(project.getKey()).thenReturn("PROJ");
        when(repository.getProject()).thenReturn(project);
        when(repository.getSlug()).thenReturn("repo");
        RepositoryHook hook = mock(RepositoryHook.class);
        when(hook.isEnabled()).thenReturn(true);
        when(repositoryHookService.getByKey(any(), eq(AIReviewInProgressMergeCheck.MODULE_KEY))).thenReturn(hook);

        invokeEnsureMergeCheck(repository);

        verify(repositoryHookService, never()).enable(any());
    }

    private void invokeEnsureMergeCheck(Repository repository) throws Exception {
        Method method = AIReviewServiceImpl.class.getDeclaredMethod("ensureMergeCheckEnabled", Repository.class);
        method.setAccessible(true);
        method.invoke(service, repository);
    }
}
