package com.aios.contextintelligence;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ContextStoreQueryTest {
    @Test
    public void multiTokenQueryUsesPortableFts4IntersectionSyntax() {
        assertEquals("\"copper\"* \"pipe\"*", ContextText.ftsQuery("Copper, pipe!"));
    }

    @Test
    public void queryIsNormalizedAndBoundedToEightTokens() {
        assertEquals(
                "\"one\"* \"two\"* \"three\"* \"four\"* \"five\"* "
                        + "\"six\"* \"seven\"* \"eight\"*",
                ContextText.ftsQuery("one two three four five six seven eight nine"));
        assertEquals("", ContextText.ftsQuery("---"));
    }

    @Test
    public void hybridLexicalRankUsesTheSameBoundedPrefixTokens() {
        assertEquals(6, ContextText.lexicalRank(
                "Estimate for copper supply pipe", "copper pipe"));
        assertEquals(0, ContextText.lexicalRank("plumber scheduled", "plumb"));
        assertEquals(-1, ContextText.lexicalRank("electrical estimate", "copper pipe"));
        assertEquals(-1, ContextText.lexicalRank("anything", "---"));
    }
}
