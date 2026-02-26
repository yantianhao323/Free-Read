package me.ash.reader.ui.page.home.feeds.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.ash.reader.domain.model.bypass.RuleManager
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.RssService
import me.ash.reader.infrastructure.rss.RssHelper
import me.ash.reader.ui.ext.spacerDollar
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RecommendedSite(
    val name: String,
    val url: String,
    val icon: String? = null,
    val isFeatured: Boolean = false
)

data class DiscoverUiState(
    val isSearching: Boolean = false,
    val searchQuery: String = "",
    val subscribedUrls: Set<String> = emptySet(),
    val otherSites: List<RecommendedSite> = emptyList(),
    val isSubscribing: Boolean = false,
)

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val rssService: RssService,
    private val rssHelper: RssHelper,
    private val accountService: AccountService,
    private val ruleManager: RuleManager,
    private val feedDao: FeedDao,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiscoverUiState())
    val uiState: StateFlow<DiscoverUiState> = _uiState.asStateFlow()

    val featuredSites = listOf(
        // English media
        RecommendedSite("Forbes", "https://www.forbes.com/innovation/feed2/", "https://www.google.com/s2/favicons?sz=64&domain=forbes.com", isFeatured = true),
        RecommendedSite("Financial Times", "https://news.google.com/rss/search?q=site:ft.com&hl=en-US&gl=US&ceid=US:en", "https://www.google.com/s2/favicons?sz=64&domain=ft.com", isFeatured = true),
        RecommendedSite("The Washington Post", "https://news.google.com/rss/search?q=site:washingtonpost.com&hl=en-US&gl=US&ceid=US:en", "https://www.google.com/s2/favicons?sz=64&domain=washingtonpost.com", isFeatured = true),
        RecommendedSite("The Atlantic", "https://news.google.com/rss/search?q=site:theatlantic.com&hl=en-US&gl=US&ceid=US:en", "https://www.google.com/s2/favicons?sz=64&domain=theatlantic.com", isFeatured = true),
        RecommendedSite("Wired", "https://news.google.com/rss/search?q=site:wired.com&hl=en-US&gl=US&ceid=US:en", "https://www.google.com/s2/favicons?sz=64&domain=wired.com", isFeatured = true),
        RecommendedSite("Harvard Business Review", "https://news.google.com/rss/search?q=site:hbr.org&hl=en-US&gl=US&ceid=US:en", "https://www.google.com/s2/favicons?sz=64&domain=hbr.org", isFeatured = true),
        RecommendedSite("BBC News", "https://news.google.com/rss/search?q=site:bbc.com&hl=en-US&gl=US&ceid=US:en", "https://www.google.com/s2/favicons?sz=64&domain=bbc.com", isFeatured = true),
        RecommendedSite("The Guardian", "https://news.google.com/rss/search?q=site:theguardian.com&hl=en-US&gl=US&ceid=US:en", "https://www.google.com/s2/favicons?sz=64&domain=theguardian.com", isFeatured = true),
        RecommendedSite("Reuters", "https://news.google.com/rss/search?q=site:reuters.com&hl=en-US&gl=US&ceid=US:en", "https://www.google.com/s2/favicons?sz=64&domain=reuters.com", isFeatured = true),
        RecommendedSite("Le Monde", "https://news.google.com/rss/search?q=site:lemonde.fr&hl=fr&gl=FR&ceid=FR:fr", "https://www.google.com/s2/favicons?sz=64&domain=lemonde.fr", isFeatured = true),
        RecommendedSite("Der Spiegel", "https://news.google.com/rss/search?q=site:spiegel.de&hl=de&gl=DE&ceid=DE:de", "https://www.google.com/s2/favicons?sz=64&domain=spiegel.de", isFeatured = true),
        // 中文媒体
        RecommendedSite("澎湃新闻", "https://feedx.net/rss/thepaper.xml", "https://www.google.com/s2/favicons?sz=64&domain=thepaper.cn", isFeatured = true),
        RecommendedSite("界面新闻", "https://feedx.net/rss/jiemian.xml", "https://www.google.com/s2/favicons?sz=64&domain=jiemian.com", isFeatured = true),
        RecommendedSite("南方周末", "https://feedx.net/rss/infzm.xml", "https://www.google.com/s2/favicons?sz=64&domain=infzm.com", isFeatured = true),
        RecommendedSite("36氪", "https://36kr.com/feed", "https://www.google.com/s2/favicons?sz=64&domain=36kr.com", isFeatured = true),
        RecommendedSite("虎嗅", "https://feedx.net/rss/huxiu.xml", "https://www.google.com/s2/favicons?sz=64&domain=huxiu.com", isFeatured = true),
        RecommendedSite("人民日报", "http://www.people.com.cn/rss/politics.xml", "https://www.google.com/s2/favicons?sz=64&domain=people.com.cn", isFeatured = true),
        RecommendedSite("少数派", "https://sspai.com/feed", "https://www.google.com/s2/favicons?sz=64&domain=sspai.com", isFeatured = true),
        RecommendedSite("端传媒", "https://theinitium.com/feed", "https://www.google.com/s2/favicons?sz=64&domain=theinitium.com", isFeatured = true),
        RecommendedSite("联合早报", "https://feedx.net/rss/zaobao.xml", "https://www.google.com/s2/favicons?sz=64&domain=zaobao.com", isFeatured = true),
    )

    // Domains already in featuredSites (avoid duplicates)
    private val featuredDomains = setOf(
        "ft.com",
        "washingtonpost.com", "theatlantic.com", "wired.com", "hbr.org",
        "bbc.com", "bbc.co.uk", "theguardian.com", "reuters.com", "forbes.com",
        "lemonde.fr", "spiegel.de",
        // Chinese media
        "thepaper.cn", "jiemian.com", "infzm.com", "36kr.com",
        "huxiu.com", "people.com.cn", "sspai.com", "theinitium.com", "zaobao.com"
    )

    val allSites: List<RecommendedSite>
        get() = featuredSites + _uiState.value.otherSites

    init {
        loadSubscribedUrls()
        loadBypassableSites()
    }

    /**
     * Load all bypassable sites from RuleManager and convert to RecommendedSite entries.
     * Uses known RSS URLs where available, falls back to Google News RSS for others.
     */
    private fun loadBypassableSites() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                timber.log.Timber.d("loadBypassableSites: starting, calling ensureLoaded()")
                ruleManager.ensureLoaded()
                
                val bypassSites = ruleManager.getAllBypassableSites()
                timber.log.Timber.d("loadBypassableSites: got ${bypassSites.size} bypass sites from RuleManager")
                
                if (bypassSites.isEmpty()) {
                    timber.log.Timber.w("loadBypassableSites: NO bypass sites returned! Rules may not be loaded.")
                    return@launch
                }
                
                // Group by display name to avoid duplicate entries for grouped domains
                val sitesByName = mutableMapOf<String, MutableList<String>>()
                for ((name, domain) in bypassSites) {
                    sitesByName.getOrPut(name) { mutableListOf() }.add(domain)
                }
                timber.log.Timber.d("loadBypassableSites: grouped into ${sitesByName.size} unique site names")
                
                val generated = mutableListOf<RecommendedSite>()
                
                // --- DEBUG ERROR INJECTION ---
                val currentError = ruleManager.lastError
                if (currentError != null) {
                    generated.add(
                        RecommendedSite(
                            name = "ERR: " + currentError.take(50),
                            url = "https://error.com",
                            icon = "https://www.google.com/s2/favicons?sz=64&domain=error.com",
                        )
                    )
                }
                
                for ((name, domains) in sitesByName) {
                    val domain = domains.first()
                    
                    // Skip if already in featured list
                    if (featuredDomains.any { domain.endsWith(it) || it.endsWith(domain) }) continue
                    
                    // All sites use Google News RSS for maximum reliability
                    val rssUrl = "https://news.google.com/rss/search?q=site:${domain}&hl=en-US&gl=US&ceid=US:en"
                    
                    generated.add(
                        RecommendedSite(
                            name = name.replace(Regex("\\s*\\(.*\\)\\s*$"), ""),
                            url = rssUrl,
                            icon = "https://www.google.com/s2/favicons?sz=64&domain=${domain}",
                        )
                    )
                }
                
                // Sort alphabetically and push into reactive state
                val sorted = generated.sortedBy { it.name.lowercase() }
                
                timber.log.Timber.d("loadBypassableSites: generated ${sorted.size} sites, updating UI state")
                _uiState.update { it.copy(otherSites = sorted) }
                timber.log.Timber.d("loadBypassableSites: UI state updated with ${sorted.size} other sites")
            } catch (e: Exception) {
                timber.log.Timber.e(e, "loadBypassableSites: FAILED with exception")
            }
        }
    }

    private fun loadSubscribedUrls() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Collect existing feeds to know what's already subscribed
                rssService.get().pullFeeds().collect { groupsWithFeeds ->
                    val urls = mutableSetOf<String>()
                    for (groupWithFeed in groupsWithFeeds) {
                        for (feed in groupWithFeed.feeds) {
                            urls.add(feed.url)
                        }
                    }
                    _uiState.update { it.copy(subscribedUrls = urls) }
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    fun toggleSearch() {
        _uiState.update { it.copy(isSearching = !it.isSearching, searchQuery = "") }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    suspend fun subscribe(site: RecommendedSite) {
        _uiState.update { it.copy(isSubscribing = true) }
        withContext(Dispatchers.IO) {
            try {
                val accountId = accountService.getCurrentAccountId()
                val groups = rssService.get().pullGroups().first()
                val defaultGroupId = groups.firstOrNull()?.id ?: return@withContext

                // Try the standard RSS subscribe flow first
                try {
                    val syndFeed = rssHelper.searchFeed(site.url)
                    rssService.get().subscribe(
                        searchedFeed = syndFeed,
                        feedLink = site.url,
                        groupId = defaultGroupId,
                        isNotification = false,
                        isFullContent = true,
                        isBrowser = false
                    )
                } catch (rssException: Exception) {
                    // RSS parsing failed — insert Feed directly into database
                    val feed = Feed(
                        id = accountId.spacerDollar(UUID.randomUUID().toString()),
                        name = site.name,
                        url = site.url,
                        groupId = defaultGroupId,
                        accountId = accountId,
                        icon = site.icon,
                        isFullContent = true,
                    )
                    feedDao.insert(feed)
                }

                // Immediately update subscribed URLs in UI
                _uiState.update { state ->
                    state.copy(
                        subscribedUrls = state.subscribedUrls + site.url,
                        isSubscribing = false
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.update { it.copy(isSubscribing = false) }
            }
        }
    }
}
