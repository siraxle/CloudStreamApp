package com.example.cloudstreamapp.data.cloud.dropbox

import com.example.cloudstreamapp.data.cloud.CloudProvider
import com.example.cloudstreamapp.domain.model.CloudItem
import com.example.cloudstreamapp.domain.model.CloudPath
import com.example.cloudstreamapp.domain.model.CloudResult
import com.example.cloudstreamapp.domain.model.CloudType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import java.util.UUID
import javax.inject.Inject

class DropboxProvider @Inject constructor(
    private val okHttpClient: OkHttpClient,
) : CloudProvider {

    override val type = CloudType.DROPBOX

    override fun isSupported(url: String): Boolean = url.contains("dropbox.com/")

    override suspend fun resolve(url: String): CloudResult = runCatching {
        val directUrl = toDirectUrl(url)
        val item = CloudItem(
            id = UUID.nameUUIDFromBytes(url.toByteArray()).toString(),
            name = url.substringAfterLast('/').substringBefore('?'),
            path = CloudPath(sourceId = url, relativePath = directUrl, cloudType = CloudType.DROPBOX),
            type = CloudItem.ItemType.FILE,
        )
        CloudResult.FileResult(item = item, streamUrl = directUrl)
    }.getOrElse { CloudResult.Error(it.message ?: "Dropbox resolve failed", it) }

    override fun listFolder(path: CloudPath): Flow<List<CloudItem>> = flow {
        // Dropbox folder listing via API v2 deferred to Phase 2
        emit(emptyList())
    }

    override suspend fun getStreamUrl(item: CloudItem): String = toDirectUrl(item.path.sourceId)

    private fun toDirectUrl(shareUrl: String): String =
        shareUrl
            .replace("www.dropbox.com", "dl.dropboxusercontent.com")
            .replace("?dl=0", "?dl=1")
            .let { if (!it.contains("dl=1")) "$it?dl=1" else it }
}
