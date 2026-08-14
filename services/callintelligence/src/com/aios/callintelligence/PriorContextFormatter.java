package com.aios.callintelligence;

import java.util.List;
import java.util.Set;

/** Converts bounded retrieval results into identifier-free JSON for the receptionist. */
final class PriorContextFormatter {
    static final int MAX_ITEMS = 8;
    static final int MAX_EXCERPT_CHARS = 512;
    static final int MAX_JSON_CHARS = 3_072;

    private static final Set<String> SOURCES = Set.of(
            "sms", "mms", "call_event", "call_artifact", "contact_note",
            "media_metadata");

    static final class Item {
        final String sourceType;
        final long eventAtEpochMillis;
        final String excerpt;

        Item(String sourceType, long eventAtEpochMillis, String excerpt) {
            this.sourceType = sourceType;
            this.eventAtEpochMillis = eventAtEpochMillis;
            this.excerpt = excerpt;
        }
    }

    private PriorContextFormatter() {}

    static String format(List<Item> values) {
        StringBuilder result = new StringBuilder("[");
        int accepted = 0;
        if (values != null) {
            for (Item item : values) {
                if (accepted == MAX_ITEMS) break;
                if (item == null || !SOURCES.contains(item.sourceType)
                        || item.eventAtEpochMillis <= 0L) {
                    continue;
                }
                String excerpt = normalize(item.excerpt, MAX_EXCERPT_CHARS);
                if (excerpt.isEmpty()) continue;
                StringBuilder candidate = new StringBuilder();
                if (accepted > 0) candidate.append(',');
                candidate.append("{\"source_type\":");
                appendJsonString(candidate, item.sourceType);
                candidate.append(",\"event_at_epoch_ms\":")
                        .append(item.eventAtEpochMillis)
                        .append(",\"excerpt\":");
                appendJsonString(candidate, excerpt);
                candidate.append('}');
                if (result.length() + candidate.length() + 1 > MAX_JSON_CHARS) break;
                result.append(candidate);
                accepted++;
            }
        }
        return result.append(']').toString();
    }

    static String normalize(String value, int maximum) {
        if (value == null || maximum < 1) return "";
        StringBuilder result = new StringBuilder(Math.min(value.length(), maximum));
        boolean whitespace = true;
        for (int index = 0; index < value.length() && result.length() < maximum; index++) {
            char character = value.charAt(index);
            if (Character.isWhitespace(character) || Character.isISOControl(character)) {
                whitespace = true;
            } else {
                if (whitespace && result.length() > 0 && result.length() < maximum) {
                    result.append(' ');
                }
                if (result.length() < maximum) result.append(character);
                whitespace = false;
            }
        }
        return result.toString();
    }

    private static void appendJsonString(StringBuilder destination, String value) {
        destination.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"': destination.append("\\\""); break;
                case '\\': destination.append("\\\\"); break;
                case '\b': destination.append("\\b"); break;
                case '\f': destination.append("\\f"); break;
                case '\n': destination.append("\\n"); break;
                case '\r': destination.append("\\r"); break;
                case '\t': destination.append("\\t"); break;
                default:
                    if (character < 0x20) {
                        destination.append(String.format("\\u%04x", (int) character));
                    } else {
                        destination.append(character);
                    }
            }
        }
        destination.append('"');
    }
}
