package me.ash.reader.infrastructure.net

import me.ash.reader.domain.model.bypass.RuleManager
import okhttp3.Interceptor
import okhttp3.Response

class BypassInterceptor(
    private val ruleManager: RuleManager
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        val url = request.url.toString()
        val rule = ruleManager.getRuleForUrl(url)

        if (rule != null) {
            val builder = request.newBuilder()

            // 1. User-Agent
            when (rule.useragent) {
                "googlebot" -> {
                    builder.header("User-Agent", UA_GOOGLEBOT)
                    // Googlebot should always include Google referer and IP
                    builder.header("Referer", "https://www.google.com/")
                    builder.header("X-Forwarded-For", "66.249.66.1")
                }
                "bingbot" -> {
                    builder.header("User-Agent", UA_BINGBOT)
                }
                "facebookbot" -> {
                    builder.header("User-Agent", UA_FACEBOOKBOT)
                }
                else -> {
                    if (!rule.useragentCustom.isNullOrEmpty()) {
                        builder.header("User-Agent", rule.useragentCustom)
                    }
                    // Custom headers
                    rule.headersCustom?.forEach { (header, value) ->
                        builder.header(header, value)
                    }
                }
            }

            // 2. Referer (only if not already set by googlebot UA)
            if (rule.useragent != "googlebot") {
                when (rule.referer) {
                    "google" -> builder.header("Referer", "https://www.google.com/")
                    "facebook" -> builder.header("Referer", "https://www.facebook.com/")
                    "twitter" -> builder.header("Referer", "https://t.co/")
                    else -> {
                        if (!rule.refererCustom.isNullOrEmpty()) {
                            builder.header("Referer", rule.refererCustom)
                        }
                    }
                }
            }

            // 3. Cookie handling
            val shouldClearCookies = when {
                // If remove_cookies_select_drop or remove_cookies_select_hold is set,
                // we need selective cookie handling
                rule.removeCookiesSelectDrop != null || rule.removeCookiesSelectHold != null -> false
                rule.allowCookies != null && rule.allowCookies > 0 -> false
                rule.removeCookies != null && rule.removeCookies > 0 -> true
                else -> true // Default: clear cookies
            }

            if (shouldClearCookies) {
                builder.removeHeader("Cookie")
            } else if (rule.removeCookiesSelectDrop != null) {
                // Remove specific cookies
                val existingCookies = request.header("Cookie")
                if (existingCookies != null) {
                    val filteredCookies = existingCookies.split(";")
                        .map { it.trim() }
                        .filter { cookie ->
                            val name = cookie.substringBefore("=").trim()
                            !rule.removeCookiesSelectDrop.contains(name)
                        }
                        .joinToString("; ")
                    if (filteredCookies.isNotBlank()) {
                        builder.header("Cookie", filteredCookies)
                    } else {
                        builder.removeHeader("Cookie")
                    }
                }
            } else if (rule.removeCookiesSelectHold != null) {
                // Keep only specific cookies
                val existingCookies = request.header("Cookie")
                if (existingCookies != null) {
                    val filteredCookies = existingCookies.split(";")
                        .map { it.trim() }
                        .filter { cookie ->
                            val name = cookie.substringBefore("=").trim()
                            rule.removeCookiesSelectHold.contains(name)
                        }
                        .joinToString("; ")
                    if (filteredCookies.isNotBlank()) {
                        builder.header("Cookie", filteredCookies)
                    } else {
                        builder.removeHeader("Cookie")
                    }
                }
            }

            // 4. Random IP
            if (!rule.randomIp.isNullOrEmpty()) {
                val ip = when (rule.randomIp) {
                    "eu" -> "185.185.${(0..255).random()}.${(0..255).random()}"
                    else -> "${(1..223).random()}.${(0..255).random()}.${(0..255).random()}.${(1..254).random()}"
                }
                builder.header("X-Forwarded-For", ip)
            }

            request = builder.build()
        }

        return chain.proceed(request)
    }

    companion object {
        const val UA_GOOGLEBOT = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"
        const val UA_BINGBOT = "Mozilla/5.0 (compatible; bingbot/2.0; +http://www.bing.com/bingbot.htm)"
        const val UA_FACEBOOKBOT = "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)"
    }
}
