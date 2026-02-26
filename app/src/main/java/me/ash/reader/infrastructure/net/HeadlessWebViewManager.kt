package me.ash.reader.infrastructure.net

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.coroutines.resume

import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import me.ash.reader.domain.model.bypass.SiteRule
import me.ash.reader.infrastructure.net.BypassInterceptor.Companion.UA_BINGBOT
import me.ash.reader.infrastructure.net.BypassInterceptor.Companion.UA_FACEBOOKBOT
import me.ash.reader.infrastructure.net.BypassInterceptor.Companion.UA_GOOGLEBOT

/**
 * A headless WebView manager to silently render webpages in the background.
 * This is crucial to bypass Cloudflare and Fastly JS-Challenges that block okHttp.
 */
@Singleton
@SuppressLint("SetJavaScriptEnabled")
class HeadlessWebViewManager @Inject constructor(@ApplicationContext private val context: Context) {

    /**
     * Fetches the rendered HTML of a given URL.
     * This will suspend until the page finishes loading or times out after [timeoutMs].
     */
    suspend fun fetchHtml(url: String, rule: SiteRule?, timeoutMs: Long = 15000L): String? {
        return withContext(Dispatchers.Main) {
            val webView = createWebView(rule)
            
            val resultHtml = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<String?> { continuation ->
                    var isResumed = false

                    webView.webViewClient = object : WebViewClient() {

                        // Compile block_regex once for efficient matching
                        private val blockRegex: Regex? = try {
                            rule?.blockRegex?.let { regexStr ->
                                val host = try { java.net.URI(url).host ?: "" } catch (e: Exception) { "" }
                                Regex(regexStr.replace("{domain}", host.replace(".", "\\.")))
                            }
                        } catch (e: Exception) {
                            Timber.w(e, "Failed to compile block_regex for WebView")
                            null
                        }
                        
                        override fun onPageFinished(view: WebView, url: String) {
                            super.onPageFinished(view, url)
                            
                            // Inject paywall-removal JS BEFORE extracting DOM
                            val jsToInject = buildString {
                                // Remove common paywall overlay elements
                                append("(function(){")
                                append("document.querySelectorAll('[class*=paywall],[class*=Paywall],[id*=paywall],[class*=gateway],[class*=modal-backdrop],[class*=piano]').forEach(function(e){e.remove();});")
                                // Unhide hidden article content
                                append("document.querySelectorAll('[class*=truncate],[class*=fade-out],[class*=article-body]').forEach(function(e){e.style.maxHeight='none';e.style.overflow='visible';});")
                                // Remove any paywall CSS overflow hidden
                                append("document.body.style.overflow='visible';")
                                append("document.documentElement.style.overflow='visible';")
                                // Clear localStorage if rule says so (Bloomberg: cs_clear_lclstrg)
                                if (rule?.csClearLclStrg != null) {
                                    append("try{localStorage.clear();}catch(e){}")
                                }
                                append("})();")
                            }
                            view.evaluateJavascript(jsToInject, null)

                            // Give it a tiny bit of extra time to execute trailing async JS
                            view.postDelayed({
                                if (isResumed) return@postDelayed
                                
                                view.evaluateJavascript(
                                    "(function() { return ('<html>'+document.getElementsByTagName('html')[0].innerHTML+'</html>'); })();"
                                ) { htmlResult ->
                                    if (isResumed) return@evaluateJavascript
                                    isResumed = true
                                    
                                    if (htmlResult != null && htmlResult != "null") {
                                        // JS strings are wrapped in quotes and escaped
                                        val cleanHtml = unescapeJsString(htmlResult)
                                        continuation.resume(cleanHtml)
                                    } else {
                                        continuation.resume(null)
                                    }
                                    
                                    // Clean up
                                    destroyWebView(view)
                                }
                            }, 5000) // 5 seconds for JS-heavy sites (Bloomberg, WSJ) to render content
                        }

                        // Block images, media, AND paywall scripts (matching block_regex)
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest
                        ): WebResourceResponse? {
                            val reqUrl = request.url.toString()
                            val reqUrlLower = reqUrl.lowercase()
                            
                            // Block images and media
                            if (reqUrlLower.endsWith(".jpg") || reqUrlLower.endsWith(".png") || reqUrlLower.endsWith(".gif") || 
                                reqUrlLower.endsWith(".webp") || reqUrlLower.endsWith(".mp4") || reqUrlLower.endsWith(".mp3") ||
                                reqUrlLower.endsWith(".woff") || reqUrlLower.endsWith(".woff2")) {
                                return WebResourceResponse("text/plain", "UTF-8", null)
                            }
                            
                            // Block paywall scripts matching block_regex
                            // This is THE KEY to bypassing paywalls in WebView!
                            // Same as what the browser extension does.
                            if (blockRegex != null && blockRegex.containsMatchIn(reqUrl)) {
                                Timber.d("WebView BLOCKED paywall script: $reqUrl")
                                return WebResourceResponse("text/javascript", "UTF-8", 
                                    "// blocked by bypass".byteInputStream())
                            }
                            
                            // Also block scripts if block_js is enabled for this site
                            if (rule?.blockJs != null && rule.blockJs > 0) {
                                val host = try { java.net.URI(url).host ?: "" } catch (e: Exception) { "" }
                                if (reqUrl.contains(host) && reqUrl.endsWith(".js")) {
                                    Timber.d("WebView BLOCKED site JS: $reqUrl")
                                    return WebResourceResponse("text/javascript", "UTF-8",
                                        "// blocked by bypass".byteInputStream())
                                }
                            }
                            
                            return super.shouldInterceptRequest(view, request)
                        }
                    }

                    // Start loading
                    val additionalHeaders = mutableMapOf<String, String>()
                    
                    if (rule != null) {
                        applyHeaders(rule, additionalHeaders)
                        applyCookies(url, rule)
                    }
                    
                    webView.loadUrl(url, additionalHeaders)

                    continuation.invokeOnCancellation {
                        if (!isResumed) {
                            isResumed = true
                            GlobalScope.launch(Dispatchers.Main) {
                                destroyWebView(webView)
                            }
                        }
                    }
                }
            }
            
