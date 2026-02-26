package me.ash.reader.infrastructure.rss

import android.content.Context
import android.util.Log
import com.rometools.modules.mediarss.MediaEntryModule
import com.rometools.modules.mediarss.MediaModule
import com.rometools.modules.mediarss.types.UrlReference
import com.rometools.rome.feed.synd.SyndEntry
import com.rometools.rome.feed.synd.SyndFeed
import com.rometools.rome.feed.synd.SyndImageImpl
import com.rometools.rome.io.SyndFeedInput
import com.rometools.rome.io.XmlReader
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.charset.Charset
import java.util.*
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.html.Readability
import me.ash.reader.ui.ext.currentAccountId
import me.ash.reader.ui.ext.decodeHTML
import me.ash.reader.ui.ext.extractDomain
import me.ash.reader.ui.ext.isFuture
import me.ash.reader.ui.ext.spacerDollar
import me.ash.reader.domain.model.bypass.RuleManager
import me.ash.reader.infrastructure.net.BypassPurifier
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.executeAsync
import okhttp3.internal.commonIsSuccessful
import okio.IOException
import org.jsoup.Jsoup
import me.ash.reader.infrastructure.net.HeadlessWebViewManager
val enclosureRegex = """<enclosure\s+url="([^"]+)"\s+type=".*"\s*/>""".toRegex()
val imgRegex = """img.*?src=(["'])((?!data).*?)\1""".toRegex(RegexOption.DOT_MATCHES_ALL)

