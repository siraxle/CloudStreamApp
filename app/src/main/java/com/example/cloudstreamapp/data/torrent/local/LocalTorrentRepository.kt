package com.example.cloudstreamapp.data.torrent.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalTorrentRepository @Inject constructor(
    private val dao: LocalTorrentDao,
    @ApplicationContext private val context: Context,
) {
    private val torrentsDir = File(context.filesDir, "local_torrents").also { it.mkdirs() }

    fun getAll(): Flow<List<LocalTorrentEntity>> = dao.getAll()

    suspend fun findByInfoHash(infoHash: String): LocalTorrentEntity? =
        dao.findByInfoHash(infoHash)

    suspend fun save(infoHash: String, torrentName: String, fileName: String, bytes: ByteArray) {
        File(torrentsDir, "$infoHash.torrent").writeBytes(bytes)
        dao.insert(LocalTorrentEntity(infoHash = infoHash, torrentName = torrentName, fileName = fileName))
    }

    fun getBytes(infoHash: String): ByteArray? = try {
        File(torrentsDir, "$infoHash.torrent").takeIf { it.exists() }?.readBytes()
    } catch (_: Exception) { null }

    suspend fun delete(infoHash: String) {
        File(torrentsDir, "$infoHash.torrent").delete()
        dao.deleteByInfoHash(infoHash)
    }
}
