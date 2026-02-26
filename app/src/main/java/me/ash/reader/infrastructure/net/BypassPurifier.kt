package me.ash.reader.infrastructure.net

import me.ash.reader.domain.model.bypass.SiteRule
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

object BypassPurifier {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Main purification pipeline. Tries multiple bypass strategies in order:
     * 1. block_regex: remove paywall script tags
     * 2. ld_json: extract articleBody from ld+json
     * 3. ld_json_next: extract from __NEXT_DATA__
     * 4. ld_json_url: fetch from alternate JSON link
     * 5. ld_json_source: fetch from script src JSON
     * 6. amp_unhide: unhide AMP content
     * 7. cs_code: DOM manipulations (remove elements, etc.)
     * 8. archive.is: fallback fetch from web archive
     */
    fun purify(html: String, url: String, rule: SiteRule?, okHttpClient: OkHttpClient? = null): String? {
        if (rule == null) return null

        try {
            val doc = Jsoup.parse(html, url)

            // Step 1: Remove paywall scripts (block_regex)
            applyBlockRegex(doc, url, rule)

            // Step 2: Try LD+JSON extraction
            rule.ldJson?.let { selectorRule ->
                val extracted = extractLdJson(doc, selectorRule)
                if (extracted != null) return extracted
            }

            // Step 3: Try NEXT_DATA extraction
            rule.ldJsonNext?.let { selectorRule ->
                val extracted = extractNextData(doc, selectorRule)
                if (extracted != null) return extracted
            }

            // Step 4: Try ld_json_url (fetch from alternate JSON link)
            if (rule.ldJsonUrl != null && okHttpClient != null) {
                val extracted = extractLdJsonUrl(doc, okHttpClient)
                if (extracted != null) return extracted
            }

            // Step 5: Try ld_json_source (fetch from script src)
            if (rule.ldJsonSource != null && okHttpClient != null) {
                val extracted = extractLdJsonSource(doc, rule.ldJsonSource, okHttpClient)
                if (extracted != null) return extracted
            }

            // Step 6: AMP unhide
            if (rule.ampUnhide != null && rule.ampUnhide > 0) {
                applyAmpUnhide(doc)
            }

            // Step 7: cs_code DOM operations
            rule.csCode?.let { csCode ->
                applyCsCode(doc, csCode)
            }

            // Step 8: cs_dompurify — try to extract article content after DOM cleanup
            if (rule.csDompurify != null && rule.csDompurify > 0) {
                // Return cleaned HTML for Readability to process
                return null // Let Readability handle it with the cleaned HTML
            }

            // If block_regex was applied, the cleaned HTML should be parseable by Readability
            if (rule.blockRegex != null || rule.blockJs != null || rule.blockJsInline != null) {
                return null // Readability will handle the cleaned page
            }

            return null
        } catch (e: Exception) {
            Timber.e(e, "Error purifying bypassed HTML")
            return null
        }
    }

    /**
     * Get AMP URL from a page if amp_redirect is configured.
     */
    fun getAmpRedirectUrl(html: String, url: String, rule: SiteRule?): String? {
        if (rule?.ampRedirect == null) return null
        try {
            val doc = Jsoup.parse(html, url)
            val ampLink = doc.selectFirst("link[rel=amphtml]")
            if (ampLink != null) {
                return ampLink.attr("abs:href")
            }
            // Some sites use a different pattern
            val canonicalUrl = doc.selectFirst("link[rel=canonical]")?.attr("abs:href") ?: url
            return when (rule.ampRedirect) {
                "amp" -> {
                    if (canonicalUrl.contains("?")) "$canonicalUrl&amp" else "$canonicalUrl?amp"
                }
                else -> null
            }
        } catch (e: Exception) {
            Timber.e(e, "Error getting AMP redirect URL")
            return null
        }
    }

