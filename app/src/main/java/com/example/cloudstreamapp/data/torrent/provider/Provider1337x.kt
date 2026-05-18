package com.example.cloudstreamapp.data.torrent.provider

import android.util.Log
import com.example.cloudstreamapp.domain.torrent.TorrentResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Provider1337x @Inject constructor(
    private val okHttpClient: OkHttpClient
) : TorrentSearchProvider {

    override val source: TorrentSource = TorrentSource.X1337

    private val baseUrl = "https://www.1377x.to"

    override suspend fun search(query: String, page: Int, category: ContentCategory): List<TorrentResult> =
        withContext(Dispatchers.IO) {
            val encodedQuery = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
            val searchUrl = if (category == ContentCategory.AUDIO)
                "$baseUrl/category-search/$encodedQuery/Music/$page/"
            else
                "$baseUrl/search/$encodedQuery/$page/"
            val html = fetch(searchUrl) ?: return@withContext emptyList()
            val doc = Jsoup.parse(html, searchUrl)

            doc.select("table.table-list tbody tr").mapNotNull { row ->
                // Prefer the link that points to /torrent/; fall back to the second <a>
                val nameLinks = row.select("td.name a")
                val nameLink = nameLinks.firstOrNull { it.attr("href").contains("/torrent/") }
                    ?: nameLinks.getOrNull(1)
                    ?: return@mapNotNull null
                val name = nameLink.text().ifBlank { return@mapNotNull null }
                val detailPath = nameLink.attr("href").ifBlank { return@mapNotNull null }

                val seeders = row.selectFirst("td.seeds")?.text()?.trim()?.toIntOrNull() ?: 0
                val leechers = row.selectFirst("td.leeches")?.text()?.trim()?.toIntOrNull() ?: 0
                // ownText() excludes child element text (e.g. uploader span inside size cell)
                val sizeStr = row.selectFirst("td.size")?.ownText()?.trim() ?: ""

                TorrentResult(
                    name = name,
                    magnetUri = if (detailPath.startsWith("http")) detailPath else "$baseUrl$detailPath",
                    infoHash = "",
                    sizeBytes = parseSizeBytes(sizeStr),
                    seeders = seeders,
                    leechers = leechers,
                    source = source.displayName,
                )
            }
        }

    /** Fetches the detail page and returns the real magnet URI, or null on failure. */
    suspend fun resolveMagnet(detailUrl: String): String? = withContext(Dispatchers.IO) {
        val html = fetch(detailUrl) ?: return@withContext null
        Jsoup.parse(html, detailUrl).selectFirst("a[href^=magnet:]")?.attr("href")
    }

    private fun fetch(url: String): String? =
        runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            okHttpClient.newCall(req).execute().use { response ->
                when {
                    response.isSuccessful -> response.body?.string()
                    else -> {
                        Log.w("1337x", "HTTP ${response.code} for $url")
                        null
                    }
                }
            }
        }.getOrElse { e ->
            Log.e("1337x", "fetch failed: $url", e)
            null
        }
}
