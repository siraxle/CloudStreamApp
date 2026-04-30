package com.example.cloudstreamapp.domain.usecase

import com.example.cloudstreamapp.data.cloud.CloudProviderRegistry
import com.example.cloudstreamapp.domain.model.CloudItem
import com.example.cloudstreamapp.domain.model.CloudPath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

class ListFolderUseCase @Inject constructor(
    private val registry: CloudProviderRegistry,
) {
    operator fun invoke(path: CloudPath): Flow<List<CloudItem>> {
        val provider = registry.forType(path.cloudType) ?: return flowOf(emptyList())

        return provider.listFolder(path)
    }
}
