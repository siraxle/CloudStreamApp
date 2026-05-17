package com.example.cloudstreamapp.data.torrent.provider

import java.net.URLEncoder

private val DEFAULT_TRACKERS = listOf(
    "udp://tracker.opentrackr.org:1337/announce",
    "udp://open.tracker.cl:1337/announce",
    "udp://tracker.openbittorrent.com:6969/announce",
    "udp://9.rarbg.com:2810/announce",
    "udp://www.torrent.eu.org:451/announce",
    "udp://tracker.torrent.eu.org:451/announce",
)

/**
 * Builds a magnet URI from a 40-char hex info hash and an optional display name.
 * Appends a fixed set of public trackers for peer discovery.
 */
fun buildMagnet(infoHash: String, name: String): String {
    val encodedName = URLEncoder.encode(name, "UTF-8")
    val trackerSuffix = DEFAULT_TRACKERS.joinToString("") { "&tr=${URLEncoder.encode(it, "UTF-8")}" }
    return "magnet:?xt=urn:btih:$infoHash&dn=$encodedName$trackerSuffix"
}

/** Extracts a 40-char lowercase hex info hash from a magnet URI, or null. */
fun extractInfoHash(magnetUri: String): String? =
    Regex("xt=urn:btih:([a-fA-F0-9]{40})", RegexOption.IGNORE_CASE)
        .find(magnetUri)
        ?.groupValues?.get(1)
        ?.lowercase()

/** Parses a human-readable size string like "1.2 GB" to bytes, or 0 if unparseable. */
fun parseSizeBytes(sizeStr: String): Long {
    val trimmed = sizeStr.trim()
    val num = trimmed.replace(Regex("[^0-9.]"), "").toDoubleOrNull() ?: return 0L
    return when {
        trimmed.contains("GB", ignoreCase = true) -> (num * 1_073_741_824).toLong()
        trimmed.contains("MB", ignoreCase = true) -> (num * 1_048_576).toLong()
        trimmed.contains("KB", ignoreCase = true) -> (num * 1_024).toLong()
        else -> num.toLong()
    }
}
