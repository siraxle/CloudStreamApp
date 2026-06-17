package com.example.cloudstreamapp.core.utils

import com.example.cloudstreamapp.domain.model.CueSheet
import com.example.cloudstreamapp.domain.model.CueTrack

object CueSheetParser {

    fun parse(text: String): CueSheet? {
        var albumTitle: String? = null
        var albumPerformer: String? = null
        var audioFileName: String? = null

        val tracks = mutableListOf<CueTrack>()
        var currentNumber: Int? = null
        var currentTitle: String? = null
        var currentPerformer: String? = null
        var currentStartMs = 0L

        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            when {
                line.startsWith("FILE ", ignoreCase = true) -> {
                    // FILE "name.ape" WAVE  — take first quoted string or first token
                    audioFileName = extractString(line.removePrefix("FILE ").removePrefix("file "))
                }
                line.startsWith("TRACK ", ignoreCase = true) -> {
                    currentNumber?.let {
                        tracks += CueTrack(it, currentTitle, currentPerformer ?: albumPerformer, currentStartMs)
                    }
                    currentNumber = line.split(" ").getOrNull(1)?.toIntOrNull()
                    currentTitle = null
                    currentPerformer = null
                    currentStartMs = 0L
                }
                line.startsWith("TITLE ", ignoreCase = true) -> {
                    val t = extractString(line.removePrefix("TITLE ").removePrefix("title "))
                    if (currentNumber == null) albumTitle = t else currentTitle = t
                }
                line.startsWith("PERFORMER ", ignoreCase = true) -> {
                    val p = extractString(line.removePrefix("PERFORMER ").removePrefix("performer "))
                    if (currentNumber == null) albumPerformer = p else currentPerformer = p
                }
                // Only INDEX 01 sets the playback start; INDEX 00 is the pre-gap
                line.startsWith("INDEX 01 ", ignoreCase = true) -> {
                    currentStartMs = parseIndexTime(line.removePrefix("INDEX 01 ").removePrefix("index 01 ").trim())
                }
            }
        }

        currentNumber?.let {
            tracks += CueTrack(it, currentTitle, currentPerformer ?: albumPerformer, currentStartMs)
        }

        if (audioFileName.isNullOrBlank() || tracks.isEmpty()) return null

        return CueSheet(
            title = albumTitle,
            performer = albumPerformer,
            audioFileName = audioFileName,
            tracks = tracks,
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun extractString(s: String): String {
        val trimmed = s.trim()
        return if (trimmed.startsWith('"') && trimmed.lastIndexOf('"') > 0) {
            trimmed.substring(1, trimmed.lastIndexOf('"'))
        } else {
            trimmed.split(' ').first()
        }
    }

    /** Converts CUE INDEX time `mm:ss:ff` (75 frames/sec) to milliseconds. */
    private fun parseIndexTime(time: String): Long {
        val parts = time.split(':').map { it.toLongOrNull() ?: 0L }
        val minutes = parts.getOrElse(0) { 0L }
        val seconds = parts.getOrElse(1) { 0L }
        val frames  = parts.getOrElse(2) { 0L }
        return minutes * 60_000L + seconds * 1_000L + frames * 1_000L / 75L
    }
}
