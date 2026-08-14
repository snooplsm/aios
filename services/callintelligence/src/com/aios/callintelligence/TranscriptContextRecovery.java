package com.aios.callintelligence;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Admits a bounded, finalized conversation tail decoded from the local
 * transcript journal.
 *
 * <p>The journal also contains replaceable ASR hypotheses and owner/uplink
 * audio. Neither belongs in recovered receptionist memory. Only final caller
 * turns and the assistant replies that were committed before caller playback
 * are eligible.</p>
 */
final class TranscriptContextRecovery {
    static final int MAX_RECOVERED_TURNS = 64;
    static final int MAX_RECOVERED_CHARS = RollingConversationMemory.MAX_RETAINED_CHARS;

    static final class Turn {
        final String role;
        final String language;
        final String text;

        Turn(String role, String language, String text) {
            this.role = role;
            this.language = language;
            this.text = text;
        }

        int retainedCharacters() {
            return role.length() + language.length() + text.length() + 5;
        }
    }

    private final ArrayDeque<Turn> turns = new ArrayDeque<>();
    private int retainedCharacters;

    boolean accept(
            String direction, String language, String text, boolean isFinal) {
        String role;
        if (isFinal && "downlink".equals(direction)) {
            role = "caller";
        } else if (isFinal && "assistant".equals(direction)) {
            role = "assistant";
        } else {
            return false;
        }
        String normalized = normalize(text);
        if (!("en".equals(language) || "es".equals(language))
                || normalized.isEmpty()) {
            return false;
        }
        Turn admitted = new Turn(role, language, normalized);
        turns.addLast(admitted);
        retainedCharacters += admitted.retainedCharacters();
        while (turns.size() > MAX_RECOVERED_TURNS
                || retainedCharacters > MAX_RECOVERED_CHARS) {
            Turn removed = turns.removeFirst();
            retainedCharacters -= removed.retainedCharacters();
        }
        return true;
    }

    List<Turn> snapshot() {
        return new ArrayList<>(turns);
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String normalized = value.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ")
                .replaceAll("\\s+", " ").trim();
        return normalized.length() <= RollingConversationMemory.MAX_TURN_CHARS
                ? normalized
                : normalized.substring(0, RollingConversationMemory.MAX_TURN_CHARS).trim();
    }
}
