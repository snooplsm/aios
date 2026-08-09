package com.aios.callintelligence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

public final class RiskAssessmentTrackerTest {
    @Test
    public void knownContactPublishesInitialLegitimacy() {
        RiskAssessmentTracker tracker = tracker(true);

        RiskAssessmentTracker.Update update = tracker.initial();

        assertEquals(1L, update.revision);
        assertEquals(0, update.assessment.score);
        assertEquals(SpamRiskEngine.LIKELY_LEGITIMATE, update.assessment.label);
        assertEquals("known_contact", update.assessment.reasonCode);
        assertEquals(RiskAssessmentTracker.SOURCE_HEURISTIC, update.source);
        assertEquals(1_001L, update.observedAtEpochMillis);
        assertSame(update, tracker.current());
    }

    @Test
    public void unchangedEvidenceDoesNotConsumeARevision() {
        RiskAssessmentTracker tracker = tracker(false);
        assertEquals(1L, tracker.initial().revision);

        assertNull(tracker.observeHeuristic("I am calling today", "en"));
        RiskAssessmentTracker.Update update = tracker.observeHeuristic(
                "I need a plumbing repair and an estimate", "en");

        assertEquals(2L, update.revision);
        assertEquals(SpamRiskEngine.LIKELY_LEGITIMATE, update.assessment.label);
        assertEquals("business_intent", update.assessment.reasonCode);
    }

    @Test
    public void strongerModelAssessmentGetsMonotonicRevisionAndSource() {
        RiskAssessmentTracker tracker = tracker(false);
        tracker.initial();

        RiskAssessmentTracker.Update update = tracker.observeModel(
                82, SpamRiskEngine.HIGH_RISK, "credential_and_payment");

        assertEquals(2L, update.revision);
        assertEquals(82, update.assessment.score);
        assertEquals(SpamRiskEngine.HIGH_RISK, update.assessment.label);
        assertEquals("model_credential_and_payment", update.assessment.reasonCode);
        assertEquals(RiskAssessmentTracker.SOURCE_MODEL, update.source);
        assertNull(tracker.observeModel(60, SpamRiskEngine.SUSPICIOUS, "lower_confidence"));
    }

    private static RiskAssessmentTracker tracker(boolean knownContact) {
        AtomicLong now = new AtomicLong(1_000L);
        return new RiskAssessmentTracker(
                new SpamRiskEngine(knownContact), now::incrementAndGet);
    }
}
