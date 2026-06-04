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
import com.example.cloudstreamapp.core.database.dao.MediaMetadataDao
import com.example.cloudstreamapp.domain.model.CloudItem
import com.example.cloudstreamapp.domain.model.CloudPath
import com.example.cloudstreamapp.domain.model.CloudType
import com.example.cloudstreamapp.domain.port.SettingsRepositoryPort
import com.example.cloudstreamapp.domain.usecase.GetStreamUrlUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

@HiltWorker
class SingleFileCacheWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val metadataDao: MediaMetadataDao,
    private val getStreamUrl: GetStreamUrlUseCase,
    private val simpleCache: SimpleCache,
    private val okHttpClient: OkHttpClient,
    private val settingsRepo: SettingsRepositoryPort,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val mediaId = inputData.getString(KEY_MEDIA_ID) ?: return Result.failure()
        val entity = metadataDao.getById(mediaId) ?: return Result.failure()

        val sizeBytes = entity.sizeBytes
        if (sizeBytes != null && simpleCache.getCachedBytes(mediaId, 0, sizeBytes) >= sizeBytes) {
            return Result.success()
        }

        // Enforce 2 GB limit
        val cacheLimit = settingsRepo.cacheLimitBytes.first()
        val usedBytes = simpleCache.cacheSpace
        if (usedBytes >= cacheLimit) return Result.success()
        if (sizeBytes != null && usedBytes + sizeBytes > cacheLimit) return Result.success()

        val cloudItem = CloudItem(
            id = entity.id,
            name = entity.title ?: entity.path.substringAfterLast('/'),
            path = CloudPath(
                sourceId = entity.sourceId,
                relativePath = entity.path,
                cloudType = runCatching { CloudType.valueOf(entity.cloudType) }.getOrDefault(CloudType.HTTP),
            ),
            type = CloudItem.ItemType.FILE,
            mimeType = entity.mimeType,
            sizeBytes = entity.sizeBytes,
            durationMs = entity.durationMs,
        )

        return try {
            val url = getStreamUrl(cloudItem) ?: return Result.failure()
            val downloadedBytes = downloadToCache(mediaId, url)
            if (entity.sizeBytes == null && downloadedBytes > 0) {
                metadataDao.updateSizeBytes(mediaId, downloadedBytes)
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    // Returns Content-Length if available, otherwise bytes actually read.
    private suspend fun downloadToCache(key: String, url: String): Long = withContext(Dispatchers.IO) {
        val dataSource = CacheDataSource.Factory()
            .setCache(simpleCache)
            .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(okHttpClient))
            .createDataSource()

        val dataSpec = DataSpec.Builder()
            .setUri(Uri.parse(url))
            .setKey(key)
            .build()

        val buffer = ByteArray(128 * 1024)
        val declaredLength = dataSource.open(dataSpec)
        var bytesRead = 0L
        try {
            while (!isStopped) {
                val read = dataSource.read(buffer, 0, buffer.size)
                if (read == C.RESULT_END_OF_INPUT) break
                if (read > 0) bytesRead += read
            }
        } finally {
            dataSource.close()
        }
        if (declaredLength > 0) declaredLength else bytesRead
    }

    companion object {
        const val KEY_MEDIA_ID = "media_id"

        fun workName(mediaId: String) = "file_cache_$mediaId"

        fun buildRequest(mediaId: String): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<SingleFileCacheWorker>()
                .setInputData(workDataOf(KEY_MEDIA_ID to mediaId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
    }
}