            if (resultHtml == null) {
                Timber.w("HeadlessWebView timed out or failed for $url")
                // Cleanup in case of timeout
                destroyWebView(webView)
            } else {
                Timber.d("HeadlessWebView successfully fetched DOM for $url (${resultHtml.length} bytes)")
            }
            
            resultHtml
        }
    }

    /**
     * Resolves a redirect URL (e.g. Google News JS-based redirect) by loading it in a WebView
     * and capturing the final URL after all redirects complete.
     * Much lighter than fetchHtml — only captures the URL, not the DOM.
     */
    suspend fun resolveRedirectUrl(url: String, timeoutMs: Long = 10000L): String? {
        return withContext(Dispatchers.Main) {
            val webView = WebView(context)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.loadsImagesAutomatically = false
            webView.settings.blockNetworkImage = true
            webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
            webView.settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

            val resolvedUrl = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<String?> { continuation ->
                    var isResumed = false

                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val newUrl = request.url.toString()
                            Timber.d("resolveRedirectUrl: redirect to $newUrl")
                            // If we've left Google's domain, we found the real URL
                            if (!newUrl.contains("google.com") && !newUrl.contains("google.co") && !isResumed) {
                                isResumed = true
                                GlobalScope.launch(Dispatchers.Main) { destroyWebView(view) }
                                continuation.resume(newUrl)
                                return true // Don't load the real article page
                            }
                            return false // Continue loading (still on Google)
                        }

                        override fun onPageFinished(view: WebView, finishedUrl: String) {
                            super.onPageFinished(view, finishedUrl)
                            // If page finished and URL changed to non-Google, capture it
                            if (!finishedUrl.contains("google.com") && !finishedUrl.contains("google.co") && !isResumed) {
                                isResumed = true
                                GlobalScope.launch(Dispatchers.Main) { destroyWebView(view) }
                                continuation.resume(finishedUrl)
                            }
                        }

                        // Block all media to speed up redirect
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val reqUrl = request.url.toString().lowercase()
                            if (reqUrl.endsWith(".jpg") || reqUrl.endsWith(".png") || reqUrl.endsWith(".gif") ||
                                reqUrl.endsWith(".webp") || reqUrl.endsWith(".css") || reqUrl.endsWith(".woff") ||
                                reqUrl.endsWith(".woff2") || reqUrl.endsWith(".mp4") || reqUrl.endsWith(".mp3")) {
                                return WebResourceResponse("text/plain", "UTF-8", null)
                            }
                            return super.shouldInterceptRequest(view, request)
                        }
                    }

                    webView.loadUrl(url)

                    continuation.invokeOnCancellation {
                        if (!isResumed) {
                            isResumed = true
                            GlobalScope.launch(Dispatchers.Main) { destroyWebView(webView) }
                        }
                    }
                }
            }

            if (resolvedUrl == null) {
                Timber.w("resolveRedirectUrl timed out for $url")
                GlobalScope.launch(Dispatchers.Main) { destroyWebView(webView) }
            } else {
                Timber.d("resolveRedirectUrl: $url -> $resolvedUrl")
            }

            resolvedUrl
        }
    }

    private fun createWebView(rule: SiteRule?): WebView {
        val webView = WebView(context)
        
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.loadsImagesAutomatically = false
        settings.blockNetworkImage = true
        
        // Cache settings
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        
        // Apply Global User Agent rule if provided
        if (rule != null) {
            when (rule.useragent) {
                "googlebot" -> settings.userAgentString = UA_GOOGLEBOT
                "bingbot" -> settings.userAgentString = UA_BINGBOT
                "facebookbot" -> settings.userAgentString = UA_FACEBOOKBOT
                else -> {
                    if (!rule.useragentCustom.isNullOrEmpty()) {
                        settings.userAgentString = rule.useragentCustom
                    } else {
                        // Pretend to be a normal mobile chrome browser to avoid bot detection
                        settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 6 Build/Tiramisu) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
                    }
                }
            }
        }
        
        return webView
    }

    private fun applyHeaders(rule: SiteRule, headers: MutableMap<String, String>) {
        if (rule.useragent == "googlebot") {
            headers["Referer"] = "https://www.google.com/"
            headers["X-Forwarded-For"] = "66.249.66.1"
        } else {
            when (rule.referer) {
                "google" -> headers["Referer"] = "https://www.google.com/"
                "facebook" -> headers["Referer"] = "https://www.facebook.com/"
                "twitter" -> headers["Referer"] = "https://t.co/"
                else -> {
                    if (!rule.refererCustom.isNullOrEmpty()) {
                        headers["Referer"] = rule.refererCustom
                    }
                }
            }
        }

        rule.headersCustom?.forEach { (k, v) ->
            headers[k] = v
        }
    }

    private fun applyCookies(url: String, rule: SiteRule) {
        val cookieManager = CookieManager.getInstance()
        
        val shouldClearCookies = when {
            rule.removeCookiesSelectDrop != null || rule.removeCookiesSelectHold != null -> false
            rule.allowCookies != null && rule.allowCookies > 0 -> false
            rule.removeCookies != null && rule.removeCookies > 0 -> true
            else -> true // Default: clear cookies
        }

        if (shouldClearCookies) {
            cookieManager.removeAllCookies(null)
            cookieManager.flush()
        }
    }

    private fun unescapeJsString(str: String): String {
        var s = str
        if (s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length - 1)
        }
        return s.replace("\\u003C", "<")
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\\\", "\\")
    }

    private fun destroyWebView(webView: WebView) {
        webView.stopLoading()
        webView.clearHistory()
        webView.clearCache(true)
        webView.loadUrl("about:blank")
        webView.removeAllViews()
        webView.destroy()
    }
}
