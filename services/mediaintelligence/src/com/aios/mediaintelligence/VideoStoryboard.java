package com.aios.mediaintelligence;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/** Extracts twenty bounded chronological keyframes without rewriting a video. */
final class VideoStoryboard {
    private static final int GRID_COLUMNS = 5;
    private static final int GRID_ROWS = 4;
    private static final int CELL_EDGE_PIXELS = 224;
    private static final int STORYBOARD_WIDTH_PIXELS = GRID_COLUMNS * CELL_EDGE_PIXELS;
    private static final int STORYBOARD_HEIGHT_PIXELS = GRID_ROWS * CELL_EDGE_PIXELS;
    private static final int JPEG_QUALITY = 85;
    private static final String FILE_PREFIX = "aios_video_storyboard_";

    static final class BlockedException extends IOException {
        final String reason;

        BlockedException(String reason) {
            super(reason);
            this.reason = reason;
        }
    }

    static final class InvalidVideoException extends IOException {
        InvalidVideoException(String message) {
            super(message);
        }

        InvalidVideoException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private VideoStoryboard() {}

    static File create(Context context, Uri uri, MediaConstraintProbe constraints)
            throws IOException, InterruptedException {
        eraseCached(context);
        requireAvailable(constraints);
        File output = null;
        Bitmap storyboard = null;
        boolean complete = false;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try (ParcelFileDescriptor source =
                     context.getContentResolver().openFileDescriptor(uri, "r")) {
            if (source == null) throw new FileNotFoundException("video is unavailable");
            try {
                retriever.setDataSource(source.getFileDescriptor());
                long durationMillis = positiveLong(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION),
                        "duration");
                int width = positiveInt(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),
                        "width");
                int height = positiveInt(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
                        "height");
                int rotation = rotation(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
                VideoStoryboardPlan.Size frameSize = VideoStoryboardPlan.scaledSize(
                        width, height, rotation, CELL_EDGE_PIXELS);
                long[] sampleTimes = VideoStoryboardPlan.sampleTimesUs(durationMillis);

                storyboard = Bitmap.createBitmap(
                        STORYBOARD_WIDTH_PIXELS,
                        STORYBOARD_HEIGHT_PIXELS,
                        Bitmap.Config.ARGB_8888);
                storyboard.eraseColor(Color.BLACK);
                Canvas canvas = new Canvas(storyboard);
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                int extractedFrames = 0;
                for (int index = 0; index < sampleTimes.length; index++) {
                    requireNotInterrupted();
                    requireAvailable(constraints);
                    Bitmap frame = retriever.getScaledFrameAtTime(
                            sampleTimes[index],
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            frameSize.width,
                            frameSize.height);
                    if (frame == null) continue;
                    try {
                        int column = index % GRID_COLUMNS;
                        int row = index / GRID_COLUMNS;
                        float scale = Math.min(
                                1f,
                                (float) CELL_EDGE_PIXELS
                                        / Math.max(frame.getWidth(), frame.getHeight()));
                        float drawnWidth = frame.getWidth() * scale;
                        float drawnHeight = frame.getHeight() * scale;
                        float left = column * CELL_EDGE_PIXELS
                                + (CELL_EDGE_PIXELS - drawnWidth) / 2f;
                        float top = row * CELL_EDGE_PIXELS
                                + (CELL_EDGE_PIXELS - drawnHeight) / 2f;
                        canvas.drawBitmap(
                                frame,
                                null,
                                new RectF(left, top, left + drawnWidth, top + drawnHeight),
                                paint);
                        extractedFrames++;
                    } finally {
                        frame.recycle();
                    }
                }
                if (!VideoStoryboardPlan.hasCompleteSampleSet(extractedFrames)) {
                    throw new InvalidVideoException(
                            "video yielded " + extractedFrames + " of "
                                    + VideoStoryboardPlan.sampleCount()
                                    + " required storyboard frames");
                }
                requireAvailable(constraints);
                output = File.createTempFile(FILE_PREFIX, ".jpg", context.getCacheDir());
                try (FileOutputStream stream = new FileOutputStream(output)) {
                    if (!storyboard.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)) {
                        throw new IOException("cannot encode video storyboard");
                    }
                    stream.getFD().sync();
                }
                complete = true;
                return output;
            } catch (IllegalArgumentException | IllegalStateException error) {
                throw new InvalidVideoException("video metadata or frames are invalid", error);
            }
        } finally {
            retriever.release();
            if (storyboard != null) storyboard.recycle();
            if (output != null && !complete) {
                output.delete();
            }
        }
    }

    static boolean isStoryboard(File file) {
        return file != null && file.getName().startsWith(FILE_PREFIX);
    }

    static void eraseCached(Context context) throws IOException {
        File[] cached = context.getCacheDir().listFiles();
        if (cached == null) throw new IOException("cannot inspect storyboard cache");
        for (File file : cached) {
            if (!isStoryboard(file)) continue;
            if (!file.isFile() || !file.delete()) {
                throw new IOException("cannot erase cached video storyboard");
            }
        }
    }

    private static long positiveLong(String value, String field)
            throws InvalidVideoException {
        if (value == null) throw new InvalidVideoException("video " + field + " is absent");
        long parsed = Long.parseLong(value);
        if (parsed <= 0L) throw new InvalidVideoException("video " + field + " is invalid");
        return parsed;
    }

    private static int positiveInt(String value, String field)
            throws InvalidVideoException {
        if (value == null) throw new InvalidVideoException("video " + field + " is absent");
        int parsed = Integer.parseInt(value);
        if (parsed <= 0) throw new InvalidVideoException("video " + field + " is invalid");
        return parsed;
    }

    private static int rotation(String value) throws InvalidVideoException {
        if (value == null || value.isEmpty()) return 0;
        int parsed = Integer.parseInt(value);
        if (parsed != 0 && parsed != 90 && parsed != 180 && parsed != 270) {
            throw new InvalidVideoException("video rotation is invalid");
        }
        return parsed;
    }

    private static void requireAvailable(MediaConstraintProbe constraints)
            throws BlockedException {
        String reason = constraints.blockedReason();
        if (reason != null) throw new BlockedException(reason);
    }

    private static void requireNotInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("video storyboard extraction interrupted");
        }
    }
}
