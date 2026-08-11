package com.aios.modelbroker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.util.List;

import org.junit.Test;

public final class CatalogTierPlannerTest {
    private static final List<CatalogTierPlanner.Tier> TIERS = List.of(
            tier("edge_8gb", 7_168L, List.of("e2-text", "e2-media", "tts", "base"), null),
            tier("edge_12gb", 11_264L,
                    List.of("e4-text", "e4-media", "tts", "small", "base"),
                    "edge_8gb"),
            tier("edge_16gb", 15_360L,
                    List.of("e4-text", "e4-media", "tts", "small"),
                    "edge_12gb"));

    @Test
    public void highestEligibleTierPrecedesDeduplicatedFallbacks() {
        assertEquals(
                List.of(
                        "e4-text", "e4-media", "tts", "small", "base", "e2-text",
                        "e2-media"),
                CatalogTierPlanner.candidates(TIERS, 16_384L));
    }

    @Test
    public void baselineAndInsufficientMemoryStayBounded() {
        assertEquals(
                List.of("e2-text", "e2-media", "tts", "base"),
                CatalogTierPlanner.candidates(TIERS, 8_192L));
        assertEquals(List.of(), CatalogTierPlanner.candidates(TIERS, 4_096L));
    }

    @Test
    public void cyclesUnknownTargetsAndUpwardFallbacksFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> CatalogTierPlanner.validate(List.of(
                tier("a", 8L, List.of("one"), "b"),
                tier("b", 4L, List.of("two"), "a"))));
        assertThrows(IllegalArgumentException.class, () -> CatalogTierPlanner.validate(List.of(
                tier("a", 8L, List.of("one"), "absent"))));
        assertThrows(IllegalArgumentException.class, () -> CatalogTierPlanner.validate(List.of(
                tier("a", 4L, List.of("one"), "b"),
                tier("b", 8L, List.of("two"), null))));
    }

    private static CatalogTierPlanner.Tier tier(
            String id, long minimum, List<String> models, String fallback) {
        return new CatalogTierPlanner.Tier(id, minimum, models, fallback);
    }
}
