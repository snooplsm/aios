package com.aios.mediaintelligence;

import android.Manifest;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.PersistableBundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.telecom.TelecomManager;
import android.util.Log;

import org.json.JSONException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** Enforces runtime constraints before and throughout a Model Broker media lease. */
public final class MediaInferenceJobService extends JobService {
    private static final String TAG = "AiosMediaInference";
    private static final String EXTRA_WORK_CLASS = "work_class";
    private static final int JOB_IMMEDIATE = 0xA105;
    private static final int JOB_DEFERRED = 0xA106;
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile MediaBrokerClient activeClient;
    private volatile Thread activeThread;
    private volatile boolean stopped;

    static void schedule(Context context, int workClass) {
        boolean deferred = workClass == MediaWorkPolicy.CLASS_DEFERRED;
        PersistableBundle extras = new PersistableBundle();
        extras.putInt(EXTRA_WORK_CLASS, workClass);
        JobInfo.Builder builder = new JobInfo.Builder(
                deferred ? JOB_DEFERRED : JOB_IMMEDIATE,
                new ComponentName(context, MediaInferenceJobService.class))
                .setExtras(extras)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .setBackoffCriteria(15L * 60L * 1000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL);
        if (deferred) {
            builder.setRequiresCharging(true);
        } else {
            builder.setMinimumLatency(1_000L);
        }
        context.getSystemService(JobScheduler.class).schedule(builder.build());
    }

    @Override
    public boolean onStartJob(JobParameters parameters) {
        int workClass = parameters.getExtras().getInt(
                EXTRA_WORK_CLASS, MediaWorkPolicy.CLASS_DEFERRED);
        if (!running.compareAndSet(false, true)) {
            schedule(this, workClass);
            return false;
        }
        stopped = false;
        activeThread = new Thread(
                () -> checkAndProcess(parameters, workClass), "aios-media-job");
        activeThread.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters parameters) {
        stopped = true;
        MediaBrokerClient client = activeClient;
        if (client != null) {
            client.close();
        }
        Thread worker = activeThread;
        if (worker != null) {
            worker.interrupt();
        }
        return true;
    }

    private void checkAndProcess(JobParameters parameters, int workClass) {
        boolean reschedule = false;
        MediaJobStore store = new MediaJobStore(this);
        MediaJobStore.PendingJob job = null;
        MediaJobStore.PortableJob portableJob = null;
        long processingStartedElapsedMillis = -1L;
        long processingStartedEpochMillis = -1L;
        try {
            if (!MediaWorkPolicy.isKnownWorkClass(workClass)) {
                return;
            }
            String blocked = currentBlockReason(workClass);
            if (blocked != null) {
                reschedule = true;
                return;
            }
            portableJob = store.nextPortableMetadata(workClass);
            if (portableJob != null) {
                new MediaMetadataCommitter(this).commit(portableJob, store);
                MediaContextAssociationService.requestReconcile(this);
                portableJob = null;
                return;
            }
            job = store.claimNext(workClass);
            if (job == null) {
                return;
            }
            processingStartedElapsedMillis = SystemClock.elapsedRealtime();
            processingStartedEpochMillis = System.currentTimeMillis();
            UriState before = inspect(job);
            if (before.generation != job.generation) {
                store.markStale(job.id);
                job = null;
                return;
            }

            activeClient = new MediaBrokerClient(this);
            MediaBrokerClient.Result brokerResult = activeClient.process(
                    job, () -> currentBlockReason(workClass));
            if (brokerResult.inference == null) {
                Log.i(TAG, "media inference will retry: " + brokerResult.retryReason);
                store.markPending(job.id);
                job = null;
                reschedule = true;
                return;
            }
            VideoTranscript transcript = VideoTranscript.notApplicable();
            long totalModelRequestMillis = brokerResult.modelRequestMillis;
            long videoAudioDurationMillis = MediaTiming.UNKNOWN_MILLIS;
            long videoAudioPipelineMillis = MediaTiming.UNKNOWN_MILLIS;
            if (MediaInputPolicy.isVideo(job.mimeType)) {
                MediaBrokerClient.AudioResult audioResult = activeClient.transcribeVideoAudio(
                        job, () -> currentBlockReason(workClass));
                if (audioResult.transcript == null) {
                    Log.i(TAG, "video ASR will retry: " + audioResult.retryReason);
                    store.markPending(job.id);
                    job = null;
                    reschedule = true;
                    return;
                }
                transcript = audioResult.transcript;
                videoAudioDurationMillis = audioResult.sourceAudioMillis;
                videoAudioPipelineMillis = audioResult.modelRequestMillis;
                totalModelRequestMillis = Math.addExact(
                        totalModelRequestMillis, audioResult.modelRequestMillis);
            }
            blocked = currentBlockReason(workClass);
            if (blocked != null) {
                Log.i(TAG, "completed media inference will retry: " + blocked);
                store.markPending(job.id);
                job = null;
                reschedule = true;
                return;
            }
            MediaResult result = MediaResult.parse(brokerResult.inference.outputJson);
            if (brokerResult.inference.modelId == null
                    || brokerResult.inference.modelDigest == null
                    || !DIGEST.matcher(brokerResult.inference.modelDigest).matches()) {
                store.markFailed(job.id);
                job = null;
                return;
            }
            long generationAfter = MediaContent.generation(
                    getContentResolver(), android.net.Uri.parse(job.uri));
            if (generationAfter != job.generation) {
                store.markStale(job.id);
                job = null;
                return;
            }
            long completedAtEpochMillis = System.currentTimeMillis();
            long processingMillis = MediaTiming.elapsedDuration(
                    processingStartedElapsedMillis, SystemClock.elapsedRealtime());
            MediaTiming.Sample timing = MediaTiming.completed(
                    job.mimeType,
                    job.observedAtEpochMillis,
                    processingStartedEpochMillis,
                    completedAtEpochMillis,
                    processingMillis,
                    brokerResult.inputPreparationMillis,
                    totalModelRequestMillis,
                    videoAudioDurationMillis,
                    videoAudioPipelineMillis);
            String portableXmp = XmpProjection.build(
                    result.caption,
                    result.tags,
                    result.language,
                    brokerResult.inference.modelId,
                    brokerResult.inference.modelDigest,
                    completedAtEpochMillis,
                    result.confidence);
            store.commitResult(
                    job,
                    before.digest,
                    result.rawJson,
                    brokerResult.inference.modelId,
                    brokerResult.inference.modelDigest,
                    completedAtEpochMillis,
                    portableXmp,
                    timing,
                    transcript);
            MediaContextAssociationService.requestReconcile(this);
            Log.i(TAG, "indexed " + timing.mediaKind
                    + " observed_to_index_ms=" + timing.observedToIndexMillis
                    + " processing_ms=" + timing.processingMillis
                    + " preparation_ms=" + timing.inputPreparationMillis
                    + " model_request_ms=" + timing.modelRequestMillis);
            job = null;
        } catch (FileNotFoundException error) {
            if (job != null) {
                store.markStale(job.id);
                job = null;
            }
            if (portableJob != null) {
                store.markPortableSkipped(portableJob.id);
                portableJob = null;
            }
        } catch (VideoStoryboard.InvalidVideoException error) {
            if (job != null) {
                Log.w(TAG, "video cannot produce a bounded storyboard", error);
                store.markFailed(job.id);
                job = null;
            }
        } catch (JSONException error) {
            if (job != null) {
                store.markFailed(job.id);
                job = null;
            }
        } catch (IOException | InterruptedException | RuntimeException error) {
            if (job != null) {
                store.markPending(job.id);
                job = null;
            }
            reschedule = true;
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        } finally {
            MediaBrokerClient client = activeClient;
            activeClient = null;
            if (client != null) {
                client.close();
            }
            if (job != null) {
                store.markPending(job.id);
            }
            boolean moreImmediate = store.hasPending(MediaWorkPolicy.CLASS_IMMEDIATE)
                    || store.hasPortableMetadataPending(MediaWorkPolicy.CLASS_IMMEDIATE);
            boolean moreDeferred = store.hasPending(MediaWorkPolicy.CLASS_DEFERRED)
                    || store.hasPortableMetadataPending(MediaWorkPolicy.CLASS_DEFERRED);
            store.close();
            activeThread = null;
            running.set(false);
            if (moreImmediate) {
                schedule(this, MediaWorkPolicy.CLASS_IMMEDIATE);
            }
            if (moreDeferred) {
                schedule(this, MediaWorkPolicy.CLASS_DEFERRED);
            }
            if (!stopped) {
                jobFinished(parameters, reschedule);
            }
        }
    }

