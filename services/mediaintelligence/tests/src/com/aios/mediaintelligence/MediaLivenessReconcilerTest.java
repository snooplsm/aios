package com.aios.mediaintelligence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

public final class MediaLivenessReconcilerTest {
    @Test
    public void deletesOnlyMissingRowsFromSuccessfullyProbedVolumes() {
        MediaLivenessReconciler.Plan plan = MediaLivenessReconciler.plan(
                List.of(
                        row(1L, "external_primary", 10L),
                        row(2L, "external_primary", 11L),
                        row(3L, "ABCD-1234", 12L)),
                Map.of("external_primary", Set.of(10L)),
                Set.of("external_primary"),
                false);

        assertEquals(List.of(uri("external_primary", 11L)), plan.deletedUris);
        assertEquals(0L, plan.nextJobId);
        assertFalse(plan.more);
    }

    @Test
    public void invalidInternalUrisFailClosed() {
        MediaLivenessReconciler.Row invalid = new MediaLivenessReconciler.Row(
                4L, "content://unexpected/item/4", null, 0L, false);

        MediaLivenessReconciler.Plan plan = MediaLivenessReconciler.plan(
                List.of(invalid), Map.of(), Set.of(), false);

        assertEquals(List.of(invalid.uri), plan.deletedUris);
    }

    @Test
    public void duplicateGenerationsDeleteOneSourceUri() {
        MediaLivenessReconciler.Plan plan = MediaLivenessReconciler.plan(
                List.of(
                        row(5L, "external_primary", 20L),
                        row(6L, "external_primary", 20L)),
                Map.of("external_primary", Set.of()),
                Set.of("external_primary"),
                false);

        assertEquals(List.of(uri("external_primary", 20L)), plan.deletedUris);
    }

    @Test
    public void truncatedPageCarriesLastJobCursor() {
        MediaLivenessReconciler.Plan plan = MediaLivenessReconciler.plan(
                List.of(row(8L, "external_primary", 30L), row(9L, "external_primary", 31L)),
                Map.of("external_primary", Set.of(30L, 31L)),
                Set.of("external_primary"),
                true);

        assertEquals(9L, plan.nextJobId);
        assertTrue(plan.more);
    }

    private static MediaLivenessReconciler.Row row(
            long jobId, String volumeName, long mediaId) {
        return new MediaLivenessReconciler.Row(
                jobId, uri(volumeName, mediaId), volumeName, mediaId, true);
    }

    private static String uri(String volumeName, long mediaId) {
        return "content://media/" + volumeName + "/images/media/" + mediaId;
    }
}
