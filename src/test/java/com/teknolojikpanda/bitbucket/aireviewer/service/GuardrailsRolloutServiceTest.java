package com.teknolojikpanda.bitbucket.aireviewer.service;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.sal.api.features.DarkFeatureManager;
import com.teknolojikpanda.bitbucket.aireviewer.ao.AIReviewRolloutCohort;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GuardrailsRolloutService} evaluation and telemetry logic.
 */
public class GuardrailsRolloutServiceTest {

    private GuardrailsRolloutService service;
    private ActiveObjects activeObjects;
    private DarkFeatureManager darkFeatureManager;

    @Before
    public void setUp() {
        activeObjects = mock(ActiveObjects.class);
        darkFeatureManager = mock(DarkFeatureManager.class);
        when(darkFeatureManager.isFeatureEnabledForAllUsers(anyString())).thenReturn(true);
        when(activeObjects.find(AIReviewRolloutCohort.class)).thenReturn(new AIReviewRolloutCohort[0]);
        service = new GuardrailsRolloutService(activeObjects, darkFeatureManager);
    }

    @Test
    public void evaluate_defaultsToEnforcedWhenNoCohorts() {
        GuardrailsRolloutService.Evaluation evaluation =
                service.evaluate("PROJ", "repo", "run-1");

        assertTrue(evaluation.isGuardrailsEnabled());
        assertEquals(GuardrailsRolloutService.RolloutMode.FALLBACK, evaluation.getMode());
        assertNull(evaluation.getCohort());
    }

    @Test
    public void evaluate_shadowWhenCohortDisabled() throws Exception {
        injectCohorts(cohortRecord(1, "pilot", GuardrailsRolloutService.ScopeMode.REPOSITORY, "PROJ", "repo", false, 100, null));

        GuardrailsRolloutService.Evaluation evaluation =
                service.evaluate("PROJ", "repo", "run-2");

        assertFalse(evaluation.isGuardrailsEnabled());
        assertEquals(GuardrailsRolloutService.RolloutMode.SHADOW, evaluation.getMode());
        assertEquals("pilot", evaluation.getCohortKey());
    }

    @Test
    public void evaluate_shadowWhenDarkFeatureDisabled() throws Exception {
        when(darkFeatureManager.isFeatureEnabledForAllUsers("feature.flag")).thenReturn(false);
        injectCohorts(cohortRecord(2, "pilot", GuardrailsRolloutService.ScopeMode.REPOSITORY, "PROJ", "repo", true, 100, "feature.flag"));

        GuardrailsRolloutService.Evaluation evaluation =
                service.evaluate("PROJ", "repo", "run-3");

        assertFalse(evaluation.isGuardrailsEnabled());
        assertEquals(GuardrailsRolloutService.RolloutMode.SHADOW, evaluation.getMode());
    }

    @Test
    public void evaluate_enforcedWhenCohortActive() throws Exception {
        injectCohorts(cohortRecord(3, "pilot", GuardrailsRolloutService.ScopeMode.PROJECT, "PROJ", null, true, 100, null));

        GuardrailsRolloutService.Evaluation evaluation =
                service.evaluate("PROJ", "other", "run-4");

        assertTrue(evaluation.isGuardrailsEnabled());
        assertEquals(GuardrailsRolloutService.RolloutMode.ENFORCED, evaluation.getMode());
        assertEquals("pilot", evaluation.getCohortKey());
    }

    @Test
    public void evaluate_fallbackWhenCohortsConfiguredButUnmatched() throws Exception {
        injectCohorts(cohortRecord(4, "pilot", GuardrailsRolloutService.ScopeMode.REPOSITORY, "PROJ", "repo", true, 100, null));

        GuardrailsRolloutService.Evaluation evaluation =
                service.evaluate("OTHER", "repo", "run-5");

        assertFalse(evaluation.isGuardrailsEnabled());
        assertEquals(GuardrailsRolloutService.RolloutMode.FALLBACK, evaluation.getMode());
        assertNull(evaluation.getCohort());
    }

    @Test
    public void describeTelemetry_includesRecordedStats() throws Exception {
        injectCohorts(cohortRecord(5, "pilot", GuardrailsRolloutService.ScopeMode.GLOBAL, null, null, true, 100, null));

        service.evaluate("PROJ", "repo", "run-6");
        service.recordCompletion("pilot", GuardrailsRolloutService.RolloutMode.ENFORCED, null);

        Map<String, Object> telemetry = service.describeTelemetry();
        assertNotNull(telemetry);
        assertEquals("shadow", telemetry.get("defaultMode"));
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> cohorts = (java.util.List<Map<String, Object>>) telemetry.get("cohorts");
        assertNotNull(cohorts);
        assertEquals(1, cohorts.size());
        Map<String, Object> entry = cohorts.get(0);
        assertEquals("pilot", entry.get("key"));
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) entry.get("metrics");
        assertEquals(1L, metrics.get("startedEnforced"));
        assertEquals(1L, metrics.get("completed"));
    }

    private GuardrailsRolloutService.CohortRecord cohortRecord(int id,
                                                               String key,
                                                               GuardrailsRolloutService.ScopeMode scope,
                                                               String projectKey,
                                                               String repositorySlug,
                                                               boolean enabled,
                                                               int percent,
                                                               String darkFeature) {
        long now = System.currentTimeMillis();
        return new GuardrailsRolloutService.CohortRecord(
                id,
                key,
                key.toUpperCase(),
                "Test cohort",
                scope,
                projectKey,
                repositorySlug,
                percent,
                darkFeature,
                enabled,
                now,
                now,
                "tester");
    }

    private void injectCohorts(GuardrailsRolloutService.CohortRecord... records) throws Exception {
        Field field = GuardrailsRolloutService.class.getDeclaredField("cachedCohorts");
        field.setAccessible(true);
        field.set(service, Collections.unmodifiableList(Arrays.asList(records)));
    }
}
