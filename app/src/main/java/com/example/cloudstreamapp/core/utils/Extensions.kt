package com.example.cloudstreamapp.core.utils

private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "aac", "ogg", "wav", "m4a", "opus", "ape")
private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "webm", "ts")
private val MEDIA_EXTENSIONS = AUDIO_EXTENSIONS + VIDEO_EXTENSIONS
private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
private val CUE_EXTENSIONS = setOf("cue")

fun String.isMediaFile(): Boolean = substringAfterLast('.').lowercase() in MEDIA_EXTENSIONS
fun String.isAudioFile(): Boolean = substringAfterLast('.').lowercase() in AUDIO_EXTENSIONS
fun String.isVideoFile(): Boolean = substringAfterLast('.').lowercase() in VIDEO_EXTENSIONS
fun String.isImageFile(): Boolean = substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS
fun String.isCueFile(): Boolean = substringAfterLast('.').lowercase() in CUE_EXTENSIONS

fun Long.toHumanReadableSize(): String = when {
    this >= 1_073_741_824L -> "%.1f GB".format(this / 1_073_741_824.0)
    this >= 1_048_576L -> "%.1f MB".format(this / 1_048_576.0)
    this >= 1_024L -> "%.0f KB".format(this / 1_024.0)
    else -> "$this B"
}

fun Long.toFormattedDuration(): String {
    val s = this / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
