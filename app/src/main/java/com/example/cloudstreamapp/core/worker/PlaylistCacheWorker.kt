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
import com.example.cloudstreamapp.domain.model.CloudItem
import com.example.cloudstreamapp.domain.port.SettingsRepositoryPort
import com.example.cloudstreamapp.domain.usecase.GetStreamUrlUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

        var processed = 0

        for (batch in toDownload.chunked(PARALLEL_DOWNLOADS)) {
            if (isStopped) break
            if (simpleCache.cacheSpace >= cacheLimit) break

            // Download batch items in parallel; wait for all before next batch
            coroutineScope {
                batch.forEach { cloudItem ->
                    launch(Dispatchers.IO) {
                        if (isStopped) return@launch
                        val fileSize = cloudItem.sizeBytes ?: 0L
                        if (fileSize > 0 && simpleCache.cacheSpace + fileSize > cacheLimit) return@launch
                        try {
                            val url = getStreamUrl(cloudItem) ?: return@launch
                            downloadToCache(cloudItem.id, url)
                        } catch (_: Exception) {
                            // Skip file on error, continue with the rest
                        }
                    }
                }
            }

            processed += batch.size
            setProgress(workDataOf(PROGRESS_INDEX to processed, PROGRESS_TOTAL to toDownload.size))
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
        const val PROGRESS_TOTAL = "progress_total"
        private const val PARALLEL_DOWNLOADS = 3

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
