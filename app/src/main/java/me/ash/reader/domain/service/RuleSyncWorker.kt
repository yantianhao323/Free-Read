package me.ash.reader.domain.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.ash.reader.domain.model.bypass.RuleManager
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

@HiltWorker
class RuleSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val ruleManager: RuleManager,
    private val okHttpClient: OkHttpClient
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://gitflic.ru/project/magnolia1234/bpc_updates/blob/raw?file=sites_updated.json")
                .build()
            
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val json = response.body?.string()
                if (!json.isNullOrEmpty()) {
                    val file = File(applicationContext.filesDir, "sites_updated.json")
                    file.writeText(json)
                    ruleManager.loadRules()
                    Timber.d("Bypass rules successfully synced and reloaded.")
                    return@withContext Result.success()
                }
            }
            Timber.e("Failed to sync bypass rules: HTTP ${response.code}")
            Result.retry()
        } catch (e: Exception) {
            Timber.e(e, "Exception during RuleSyncWorker")
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "RuleSyncWorker"

        fun enqueuePeriodicWork(workManager: WorkManager) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<RuleSyncWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        }
    }
}
