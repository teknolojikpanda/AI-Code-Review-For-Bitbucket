package com.teknolojikpanda.bitbucket.aireviewer.service;

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class GuardrailsAutoSnoozeServiceTest {

    private AIReviewerConfigService configService;
    private GuardrailsRateLimitOverrideService overrideService;
    private GuardrailsAutoSnoozeService service;

    @Before
    public void setUp() {
        configService = mock(AIReviewerConfigService.class);
        overrideService = mock(GuardrailsRateLimitOverrideService.class);
        service = new GuardrailsAutoSnoozeService(configService, overrideService);

        when(configService.getPriorityRateLimitSnoozeMinutes()).thenReturn(15);
        when(configService.getPriorityRepoRateLimitPerHour()).thenReturn(30);
        when(configService.getPriorityProjectRateLimitPerHour()).thenReturn(120);
        when(overrideService.ensureAutoSnooze(any(), anyString(), anyInt(), anyLong(), anyString())).thenReturn(true);
    }

    @Test
    public void ensurePriorityCapacity_appliesRepoOverride() {
        when(configService.isPriorityRepository("PROJ", "repo")).thenReturn(true);
        when(configService.isPriorityProject("PROJ")).thenReturn(false);

        service.ensurePriorityCapacity("PROJ", "repo");

        verify(overrideService).ensureAutoSnooze(
                eq(GuardrailsRateLimitScope.REPOSITORY),
                eq("repo"),
                eq(30),
                eq(TimeUnit.MINUTES.toMillis(15)),
                contains("repository"));
        verifyNoMoreInteractions(overrideService);
    }

    @Test
    public void ensurePriorityCapacity_appliesProjectOverride() {
        when(configService.isPriorityRepository("PROJ", "repo")).thenReturn(false);
        when(configService.isPriorityProject("PROJ")).thenReturn(true);

        service.ensurePriorityCapacity("PROJ", "repo");

        verify(overrideService).ensureAutoSnooze(
                eq(GuardrailsRateLimitScope.PROJECT),
                eq("PROJ"),
                eq(120),
                eq(TimeUnit.MINUTES.toMillis(15)),
                contains("project"));
    }

    @Test
    public void ensurePriorityCapacity_skipsWhenNotPriority() {
        when(configService.isPriorityRepository(anyString(), anyString())).thenReturn(false);
        when(configService.isPriorityProject(anyString())).thenReturn(false);

        service.ensurePriorityCapacity("PROJ", "repo");

        verifyNoInteractions(overrideService);
    }
}
