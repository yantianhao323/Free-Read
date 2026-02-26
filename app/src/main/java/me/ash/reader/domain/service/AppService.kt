package me.ash.reader.domain.service

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import me.ash.reader.R
import me.ash.reader.domain.model.general.toVersion
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.di.MainDispatcher
import me.ash.reader.infrastructure.net.Download
import me.ash.reader.infrastructure.net.NetworkDataSource
import me.ash.reader.infrastructure.net.downloadToFileWithProgress
import me.ash.reader.infrastructure.preference.*
import me.ash.reader.infrastructure.preference.NewVersionSizePreference.formatSize
import me.ash.reader.ui.ext.getCurrentVersion
import me.ash.reader.ui.ext.getLatestApk
import me.ash.reader.ui.ext.showToast
import me.ash.reader.ui.ext.skipVersionNumber
import javax.inject.Inject

class AppService @Inject constructor(
    @ApplicationContext
    private val context: Context,
    private val networkDataSource: NetworkDataSource,
    @IODispatcher
    private val ioDispatcher: CoroutineDispatcher,
    @MainDispatcher
    private val mainDispatcher: CoroutineDispatcher,
) {

    suspend fun checkUpdate(showToast: Boolean = true): Boolean? = withContext(ioDispatcher) {
        return@withContext null
    }

    suspend fun downloadFile(url: String): Flow<Download> =
        withContext(ioDispatcher) {
            Log.i("RLog", "downloadFile start: $url")
            try {
                return@withContext networkDataSource.downloadFile(url)
                    .downloadToFileWithProgress(context.getLatestApk())
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e("RLog", "downloadFile: ${e.message}")
                withContext(mainDispatcher) {
                    context.showToast(context.getString(R.string.download_failure))
                }
            }
            emptyFlow()
        }
}
