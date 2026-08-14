package com.aios.runtime.whispercpp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class TurnLanguagePolicyTest {
    @Test
    public void firstSupportedWindowLocksDecoderUntilTurnEnds() {
        TurnLanguagePolicy policy = new TurnLanguagePolicy();

        assertEquals("auto", policy.decoderLanguage());
        assertEquals("es", policy.acceptDecoderResult("es"));
        assertEquals("es", policy.decoderLanguage());
        assertEquals("es", policy.acceptDecoderResult("zh"));

        policy.finishTurn();
        assertEquals("auto", policy.decoderLanguage());
        assertEquals("en", policy.acceptDecoderResult("en"));
    }

    @Test
    public void unsupportedInitialDetectionFailsClosed() {
        TurnLanguagePolicy policy = new TurnLanguagePolicy();

        assertThrows(IllegalArgumentException.class,
                () -> policy.acceptDecoderResult("zh"));
        assertEquals("auto", policy.decoderLanguage());
    }
}
