package com.aios.mediaintelligence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MediaInputPolicyTest {
    @Test
    public void imagesUseTheOriginalImageContract() {
        assertEquals(
                MediaInputPolicy.CAPABILITY_IMAGE,
                MediaInputPolicy.capability("image/heic"));
        assertEquals(
                "image/heic",
                MediaInputPolicy.submittedMimeType("image/heic"));
        assertTrue(MediaInputPolicy.isImage("image/jpeg"));
    }

    @Test
    public void videosUseAnExplicitJpegStoryboardContract() {
        assertEquals(
                MediaInputPolicy.CAPABILITY_VIDEO,
                MediaInputPolicy.capability("video/mp4"));
        assertEquals(
                MediaInputPolicy.STORYBOARD_MIME_TYPE,
                MediaInputPolicy.submittedMimeType("video/mp4"));
        assertTrue(MediaInputPolicy.isVideo("video/quicktime"));
    }

    @Test
    public void unrelatedMediaTypeFailsClosed() {
        assertNull(MediaInputPolicy.capability("audio/mp4"));
        assertNull(MediaInputPolicy.submittedMimeType(null));
    }
}
