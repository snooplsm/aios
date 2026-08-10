package com.aios.media;

import com.aios.media.EnhancedVideoCue;
import com.aios.media.EnhancedVideoInfo;

/** Signature-only, read-only access to metadata embedded in AIOS-enhanced MP4s. */
interface IEnhancedVideoMetadata {
    EnhancedVideoInfo getInfo(String mediaUri);
    List<EnhancedVideoCue> getCues(
            String mediaUri,
            long expectedMediaGeneration,
            int startSequence,
            int limit);
}
