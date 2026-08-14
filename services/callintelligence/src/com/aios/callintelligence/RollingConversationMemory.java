package com.aios.callintelligence;

import java.util.ArrayList;
import java.util.List;

/**
 * Call-local hierarchical memory with immutable finalized turns and one
 * replaceable live ASR hypothesis.
 *
 * <p>Semantic compaction is deliberately a compare-and-set operation. A result
 * names the summary revision and exact finalized-turn prefix it consumed, so a
 * duplicate or stale result cannot replace a newer summary. Finalized turns
 * that arrive while compaction is running remain outside that prefix and are
 * retained verbatim.</p>
 */
final class RollingConversationMemory {
    static final int MAX_RECENT_TURNS = 8;
    static final int MAX_RECENT_CHARS = 6_144;
    static final int MAX_RETAINED_CHARS = 24_576;
    static final int MAX_TURN_CHARS = 2_048;
    static final int MAX_PARTIAL_CHARS = 2_048;
    static final int MAX_SUMMARY_CHARS = 4_096;

    static final class PromptSnapshot {
        final long conversationRevision;
        final long summaryRevision;
        final long summaryThroughTurnId;
        final String structuredSummaryJson;
        final String recentExactTurns;
        final String livePartial;

        PromptSnapshot(
                long conversationRevision,
                long summaryRevision,
                long summaryThroughTurnId,
                String structuredSummaryJson,
                String recentExactTurns,
                String livePartial) {
            this.conversationRevision = conversationRevision;
            this.summaryRevision = summaryRevision;
            this.summaryThroughTurnId = summaryThroughTurnId;
            this.structuredSummaryJson = structuredSummaryJson;
            this.recentExactTurns = recentExactTurns;
            this.livePartial = livePartial;
        }
    }

    static final class CompactionInput {
        final long inputSummaryRevision;
        final long inputSummaryThroughTurnId;
        final long firstTurnId;
        final long lastTurnId;
        final String existingSummaryJson;
        final String finalizedPrefix;

        CompactionInput(
                long inputSummaryRevision,
                long inputSummaryThroughTurnId,
                long firstTurnId,
                long lastTurnId,
                String existingSummaryJson,
                String finalizedPrefix) {
            this.inputSummaryRevision = inputSummaryRevision;
            this.inputSummaryThroughTurnId = inputSummaryThroughTurnId;
            this.firstTurnId = firstTurnId;
            this.lastTurnId = lastTurnId;
            this.existingSummaryJson = existingSummaryJson;
            this.finalizedPrefix = finalizedPrefix;
        }
    }

    private static final class Turn {
        final long id;
        final String role;
        final String language;
        final String text;

        Turn(long id, String role, String language, String text) {
            this.id = id;
            this.role = role;
            this.language = language;
            this.text = text;
        }

        String render() {
            return role + "[" + language + "]: " + text + "\n";
        }
    }

    private final ArrayList<Turn> finalized = new ArrayList<>();
    private long conversationRevision;
    private long summaryRevision;
    private long summaryThroughTurnId;
    private long discardedThroughTurnId;
    private String structuredSummaryJson = "{}";
    private long partialRevision = -1L;
    private String livePartial = "";

    synchronized boolean observePartial(
            String language, String text, long transcriptRevision) {
        String normalized = normalize(text, MAX_PARTIAL_CHARS);
        if (!validLanguage(language) || normalized.isEmpty()
                || transcriptRevision <= partialRevision) {
            return false;
        }
        partialRevision = transcriptRevision;
        livePartial = "caller[" + language + "][partial]: " + normalized;
        return true;
    }

