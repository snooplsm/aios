package com.aios.messaging.context

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.aios.messaging.MessagingRuntime

/** Keeps restart/role-change reconciliation alive until one durable pass finishes. */
class MessageContextReconcileJobService : JobService() {
    private var activeParameters: JobParameters? = null

    override fun onStartJob(parameters: JobParameters): Boolean {
        activeParameters = parameters
        MessagingRuntime.refreshRole()
        MessagingRuntime.reconcileMessageContext { result ->
            if (activeParameters === parameters) {
                activeParameters = null
                jobFinished(parameters, result.isFailure)
            }
        }
        return true
    }

    override fun onStopJob(parameters: JobParameters): Boolean {
        if (activeParameters === parameters) activeParameters = null
        return true
    }

    companion object {
        private const val JOB_ID = 0xA107

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val job = JobInfo.Builder(
                JOB_ID,
                ComponentName(context, MessageContextReconcileJobService::class.java),
            )
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .setPersisted(true)
                .setBackoffCriteria(15_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build()
            scheduler.schedule(job)
        }
    }
}
