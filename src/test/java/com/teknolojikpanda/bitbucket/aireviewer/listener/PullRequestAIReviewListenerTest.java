package com.teknolojikpanda.bitbucket.aireviewer.listener;

import com.atlassian.bitbucket.event.pull.PullRequestOpenedEvent;
import com.atlassian.bitbucket.pull.PullRequest;
import com.atlassian.event.api.EventPublisher;
import com.teknolojikpanda.bitbucket.aireviewer.dto.ReviewResult;
import com.teknolojikpanda.bitbucket.aireviewer.service.AIReviewService;
import com.teknolojikpanda.bitbucket.aireviewer.service.AIReviewerConfigService;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;

import static org.mockito.Mockito.*;

public class PullRequestAIReviewListenerTest {

    private EventPublisher eventPublisher;
    private AIReviewService reviewService;
    private AIReviewerConfigService configService;
    private PullRequestAIReviewListener listener;

    @Before
    public void setUp() {
        eventPublisher = mock(EventPublisher.class);
        reviewService = mock(AIReviewService.class);
        configService = mock(AIReviewerConfigService.class);
        listener = new PullRequestAIReviewListener(eventPublisher, reviewService, configService);
    }

    @Test
    public void configReadFailureDisablesReviewTrigger() {
        PullRequest pullRequest = mock(PullRequest.class);
        PullRequestOpenedEvent event = mock(PullRequestOpenedEvent.class);
        when(event.getPullRequest()).thenReturn(pullRequest);
        when(pullRequest.getId()).thenReturn(123L);
        when(pullRequest.getTitle()).thenReturn("feature: update api");
        when(configService.getConfigurationAsMap()).thenThrow(new RuntimeException("config unavailable"));

        listener.onPullRequestOpened(event);

        verify(reviewService, never()).reviewPullRequest(any(PullRequest.class));
        verify(reviewService, never()).reReviewPullRequest(any(PullRequest.class));
    }

    @Test
    public void reviewExecutionExceptionDoesNotEscapeListener() {
        PullRequest pullRequest = mock(PullRequest.class);
        PullRequestOpenedEvent event = mock(PullRequestOpenedEvent.class);
        when(event.getPullRequest()).thenReturn(pullRequest);
        when(pullRequest.getId()).thenReturn(456L);
        when(pullRequest.getTitle()).thenReturn("feature: add endpoint");
        when(configService.getConfigurationAsMap()).thenReturn(Map.of("enabled", true));
        when(reviewService.reviewPullRequest(pullRequest)).thenThrow(new RuntimeException("ollama timeout"));

        listener.onPullRequestOpened(event);

        verify(reviewService, times(1)).reviewPullRequest(pullRequest);
    }

    @Test
    public void reviewRunsWhenEnabled() {
        PullRequest pullRequest = mock(PullRequest.class);
        PullRequestOpenedEvent event = mock(PullRequestOpenedEvent.class);
        when(event.getPullRequest()).thenReturn(pullRequest);
        when(pullRequest.getId()).thenReturn(789L);
        when(pullRequest.getTitle()).thenReturn("feature: add checks");
        when(configService.getConfigurationAsMap()).thenReturn(Map.of("enabled", true));

        ReviewResult result = ReviewResult.builder()
                .pullRequestId(789L)
                .status(ReviewResult.Status.SUCCESS)
                .filesReviewed(1)
                .filesSkipped(0)
                .build();
        when(reviewService.reviewPullRequest(pullRequest)).thenReturn(result);

        listener.onPullRequestOpened(event);

        verify(reviewService, times(1)).reviewPullRequest(pullRequest);
    }
}
