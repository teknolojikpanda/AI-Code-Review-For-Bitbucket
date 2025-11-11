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

import java.lang.reflect.Field;
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
    private ReviewWorkerPool workerPool;
    private GuardrailsRateLimitStore rateLimitStore;
    private GuardrailsRateLimitOverrideService overrideService;
    private GuardrailsBurstCreditService burstCreditService;
    private GuardrailsAutoSnoozeService autoSnoozeService;
    private ReviewSchedulerStateService schedulerStateService;
    private ReviewQueueAuditService queueAuditService;
    private WorkerDegradationService workerDegradationService;

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
        when(configService.getConfigurationAsMap()).thenReturn(Collections.emptyMap());
        rateLimitStore = mock(GuardrailsRateLimitStore.class);
        overrideService = mock(GuardrailsRateLimitOverrideService.class);
        when(overrideService.resolveRepoLimit(any(), any(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        when(overrideService.resolveProjectLimit(any(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        burstCreditService = mock(GuardrailsBurstCreditService.class);
        securityService = mock(SecurityService.class);
        schedulerStateService = mock(ReviewSchedulerStateService.class);
        queueAuditService = mock(ReviewQueueAuditService.class);
        workerDegradationService = mock(WorkerDegradationService.class);
        ReviewSchedulerStateService.SchedulerState schedulerState =
                new ReviewSchedulerStateService.SchedulerState(
                        ReviewSchedulerStateService.SchedulerState.Mode.ACTIVE,
                        null,
                        null,
                        null,
                        System.currentTimeMillis());
        when(schedulerStateService.getState()).thenReturn(schedulerState);
        concurrencyController = new ReviewConcurrencyController(configService, schedulerStateService, queueAuditService);
        rateLimiter = new ReviewRateLimiter(configService, rateLimitStore, overrideService, burstCreditService);
        workerPool = new ReviewWorkerPool(configService);
        autoSnoozeService = mock(GuardrailsAutoSnoozeService.class);
        when(workerDegradationService.apply(any())).thenAnswer(invocation ->
                WorkerDegradationService.Result.passThrough(invocation.getArgument(0)));

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
                rateLimiter,
                workerPool,
                autoSnoozeService,
                workerDegradationService);

        try {
            setSecurityServiceNull();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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

    private void setSecurityServiceNull() throws Exception {
        Field field = AIReviewServiceImpl.class.getDeclaredField("securityService");
        field.setAccessible(true);
        field.set(service, null);
    }
}