/** Some operations on RSS. */
class RssHelper
@Inject
constructor(
    @ApplicationContext private val context: Context,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    private val okHttpClient: OkHttpClient,
    private val ruleManager: RuleManager,
    private val headlessWebViewManager: HeadlessWebViewManager,
) {

    @Throws(Exception::class)
    suspend fun searchFeed(feedLink: String): SyndFeed {
        return withContext(ioDispatcher) {
            val response = response(okHttpClient, feedLink)
            val contentType = response.header("Content-Type")
            val httpContentType =
                contentType?.let {
                    if (it.contains("charset=", ignoreCase = true)) it
                    else "$it; charset=UTF-8"
                } ?: "text/xml; charset=UTF-8"


            response.body.byteStream().use { inputStream ->
                    SyndFeedInput().build(XmlReader(inputStream, httpContentType)).also {
                    it.icon = SyndImageImpl()
                    it.icon.link = queryRssIconLink(feedLink)
                    it.icon.url = it.icon.link
                }
            }
        }
    }

    @Throws(Exception::class)
    suspend fun parseFullContent(link: String, title: String): String {
        return withContext(ioDispatcher) {
            // ============================================================
            // STEP 0: Resolve redirects to get the REAL article URL.
            // Google News RSS returns opaque URLs like:
            //   https://news.google.com/rss/articles/CBMi...
            // We MUST resolve these to the real article domain (nytimes.com, etc.)
            // so that bypass rules can match.
            // ============================================================
            var realLink = link
            if (link.contains("news.google.com") || link.contains("google.com/rss/")) {
                timber.log.Timber.d("Resolving Google News redirect via WebView: $link")
                try {
                    val resolved = headlessWebViewManager.resolveRedirectUrl(link, timeoutMs = 10000L)
                    if (resolved != null && !resolved.contains("google.com")) {
                        realLink = resolved
                        timber.log.Timber.d("Resolved Google News URL to: $realLink")
                    } else {
                        timber.log.Timber.w("Failed to resolve Google News URL, using original: $link")
                    }
                } catch (e: Exception) {
                    timber.log.Timber.w(e, "Error resolving Google News URL")
                }
            }

            // STEP 1: Check for bypass rule using the REAL article URL
            val rule = ruleManager.getRuleForUrl(realLink)
            timber.log.Timber.d("parseFullContent: realLink=$realLink, rule=${if (rule != null) "FOUND (domain=${rule.domain})" else "NONE"}")

            // ============================================================
            // STEP 2a: For known hard-paywall sites that block ALL HTTP
            // clients (even Googlebot), skip OkHttp and go straight to
            // WebView with script blocking.
            // Bloomberg: OkHttp→403, needs JS rendering + script blocking
            // WSJ: OkHttp→401, needs Drudge referer in real browser context
            // ============================================================
            val webViewFirstDomains = setOf("bloomberg.com", "wsj.com")
            val isWebViewFirst = rule != null && webViewFirstDomains.any { realLink.contains(it) }

            if (isWebViewFirst) {
                timber.log.Timber.d("WebView-first site detected: $realLink")
                try {
                    val webViewHtml = headlessWebViewManager.fetchHtml(realLink, rule, timeoutMs = 20000L)
                    if (webViewHtml != null) {
                        val wvClean = BypassPurifier.cleanHtml(webViewHtml, realLink, rule)
                        val wvArticle = Readability.parseToElement(wvClean, realLink)
                        if (wvArticle != null) {
                            val h1 = wvArticle.selectFirst("h1")
                            if (h1 != null && h1.hasText() && h1.text() == title) h1.remove()
                            val wvText = wvArticle.text()
                            if (wvText.length > 200) {
                                timber.log.Timber.d("WebView-first extracted ${wvText.length} chars for $realLink")
                                return@withContext wvArticle.toString()
                            }
                        }
                    }
                } catch (e: Exception) {
                    timber.log.Timber.w(e, "WebView-first failed for $realLink")
                }

                // Try archive.is as fallback for WebView-first sites
                try {
                    val archiveContent = BypassPurifier.fetchFromArchiveIs(realLink, okHttpClient)
                    if (archiveContent != null) {
                        val archiveArticle = Readability.parseToElement(archiveContent, realLink)
                        if (archiveArticle != null && archiveArticle.text().length > 200) {
                            return@withContext archiveArticle.toString()
                        }
                    }
                } catch (e: Exception) {
                    timber.log.Timber.w(e, "archive.is fallback failed for $realLink")
                }
            }

            // ============================================================
            // STEP 2b: Standard OkHttp path
            // BypassInterceptor auto-applies UA/Referer/Cookies per rule:
            //   NYT → Googlebot UA + X-Forwarded-For
            //   Economist → Lamarr custom UA
            //   Forbes → allow_cookies
            //   All others → per sites.json rule
            // ============================================================
            var articleContent: org.jsoup.nodes.Element? = null
            var okHttpWorked = false

            try {
                val response = response(okHttpClient, realLink)
                if (response.commonIsSuccessful) {
                    okHttpWorked = true
                    val responseBody = response.body
                    val charset = responseBody.contentType()?.charset()
                    val content =
                        responseBody.source().use {
                            if (charset != null) {
                                return@use it.readString(charset)
                            }

                            val peekContent = it.peek().readString(Charsets.UTF_8)

                            val charsetFromMeta =
                                runCatching {
                                        val element =
                                            Jsoup.parse(peekContent, realLink)
                                                .selectFirst("meta[http-equiv=content-type]")
                                        return@runCatching if (element == null) Charsets.UTF_8
                                        else {
                                            element
                                                .attr("content")
                                                .substringAfter("charset=")
                                                .removeSurrounding("\"")
                                                .lowercase()
                                                .let { Charset.forName(it) }
                                        }
                                    }
                                    .getOrDefault(Charsets.UTF_8)

                            if (charsetFromMeta == Charsets.UTF_8) {
                                peekContent
                            } else {
                                it.readString(charsetFromMeta)
                            }
                        }

                    // Try BypassPurifier (ld_json extraction, block_regex etc.)
                    val bypassedContent = BypassPurifier.purify(content, realLink, rule, okHttpClient)
                    if (bypassedContent != null) {
                        timber.log.Timber.d("BypassPurifier extracted content for $realLink")
                        return@withContext bypassedContent
                    }

                    // Clean HTML with bypass rules (strip paywall scripts)
                    val cleanedContent = if (rule != null) {
                        BypassPurifier.cleanHtml(content, realLink, rule)
                    } else {
                        content
                    }

                    // Try Readability on the cleaned content
                    articleContent = Readability.parseToElement(cleanedContent, realLink)
                    if (articleContent != null) {
                        val h1Element = articleContent.selectFirst("h1")
                        if (h1Element != null && h1Element.hasText() && h1Element.text() == title) {
                            h1Element.remove()
                        }
                        val parsedText = articleContent.text()
                        if (parsedText.length > 200) {
                            timber.log.Timber.d("OkHttp+Readability extracted ${parsedText.length} chars for $realLink")
                            return@withContext articleContent.toString()
                        }
                        timber.log.Timber.d("OkHttp content too short (${parsedText.length} chars), trying fallbacks")
                    }
                } else {
                    timber.log.Timber.d("OkHttp returned ${response.code} for $realLink, trying fallbacks")
                }
            } catch (e: Exception) {
                timber.log.Timber.d("OkHttp failed for $realLink: ${e.message}, trying fallbacks")
            }

            // ============================================================
            // FALLBACKS: Run when OkHttp fails (403/401) OR content is too short.
            // These are OUTSIDE the response.commonIsSuccessful block so they
            // always run for bypass sites, even when OkHttp returns 4xx.
            // ============================================================
            if (rule != null && !isWebViewFirst) {
                // FALLBACK 1: archive.is
                timber.log.Timber.d("FALLBACK 1: Trying archive.is for $realLink")
                try {
                    val archiveContent = BypassPurifier.fetchFromArchiveIs(realLink, okHttpClient)
                    if (archiveContent != null) {
                        val archiveArticle = Readability.parseToElement(archiveContent, realLink)
                        if (archiveArticle != null) {
                            val archiveText = archiveArticle.text()
                            if (archiveText.length > 200) {
                                timber.log.Timber.d("archive.is returned ${archiveText.length} chars")
                                return@withContext archiveArticle.toString()
                            }
                        }
                    }
                } catch (e: Exception) {
                    timber.log.Timber.w(e, "archive.is failed for $realLink")
                }

                // FALLBACK 2: Headless WebView
                timber.log.Timber.d("FALLBACK 2: Trying HeadlessWebView for $realLink")
                try {
                    val webViewHtml = headlessWebViewManager.fetchHtml(realLink, rule)
                    if (webViewHtml != null) {
                        val wvClean = BypassPurifier.cleanHtml(webViewHtml, realLink, rule)
                        val wvArticle = Readability.parseToElement(wvClean, realLink)
                        if (wvArticle != null) {
                            val h1 = wvArticle.selectFirst("h1")
                            if (h1 != null && h1.hasText() && h1.text() == title) h1.remove()
                            return@withContext wvArticle.toString()
                        }
                    }
                } catch (e: Exception) {
                    timber.log.Timber.w(e, "HeadlessWebView failed for $realLink")
                }
            }

            // Return whatever OkHttp Readability got, even if short
            if (articleContent != null) {
                return@withContext articleContent.toString()
            }

            throw IOException("All content extraction methods failed for $realLink")
        }
    }

    suspend fun queryRssXml(
        feed: Feed,
        latestLink: String?,
        preDate: Date = Date(),
    ): List<Article> =
        try {
            val accountId = context.currentAccountId
            val response = response(okHttpClient, feed.url)
            val contentType = response.header("Content-Type")

            val httpContentType =
                contentType?.let {
                    if (it.contains("charset=", ignoreCase = true)) it
                    else "$it; charset=UTF-8"
                } ?: "text/xml; charset=UTF-8"

            response.body.byteStream().use { inputStream ->
                SyndFeedInput()
                    .apply { isPreserveWireFeed = true }
                    .build(XmlReader(inputStream, httpContentType))
                    .entries
                    .asSequence()
                    .takeWhile { latestLink == null || latestLink != it.link }
                    .map { buildArticleFromSyndEntry(feed, accountId, it, preDate) }
                    .toList()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("RLog", "queryRssXml[${feed.name}]: ${e.message}")
            listOf()
        }

    fun buildArticleFromSyndEntry(
        feed: Feed,
        accountId: Int,
        syndEntry: SyndEntry,
        preDate: Date = Date(),
    ): Article {
        val desc = syndEntry.description?.value
        val content =
            syndEntry.contents
                .takeIf { it.isNotEmpty() }
                ?.let { it.joinToString("\n") { it.value } }
        //        Log.i(
        //            "RLog",
        //            "request rss:\n" +
        //                    "name: ${feed.name}\n" +
        //                    "feedUrl: ${feed.url}\n" +
        //                    "url: ${syndEntry.link}\n" +
        //                    "title: ${syndEntry.title}\n" +
        //                    "desc: ${desc}\n" +
        //                    "content: ${content}\n"
        //        )
        return Article(
            id = accountId.spacerDollar(UUID.randomUUID().toString()),
            accountId = accountId,
            feedId = feed.id,
            date =
                (syndEntry.publishedDate ?: syndEntry.updatedDate)?.takeIf { !it.isFuture(preDate) }
                    ?: preDate,
            title = syndEntry.title.decodeHTML() ?: feed.name,
            author = syndEntry.author,
            rawDescription = content ?: desc ?: "",
            shortDescription = Readability.parseToText(desc ?: content, syndEntry.link).take(280),
            //            fullContent = content,
            img = findThumbnail(syndEntry) ?: findThumbnail(content ?: desc),
            link = syndEntry.link ?: "",
            updateAt = preDate,
        )
    }

    fun findThumbnail(syndEntry: SyndEntry): String? {
        if (syndEntry.enclosures?.firstOrNull()?.url != null) {
            return syndEntry.enclosures.first().url
        }

        val mediaModule = syndEntry.getModule(MediaModule.URI) as? MediaEntryModule
        if (mediaModule != null) {
            return findThumbnail(mediaModule)
        }

        return null
    }

    private fun findThumbnail(mediaModule: MediaEntryModule): String? {
        val candidates =
            buildList {
                    add(mediaModule.metadata)
                    addAll(mediaModule.mediaGroups.map { mediaGroup -> mediaGroup.metadata })
                    addAll(mediaModule.mediaContents.map { content -> content.metadata })
                }
                .flatMap { it.thumbnail.toList() }

        val thumbnail = candidates.firstOrNull()

        if (thumbnail != null) {
            return thumbnail.url.toString()
        } else {
            val imageMedia = mediaModule.mediaContents.firstOrNull { it.medium == "image" }
            if (imageMedia != null) {
                return (imageMedia.reference as? UrlReference)?.url.toString()
            }
        }
        return null
    }

    fun findThumbnail(text: String?): String? {
        text ?: return null
        val enclosure = enclosureRegex.find(text)?.groupValues?.get(1)
        if (enclosure?.isNotBlank() == true) {
            return enclosure
        }
        // From https://gitlab.com/spacecowboy/Feeder
        // Using negative lookahead to skip data: urls, being inline base64
        // And capturing original quote to use as ending quote
        // Base64 encoded images can be quite large - and crash database cursors
        return imgRegex.find(text)?.groupValues?.get(2)?.takeIf { !it.startsWith("data:") }
    }

    suspend fun queryRssIconLink(feedLink: String?): String? {
        if (feedLink.isNullOrEmpty()) return null
        val iconFinder = BestIconFinder(okHttpClient)
        val domain = feedLink.extractDomain()
        return iconFinder.findBestIcon(domain ?: feedLink).also {
            Log.i("RLog", "queryRssIconByLink: get $it from $domain")
        }
    }

    suspend fun saveRssIcon(feedDao: FeedDao, feed: Feed, iconLink: String) {
        feedDao.update(feed.copy(icon = iconLink))
    }

    private suspend fun response(client: OkHttpClient, url: String): okhttp3.Response =
        client.newCall(Request.Builder().url(url).build()).executeAsync()
}
