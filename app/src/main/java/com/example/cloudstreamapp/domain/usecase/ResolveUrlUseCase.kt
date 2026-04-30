package com.example.cloudstreamapp.domain.usecase

import com.example.cloudstreamapp.data.cloud.CloudProviderRegistry
import com.example.cloudstreamapp.domain.model.CloudResult
import javax.inject.Inject

class ResolveUrlUseCase @Inject constructor(
    private val registry: CloudProviderRegistry,
) {
    suspend operator fun invoke(url: String): CloudResult {
        val provider = registry.forUrl(url)
            ?: return CloudResult.Error("No provider found for URL: $url")
        return provider.resolve(url)
    }
}
