package com.aios.callintelligence;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/** Best-effort taps that can fail without touching the actual call media path. */
final class TelephonyAudioCapture implements AutoCloseable {
    private static final String TAG = "AiosCallCapture";
    private static final int SAMPLE_RATE_HZ = 16_000;
    private static final int CHANNEL_MASK = AudioFormat.CHANNEL_IN_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    private final CaptureDirection downlink;
    private final CaptureDirection uplink;

    TelephonyAudioCapture(OutputStream downlinkSink, OutputStream uplinkSink) {
        downlink = new CaptureDirection(
                "downlink", MediaRecorder.AudioSource.VOICE_DOWNLINK, downlinkSink);
        uplink = new CaptureDirection(
                "uplink", MediaRecorder.AudioSource.VOICE_UPLINK, uplinkSink);
    }

    void start() {
        downlink.start();
        uplink.start();
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
        private final AtomicBoolean running = new AtomicBoolean();
        private AudioRecord record;
        private Thread thread;

        CaptureDirection(String name, int source, OutputStream sink) {
            this.name = name;
            this.source = source;
            this.sink = sink;
        }

        void start() {
            int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_MASK, ENCODING);
            if (minimum <= 0) {
                Log.w(TAG, name + " capture has no supported buffer size");
                return;
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
                    close();
                    return;
                }
                record.startRecording();
                running.set(true);
                thread = new Thread(this, "aios-call-" + name);
                thread.start();
            } catch (RuntimeException error) {
                Log.w(TAG, name + " capture unavailable", error);
                close();
            }
        }

        @Override
        public void run() {
            byte[] buffer = new byte[3_200]; // 100 ms of mono 16-bit PCM at 16 kHz.
            AudioRecord activeRecord = record;
            try {
                while (running.get() && activeRecord != null) {
                    int read = activeRecord.read(
                            buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                    if (read > 0) {
                        sink.write(buffer, 0, read);
                    } else if (read < 0) {
                        Log.w(TAG, name + " capture read failed: " + read);
                        break;
                    }
                }
            } catch (IOException | RuntimeException error) {
                Log.w(TAG, name + " capture stopped", error);
            } finally {
                running.set(false);
            }
        }

        @Override
        public void close() {
            running.set(false);
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
