package com.aios.modelbenchmark;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Deterministic aggregate math shared by the device runner and host tests. */
public final class BenchmarkMath {
    private BenchmarkMath() {}

    public static double rate(int successes, int attempts) {
        if (attempts <= 0 || successes < 0 || successes > attempts) {
            throw new IllegalArgumentException("invalid rate counts");
        }
        return (double) successes / attempts;
    }

    public static double percentileLong(List<Long> values, double quantile) {
        if (values.isEmpty() || quantile <= 0.0 || quantile > 1.0) {
            throw new IllegalArgumentException("invalid percentile input");
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = Math.max(0, (int) Math.ceil(quantile * sorted.size()) - 1);
        return sorted.get(index);
    }

    public static double percentileDouble(List<Double> values, double quantile) {
        if (values.isEmpty() || quantile <= 0.0 || quantile > 1.0) {
            throw new IllegalArgumentException("invalid percentile input");
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = Math.max(0, (int) Math.ceil(quantile * sorted.size()) - 1);
        return sorted.get(index);
    }

    public static int approximateTokens(String value) {
        return words(value).size();
    }

    public static double wordErrorRate(String reference, String hypothesis) {
        List<String> expected = words(reference);
        List<String> actual = words(hypothesis);
        if (expected.isEmpty()) {
            throw new IllegalArgumentException("reference text is empty");
        }
        int[] previous = new int[actual.size() + 1];
        for (int column = 0; column <= actual.size(); column++) {
            previous[column] = column;
        }
        for (int row = 1; row <= expected.size(); row++) {
            int[] current = new int[actual.size() + 1];
            current[0] = row;
            for (int column = 1; column <= actual.size(); column++) {
                int substitution = previous[column - 1]
                        + (expected.get(row - 1).equals(actual.get(column - 1)) ? 0 : 1);
                current[column] = Math.min(
                        Math.min(previous[column] + 1, current[column - 1] + 1),
                        substitution);
            }
            previous = current;
        }
        return (double) previous[actual.size()] / expected.size();
    }

    public static boolean containsNormalizedWord(String value, String expected) {
        return words(value).containsAll(words(expected));
    }

    public static long sourceRelativeLagOrTimeout(
            long callbackAtMillis,
            long inputStartedAtMillis,
            long sourceEndMillis,
            long timeoutMillis) {
        if (callbackAtMillis <= 0L || inputStartedAtMillis <= 0L
                || sourceEndMillis < 0L || timeoutMillis <= 0L) {
            return timeoutMillis;
        }
        return Math.max(
                0L, callbackAtMillis - inputStartedAtMillis - sourceEndMillis);
    }

    public static long endpointDelayOrTimeout(
            long finalCallbackAtMillis,
            long inputStartedAtMillis,
            long speechDurationMillis,
            long timeoutMillis) {
        if (finalCallbackAtMillis <= 0L || inputStartedAtMillis <= 0L
                || speechDurationMillis < 0L || timeoutMillis <= 0L) {
            return timeoutMillis;
        }
        return Math.max(
                0L, finalCallbackAtMillis - inputStartedAtMillis - speechDurationMillis);
    }

    public static long sourceSpanOrTimeout(
            long sourceStartMillis, long sourceEndMillis, long timeoutMillis) {
        if (sourceStartMillis < 0L || sourceEndMillis < sourceStartMillis
                || timeoutMillis <= 0L) {
            return timeoutMillis;
        }
        return sourceEndMillis - sourceStartMillis;
    }

    public static boolean isNormalizedEmbedding(
            float[] values, int dimensions, double tolerance) {
        if (values == null || values.length != dimensions
                || dimensions <= 0 || tolerance < 0.0 || tolerance >= 1.0) {
            return false;
        }
        double squaredNorm = 0.0;
        for (float value : values) {
            if (!Float.isFinite(value)) return false;
            squaredNorm += (double) value * value;
        }
        double minimum = (1.0 - tolerance) * (1.0 - tolerance);
        double maximum = (1.0 + tolerance) * (1.0 + tolerance);
        return Double.isFinite(squaredNorm)
                && squaredNorm >= minimum
                && squaredNorm <= maximum;
    }

    public static double cosine(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0
                || left.length != right.length) {
            return Double.NaN;
        }
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int index = 0; index < left.length; index++) {
            if (!Float.isFinite(left[index]) || !Float.isFinite(right[index])) {
                return Double.NaN;
            }
            dot += (double) left[index] * right[index];
            leftNorm += (double) left[index] * left[index];
            rightNorm += (double) right[index] * right[index];
        }
        if (!(leftNorm > 0.0) || !(rightNorm > 0.0)) return Double.NaN;
        return dot / Math.sqrt(leftNorm * rightNorm);
    }

    private static List<String> words(String value) {
        String normalized = Normalizer.normalize(
                value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        if (normalized.isEmpty()) {
            return List.of();
        }
        return List.of(normalized.split(" +"));
    }
}
