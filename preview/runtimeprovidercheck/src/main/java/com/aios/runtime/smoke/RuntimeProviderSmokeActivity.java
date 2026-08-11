package com.aios.runtime.smoke;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import com.aios.model.GenerationChunk;
import com.aios.model.IModelCallback;
import com.aios.model.InferenceResult;
import com.aios.model.ModelRequest;
import com.aios.runtime.IAiosRuntimeProvider;
import com.aios.runtime.RuntimeArtifact;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Cross-process smoke client for the production LiteRT-LM runtime provider APK. */
public final class RuntimeProviderSmokeActivity extends Activity {
    private static final String TAG = "AiosRuntimeProviderSmoke";
    private static final ComponentName PROVIDER = new ComponentName(
            "com.aios.runtime.litertlm",
            "com.aios.runtime.litertlm.LiteRtLmRuntimeService");

    private boolean bound;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            Thread worker = new Thread(() -> {
                Throwable failure = null;
                try {
                    verify(IAiosRuntimeProvider.Stub.asInterface(binder));
                } catch (Throwable error) {
                    failure = error;
                }
                Throwable result = failure;
                runOnUiThread(() -> finishFixture(result));
            }, "aios-runtime-provider-smoke");
            worker.start();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // The runner treats loss before the success marker as failure.
        }

        @Override
        public void onNullBinding(ComponentName name) {
            finishFixture(new IllegalStateException("LiteRT-LM returned a null binding"));
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = new Intent("com.aios.model.RUNTIME_PROVIDER").setComponent(PROVIDER);
        try {
            bound = bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!bound) {
                finishFixture(new IllegalStateException("could not bind LiteRT-LM provider"));
            }
        } catch (Throwable error) {
            finishFixture(error);
        }
    }

    private void verify(IAiosRuntimeProvider remote) throws Exception {
        require(remote != null, "runtime AIDL interface was unavailable");
        require(remote.getProviderApiVersion() == 2, "provider API version changed");
        require("litert_lm".equals(remote.getRuntimeId()), "runtime ID changed");
        require("0.15.0".equals(remote.getImplementationVersion()),
                "implementation version changed");
        require(Arrays.equals(new String[]{"gpu", "cpu"}, remote.getSupportedBackends()),
                "backend order or allowlist changed");

        ModelRequest request = request();
        RecordingCallback invalid = new RecordingCallback();
        long invalidId = remote.createSession(null, request, invalid);
        require(invalidId == -1L, "null artifact created a provider session");
        invalid.await();
        require(invalid.errorCode.get() == 2 && invalid.terminalCount.get() == 1,
                "invalid request did not produce one typed terminal error");

        RuntimeArtifact invalidBackend = artifact("/product/etc/aios/models/missing.litertlm");
        invalidBackend.backend = "npu";
        RecordingCallback backend = new RecordingCallback();
        long backendId = remote.createSession(invalidBackend, request(), backend);
        require(backendId == -1L, "unallowlisted backend created a provider session");
        backend.await();
        require(backend.errorCode.get() == 2 && backend.terminalCount.get() == 1,
                "backend rejection did not produce one typed terminal error");

        File outside = new File(getFilesDir(), "not-model-weights.bin");
        byte[] bytes = "AIOS runtime path fixture; not model weights."
                .getBytes(StandardCharsets.UTF_8);
        write(outside, bytes);
        RuntimeArtifact escaped = artifact(outside.getCanonicalPath());
        escaped.sizeBytes = bytes.length;
        escaped.modelDigest = sha256(outside);
        RecordingCallback confinement = new RecordingCallback();
        long sessionId = remote.createSession(escaped, request(), confinement);
        require(sessionId > 0L, "valid envelope did not reach asynchronous preparation");
        confinement.await();
        require(confinement.errorCode.get() == 5 && confinement.terminalCount.get() == 1,
                "path confinement did not produce one runtime terminal error");
        require(confinement.message != null
                        && confinement.message.contains("outside the read-only model directory")
                        && confinement.message.length() <= 256
                        && !confinement.message.contains(outside.getCanonicalPath()),
                "provider error was missing, unbounded, or exposed the caller path");
        remote.cancel(sessionId);
        require("litert_lm".equals(remote.getRuntimeId()),
                "provider process did not survive rejected model preparation");
        require(outside.delete(), "temporary runtime fixture survived execution");
    }

    private static ModelRequest request() {
        ModelRequest value = new ModelRequest();
        value.requestId = "runtime-smoke";
        value.capability = "text_generation";
        value.workload = "call_agent";
        value.language = "en";
        value.maxOutputTokens = 16;
        value.deadlineElapsedRealtimeMillis = SystemClock.elapsedRealtime() + 30_000L;
        value.allowFallback = false;
        return value;
    }

    private static RuntimeArtifact artifact(String path) {
        RuntimeArtifact value = new RuntimeArtifact();
        value.modelId = "fixture-text-model";
        value.modelPath = path;
        value.modelDigest = "0".repeat(64);
        value.sizeBytes = 1L;
        value.backend = "cpu";
        return value;
    }

    private void finishFixture(Throwable result) {
        if (bound) {
            bound = false;
            unbindService(connection);
        }
        File outside = new File(getFilesDir(), "not-model-weights.bin");
        if (outside.exists() && !outside.delete() && result == null) {
            result = new IllegalStateException("temporary runtime fixture survived cleanup");
        }
        if (result == null) {
            Log.i(TAG, "AIOS_RUNTIME_PROVIDER_SMOKE_OK");
        } else {
            Log.e(TAG, "AIOS_RUNTIME_PROVIDER_SMOKE_FAILED", result);
        }
        finish();
    }

    private static void write(File file, byte[] bytes) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(file, false)) {
            stream.write(bytes);
            stream.getFD().sync();
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream stream = new FileInputStream(file)) {
            byte[] buffer = new byte[8_192];
            int count;
            while ((count = stream.read(buffer)) >= 0) {
                if (count > 0) digest.update(buffer, 0, count);
            }
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }

    private static final class RecordingCallback extends IModelCallback.Stub {
        final AtomicInteger terminalCount = new AtomicInteger();
        final AtomicInteger chunkCount = new AtomicInteger();
        final AtomicInteger errorCode = new AtomicInteger(-1);
        final CountDownLatch terminal = new CountDownLatch(1);
        volatile String message;

        @Override
        public void onChunk(GenerationChunk chunk) {
            chunkCount.incrementAndGet();
        }

        @Override
        public void onCompleted(InferenceResult result) {
            terminalCount.incrementAndGet();
            terminal.countDown();
        }

        @Override
        public void onError(int code, String value) {
            errorCode.set(code);
            message = value;
            terminalCount.incrementAndGet();
            terminal.countDown();
        }

        void await() throws Exception {
            require(terminal.await(10L, TimeUnit.SECONDS),
                    "timed out waiting for runtime callback");
            SystemClock.sleep(100L);
            require(chunkCount.get() == 0, "rejected request emitted a model chunk");
        }
    }
}
