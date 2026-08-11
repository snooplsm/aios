package com.aios.runtime.smoke;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

import com.aios.model.AudioStreamFormat;
import com.aios.model.GenerationChunk;
import com.aios.model.IModelCallback;
import com.aios.model.InferenceResult;
import com.aios.model.ModelRequest;
import com.aios.runtime.IAiosRuntimeProvider;
import com.aios.runtime.RuntimeArtifact;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Real bilingual PCM smoke client for the production whisper.cpp provider. */
public final class WhisperProviderSmokeActivity extends Activity {
    private static final String TAG = "AiosWhisperProviderSmoke";
    private static final ComponentName PROVIDER = new ComponentName(
            "com.aios.runtime.whispercpp",
            "com.aios.runtime.whispercpp.WhisperRuntimeService");
    private static final int SAMPLE_RATE_HZ = 16_000;
    private static final int PCM_BYTES_PER_100_MS = SAMPLE_RATE_HZ * 2 / 10;
    private static final int TRAILING_SILENCE_BYTES = SAMPLE_RATE_HZ * 2 * 7 / 10;
    private static final long WALL_PACE_MILLIS = 250L;
    private static final int MAX_WAV_BYTES = 2 * 1024 * 1024;

    private String modelPath;
    private long modelSize;
    private String modelSha256;
    private String englishWavPath;
    private String englishWavSha256;
    private String spanishWavPath;
    private String spanishWavSha256;
    private boolean bound;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            new Thread(() -> {
                try {
                    runChecks(IAiosRuntimeProvider.Stub.asInterface(binder));
                    Log.i(TAG, "AIOS_WHISPER_REAL_ASR_OK");
                    Log.i(TAG, "AIOS_WHISPER_PROVIDER_SMOKE_OK");
                } catch (Throwable failure) {
                    Log.e(TAG, "AIOS_WHISPER_PROVIDER_SMOKE_FAILED", failure);
                } finally {
                    if (bound) {
                        unbindService(connection);
                        bound = false;
                    }
                    finish();
                }
            }, "aios-whisper-provider-smoke").start();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.e(TAG, "AIOS_WHISPER_PROVIDER_SMOKE_FAILED: provider disconnected");
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent launch = getIntent();
        modelPath = launch.getStringExtra("inference_model_path");
        modelSize = launch.getLongExtra("inference_model_size", -1L);
        modelSha256 = launch.getStringExtra("inference_model_sha256");
        englishWavPath = launch.getStringExtra("english_wav_path");
        englishWavSha256 = launch.getStringExtra("english_wav_sha256");
        spanishWavPath = launch.getStringExtra("spanish_wav_path");
        spanishWavSha256 = launch.getStringExtra("spanish_wav_sha256");
        try {
            bound = bindService(
                    new Intent("com.aios.model.RUNTIME_PROVIDER").setComponent(PROVIDER),
                    connection,
                    Context.BIND_AUTO_CREATE);
            require(bound, "could not bind production whisper.cpp provider");
        } catch (Throwable failure) {
            Log.e(TAG, "AIOS_WHISPER_PROVIDER_SMOKE_FAILED", failure);
            finish();
        }
    }

    private void runChecks(IAiosRuntimeProvider remote) throws Exception {
        require(remote != null, "provider Binder is absent");
        require(remote.getProviderApiVersion() == 2, "provider API identity changed");
        require("whisper_cpp".equals(remote.getRuntimeId()), "runtime identity changed");
        require("1.9.4".equals(remote.getImplementationVersion()),
                "implementation identity changed");
        String[] backends = remote.getSupportedBackends();
        require(backends != null && backends.length == 1 && "cpu".equals(backends[0]),
                "whisper.cpp backend allowlist changed");

        require(modelPath != null && modelPath.startsWith(
                        "/data/user/0/com.aios.runtime.whispercpp/files/emulator-models/")
                        && modelSize > 0L && validDigest(modelSha256),
                "model fixture identity is invalid");
        File english = verifiedAudio(englishWavPath, englishWavSha256);
        File spanish = verifiedAudio(spanishWavPath, spanishWavSha256);
        RuntimeArtifact model = artifact(modelPath, modelSize, modelSha256);

        TerminalCallback invalid = new TerminalCallback();
        ModelRequest invalidRequest = request("invalid-asr-request");
        invalidRequest.capability = "text_generation";
        require(remote.createSession(model, invalidRequest, invalid) == -1L,
                "provider accepted an invalid capability");
        invalid.awaitError(2, "invalid ASR runtime request");

        File outside = new File(getFilesDir(), "not-whisper-weights.bin");
        Files.write(outside.toPath(),
                "not model weights".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        RuntimeArtifact outsideArtifact = artifact(
                outside.getCanonicalPath(), outside.length(), sha256(outside));
        TerminalCallback confined = new TerminalCallback();
        long outsideSession = remote.createSession(
                outsideArtifact, request("outside-model-path"), confined);
        require(outsideSession > 0L, "path-confinement session was not created");
        submitWav(remote, outsideSession, english, confined, 2_700L);
        confined.awaitError(5, "ASR decode failed");
        require(outside.delete(), "path-confinement fixture survived execution");
        require("whisper_cpp".equals(remote.getRuntimeId()),
                "provider did not survive rejected model preparation");

        transcribe(remote, model, english, "real-asr-english", "en", "country");
        transcribe(remote, model, spanish, "real-asr-spanish", "es", "ayudar");
    }

    private static void transcribe(
            IAiosRuntimeProvider remote,
            RuntimeArtifact model,
            File wav,
            String requestId,
            String expectedLanguage,
            String requiredContentMarker) throws Exception {
        TranscriptCallback callback = new TranscriptCallback();
        ModelRequest request = request(requestId);
        long sessionId = remote.createSession(model, request, callback);
        require(sessionId > 0L, "real ASR session was not created");
        submitWav(remote, sessionId, wav, callback, Long.MAX_VALUE);
        callback.await(model, request, expectedLanguage, requiredContentMarker);
    }

    private static void submitWav(
            IAiosRuntimeProvider remote,
            long sessionId,
            File wav,
            TerminalCallback callback,
            long maxAudioMillis) throws Exception {
        byte[] pcm = readPcm16Mono16k(wav, maxAudioMillis);
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
        AudioStreamFormat format = new AudioStreamFormat();
        format.sampleRateHz = SAMPLE_RATE_HZ;
        format.channelCount = 1;
        format.pcmEncoding = 2;
        format.direction = "downlink";
        remote.submitAudio(sessionId, pipe[0], format, false);
        pipe[0].close();
        try (ParcelFileDescriptor.AutoCloseOutputStream output =
                     new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
            int offset = 0;
            while (offset < pcm.length) {
                int count = Math.min(PCM_BYTES_PER_100_MS, pcm.length - offset);
                output.write(pcm, offset, count);
                offset += count;
                SystemClock.sleep(WALL_PACE_MILLIS);
            }
            byte[] silence = new byte[PCM_BYTES_PER_100_MS];
            int remaining = TRAILING_SILENCE_BYTES;
            while (remaining > 0) {
                int count = Math.min(silence.length, remaining);
                output.write(silence, 0, count);
                remaining -= count;
                SystemClock.sleep(WALL_PACE_MILLIS);
            }
        } catch (IOException failure) {
            if (callback.terminalCount.get() == 0) throw failure;
        }
    }

    private static byte[] readPcm16Mono16k(File wav, long maxAudioMillis)
            throws IOException {
        byte[] bytes = Files.readAllBytes(wav.toPath());
        require(bytes.length >= 44 && bytes.length <= MAX_WAV_BYTES,
                "WAV fixture size is invalid");
        require(ascii(bytes, 0, 4).equals("RIFF") && ascii(bytes, 8, 4).equals("WAVE"),
                "audio fixture is not RIFF/WAVE");
        int channels = -1;
        int sampleRate = -1;
        int bitsPerSample = -1;
        int audioFormat = -1;
        int dataOffset = -1;
        int dataLength = -1;
        int offset = 12;
        while (offset + 8 <= bytes.length) {
            String type = ascii(bytes, offset, 4);
            int length = littleEndianInt(bytes, offset + 4);
            int payload = offset + 8;
            require(length >= 0 && payload + (long) length <= bytes.length,
                    "WAV chunk is truncated");
            if ("fmt ".equals(type)) {
                require(length >= 16, "WAV format chunk is too short");
                audioFormat = littleEndianShort(bytes, payload);
                channels = littleEndianShort(bytes, payload + 2);
                sampleRate = littleEndianInt(bytes, payload + 4);
                bitsPerSample = littleEndianShort(bytes, payload + 14);
            } else if ("data".equals(type)) {
                dataOffset = payload;
                dataLength = length;
            }
            offset = payload + length + (length & 1);
        }
        require(audioFormat == 1 && channels == 1 && bitsPerSample == 16
                        && sampleRate >= 8_000 && sampleRate <= 48_000
                        && dataOffset >= 0 && dataLength > 0 && (dataLength & 1) == 0,
                "WAV fixture must be mono PCM16 at a supported rate");
        int sourceSamples = dataLength / 2;
        if (maxAudioMillis != Long.MAX_VALUE) {
            sourceSamples = Math.min(sourceSamples,
                    Math.toIntExact(maxAudioMillis * sampleRate / 1_000L));
        }
        short[] source = new short[sourceSamples];
        ByteBuffer.wrap(bytes, dataOffset, sourceSamples * 2)
                .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(source);
        int outputSamples = Math.toIntExact(
                Math.max(1L, Math.round(sourceSamples * (double) SAMPLE_RATE_HZ / sampleRate)));
        ByteBuffer output = ByteBuffer.allocate(outputSamples * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int index = 0; index < outputSamples; index++) {
            double position = index * (double) sampleRate / SAMPLE_RATE_HZ;
            int left = Math.min((int) position, source.length - 1);
            int right = Math.min(left + 1, source.length - 1);
            double fraction = position - left;
            int sample = (int) Math.round(source[left] * (1.0 - fraction)
                    + source[right] * fraction);
            output.putShort((short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sample)));
        }
        return output.array();
    }

    private File verifiedAudio(String path, String digest) throws Exception {
        require(path != null && path.startsWith(
                        "/data/user/0/com.aios.modelbroker/files/asr-fixtures/")
                        && validDigest(digest),
                "audio fixture identity is invalid");
        File file = new File(path).getCanonicalFile();
        require(file.isFile() && MessageDigest.isEqual(
                        digest.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                        sha256(file).getBytes(java.nio.charset.StandardCharsets.US_ASCII)),
                "audio fixture digest changed after staging");
        return file;
    }

    private static RuntimeArtifact artifact(String path, long size, String digest) {
        RuntimeArtifact artifact = new RuntimeArtifact();
        artifact.modelId = "whisper-base-multilingual-quantized";
        artifact.modelPath = path;
        artifact.modelDigest = digest;
        artifact.sizeBytes = size;
        artifact.backend = "cpu";
        return artifact;
    }

    private static ModelRequest request(String id) {
        ModelRequest request = new ModelRequest();
        request.requestId = id;
        request.capability = "streaming_asr";
        request.workload = "call_rx";
        request.language = "und";
        request.maxOutputTokens = 0;
        request.deadlineElapsedRealtimeMillis = Long.MAX_VALUE;
        request.allowFallback = false;
        return request;
    }

    private static boolean validDigest(String digest) {
        return digest != null && digest.matches("[0-9a-f]{64}");
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private static String ascii(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static int littleEndianInt(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static int littleEndianShort(byte[] bytes, int offset) {
        return Short.toUnsignedInt(
                ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).getShort());
    }

    private static String normalized(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static class TerminalCallback extends IModelCallback.Stub {
        final AtomicInteger terminalCount = new AtomicInteger();
        final CountDownLatch terminal = new CountDownLatch(1);
        volatile int errorCode = -1;
        volatile String errorMessage;
        volatile InferenceResult result;

        @Override
        public void onChunk(GenerationChunk chunk) {
            // Specialized success callback validates chunks.
        }

        @Override
        public void onCompleted(InferenceResult value) {
            result = value;
            terminalCount.incrementAndGet();
            terminal.countDown();
        }

        @Override
        public void onError(int code, String message) {
            errorCode = code;
            errorMessage = message;
            terminalCount.incrementAndGet();
            terminal.countDown();
        }

        void awaitError(int expectedCode, String expectedMessage) throws Exception {
            require(terminal.await(30L, TimeUnit.SECONDS), "timed out waiting for ASR error");
            SystemClock.sleep(100L);
            require(terminalCount.get() == 1 && errorCode == expectedCode && result == null,
                    "ASR rejection did not terminate exactly once");
            require(errorMessage != null && errorMessage.contains(expectedMessage)
                            && errorMessage.length() <= 256
                            && !errorMessage.contains("/data/"),
                    "ASR rejection was absent, unbounded, or path-bearing");
        }
    }

    private static final class TranscriptCallback extends TerminalCallback {
        private final Set<String> languages = new HashSet<>();
        private long nextSequence;
        private long lastEndMillis = -1L;
        private int finalChunkCount;
        private final StringBuilder finalText = new StringBuilder();
        private String invalidChunk;

        @Override
        public synchronized void onChunk(GenerationChunk chunk) {
            if (chunk == null || chunk.sequence != nextSequence || chunk.text == null
                    || chunk.language == null || chunk.sourceStartMillis < 0L
                    || chunk.sourceEndMillis < chunk.sourceStartMillis
                    || chunk.sourceEndMillis < lastEndMillis) {
                invalidChunk = "ASR emitted an invalid, non-contiguous, or regressing chunk";
                return;
            }
            nextSequence++;
            lastEndMillis = chunk.sourceEndMillis;
            languages.add(chunk.language);
            if (chunk.isFinal) {
                finalChunkCount++;
                if (finalText.length() > 0) finalText.append(' ');
                finalText.append(chunk.text);
            }
        }

        void await(
                RuntimeArtifact model,
                ModelRequest request,
                String expectedLanguage,
                String requiredContentMarker) throws Exception {
            require(terminal.await(180L, TimeUnit.SECONDS),
                    "timed out waiting for real whisper.cpp transcription");
            SystemClock.sleep(100L);
            require(invalidChunk == null, invalidChunk);
            require(terminalCount.get() == 1 && errorCode == -1 && result != null,
                    "real ASR did not complete exactly once: code=" + errorCode
                            + " message=" + errorMessage);
            String normalizedFinalText = normalized(finalText.toString());
            require(finalChunkCount > 0 && !normalizedFinalText.isEmpty(),
                    "real ASR emitted no finalized transcript content: chunks="
                            + nextSequence + " finals=" + finalChunkCount
                            + " languages=" + languages + " result=" + result.outputJson);
            require(requiredContentMarker != null && !requiredContentMarker.isEmpty()
                            && normalizedFinalText.contains(requiredContentMarker),
                    "real ASR transcript did not contain the fixture content marker");
            require(languages.contains(expectedLanguage)
                            && expectedLanguage.equals(result.language),
                    "real ASR language decision did not match the fixture");
            JSONObject output = new JSONObject(result.outputJson);
            require(output.getInt("schema_version") == 1
                            && expectedLanguage.equals(output.getString("language"))
                            && output.getInt("decoded_windows") > 0
                            && request.requestId.equals(result.requestId)
                            && request.capability.equals(result.capability)
                            && model.modelId.equals(result.modelId)
                            && model.modelDigest.equals(result.modelDigest)
                            && result.elapsedMillis > 0L,
                    "real ASR terminal result lost request, language, or artifact identity");
        }
    }
}
