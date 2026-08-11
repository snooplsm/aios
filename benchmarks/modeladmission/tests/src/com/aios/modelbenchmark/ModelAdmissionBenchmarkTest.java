package com.aios.modelbenchmark;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.ActivityManager;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.AudioFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.SystemClock;
import android.telecom.TelecomManager;
import android.util.Base64;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.aios.model.AudioStreamFormat;
import com.aios.model.GenerationChunk;
import com.aios.model.IAiosModelService;
import com.aios.model.IModelCallback;
import com.aios.model.InferenceResult;
import com.aios.model.ModelCapability;
import com.aios.model.ModelRequest;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Userdebug-only end-to-end measurements through the production Model Broker.
 *
 * The runner emits measurements, never admission decisions. Host tooling binds
 * them to device/build identity and independently evaluates the checked-in
 * benchmark suite.
 */
@RunWith(AndroidJUnit4.class)
public final class ModelAdmissionBenchmarkTest {
    private static final int RUNS_PER_LANGUAGE = 5;
    private static final int PCM_16_BIT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int TTS_SAMPLE_RATE = 44_100;
    private static final int ASR_SAMPLE_RATE = 16_000;
    private static final int ASR_PACING_FRAME_MILLIS = 100;
    private static final int ASR_PACING_FRAME_BYTES =
            ASR_SAMPLE_RATE * ASR_PACING_FRAME_MILLIS / 1_000 * 2;
    private static final int ASR_ENDPOINT_SILENCE_MILLIS = 800;
    private static final long BIND_TIMEOUT_MILLIS = 15_000L;
    private static final long INFERENCE_TIMEOUT_MILLIS = 120_000L;
    private static final String ARTIFACT_MANIFEST =
            "/product/etc/aios/model_artifacts.json";
    private static final String EN_PHRASE =
            "Thank you for calling. I need help with a plumbing appointment tomorrow.";
    private static final String ES_PHRASE =
            "Gracias por llamar. Necesito ayuda con una cita de plomería mañana.";

    @Test
    public void runAdmissionBenchmark() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        assertTrue("benchmark requires an eng/userdebug build",
                "eng".equals(Build.TYPE) || "userdebug".equals(Build.TYPE));
        TelecomManager telecom = context.getSystemService(TelecomManager.class);
        assertNotNull("Telecom is unavailable", telecom);
        assertFalse("refusing to benchmark during a live call", telecom.isInCall());

