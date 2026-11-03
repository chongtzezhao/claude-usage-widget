package com.usage.claudewidget.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.usage.claudewidget.data.CodexUsageRepository
import com.usage.claudewidget.data.FetchResult
import com.usage.claudewidget.data.Provider
import com.usage.claudewidget.data.UsageRepository
import com.usage.claudewidget.widget.CodexWidget
import com.usage.claudewidget.widget.UsageWidget

/** Fetches one provider's usage in the background and pushes it into that widget. */
class RefreshWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val provider = Provider.fromName(inputData.getString(KEY_PROVIDER))
        val result = when (provider) {
            Provider.CLAUDE -> UsageRepository(applicationContext).refresh()
            Provider.CODEX -> CodexUsageRepository(applicationContext).refresh()
        }

        // Snapshot + authState are already persisted by the repository; just re-render.
        when (provider) {
            Provider.CLAUDE -> UsageWidget.updateAll(applicationContext)
            Provider.CODEX -> CodexWidget.updateAll(applicationContext)
        }

        return when (result) {
            is FetchResult.Success, is FetchResult.CodexSuccess,
            is FetchResult.NeedsLogin -> Result.success()
            is FetchResult.Soft -> Result.retry() // transient; let WorkManager back off
        }
    }

    companion object {
        const val KEY_PROVIDER = "provider"
    }
}
