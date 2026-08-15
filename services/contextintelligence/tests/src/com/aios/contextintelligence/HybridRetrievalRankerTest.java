package com.aios.contextintelligence;

import static org.junit.Assert.assertEquals;

import java.util.List;
import org.junit.Test;

public final class HybridRetrievalRankerTest {
    private static final long NOW = 2_000_000_000_000L;

    @Test
    public void semanticCandidateCanBeatUnrelatedKeywordMatch() {
        float[] query = vector(1.0f, 0.0f);
        HybridRetrievalRanker.Candidate lexical = candidate(
                "lexical", 0, vector(0.0f, 1.0f));
        HybridRetrievalRanker.Candidate semantic = candidate(
                "semantic", -1, vector(1.0f, 0.0f));

        List<HybridRetrievalRanker.Candidate> result = HybridRetrievalRanker.rank(
                List.of(lexical, semantic), query, 2, NOW);

        assertEquals("semantic", result.get(0).sourceId);
    }

    @Test
    public void lexicalFallbackWorksWithoutQueryEmbedding() {
        HybridRetrievalRanker.Candidate exact = candidate("exact", 0, null);
        HybridRetrievalRanker.Candidate recentOnly = candidate("recent", -1, null);

        List<HybridRetrievalRanker.Candidate> result = HybridRetrievalRanker.rank(
                List.of(recentOnly, exact), null, 2, NOW);

        assertEquals("exact", result.get(0).sourceId);
    }

    @Test(expected = IllegalArgumentException.class)
    public void candidateSetIsHardBounded() {
        HybridRetrievalRanker.Candidate candidate = candidate("same", -1, null);
        HybridRetrievalRanker.rank(
                java.util.Collections.nCopies(
                        HybridRetrievalRanker.MAX_CANDIDATES + 1, candidate),
                null,
                1,
                NOW);
    }

    private static HybridRetrievalRanker.Candidate candidate(
            String id, int lexicalRank, float[] vector) {
        return new HybridRetrievalRanker.Candidate(
                "sms",
                id,
                1L,
                NOW - 1_000L,
                "fixture text",
                lexicalRank,
                vector == null ? null : QuantizedEmbedding.quantize(vector));
    }

    private static float[] vector(float first, float second) {
        float[] result = new float[QuantizedEmbedding.DIMENSIONS];
        result[0] = first;
        result[1] = second;
        return result;
    }
}
