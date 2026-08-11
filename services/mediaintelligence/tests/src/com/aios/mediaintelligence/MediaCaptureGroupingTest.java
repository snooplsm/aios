package com.aios.mediaintelligence;

import static org.junit.Assert.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.Test;

public final class MediaCaptureGroupingTest {
    @Test
    public void unrelatedSettledPhotosRemainImmediate() {
        Map<String, Integer> result = MediaCaptureGrouping.classify(List.of(
                photo("later", 20_000L),
                photo("earlier", 1_000L)), false, false);

        assertEquals(Integer.valueOf(MediaWorkPolicy.CLASS_IMMEDIATE), result.get("earlier"));
        assertEquals(Integer.valueOf(MediaWorkPolicy.CLASS_IMMEDIATE), result.get("later"));
    }

    @Test
    public void chainedCaptureSessionAndEveryVideoAreDeferred() {
        Map<String, Integer> result = MediaCaptureGrouping.classify(List.of(
                photo("one", 1_000L),
                photo("two", 5_000L),
                photo("three", 9_000L),
                video("video", 40_000L)), false, false);

        assertEquals(Integer.valueOf(MediaWorkPolicy.CLASS_DEFERRED), result.get("one"));
        assertEquals(Integer.valueOf(MediaWorkPolicy.CLASS_DEFERRED), result.get("two"));
        assertEquals(Integer.valueOf(MediaWorkPolicy.CLASS_DEFERRED), result.get("three"));
        assertEquals(Integer.valueOf(MediaWorkPolicy.CLASS_DEFERRED), result.get("video"));
    }

    @Test
    public void fiveSecondGapIsOneSessionButLongerGapIsNot() {
        Map<String, Integer> boundary = MediaCaptureGrouping.classify(List.of(
                photo("one", 1_000L), photo("two", 6_000L)), false, false);
        Map<String, Integer> separated = MediaCaptureGrouping.classify(List.of(
                photo("three", 1_000L), photo("four", 6_001L)), false, false);

        assertEquals(Integer.valueOf(MediaWorkPolicy.CLASS_DEFERRED), boundary.get("one"));
        assertEquals(Integer.valueOf(MediaWorkPolicy.CLASS_DEFERRED), boundary.get("two"));
        assertEquals(Integer.valueOf(MediaWorkPolicy.CLASS_IMMEDIATE), separated.get("three"));
        assertEquals(Integer.valueOf(MediaWorkPolicy.CLASS_IMMEDIATE), separated.get("four"));
    }

    @Test
    public void unknownPageBoundariesFailEveryPhotoClosed() {
        Map<String, Integer> result = MediaCaptureGrouping.classify(List.of(
                photo("first", 1_000L),
                photo("middle", 20_000L),
                photo("last", 40_000L)), true, true);

        assertEquals(Integer.valueOf(MediaWorkPolicy.CLASS_DEFERRED), result.get("first"));
        assertEquals(Integer.valueOf(MediaWorkPolicy.CLASS_DEFERRED), result.get("middle"));
        assertEquals(Integer.valueOf(MediaWorkPolicy.CLASS_DEFERRED), result.get("last"));
    }

    private static MediaCaptureGrouping.Item photo(String key, long observedAt) {
        return new MediaCaptureGrouping.Item(key, "image/jpeg", observedAt);
    }

    private static MediaCaptureGrouping.Item video(String key, long observedAt) {
        return new MediaCaptureGrouping.Item(key, "video/mp4", observedAt);
    }
}
