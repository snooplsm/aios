package com.aios.contextintelligence;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ContextStoreQueryTest {
    @Test
    public void multiTokenQueryUsesPortableFts4IntersectionSyntax() {
        assertEquals("\"copper\"* \"pipe\"*", ContextStore.ftsQuery("Copper, pipe!"));
    }

    @Test
    public void queryIsNormalizedAndBoundedToEightTokens() {
        assertEquals(
                "\"one\"* \"two\"* \"three\"* \"four\"* \"five\"* "
                        + "\"six\"* \"seven\"* \"eight\"*",
                ContextStore.ftsQuery("one two three four five six seven eight nine"));
        assertEquals("", ContextStore.ftsQuery("---"));
    }
}
