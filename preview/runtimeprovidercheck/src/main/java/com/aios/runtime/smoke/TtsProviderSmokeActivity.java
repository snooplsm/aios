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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Real English/Spanish PCM smoke client for the production Sherpa TTS provider. */
public final class TtsProviderSmokeActivity extends Activity {
    private static final String TAG = "AiosTtsProviderSmoke";
    private static final ComponentName PROVIDER = new ComponentName(
            "com.aios.runtime.sherpatts",
            "com.aios.runtime.sherpatts.SherpaTtsRuntimeService");
    private static final int SAMPLE_RATE_HZ = 44_100;
    private static final int PCM_ENCODING_16_BIT = 2;
    private static final int MAX_PCM_BYTES = 16 * 1024 * 1024;
    private static final long WAIT_SECONDS = 240L;

    private String descriptorPath;
    private long descriptorSize;
    private String descriptorSha256;
    private boolean bound;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            new Thread(() -> {
                try {
                    verify(IAiosRuntimeProvider.Stub.asInterface(binder));
                    Log.i(TAG, "AIOS_TTS_REAL_BILINGUAL_OK");
                    Log.i(TAG, "AIOS_TTS_PROVIDER_SMOKE_OK");
                } catch (Throwable failure) {
                    Log.e(TAG, "AIOS_TTS_PROVIDER_SMOKE_FAILED", failure);
                } finally {
                    if (bound) {
                        unbindService(connection);
                        bound = false;
                    }
                    finish();
                }
            }, "aios-tts-provider-smoke").start();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.e(TAG, "AIOS_TTS_PROVIDER_SMOKE_FAILED: provider disconnected");
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent launch = getIntent();
        descriptorPath = launch.getStringExtra("tts_descriptor_path");
        descriptorSize = launch.getLongExtra("tts_descriptor_size", -1L);
        descriptorSha256 = launch.getStringExtra("tts_descriptor_sha256");
        try {
            bound = bindService(
                    new Intent("com.aios.model.RUNTIME_PROVIDER").setComponent(PROVIDER),
                    connection,
                    Context.BIND_AUTO_CREATE);
            require(bound, "could not bind production Sherpa TTS provider");
        } catch (Throwable failure) {
            Log.e(TAG, "AIOS_TTS_PROVIDER_SMOKE_FAILED", failure);
            finish();
        }
    }

    private void verify(IAiosRuntimeProvider remote) throws Exception {
        require(remote != null, "provider Binder is absent");
        require(remote.getProviderApiVersion() == 2, "provider API identity changed");
        require("sherpa_onnx_tts".equals(remote.getRuntimeId()), "runtime identity changed");
        require("1.13.4".equals(remote.getImplementationVersion()),
                "implementation identity changed");
        require(Arrays.equals(new String[]{"cpu"}, remote.getSupportedBackends()),
                "TTS backend allowlist changed");
        require(descriptorPath != null && descriptorPath.startsWith(
                        "/data/user/0/com.aios.runtime.sherpatts/files/emulator-config/models/")
                        && descriptorSize > 0L && validDigest(descriptorSha256),
                "TTS bundle descriptor identity is invalid");

        RuntimeArtifact model = artifact(descriptorPath, descriptorSize, descriptorSha256);
        TerminalCallback invalid = new TerminalCallback();
        require(remote.createSession(null, request("invalid-tts", "en"), invalid) == -1L,
                "provider accepted a null TTS artifact");
        invalid.awaitError(2, "invalid TTS runtime request");

        verifyPathConfinement(remote);
        synthesize(remote, model, "real-tts-english", "en",
                "Hello, how may I help you today?");
        synthesize(remote, model, "real-tts-spanish", "es",
                "Hola, ¿cómo puedo ayudarle hoy?");
    }

    private void verifyPathConfinement(IAiosRuntimeProvider remote) throws Exception {
        File outside = new File(getFilesDir(), "not-tts-bundle.json");
        Files.write(outside.toPath(), "{}".getBytes(StandardCharsets.UTF_8));
        RuntimeArtifact escaped = artifact(
                outside.getCanonicalPath(), outside.length(), sha256(outside));
        TerminalCallback callback = new TerminalCallback();
        long sessionId = remote.createSession(escaped, request("outside-tts-path", "en"), callback);
        require(sessionId > 0L, "path-confinement request did not reach preparation");
        PcmCollector collector = attachCollector(remote, sessionId);
        remote.submitText(sessionId, "Hello", true);
        callback.awaitError(5, "TTS synthesis failed");
        collector.awaitClosed();
        require(outside.delete(), "path-confinement fixture survived execution");
        require("sherpa_onnx_tts".equals(remote.getRuntimeId()),
                "provider did not survive rejected TTS model preparation");
    }

    private static void synthesize(
            IAiosRuntimeProvider remote,
            RuntimeArtifact model,
            String requestId,
            String language,
            String text) throws Exception {
        TerminalCallback callback = new TerminalCallback();
        ModelRequest request = request(requestId, language);
        long sessionId = remote.createSession(model, request, callback);
        require(sessionId > 0L, "real TTS session was not created");
        PcmCollector collector = attachCollector(remote, sessionId);
        remote.submitText(sessionId, text, true);
        InferenceResult result = callback.awaitSuccess(model, request);
        PcmMetrics metrics = collector.awaitPcm();
        JSONObject output = new JSONObject(result.outputJson);
        require(output.getInt("schema_version") == 1
                        && output.getInt("sample_rate_hz") == SAMPLE_RATE_HZ
                        && output.getLong("sample_count") == metrics.sampleCount
                        && output.getInt("speaker_id") == 0,
                "TTS terminal metadata diverged from generated PCM");
    }

    private static PcmCollector attachCollector(
            IAiosRuntimeProvider remote, long sessionId) throws Exception {
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
        PcmCollector collector = new PcmCollector(pipe[0]);
        collector.start();
        AudioStreamFormat format = new AudioStreamFormat();
        format.sampleRateHz = SAMPLE_RATE_HZ;
        format.channelCount = 1;
        format.pcmEncoding = PCM_ENCODING_16_BIT;
        format.direction = "synthesis";
        try {
            remote.attachAudioOutput(sessionId, pipe[1], format);
        } finally {
            pipe[1].close();
        }
        return collector;
    }

    private static ModelRequest request(String requestId, String language) {
        ModelRequest request = new ModelRequest();
        request.requestId = requestId;
        request.capability = "speech_synthesis";
        request.workload = "call_agent";
        request.language = language;
        request.maxOutputTokens = 0;
        request.deadlineElapsedRealtimeMillis = SystemClock.elapsedRealtime() + 240_000L;
        request.allowFallback = false;
        return request;
    }

    private static RuntimeArtifact artifact(String path, long size, String digest) {
        RuntimeArtifact artifact = new RuntimeArtifact();
        artifact.modelId = "supertonic3-en-es-int8";
        artifact.modelPath = path;
        artifact.modelDigest = digest;
        artifact.sizeBytes = size;
        artifact.backend = "cpu";
        return artifact;
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

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static final class PcmMetrics {
        final long sampleCount;

        PcmMetrics(long sampleCount) {
            this.sampleCount = sampleCount;
        }
    }

    private static final class PcmCollector {
        private final ParcelFileDescriptor source;
        private final CountDownLatch closed = new CountDownLatch(1);
        private volatile Throwable failure;
        private volatile byte[] pcm;

        PcmCollector(ParcelFileDescriptor source) {
            this.source = source;
        }

        void start() {
            new Thread(() -> {
                try (ParcelFileDescriptor.AutoCloseInputStream input =
                             new ParcelFileDescriptor.AutoCloseInputStream(source);
                     ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8_192];
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        if (read == 0) continue;
                        require(output.size() + read <= MAX_PCM_BYTES,
                                "TTS emitted unbounded PCM");
                        output.write(buffer, 0, read);
                    }
                    pcm = output.toByteArray();
                } catch (Throwable error) {
                    failure = error;
                } finally {
                    closed.countDown();
                }
            }, "aios-tts-pcm-drain").start();
        }

        void awaitClosed() throws Exception {
            require(closed.await(WAIT_SECONDS, TimeUnit.SECONDS),
                    "timed out draining rejected TTS output");
        }

        PcmMetrics awaitPcm() throws Exception {
            require(closed.await(WAIT_SECONDS, TimeUnit.SECONDS),
                    "timed out draining real TTS PCM");
            require(failure == null, "TTS PCM drain failed");
            byte[] bytes = pcm;
            require(bytes != null && bytes.length >= SAMPLE_RATE_HZ / 2
                            && (bytes.length & 1) == 0,
                    "TTS emitted absent, short, or misaligned PCM");
            int peak = 0;
            int nonzero = 0;
            for (int offset = 0; offset < bytes.length; offset += 2) {
                int sample = (short) ((bytes[offset] & 0xff) | (bytes[offset + 1] << 8));
                int magnitude = Math.abs(sample == Short.MIN_VALUE ? Short.MAX_VALUE : sample);
                peak = Math.max(peak, magnitude);
                if (magnitude > 32) nonzero++;
            }
            require(peak >= 128 && nonzero >= SAMPLE_RATE_HZ / 20,
                    "TTS PCM was effectively silent");
            return new PcmMetrics(bytes.length / 2L);
        }
    }

    private static final class TerminalCallback extends IModelCallback.Stub {
        private final CountDownLatch terminal = new CountDownLatch(1);
        private final AtomicInteger terminalCount = new AtomicInteger();
        private final AtomicInteger chunkCount = new AtomicInteger();
        private volatile int errorCode = -1;
        private volatile String errorMessage;
        private volatile InferenceResult result;

        @Override
        public void onChunk(GenerationChunk chunk) {
            chunkCount.incrementAndGet();
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
            require(terminal.await(WAIT_SECONDS, TimeUnit.SECONDS),
                    "timed out waiting for TTS rejection");
            SystemClock.sleep(100L);
            require(terminalCount.get() == 1 && chunkCount.get() == 0
                            && errorCode == expectedCode && result == null,
                    "TTS rejection did not terminate exactly once");
            require(errorMessage != null && errorMessage.contains(expectedMessage)
                            && errorMessage.length() <= 256
                            && !errorMessage.contains("/data/"),
                    "TTS rejection was absent, unbounded, or path-bearing");
        }

        InferenceResult awaitSuccess(
                RuntimeArtifact model, ModelRequest request) throws Exception {
            require(terminal.await(WAIT_SECONDS, TimeUnit.SECONDS),
                    "timed out waiting for real TTS synthesis");
            SystemClock.sleep(100L);
            require(terminalCount.get() == 1 && chunkCount.get() == 0
                            && errorCode == -1 && result != null,
                    "real TTS did not complete exactly once: code=" + errorCode
                            + " message=" + errorMessage);
            require(request.requestId.equals(result.requestId)
                            && request.capability.equals(result.capability)
                            && model.modelId.equals(result.modelId)
                            && model.modelDigest.equals(result.modelDigest)
                            && request.language.equals(result.language)
                            && result.elapsedMillis > 0L,
                    "TTS result lost request, model, language, or timing identity");
            return result;
        }
    }
}
