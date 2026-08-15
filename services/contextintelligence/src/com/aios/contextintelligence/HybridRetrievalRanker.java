package com.aios.contextintelligence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Deterministic bounded reranking after SQL identity, source, and expiry filters. */
final class HybridRetrievalRanker {
    static final int MAX_CANDIDATES = 512;
    private static final double DAY_MILLIS = 24.0 * 60.0 * 60.0 * 1_000.0;

    private HybridRetrievalRanker() {}

    static List<Candidate> rank(
            List<Candidate> candidates,
            float[] queryEmbedding,
            int limit,
            long nowEpochMillis) {
        if (candidates == null || candidates.size() > MAX_CANDIDATES
                || limit < 1 || limit > ContextPolicy.MAX_QUERY_RESULTS
                || nowEpochMillis <= 0L) {
            throw new IllegalArgumentException("invalid hybrid retrieval request");
        }
        List<Scored> scored = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            if (candidate == null || candidate.eventAtEpochMillis <= 0L
                    || candidate.eventAtEpochMillis > nowEpochMillis
                    || candidate.lexicalRank < -1) {
                throw new IllegalArgumentException("invalid retrieval candidate");
            }
            if (queryEmbedding != null
                    && candidate.embedding == null
                    && candidate.lexicalRank < 0) {
                // Partial indexing must not turn unrelated recent rows into matches.
                continue;
            }
            double lexical = candidate.lexicalRank < 0
                    ? 0.0 : 1.0 / (1.0 + candidate.lexicalRank);
            double ageDays = (nowEpochMillis - candidate.eventAtEpochMillis) / DAY_MILLIS;
            double recency = 1.0 / (1.0 + ageDays / 30.0);
            double score;
            if (queryEmbedding != null && candidate.embedding != null) {
                double semantic = (candidate.embedding.cosine(queryEmbedding) + 1.0) / 2.0;
                score = 0.65 * semantic + 0.25 * lexical + 0.10 * recency;
            } else {
                score = 0.75 * lexical + 0.25 * recency;
            }
            scored.add(new Scored(candidate, score));
        }
        scored.sort(Comparator
                .comparingDouble((Scored value) -> value.score).reversed()
                .thenComparing(
                        Comparator.comparingLong(
                                (Scored value) -> value.candidate.eventAtEpochMillis).reversed())
                .thenComparing(value -> value.candidate.sourceType)
                .thenComparing(value -> value.candidate.sourceId));
        List<Candidate> result = new ArrayList<>(Math.min(limit, scored.size()));
        for (int index = 0; index < Math.min(limit, scored.size()); index++) {
            result.add(scored.get(index).candidate);
        }
        return result;
    }

    static final class Candidate {
        final String sourceType;
        final String sourceId;
        final long revision;
        final long eventAtEpochMillis;
        final String text;
        /** Zero-based FTS rank, or -1 when this row came only from semantic/recent recall. */
        final int lexicalRank;
        final QuantizedEmbedding embedding;

        Candidate(
                String sourceType,
                String sourceId,
                long revision,
                long eventAtEpochMillis,
                String text,
                int lexicalRank,
                QuantizedEmbedding embedding) {
            if (sourceType == null || sourceType.isBlank()
                    || sourceId == null || sourceId.isBlank()
                    || revision <= 0L || text == null || text.isBlank()) {
                throw new IllegalArgumentException("invalid retrieval candidate");
            }
            this.sourceType = sourceType;
            this.sourceId = sourceId;
            this.revision = revision;
            this.eventAtEpochMillis = eventAtEpochMillis;
            this.text = text;
            this.lexicalRank = lexicalRank;
            this.embedding = embedding;
        }
    }

    private static final class Scored {
        final Candidate candidate;
        final double score;

        Scored(Candidate candidate, double score) {
            this.candidate = candidate;
            this.score = score;
        }
    }
}
