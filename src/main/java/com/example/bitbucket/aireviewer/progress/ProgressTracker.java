package com.example.bitbucket.aireviewer.progress;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe tracker that records {@link ProgressEvent}s during a review run.
 */
public final class ProgressTracker {

    private final CopyOnWriteArrayList<ProgressEvent> events = new CopyOnWriteArrayList<>();

    public ProgressTracker() {
    }

    /**
     * Record a progress milestone.
     *
     * @param stage            identifier of the stage (e.g. diff.collected)
     * @param percentComplete  snapshot percentage (0-100)
     * @param details          optional detail map
     * @return the recorded event
     */
    @Nonnull
    public ProgressEvent record(@Nonnull String stage, int percentComplete, @Nullable Map<String, Object> details) {
        Map<String, Object> safeDetails = details == null
                ? Collections.emptyMap()
                : new LinkedHashMap<>(details);
        ProgressEvent event = ProgressEvent.builder(stage)
                .percentComplete(percentComplete)
                .details(safeDetails)
                .build();
        events.add(event);
        return event;
    }

    /**
     * Record a progress milestone without extra details.
     */
    @Nonnull
    public ProgressEvent record(@Nonnull String stage, int percentComplete) {
        return record(stage, percentComplete, Collections.emptyMap());
    }

    /**
     * Immutable snapshot of the recorded events in chronological order.
     */
    @Nonnull
    public List<ProgressEvent> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(events));
    }

    /**
     * Removes all recorded events.
     */
    public void clear() {
        events.clear();
    }
}
