package com.aios.runtime.whispercpp;

import java.util.Objects;

/** Builds replaceable partials for one live ASR turn and resets after its final chunk. */
final class StreamingAsrTurnAccumulator {
    static final class Emission {
        final String text;
        final String language;
        final long startMillis;
        final long endMillis;
        final boolean finalChunk;

        private Emission(
                String text,
                String language,
                long startMillis,
                long endMillis,
                boolean finalChunk) {
            this.text = text;
            this.language = language;
            this.startMillis = startMillis;
            this.endMillis = endMillis;
            this.finalChunk = finalChunk;
        }
    }

    private final StringBuilder text = new StringBuilder();
    private String language = "und";
    private long startMillis;
    private long endMillis;

    Emission acceptDecoded(
            String decodedText,
            String decodedLanguage,
            long windowStartMillis,
            long windowEndMillis,
            boolean endOfTurn) {
        Objects.requireNonNull(decodedText, "decodedText");
        Objects.requireNonNull(decodedLanguage, "decodedLanguage");
        if (decodedLanguage.isEmpty()) {
            throw new IllegalArgumentException("decodedLanguage must not be empty");
        }
        if (windowStartMillis < 0L || windowEndMillis < windowStartMillis) {
            throw new IllegalArgumentException("invalid source timestamps");
        }

        if (!decodedText.isEmpty()) {
            if (text.length() == 0) {
                startMillis = windowStartMillis;
            } else {
                text.append(' ');
            }
            text.append(decodedText);
            language = decodedLanguage;
        }
        if (text.length() == 0) {
            return null;
        }

        // A decoded silence window still advances the turn to its source-audio boundary.
        endMillis = windowEndMillis;
        Emission emission = snapshot(endOfTurn);
        if (endOfTurn) {
            reset();
        }
        return emission;
    }

    Emission finishTurn() {
        if (text.length() == 0) {
            return null;
        }
        Emission emission = snapshot(true);
        reset();
        return emission;
    }

    private Emission snapshot(boolean finalChunk) {
        return new Emission(text.toString(), language, startMillis, endMillis, finalChunk);
    }

    private void reset() {
        text.setLength(0);
        language = "und";
        startMillis = 0L;
        endMillis = 0L;
    }
}