    synchronized boolean appendFinal(String role, String language, String text) {
        String normalized = normalize(text, MAX_TURN_CHARS);
        if (!("caller".equals(role) || "assistant".equals(role))
                || !validLanguage(language) || normalized.isEmpty()
                || conversationRevision == Long.MAX_VALUE) {
            return false;
        }
        long turnId = ++conversationRevision;
        finalized.add(new Turn(turnId, role, language, normalized));
        if ("caller".equals(role)) {
            partialRevision = -1L;
            livePartial = "";
        }
        trimUncompactedFallback();
        return true;
    }

    synchronized PromptSnapshot promptSnapshot() {
        return new PromptSnapshot(
                conversationRevision,
                summaryRevision,
                summaryThroughTurnId,
                structuredSummaryJson,
                renderRecent(),
                livePartial);
    }

    synchronized CompactionInput prepareCompaction() {
        int compactCount = finalized.size() - MAX_RECENT_TURNS;
        if (compactCount <= 0 || discardedThroughTurnId > summaryThroughTurnId
                || finalized.get(0).id != summaryThroughTurnId + 1L) {
            return null;
        }
        List<Turn> prefix = finalized.subList(0, compactCount);
        return new CompactionInput(
                summaryRevision,
                summaryThroughTurnId,
                prefix.get(0).id,
                prefix.get(prefix.size() - 1).id,
                structuredSummaryJson,
                render(prefix));
    }

    synchronized boolean applyCompaction(
            CompactionInput input, String replacementSummaryJson) {
        String normalizedSummary = normalizeSummary(replacementSummaryJson);
        if (input == null || normalizedSummary.isEmpty()
                || summaryRevision == Long.MAX_VALUE
                || input.inputSummaryRevision != summaryRevision
                || input.inputSummaryThroughTurnId != summaryThroughTurnId
                || input.firstTurnId != summaryThroughTurnId + 1L) {
            return false;
        }
        int lastIndex = -1;
        StringBuilder actual = new StringBuilder();
        for (int index = 0; index < finalized.size(); index++) {
            Turn turn = finalized.get(index);
            if (index == 0 && turn.id != input.firstTurnId) return false;
            actual.append(turn.render());
            if (turn.id == input.lastTurnId) {
                lastIndex = index;
                break;
            }
        }
        if (lastIndex < 0 || !actual.toString().equals(input.finalizedPrefix)) return false;
        finalized.subList(0, lastIndex + 1).clear();
        structuredSummaryJson = normalizedSummary;
        summaryThroughTurnId = input.lastTurnId;
        summaryRevision++;
        return true;
    }

    private String renderRecent() {
        StringBuilder result = new StringBuilder();
        int first = Math.max(0, finalized.size() - MAX_RECENT_TURNS);
        for (int index = first; index < finalized.size(); index++) {
            result.append(finalized.get(index).render());
        }
        if (result.length() > MAX_RECENT_CHARS) {
            result.delete(0, result.length() - MAX_RECENT_CHARS);
        }
        return result.toString();
    }

    private void trimUncompactedFallback() {
        int characters = 0;
        for (Turn turn : finalized) characters += turn.render().length();
        while (characters > MAX_RETAINED_CHARS && finalized.size() > MAX_RECENT_TURNS) {
            Turn removed = finalized.remove(0);
            characters -= removed.render().length();
            discardedThroughTurnId = removed.id;
        }
    }

    private static String render(List<Turn> turns) {
        StringBuilder result = new StringBuilder();
        for (Turn turn : turns) result.append(turn.render());
        return result.toString();
    }

    private static boolean validLanguage(String language) {
        return "en".equals(language) || "es".equals(language);
    }

    private static String normalize(String value, int maxChars) {
        if (value == null) return "";
        String normalized = value.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ")
                .replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxChars
                ? normalized : normalized.substring(0, maxChars).trim();
    }

    private static String normalizeSummary(String value) {
        if (value == null) return "";
        String normalized = value.trim();
        if (normalized.length() > MAX_SUMMARY_CHARS
                || !normalized.startsWith("{") || !normalized.endsWith("}")) {
            return "";
        }
        return normalized;
    }
}
