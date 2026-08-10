package com.aios.mediaintelligence;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class VideoExportRecoveryPolicyTest {
    @Test
    public void deletesOnlyOwnedMarkedPendingMp4() {
        assertEquals(
                VideoExportRecoveryPolicy.DELETE_PENDING,
                VideoExportRecoveryPolicy.decide(true, true, true, true, true));
        assertEquals(
                VideoExportRecoveryPolicy.FORGET_UNTRUSTED,
                VideoExportRecoveryPolicy.decide(true, true, false, true, true));
        assertEquals(
                VideoExportRecoveryPolicy.FORGET_UNTRUSTED,
                VideoExportRecoveryPolicy.decide(true, true, true, false, true));
        assertEquals(
                VideoExportRecoveryPolicy.FORGET_UNTRUSTED,
                VideoExportRecoveryPolicy.decide(true, true, true, true, false));
    }

    @Test
    public void preservesPublishedOwnedMp4WithoutPendingMarker() {
        assertEquals(
                VideoExportRecoveryPolicy.PRESERVE_PUBLISHED,
                VideoExportRecoveryPolicy.decide(true, false, true, true, false));
    }

    @Test
    public void forgetsAnAbsentOutput() {
        assertEquals(
                VideoExportRecoveryPolicy.FORGET_ABSENT,
                VideoExportRecoveryPolicy.decide(false, false, false, false, false));
    }
}
