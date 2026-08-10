package com.aios.mediaintelligence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.junit.Test;

public final class MediaAssociationPolicyTest {
    private static final String NUMBER = "number:"
            + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    public void draftAndCarrierSubmissionCannotPublish() {
        assertFalse(MediaAssociationPolicy.publishable(true, false, true, false));
        assertFalse(MediaAssociationPolicy.publishable(false, true, true, false));
        assertFalse(MediaAssociationPolicy.publishable(true, true, false, false));
        assertFalse(MediaAssociationPolicy.publishable(true, true, true, true));
        assertTrue(MediaAssociationPolicy.publishable(true, true, true, false));
    }

    @Test
    public void tokensSourcesAndOpaqueIdentityFailClosed() {
        String token = "123e4567-e89b-12d3-a456-426614174000";
        MediaAssociationPolicy.validateStage(
                token, "image/jpeg", NUMBER, "", new String[]{NUMBER}, 1L);
        assertEquals("mms:42", MediaAssociationPolicy.sourceId(42L));
        MediaAssociationPolicy.validateSourceId("mms:42");

        assertIllegal(() -> MediaAssociationPolicy.validateToken("draft"));
        assertIllegal(() -> MediaAssociationPolicy.validateSourceId("sms:42"));
        assertIllegal(() -> MediaAssociationPolicy.validateStage(
                token, "video/mp4", NUMBER, "", new String[]{NUMBER}, 1L));
        assertIllegal(() -> MediaAssociationPolicy.validateStage(
                token, "image/jpeg", NUMBER, "", new String[]{"number:+1555"}, 1L));
    }

    @Test
    public void selectedPhotoHashIsExactAndNonEmpty() throws IOException {
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                MediaAssociationPolicy.sha256(new ByteArrayInputStream(
                        new byte[]{'a', 'b', 'c'})));
        try {
            MediaAssociationPolicy.sha256(new ByteArrayInputStream(new byte[0]));
            fail("expected IOException");
        } catch (IOException expected) {
            // Expected.
        }
    }

    private static void assertIllegal(Runnable operation) {
        try {
            operation.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
