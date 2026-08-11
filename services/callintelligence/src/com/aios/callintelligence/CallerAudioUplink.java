package com.aios.callintelligence;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Routes synthesized PCM only to Android's explicit telephony-TX device.
 *
 * A successful static probe is deliberately not treated as proof that a remote
 * caller heard audio. Release enablement additionally requires physical
 * carrier-call evidence and the immutable product validation property.
 */
final class CallerAudioUplink implements AutoCloseable {
    private static final String TAG = "AiosCallerUplink";
    private static final int OUTPUT_SAMPLE_RATE_HZ = 48_000;
    private static final int OUTPUT_CHANNEL_MASK = AudioFormat.CHANNEL_OUT_STEREO;
    private static final int PCM_ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int INPUT_BUFFER_BYTES = 4_096;
    private static final int ROUTE_CHECK_BYTES = 4_800 * 4; // 100 ms at 48 kHz stereo PCM16.
    private static final int OUTPUT_BYTES_PER_FRAME = 4;
    private static final long PLAYBACK_DRAIN_GRACE_MILLIS = 2_000L;

    interface Listener {
        void onStatus(String callId, Stream stream, String detail);
    }

    static final class Probe {
        final boolean available;
        final String reason;
        final AudioDeviceInfo device;
        final int minimumBufferBytes;

        Probe(boolean available, String reason, AudioDeviceInfo device, int minimumBufferBytes) {
            this.available = available;
            this.reason = reason;
            this.device = device;
            this.minimumBufferBytes = minimumBufferBytes;
        }
    }

    private final Context context;
    private final AudioManager audioManager;
    private final Map<String, Stream> active = new HashMap<>();
    private boolean closed;

    CallerAudioUplink(Context context) {
        this.context = context;
        audioManager = context.getSystemService(AudioManager.class);
    }

    Probe probe() {
        if (context.checkSelfPermission(Manifest.permission.MODIFY_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            return new Probe(false, "modify_phone_state_not_granted", null, 0);
        }
        if (audioManager == null) {
            return new Probe(false, "audio_manager_unavailable", null, 0);
        }
        AudioDeviceInfo telephony = null;
        for (AudioDeviceInfo candidate
                : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (candidate.isSink() && candidate.getType() == AudioDeviceInfo.TYPE_TELEPHONY) {
                telephony = candidate;
                break;
            }
        }
        if (telephony == null) {
            return new Probe(false, "telephony_tx_device_absent", null, 0);
        }
        int minimum = AudioTrack.getMinBufferSize(
                OUTPUT_SAMPLE_RATE_HZ, OUTPUT_CHANNEL_MASK, PCM_ENCODING);
        if (minimum <= 0) {
            return new Probe(false, "telephony_tx_format_unavailable", telephony, 0);
        }
        return new Probe(true, "route_requires_active_call_verification", telephony, minimum);
    }

    synchronized Stream open(
            String callId,
            ParcelFileDescriptor synthesizedPcm,
            int inputSampleRateHz,
            Listener listener) throws IOException {
        if (closed || callId == null || callId.isEmpty() || synthesizedPcm == null
                || listener == null
                || !Pcm16MonoToStereo48k.isSupportedInputRate(inputSampleRateHz)) {
            closeDescriptor(synthesizedPcm);
            throw new IOException("invalid or unavailable caller-audio stream");
        }
        if (active.containsKey(callId)) {
            closeDescriptor(synthesizedPcm);
            throw new IOException("caller-audio stream already active");
        }
        Probe probe = probe();
        if (!probe.available) {
            closeDescriptor(synthesizedPcm);
            throw new IOException(probe.reason);
        }

        AudioFormat outputFormat = new AudioFormat.Builder()
                .setEncoding(PCM_ENCODING)
                .setSampleRate(OUTPUT_SAMPLE_RATE_HZ)
                .setChannelMask(OUTPUT_CHANNEL_MASK)
                .build();
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        AudioTrack track;
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(outputFormat)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(Math.max(probe.minimumBufferBytes * 2,
                            ROUTE_CHECK_BYTES))
                    .build();
        } catch (RuntimeException error) {
            closeDescriptor(synthesizedPcm);
            throw new IOException("telephony TX AudioTrack creation failed", error);
        }
        if (track.getState() != AudioTrack.STATE_INITIALIZED
                || !track.setPreferredDevice(probe.device)) {
            track.release();
            closeDescriptor(synthesizedPcm);
            throw new IOException("telephony TX route request was rejected");
        }
        Stream stream = new Stream(
                callId, synthesizedPcm, inputSampleRateHz, track, listener);
        active.put(callId, stream);
        return stream;
    }

    synchronized boolean staticRouteAvailable() {
        return !closed && probe().available;
    }

    @Override
    public void close() {
        ArrayList<Stream> snapshot;
        synchronized (this) {
            if (closed) return;
            closed = true;
            snapshot = new ArrayList<>(active.values());
            active.clear();
        }
        for (Stream stream : snapshot) stream.close();
    }

    private synchronized void finished(Stream stream) {
        if (active.get(stream.callId) == stream) active.remove(stream.callId);
    }

