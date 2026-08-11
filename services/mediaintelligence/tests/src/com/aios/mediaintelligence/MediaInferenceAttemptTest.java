package com.aios.mediaintelligence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.Test;

public final class MediaInferenceAttemptTest {
    @Test
    public void firstTerminalCallbackOwnsResult() throws Exception {
        MediaInferenceAttempt<String> attempt = new MediaInferenceAttempt<>();
        assertTrue(attempt.attachSession(41L));
        assertTrue(attempt.markSubmitted());

        assertTrue(attempt.complete("caption"));
        assertFalse(attempt.fail(9, "late_error"));
        assertEquals(MediaInferenceAttempt.NO_SESSION, attempt.cancel(3, "late_cancel"));
        assertTrue(attempt.await(0L, TimeUnit.MILLISECONDS));

        MediaInferenceAttempt.Snapshot<String> value = attempt.snapshot();
        assertEquals("caption", value.result);
        assertFalse(value.cancelled);
    }

    @Test
    public void disconnectWakesWaiterAndRejectsLateSuccess() throws Exception {
        MediaInferenceAttempt<String> attempt = new MediaInferenceAttempt<>();
        assertTrue(attempt.attachSession(7L));

        assertTrue(attempt.fail(1, "model_broker_disconnected"));
        assertTrue(attempt.await(0L, TimeUnit.MILLISECONDS));
        assertFalse(attempt.complete("stale result"));

        MediaInferenceAttempt.Snapshot<String> value = attempt.snapshot();
        assertNull(value.result);
        assertEquals(1, value.errorCode);
        assertEquals("model_broker_disconnected", value.reason);
    }

    @Test
    public void cancellationReturnsOnlyItsOwnSession() {
        MediaInferenceAttempt<String> attempt = new MediaInferenceAttempt<>();
        assertTrue(attempt.attachSession(99L));

        assertEquals(99L, attempt.cancel(3, "job_stopped"));
        assertEquals(MediaInferenceAttempt.NO_SESSION, attempt.cancel(3, "again"));
        assertFalse(attempt.complete("stale"));
        assertTrue(attempt.snapshot().cancelled);
    }

    @Test
    public void terminalCallbackBeforeSessionAttachCannotAdoptLaterSession() {
        MediaInferenceAttempt<String> attempt = new MediaInferenceAttempt<>();

        assertTrue(attempt.fail(5, "synchronous_rejection"));
        assertFalse(attempt.attachSession(123L));
        assertEquals(MediaInferenceAttempt.NO_SESSION, attempt.remainingSession());
    }

    @Test
    public void resultBeforeInputSubmissionFailsClosed() {
        MediaInferenceAttempt<String> attempt = new MediaInferenceAttempt<>();
        assertTrue(attempt.attachSession(55L));

        assertFalse(attempt.complete("impossible result"));
        assertFalse(attempt.markSubmitted());
        assertEquals("model_result_before_submission", attempt.snapshot().reason);
    }

    @Test(expected = IllegalStateException.class)
    public void activeAttemptHasNoSnapshot() {
        new MediaInferenceAttempt<String>().snapshot();
    }
}
