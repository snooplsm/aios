package com.aios.mediaintelligence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure deletion planner for bounded MediaStore source-liveness scans. */
final class MediaLivenessReconciler {
    private MediaLivenessReconciler() {}

    static Plan plan(
            List<Row> queriedRows,
            Map<String, Set<Long>> presentIdsByVolume,
            Set<String> successfullyProbedVolumes,
            boolean queryWasTruncated) {
        if (queriedRows == null || presentIdsByVolume == null
                || successfullyProbedVolumes == null
                || (queryWasTruncated && queriedRows.isEmpty())) {
            throw new IllegalArgumentException("invalid media liveness scan");
        }
        List<Row> ordered = new ArrayList<>(queriedRows);
        ordered.sort(Comparator.comparingLong(row -> row.jobId));
        LinkedHashSet<String> deletedUris = new LinkedHashSet<>();
        long nextJobId = 0L;
        for (Row row : ordered) {
            nextJobId = row.jobId;
            if (!row.verifiable) {
                deletedUris.add(row.uri);
                continue;
            }
            if (!successfullyProbedVolumes.contains(row.volumeName)) continue;
            Set<Long> present = presentIdsByVolume.get(row.volumeName);
            if (present == null || !present.contains(row.mediaId)) {
                deletedUris.add(row.uri);
            }
        }
        return new Plan(
                List.copyOf(deletedUris),
                queryWasTruncated ? nextJobId : 0L,
                queryWasTruncated);
    }

    static final class Row {
        final long jobId;
        final String uri;
        final String volumeName;
        final long mediaId;
        final boolean verifiable;

        Row(long jobId, String uri, String volumeName, long mediaId, boolean verifiable) {
            if (jobId <= 0L || uri == null || uri.isBlank()
                    || (verifiable && (volumeName == null || volumeName.isBlank()
                    || mediaId <= 0L))) {
                throw new IllegalArgumentException("invalid media liveness row");
            }
            this.jobId = jobId;
            this.uri = uri;
            this.volumeName = volumeName;
            this.mediaId = mediaId;
            this.verifiable = verifiable;
        }
    }

    static final class Plan {
        final List<String> deletedUris;
        final long nextJobId;
        final boolean more;

        Plan(List<String> deletedUris, long nextJobId, boolean more) {
            this.deletedUris = deletedUris;
            this.nextJobId = nextJobId;
            this.more = more;
        }
    }
}
