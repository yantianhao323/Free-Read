package me.ash.reader.domain.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.infrastructure.rss.ReaderCacheHelper
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.util.Date

import me.ash.reader.domain.repository.AccountDao

@HiltWorker
class CleanupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val articleDao: ArticleDao,
    private val accountDao: AccountDao
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val days = 7
            val cutoffTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong())
            val cutoffDate = Date(cutoffTime)

            val accounts = accountDao.queryAll()
            
            accounts.forEach { account ->
                // Query articles older than cutoff
                val oldArticles = articleDao.queryMetadataAll(account.id!!, cutoffDate)
                
                // Filter unstarred
                val toClean = oldArticles.filter { !it.isStarred }
                
                if (toClean.isNotEmpty()) {
                    Timber.d("Cleaning up ${toClean.size} articles for account ${account.id}")
                    
                    toClean.forEach { meta ->
                        // 1. Delete file cache manually
                        val cacheDir = applicationContext.cacheDir.resolve("readability/${account.id}")
                        if (cacheDir.exists()) {
                             val md = java.security.MessageDigest.getInstance("SHA-256")
                             val bytes = meta.id.toByteArray()
                             val digest = md.digest(bytes)
                             @OptIn(ExperimentalStdlibApi::class)
                             val hash = digest.toHexString()
                             val file = cacheDir.resolve("$hash.html")
                             if (file.exists()) {
                                 file.delete()
                             }
                        }

                        // 2. Clear DB content
                        val articleWithFeed = articleDao.queryById(meta.id)
                        if (articleWithFeed != null) {
                            val article = articleWithFeed.article
                            if (article.fullContent != null) {
                                article.fullContent = null
                                articleDao.update(article)
                            }
                        }
                    }
                }
            }
            
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "Cleanup failed")
            Result.failure()
        }
    }
    
    companion object {
        fun enqueuePeriodic(workManager: WorkManager) {
             val request = PeriodicWorkRequestBuilder<CleanupWorker>(24, TimeUnit.HOURS)
                .build()
            workManager.enqueueUniquePeriodicWork("CleanupWorker", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
