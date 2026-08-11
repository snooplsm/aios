package com.aios.mediaintelligence;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MediaGenerationBaselinePolicyTest {
    @Test
    public void firstInstallEstablishesBaseline() {
        assertTrue(MediaGenerationBaselinePolicy.requiresBaseline(
                "provider-v1", 100L, false, null, 0L));
    }

    @Test
    public void providerIdentityChangeEstablishesBaseline() {
        assertTrue(MediaGenerationBaselinePolicy.requiresBaseline(
                "provider-v2", 100L, true, "provider-v1", 90L));
    }

    @Test
    public void providerGenerationRegressionEstablishesBaseline() {
        assertTrue(MediaGenerationBaselinePolicy.requiresBaseline(
                "provider-v1", 10L, true, "provider-v1", 20L));
    }

    @Test
    public void matchingCursorPreservesRecoveryWork() {
        assertFalse(MediaGenerationBaselinePolicy.requiresBaseline(
                "provider-v1", 100L, true, "provider-v1", 90L));
    }

    @Test
    public void cursorAtProviderTipRemainsValid() {
        assertFalse(MediaGenerationBaselinePolicy.requiresBaseline(
                "provider-v1", 100L, true, "provider-v1", 100L));
    }
}
