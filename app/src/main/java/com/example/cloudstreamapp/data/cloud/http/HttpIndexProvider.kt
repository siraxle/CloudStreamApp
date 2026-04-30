package com.example.cloudstreamapp.data.cloud.http

import com.example.cloudstreamapp.data.cloud.CloudProvider
import com.example.cloudstreamapp.domain.model.CloudItem
import com.example.cloudstreamapp.domain.model.CloudPath
import com.example.cloudstreamapp.domain.model.CloudResult
import com.example.cloudstreamapp.domain.model.CloudType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID
import javax.inject.Inject

class HttpIndexProvider @Inject constructor(
    private val okHttpClient: OkHttpClient,
) : CloudProvider {

    override val type = CloudType.HTTP

    override fun isSupported(url: String): Boolean =
        url.startsWith("http://") || url.startsWith("https://")

    override suspend fun resolve(url: String): CloudResult = runCatching {
        val path = CloudPath(sourceId = url, relativePath = url, cloudType = CloudType.HTTP)
        if (url.trimEnd('/').contains('.').not() || url.endsWith("/")) {
            CloudResult.FolderResult(path = path, items = emptyList())
        } else {
            val item = CloudItem(
                id = UUID.nameUUIDFromBytes(url.toByteArray()).toString(),
                name = url.substringAfterLast('/'),
                path = path,
                type = CloudItem.ItemType.FILE,
            )
            CloudResult.FileResult(item = item, streamUrl = url)
        }
    }.getOrElse { CloudResult.Error(it.message ?: "HTTP resolve failed", it) }

    override fun listFolder(path: CloudPath): Flow<List<CloudItem>> = flow {
        val request = Request.Builder().url(path.relativePath).build()
        val html = okHttpClient.newCall(request).execute().use { it.body?.string() ?: "" }
        emit(HttpIndexParser.parse(html, path.relativePath, path))
    }

    override suspend fun getStreamUrl(item: CloudItem): String = item.path.relativePath
}
