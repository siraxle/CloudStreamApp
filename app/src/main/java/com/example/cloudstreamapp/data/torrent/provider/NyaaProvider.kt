package com.example.cloudstreamapp.data.torrent.provider

import android.util.Log
import com.example.cloudstreamapp.domain.torrent.TorrentResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NyaaProvider @Inject constructor() : TorrentSearchProvider {

    override val source: TorrentSource = TorrentSource.NYAA

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    override suspend fun search(query: String, page: Int, category: ContentCategory): List<TorrentResult> =
        withContext(Dispatchers.IO) {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val cat = if (category == ContentCategory.AUDIO) "2_0" else "0_0"
            val path = "/?page=rss&q=$encodedQuery&c=$cat&p=$page"
            val body = fetchRss(path) ?: return@withContext emptyList()

            val doc = Jsoup.parse(body, "", org.jsoup.parser.Parser.xmlParser())
            doc.select("item").mapNotNull { item ->
                val infoHash = item.selectFirst("nyaa|infoHash")?.text()?.lowercase()
                    ?: return@mapNotNull null
                if (infoHash.isBlank()) return@mapNotNull null

                val name = item.selectFirst("title")?.text() ?: "Unknown"
                val sizeStr = item.selectFirst("nyaa|size")?.text() ?: "0"
                val seeders = item.selectFirst("nyaa|seeders")?.text()?.toIntOrNull() ?: 0
                val leechers = item.selectFirst("nyaa|leechers")?.text()?.toIntOrNull() ?: 0

                TorrentResult(
                    name = name,
                    magnetUri = buildMagnet(infoHash, name),
                    infoHash = infoHash,
                    sizeBytes = parseSizeBytes(sizeStr),
                    seeders = seeders,
                    leechers = leechers,
                    source = source.displayName,
                )
            }
        }

    private fun fetchRss(path: String): String? {
        for (base in BASE_URLS) {
            val result = runCatching {
                httpClient.newCall(
                    Request.Builder().url("$base$path").build()
                ).execute().use { response ->
                    if (response.isSuccessful) response.body?.string() else null
                }
            }.getOrElse { e ->
                Log.d(TAG, "fetch failed for $base: ${e.message}")
                null
            }
            if (result != null) return result
        }
        return null
    }

    companion object {
        private const val TAG = "NyaaProvider"
        // nyaa.land is a long-running mirror with identical RSS structure
        private val BASE_URLS = listOf(
            "https://nyaa.si",
            "https://nyaa.land",
        )
    }
}
