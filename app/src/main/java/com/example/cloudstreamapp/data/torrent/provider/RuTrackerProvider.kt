package com.example.cloudstreamapp.data.torrent.provider

import com.example.cloudstreamapp.data.torrent.auth.TorrentAuthStore
import com.example.cloudstreamapp.domain.torrent.TorrentResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.nio.charset.Charset
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RuTrackerProvider @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val authStore: TorrentAuthStore,
) : TorrentSearchProvider {

    override val source: TorrentSource = TorrentSource.RUTRACKER

    private val baseUrl = "https://rutracker.org/forum"
    private val sessionMutex = Mutex()
    private var sessionInitialized = false

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

                val topicId = Regex("t=(\\d+)").find(titleEl.attr("href"))?.groupValues?.get(1)
                    ?: return@mapNotNull null
                TorrentResult(
                    name = name,
                    magnetUri = topicUrl,
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

    /**
     * Performs a full login to RuTracker. On success the OkHttpClient cookie jar
     * will hold the session cookie for subsequent requests.
     *
     * RuTracker's charset is Windows-1251 — Cyrillic must be encoded in cp1251,
     * not UTF-8, or the server won't recognise the credentials.
     */
    suspend fun login(username: String, password: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cp1251 = Charset.forName("windows-1251")
                val formBody = FormBody.Builder(cp1251)
                    .add("login_username", username)
                    .add("login_password", password)
                    .add("login", "Вход")
                    .build()

                val request = Request.Builder()
                    .url("$baseUrl/login.php")
                    .post(formBody)
                    .header("User-Agent", "Mozilla/5.0 (Android)")
                    .header("Referer", "$baseUrl/login.php")
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error("Сервер вернул ${response.code}")
                    }
                    // After a successful login RuTracker redirects away from login.php.
                    // If the final URL still points to login.php the credentials were wrong.
                    val finalUrl = response.request.url.toString()
                    if (finalUrl.contains("login.php")) {
                        error("Неверное имя пользователя или пароль")
                    }
                }

                sessionInitialized = true
            }
        }

    private suspend fun ensureSession() {
        if (sessionInitialized) return
        sessionMutex.withLock {
            if (sessionInitialized) return
            val credentials = authStore.getCredentials(TorrentSource.RUTRACKER)
            if (credentials != null) {
                val (u, p) = credentials
                // Best-effort re-login on app restart; ignore failure (falls back to guest)
                login(u, p)
            } else {
                // Guest session — RuTracker sets bb_session cookie on first visit
                runCatching {
                    val req = Request.Builder()
                        .url("$baseUrl/tracker.php")
                        .header("User-Agent", "Mozilla/5.0 (Android)")
                        .build()
                    okHttpClient.newCall(req).execute().close()
                }
            }
            sessionInitialized = true
        }
    }
}
