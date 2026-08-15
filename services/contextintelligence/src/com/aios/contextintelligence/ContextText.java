package com.aios.contextintelligence;

import java.util.Locale;

/** Pure text normalization shared by the Android store and host tests. */
final class ContextText {
    private ContextText() {}

    static String ftsQuery(String value) {
        String normalized = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        if (normalized.isEmpty()) return "";
        String[] tokens = normalized.split("\\s+");
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < Math.min(tokens.length, 8); index++) {
            if (tokens[index].isEmpty()) continue;
            // Android's FTS4 build uses the basic syntax, where whitespace is
            // intersection. AND is treated as a literal token on some builds.
            if (result.length() > 0) result.append(' ');
            result.append('"').append(tokens[index]).append('"').append('*');
        }
        return result.toString();
    }

    static String excerpt(String value) {
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= ContextPolicy.MAX_SNIPPET_CHARS
                ? normalized : normalized.substring(0, ContextPolicy.MAX_SNIPPET_CHARS);
    }
}
