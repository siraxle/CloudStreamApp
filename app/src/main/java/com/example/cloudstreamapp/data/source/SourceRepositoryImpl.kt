package com.example.cloudstreamapp.data.source

import com.example.cloudstreamapp.core.database.dao.SourceDao
import com.example.cloudstreamapp.core.database.entity.SourceEntity
import com.example.cloudstreamapp.domain.model.CloudSource
import com.example.cloudstreamapp.domain.model.CloudType
import com.example.cloudstreamapp.domain.port.SourceRepositoryPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SourceRepositoryImpl @Inject constructor(
    private val dao: SourceDao,
) : SourceRepositoryPort {

    override fun getAll(): Flow<List<CloudSource>> =
        dao.getAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getById(id: String): CloudSource? = dao.getById(id)?.toDomain()

    override suspend fun getByUrl(url: String): CloudSource? = dao.getByUrl(url)?.toDomain()

    override suspend fun add(source: CloudSource) = dao.insert(source.toEntity())

    override suspend fun update(source: CloudSource) = dao.update(source.toEntity())

    override suspend fun delete(id: String) = dao.deleteById(id)

    private fun SourceEntity.toDomain() = CloudSource(
        id = id,
        url = url,
        name = name,
        provider = CloudType.valueOf(provider),
        addedAt = addedAt,
        lastSync = lastSync,
        isPinned = isPinned == 1,
    )

    private fun CloudSource.toEntity() = SourceEntity(
        id = id,
        url = url,
        name = name,
        provider = provider.name,
        addedAt = addedAt,
        lastSync = lastSync,
        isPinned = if (isPinned) 1 else 0,
    )
}