    /**
     * Try to fetch full article from archive.is as a last-resort fallback.
     */
    fun fetchFromArchiveIs(url: String, okHttpClient: OkHttpClient): String? {
        try {
            val archiveUrl = "https://archive.is/newest/$url"
            val request = Request.Builder()
                .url(archiveUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return null
                val doc = Jsoup.parse(body, archiveUrl)
                // Remove archive.is toolbar and UI
                doc.select("#HEADER, #wm-ipp, .wm-ipp-base, #donato, #FOOTER").remove()
                return doc.body().html()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch from archive.is")
        }
        return null
    }

    /**
     * Remove paywall script tags matching block_regex.
     * This is the most important bypass strategy — works for ~60% of paywalled sites.
     */
    private fun applyBlockRegex(doc: Document, url: String, rule: SiteRule) {
        val regexStr = rule.blockRegex ?: return
        try {
            // Resolve {domain} placeholder
            val host = getHost(url)
            val resolvedRegex = regexStr.replace("{domain}", host.replace(".", "\\."))
            val regex = Regex(resolvedRegex)

            // Remove matching <script> tags by src attribute
            doc.select("script[src]").forEach { script ->
                val src = script.attr("src")
                if (regex.containsMatchIn(src)) {
                    script.remove()
                }
            }

            // Also remove matching inline scripts
            doc.select("script:not([src])").forEach { script ->
                val content = script.data()
                if (regex.containsMatchIn(content)) {
                    script.remove()
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error applying block_regex: $regexStr")
        }

        // Also handle block_js (remove all JS from domain)
        if (rule.blockJs != null && rule.blockJs > 0) {
            val host = getHost(url)
            doc.select("script[src]").forEach { script ->
                val src = script.attr("src")
                if (src.contains(host)) {
                    script.remove()
                }
            }
        }

        // block_js_inline
        rule.blockJsInline?.let { inlineRegex ->
            try {
                val regex = Regex(inlineRegex.replace("{domain}", getHost(url).replace(".", "\\.")))
                if (regex.containsMatchIn(url)) {
                    doc.select("script:not([src])").remove()
                }
            } catch (e: Exception) {
                Timber.e(e, "Error applying block_js_inline")
            }
        }
    }

    /**
     * Extract article text from ld+json script tags.
     */
    private fun extractLdJson(doc: Document, selectorRule: String): String? {
        try {
            val scripts = doc.select("script[type=application/ld+json]")
            for (script in scripts) {
                val data = script.data()
                if (data.contains("\"articleBody\"", ignoreCase = true) || data.contains("\"text\"", ignoreCase = true)) {
                    val jsonElement = json.parseToJsonElement(data)

                    val obj = when (jsonElement) {
                        is JsonArray -> if (jsonElement.isNotEmpty()) jsonElement[0].jsonObject else null
                        is JsonObject -> {
                            // Handle @graph pattern
                            val graph = jsonElement["@graph"]
                            if (graph is JsonArray) {
                                graph.firstOrNull { it.jsonObject.containsKey("articleBody") || it.jsonObject.containsKey("text") }?.jsonObject
                            } else {
                                jsonElement
                            }
                        }
                        else -> null
                    }

                    val articleBody = obj?.get("articleBody")?.jsonPrimitive?.content
                        ?: obj?.get("text")?.jsonPrimitive?.content

                    if (!articleBody.isNullOrEmpty()) {
                        return "<div><p>${articleBody.replace("\n", "</p><p>")}</p></div>"
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse ld_json")
        }
        return null
    }

    /**
     * Extract article content from __NEXT_DATA__ script tag.
     */
    private fun extractNextData(doc: Document, selectorRule: String): String? {
        try {
            val script = doc.selectFirst("script#__NEXT_DATA__") ?: return null
            val data = script.data()

            // Try multiple common field patterns
            val patterns = listOf(
                "\"contentHtml\"\\s*:\\s*\"(.*?)\"",
                "\"body\"\\s*:\\s*\"(.*?)\"",
                "\"BodyPlainText\"\\s*:\\s*\"(.*?)\"",
                "\"content\"\\s*:\\s*\"(.*?)\"",
                "\"html\"\\s*:\\s*\"(.*?)\"",
                "\"articleBody\"\\s*:\\s*\"(.*?)\""
            )

            for (pattern in patterns) {
                val match = Regex(pattern).find(data)
                if (match != null) {
                    val htmlStr = match.groupValues[1]
                        .replace("\\\"", "\"")
                        .replace("\\\\n", "<br>")
                        .replace("\\n", "<br>")
                        .replace("\\/", "/")
                    return "<div>$htmlStr</div>"
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse NEXT_DATA")
        }
        return null
    }

    /**
     * Fetch full content from an alternate JSON API link.
     * Looks for <link rel="alternate" type="application/json" href="...">
     */
    private fun extractLdJsonUrl(doc: Document, okHttpClient: OkHttpClient): String? {
        try {
            val link = doc.selectFirst("link[rel=alternate][type=application/json][href]") ?: return null
            val jsonUrl = link.attr("abs:href")
            if (jsonUrl.isBlank()) return null

            val request = Request.Builder()
                .url(jsonUrl)
                .header("User-Agent", "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)")
                .build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return null
                val jsonObj = json.parseToJsonElement(body).jsonObject

                // WordPress REST API pattern: content.rendered
                val content = jsonObj["content"]?.jsonObject?.get("rendered")?.jsonPrimitive?.content
                if (!content.isNullOrEmpty()) return "<div>$content</div>"

                // Fallback: direct content field
                val directContent = jsonObj["content"]?.jsonPrimitive?.content
                if (!directContent.isNullOrEmpty()) return "<div>$directContent</div>"
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to extract ld_json_url")
        }
        return null
    }

    /**
     * Fetch content from a JSON source script.
     */
    private fun extractLdJsonSource(doc: Document, sourcePattern: String, okHttpClient: OkHttpClient): String? {
        try {
            val scripts = doc.select("script[src]")
            for (script in scripts) {
                val src = script.attr("abs:src")
                if (src.contains(sourcePattern) || src.endsWith(".json")) {
                    val request = Request.Builder()
                        .url(src)
                        .header("User-Agent", "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)")
                        .build()
                    val response = okHttpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: continue
                        val jsonObj = json.parseToJsonElement(body).jsonObject
                        val articleBody = jsonObj["articleBody"]?.jsonPrimitive?.content
                            ?: jsonObj["text"]?.jsonPrimitive?.content
                            ?: jsonObj["content"]?.jsonPrimitive?.content
                        if (!articleBody.isNullOrEmpty()) {
                            return "<div><p>${articleBody.replace("\n", "</p><p>")}</p></div>"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to extract ld_json_source")
        }
        return null
    }

    /**
     * Unhide AMP-hidden content by removing amp-access-hide attributes.
     */
    private fun applyAmpUnhide(doc: Document) {
        doc.select("[amp-access-hide]").forEach { elem ->
            elem.removeAttr("amp-access-hide")
        }
        // Also remove amp-access paywall elements
        doc.select("amp-access-extension, [amp-access]").forEach { elem ->
            if (elem.attr("amp-access").contains("NOT") || elem.attr("amp-access").contains("subscriber")) {
                elem.remove()
            }
        }
    }

    /**
     * Apply cs_code DOM operations (JSON-based DOM manipulation directives).
     * Supports: rm_elem (remove element), rm_attr (remove attribute), set_attr (set attribute)
     */
    private fun applyCsCode(doc: Document, csCodeElement: kotlinx.serialization.json.JsonElement) {
        try {
            val codeList = when (csCodeElement) {
                is JsonArray -> csCodeElement.map { it.jsonObject }
                is JsonObject -> listOf(csCodeElement)
                else -> return
            }

            for (codeObj in codeList) {
                val condition = codeObj["cond"]?.jsonPrimitive?.content ?: continue

                if (codeObj["rm_elem"]?.jsonPrimitive?.intOrNull == 1) {
                    doc.select(condition).remove()
                }

                codeObj["rm_attr"]?.jsonPrimitive?.content?.let { attr ->
                    doc.select(condition).removeAttr(attr)
                }

                codeObj["set_attr"]?.jsonPrimitive?.content?.let { attr ->
                    val value = codeObj["set_val"]?.jsonPrimitive?.content ?: ""
                    doc.select(condition).attr(attr, value)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error applying cs_code")
        }
    }

    /**
     * Clean HTML by applying all applicable DOM-level bypass operations.
     * Returns the modified HTML string for Readability to process.
     */
    fun cleanHtml(html: String, url: String, rule: SiteRule?): String {
        if (rule == null) return html
        try {
            val doc = Jsoup.parse(html, url)
            applyBlockRegex(doc, url, rule)
            if (rule.ampUnhide != null && rule.ampUnhide > 0) {
                applyAmpUnhide(doc)
            }
            rule.csCode?.let { applyCsCode(doc, it) }
            return doc.html()
        } catch (e: Exception) {
            Timber.e(e, "Error cleaning HTML")
            return html
        }
    }

    private fun getHost(url: String): String {
        return try {
            val uri = java.net.URI(url)
            uri.host?.removePrefix("www.") ?: ""
        } catch (e: Exception) { "" }
    }
}
