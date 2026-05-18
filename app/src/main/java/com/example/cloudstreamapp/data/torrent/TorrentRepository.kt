package com.example.cloudstreamapp.data.torrent

import com.example.cloudstreamapp.data.torrent.provider.NyaaProvider
import com.example.cloudstreamapp.data.torrent.provider.PirateBayProvider
import com.example.cloudstreamapp.data.torrent.provider.Provider1337x
import com.example.cloudstreamapp.data.torrent.provider.RuTrackerProvider
import com.example.cloudstreamapp.data.torrent.provider.TorrentProviderConfig
import com.example.cloudstreamapp.data.torrent.provider.ContentCategory
import com.example.cloudstreamapp.data.torrent.provider.TorrentSource
import com.example.cloudstreamapp.data.torrent.provider.Torrentz2Provider
import com.example.cloudstreamapp.data.torrent.provider.extractInfoHash
import com.example.cloudstreamapp.domain.torrent.TorrentResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TorrentRepository @Inject constructor(
    private val config: TorrentProviderConfig,
    private val pirateBay: PirateBayProvider,
    private val nyaa: NyaaProvider,
    private val x1337: Provider1337x,
    private val torrentz2: Torrentz2Provider,
    private val ruTracker: RuTrackerProvider,
) {

    /**
     * Searches all enabled providers in parallel, resolves placeholder magnet URIs
     * (1337x, RuTracker), deduplicates by info hash, and returns the merged list
     * sorted by seeders descending.
     */
    suspend fun search(
        query: String,
        page: Int = 1,
        category: ContentCategory = ContentCategory.AUDIO,
    ): List<TorrentResult> = coroutineScope {
        val jobs = TorrentSource.entries.map { src ->
            async {
                val enabled = config.isEnabled(src).first()
                if (!enabled) return@async emptyList()
                runCatching {
                    when (src) {
                        TorrentSource.PIRATE_BAY -> pirateBay.search(query, page, category)
                        TorrentSource.NYAA -> nyaa.search(query, page, category)
                        TorrentSource.X1337 -> x1337.search(query, page, category)
                        TorrentSource.TORRENTZ2 -> torrentz2.search(query, page, category)
                        TorrentSource.RUTRACKER -> ruTracker.search(query, page, category)
                    }
                }.getOrElse { emptyList() }
            }
        }

        val raw = jobs.awaitAll().flatten()

        // Resolve placeholder magnet URIs from 2-step providers
        val resolved = raw.map { result ->
            when {
                result.source == TorrentSource.X1337.displayName && result.infoHash.isEmpty() -> {
                    val magnet = runCatching { x1337.resolveMagnet(result.magnetUri) }.getOrNull()
                    if (magnet != null) {
                        val hash = extractInfoHash(magnet) ?: ""
                        result.copy(magnetUri = magnet, infoHash = hash)
                    } else result
                }
                result.source == TorrentSource.RUTRACKER.displayName && result.infoHash.isEmpty() -> {
                    val magnet = runCatching { ruTracker.resolveMagnet(result.magnetUri) }.getOrNull()
                    if (magnet != null) {
                        val hash = extractInfoHash(magnet) ?: ""
                        result.copy(magnetUri = magnet, infoHash = hash)
                    } else result
                }
                else -> result
            }
        }

        // Deduplicate by info hash (keep highest-seeder entry), drop unresolved placeholders
        resolved
            .filter { it.infoHash.isNotEmpty() && it.magnetUri.startsWith("magnet:") }
            .groupBy { it.infoHash }
            .values
            .map { dupes -> dupes.maxByOrNull { it.seeders }!! }
            .sortedWith(
                compareByDescending<TorrentResult> { relevanceTier(query, it.name) }
                    .thenByDescending { it.seeders }
            )
    }

    /**
     * Returns a relevance tier (0–4) indicating how well [name] matches [query]:
     *  4 — name contains the full query as a substring
     *  3 — every query word is present in the name
     *  2 — majority (>50 %) of query words are present
     *  1 — at least one query word is present
     *  0 — no match
     * Single-character tokens are ignored to avoid noise from conjunctions etc.
     */
    private fun relevanceTier(query: String, name: String): Int {
        val q = query.trim().lowercase()
        val n = name.lowercase()
        if (q.isBlank()) return 0
        if (n.contains(q)) return 4
        val words = q.split(Regex("\\s+")).filter { it.length > 1 }
        if (words.isEmpty()) return 0
        val matched = words.count { n.contains(it) }
        return when {
            matched == words.size -> 3
            matched * 2 > words.size -> 2
            matched > 0 -> 1
            else -> 0
        }
    }
}
