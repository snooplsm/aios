package com.aios.mediaintelligence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class EnhancedVideoReadPolicyTest {
    private static final String URI = "content://media/external_primary/video/media/42";

    @Test
    public void acceptsOnlyCanonicalPublishedOwnedMp4() {
        EnhancedVideoReadPolicy.validateMediaRow(
                URI, URI, "video/mp4", EnhancedVideoReadPolicy.OWNER_PACKAGE, 0, 0, 7L);

        assertThrows(IllegalArgumentException.class, () ->
                EnhancedVideoReadPolicy.validateMediaRow(
                        URI + "?x=1", URI, "video/mp4",
                        EnhancedVideoReadPolicy.OWNER_PACKAGE, 0, 0, 7L));
        assertThrows(IllegalArgumentException.class, () ->
                EnhancedVideoReadPolicy.validateMediaRow(
                        URI, URI, "video/mp4", "com.example.other", 0, 0, 7L));
        assertThrows(IllegalArgumentException.class, () ->
                EnhancedVideoReadPolicy.validateMediaRow(
                        URI, URI, "video/mp4",
                        EnhancedVideoReadPolicy.OWNER_PACKAGE, 1, 0, 7L));
    }

    @Test
    public void requiresOneDescriptionAndAtMostOneSubtitleTrack() {
        EnhancedVideoReadPolicy.validateContainer(5, 1, 1, true, 2_000_000L);

        assertThrows(IllegalArgumentException.class, () ->
                EnhancedVideoReadPolicy.validateContainer(5, 0, 1, true, 2_000_000L));
        assertThrows(IllegalArgumentException.class, () ->
                EnhancedVideoReadPolicy.validateContainer(5, 1, 2, true, 2_000_000L));
        assertThrows(IllegalArgumentException.class, () ->
                EnhancedVideoReadPolicy.validateContainer(5, 1, 1, false, 2_000_000L));
    }

    @Test
    public void cuePagesAreSmallAndCannotSkipPastTranscript() {
        assertEquals(16, EnhancedVideoReadPolicy.pageEnd(0, 16, 100));
        assertEquals(100, EnhancedVideoReadPolicy.pageEnd(96, 16, 100));
        assertEquals(100, EnhancedVideoReadPolicy.pageEnd(100, 1, 100));

        assertThrows(IllegalArgumentException.class, () ->
                EnhancedVideoReadPolicy.pageEnd(0, 17, 100));
        assertThrows(IllegalArgumentException.class, () ->
                EnhancedVideoReadPolicy.pageEnd(101, 1, 100));
        assertThrows(IllegalArgumentException.class, () ->
                EnhancedVideoReadPolicy.pageEnd(0, 1, 4_097));
    }
}