    final class Stream implements AutoCloseable, Runnable {
        private final String callId;
        private final ParcelFileDescriptor input;
        private final Pcm16MonoToStereo48k converter;
        private final AudioTrack track;
        private final Listener listener;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final AtomicBoolean started = new AtomicBoolean(false);
        private final AtomicBoolean trackReleased = new AtomicBoolean(false);
        private final Thread thread;

        Stream(
                String callId,
                ParcelFileDescriptor input,
                int inputSampleRateHz,
                AudioTrack track,
                Listener listener) {
            this.callId = callId;
            this.input = input;
            converter = new Pcm16MonoToStereo48k(inputSampleRateHz);
            this.track = track;
            this.listener = listener;
            thread = new Thread(this, "aios-caller-uplink");
        }

        void start() {
            if (!running.get() || !started.compareAndSet(false, true)) {
                throw new IllegalStateException("caller-audio stream cannot be started");
            }
            thread.start();
        }

        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            int writtenBytes = 0;
            boolean routeVerified = false;
            String terminalStatus = null;
            byte[] inputBuffer = new byte[INPUT_BUFFER_BYTES];
            byte[] outputBuffer = new byte[converter.maximumOutputBytes(INPUT_BUFFER_BYTES)];
            try (InputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(input)) {
                track.play();
                while (running.get()) {
                    int count = stream.read(inputBuffer);
                    if (count < 0) break;
                    if (count == 0) continue;
                    int converted = converter.convert(
                            inputBuffer, 0, count, outputBuffer);
                    writeFully(outputBuffer, converted);
                    writtenBytes += converted;
                    if (!routeVerified && writtenBytes >= ROUTE_CHECK_BYTES) {
                        requireTelephonyRoute();
                        routeVerified = true;
                        notifyStatus("caller_audio_route_verified");
                    }
                }
                if (running.get()) {
                    int tail = converter.finish(outputBuffer);
                    writeFully(outputBuffer, tail);
                    writtenBytes += tail;
                    if (writtenBytes <= 0) {
                        throw new IOException("synthesis produced no PCM");
                    }
                    if (!routeVerified) {
                        requireTelephonyRoute();
                        notifyStatus("caller_audio_route_verified");
                    }
                    awaitPlayback(writtenBytes / OUTPUT_BYTES_PER_FRAME);
                    terminalStatus = "caller_audio_complete";
                }
            } catch (IOException | RuntimeException error) {
                if (running.get()) {
                    Log.w(TAG, "caller uplink stopped", error);
                    terminalStatus = "caller_audio_failed";
                }
            } finally {
                running.set(false);
                releaseTrack();
                finished(this);
                if (terminalStatus != null) notifyStatus(terminalStatus);
            }
        }

        @Override
        public void close() {
            if (!running.getAndSet(false)) return;
            closeDescriptor(input);
            try {
                track.pause();
                track.flush();
            } catch (IllegalStateException ignored) {
                // The route may have failed before playback started.
            }
            thread.interrupt();
            if (started.get() && thread != Thread.currentThread()) {
                try {
                    thread.join(1_000L);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }
            releaseTrack();
            finished(this);
        }

        private void writeFully(byte[] buffer, int byteCount) throws IOException {
            int offset = 0;
            while (running.get() && offset < byteCount) {
                int written = track.write(
                        buffer, offset, byteCount - offset, AudioTrack.WRITE_BLOCKING);
                if (written <= 0) {
                    throw new IOException("telephony TX write failed: " + written);
                }
                offset += written;
            }
        }

        private void requireTelephonyRoute() throws IOException {
            AudioDeviceInfo routed = track.getRoutedDevice();
            if (routed == null || routed.getType() != AudioDeviceInfo.TYPE_TELEPHONY) {
                throw new IOException("AudioTrack did not route to telephony TX");
            }
        }

        private void awaitPlayback(int expectedFrames) throws IOException {
            long durationMillis = expectedFrames * 1_000L / OUTPUT_SAMPLE_RATE_HZ;
            long deadline = SystemClock.elapsedRealtime()
                    + durationMillis + PLAYBACK_DRAIN_GRACE_MILLIS;
            while (running.get()) {
                requireTelephonyRoute();
                long playedFrames = Integer.toUnsignedLong(track.getPlaybackHeadPosition());
                if (playedFrames >= expectedFrames) return;
                if (SystemClock.elapsedRealtime() >= deadline) {
                    throw new IOException("telephony TX playback did not drain");
                }
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException("telephony TX playback was interrupted", error);
                }
            }
            throw new IOException("telephony TX playback was cancelled");
        }

        private void releaseTrack() {
            if (!trackReleased.compareAndSet(false, true)) return;
            try {
                if (track.getPlayState() != AudioTrack.PLAYSTATE_STOPPED) track.stop();
            } catch (IllegalStateException ignored) {
                // Already torn down after a route failure.
            }
            track.release();
        }

        private void notifyStatus(String detail) {
            try {
                listener.onStatus(callId, this, detail);
            } catch (RuntimeException error) {
                Log.w(TAG, "caller-audio listener failed", error);
            }
        }
    }

    static void closeDescriptor(ParcelFileDescriptor descriptor) {
        if (descriptor == null) return;
        try {
            descriptor.close();
        } catch (IOException ignored) {
            // Best effort during fail-closed teardown.
        }
    }
}
