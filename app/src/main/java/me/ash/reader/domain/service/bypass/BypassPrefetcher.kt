package me.ash.reader.domain.service.bypass

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.bypass.RuleManager
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.service.AccountService
import me.ash.reader.infrastructure.rss.ReaderCacheHelper
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BypassPrefetcher @Inject constructor(
    private val articleDao: ArticleDao,
    private val ruleManager: RuleManager,
    private val readerCacheHelper: ReaderCacheHelper,
    private val accountService: AccountService
) {

    /**
     * Prefetch specific articles that match bypass rules.
     */
    suspend fun prefetchArticles(articles: List<Article>) = withContext(Dispatchers.IO) {
        val candidates = articles.filter { article ->
            val rule = ruleManager.getRuleForUrl(article.link)
            rule != null
        }

        if (candidates.isEmpty()) return@withContext

        Timber.d("Prefetching ${candidates.size} articles via BypassPrefetcher")

        candidates.forEach { article ->
            try {
                val content = readerCacheHelper.readOrFetchFullContent(article).getOrNull()

                if (!content.isNullOrBlank()) {
                    article.fullContent = content
                    articleDao.update(article)
                    Timber.d("Bypassed and updated: ${article.title}")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to bypass article: ${article.title}")
            }
        }
    }

    /**
     * Prefetch the latest N bypassable articles from the current account.
     * Called automatically after sync completes.
     * 
     * @param limit Maximum number of articles to prefetch (default: 30)
     */
    suspend fun prefetchLatest(limit: Int = 30) = withContext(Dispatchers.IO) {
        try {
            val accountId = accountService.getCurrentAccountId()
            Timber.d("Starting auto-prefetch for account $accountId (limit=$limit)")

            // Get all recent articles for this account
            val recentArticles = articleDao.queryLatestByAccount(accountId, limit = limit * 3)

            // Filter to only bypassable articles
            val bypassable = recentArticles.filter { article ->
                ruleManager.getRuleForUrl(article.link) != null
            }.take(limit)

            if (bypassable.isEmpty()) {
                Timber.d("No bypassable articles found for prefetch")
                return@withContext
            }

            Timber.d("Auto-prefetching ${bypassable.size} bypassable articles")

            var successCount = 0
            var skipCount = 0
            
            bypassable.forEach { article ->
                try {
                    // Skip if already cached
                    val existing = readerCacheHelper.readFullContent(article.id)
                    if (existing.isSuccess) {
                        skipCount++
                        return@forEach
                    }

                    val content = readerCacheHelper.readOrFetchFullContent(article).getOrNull()
                    if (!content.isNullOrBlank()) {
                        article.fullContent = content
                        articleDao.update(article)
                        successCount++
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to prefetch: ${article.title}")
                }
            }

            Timber.d("Auto-prefetch complete: $successCount fetched, $skipCount skipped")
        } catch (e: Exception) {
            Timber.e(e, "Auto-prefetch failed")
        }
    }
}
