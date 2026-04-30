package com.example.cloudstreamapp.domain.usecase

import com.example.cloudstreamapp.domain.model.CloudResult
import com.example.cloudstreamapp.domain.model.CloudSource
import com.example.cloudstreamapp.domain.model.CloudType
import com.example.cloudstreamapp.domain.port.SourceRepositoryPort
import java.util.UUID
import javax.inject.Inject

class AddSourceUseCase @Inject constructor(
    private val resolveUrl: ResolveUrlUseCase,
    private val sourceRepo: SourceRepositoryPort,
) {
    sealed class Result {
        data class Success(val source: CloudSource) : Result()
        data class AlreadyExists(val source: CloudSource) : Result()
        data class Error(val message: String) : Result()
    }

    suspend operator fun invoke(url: String, customName: String? = null): Result {
        val existing = sourceRepo.getByUrl(url)
        if (existing != null) return Result.AlreadyExists(existing)

        val resolved = resolveUrl(url)
        if (resolved is CloudResult.Error) return Result.Error(resolved.message)

        val providerType: CloudType = when (resolved) {
            is CloudResult.FolderResult -> resolved.path.cloudType
            is CloudResult.FileResult -> resolved.item.path.cloudType
            is CloudResult.Error -> return Result.Error(resolved.message)
        }

        val source = CloudSource(
            id = UUID.randomUUID().toString(),
            url = url,
            name = customName,
            provider = providerType,
            addedAt = System.currentTimeMillis(),
            lastSync = System.currentTimeMillis(),
        )
        sourceRepo.add(source)
        return Result.Success(source)
    }
}
