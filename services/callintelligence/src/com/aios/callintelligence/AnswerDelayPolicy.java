package com.aios.callintelligence;

import java.util.concurrent.ThreadLocalRandom;

/** Pure owner-selected delay policy evaluated independently for every eligible call. */
final class AnswerDelayPolicy {
    static final String FIXED_1_SECOND = "fixed_1000_ms";
    static final String FIXED_2_SECONDS = "fixed_2000_ms";
    static final String FIXED_3_SECONDS = "fixed_3000_ms";
    static final String FIXED_4_SECONDS = "fixed_4000_ms";
    static final String RANDOM_1_01_TO_3_99_SECONDS = "random_1010_3990_ms";
    static final String DEFAULT_MODE = FIXED_2_SECONDS;

    static final long RANDOM_MIN_MILLIS = 1_010L;
    static final long RANDOM_MAX_MILLIS = 3_990L;

    interface RandomSource {
        long nextLong(long originInclusive, long boundExclusive);
    }

    private final String mode;
    private final RandomSource random;

    AnswerDelayPolicy(String mode) {
        this(mode, (origin, bound) -> ThreadLocalRandom.current().nextLong(origin, bound));
    }

    AnswerDelayPolicy(String mode, RandomSource random) {
        this.mode = isKnownMode(mode) ? mode : DEFAULT_MODE;
        this.random = random;
    }

    long nextDelayMillis() {
        switch (mode) {
            case FIXED_1_SECOND:
                return 1_000L;
            case FIXED_3_SECONDS:
                return 3_000L;
            case FIXED_4_SECONDS:
                return 4_000L;
            case RANDOM_1_01_TO_3_99_SECONDS:
                return random.nextLong(RANDOM_MIN_MILLIS, RANDOM_MAX_MILLIS + 1L);
            case FIXED_2_SECONDS:
            default:
                return 2_000L;
        }
    }

    static boolean isKnownMode(String candidate) {
        return FIXED_1_SECOND.equals(candidate)
                || FIXED_2_SECONDS.equals(candidate)
                || FIXED_3_SECONDS.equals(candidate)
                || FIXED_4_SECONDS.equals(candidate)
                || RANDOM_1_01_TO_3_99_SECONDS.equals(candidate);
    }
}
