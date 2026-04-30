package com.example.cloudstreamapp.data.cloud.onedrive

import com.example.cloudstreamapp.data.cloud.CloudProvider
import com.example.cloudstreamapp.domain.model.CloudItem
import com.example.cloudstreamapp.domain.model.CloudPath
import com.example.cloudstreamapp.domain.model.CloudResult
import com.example.cloudstreamapp.domain.model.CloudType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import javax.inject.Inject

class OneDriveProvider @Inject constructor(
    private val okHttpClient: OkHttpClient,
) : CloudProvider {

    override val type = CloudType.ONEDRIVE

    override fun isSupported(url: String): Boolean =
        url.contains("1drv.ms/") || url.contains("onedrive.live.com/share")

    override suspend fun resolve(url: String): CloudResult {
        val path = CloudPath(sourceId = url, relativePath = "/", cloudType = CloudType.ONEDRIVE)
        return CloudResult.FolderResult(path = path, items = emptyList())
    }

    override fun listFolder(path: CloudPath): Flow<List<CloudItem>> = flow {
        // OneDrive OData listing deferred to Phase 3
        emit(emptyList())
    }

    override suspend fun getStreamUrl(item: CloudItem): String = item.path.relativePath
}
