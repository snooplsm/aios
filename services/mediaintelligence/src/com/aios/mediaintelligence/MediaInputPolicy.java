package com.aios.mediaintelligence;

/** Maps a MediaStore type to the explicit broker contract used for inference. */
final class MediaInputPolicy {
    static final String CAPABILITY_IMAGE = "image_understanding";
    static final String CAPABILITY_VIDEO = "video_understanding";
    static final String STORYBOARD_MIME_TYPE = "image/jpeg";

    private MediaInputPolicy() {}

    static boolean isImage(String mimeType) {
        return mimeType != null && mimeType.startsWith("image/");
    }

    static boolean isVideo(String mimeType) {
        return mimeType != null && mimeType.startsWith("video/");
    }

    static String capability(String mimeType) {
        if (isImage(mimeType)) return CAPABILITY_IMAGE;
        if (isVideo(mimeType)) return CAPABILITY_VIDEO;
        return null;
    }

    static String submittedMimeType(String mimeType) {
        if (isImage(mimeType)) return mimeType;
        if (isVideo(mimeType)) return STORYBOARD_MIME_TYPE;
        return null;
    }
}
