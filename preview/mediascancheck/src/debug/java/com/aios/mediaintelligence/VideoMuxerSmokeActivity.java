package com.aios.mediaintelligence;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.util.List;

/** Emulator-only entry point for an actual platform MP4 remux round trip. */
public final class VideoMuxerSmokeActivity extends Activity {
    private static final String TAG = "AiosVideoMuxSmoke";
    private static final String DIGEST =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Uri source = getIntent().getData();
        File output = new File(getCacheDir(), "video_muxer_smoke.mp4");
        try {
            if (source == null) throw new IllegalArgumentException("source URI required");
            try (ParcelFileDescriptor sourceDescriptor =
                         getContentResolver().openFileDescriptor(source, "r");
                 ParcelFileDescriptor outputDescriptor = ParcelFileDescriptor.open(
                         output,
                         ParcelFileDescriptor.MODE_CREATE
                                 | ParcelFileDescriptor.MODE_TRUNCATE
                                 | ParcelFileDescriptor.MODE_READ_WRITE)) {
                if (sourceDescriptor == null) {
                    throw new IllegalStateException("source descriptor unavailable");
                }
                VideoEmbeddedMetadata.Data metadata = new VideoEmbeddedMetadata.Data(
                        1L,
                        DIGEST,
                        "Platform muxer smoke video",
                        List.of("smoke-test"),
                        "en",
                        1.0f,
                        "smoke-vision",
                        DIGEST,
                        1L,
                        VideoEmbeddedMetadata.STATUS_TRANSCRIBED,
                        "smoke-asr",
                        DIGEST,
                        "en",
                        List.of(new VideoEmbeddedMetadata.Cue(
                                0, "en", 250L, 1_250L, "Embedded subtitle smoke", 1.0f)));
                VideoEnhancedCopyMuxer.Result result = VideoEnhancedCopyMuxer.create(
                        sourceDescriptor, outputDescriptor, metadata, null);
                Log.i(TAG, "AIOS_MUX_SMOKE_OK tracks=" + result.sourceTrackCount
                        + " samples=" + result.copiedSampleCount
                        + " bytes=" + result.copiedSampleBytes);
            }
        } catch (Exception error) {
            Log.e(TAG, "AIOS_MUX_SMOKE_FAILED", error);
        } finally {
            if (output.exists() && !output.delete()) {
                Log.w(TAG, "cannot erase muxer smoke output");
            }
            finishAndRemoveTask();
        }
    }
}
