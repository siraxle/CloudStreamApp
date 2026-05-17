package com.example.cloudstreamapp.data.torrent.provider

import com.example.cloudstreamapp.domain.torrent.TorrentResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RuTrackerProvider @Inject constructor(
    private val okHttpClient: OkHttpClient
) : TorrentSearchProvider {

    override val source: TorrentSource = TorrentSource.RUTRACKER

    private val baseUrl = "https://rutracker.org/forum"

    // Cookie jar is handled by the shared OkHttpClient; we just need to POST once to get a session.
    // RuTracker allows guest search without login, but requires a valid session cookie.
    // We fetch the main page first (sets bb_session cookie), then POST the search form.

    override suspend fun search(query: String, page: Int): List<TorrentResult> =
        withContext(Dispatchers.IO) {
            ensureSession()

            val start = (page - 1) * 50
            val formBody = FormBody.Builder()
                .add("nm", query)
                .add("start", start.toString())
                .build()

            val request = Request.Builder()
                .url("$baseUrl/tracker.php")
                .post(formBody)
                .header("User-Agent", "Mozilla/5.0 (Android)")
                .header("Referer", "$baseUrl/tracker.php")
                .build()

            val html = runCatching {
                okHttpClient.newCall(request).execute().use { it.body?.string() }
            }.getOrNull() ?: return@withContext emptyList()

            val doc = Jsoup.parse(html, baseUrl)
            doc.select("table#tor-tbl tbody tr").mapNotNull { row ->
                val titleEl = row.selectFirst("td.t-title a.tLink") ?: return@mapNotNull null
                val name = titleEl.text().ifBlank { return@mapNotNull null }
                val topicUrl = "$baseUrl/${titleEl.attr("href")}"

                val sizeStr = row.selectFirst("td.tor-size u")?.text() ?: "0"
                val seeders = row.selectFirst("td.seedmed b")?.text()?.toIntOrNull() ?: 0
                val leechers = row.selectFirst("td.leechmed b")?.text()?.toIntOrNull() ?: 0

                // Extract topic id for magnet construction: href is like viewtopic.php?t=12345
                val topicId = Regex("t=(\\d+)").find(titleEl.attr("href"))?.groupValues?.get(1)
                    ?: return@mapNotNull null
                // RuTracker magnet links require the topic page — store topic URL as magnetUri placeholder
                TorrentResult(
                    name = name,
                    magnetUri = topicUrl,   // resolved to real magnet by resolveMagnet()
                    infoHash = "",
                    sizeBytes = parseSizeBytes(sizeStr),
                    seeders = seeders,
                    leechers = leechers,
                    source = source.displayName,
                )
            }
        }

    /** Fetches the topic page and extracts the magnet link. */
    suspend fun resolveMagnet(topicUrl: String): String? = withContext(Dispatchers.IO) {
        ensureSession()
        val html = runCatching {
            val req = Request.Builder()
                .url(topicUrl)
                .header("User-Agent", "Mozilla/5.0 (Android)")
                .build()
            okHttpClient.newCall(req).execute().use { it.body?.string() }
        }.getOrNull() ?: return@withContext null
        Jsoup.parse(html).selectFirst("a.magnet-link")?.attr("href")
    }

    private var sessionInitialized = false

    private fun ensureSession() {
        if (sessionInitialized) return
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/tracker.php")
                .header("User-Agent", "Mozilla/5.0 (Android)")
                .build()
            okHttpClient.newCall(req).execute().close()
        }
        sessionInitialized = true
    }
}
