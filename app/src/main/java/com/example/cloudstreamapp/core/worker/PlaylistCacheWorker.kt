package com.example.cloudstreamapp.core.worker

import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.cloudstreamapp.data.playlist.PlaylistRepositoryImpl
import com.example.cloudstreamapp.domain.model.CacheStatus
import com.example.cloudstreamapp.domain.port.SettingsRepositoryPort
import com.example.cloudstreamapp.domain.usecase.GetStreamUrlUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

@HiltWorker
class PlaylistCacheWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val playlistRepo: PlaylistRepositoryImpl,
    private val getStreamUrl: GetStreamUrlUseCase,
    private val simpleCache: SimpleCache,
    private val okHttpClient: OkHttpClient,
    private val settingsRepo: SettingsRepositoryPort,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val playlistId = inputData.getString(KEY_PLAYLIST_ID) ?: return Result.failure()
        val cacheLimit = settingsRepo.cacheLimitBytes.first()

        val items = playlistRepo.getItemsWithMetadata(playlistId).first()
        val toDownload = items
            .mapNotNull { (_, cloudItem) -> cloudItem }
            .filter { it.cacheStatus != CacheStatus.CACHED }

        for ((index, cloudItem) in toDownload.withIndex()) {
            if (isStopped) break

            // Stop if adding this file would exceed the cache limit
            val usedBytes = simpleCache.cacheSpace
            val fileSize = cloudItem.sizeBytes ?: 0L
            if (fileSize > 0 && usedBytes + fileSize > cacheLimit) break

            try {
                val url = getStreamUrl(cloudItem) ?: continue
                downloadToCache(cloudItem.id, url)
            } catch (_: Exception) {
                // Skip track on error, continue with the next one
            }

            setProgress(workDataOf(PROGRESS_INDEX to index))
        }

        return Result.success()
    }

    private suspend fun downloadToCache(key: String, url: String) = withContext(Dispatchers.IO) {
        val dataSource = CacheDataSource.Factory()
            .setCache(simpleCache)
            .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(okHttpClient))
            .createDataSource()

        val dataSpec = DataSpec.Builder()
            .setUri(Uri.parse(url))
            .setKey(key)
            .build()

        val buffer = ByteArray(128 * 1024)
        dataSource.open(dataSpec)
        try {
            while (!isStopped) {
                val read = dataSource.read(buffer, 0, buffer.size)
                if (read == C.RESULT_END_OF_INPUT) break
            }
        } finally {
            dataSource.close()
        }
    }

    companion object {
        const val KEY_PLAYLIST_ID = "playlist_id"
        const val PROGRESS_INDEX = "progress_index"

        fun workName(playlistId: String) = "playlist_cache_$playlistId"

        fun buildRequest(playlistId: String, wifiOnly: Boolean): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<PlaylistCacheWorker>()
                .setInputData(workDataOf(KEY_PLAYLIST_ID to playlistId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                        )
                        .build()
                )
                .build()
    }
}
