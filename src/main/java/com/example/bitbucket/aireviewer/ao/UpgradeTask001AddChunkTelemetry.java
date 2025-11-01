package com.example.bitbucket.aireviewer.ao;

import com.atlassian.activeobjects.spi.ActiveObjectsUpgradeTask;
import net.java.ao.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;

/**
 * Adds request/response telemetry columns to {@link AIReviewChunk} records.
 */
public class UpgradeTask001AddChunkTelemetry implements ActiveObjectsUpgradeTask {

    private static final Logger log = LoggerFactory.getLogger(UpgradeTask001AddChunkTelemetry.class);

    @Nonnull
    @Override
    public String getModelVersion() {
        return "1";
    }

    @Override
    public void upgrade(@Nonnull EntityManager entityManager) {
        log.info("Running AO upgrade 001: migrating AIReviewChunk telemetry columns");
        entityManager.migrate(AIReviewChunk.class);
    }
}
