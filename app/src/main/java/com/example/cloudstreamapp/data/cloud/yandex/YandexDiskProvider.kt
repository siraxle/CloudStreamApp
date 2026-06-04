package com.example.cloudstreamapp.data.cloud.yandex

import com.example.cloudstreamapp.data.cloud.CloudProvider
import com.example.cloudstreamapp.domain.model.CacheStatus
import com.example.cloudstreamapp.domain.model.CloudItem
import com.example.cloudstreamapp.domain.model.CloudPath
import com.example.cloudstreamapp.domain.model.CloudResult
import com.example.cloudstreamapp.domain.model.CloudType
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.UUID
import javax.inject.Inject

class YandexDiskProvider @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val gson: Gson,
) : CloudProvider {

    override val type = CloudType.YANDEX

    private val apiBase = "https://cloud-api.yandex.net/v1/disk/public/resources"

    override fun isSupported(url: String): Boolean =
        url.contains("disk.yandex.ru/d/") ||
        url.contains("disk.yandex.com/d/") ||
        url.contains("yadi.sk/d/")

    override suspend fun resolve(url: String): CloudResult {
        val path = CloudPath(sourceId = url, relativePath = "/", cloudType = CloudType.YANDEX)
        return CloudResult.FolderResult(path = path, items = emptyList())
    }

    override fun listFolder(path: CloudPath): Flow<List<CloudItem>> = flow {
        val publicKey = URLEncoder.encode(path.sourceId, "UTF-8")
        // "root" and "/" both mean the root of the shared folder
        val relPath = if (path.relativePath == "root" || path.relativePath == "/") {
            "/"
        } else {
            path.relativePath
        }
        val encodedPath = URLEncoder.encode(relPath, "UTF-8")

        val url = "$apiBase?public_key=$publicKey&path=$encodedPath&limit=100&preview_size=L"
        val request = Request.Builder().url(url).build()

        val body = okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Yandex API error ${response.code}: ${response.body?.string()}")
            }
            response.body?.string() ?: error("Empty response from Yandex API")
        }

        val response = gson.fromJson(body, YandexResponse::class.java)
        val items = response.embedded?.items?.map { it.toCloudItem(path.sourceId) } ?: emptyList()
        emit(items)
    }.flowOn(Dispatchers.IO)

    override suspend fun getStreamUrl(item: CloudItem): String {
        val publicKey = URLEncoder.encode(item.path.sourceId, "UTF-8")
        val encodedPath = URLEncoder.encode(item.path.relativePath, "UTF-8")
        val request = Request.Builder()
            .url("$apiBase/download?public_key=$publicKey&path=$encodedPath")
            .build()
        val body = okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Yandex download error ${response.code}")
            response.body?.string() ?: error("Empty download response")
        }
        return gson.fromJson(body, YandexDownloadResponse::class.java).href
            ?: error("No download URL for ${item.name}")
    }

    private data class YandexResponse(
        @SerializedName("_embedded") val embedded: YandexEmbedded?,
    )

    private data class YandexEmbedded(val items: List<YandexItem>?)

    private data class YandexItem(
        val name: String,
        val type: String,
        val path: String,
        val size: Long?,
        val mime_type: String?,
        val preview: String?,
    ) {
        fun toCloudItem(sourceId: String) = CloudItem(
            id = UUID.nameUUIDFromBytes("$sourceId:$path".toByteArray()).toString(),
            name = name,
            path = CloudPath(sourceId = sourceId, relativePath = path, cloudType = CloudType.YANDEX),
            type = if (type == "dir") CloudItem.ItemType.DIRECTORY else CloudItem.ItemType.FILE,
            mimeType = mime_type,
            sizeBytes = size,
            thumbnailUrl = preview,
            cacheStatus = CacheStatus.REMOTE,
        )
    }

    private data class YandexDownloadResponse(val href: String?)
}
