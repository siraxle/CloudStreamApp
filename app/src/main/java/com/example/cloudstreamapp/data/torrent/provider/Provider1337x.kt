package com.example.cloudstreamapp.data.torrent.provider

import android.util.Log
import com.example.cloudstreamapp.domain.torrent.TorrentResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Provider1337x @Inject constructor() : TorrentSearchProvider {

    override val source: TorrentSource = TorrentSource.X1337

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    // Mirror list — tried in order; first successful response wins.
    // Sites may be blocked on Russian mobile networks, so several fallbacks are included.
    private val baseUrls = listOf(
        "https://www.1377x.to",
        "https://1337x.to",
        "https://www.1337x.to",
        "https://1337xx.to",
        "https://x1337x.se",
    )

    // Tracks the last successfully reachable base URL so detail-page requests
    // reuse the same domain that worked for the search.
    @Volatile private var activeBaseUrl = baseUrls[0]

    override suspend fun search(query: String, page: Int, category: ContentCategory): List<TorrentResult> =
        withContext(Dispatchers.IO) {
            val encodedQuery = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
            val searchPath = if (category == ContentCategory.AUDIO)
                "/category-search/$encodedQuery/Music/$page/"
            else
                "/search/$encodedQuery/$page/"

            for (base in baseUrls) {
                val html = fetch("$base$searchPath") ?: continue
                activeBaseUrl = base
                val doc = Jsoup.parse(html, "$base$searchPath")
                val results = doc.select("table.table-list tbody tr").mapNotNull { row ->
                    val nameLinks = row.select("td.name a")
                    val nameLink = nameLinks.firstOrNull { it.attr("href").contains("/torrent/") }
                        ?: nameLinks.getOrNull(1)
                        ?: return@mapNotNull null
                    val name = nameLink.text().ifBlank { return@mapNotNull null }
                    val detailPath = nameLink.attr("href").ifBlank { return@mapNotNull null }

                    val seeders = row.selectFirst("td.seeds")?.text()?.trim()?.toIntOrNull() ?: 0
                    val leechers = row.selectFirst("td.leeches")?.text()?.trim()?.toIntOrNull() ?: 0
                    val sizeStr = row.selectFirst("td.size")?.ownText()?.trim() ?: ""

                    TorrentResult(
                        name = name,
                        magnetUri = if (detailPath.startsWith("http")) detailPath else "$base$detailPath",
                        infoHash = "",
                        sizeBytes = parseSizeBytes(sizeStr),
                        seeders = seeders,
                        leechers = leechers,
                        source = source.displayName,
                    )
                }
                if (results.isNotEmpty()) return@withContext results
            }
            emptyList()
        }

    suspend fun resolveMagnet(detailUrl: String): String? = withContext(Dispatchers.IO) {
        // Try the original URL first, then the same path on every mirror.
        val path = runCatching { URI(detailUrl).rawPath }.getOrNull() ?: return@withContext null
        val candidates = listOf(detailUrl) + baseUrls.map { "$it$path" }.filter { it != detailUrl }
        for (url in candidates) {
            val html = fetch(url) ?: continue
            val magnet = Jsoup.parse(html, url).selectFirst("a[href^=magnet:]")?.attr("href")
            if (magnet != null) return@withContext magnet
        }
        null
    }

    private fun fetch(url: String): String? =
        runCatching {
            httpClient.newCall(
                Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()
            ).execute().use { response ->
                when {
                    response.isSuccessful -> response.body?.string()
                    else -> {
                        Log.d(TAG, "HTTP ${response.code} for $url")
                        null
                    }
                }
            }
        }.getOrElse { e ->
            Log.d(TAG, "fetch failed: $url — ${e.message}")
            null
        }

    companion object {
        private const val TAG = "1337xProvider"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 11; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}
