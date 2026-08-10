package com.aios.mediaintelligence;

/** Pure, host-tested sampling and sizing policy for bounded video storyboards. */
final class VideoStoryboardPlan {
    private static final int SAMPLE_COUNT = 20;

    static final class Size {
        final int width;
        final int height;

        Size(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    private VideoStoryboardPlan() {}

    static int sampleCount() {
        return SAMPLE_COUNT;
    }

    static long[] sampleTimesUs(long durationMillis) {
        if (durationMillis <= 0L || durationMillis > Long.MAX_VALUE / 1_000L) {
            throw new IllegalArgumentException("video duration must be positive");
        }
        long durationUs = Math.multiplyExact(durationMillis, 1_000L);
        long[] values = new long[SAMPLE_COUNT];
        long denominator = SAMPLE_COUNT * 2L;
        long wholeSegments = durationUs / denominator;
        long remainder = durationUs % denominator;
        for (int index = 0; index < SAMPLE_COUNT; index++) {
            long numerator = index * 2L + 1L;
            values[index] = wholeSegments * numerator
                    + remainder * numerator / denominator;
        }
        return values;
    }

    static Size scaledSize(
            int encodedWidth,
            int encodedHeight,
            int rotationDegrees,
            int maximumEdge) {
        if (encodedWidth <= 0 || encodedHeight <= 0 || maximumEdge <= 0
                || (rotationDegrees != 0 && rotationDegrees != 90
                && rotationDegrees != 180 && rotationDegrees != 270)) {
            throw new IllegalArgumentException("invalid video dimensions or rotation");
        }
        int displayWidth = encodedWidth;
        int displayHeight = encodedHeight;
        if (rotationDegrees == 90 || rotationDegrees == 270) {
            displayWidth = encodedHeight;
            displayHeight = encodedWidth;
        }
        double scale = Math.min(
                1.0,
                (double) maximumEdge / Math.max(displayWidth, displayHeight));
        return new Size(
                Math.max(1, (int) Math.round(displayWidth * scale)),
                Math.max(1, (int) Math.round(displayHeight * scale)));
    }
}
