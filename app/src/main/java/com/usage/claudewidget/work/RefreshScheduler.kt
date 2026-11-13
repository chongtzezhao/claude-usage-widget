package com.usage.claudewidget.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.usage.claudewidget.data.Const
import com.usage.claudewidget.data.Provider
import java.util.concurrent.TimeUnit

object RefreshScheduler {

    private val networkConstraint =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Periodic 15-minute refresh per provider; survives reboot. Idempotent (KEEP). */
    fun ensurePeriodic(context: Context, provider: Provider = Provider.CLAUDE) {
        val work = PeriodicWorkRequestBuilder<RefreshWorker>(
            Const.REFRESH_INTERVAL_MIN, TimeUnit.MINUTES
        )
            .setConstraints(networkConstraint)
            .setInputData(workDataOf(RefreshWorker.KEY_PROVIDER to provider.name))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            provider.workName, ExistingPeriodicWorkPolicy.KEEP, work
        )
    }

    /** Manual tap-to-refresh: run once now, ahead of the periodic schedule. */
    fun refreshNow(context: Context, provider: Provider = Provider.CLAUDE) {
        val work = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setConstraints(networkConstraint)
            .setInputData(workDataOf(RefreshWorker.KEY_PROVIDER to provider.name))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            provider.workNameNow, ExistingWorkPolicy.REPLACE, work
        )
    }

    /** Stops the provider's periodic and one-shot refresh work (sign-out). */
    fun cancel(context: Context, provider: Provider = Provider.CLAUDE) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(provider.workName)
        wm.cancelUniqueWork(provider.workNameNow)
    }
}
