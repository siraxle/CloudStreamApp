package com.example.cloudstreamapp.data.cloud.gdrive

import com.example.cloudstreamapp.data.cloud.CloudProvider
import com.example.cloudstreamapp.domain.model.CloudItem
import com.example.cloudstreamapp.domain.model.CloudPath
import com.example.cloudstreamapp.domain.model.CloudResult
import com.example.cloudstreamapp.domain.model.CloudType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import javax.inject.Inject

class GoogleDriveProvider @Inject constructor(
    private val okHttpClient: OkHttpClient,
) : CloudProvider {

    override val type = CloudType.GDRIVE

    private val folderRegex = Regex("drive\\.google\\.com/drive/folders/([a-zA-Z0-9_-]+)")
    private val fileRegex = Regex("drive\\.google\\.com/file/d/([a-zA-Z0-9_-]+)")

    override fun isSupported(url: String): Boolean = url.contains("drive.google.com")

    override suspend fun resolve(url: String): CloudResult {
        val folderId = folderRegex.find(url)?.groupValues?.get(1)
        val fileId = fileRegex.find(url)?.groupValues?.get(1)

        return when {
            folderId != null -> CloudResult.FolderResult(
                path = CloudPath(sourceId = folderId, relativePath = "/", cloudType = CloudType.GDRIVE),
                items = emptyList(),
            )
            fileId != null -> {
                val item = CloudItem(
                    id = fileId,
                    name = "Google Drive File",
                    path = CloudPath(sourceId = fileId, relativePath = "/", cloudType = CloudType.GDRIVE),
                    type = CloudItem.ItemType.FILE,
                )
                CloudResult.FileResult(item = item, streamUrl = directDownloadUrl(fileId))
            }
            else -> CloudResult.Error("Unrecognized Google Drive URL: $url")
        }
    }

    override fun listFolder(path: CloudPath): Flow<List<CloudItem>> = flow {
        // Google Drive HTML scraping deferred to Phase 3 due to anti-scraping measures
        emit(emptyList())
    }

    override suspend fun getStreamUrl(item: CloudItem): String = directDownloadUrl(item.id)

    private fun directDownloadUrl(fileId: String) =
        "https://drive.google.com/uc?export=download&id=$fileId"
}
