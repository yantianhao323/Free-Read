package me.ash.reader.domain.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.ash.reader.domain.model.bypass.RuleManager
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for updating bypass rules from the BPC (Bypass Paywalls Clean) repository.
 * Falls back to keeping the current version if the repo is unavailable.
 */
@Singleton
class RuleUpdateService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val ruleManager: RuleManager,
) {
    companion object {
        // BPC update URL from gitflic.ru
        private const val RULES_UPDATE_URL =
            "https://gitflic.ru/project/magnolia1234/bpc_updates/blob/raw?file=sites_updated.json"
        private const val VERSION_FILE_NAME = "rules_version.txt"
    }

    /**
     * Check and download rule updates.
     * Returns true if rules were updated, false otherwise.
     */
    suspend fun checkAndUpdate(): Boolean = withContext(Dispatchers.IO) {
        try {
            Timber.d("Checking for bypass rule updates...")

            val request = Request.Builder()
                .url(RULES_UPDATE_URL)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()

            val response = okHttpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                Timber.w("Rule update check failed: HTTP ${response.code}")
                return@withContext false
            }

            val newRulesJson = response.body?.string()
            if (newRulesJson.isNullOrBlank()) {
                Timber.w("Rule update returned empty body")
                return@withContext false
            }

            // Compare with current version (use content hash)
            val newHash = newRulesJson.hashCode().toString()
            val versionFile = File(context.filesDir, VERSION_FILE_NAME)
            val currentHash = if (versionFile.exists()) versionFile.readText() else ""

            if (newHash == currentHash) {
                Timber.d("Bypass rules are already up to date")
                return@withContext false
            }

            // Save updated rules
            ruleManager.saveUpdatedRules(newRulesJson)
            versionFile.writeText(newHash)

            Timber.d("Bypass rules updated successfully")
            return@withContext true
        } catch (e: Exception) {
            // Silently fail — keep current version
            Timber.w(e, "Failed to update bypass rules, keeping current version")
            return@withContext false
        }
    }
}
