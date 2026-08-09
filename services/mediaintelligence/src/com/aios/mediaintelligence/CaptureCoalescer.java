package com.aios.mediaintelligence;

import android.os.Handler;

import java.util.ArrayList;
import java.util.List;

/** Debounces camera activity so bursts do not contend with capture or calls. */
final class CaptureCoalescer {
    static final long QUIET_PERIOD_MILLIS = 5_000L;

    interface Callback {
        void onSettled(List<ObservedMedia> group);
    }

    static final class ObservedMedia {
        final String uri;
        final long generation;
        final String mimeType;

        ObservedMedia(String uri, long generation, String mimeType) {
            this.uri = uri;
            this.generation = generation;
            this.mimeType = mimeType;
        }
    }

    private final Handler handler;
    private final Callback callback;
    private final List<ObservedMedia> pending = new ArrayList<>();
    private final Runnable flush = this::flush;

    CaptureCoalescer(Handler handler, Callback callback) {
        this.handler = handler;
        this.callback = callback;
    }

    void add(ObservedMedia media) {
        pending.add(media);
        handler.removeCallbacks(flush);
        handler.postDelayed(flush, QUIET_PERIOD_MILLIS);
    }

    void close() {
        handler.removeCallbacks(flush);
        pending.clear();
    }

    private void flush() {
        if (pending.isEmpty()) {
            return;
        }
        List<ObservedMedia> settled = List.copyOf(pending);
        pending.clear();
        callback.onSettled(settled);
    }
}
