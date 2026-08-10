package com.aios.mediaintelligence;

import android.content.Context;

/** Compile-check seam; production scheduling is supplied by the platform module. */
final class MediaInferenceJobService {
    private MediaInferenceJobService() {}

    static void schedule(Context context, int workClass) {
        // The compile check validates MediaObserverService against the public SDK.
    }
}
