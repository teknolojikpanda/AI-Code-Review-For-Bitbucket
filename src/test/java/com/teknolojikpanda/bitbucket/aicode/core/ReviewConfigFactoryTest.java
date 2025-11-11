package com.teknolojikpanda.bitbucket.aicode.core;

import com.teknolojikpanda.bitbucket.aicode.model.ReviewConfig;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class ReviewConfigFactoryTest {

    private ReviewConfigFactory factory;

    @Before
    public void setUp() {
        factory = new ReviewConfigFactory();
    }

    @Test
    public void respectsChunkAndOverviewOverrides() {
        Map<String, Object> config = new HashMap<>();
        config.put("chunkMaxRetries", 5);
        config.put("chunkRetryDelay", 1800);
        config.put("overviewMaxRetries", 2);
        config.put("overviewRetryDelay", 3200);

        ReviewConfig reviewConfig = factory.from(config);

        assertEquals(5, reviewConfig.getChunkMaxRetries());
        assertEquals(1800, reviewConfig.getChunkRetryDelayMs());
        assertEquals(2, reviewConfig.getOverviewMaxRetries());
        assertEquals(3200, reviewConfig.getOverviewRetryDelayMs());
    }

    @Test
    public void fallsBackToLegacyRetryKeys() {
        Map<String, Object> config = new HashMap<>();
        config.put("maxRetries", 4);
        config.put("baseRetryDelay", 2500);

        ReviewConfig reviewConfig = factory.from(config);

        assertEquals(4, reviewConfig.getChunkMaxRetries());
        assertEquals(4, reviewConfig.getOverviewMaxRetries());
        assertEquals(2500, reviewConfig.getChunkRetryDelayMs());
        assertEquals(2500, reviewConfig.getOverviewRetryDelayMs());
    }
}
