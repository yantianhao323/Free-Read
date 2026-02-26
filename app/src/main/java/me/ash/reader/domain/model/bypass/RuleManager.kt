package me.ash.reader.domain.model.bypass

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

import dagger.hilt.android.qualifiers.ApplicationContext

@Singleton
class RuleManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val rules = mutableMapOf<String, SiteRule>()

    // This will hold the "domain" mapped to its fully resolved SiteRule.
    private val domainRules = mutableMapOf<String, SiteRule>()

    // All site names mapped to their rules (for media catalog)
    private val namedRules = mutableMapOf<String, SiteRule>()

    // Thread-safety: prevent concurrent loading
    private val loadMutex = Mutex()
    @Volatile
    private var isLoaded = false

    var lastError: String? = null

    /**
     * Ensure rules are loaded. Safe to call from multiple threads.
     * If already loaded, returns immediately. Otherwise waits for loading to complete.
     */
    suspend fun ensureLoaded() {
        if (isLoaded) return
        loadRules()
    }

    suspend fun loadRules() = loadMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                // Load base rules from assets
                val baseRulesJson = context.assets.open("sites.json").bufferedReader().use { it.readText() }
                val baseJsonObj = org.json.JSONObject(baseRulesJson)

                // Load updated rules from local files if present
                val updatedRulesFile = File(context.filesDir, "sites_updated.json")
                val updatedJsonObj = if (updatedRulesFile.exists()) {
                    try {
                        org.json.JSONObject(updatedRulesFile.readText())
                    } catch (e: Exception) {
                        Timber.e(e, "Error loading updated rules, using base rules only")
                        org.json.JSONObject()
                    }
                } else {
                    org.json.JSONObject()
                }

                // Merge JSON objects
                val mergedKeys = mutableSetOf<String>()
                baseJsonObj.keys().forEach { mergedKeys.add(it) }
                updatedJsonObj.keys().forEach { mergedKeys.add(it) }

                rules.clear()
                
                for (key in mergedKeys) {
                    try {
                        val jsonObj = if (updatedJsonObj.has(key)) updatedJsonObj.getJSONObject(key) else baseJsonObj.getJSONObject(key)
                        rules[key] = mapJsonToSiteRule(jsonObj)
                    } catch (e: Exception) {
                        Timber.w(e, "Skipped invalid rule format for key: $key")
                    }
                }

                // Resolve grouped domains and exceptions
                domainRules.clear()
                namedRules.clear()

                for ((key, rule) in rules) {
                    // Skip settings entries
                    if (rule.domain == "###" || rule.domain?.startsWith("#options_") == true) continue
                    // Skip nofix entries
                    if (rule.nofix != null && rule.nofix > 0) continue

                    namedRules[key] = rule

                    if (rule.domain?.startsWith("###") == true && rule.group != null) {
                        // Grouped domains
                        for (groupDomain in rule.group) {
                            // Find exception
                            val exceptionRule = findExceptionNative(rule, groupDomain)
                            domainRules[groupDomain] = exceptionRule ?: rule
                        }
                    } else if (rule.domain != null) {
                        domainRules[rule.domain] = rule
                    }
                }
                isLoaded = true
                Timber.d("Loaded ${domainRules.size} bypass rules from ${namedRules.size} named entries.")
            } catch (e: Exception) {
                lastError = e.message ?: e.toString()
                Timber.e(e, "Error loading bypass rules")
            }
        }
    }

    private fun findExceptionNative(parentRule: SiteRule, domain: String): SiteRule? {
        val exceptions = parentRule.exception ?: return null
        for (exception in exceptions) {
            if (exception.domain == domain || exception.group?.contains(domain) == true) {
                return exception
            }
        }
        return null
    }

    private fun mapJsonToSiteRule(jsonObj: org.json.JSONObject): SiteRule {
        val groupArray = jsonObj.optJSONArray("group")
        val groupList = if (groupArray != null) List(groupArray.length()) { groupArray.getString(it) } else null

        val holdArray = jsonObj.optJSONArray("remove_cookies_select_hold")
        val holdList = if (holdArray != null) List(holdArray.length()) { holdArray.getString(it) } else null

        val dropArray = jsonObj.optJSONArray("remove_cookies_select_drop")
        val dropList = if (dropArray != null) List(dropArray.length()) { dropArray.getString(it) } else null

        var headersMap: Map<String, String>? = null
        val headersObj = jsonObj.optJSONObject("headers_custom")
        if (headersObj != null) {
            val map = mutableMapOf<String, String>()
            headersObj.keys().forEach { map[it] = headersObj.getString(it) }
            headersMap = map
        }

        var exceptionList: List<SiteRule>? = null
        val exceptionArray = jsonObj.optJSONArray("exception")
        if (exceptionArray != null) {
            val elist = mutableListOf<SiteRule>()
            for (i in 0 until exceptionArray.length()) {
                elist.add(mapJsonToSiteRule(exceptionArray.getJSONObject(i)))
            }
            exceptionList = elist
        }

        // Handle cs_code as JsonElement (via kotlinx string parsing of the inner object if needed, but since it's just passed to BypassPurifier, we can just leave it as null for now if we can't map it properly. But we can stringify it!)
        // Wait, BypassPurifier expects kotlinx.serialization.json.JsonElement. So let's parse just that small snippet using Json!
        val csCodeStr = jsonObj.opt("cs_code")?.toString()
        val csCodeEl: kotlinx.serialization.json.JsonElement? = if (csCodeStr != null && csCodeStr != "null") {
            try { kotlinx.serialization.json.Json.parseToJsonElement(csCodeStr) } catch(e: Exception) { null }
        } else null

        return SiteRule(
            domain = jsonObj.optString("domain", "").takeIf { it.isNotEmpty() },
            group = groupList,
            allowCookies = if (jsonObj.has("allow_cookies")) jsonObj.optInt("allow_cookies") else null,
            removeCookies = if (jsonObj.has("remove_cookies")) jsonObj.optInt("remove_cookies") else null,
            removeCookiesSelectHold = holdList,
            removeCookiesSelectDrop = dropList,
            useragent = jsonObj.optString("useragent", "").takeIf { it.isNotEmpty() },
            useragentCustom = jsonObj.optString("useragent_custom", "").takeIf { it.isNotEmpty() },
            referer = jsonObj.optString("referer", "").takeIf { it.isNotEmpty() },
            refererCustom = jsonObj.optString("referer_custom", "").takeIf { it.isNotEmpty() },
            randomIp = jsonObj.optString("random_ip", "").takeIf { it.isNotEmpty() },
            blockRegex = jsonObj.optString("block_regex", "").takeIf { it.isNotEmpty() },
            blockJs = if (jsonObj.has("block_js")) jsonObj.optInt("block_js") else null,
            blockJsExt = if (jsonObj.has("block_js_ext")) jsonObj.optInt("block_js_ext") else null,
            blockJsInline = jsonObj.optString("block_js_inline", "").takeIf { it.isNotEmpty() },
            ldJson = jsonObj.optString("ld_json", "").takeIf { it.isNotEmpty() },
            ldJsonNext = jsonObj.optString("ld_json_next", "").takeIf { it.isNotEmpty() },
            ldJsonSource = jsonObj.optString("ld_json_source", "").takeIf { it.isNotEmpty() },
            ldJsonUrl = jsonObj.optString("ld_json_url", "").takeIf { it.isNotEmpty() },
            ldArchiveIs = jsonObj.optString("ld_archive_is", "").takeIf { it.isNotEmpty() },
            ldOchToUnlock = jsonObj.optString("ld_och_to_unlock", "").takeIf { it.isNotEmpty() },
            csClearLclStrg = if (jsonObj.has("cs_clear_lclstrg")) jsonObj.optInt("cs_clear_lclstrg") else null,
            csCode = csCodeEl,
            csDompurify = if (jsonObj.has("cs_dompurify")) jsonObj.optInt("cs_dompurify") else null,
            csBlock = if (jsonObj.has("cs_block")) jsonObj.optInt("cs_block") else null,
            ampUnhide = if (jsonObj.has("amp_unhide")) jsonObj.optInt("amp_unhide") else null,
            ampRedirect = jsonObj.optString("amp_redirect", "").takeIf { it.isNotEmpty() },
            addExtLink = jsonObj.optString("add_ext_link", "").takeIf { it.isNotEmpty() },
            addExtLinkType = jsonObj.optString("add_ext_link_type", "").takeIf { it.isNotEmpty() },
            exception = exceptionList,
            nofix = if (jsonObj.has("nofix")) jsonObj.optInt("nofix") else null,
            headersCustom = headersMap
        )
    }

    /**
     * Find exception rule for a specific domain within a group rule.
     */
    private fun findException(parentRule: SiteRule, domain: String): SiteRule? {
        val exceptions = parentRule.exception ?: return null
        for (exception in exceptions) {
            val exceptionDomain = exception.domain
            if (exceptionDomain == domain) return exception
            // exception.domain can also be in the group list
            if (exception.group?.contains(domain) == true) return exception
        }
        return null
    }

    fun getRuleForUrl(url: String): SiteRule? {
        val host = getHost(url)
        if (host.isEmpty()) return null

        // Exact match
        if (domainRules.containsKey(host)) return domainRules[host]

        // Subdomain match (e.g., www.nytimes.com -> nytimes.com)
        for ((domain, rule) in domainRules) {
            if (host.endsWith(".$domain")) {
                return rule
            }
        }
        return null
    }

    /**
     * Check if a URL belongs to a bypassable site.
     */
    fun isBypassable(url: String): Boolean {
        return getRuleForUrl(url) != null
    }

    /**
     * Get all bypassable domains with their display names.
     * Returns a list of pairs: (displayName, domain)
     */
    fun getAllBypassableSites(): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        for ((name, rule) in namedRules) {
            if (rule.domain?.startsWith("###") == true && rule.group != null) {
                // For grouped sites, add the group name with all domains
                for (domain in rule.group) {
                    result.add(Pair(name, domain))
                }
            } else if (rule.domain != null && !rule.domain.startsWith("#")) {
                result.add(Pair(name, rule.domain))
            }
        }
        return result
    }

    /**
     * Get all unique bypassable domains.
     */
    fun getAllBypassableDomains(): Set<String> {
        return domainRules.keys.toSet()
    }

    /**
     * Save updated rules to local storage.
     */
    suspend fun saveUpdatedRules(updatedRulesJson: String) = withContext(Dispatchers.IO) {
        try {
            val file = File(context.filesDir, "sites_updated.json")
            file.writeText(updatedRulesJson)
            // Reload rules after save
            loadRules()
            Timber.d("Updated rules saved and reloaded")
        } catch (e: Exception) {
            Timber.e(e, "Error saving updated rules")
        }
    }

    private fun getHost(url: String): String {
        return try {
            val uri = java.net.URI(url)
            uri.host?.removePrefix("www.") ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}
