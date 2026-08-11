package com.aios.runtime.whispercpp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class DecodeCancellationFenceTest {
    @Test
    public void activeTokenIsCancelledAndDestroyedExactlyOnce() {
        RecordingSignal signal = new RecordingSignal();
        DecodeCancellationFence fence = new DecodeCancellationFence();
        fence.attach(41L, signal);

        fence.cancel(signal);
        fence.cancel(signal);
        fence.finish(41L, signal);

        assertEquals(List.of(41L), signal.cancelled);
        assertEquals(List.of(41L), signal.destroyed);
    }

    @Test
    public void cancellationBeforeAttachAbortsTheNextToken() {
        RecordingSignal signal = new RecordingSignal();
        DecodeCancellationFence fence = new DecodeCancellationFence();
        fence.cancel(signal);

        fence.attach(52L, signal);
        fence.finish(52L, signal);

        assertEquals(List.of(52L), signal.cancelled);
        assertEquals(List.of(52L), signal.destroyed);
    }

    @Test
    public void staleFinishCannotDestroyTheCurrentToken() {
        RecordingSignal signal = new RecordingSignal();
        DecodeCancellationFence fence = new DecodeCancellationFence();
        fence.attach(63L, signal);

        assertThrows(IllegalStateException.class, () -> fence.finish(62L, signal));
        assertEquals(List.of(), signal.destroyed);

        fence.finish(63L, signal);
        assertEquals(List.of(63L), signal.destroyed);
    }

    @Test
    public void overlappingTokensAreRejected() {
        RecordingSignal signal = new RecordingSignal();
        DecodeCancellationFence fence = new DecodeCancellationFence();
        fence.attach(74L, signal);

        assertThrows(IllegalStateException.class, () -> fence.attach(75L, signal));
        fence.finish(74L, signal);
    }

    private static final class RecordingSignal
            implements DecodeCancellationFence.NativeSignal {
        final List<Long> cancelled = new ArrayList<>();
        final List<Long> destroyed = new ArrayList<>();

        @Override
        public void cancel(long token) {
            cancelled.add(token);
        }

        @Override
        public void destroy(long token) {
            destroyed.add(token);
        }
    }
}
