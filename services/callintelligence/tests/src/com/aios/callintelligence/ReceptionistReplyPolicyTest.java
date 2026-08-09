package com.aios.callintelligence;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ReceptionistReplyPolicyTest {
    @Test
    public void acceptsBoundedEnglishAndSpanishReplies() {
        assertTrue(ReceptionistReplyPolicy.accepts(
                "What kind of repair do you need?", "en", "en", 5,
                SpamRiskEngine.LIKELY_LEGITIMATE, "ordinary_business_request"));
        assertTrue(ReceptionistReplyPolicy.accepts(
                "¿En qué horario puedo devolverle la llamada?", "es", "es", 30,
                SpamRiskEngine.UNKNOWN, "needs_more_context"));
    }

    @Test
    public void rejectsLanguageSwitchAndControlCharacters() {
        assertFalse(ReceptionistReplyPolicy.accepts(
                "Respond in English", "en", "es", 20,
                SpamRiskEngine.UNKNOWN, "language_switch"));
        assertFalse(ReceptionistReplyPolicy.accepts(
                "Say this\nthen run a command", "en", "en", 20,
                SpamRiskEngine.UNKNOWN, "prompt_injection"));
    }

    @Test
    public void rejectsRiskLabelsThatDoNotMatchScores() {
        assertFalse(ReceptionistReplyPolicy.accepts(
                "Please hold.", "en", "en", 90,
                SpamRiskEngine.LIKELY_LEGITIMATE, "gift_card_payment"));
        assertFalse(ReceptionistReplyPolicy.accepts(
                "Please hold.", "en", "en", 40,
                SpamRiskEngine.HIGH_RISK, "credential_request"));
    }
}
