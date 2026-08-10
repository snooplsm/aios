package com.aios.mediaintelligence;

import org.json.JSONException;

/** Privacy-reviewed projection from the private result schema into communication RAG text. */
final class MediaContextProjection {
    private MediaContextProjection() {}

    static String project(String resultJson) throws JSONException {
        MediaResult result = MediaResult.parse(resultJson);
        StringBuilder value = new StringBuilder(MediaAssociationPolicy.MAX_CONTEXT_CHARS);
        appendBounded(value, "Photo: ");
        appendBounded(value, result.caption);
        if (!result.tags.isEmpty() && value.length() < MediaAssociationPolicy.MAX_CONTEXT_CHARS) {
            appendBounded(value, "\nTags: ");
            boolean first = true;
            for (String tag : result.tags) {
                if (!first) appendBounded(value, ", ");
                int before = value.length();
                appendBounded(value, tag);
                if (value.length() == before) break;
                first = false;
            }
        }
        String projected = value.toString().trim();
        if (projected.isEmpty()) throw new JSONException("empty media-context projection");
        return projected;
    }

    private static void appendBounded(StringBuilder destination, String value) {
        int available = MediaAssociationPolicy.MAX_CONTEXT_CHARS - destination.length();
        if (available <= 0 || value == null || value.isEmpty()) return;
        destination.append(value, 0, Math.min(available, value.length()));
    }
}
