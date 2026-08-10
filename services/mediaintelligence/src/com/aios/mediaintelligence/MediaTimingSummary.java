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
        final Long p50VideoAudioDurationMillis;
        final Long p95VideoAudioDurationMillis;
        final Long p50VideoAudioPipelineMillis;
        final Long p95VideoAudioPipelineMillis;
        final Long p50VideoAudioRealtimeFactorPermille;
        final Long p95VideoAudioRealtimeFactorPermille;
        final int videoAudioSampleCount;
        final int videoAudioRealtimeFactorSampleCount;

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
            p50VideoAudioDurationMillis = nearestRankPercentile(
                    values(samples, Value.VIDEO_AUDIO_DURATION), 0.50);
            p95VideoAudioDurationMillis = nearestRankPercentile(
                    values(samples, Value.VIDEO_AUDIO_DURATION), 0.95);
            p50VideoAudioPipelineMillis = nearestRankPercentile(
                    values(samples, Value.VIDEO_AUDIO_PIPELINE), 0.50);
            p95VideoAudioPipelineMillis = nearestRankPercentile(
                    values(samples, Value.VIDEO_AUDIO_PIPELINE), 0.95);
            p50VideoAudioRealtimeFactorPermille = nearestRankPercentile(
                    values(samples, Value.VIDEO_AUDIO_REALTIME_FACTOR), 0.50);
            p95VideoAudioRealtimeFactorPermille = nearestRankPercentile(
                    values(samples, Value.VIDEO_AUDIO_REALTIME_FACTOR), 0.95);
            videoAudioSampleCount = values(samples, Value.VIDEO_AUDIO_PIPELINE).size();
            videoAudioRealtimeFactorSampleCount =
                    values(samples, Value.VIDEO_AUDIO_REALTIME_FACTOR).size();
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
            StringBuilder output = new StringBuilder(1_024);
            output.append("{\"schema_version\":2")
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
        MODEL_REQUEST,
        VIDEO_AUDIO_DURATION,
        VIDEO_AUDIO_PIPELINE,
        VIDEO_AUDIO_REALTIME_FACTOR
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
                case VIDEO_AUDIO_DURATION:
                    item = sample.videoAudioDurationMillis;
                    break;
                case VIDEO_AUDIO_PIPELINE:
                    item = sample.videoAudioPipelineMillis;
                    break;
                case VIDEO_AUDIO_REALTIME_FACTOR:
                    item = videoAudioRealtimeFactorPermille(sample);
                    break;
                default:
                    throw new AssertionError(value);
            }
            if (item >= 0L) values.add(item);
        }
        return values;
    }

    private static long videoAudioRealtimeFactorPermille(MediaTiming.Sample sample) {
        if (sample.videoAudioDurationMillis <= 0L
                || sample.videoAudioPipelineMillis < 0L) {
            return MediaTiming.UNKNOWN_MILLIS;
        }
        return Math.max(0L, Math.round(
                (double) sample.videoAudioPipelineMillis * 1_000.0d
                        / sample.videoAudioDurationMillis));
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
        appendMetric(
                output, "p50_video_audio_duration_ms", group.p50VideoAudioDurationMillis);
        appendMetric(
                output, "p95_video_audio_duration_ms", group.p95VideoAudioDurationMillis);
        appendMetric(
                output, "p50_video_audio_pipeline_ms", group.p50VideoAudioPipelineMillis);
        appendMetric(
                output, "p95_video_audio_pipeline_ms", group.p95VideoAudioPipelineMillis);
        appendMetric(
                output,
                "p50_video_audio_realtime_factor_permille",
                group.p50VideoAudioRealtimeFactorPermille);
        appendMetric(
                output,
                "p95_video_audio_realtime_factor_permille",
                group.p95VideoAudioRealtimeFactorPermille);
        output.append(",\"video_audio_realtime_factor_sample_count\":")
                .append(group.videoAudioRealtimeFactorSampleCount)
                .append(",\"video_audio_sample_count\":")
                .append(group.videoAudioSampleCount)
                .append('}');
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
