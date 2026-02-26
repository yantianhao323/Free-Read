package me.ash.reader.domain.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.ash.reader.domain.service.bypass.BypassPrefetcher
import me.ash.reader.infrastructure.rss.ReaderCacheHelper
import timber.log.Timber

@HiltWorker
class ReaderWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val rssService: RssService,
    private val cacheHelper: ReaderCacheHelper,
    private val bypassPrefetcher: BypassPrefetcher,
    private val ruleUpdateService: RuleUpdateService,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val semaphore = Semaphore(2)

        val deferredList =
            withContext(Dispatchers.IO) {
                val rssService = rssService.get()
                val articleList = rssService.queryUnreadFullContentArticles()
                articleList.map {
                    async { semaphore.withPermit { cacheHelper.checkOrFetchFullContent(it) } }
                }
            }

        val fetchResult = deferredList.awaitAll().all { it }

        // Check for bypass rule updates (silently fails if repo unavailable)
        try {
            ruleUpdateService.checkAndUpdate()
        } catch (e: Exception) {
            Timber.w(e, "Rule update check failed, continuing")
        }

        // Auto-prefetch latest 30 bypassable articles
        try {
            bypassPrefetcher.prefetchLatest(30)
        } catch (e: Exception) {
            Timber.w(e, "Bypass prefetch failed, continuing")
        }

        return if (fetchResult) Result.success() else Result.retry()
    }
}

