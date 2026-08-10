package com.aios.mediaintelligence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pure cursor planner for crash-safe, bounded MediaStore generation scans. */
final class MediaGenerationReconciler {
    static final long END_OF_GENERATION = Long.MAX_VALUE;

    private MediaGenerationReconciler() {}

    static Plan plan(
            CursorPoint previous,
            long observedCurrentGeneration,
            List<Row> queriedRows,
            boolean queryWasTruncated) {
        if (previous == null || observedCurrentGeneration < 0L || queriedRows == null
                || observedCurrentGeneration < previous.generation
                || (queryWasTruncated && queriedRows.isEmpty())) {
            throw new IllegalArgumentException("invalid MediaStore generation scan");
        }
        List<Row> ordered = new ArrayList<>(queriedRows);
        ordered.sort(Comparator.comparingLong((Row row) -> row.generationAdded)
                .thenComparingLong(row -> row.mediaId));
        List<Row> ready = new ArrayList<>();
        CursorPoint lastProcessed = previous;
        boolean blocked = false;
        for (Row row : ordered) {
            CursorPoint rowPoint = new CursorPoint(row.generationAdded, row.mediaId);
            if (rowPoint.compareTo(previous) <= 0) continue;
            if (row.pending) {
                blocked = true;
                break;
            }
            lastProcessed = rowPoint;
            if (row.eligible) ready.add(row);
        }

        if (blocked) {
            // IS_PENDING can clear without changing GENERATION_ADDED. Keep the
            // cursor before this row so a later scan sees the completed item.
            return new Plan(List.copyOf(ready), lastProcessed, false, true);
        }
        if (queryWasTruncated) {
            boolean progressed = lastProcessed.compareTo(previous) > 0;
            return new Plan(List.copyOf(ready), lastProcessed, progressed, false);
        }
        return new Plan(
                List.copyOf(ready),
                new CursorPoint(observedCurrentGeneration, END_OF_GENERATION),
                false,
                false);
    }

    static final class CursorPoint implements Comparable<CursorPoint> {
        final long generation;
        final long mediaId;

        CursorPoint(long generation, long mediaId) {
            if (generation < 0L || mediaId < 0L) {
                throw new IllegalArgumentException("invalid MediaStore cursor");
            }
            this.generation = generation;
            this.mediaId = mediaId;
        }

        @Override
        public int compareTo(CursorPoint other) {
            int generationOrder = Long.compare(generation, other.generation);
            return generationOrder != 0 ? generationOrder : Long.compare(mediaId, other.mediaId);
        }
    }

    static final class Row {
        final long mediaId;
        final String uri;
        final long generationAdded;
        final long generationModified;
        final String mimeType;
        final long observedAtEpochMillis;
        final boolean pending;
        final boolean eligible;

        Row(
                long mediaId,
                String uri,
                long generationAdded,
                long generationModified,
                String mimeType,
                long observedAtEpochMillis,
                boolean pending,
                boolean eligible) {
            if (mediaId <= 0L || uri == null || uri.isBlank() || generationAdded <= 0L
                    || generationModified < 0L || mimeType == null
                    || observedAtEpochMillis <= 0L) {
                throw new IllegalArgumentException("invalid MediaStore scan row");
            }
            this.mediaId = mediaId;
            this.uri = uri;
            this.generationAdded = generationAdded;
            this.generationModified = generationModified;
            this.mimeType = mimeType;
            this.observedAtEpochMillis = observedAtEpochMillis;
            this.pending = pending;
            this.eligible = eligible;
        }
    }

    static final class Plan {
        final List<Row> ready;
        final CursorPoint next;
        final boolean more;
        final boolean blockedByPendingItem;

        Plan(List<Row> ready, CursorPoint next, boolean more, boolean blockedByPendingItem) {
            this.ready = ready;
            this.next = next;
            this.more = more;
            this.blockedByPendingItem = blockedByPendingItem;
        }
    }
}
