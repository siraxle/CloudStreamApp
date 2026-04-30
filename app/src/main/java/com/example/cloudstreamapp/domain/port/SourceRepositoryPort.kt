package com.example.cloudstreamapp.domain.port

import com.example.cloudstreamapp.domain.model.CloudSource
import kotlinx.coroutines.flow.Flow

interface SourceRepositoryPort {
    fun getAll(): Flow<List<CloudSource>>
    suspend fun getById(id: String): CloudSource?
    suspend fun getByUrl(url: String): CloudSource?
    suspend fun add(source: CloudSource)
    suspend fun update(source: CloudSource)
    suspend fun delete(id: String)
}
