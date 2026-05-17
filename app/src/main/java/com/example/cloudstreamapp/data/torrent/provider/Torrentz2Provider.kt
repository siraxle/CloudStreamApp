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
class Torrentz2Provider @Inject constructor(
    private val okHttpClient: OkHttpClient
) : TorrentSearchProvider {

    override val source: TorrentSource = TorrentSource.TORRENTZ2

    override suspend fun search(query: String, page: Int): List<TorrentResult> =
        withContext(Dispatchers.IO) {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://torrentz2.nz/search?q=$encodedQuery"
            val html = runCatching {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Android)")
                    .build()
                okHttpClient.newCall(req).execute().use { it.body?.string() }
            }.getOrNull() ?: return@withContext emptyList()

            val doc = Jsoup.parse(html, url)
            doc.select("dl").mapNotNull { dl ->
                val titleEl = dl.selectFirst("dt a") ?: return@mapNotNull null
                val name = titleEl.text().ifBlank { return@mapNotNull null }
                val href = titleEl.attr("href")
                // href is like /abcdef1234567890abcdef1234567890abcdef12 (40-char hash)
                val infoHash = href.trimStart('/').lowercase()
                if (infoHash.length != 40 || infoHash.any { it !in '0'..'9' && it !in 'a'..'f' }) {
                    return@mapNotNull null
                }

                val dd = dl.selectFirst("dd")
                val seeders = dd?.selectFirst("span.u")?.text()?.toIntOrNull() ?: 0
                val leechers = dd?.selectFirst("span.d")?.text()?.toIntOrNull() ?: 0
                val sizeStr = dd?.selectFirst("span.s")?.text() ?: "0"

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
}
