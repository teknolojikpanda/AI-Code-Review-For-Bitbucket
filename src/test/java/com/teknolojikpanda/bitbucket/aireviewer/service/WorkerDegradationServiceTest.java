package com.teknolojikpanda.bitbucket.aireviewer.service;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class WorkerDegradationServiceTest {

    private ReviewWorkerPool workerPool;
    private WorkerDegradationService service;

    @Before
    public void setUp() {
        workerPool = mock(ReviewWorkerPool.class);
        service = new WorkerDegradationService(workerPool);
    }

    @Test
    public void disabledConfigurationSkipsDegradation() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("parallelThreads", 4);
        config.put("workerDegradationEnabled", false);

        when(workerPool.snapshot()).thenReturn(createSnapshot(4, 4, 4));

        WorkerDegradationService.Result result = service.apply(config);

        assertFalse(result.isDegraded());
        assertEquals(config, result.getConfiguration());
    }

    @Test
    public void highUtilizationReducesParallelThreads() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("parallelThreads", 4);

        when(workerPool.snapshot()).thenReturn(createSnapshot(4, 4, 5));

        WorkerDegradationService.Result result = service.apply(config);

        assertTrue(result.isDegraded());
        assertEquals(4, result.getOriginalParallelThreads());
        assertTrue(result.getAdjustedParallelThreads() < 4);
        assertEquals(result.getAdjustedParallelThreads(),
                result.getConfiguration().get("parallelThreads"));
    }

    @Test
    public void alreadyMinimalParallelThreadsRemainsUnchanged() throws Exception {
        Map<String, Object> config = Collections.singletonMap("parallelThreads", 1);

        when(workerPool.snapshot()).thenReturn(createSnapshot(2, 2, 3));

        WorkerDegradationService.Result result = service.apply(config);

        assertFalse(result.isDegraded());
        assertEquals(1, result.getConfiguration().get("parallelThreads"));
    }

    private ReviewWorkerPool.WorkerPoolSnapshot createSnapshot(int configuredSize,
                                                               int activeThreads,
                                                               int queuedTasks) throws Exception {
        Constructor<ReviewWorkerPool.WorkerPoolSnapshot> ctor =
                ReviewWorkerPool.WorkerPoolSnapshot.class.getDeclaredConstructor(
                        int.class, int.class, int.class, int.class, int.class,
                        long.class, long.class, long.class);
        ctor.setAccessible(true);
        return ctor.newInstance(
                configuredSize,
                activeThreads,
                queuedTasks,
                configuredSize,
                configuredSize,
                10L,
                8L,
                System.currentTimeMillis());
    }
}
