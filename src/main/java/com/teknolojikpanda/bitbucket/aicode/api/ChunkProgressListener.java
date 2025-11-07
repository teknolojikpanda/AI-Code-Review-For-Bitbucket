package com.teknolojikpanda.bitbucket.aicode.api;

import com.teknolojikpanda.bitbucket.aicode.model.ReviewChunk;

import javax.annotation.Nonnull;

/**
 * Listener invoked when chunk execution state changes inside the review orchestrator.
 */
public interface ChunkProgressListener {

    /**
     * Called immediately before a chunk begins AI analysis.
     *
     * @param chunk active chunk
     * @param index zero-based chunk index
     * @param total total number of chunks scheduled for the run
     */
    default void onChunkStarted(@Nonnull ReviewChunk chunk, int index, int total) {
        // no-op
    }

    /**
     * Called after a chunk finishes (successfully or not).
     *
     * @param chunk   chunk that just completed
     * @param index   zero-based chunk index
     * @param total   total number of chunks scheduled for the run
     * @param success whether the chunk finished without errors
     */
    default void onChunkCompleted(@Nonnull ReviewChunk chunk, int index, int total, boolean success) {
        // no-op
    }
}
