package com.teknolojikpanda.bitbucket.aireviewer.service;

public class ReviewSchedulerPausedException extends RuntimeException {

    private final ReviewSchedulerStateService.SchedulerState state;

    public ReviewSchedulerPausedException(ReviewSchedulerStateService.SchedulerState state) {
        super(buildMessage(state));
        this.state = state;
    }

    private static String buildMessage(ReviewSchedulerStateService.SchedulerState state) {
        StringBuilder builder = new StringBuilder("AI reviewer scheduler is ");
        builder.append(state.getMode().name().toLowerCase());
        if (state.getReason() != null && !state.getReason().isBlank()) {
            builder.append(": ").append(state.getReason());
        }
        return builder.toString();
    }

    public ReviewSchedulerStateService.SchedulerState getState() {
        return state;
    }
}
