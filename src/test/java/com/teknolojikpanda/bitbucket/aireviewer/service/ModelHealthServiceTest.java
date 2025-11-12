package com.teknolojikpanda.bitbucket.aireviewer.service;

import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ModelHealthServiceTest {

    private ModelHealthService service;

    @Before
    public void setUp() {
        service = new ModelHealthService();
    }

    @Test
    public void applySkipsPrimaryWhenDegradedAndFallbackHealthy() {
        Map<String, Object> config = new HashMap<>();
        config.put("ollamaUrl", "http://localhost:11434");
        config.put("ollamaModel", "primary-model");
        config.put("fallbackModel", "fallback-model");

        service.recordFailure("http://localhost:11434", "primary-model", "timeout");
        service.recordFailure("http://localhost:11434", "primary-model", "timeout");

        ModelHealthService.Result result = service.apply(config);

        assertTrue(result.getConfiguration().containsKey("skipPrimaryModel"));
        assertTrue(result.isPrimaryDegraded());
        assertTrue(result.isFailoverApplied());
    }

    @Test
    public void successProbeClearsDegradedState() {
        Map<String, Object> config = new HashMap<>();
        config.put("ollamaUrl", "http://localhost:11434");
        config.put("ollamaModel", "primary-model");
        config.put("fallbackModel", "fallback-model");

        service.recordFailure("http://localhost:11434", "primary-model", "timeout");
        service.recordFailure("http://localhost:11434", "primary-model", "timeout");
        service.recordSuccess("http://localhost:11434", "primary-model", 40);

        ModelHealthService.Result result = service.apply(config);

        assertFalse(result.isPrimaryDegraded());
        assertFalse(result.getConfiguration().containsKey("skipPrimaryModel"));
    }
}
