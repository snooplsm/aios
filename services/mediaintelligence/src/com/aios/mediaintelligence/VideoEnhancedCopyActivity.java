package com.aios.mediaintelligence;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.Toast;

/** User-confirmed share target that creates a new MP4 with embedded AIOS tracks. */
public final class VideoEnhancedCopyActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Uri source = sharedMp4(getIntent());
        if (source == null) {
            Toast.makeText(this, "Choose one MediaStore MP4 video", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Create AI-enhanced copy?")
                .setMessage(
                        "AIOS will make a new MP4 in Movies/AIOS with its description "
                                + "and timed subtitles embedded. The original stays unchanged. "
                                + "The subtitles use an AIOS metadata track; some video "
                                + "players may ignore it.")
                .setNegativeButton("Cancel", (dialog, which) -> finish())
                .setPositiveButton("Create copy", (dialog, which) -> {
                    try {
                        Intent request = VideoEnhancedCopyService.request(this, source);
                        request.setClipData(ClipData.newRawUri("Source video", source));
                        request.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startForegroundService(request);
                    } catch (RuntimeException error) {
                        Toast.makeText(
                                this,
                                "Could not start the AI-enhanced copy",
                                Toast.LENGTH_LONG).show();
                    }
                    finish();
                })
                .setOnCancelListener(dialog -> finish())
                .show();
    }

    private Uri sharedMp4(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())
                || !"video/mp4".equalsIgnoreCase(intent.getType())) {
            return null;
        }
        Uri source = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        if (source == null && intent.getClipData() != null
                && intent.getClipData().getItemCount() == 1) {
            source = intent.getClipData().getItemAt(0).getUri();
        }
        if (!VideoEnhancedCopyService.isCanonicalVideoUri(source)) return null;
        try {
            String actualMime = getContentResolver().getType(source);
            return "video/mp4".equalsIgnoreCase(actualMime) ? source : null;
        } catch (RuntimeException error) {
            return null;
        }
    }
}
