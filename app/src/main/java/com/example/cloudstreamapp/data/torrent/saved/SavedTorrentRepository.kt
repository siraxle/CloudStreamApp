package com.example.cloudstreamapp.data.torrent.saved

import com.example.cloudstreamapp.domain.torrent.TorrentResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SavedTorrentRepository @Inject constructor(private val dao: SavedTorrentDao) {

    fun getAll(): Flow<List<SavedTorrentEntity>> = dao.getAll()

    fun getAllHashes(): Flow<Set<String>> = dao.getAllHashes().map { it.toSet() }

    suspend fun save(result: TorrentResult) {
        dao.insert(
            SavedTorrentEntity(
                infoHash = result.infoHash,
                name = result.name,
                magnetUri = result.magnetUri,
                sizeBytes = result.sizeBytes,
                seeders = result.seeders,
                leechers = result.leechers,
                source = result.source,
            )
        )
    }

    suspend fun delete(infoHash: String) = dao.deleteByInfoHash(infoHash)
}
