package com.aios.callintelligence;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/** Required AI capture taps that can fail without touching the actual call media path. */
final class TelephonyAudioCapture implements AutoCloseable {
    private static final String TAG = "AiosCallCapture";
    private static final int SAMPLE_RATE_HZ = 16_000;
    private static final int CHANNEL_MASK = AudioFormat.CHANNEL_IN_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final long FIRST_PCM_TIMEOUT_MILLIS = 2_000L;

    private final RequiredCaptureGate startup = new RequiredCaptureGate();
    private final CaptureDirection downlink;
    private final CaptureDirection uplink;

    TelephonyAudioCapture(OutputStream downlinkSink, OutputStream uplinkSink) {
        downlink = new CaptureDirection(
                RequiredCaptureGate.DOWNLINK,
                MediaRecorder.AudioSource.VOICE_DOWNLINK,
                downlinkSink,
                startup);
        uplink = new CaptureDirection(
                RequiredCaptureGate.UPLINK,
                MediaRecorder.AudioSource.VOICE_UPLINK,
                uplinkSink,
                startup);
    }

    void startRequired() throws IOException {
        downlink.start();
        uplink.start();
        String failure = startup.await(FIRST_PCM_TIMEOUT_MILLIS);
        if (failure != null) {
            close();
            throw new IOException(failure);
        }
    }

    @Override
    public void close() {
        downlink.close();
        uplink.close();
    }

    private static final class CaptureDirection implements AutoCloseable, Runnable {
        private final String name;
        private final int source;
        private final OutputStream sink;
        private final RequiredCaptureGate startup;
        private final AtomicBoolean running = new AtomicBoolean();
        private final AtomicBoolean started = new AtomicBoolean();
        private volatile boolean receivedPcm;
        private AudioRecord record;
        private Thread thread;

        CaptureDirection(
                String name,
                int source,
                OutputStream sink,
                RequiredCaptureGate startup) {
            this.name = name;
            this.source = source;
            this.sink = sink;
            this.startup = startup;
        }

        boolean start() {
            if (!started.compareAndSet(false, true)) {
                markFailure(name + "_capture_already_started");
                return false;
            }
            int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_MASK, ENCODING);
            if (minimum <= 0) {
                Log.w(TAG, name + " capture has no supported buffer size");
                markFailure(name + "_buffer_size_unavailable");
                return false;
            }
            try {
                AudioFormat format = new AudioFormat.Builder()
                        .setEncoding(ENCODING)
                        .setSampleRate(SAMPLE_RATE_HZ)
                        .setChannelMask(CHANNEL_MASK)
                        .build();
                record = new AudioRecord.Builder()
                        .setAudioSource(source)
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(minimum * 2)
                        .build();
                if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                    Log.w(TAG, name + " capture did not initialize");
                    markFailure(name + "_initialization_failed");
                    close();
                    return false;
                }
                record.startRecording();
                if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                    Log.w(TAG, name + " capture did not enter recording state");
                    markFailure(name + "_recording_start_failed");
                    close();
                    return false;
                }
                running.set(true);
                thread = new Thread(this, "aios-call-" + name);
                thread.start();
                return true;
            } catch (RuntimeException error) {
                Log.w(TAG, name + " capture unavailable", error);
                markFailure(name + "_capture_unavailable");
                close();
                return false;
            }
        }

        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            byte[] buffer = new byte[3_200]; // 100 ms of mono 16-bit PCM at 16 kHz.
            AudioRecord activeRecord = record;
            try {
                while (running.get() && activeRecord != null) {
                    int read = activeRecord.read(
                            buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                    if (read > 0) {
                        sink.write(buffer, 0, read);
                        if (!receivedPcm) {
                            receivedPcm = true;
                            startup.markReady(name);
                        }
                    } else if (read < 0) {
                        Log.w(TAG, name + " capture read failed: " + read);
                        markFailure(name + "_read_failed");
                        break;
                    }
                }
            } catch (IOException | RuntimeException error) {
                Log.w(TAG, name + " capture stopped", error);
                markFailure(name + "_stream_failed");
            } finally {
                running.set(false);
                if (!receivedPcm) markFailure(name + "_stopped_before_first_pcm");
            }
        }

        private void markFailure(String reason) {
            if (!receivedPcm) startup.markFailure(name, reason);
        }

        @Override
        public void close() {
            running.set(false);
            if (!receivedPcm) markFailure(name + "_closed_before_first_pcm");
            AudioRecord current = record;
            record = null;
            if (current != null) {
                try {
                    current.stop();
                } catch (IllegalStateException ignored) {
                    // A partially initialized capture has nothing to stop.
                }
                current.release();
            }
            Thread currentThread = thread;
            thread = null;
            if (currentThread != null && currentThread != Thread.currentThread()) {
                currentThread.interrupt();
                try {
                    currentThread.join(1_000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
