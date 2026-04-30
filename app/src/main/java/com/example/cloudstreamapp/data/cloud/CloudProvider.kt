package com.example.cloudstreamapp.data.cloud

import com.example.cloudstreamapp.domain.model.CloudItem
import com.example.cloudstreamapp.domain.model.CloudPath
import com.example.cloudstreamapp.domain.model.CloudResult
import com.example.cloudstreamapp.domain.model.CloudType
import kotlinx.coroutines.flow.Flow

interface CloudProvider {
    val type: CloudType

    suspend fun resolve(url: String): CloudResult
    fun listFolder(path: CloudPath): Flow<List<CloudItem>>
    suspend fun getStreamUrl(item: CloudItem): String
    fun isSupported(url: String): Boolean
}
