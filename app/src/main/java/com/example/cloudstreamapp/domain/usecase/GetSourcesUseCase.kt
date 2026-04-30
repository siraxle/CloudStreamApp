package com.example.cloudstreamapp.domain.usecase

import com.example.cloudstreamapp.domain.model.CloudSource
import com.example.cloudstreamapp.domain.port.SourceRepositoryPort
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetSourcesUseCase @Inject constructor(
    private val sourceRepo: SourceRepositoryPort,
) {
    operator fun invoke(): Flow<List<CloudSource>> = sourceRepo.getAll()
}
