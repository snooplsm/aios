package com.aios.mediaintelligence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Aggregate timing evidence that never includes a URI, caption, tag, or model output. */
final class MediaTimingSummary {
    static final int MAX_SAMPLES_PER_KIND = 100;

    static final class Group {
        final int sampleCount;
        final Long p50ObservedToIndexMillis;
        final Long p95ObservedToIndexMillis;
        final Long p50QueueToStartMillis;
        final Long p95QueueToStartMillis;
        final Long p50ProcessingMillis;
        final Long p95ProcessingMillis;
        final Long p50InputPreparationMillis;
        final Long p95InputPreparationMillis;
        final Long p50ModelRequestMillis;
        final Long p95ModelRequestMillis;

        Group(List<MediaTiming.Sample> samples) {
            sampleCount = samples.size();
            p50ObservedToIndexMillis = nearestRankPercentile(values(
                    samples, Value.OBSERVED_TO_INDEX), 0.50);
            p95ObservedToIndexMillis = nearestRankPercentile(values(
                    samples, Value.OBSERVED_TO_INDEX), 0.95);
            p50QueueToStartMillis = nearestRankPercentile(
                    values(samples, Value.QUEUE_TO_START), 0.50);
            p95QueueToStartMillis = nearestRankPercentile(
                    values(samples, Value.QUEUE_TO_START), 0.95);
            p50ProcessingMillis = nearestRankPercentile(
                    values(samples, Value.PROCESSING), 0.50);
            p95ProcessingMillis = nearestRankPercentile(
                    values(samples, Value.PROCESSING), 0.95);
            p50InputPreparationMillis = nearestRankPercentile(values(
                    samples, Value.INPUT_PREPARATION), 0.50);
            p95InputPreparationMillis = nearestRankPercentile(values(
                    samples, Value.INPUT_PREPARATION), 0.95);
            p50ModelRequestMillis = nearestRankPercentile(
                    values(samples, Value.MODEL_REQUEST), 0.50);
            p95ModelRequestMillis = nearestRankPercentile(
                    values(samples, Value.MODEL_REQUEST), 0.95);
        }
    }

    static final class Snapshot {
        final long generatedAtEpochMillis;
        final Group photos;
        final Group videos;

        Snapshot(
                long generatedAtEpochMillis,
                List<MediaTiming.Sample> photos,
                List<MediaTiming.Sample> videos) {
            if (generatedAtEpochMillis <= 0L
                    || photos.size() > MAX_SAMPLES_PER_KIND
                    || videos.size() > MAX_SAMPLES_PER_KIND) {
                throw new IllegalArgumentException("invalid media timing snapshot");
            }
            this.generatedAtEpochMillis = generatedAtEpochMillis;
            this.photos = new Group(photos);
            this.videos = new Group(videos);
        }

        String toJson() {
            StringBuilder output = new StringBuilder(768);
            output.append("{\"schema_version\":1")
                    .append(",\"generated_at_epoch_ms\":")
                    .append(generatedAtEpochMillis)
                    .append(",\"max_samples_per_kind\":")
                    .append(MAX_SAMPLES_PER_KIND)
                    .append(",\"photos\":");
            appendGroup(output, photos);
            output.append(",\"videos\":");
            appendGroup(output, videos);
            return output.append('}').toString();
        }
    }

    private enum Value {
        OBSERVED_TO_INDEX,
        QUEUE_TO_START,
        PROCESSING,
        INPUT_PREPARATION,
        MODEL_REQUEST
    }

    private MediaTimingSummary() {}

    static Snapshot snapshot(
            long generatedAtEpochMillis,
            List<MediaTiming.Sample> photos,
            List<MediaTiming.Sample> videos) {
        return new Snapshot(generatedAtEpochMillis, List.copyOf(photos), List.copyOf(videos));
    }

    private static List<Long> values(List<MediaTiming.Sample> samples, Value value) {
        List<Long> values = new ArrayList<>(samples.size());
        for (MediaTiming.Sample sample : samples) {
            long item;
            switch (value) {
                case OBSERVED_TO_INDEX:
                    item = sample.observedToIndexMillis;
                    break;
                case QUEUE_TO_START:
                    item = sample.queueToStartMillis;
                    break;
                case PROCESSING:
                    item = sample.processingMillis;
                    break;
                case INPUT_PREPARATION:
                    item = sample.inputPreparationMillis;
                    break;
                case MODEL_REQUEST:
                    item = sample.modelRequestMillis;
                    break;
                default:
                    throw new AssertionError(value);
            }
            if (item >= 0L) values.add(item);
        }
        return values;
    }

    private static Long nearestRankPercentile(List<Long> values, double fraction) {
        if (values.isEmpty()) return null;
        Collections.sort(values);
        int index = Math.max(0, (int) Math.ceil(fraction * values.size()) - 1);
        return values.get(index);
    }

    private static void appendGroup(StringBuilder output, Group group) {
        output.append("{\"sample_count\":").append(group.sampleCount);
        appendMetric(output, "p50_observed_to_index_ms", group.p50ObservedToIndexMillis);
        appendMetric(output, "p95_observed_to_index_ms", group.p95ObservedToIndexMillis);
        appendMetric(output, "p50_queue_to_start_ms", group.p50QueueToStartMillis);
        appendMetric(output, "p95_queue_to_start_ms", group.p95QueueToStartMillis);
        appendMetric(output, "p50_processing_ms", group.p50ProcessingMillis);
        appendMetric(output, "p95_processing_ms", group.p95ProcessingMillis);
        appendMetric(output, "p50_input_preparation_ms", group.p50InputPreparationMillis);
        appendMetric(output, "p95_input_preparation_ms", group.p95InputPreparationMillis);
        appendMetric(output, "p50_model_request_ms", group.p50ModelRequestMillis);
        appendMetric(output, "p95_model_request_ms", group.p95ModelRequestMillis);
        output.append('}');
    }

    private static void appendMetric(StringBuilder output, String name, Long value) {
        output.append(",\"").append(name).append("\":");
        if (value == null) {
            output.append("null");
        } else {
            output.append(value);
        }
    }
}
