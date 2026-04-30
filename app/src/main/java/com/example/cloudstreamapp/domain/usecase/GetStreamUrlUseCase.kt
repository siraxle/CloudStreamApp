package com.example.cloudstreamapp.domain.usecase

import com.example.cloudstreamapp.data.cloud.CloudProviderRegistry
import com.example.cloudstreamapp.domain.model.CloudItem
import javax.inject.Inject

class GetStreamUrlUseCase @Inject constructor(
    private val registry: CloudProviderRegistry,
) {
    suspend operator fun invoke(item: CloudItem): String? {
        return registry.forType(item.path.cloudType)?.getStreamUrl(item)
    }
}
