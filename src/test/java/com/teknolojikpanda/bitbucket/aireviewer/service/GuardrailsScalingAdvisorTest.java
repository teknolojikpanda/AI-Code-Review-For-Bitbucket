package com.teknolojikpanda.bitbucket.aireviewer.service;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GuardrailsScalingAdvisorTest {

    private GuardrailsScalingAdvisor advisor;

    @Before
    public void setUp() {
        advisor = new GuardrailsScalingAdvisor();
    }

    @Test
    public void queueBacklogProducesHint() {
        ReviewConcurrencyController.QueueStats stats =
                new ReviewConcurrencyController.QueueStats(
                        4,
                        40,
                        4,
                        8,
                        System.currentTimeMillis(),
                        null,
                        0,
                        0,
                        Collections.emptyList(),
                        Collections.emptyList());

        List<GuardrailsScalingAdvisor.ScalingHint> hints =
                advisor.evaluate(stats, Collections.emptyList());

        assertFalse(hints.isEmpty());
        assertTrue(hints.stream().anyMatch(h -> h.getSummary().contains("backlog")));
    }

    @Test
    public void workerSaturationProducesHint() throws Exception {
        List<GuardrailsWorkerNodeService.WorkerNodeRecord> nodes = Arrays.asList(
                node(4, 4),
                node(4, 3)
        );

        List<GuardrailsScalingAdvisor.ScalingHint> hints =
                advisor.evaluate(null, nodes);

        assertFalse(hints.isEmpty());
        assertTrue(hints.get(0).getSummary().toLowerCase().contains("worker"));
    }

    private GuardrailsWorkerNodeService.WorkerNodeRecord node(int configured, int active) throws Exception {
        Constructor<GuardrailsWorkerNodeService.WorkerNodeRecord> ctor =
                GuardrailsWorkerNodeService.WorkerNodeRecord.class.getDeclaredConstructor(
                        String.class,
                        String.class,
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        long.class,
                        long.class,
                        long.class,
                        boolean.class);
        ctor.setAccessible(true);
        return ctor.newInstance(
                "node-" + configured,
                "Node " + configured,
                configured,
                active,
                0,
                configured,
                configured,
                10L,
                9L,
                System.currentTimeMillis(),
                false);
    }
}
