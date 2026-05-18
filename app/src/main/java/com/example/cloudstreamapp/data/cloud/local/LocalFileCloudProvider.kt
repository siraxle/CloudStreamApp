package com.example.cloudstreamapp.data.cloud.local

import com.example.cloudstreamapp.data.cloud.CloudProvider
import com.example.cloudstreamapp.domain.model.CloudItem
import com.example.cloudstreamapp.domain.model.CloudPath
import com.example.cloudstreamapp.domain.model.CloudResult
import com.example.cloudstreamapp.domain.model.CloudType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CloudProvider for locally downloaded files.
 * [CloudPath.sourceId] = absolute file path (e.g. /storage/emulated/0/Android/…/file.mp3).
 * Stream URL is a file:// URI derived directly from sourceId — no network required.
 */
@Singleton
class LocalFileCloudProvider @Inject constructor() : CloudProvider {

    override val type: CloudType = CloudType.LOCAL

    // LOCAL items are never resolved via URL — they come from TorrentDownloadManager
    override fun isSupported(url: String): Boolean = false

    override suspend fun resolve(url: String): CloudResult =
        CloudResult.Error("LOCAL provider does not support URL resolution")

    override fun listFolder(path: CloudPath): Flow<List<CloudItem>> {
        val dir = java.io.File(path.sourceId)
        if (!dir.isDirectory) return flowOf(emptyList())
        return flowOf(walkDir(dir))
    }

    private fun walkDir(dir: java.io.File): List<CloudItem> {
        val result = mutableListOf<CloudItem>()
        dir.listFiles()?.sortedBy { it.name.lowercase() }?.forEach { f ->
            when {
                f.isFile -> result += CloudItem(
                    id = f.absolutePath,
                    name = f.name,
                    path = CloudPath(
                        sourceId = f.absolutePath,
                        relativePath = f.absolutePath,
                        cloudType = CloudType.LOCAL,
                    ),
                    type = CloudItem.ItemType.FILE,
                    sizeBytes = f.length(),
                )
                f.isDirectory -> result += walkDir(f)
            }
        }
        return result
    }

    override suspend fun getStreamUrl(item: CloudItem): String {
        val path = item.path.sourceId
        return if (path.startsWith("file://")) path else "file://$path"
    }
}
