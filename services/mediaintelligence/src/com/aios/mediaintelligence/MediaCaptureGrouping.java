package com.aios.mediaintelligence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Classifies actual capture sessions independently of reconciliation page size. */
final class MediaCaptureGrouping {
    static final long CAPTURE_SESSION_GAP_MILLIS = 5_000L;

    static final class Item {
        final String key;
        final String mimeType;
        final long observedAtEpochMillis;

        Item(String key, String mimeType, long observedAtEpochMillis) {
            if (key == null || key.isBlank() || mimeType == null || mimeType.isBlank()
                    || observedAtEpochMillis <= 0L) {
                throw new IllegalArgumentException("invalid capture item");
            }
            this.key = key;
            this.mimeType = mimeType;
            this.observedAtEpochMillis = observedAtEpochMillis;
        }
    }

    private MediaCaptureGrouping() {}

    static Map<String, Integer> classify(
            List<Item> values,
            boolean continuationFromPreviousPage,
            boolean continuesOnFollowingPage) {
        if (values == null) throw new IllegalArgumentException("capture items required");
        HashMap<String, Integer> result = new HashMap<>();
        HashSet<String> keys = new HashSet<>();
        ArrayList<Item> photos = new ArrayList<>();
        for (Item item : values) {
            if (item == null || !keys.add(item.key)) {
                throw new IllegalArgumentException("capture item keys must be unique");
            }
            if (MediaInputPolicy.isVideo(item.mimeType)) {
                result.put(item.key, MediaWorkPolicy.schedulingClass(item.mimeType, 1));
            } else if (item.mimeType.startsWith("image/")) {
                photos.add(item);
            } else {
                throw new IllegalArgumentException("unsupported capture item");
            }
        }
        photos.sort(Comparator
                .comparingLong((Item item) -> item.observedAtEpochMillis)
                .thenComparing(item -> item.key));
        boolean pageBoundaryUnknown =
                continuationFromPreviousPage || continuesOnFollowingPage;
        int groupStart = 0;
        while (groupStart < photos.size()) {
            int groupEnd = groupStart + 1;
            while (groupEnd < photos.size()
                    && photos.get(groupEnd).observedAtEpochMillis
                    - photos.get(groupEnd - 1).observedAtEpochMillis
                    <= CAPTURE_SESSION_GAP_MILLIS) {
                groupEnd++;
            }
            int workClass = pageBoundaryUnknown
                    ? MediaWorkPolicy.CLASS_DEFERRED
                    : MediaWorkPolicy.schedulingClass(
                            photos.get(groupStart).mimeType, groupEnd - groupStart);
            for (int index = groupStart; index < groupEnd; index++) {
                result.put(photos.get(index).key, workClass);
            }
            groupStart = groupEnd;
        }
        if (result.size() != values.size()) {
            throw new IllegalArgumentException("capture item keys must be unique");
        }
        return Map.copyOf(result);
    }
}
