package com.aios.callintelligence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SpamRiskEngineTest {
    @Test
    public void englishGiftCardAndAuthorityClaimIsHighRisk() {
        SpamRiskEngine engine = new SpamRiskEngine(false);
        SpamRiskEngine.Assessment value = engine.observe(
                "This is the Internal Revenue Service. Act now and pay with gift cards.", "en");

        assertEquals(SpamRiskEngine.HIGH_RISK, value.label);
        assertTrue(value.score >= 75);
        assertEquals("gift_card_payment", value.reasonCode);
    }

    @Test
    public void spanishCoercionAndTransferIsSuspicious() {
        SpamRiskEngine engine = new SpamRiskEngine(false);
        SpamRiskEngine.Assessment value = engine.observe(
                "Su cuenta será suspendida. Haga una transferencia bancaria inmediatamente.",
                "es");

        assertEquals(SpamRiskEngine.SUSPICIOUS, value.label);
        assertTrue(value.score >= 50);
    }

    @Test
    public void repeatedTranscriptDoesNotInflateScore() {
        SpamRiskEngine engine = new SpamRiskEngine(false);
        int first = engine.observe("Please install AnyDesk for remote access.", "en").score;
        int second = engine.observe("Please install AnyDesk for remote access.", "en").score;

        assertEquals(first, second);
    }

    @Test
    public void correctedPartialRetractsProvisionalRisk() {
        SpamRiskEngine engine = new SpamRiskEngine(false);

        SpamRiskEngine.Assessment provisional = engine.observeRevision(
                "Buy a gift card right now", "en", false);
        SpamRiskEngine.Assessment corrected = engine.observeRevision(
                "I need an estimate for a plumbing repair", "en", false);

        assertTrue(provisional.score >= 50);
        assertEquals(SpamRiskEngine.LIKELY_LEGITIMATE, corrected.label);
        assertEquals("business_intent", corrected.reasonCode);
    }

    @Test
    public void finalizedSignalSurvivesTheNextPartial() {
        SpamRiskEngine engine = new SpamRiskEngine(false);
        int finalized = engine.observeRevision(
                "Please buy a gift card", "en", true).score;

        int nextPartial = engine.observeRevision(
                "I am calling today", "en", false).score;

        assertEquals(finalized, nextPartial);
    }

    @Test
    public void ordinaryBusinessPurposeCanBecomeLikelyLegitimate() {
        SpamRiskEngine engine = new SpamRiskEngine(false);
        SpamRiskEngine.Assessment value = engine.observe(
                "I need an estimate for a plumbing repair at our job site.", "en");

        assertEquals(SpamRiskEngine.LIKELY_LEGITIMATE, value.label);
        assertEquals("business_intent", value.reasonCode);
    }

    @Test
    public void unsupportedLanguageDoesNotChangeAssessment() {
        SpamRiskEngine engine = new SpamRiskEngine(false);
        SpamRiskEngine.Assessment before = engine.current();
        SpamRiskEngine.Assessment after = engine.observe("gift card", "fr");

        assertEquals(before.score, after.score);
        assertEquals(before.label, after.label);
    }
}
