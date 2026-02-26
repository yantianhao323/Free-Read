package me.ash.reader.domain.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import me.ash.reader.domain.model.bypass.RuleManager
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class MediaEntry(
    val name: String,
    val domain: String,
    val country: String = "",
    val rss: List<String> = emptyList(),
    val weight: Int = 0,
)

@Singleton
class MediaCatalogRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ruleManager: RuleManager,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var catalog: List<MediaEntry> = emptyList()

    fun loadCatalog(): List<MediaEntry> {
        if (catalog.isNotEmpty()) return catalog
        try {
            val catalogJson = context.assets.open("media_catalog.json").bufferedReader().use { it.readText() }
            catalog = json.decodeFromString<List<MediaEntry>>(catalogJson)
                .sortedByDescending { it.weight }
            Timber.d("Loaded ${catalog.size} media entries from catalog")
        } catch (e: Exception) {
            Timber.e(e, "Failed to load media catalog")
        }
        return catalog
    }

    /**
     * Search media catalog by name or domain.
     */
    fun search(query: String): List<MediaEntry> {
        val q = query.lowercase().trim()
        if (q.isEmpty()) return loadCatalog()
        return loadCatalog().filter {
            it.name.lowercase().contains(q) || it.domain.lowercase().contains(q) ||
                    it.country.lowercase().contains(q)
        }
    }

    /**
     * Get the best RSS URL for a given media entry.
     * Falls back to Google News RSS if no pre-set RSS is available.
     */
    fun getRssUrl(entry: MediaEntry): String {
        return if (entry.rss.isNotEmpty()) {
            entry.rss.first()
        } else {
            // Google News RSS fallback
            "https://news.google.com/rss/search?q=site:${entry.domain}&hl=en"
        }
    }

    /**
     * Get all RSS URLs for a given media entry (for offering multiple feeds).
     */
    fun getAllRssUrls(entry: MediaEntry): List<Pair<String, String>> {
        val urls = mutableListOf<Pair<String, String>>()
        entry.rss.forEachIndexed { index, url ->
            val label = if (entry.rss.size == 1) entry.name else "${entry.name} (#${index + 1})"
            urls.add(Pair(label, url))
        }
        if (urls.isEmpty()) {
            urls.add(Pair("${entry.name} (via Google News)", "https://news.google.com/rss/search?q=site:${entry.domain}&hl=en"))
        }
        return urls
    }

    /**
     * Check if a domain is bypassable (has bypass rules).
     */
    fun isBypassable(domain: String): Boolean {
        return ruleManager.isBypassable("https://$domain")
    }
}
