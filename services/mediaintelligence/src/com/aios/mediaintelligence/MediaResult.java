package com.aios.mediaintelligence;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Strict model-output schema; portable metadata uses only these reviewed fields. */
final class MediaResult {
    final String rawJson;
    final String caption;
    final List<String> tags;
    final String language;
    final float confidence;

    private MediaResult(
            String rawJson,
            String caption,
            List<String> tags,
            String language,
            float confidence) {
        this.rawJson = rawJson;
        this.caption = caption;
        this.tags = List.copyOf(tags);
        this.language = language;
        this.confidence = confidence;
    }

    static MediaResult parse(String rawJson) throws JSONException {
        if (rawJson == null) {
            throw new JSONException("media-result JSON is absent");
        }
        JSONObject root = new JSONObject(rawJson);
        if (root.getInt("schema_version") != 1) {
            throw new JSONException("unsupported media-result schema");
        }
        String caption = root.getString("caption").trim();
        if (caption.isEmpty() || caption.length() > 2048) {
            throw new JSONException("caption length is invalid");
        }
        String language = root.getString("language");
        if (!"en".equals(language) && !"es".equals(language)) {
            throw new JSONException("media-result language must be English or Spanish");
        }
        double confidenceValue = root.getDouble("confidence");
        if (!Double.isFinite(confidenceValue)
                || confidenceValue < 0.0 || confidenceValue > 1.0) {
            throw new JSONException("confidence is invalid");
        }
        JSONArray values = root.getJSONArray("tags");
        if (values.length() > 64) {
            throw new JSONException("too many media tags");
        }
        List<String> tags = new ArrayList<>(values.length());
        for (int index = 0; index < values.length(); index++) {
            String tag = values.getString(index).trim();
            if (tag.isEmpty() || tag.length() > 128 || tags.contains(tag)) {
                throw new JSONException("invalid or duplicate media tag");
            }
            tags.add(tag);
        }
        return new MediaResult(rawJson, caption, tags, language, (float) confidenceValue);
    }
}
