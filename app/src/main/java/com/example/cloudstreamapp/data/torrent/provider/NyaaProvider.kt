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
class NyaaProvider @Inject constructor(
    private val okHttpClient: OkHttpClient
) : TorrentSearchProvider {

    override val source: TorrentSource = TorrentSource.NYAA

    override suspend fun search(query: String, page: Int, category: ContentCategory): List<TorrentResult> =
        withContext(Dispatchers.IO) {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            // c=2_0 = audio; c=0_0 = all
            val cat = if (category == ContentCategory.AUDIO) "2_0" else "0_0"
            val url = "https://nyaa.si/?page=rss&q=$encodedQuery&c=$cat&p=$page"
            val request = Request.Builder().url(url).build()
            val body = okHttpClient.newCall(request).execute().use { it.body?.string() }
                ?: return@withContext emptyList()

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
}
