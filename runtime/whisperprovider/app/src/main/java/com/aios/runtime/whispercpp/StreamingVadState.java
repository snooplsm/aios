package com.aios.runtime.whispercpp;

/** Tracks speech turns over fixed-duration VAD frames. */
final class StreamingVadState {
    enum Event {
        IGNORED,
        STARTED,
        CONTINUED,
        ENDED,
    }

    private final int endpointSilenceFrames;
    private boolean active;
    private int silenceFrames;

    StreamingVadState(int endpointSilenceFrames) {
        if (endpointSilenceFrames <= 0) {
            throw new IllegalArgumentException("endpoint silence must contain at least one frame");
        }
        this.endpointSilenceFrames = endpointSilenceFrames;
    }

    Event accept(boolean speech) {
        if (!active) {
            if (!speech) return Event.IGNORED;
            active = true;
            silenceFrames = 0;
            return Event.STARTED;
        }
        if (speech) {
            silenceFrames = 0;
            return Event.CONTINUED;
        }
        silenceFrames++;
        if (silenceFrames >= endpointSilenceFrames) {
            active = false;
            silenceFrames = 0;
            return Event.ENDED;
        }
        return Event.CONTINUED;
    }

    boolean isActive() {
        return active;
    }
}