        ArtifactCatalog artifacts = ArtifactCatalog.load(new File(ARTIFACT_MANIFEST));
        try (BrokerConnection connection = BrokerConnection.bind(context)) {
            IAiosModelService broker = connection.service;
            Map<String, ModelCapability> capabilities = waitForCapabilities(broker);
            requireCapabilities(capabilities);
            assertEquals(
                    "image and storyboard video must select the same admitted media model",
                    capabilities.get("image_understanding").selectedModelId,
                    capabilities.get("video_understanding").selectedModelId);

            JSONArray results = new JSONArray();
            results.put(benchmarkMedia(
                    context, broker, artifacts.require(capabilities.get(
                            "image_understanding").selectedModelId)));
            results.put(benchmarkText(
                    context, broker, artifacts.require(capabilities.get(
                            "text_generation").selectedModelId)));
            TtsOutput tts = benchmarkTts(
                    context, broker, artifacts.require(capabilities.get(
                            "speech_synthesis").selectedModelId));
            results.put(tts.result);
            results.put(benchmarkAsr(
                    context,
                    broker,
                    artifacts.require(capabilities.get("streaming_asr").selectedModelId),
                    tts.englishPcm,
                    tts.spanishPcm));

            JSONObject measurements = new JSONObject()
                    .put("schema_version", 1)
                    .put("suite_version", 4)
                    .put("results", results);
            Bundle output = new Bundle();
            output.putString(
                    "aios_measurements_base64",
                    Base64.encodeToString(
                            measurements.toString().getBytes(StandardCharsets.UTF_8),
                            Base64.NO_WRAP));
            instrumentation.addResults(output);
        }
    }

    private static JSONObject benchmarkText(
            Context context, IAiosModelService broker, Artifact artifact) throws Exception {
        Aggregate aggregate = new Aggregate();
        List<Long> firstTokenMillis = new ArrayList<>();
        List<Double> throughput = new ArrayList<>();
        int valid = 0;
        int englishKnown = 0;
        int spanishKnown = 0;
        for (String language : List.of("en", "es")) {
            for (int run = 0; run < RUNS_PER_LANGUAGE; run++) {
                String prompt = language.equals("es")
                        ? "Responde solo con la palabra azul: ¿de qué color es un cielo despejado de día?"
                        : "Reply with only the word blue: what color is a clear daytime sky?";
                Invocation invocation;
                try (ResourceSampler sampler = new ResourceSampler(context)) {
                    invocation = invokeText(
                            broker, "text_generation", "call_agent", language, prompt, 32);
                    aggregate.record(invocation, artifact, sampler.finish());
                }
                String text = resultText(invocation.result);
                boolean outputValid = text != null && !text.isBlank();
                if (outputValid) valid++;
                boolean known = outputValid && BenchmarkMath.containsNormalizedWord(
                        text, language.equals("es") ? "azul" : "blue");
                if (language.equals("es") && known) spanishKnown++;
                if (language.equals("en") && known) englishKnown++;
                firstTokenMillis.add(invocation.firstLatencyOrTimeout());
                double seconds = Math.max(0.001,
                        (invocation.completedAt - invocation.startedAt) / 1000.0);
                throughput.add(outputValid
                        ? BenchmarkMath.approximateTokens(text) / seconds : 0.0);
            }
        }
        JSONObject metrics = aggregate.commonMetrics()
                .put("output_valid_rate", BenchmarkMath.rate(valid, aggregate.attempts))
                .put("p95_first_token_ms",
                        BenchmarkMath.percentileLong(firstTokenMillis, 0.95))
                .put("p50_output_tokens_per_second",
                        BenchmarkMath.percentileDouble(throughput, 0.5))
                .put("en_known_answer_rate",
                        BenchmarkMath.rate(englishKnown, RUNS_PER_LANGUAGE))
                .put("es_known_answer_rate",
                        BenchmarkMath.rate(spanishKnown, RUNS_PER_LANGUAGE));
        return result(artifact, metrics);
    }

    private static JSONObject benchmarkMedia(
            Context context, IAiosModelService broker, Artifact artifact) throws Exception {
        Aggregate aggregate = new Aggregate();
        List<Long> imageLatency = new ArrayList<>();
        List<Long> videoLatency = new ArrayList<>();
        int valid = 0;
        int englishKnown = 0;
        int spanishKnown = 0;
        int videoAttempts = 0;
        int videoSuccesses = 0;
        int videoValid = 0;
        byte[] redJpeg = redJpeg();
        for (String capability : List.of(
                "image_understanding", "video_understanding")) {
            for (String language : List.of("en", "es")) {
                for (int run = 0; run < RUNS_PER_LANGUAGE; run++) {
                    Invocation invocation;
                    try (ResourceSampler sampler = new ResourceSampler(context)) {
                        invocation = invokeMedia(broker, capability, language, redJpeg);
                        aggregate.record(invocation, artifact, sampler.finish());
                    }
                    JSONObject output = parseObject(invocation.result);
                    boolean outputValid = output != null
                            && output.optInt("schema_version", -1) == 1
                            && language.equals(output.optString("language"))
                            && !output.optString("caption").isBlank();
                    if (outputValid) valid++;
                    boolean known = outputValid && BenchmarkMath.containsNormalizedWord(
                            output.toString(), language.equals("es") ? "rojo" : "red");
                    if (language.equals("es") && known) spanishKnown++;
                    if (language.equals("en") && known) englishKnown++;
                    if ("video_understanding".equals(capability)) {
                        videoAttempts++;
                        if (invocation.succeeded()) videoSuccesses++;
                        if (outputValid) videoValid++;
                        videoLatency.add(invocation.elapsedOrTimeout());
                    } else {
                        imageLatency.add(invocation.elapsedOrTimeout());
                    }
                }
            }
        }
        List<Long> warmImageLatency = imageLatency.size() > 1
                ? imageLatency.subList(1, imageLatency.size()) : imageLatency;
        JSONObject metrics = aggregate.commonMetrics()
                .put("output_valid_rate", BenchmarkMath.rate(valid, aggregate.attempts))
                .put("video_invocation_success_rate",
                        BenchmarkMath.rate(videoSuccesses, videoAttempts))
                .put("video_output_valid_rate", BenchmarkMath.rate(videoValid, videoAttempts))
                .put("p95_latency_ms", BenchmarkMath.percentileLong(imageLatency, 0.95))
                .put("p95_image_latency_ms",
                        BenchmarkMath.percentileLong(imageLatency, 0.95))
                .put("first_image_latency_ms", imageLatency.get(0))
                .put("p50_warm_image_latency_ms",
                        BenchmarkMath.percentileLong(warmImageLatency, 0.50))
                .put("p95_video_storyboard_inference_ms",
                        BenchmarkMath.percentileLong(videoLatency, 0.95))
                .put("en_known_answer_rate",
                        BenchmarkMath.rate(englishKnown, RUNS_PER_LANGUAGE * 2))
                .put("es_known_answer_rate",
                        BenchmarkMath.rate(spanishKnown, RUNS_PER_LANGUAGE * 2));
        return result(artifact, metrics);
    }

    private static TtsOutput benchmarkTts(
            Context context, IAiosModelService broker, Artifact artifact) throws Exception {
        Aggregate aggregate = new Aggregate();
        List<Long> firstAudioMillis = new ArrayList<>();
        List<Double> realtimeFactors = new ArrayList<>();
        int englishValid = 0;
        int spanishValid = 0;
        byte[] englishFixture = null;
        byte[] spanishFixture = null;
        for (String language : List.of("en", "es")) {
            String phrase = language.equals("es") ? ES_PHRASE : EN_PHRASE;
            for (int run = 0; run < RUNS_PER_LANGUAGE; run++) {
                AudioInvocation audio;
                try (ResourceSampler sampler = new ResourceSampler(context)) {
                    audio = invokeTts(broker, language, phrase);
                    aggregate.record(audio.invocation, artifact, sampler.finish());
                }
                boolean valid = audio.invocation.succeeded()
                        && validTtsResult(audio.invocation.result)
                        && nonSilentPcm(audio.pcm);
                if (valid && language.equals("en")) {
                    englishValid++;
                    if (englishFixture == null) englishFixture = audio.pcm;
                }
                if (valid && language.equals("es")) {
                    spanishValid++;
                    if (spanishFixture == null) spanishFixture = audio.pcm;
                }
                firstAudioMillis.add(audio.firstAudioAt > 0L
                        ? audio.firstAudioAt - audio.invocation.startedAt
                        : INFERENCE_TIMEOUT_MILLIS);
                double audioSeconds = Math.max(
                        0.001, audio.pcm.length / 2.0 / TTS_SAMPLE_RATE);
                realtimeFactors.add(
                        audio.invocation.elapsedOrTimeout() / 1000.0 / audioSeconds);
            }
        }
        JSONObject metrics = aggregate.commonMetrics()
                .put("p95_time_to_first_audio_ms",
                        BenchmarkMath.percentileLong(firstAudioMillis, 0.95))
                .put("p95_realtime_factor",
                        BenchmarkMath.percentileDouble(realtimeFactors, 0.95))
                .put("en_output_valid_rate",
                        BenchmarkMath.rate(englishValid, RUNS_PER_LANGUAGE))
                .put("es_output_valid_rate",
                        BenchmarkMath.rate(spanishValid, RUNS_PER_LANGUAGE));
        if (englishFixture == null) englishFixture = new byte[ASR_SAMPLE_RATE * 2];
        if (spanishFixture == null) spanishFixture = new byte[ASR_SAMPLE_RATE * 2];
        return new TtsOutput(
                result(artifact, metrics),
                resampleTtsTo16k(englishFixture),
                resampleTtsTo16k(spanishFixture));
    }

    private static JSONObject benchmarkAsr(
            Context context,
            IAiosModelService broker,
            Artifact artifact,
            byte[] englishPcm,
            byte[] spanishPcm) throws Exception {
        Aggregate aggregate = new Aggregate();
        List<Long> partialLatency = new ArrayList<>();
        List<Long> finalLatency = new ArrayList<>();
        List<Long> endpointDelay = new ArrayList<>();
        List<Long> firstPartialSourceSpan = new ArrayList<>();
        List<Double> realtimeFactors = new ArrayList<>();
        int livePartialSuccesses = 0;
        int liveFinalSuccesses = 0;
        int liveAttempts = 0;
        int englishLanguageDetections = 0;
        int spanishLanguageDetections = 0;
        double englishWer = 0.0;
        double spanishWer = 0.0;
        for (String language : List.of("en", "es")) {
            byte[] speech = language.equals("es") ? spanishPcm : englishPcm;
            byte[] withEndpointSilence = appendSilence(
                    speech, ASR_ENDPOINT_SILENCE_MILLIS);
            String reference = language.equals("es") ? ES_PHRASE : EN_PHRASE;
            for (int run = 0; run < RUNS_PER_LANGUAGE; run++) {
                Invocation live;
                try (ResourceSampler sampler = new ResourceSampler(context)) {
                    live = invokeAsr(broker, withEndpointSilence, true);
                    aggregate.record(live, artifact, sampler.finish());
                }
                liveAttempts++;
                if (live.sawNonFinalPartial()) livePartialSuccesses++;
                if (live.sawFinalEndpoint()) liveFinalSuccesses++;
                if (language.equals(live.finalChunkLanguage)) {
                    if (language.equals("es")) spanishLanguageDetections++;
                    else englishLanguageDetections++;
                }
                partialLatency.add(live.firstPartialProcessingLagOrTimeout());
                finalLatency.add(live.finalProcessingLagOrTimeout());
                endpointDelay.add(live.finalEndpointDelayOrTimeout(
                        durationMillis(speech)));
                firstPartialSourceSpan.add(
                        live.firstPartialSourceSpanOrTimeout());
                double wer = live.latestChunk.isBlank()
                        ? 1.0
                        : BenchmarkMath.wordErrorRate(reference, live.latestChunk);
                if (language.equals("es")) spanishWer += wer;
                else englishWer += wer;

                Invocation throughput;
                try (ResourceSampler sampler = new ResourceSampler(context)) {
                    throughput = invokeAsr(broker, withEndpointSilence, false);
                    aggregate.record(throughput, artifact, sampler.finish());
                }
                double sourceSeconds = Math.max(
                        0.001, withEndpointSilence.length / 2.0 / ASR_SAMPLE_RATE);
                realtimeFactors.add(
                        throughput.elapsedOrTimeout() / 1000.0 / sourceSeconds);
            }
        }
        JSONObject metrics = aggregate.commonMetrics()
                .put("live_non_final_partial_rate",
                        BenchmarkMath.rate(livePartialSuccesses, liveAttempts))
                .put("live_final_endpoint_rate",
                        BenchmarkMath.rate(liveFinalSuccesses, liveAttempts))
                .put("en_language_detection_rate",
                        BenchmarkMath.rate(englishLanguageDetections, RUNS_PER_LANGUAGE))
                .put("es_language_detection_rate",
                        BenchmarkMath.rate(spanishLanguageDetections, RUNS_PER_LANGUAGE))
                .put("p95_partial_latency_ms",
                        BenchmarkMath.percentileLong(partialLatency, 0.95))
                .put("p95_final_latency_ms",
                        BenchmarkMath.percentileLong(finalLatency, 0.95))
                .put("p95_endpoint_delay_ms",
                        BenchmarkMath.percentileLong(endpointDelay, 0.95))
                .put("p95_first_partial_source_span_ms",
                        BenchmarkMath.percentileLong(firstPartialSourceSpan, 0.95))
                .put("p95_realtime_factor",
                        BenchmarkMath.percentileDouble(realtimeFactors, 0.95))
                .put("en_wer", englishWer / RUNS_PER_LANGUAGE)
                .put("es_wer", spanishWer / RUNS_PER_LANGUAGE);
        return result(artifact, metrics);
    }

    private static Invocation invokeText(
            IAiosModelService broker,
            String capability,
            String workload,
            String language,
            String text,
            int maxTokens) throws Exception {
        Invocation invocation = new Invocation();
        try {
            long session = broker.createSession(
                    request(capability, workload, language, maxTokens), invocation.callback);
            invocation.sessionId = session;
            if (session > 0L) broker.submitText(session, text, true);
        } catch (Exception error) {
            invocation.failLocal(error);
        }
        invocation.await(broker);
        return invocation;
    }

    private static Invocation invokeMedia(
            IAiosModelService broker,
            String capability,
            String language,
            byte[] media) throws Exception {
        Invocation invocation = new Invocation();
        ParcelFileDescriptor[] pipe = null;
        try {
            long session = broker.createSession(request(
                    capability, "media_background", language, 256),
                    invocation.callback);
            invocation.sessionId = session;
            if (session > 0L) {
                pipe = ParcelFileDescriptor.createPipe();
                try (ParcelFileDescriptor.AutoCloseOutputStream output =
                             new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    output.write(media);
                }
                broker.submitMedia(session, pipe[0], "image/jpeg", true);
                pipe[0].close();
            }
        } catch (Exception error) {
            invocation.failLocal(error);
        } finally {
            closePipe(pipe);
        }
        invocation.await(broker);
        return invocation;
    }

    private static AudioInvocation invokeTts(
            IAiosModelService broker, String language, String text) throws Exception {
        Invocation invocation = new Invocation();
        ExecutorService reader = null;
        AtomicLong firstAudioAt = new AtomicLong(0L);
        Future<byte[]> pcm = null;
        byte[] bytes = new byte[0];
        ParcelFileDescriptor[] pipe = null;
        try {
            long session = broker.createSession(request(
                    "speech_synthesis", "call_agent", language, 0), invocation.callback);
            invocation.sessionId = session;
            if (session > 0L) {
                pipe = ParcelFileDescriptor.createPipe();
                ParcelFileDescriptor readEnd = pipe[0];
                reader = Executors.newSingleThreadExecutor();
                pcm = reader.submit(() -> readAll(readEnd, firstAudioAt));
                AudioStreamFormat format = audioFormat(TTS_SAMPLE_RATE, "synthesis");
                broker.attachAudioOutput(session, pipe[1], format);
                pipe[1].close();
                broker.submitText(session, text, true);
            }
        } catch (Exception error) {
            invocation.failLocal(error);
        }
        try {
            invocation.await(broker);
            if (pcm != null) bytes = pcm.get(30, TimeUnit.SECONDS);
        } catch (Exception error) {
            invocation.failLocal(error);
        } finally {
            closePipe(pipe);
            if (reader != null) reader.shutdownNow();
        }
        return new AudioInvocation(invocation, bytes, firstAudioAt.get());
    }

    private static Invocation invokeAsr(
            IAiosModelService broker,
            byte[] pcm,
            boolean paceAtRealtime) throws Exception {
        Invocation invocation = new Invocation();
        ExecutorService writer = null;
        Future<?> write = null;
        ParcelFileDescriptor[] pipe = null;
        try {
            ModelRequest request = request(
                    "streaming_asr", "call_rx", "und", 0);
            if (paceAtRealtime) {
                request.deadlineElapsedRealtimeMillis = Long.MAX_VALUE;
            }
            long session = broker.createSession(request, invocation.callback);
            invocation.sessionId = session;
            if (session > 0L) {
                pipe = ParcelFileDescriptor.createPipe();
                writer = Executors.newSingleThreadExecutor();
                ParcelFileDescriptor writeEnd = pipe[1];
                write = writer.submit(() -> {
                    try (ParcelFileDescriptor.AutoCloseOutputStream output =
                                 new ParcelFileDescriptor.AutoCloseOutputStream(writeEnd)) {
                        writeAsrPcm(invocation, output, pcm, paceAtRealtime);
                    } catch (Exception error) {
                        invocation.failLocal(error);
                        throw error;
                    }
                    return null;
                });
                broker.submitAudio(
                        session, pipe[0], audioFormat(ASR_SAMPLE_RATE, "downlink"), false);
                pipe[0].close();
            }
        } catch (Exception error) {
            invocation.failLocal(error);
        }
        try {
            invocation.await(broker);
            if (write != null) write.get(30, TimeUnit.SECONDS);
        } catch (Exception error) {
            invocation.failLocal(error);
        } finally {
            closePipe(pipe);
            if (writer != null) writer.shutdownNow();
        }
        return invocation;
    }

    private static void writeAsrPcm(
            Invocation invocation,
            ParcelFileDescriptor.AutoCloseOutputStream output,
            byte[] pcm,
            boolean paceAtRealtime) throws IOException, InterruptedException {
        if ((pcm.length & 1) != 0) {
            throw new IOException("ASR fixture must contain complete PCM16 samples");
        }
        long startedAt = SystemClock.elapsedRealtime();
        invocation.inputStartedAt.compareAndSet(0L, startedAt);
        if (!paceAtRealtime) {
            output.write(pcm);
            return;
        }
        int offset = 0;
        while (offset < pcm.length) {
            int count = Math.min(ASR_PACING_FRAME_BYTES, pcm.length - offset);
            long frameEndMillis = (offset + count) / 2L * 1_000L / ASR_SAMPLE_RATE;
            sleepUntil(startedAt + frameEndMillis);
            output.write(pcm, offset, count);
            offset += count;
        }
    }

    private static void sleepUntil(long targetElapsedRealtimeMillis)
            throws InterruptedException {
        while (true) {
            long remaining = targetElapsedRealtimeMillis - SystemClock.elapsedRealtime();
            if (remaining <= 0L) return;
            Thread.sleep(remaining);
        }
    }

    private static ModelRequest request(
            String capability, String workload, String language, int maxTokens) {
        ModelRequest request = new ModelRequest();
        request.requestId = "benchmark-" + capability + "-" + language + "-"
                + Long.toUnsignedString(System.nanoTime());
        request.capability = capability;
        request.workload = workload;
        request.language = language;
        request.maxOutputTokens = maxTokens;
        request.deadlineElapsedRealtimeMillis =
                SystemClock.elapsedRealtime() + INFERENCE_TIMEOUT_MILLIS;
        request.allowFallback = false;
        return request;
    }

    private static AudioStreamFormat audioFormat(int sampleRate, String direction) {
        AudioStreamFormat format = new AudioStreamFormat();
        format.sampleRateHz = sampleRate;
        format.channelCount = 1;
        format.pcmEncoding = PCM_16_BIT;
        format.direction = direction;
        return format;
    }

    private static Map<String, ModelCapability> waitForCapabilities(
            IAiosModelService broker) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 30_000L;
        Map<String, ModelCapability> latest = Map.of();
        do {
            Map<String, ModelCapability> values = new HashMap<>();
            for (ModelCapability capability : broker.listCapabilities()) {
                values.put(capability.capability, capability);
            }
            latest = values;
            if (List.of("text_generation", "image_understanding", "video_understanding",
                    "speech_synthesis", "streaming_asr").stream()
                    .allMatch(values::containsKey)) {
                return values;
            }
            Thread.sleep(500L);
        } while (SystemClock.elapsedRealtime() < deadline);
        return latest;
    }

    private static void requireCapabilities(Map<String, ModelCapability> values) {
        for (String capability : List.of(
                "text_generation", "image_understanding", "video_understanding",
                "speech_synthesis", "streaming_asr")) {
            ModelCapability value = values.get(capability);
            assertNotNull("missing benchmark capability " + capability, value);
            assertTrue("selected model ID is absent for " + capability,
                    value.selectedModelId != null && !value.selectedModelId.isBlank());
        }
    }

    private static JSONObject result(Artifact artifact, JSONObject metrics)
            throws JSONException {
        return new JSONObject()
                .put("model_id", artifact.modelId)
                .put("runtime", artifact.runtime)
                .put("backend", artifact.backend)
                .put("artifact_sha256", artifact.sha256)
                .put("metrics", metrics);
    }

    private static String resultText(InferenceResult result) {
        JSONObject value = parseObject(result);
        return value == null ? null : value.optString("text", null);
    }

    private static JSONObject parseObject(InferenceResult result) {
        if (result == null || result.outputJson == null) return null;
        try {
            return new JSONObject(result.outputJson);
        } catch (JSONException ignored) {
            return null;
        }
    }

    private static boolean validTtsResult(InferenceResult result) {
        JSONObject value = parseObject(result);
        return value != null
                && value.optInt("schema_version", -1) == 1
                && value.optInt("sample_rate_hz", -1) == TTS_SAMPLE_RATE
                && value.optLong("sample_count", 0L) > 0L;
    }

    private static byte[] redJpeg() throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        try {
            bitmap.eraseColor(Color.RED);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) {
                throw new IOException("cannot encode benchmark JPEG");
            }
            return output.toByteArray();
        } finally {
            bitmap.recycle();
        }
    }

    private static byte[] readAll(
            ParcelFileDescriptor descriptor, AtomicLong firstByteAt) throws IOException {
        try (ParcelFileDescriptor.AutoCloseInputStream input =
                     new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                firstByteAt.compareAndSet(0L, SystemClock.elapsedRealtime());
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void closePipe(ParcelFileDescriptor[] pipe) {
        if (pipe == null) return;
        for (ParcelFileDescriptor descriptor : pipe) {
            if (descriptor == null) continue;
            try {
                descriptor.close();
            } catch (IOException ignored) {
                // A stream or successful Binder transfer may already own it.
            }
        }
    }

    private static boolean nonSilentPcm(byte[] pcm) {
        if (pcm.length < 2_000 || (pcm.length & 1) != 0) return false;
        for (int offset = 0; offset < pcm.length; offset += 2) {
            int sample = (pcm[offset] & 0xff) | (pcm[offset + 1] << 8);
            if (Math.abs((short) sample) > 64) return true;
        }
        return false;
    }

    private static byte[] resampleTtsTo16k(byte[] input) {
        int inputSamples = input.length / 2;
        int outputSamples = (int) ((long) inputSamples * ASR_SAMPLE_RATE / TTS_SAMPLE_RATE);
        byte[] output = new byte[outputSamples * 2];
        for (int index = 0; index < outputSamples; index++) {
            double source = (double) index * TTS_SAMPLE_RATE / ASR_SAMPLE_RATE;
            int lower = Math.min(inputSamples - 1, (int) source);
            int upper = Math.min(inputSamples - 1, lower + 1);
            double fraction = source - lower;
            short left = pcm16(input, lower);
            short right = pcm16(input, upper);
            int sample = (int) Math.round(left + (right - left) * fraction);
            output[index * 2] = (byte) (sample & 0xff);
            output[index * 2 + 1] = (byte) ((sample >>> 8) & 0xff);
        }
        return output;
    }

    private static short pcm16(byte[] value, int sample) {
        int offset = sample * 2;
        return (short) ((value[offset] & 0xff) | (value[offset + 1] << 8));
    }

    private static byte[] appendSilence(byte[] input, int milliseconds) {
        int bytes = milliseconds * ASR_SAMPLE_RATE * 2 / 1000;
        byte[] output = new byte[input.length + bytes];
        System.arraycopy(input, 0, output, 0, input.length);
        return output;
    }

    private static long durationMillis(byte[] pcm) {
        return pcm.length / 2L * 1_000L / ASR_SAMPLE_RATE;
    }

    private static final class Aggregate {
        int attempts;
        int successes;
        int failures;
        int peakRssMb;
        int thermalStatusMax;

        void record(
                Invocation invocation,
                Artifact artifact,
                ResourceObservation observation) {
            if (invocation.result != null
                    && (!artifact.modelId.equals(invocation.result.modelId)
                    || !artifact.sha256.equals(invocation.result.modelDigest))) {
                invocation.error = "result identity mismatch";
            }
            attempts++;
            if (invocation.succeeded()) successes++;
            else failures++;
            peakRssMb = Math.max(peakRssMb, observation.peakRssMb);
            thermalStatusMax = Math.max(
                    thermalStatusMax, observation.thermalStatusMax);
        }

        JSONObject commonMetrics() throws JSONException {
            return new JSONObject()
                    .put("measured_runs", attempts)
                    .put("crash_count", failures)
                    .put("invocation_success_rate", BenchmarkMath.rate(successes, attempts))
                    .put("peak_rss_mb", peakRssMb)
                    .put("thermal_status_max", thermalStatusMax);
        }
    }

    private static final class ResourceSampler implements AutoCloseable {
        private static final long SAMPLE_INTERVAL_MILLIS = 50L;

        final Context context;
        final PowerManager power;
        final AtomicBoolean running = new AtomicBoolean(true);
        final AtomicInteger peakRssMb = new AtomicInteger(0);
        final AtomicInteger thermalStatusMax = new AtomicInteger(0);
        final Thread worker;

        ResourceSampler(Context context) {
            this.context = context;
            power = context.getSystemService(PowerManager.class);
            observe();
            worker = new Thread(this::sampleUntilStopped,
                    "aios-benchmark-resource-sampler");
            worker.setDaemon(true);
            worker.start();
        }

        private void sampleUntilStopped() {
            while (running.get()) {
                try {
                    Thread.sleep(SAMPLE_INTERVAL_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (running.get()) observe();
            }
        }

        private void observe() {
            peakRssMb.accumulateAndGet(runtimePssMb(context), Math::max);
            if (power != null) {
                thermalStatusMax.accumulateAndGet(
                        power.getCurrentThermalStatus(), Math::max);
            }
        }

        ResourceObservation finish() {
            if (running.getAndSet(false)) {
                worker.interrupt();
                boolean interrupted = false;
                try {
                    worker.join(2_000L);
                } catch (InterruptedException error) {
                    interrupted = true;
                }
                observe();
                if (interrupted) Thread.currentThread().interrupt();
            }
            return new ResourceObservation(
                    peakRssMb.get(), thermalStatusMax.get());
        }

        @Override
        public void close() {
            finish();
        }
    }

    private static final class ResourceObservation {
        final int peakRssMb;
        final int thermalStatusMax;

        ResourceObservation(int peakRssMb, int thermalStatusMax) {
            this.peakRssMb = peakRssMb;
            this.thermalStatusMax = thermalStatusMax;
        }
    }

    private static int runtimePssMb(Context context) {
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        if (manager == null) return 0;
        List<ActivityManager.RunningAppProcessInfo> running =
                manager.getRunningAppProcesses();
        if (running == null) return 0;
        List<Integer> pids = new ArrayList<>();
        for (ActivityManager.RunningAppProcessInfo process : running) {
            if (process.processName.equals("com.aios.modelbroker")
                    || process.processName.startsWith("com.aios.runtime.")) {
                pids.add(process.pid);
            }
        }
        if (pids.isEmpty()) return 0;
        int[] values = pids.stream().mapToInt(Integer::intValue).toArray();
        android.os.Debug.MemoryInfo[] memory = manager.getProcessMemoryInfo(values);
        long totalKb = 0L;
        for (android.os.Debug.MemoryInfo item : memory) {
            totalKb += item.getTotalPss();
        }
        return (int) Math.min(Integer.MAX_VALUE, (totalKb + 1023L) / 1024L);
    }

    private static final class Invocation {
        final long startedAt = SystemClock.elapsedRealtime();
        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicLong firstChunkAt = new AtomicLong(0L);
        final AtomicLong inputStartedAt = new AtomicLong(0L);
        final AtomicLong firstNonFinalChunkAt = new AtomicLong(0L);
        final AtomicLong firstNonFinalSourceStartMillis = new AtomicLong(-1L);
        final AtomicLong firstNonFinalSourceEndMillis = new AtomicLong(-1L);
        final AtomicLong finalChunkAt = new AtomicLong(0L);
        final AtomicLong finalSourceEndMillis = new AtomicLong(-1L);
        volatile long completedAt = startedAt;
        volatile long sessionId = -1L;
        volatile InferenceResult result;
        volatile String error = "";
        volatile String latestChunk = "";
        volatile String finalChunkLanguage = "";

        final IModelCallback callback = new IModelCallback.Stub() {
            @Override
            public void onChunk(GenerationChunk chunk) {
                long observedAt = SystemClock.elapsedRealtime();
                firstChunkAt.compareAndSet(0L, observedAt);
                if (chunk != null && !chunk.isFinal
                        && firstNonFinalChunkAt.compareAndSet(0L, observedAt)) {
                    firstNonFinalSourceStartMillis.set(chunk.sourceStartMillis);
                    firstNonFinalSourceEndMillis.set(chunk.sourceEndMillis);
                }
                if (chunk != null && chunk.isFinal
                        && finalChunkAt.compareAndSet(0L, observedAt)) {
                    finalSourceEndMillis.set(chunk.sourceEndMillis);
                    finalChunkLanguage = chunk.language == null ? "" : chunk.language;
                }
                if (chunk != null && chunk.text != null && !chunk.text.isBlank()) {
                    latestChunk = chunk.text;
                }
            }

            @Override
            public void onCompleted(InferenceResult value) {
                result = value;
                completedAt = SystemClock.elapsedRealtime();
                completed.countDown();
            }

            @Override
            public void onError(int code, String message) {
                error = code + ":" + (message == null ? "" : message);
                completedAt = SystemClock.elapsedRealtime();
                completed.countDown();
            }
        };

        void await(IAiosModelService broker) throws Exception {
            if (!completed.await(INFERENCE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                error = "timeout";
                completedAt = SystemClock.elapsedRealtime();
                if (sessionId > 0L) broker.cancel(sessionId);
            }
        }

        void failLocal(Throwable failure) {
            if (error.isEmpty()) {
                error = failure.getClass().getSimpleName();
            }
            completedAt = SystemClock.elapsedRealtime();
            completed.countDown();
        }

        boolean succeeded() {
            return result != null && error.isEmpty();
        }

        long firstLatencyOrTimeout() {
            long value = firstChunkAt.get();
            return value > 0L ? value - startedAt : INFERENCE_TIMEOUT_MILLIS;
        }

        long elapsedOrTimeout() {
            return succeeded()
                    ? Math.max(0L, completedAt - startedAt)
                    : INFERENCE_TIMEOUT_MILLIS;
        }

        boolean sawNonFinalPartial() {
            return firstNonFinalChunkAt.get() > 0L;
        }

        boolean sawFinalEndpoint() {
            return finalChunkAt.get() > 0L;
        }

        long firstPartialProcessingLagOrTimeout() {
            return sourceRelativeLagOrTimeout(
                    firstNonFinalChunkAt.get(), firstNonFinalSourceEndMillis.get());
        }

        long finalProcessingLagOrTimeout() {
            return sourceRelativeLagOrTimeout(
                    finalChunkAt.get(), finalSourceEndMillis.get());
        }

        long finalEndpointDelayOrTimeout(long speechDurationMillis) {
            return BenchmarkMath.endpointDelayOrTimeout(
                    finalChunkAt.get(),
                    inputStartedAt.get(),
                    speechDurationMillis,
                    INFERENCE_TIMEOUT_MILLIS);
        }

        long firstPartialSourceSpanOrTimeout() {
            return BenchmarkMath.sourceSpanOrTimeout(
                    firstNonFinalSourceStartMillis.get(),
                    firstNonFinalSourceEndMillis.get(),
                    INFERENCE_TIMEOUT_MILLIS);
        }

        private long sourceRelativeLagOrTimeout(long callbackAt, long sourceEndMillis) {
            return BenchmarkMath.sourceRelativeLagOrTimeout(
                    callbackAt,
                    inputStartedAt.get(),
                    sourceEndMillis,
                    INFERENCE_TIMEOUT_MILLIS);
        }
    }

    private static final class AudioInvocation {
        final Invocation invocation;
        final byte[] pcm;
        final long firstAudioAt;

        AudioInvocation(Invocation invocation, byte[] pcm, long firstAudioAt) {
            this.invocation = invocation;
            this.pcm = pcm;
            this.firstAudioAt = firstAudioAt;
        }
    }

    private static final class TtsOutput {
        final JSONObject result;
        final byte[] englishPcm;
        final byte[] spanishPcm;

        TtsOutput(JSONObject result, byte[] englishPcm, byte[] spanishPcm) {
            this.result = result;
            this.englishPcm = englishPcm;
            this.spanishPcm = spanishPcm;
        }
    }

    private static final class Artifact {
        final String modelId;
        final String runtime;
        final String backend;
        final String sha256;

        Artifact(String modelId, String runtime, String backend, String sha256) {
            this.modelId = modelId;
            this.runtime = runtime;
            this.backend = backend;
            this.sha256 = sha256;
        }
    }

    private static final class ArtifactCatalog {
        final Map<String, Artifact> values;

        ArtifactCatalog(Map<String, Artifact> values) {
            this.values = values;
        }

        static ArtifactCatalog load(File path) throws Exception {
            if (!path.isFile() || path.length() <= 0L || path.length() > 8L * 1024 * 1024) {
                throw new IOException("model artifact manifest is absent or unbounded");
            }
            byte[] bytes;
            try (FileInputStream input = new FileInputStream(path)) {
                bytes = input.readNBytes((int) path.length() + 1);
            }
            if (bytes.length != path.length()) {
                throw new IOException("model artifact manifest changed while reading");
            }
            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (root.getInt("schema_version") != 1) {
                throw new IOException("unsupported artifact manifest schema");
            }
            Map<String, Artifact> values = new LinkedHashMap<>();
            JSONArray records = root.getJSONArray("artifacts");
            for (int index = 0; index < records.length(); index++) {
                JSONObject value = records.getJSONObject(index);
                Artifact artifact = new Artifact(
                        value.getString("model_id"),
                        value.getString("runtime"),
                        value.getString("backend"),
                        value.getString("sha256"));
                if (!artifact.sha256.matches("[0-9a-f]{64}")
                        || values.put(artifact.modelId, artifact) != null) {
                    throw new IOException("invalid or duplicate artifact identity");
                }
            }
            return new ArtifactCatalog(Map.copyOf(values));
        }

        Artifact require(String modelId) throws IOException {
            Artifact value = values.get(modelId);
            if (value == null) throw new IOException("selected artifact is absent: " + modelId);
            return value;
        }
    }

    private static final class BrokerConnection implements ServiceConnection, AutoCloseable {
        final Context context;
        final CountDownLatch connected = new CountDownLatch(1);
        IAiosModelService service;
        boolean bound;

        BrokerConnection(Context context) {
            this.context = context;
        }

        static BrokerConnection bind(Context context) throws Exception {
            BrokerConnection connection = new BrokerConnection(context);
            Intent intent = new Intent("com.aios.model.MODEL_SERVICE").setComponent(
                    new ComponentName(
                            "com.aios.modelbroker",
                            "com.aios.modelbroker.ModelBrokerService"));
            connection.bound = context.bindService(
                    intent, connection, Context.BIND_AUTO_CREATE);
            if (!connection.bound
                    || !connection.connected.await(BIND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                    || connection.service == null) {
                connection.close();
                throw new IOException("cannot bind AIOS Model Broker");
            }
            return connection;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = IAiosModelService.Stub.asInterface(binder);
            connected.countDown();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }

        @Override
        public void onBindingDied(ComponentName name) {
            service = null;
        }

        @Override
        public void onNullBinding(ComponentName name) {
            service = null;
            connected.countDown();
        }

        @Override
        public void close() {
            if (bound) {
                context.unbindService(this);
                bound = false;
            }
            service = null;
        }
    }
}
