package com.aios.contextintelligence;

import java.util.Arrays;

/** A normalized 256-dimensional embedding stored as a symmetric int8 vector. */
final class QuantizedEmbedding {
    static final int DIMENSIONS = 256;

    private final byte[] values;
    private final float scale;
    private final float norm;

    private QuantizedEmbedding(byte[] values, float scale, float norm) {
        this.values = values;
        this.scale = scale;
        this.norm = norm;
    }

    static QuantizedEmbedding quantize(float[] input) {
        requireVector(input);
        double squaredNorm = 0.0;
        for (float value : input) squaredNorm += (double) value * value;
        if (!(squaredNorm > 0.0) || !Double.isFinite(squaredNorm)) {
            throw new IllegalArgumentException("embedding must have a finite non-zero norm");
        }
        float inputNorm = (float) Math.sqrt(squaredNorm);
        float maxAbsolute = 0.0f;
        for (float value : input) {
            maxAbsolute = Math.max(maxAbsolute, Math.abs(value / inputNorm));
        }
        float scale = maxAbsolute / 127.0f;
        if (!(scale > 0.0f) || !Float.isFinite(scale)) {
            throw new IllegalArgumentException("embedding quantization scale is invalid");
        }
        byte[] quantized = new byte[DIMENSIONS];
        double quantizedSquaredNorm = 0.0;
        for (int index = 0; index < DIMENSIONS; index++) {
            int value = Math.round((input[index] / inputNorm) / scale);
            value = Math.max(-127, Math.min(127, value));
            quantized[index] = (byte) value;
            double restored = value * (double) scale;
            quantizedSquaredNorm += restored * restored;
        }
        float norm = (float) Math.sqrt(quantizedSquaredNorm);
        return restore(quantized, scale, norm);
    }

    static QuantizedEmbedding restore(byte[] values, float scale, float norm) {
        if (values == null || values.length != DIMENSIONS
                || !(scale > 0.0f) || !Float.isFinite(scale)
                || !(norm > 0.0f) || !Float.isFinite(norm)) {
            throw new IllegalArgumentException("invalid stored embedding");
        }
        return new QuantizedEmbedding(Arrays.copyOf(values, values.length), scale, norm);
    }

    float cosine(float[] query) {
        requireVector(query);
        double querySquaredNorm = 0.0;
        for (float value : query) querySquaredNorm += (double) value * value;
        if (!(querySquaredNorm > 0.0) || !Double.isFinite(querySquaredNorm)) {
            throw new IllegalArgumentException("query embedding must have a finite non-zero norm");
        }
        double dot = 0.0;
        for (int index = 0; index < DIMENSIONS; index++) {
            dot += values[index] * (double) scale * query[index];
        }
        double cosine = dot / (norm * Math.sqrt(querySquaredNorm));
        return (float) Math.max(-1.0, Math.min(1.0, cosine));
    }

    byte[] values() {
        return Arrays.copyOf(values, values.length);
    }

    float scale() {
        return scale;
    }

    float norm() {
        return norm;
    }

    private static void requireVector(float[] vector) {
        if (vector == null || vector.length != DIMENSIONS) {
            throw new IllegalArgumentException("embedding must have exactly 256 dimensions");
        }
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("embedding contains a non-finite value");
            }
        }
    }
}
