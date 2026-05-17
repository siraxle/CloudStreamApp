package com.example.cloudstreamapp.data.torrent.provider

import com.example.cloudstreamapp.domain.torrent.TorrentResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Provider1337x @Inject constructor(
    private val okHttpClient: OkHttpClient
) : TorrentSearchProvider {

    override val source: TorrentSource = TorrentSource.X1337

    private val baseUrl = "https://www.1337x.to"

    override suspend fun search(query: String, page: Int): List<TorrentResult> =
        withContext(Dispatchers.IO) {
            val encodedQuery = query.replace(" ", "+")
            val searchUrl = "$baseUrl/search/$encodedQuery/$page/"
            val html = fetch(searchUrl) ?: return@withContext emptyList()
            val doc = Jsoup.parse(html, searchUrl)

            doc.select("table.table-list tbody tr").mapNotNull { row ->
                val nameLink = row.selectFirst("td.name a:nth-child(2)") ?: return@mapNotNull null
                val name = nameLink.text().ifBlank { return@mapNotNull null }
                val detailPath = nameLink.attr("href").ifBlank { return@mapNotNull null }
                val seeders = row.select("td.seeds").text().toIntOrNull() ?: 0
                val leechers = row.select("td.leeches").text().toIntOrNull() ?: 0
                val sizeStr = row.select("td.size").text().substringBefore("\n").trim()

                // Magnet is only available on the detail page — store detail URL in magnetUri
                // temporarily; TorrentRepository will resolve it before returning to the UI.
                TorrentResult(
                    name = name,
                    magnetUri = "$baseUrl$detailPath",  // placeholder, resolved later
                    infoHash = "",                       // resolved after detail fetch
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
        val doc = Jsoup.parse(html, detailUrl)
        doc.selectFirst("a[href^=magnet:]")?.attr("href")
    }

    private fun fetch(url: String): String? =
        runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android)")
                .build()
            okHttpClient.newCall(req).execute().use { it.body?.string() }
        }.getOrNull()
}
