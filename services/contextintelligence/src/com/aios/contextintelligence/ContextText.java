package com.aios.contextintelligence;

import java.util.Locale;

/** Pure text normalization shared by the Android store and host tests. */
final class ContextText {
    private ContextText() {}

    static String ftsQuery(String value) {
        String[] tokens = tokens(value);
        StringBuilder result = new StringBuilder();
        for (String token : tokens) {
            // Android's FTS4 build uses the basic syntax, where whitespace is
            // intersection. AND is treated as a literal token on some builds.
            if (result.length() > 0) result.append(' ');
            result.append('"').append(token).append('"').append('*');
        }
        return result.toString();
    }

    /** Returns a deterministic prefix-token position rank, or -1 for no lexical match. */
    static int lexicalRank(String value, String query) {
        String[] needles = tokens(query);
        if (needles.length == 0) return -1;
        String[] haystack = normalized(value).split("\\s+");
        long rank = 0L;
        for (String needle : needles) {
            int position = -1;
            for (int index = 0; index < haystack.length; index++) {
                if (haystack[index].startsWith(needle)) {
                    position = index;
                    break;
                }
            }
            if (position < 0) return -1;
            rank += position;
        }
        return (int) Math.min(Integer.MAX_VALUE, rank);
    }

    static String excerpt(String value) {
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= ContextPolicy.MAX_SNIPPET_CHARS
                ? normalized : normalized.substring(0, ContextPolicy.MAX_SNIPPET_CHARS);
    }

    private static String[] tokens(String value) {
        String normalized = normalized(value);
        if (normalized.isEmpty()) return new String[0];
        String[] all = normalized.split("\\s+");
        if (all.length <= 8) return all;
        return java.util.Arrays.copyOf(all, 8);
    }

    private static String normalized(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }
}
