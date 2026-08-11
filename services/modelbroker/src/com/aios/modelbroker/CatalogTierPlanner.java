package com.aios.modelbroker;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves the highest memory-eligible tier followed by its declared fallbacks. */
final class CatalogTierPlanner {
    static final class Tier {
        final String id;
        final long minTotalRamMb;
        final List<String> modelIds;
        final String fallbackTierId;

        Tier(
                String id,
                long minTotalRamMb,
                List<String> modelIds,
                String fallbackTierId) {
            this.id = id;
            this.minTotalRamMb = minTotalRamMb;
            this.modelIds = List.copyOf(modelIds);
            this.fallbackTierId = fallbackTierId;
        }
    }

    private CatalogTierPlanner() {}

    static void validate(List<Tier> tiers) {
        Map<String, Tier> byId = index(tiers);
        for (Tier tier : tiers) {
            Set<String> seen = new HashSet<>();
            Tier current = tier;
            while (current != null) {
                if (!seen.add(current.id)) {
                    throw new IllegalArgumentException("catalog tier fallback cycle");
                }
                String fallbackId = current.fallbackTierId;
                if (fallbackId == null) break;
                Tier fallback = byId.get(fallbackId);
                if (fallback == null) {
                    throw new IllegalArgumentException("unknown catalog fallback tier");
                }
                if (fallback.minTotalRamMb >= current.minTotalRamMb) {
                    throw new IllegalArgumentException(
                            "catalog fallback must require less total RAM");
                }
                current = fallback;
            }
        }
    }

    static List<String> candidates(List<Tier> tiers, long totalRamMb) {
        validate(tiers);
        Tier selected = null;
        for (Tier tier : tiers) {
            if (tier.minTotalRamMb <= totalRamMb
                    && (selected == null
                    || tier.minTotalRamMb > selected.minTotalRamMb)) {
                selected = tier;
            }
        }
        if (selected == null) return List.of();
        Map<String, Tier> byId = index(tiers);
        Set<String> candidates = new LinkedHashSet<>();
        Tier current = selected;
        while (current != null) {
            candidates.addAll(current.modelIds);
            current = current.fallbackTierId == null
                    ? null : byId.get(current.fallbackTierId);
        }
        return List.copyOf(candidates);
    }

    private static Map<String, Tier> index(List<Tier> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            throw new IllegalArgumentException("catalog tiers are required");
        }
        Map<String, Tier> result = new HashMap<>();
        Set<Long> memoryFloors = new HashSet<>();
        for (Tier tier : tiers) {
            if (tier == null || tier.id == null || tier.id.isEmpty()
                    || tier.minTotalRamMb <= 0L || tier.modelIds.isEmpty()
                    || result.put(tier.id, tier) != null
                    || !memoryFloors.add(tier.minTotalRamMb)) {
                throw new IllegalArgumentException("invalid or duplicate catalog tier");
            }
        }
        return result;
    }
}
