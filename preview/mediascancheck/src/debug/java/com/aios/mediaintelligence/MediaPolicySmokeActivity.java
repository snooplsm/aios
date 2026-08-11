package com.aios.mediaintelligence;

import android.app.Activity;
import android.app.job.JobInfo;
import android.os.Bundle;
import android.util.Log;

import java.util.List;
import java.util.Map;

/** Executes production capture, battery, and JobScheduler policy on an emulator. */
public final class MediaPolicySmokeActivity extends Activity {
    private static final String TAG = "AiosMediaPolicySmoke";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            verifyCaptureGrouping();
            verifyExecutionGates();
            verifyAndroidJobs();
            Log.i(TAG, "AIOS_MEDIA_POLICY_SMOKE_OK");
        } catch (Throwable error) {
            Log.e(TAG, "AIOS_MEDIA_POLICY_SMOKE_FAILED", error);
        } finally {
            finish();
        }
    }

    private void verifyCaptureGrouping() {
        Map<String, Integer> classes = MediaCaptureGrouping.classify(List.of(
                new MediaCaptureGrouping.Item("single", "image/jpeg", 1_000L),
                new MediaCaptureGrouping.Item("burst-one", "image/jpeg", 20_000L),
                new MediaCaptureGrouping.Item("burst-two", "image/jpeg", 25_000L),
                new MediaCaptureGrouping.Item("video", "video/mp4", 40_000L)),
                false,
                false);
        requireClass(classes, "single", MediaWorkPolicy.CLASS_IMMEDIATE);
        requireClass(classes, "burst-one", MediaWorkPolicy.CLASS_DEFERRED);
        requireClass(classes, "burst-two", MediaWorkPolicy.CLASS_DEFERRED);
        requireClass(classes, "video", MediaWorkPolicy.CLASS_DEFERRED);
    }

    private void verifyExecutionGates() {
        require(MediaWorkPolicy.executionBlockReason(
                MediaWorkPolicy.CLASS_IMMEDIATE, false, false, false, 4) == null,
                "isolated photo unexpectedly requires charging");
        require(MediaWorkPolicy.BLOCK_NOT_CHARGING.equals(
                        MediaWorkPolicy.executionBlockReason(
                                MediaWorkPolicy.CLASS_DEFERRED,
                                false,
                                false,
                                false,
                                100)),
                "deferred work ran off charger");
        require(MediaWorkPolicy.BLOCK_BELOW_BATTERY_THRESHOLD.equals(
                        MediaWorkPolicy.executionBlockReason(
                                MediaWorkPolicy.CLASS_DEFERRED,
                                false,
                                false,
                                true,
                                79)),
                "deferred work ran below 80 percent");
        require(MediaWorkPolicy.executionBlockReason(
                MediaWorkPolicy.CLASS_DEFERRED, false, false, true, 80) == null,
                "deferred work did not unlock at 80 percent while charging");
        require(MediaWorkPolicy.BLOCK_ACTIVE_CALL.equals(
                        MediaWorkPolicy.executionBlockReason(
                                MediaWorkPolicy.CLASS_IMMEDIATE,
                                true,
                                false,
                                true,
                                100)),
                "active call did not preempt immediate media");
    }

    private void verifyAndroidJobs() {
        JobInfo immediate = MediaInferenceJobService.jobInfo(
                this, MediaWorkPolicy.CLASS_IMMEDIATE, "smoke-immediate");
        JobInfo deferred = MediaInferenceJobService.jobInfo(
                this, MediaWorkPolicy.CLASS_DEFERRED, "smoke-deferred");

        require(!immediate.isRequireCharging(), "immediate job requires charging");
        require(immediate.getMinLatencyMillis() == 1_000L,
                "immediate job lost its settle latency");
        require(deferred.isRequireCharging(), "deferred job does not require charging");
        require(deferred.getMinLatencyMillis() == 0L,
                "deferred job gained an unexpected latency");
        require(immediate.getRequiredNetwork() == null
                        && deferred.getRequiredNetwork() == null,
                "media jobs unexpectedly require a network");
        require(immediate.getExtras().getInt(MediaInferenceJobService.EXTRA_WORK_CLASS, -1)
                        == MediaWorkPolicy.CLASS_IMMEDIATE,
                "immediate work class missing from JobInfo");
        require(deferred.getExtras().getInt(MediaInferenceJobService.EXTRA_WORK_CLASS, -1)
                        == MediaWorkPolicy.CLASS_DEFERRED,
                "deferred work class missing from JobInfo");
        require("smoke-immediate".equals(immediate.getExtras().getString(
                        MediaInferenceJobService.EXTRA_DELIVERY_ID))
                        && "smoke-deferred".equals(deferred.getExtras().getString(
                        MediaInferenceJobService.EXTRA_DELIVERY_ID)),
                "delivery identity missing from JobInfo");

        boolean rejected = false;
        try {
            MediaInferenceJobService.jobInfo(this, 99, "invalid");
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        require(rejected, "unknown work class was scheduled");
    }

    private static void requireClass(Map<String, Integer> values, String key, int expected) {
        require(Integer.valueOf(expected).equals(values.get(key)),
                "unexpected scheduling class for " + key);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}
