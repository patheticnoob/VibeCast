package dev.vibecast.tv.playback

data class SubtitleSource(
    val url: String,
    val mimeType: String? = null,
    val language: String? = null,
    val label: String? = null,
)

data class MediaTrackInfo(
    val id: String,
    val label: String,
    val language: String? = null,
    val selected: Boolean = false,
)

const val OFF_TRACK_ID = "__off__"
