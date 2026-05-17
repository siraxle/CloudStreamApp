package com.example.cloudstreamapp.data.torrent.engine

import android.util.Log
import fi.iki.elonen.NanoHTTPD

/**
 * Local HTTP server that serves torrent piece data to ExoPlayer via Range requests.
 *
 * URL format: http://127.0.0.1:18384/stream/{infoHash}/{fileIndex}
 *
 * ExoPlayer sends multiple Range requests as the user seeks. Each request triggers
 * piece re-prioritisation in [LibtorrentEngine] and blocks until the required piece
 * is downloaded.
 */
class TorrentHttpServer(private val engine: LibtorrentEngine) : NanoHTTPD("127.0.0.1", PORT) {

    companion object {
        const val PORT = 18384
        private const val TAG = "TorrentHttpServer"
    }

    override fun serve(session: IHTTPSession): Response {
        // Expect: /stream/{infoHash}/{fileIndex}
        val parts = session.uri.removePrefix("/").split("/")
        if (parts.size < 3 || parts[0] != "stream") {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }

        val infoHash = parts[1]
        val fileIndex = parts[2].toIntOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Bad file index")

        val totalSize = engine.fileSize(infoHash, fileIndex)
        if (totalSize <= 0L) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")
        }

        val (start, end) = parseRange(session.headers["range"], totalSize)
        val length = end - start + 1

        Log.d(TAG, "Serving $infoHash/$fileIndex bytes $start-$end/$totalSize")

        // Re-prioritise pieces around the seek point before opening the stream
        engine.seekTo(infoHash, fileIndex, start)

        val inputStream = engine.openInputStream(infoHash, fileIndex, start, length)
        val mime = mimeType(engine.getFilePath(infoHash, fileIndex)?.name ?: "")

        return newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, inputStream, length).apply {
            addHeader("Content-Range", "bytes $start-$end/$totalSize")
            addHeader("Accept-Ranges", "bytes")
        }
    }

    // ── Range header parsing ──────────────────────────────────────────────────

    private fun parseRange(rangeHeader: String?, totalSize: Long): Pair<Long, Long> {
        if (rangeHeader == null) return Pair(0L, totalSize - 1)

        val match = Regex("bytes=(\\d+)-(\\d*)").find(rangeHeader)
            ?: return Pair(0L, totalSize - 1)

        val start = match.groupValues[1].toLong()
        val end = match.groupValues[2].let { if (it.isEmpty()) totalSize - 1 else it.toLong() }
        return Pair(start.coerceIn(0L, totalSize - 1), end.coerceIn(start, totalSize - 1))
    }

    // ── MIME type resolution ──────────────────────────────────────────────────

    private fun mimeType(filename: String): String = when (filename.substringAfterLast('.').lowercase()) {
        "mp3"  -> "audio/mpeg"
        "flac" -> "audio/flac"
        "aac"  -> "audio/aac"
        "ogg"  -> "audio/ogg"
        "opus" -> "audio/ogg; codecs=opus"
        "m4a"  -> "audio/mp4"
        "wav"  -> "audio/wav"
        "mp4"  -> "video/mp4"
        "mkv"  -> "video/x-matroska"
        "webm" -> "video/webm"
        else   -> "application/octet-stream"
    }
}