    private UriState inspect(MediaJobStore.PendingJob job) throws IOException {
        android.net.Uri uri = android.net.Uri.parse(job.uri);
        long generation = MediaContent.generation(getContentResolver(), uri);
        String digest = MediaContent.sha256(getContentResolver(), uri);
        long generationAfterDigest = MediaContent.generation(getContentResolver(), uri);
        if (generation != generationAfterDigest) {
            throw new FileNotFoundException("media changed while hashing");
        }
        return new UriState(generation, digest);
    }

    private static final class UriState {
        final long generation;
        final String digest;

        UriState(long generation, String digest) {
            this.generation = generation;
            this.digest = digest;
        }
    }

    private boolean callIsActive() {
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_PHONE_STATE unavailable; blocking background media inference");
            return true;
        }
        TelecomManager telecom = getSystemService(TelecomManager.class);
        if (telecom == null) {
            return true;
        }
        try {
            return telecom.isInCall();
        } catch (SecurityException denied) {
            Log.w(TAG, "Phone state unavailable; blocking background media inference", denied);
            return true;
        }
    }

    private boolean thermalPressureIsHigh() {
        PowerManager power = getSystemService(PowerManager.class);
        return power != null
                && power.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_SEVERE;
    }

    private String currentBlockReason(int workClass) {
        BatteryState battery = workClass == MediaWorkPolicy.CLASS_DEFERRED
                ? readBattery() : new BatteryState(false, -1);
        return MediaWorkPolicy.executionBlockReason(
                workClass,
                callIsActive(),
                thermalPressureIsHigh(),
                battery.charging,
                battery.percent);
    }

    private BatteryState readBattery() {
        Intent value = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (value == null) {
            return new BatteryState(false, -1);
        }
        int status = value.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        int level = value.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = value.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int percent = level >= 0 && scale > 0 && level <= scale
                ? (int) (((long) level * 100L) / scale) : -1;
        return new BatteryState(charging, percent);
    }

    private static final class BatteryState {
        final boolean charging;
        final int percent;

        BatteryState(boolean charging, int percent) {
            this.charging = charging;
            this.percent = percent;
        }
    }
}
