package dev.vibecast.tv.playback

import androidx.media3.common.MimeTypes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class PlaybackRequest(
    val url: String,
    val title: String? = null,
    val subtitle: SubtitleSource? = null,
    val audioTrackId: String? = null,
    val subtitleTrackId: String? = null,
    val formatHint: String? = null,
    val containerHint: String? = null,
    val audioCodecHint: String? = null,
    val videoCodecHint: String? = null,
    val playerHint: String? = null,
    val mimeTypeHint: String? = null,
    val userAgent: String? = null,
    val referer: String? = null,
    val origin: String? = null,
    val headers: Map<String, String> = emptyMap(),
) {
    val mediaMimeType: String?
        get() = mimeTypeHint ?: inferMimeType(url, formatHint, containerHint)

    val subtitleMimeType: String?
        get() = subtitle?.mimeType ?: subtitle?.url?.let(::inferSubtitleMimeType)

    val preferredBackend: PlaybackBackend
        get() {
            val player = playerHint?.trim()?.lowercase()
            if (player == "vlc") {
                return PlaybackBackend.VLC
            }

            val format = formatHint?.trim()?.lowercase()
            val container = containerHint?.trim()?.lowercase()
            val audioCodec = audioCodecHint?.trim()?.lowercase().orEmpty()
            val videoCodec = videoCodecHint?.trim()?.lowercase().orEmpty()

            val isMkvLike =
                format == "mkv" ||
                    format == "matroska" ||
                    container == "mkv" ||
                    container == "matroska"

            val shouldPreferVlc =
                format == "progressive" ||
                    isMkvLike ||
                    audioCodec.contains("eac3") ||
                    audioCodec.contains("ec-3") ||
                    audioCodec.contains("ddp") ||
                    audioCodec.contains("dolby digital plus") ||
                    audioCodec.contains("truehd") ||
                    audioCodec.contains("dts") ||
                    videoCodec.contains("hevc") ||
                    videoCodec.contains("h265") ||
                    videoCodec.contains("h.265")

            return if (shouldPreferVlc) {
                PlaybackBackend.VLC
            } else {
                PlaybackBackend.EXOPLAYER
            }
        }

    companion object {
        fun fromJson(payload: JsonObject): PlaybackRequest? {
            val url = payload["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (url.isBlank()) {
                return null
            }

            val headers = payload["headers"]?.jsonObject
                ?.mapNotNull { (key, value) ->
                    value.jsonPrimitive.contentOrNull?.let { key to it }
                }
                ?.toMap()
                .orEmpty()

            return PlaybackRequest(
                url = url,
                title = payload["title"]?.jsonPrimitive?.contentOrNull,
                subtitle = parseSubtitle(payload),
                audioTrackId = payload["audioTrackId"]?.jsonPrimitive?.contentOrNull,
                subtitleTrackId = payload["subtitleTrackId"]?.jsonPrimitive?.contentOrNull,
                formatHint = payload["format"]?.jsonPrimitive?.contentOrNull
                    ?: payload["type"]?.jsonPrimitive?.contentOrNull,
                containerHint = payload["container"]?.jsonPrimitive?.contentOrNull,
                audioCodecHint = payload["audioCodec"]?.jsonPrimitive?.contentOrNull,
                videoCodecHint = payload["videoCodec"]?.jsonPrimitive?.contentOrNull,
                playerHint = payload["player"]?.jsonPrimitive?.contentOrNull,
                mimeTypeHint = payload["mimeType"]?.jsonPrimitive?.contentOrNull,
                userAgent = payload["userAgent"]?.jsonPrimitive?.contentOrNull,
                referer = payload["referer"]?.jsonPrimitive?.contentOrNull,
                origin = payload["origin"]?.jsonPrimitive?.contentOrNull,
                headers = headers,
            )
        }

        private fun parseSubtitle(payload: JsonObject): SubtitleSource? {
            val subtitleObject = payload["subtitle"]?.jsonObject
            val url = subtitleObject?.get("url")?.jsonPrimitive?.contentOrNull
                ?: payload["subtitleUrl"]?.jsonPrimitive?.contentOrNull
            if (url.isNullOrBlank()) {
                return null
            }

            return SubtitleSource(
                url = url,
                mimeType = subtitleObject?.get("mimeType")?.jsonPrimitive?.contentOrNull
                    ?: payload["subtitleMimeType"]?.jsonPrimitive?.contentOrNull,
                language = subtitleObject?.get("language")?.jsonPrimitive?.contentOrNull
                    ?: payload["subtitleLanguage"]?.jsonPrimitive?.contentOrNull,
                label = subtitleObject?.get("label")?.jsonPrimitive?.contentOrNull
                    ?: payload["subtitleLabel"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }
}

private fun inferMimeType(url: String, formatHint: String?, containerHint: String?): String? {
    val normalizedHint = formatHint?.trim()?.lowercase()
    if (!normalizedHint.isNullOrBlank()) {
        return when (normalizedHint) {
            "hls", "m3u8" -> MimeTypes.APPLICATION_M3U8
            "dash", "mpd" -> MimeTypes.APPLICATION_MPD
            "smoothstreaming", "ss", "ism" -> MimeTypes.APPLICATION_SS
            "rtsp" -> null
            "progressive", "file", "direct", "video" -> inferProgressiveMimeType(containerHint)
            "mp4" -> MimeTypes.VIDEO_MP4
            "mkv", "matroska" -> MimeTypes.VIDEO_MATROSKA
            "webm" -> MimeTypes.VIDEO_WEBM
            "mp3" -> MimeTypes.AUDIO_MPEG
            "m4a", "aac" -> MimeTypes.AUDIO_AAC
            "flac" -> MimeTypes.AUDIO_FLAC
            "wav" -> MimeTypes.AUDIO_WAV
            "ogg", "opus" -> MimeTypes.AUDIO_OGG
            else -> null
        }
    }

    val path = url.substringBefore('?').substringBefore('#').lowercase()
    inferProgressiveMimeType(containerHint)?.let { return it }
    return when {
        path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
        path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
        path.endsWith(".ism") || path.endsWith(".isml") -> MimeTypes.APPLICATION_SS
        path.startsWith("rtsp://") -> null
        path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".cmfv") -> MimeTypes.VIDEO_MP4
        path.endsWith(".mkv") || path.endsWith(".mk3d") -> MimeTypes.VIDEO_MATROSKA
        path.endsWith(".webm") -> MimeTypes.VIDEO_WEBM
        path.endsWith(".mp3") -> MimeTypes.AUDIO_MPEG
        path.endsWith(".aac") || path.endsWith(".m4a") -> MimeTypes.AUDIO_AAC
        path.endsWith(".flac") -> MimeTypes.AUDIO_FLAC
        path.endsWith(".wav") -> MimeTypes.AUDIO_WAV
        path.endsWith(".ogg") || path.endsWith(".opus") -> MimeTypes.AUDIO_OGG
        else -> null
    }
}

private fun inferProgressiveMimeType(containerHint: String?): String? {
    return when (containerHint?.trim()?.lowercase()) {
        "mkv", "matroska" -> MimeTypes.VIDEO_MATROSKA
        "mp4", "m4v" -> MimeTypes.VIDEO_MP4
        "webm" -> MimeTypes.VIDEO_WEBM
        "mp3" -> MimeTypes.AUDIO_MPEG
        "m4a", "aac" -> MimeTypes.AUDIO_AAC
        "flac" -> MimeTypes.AUDIO_FLAC
        "wav" -> MimeTypes.AUDIO_WAV
        "ogg", "opus" -> MimeTypes.AUDIO_OGG
        else -> null
    }
}

private fun inferSubtitleMimeType(url: String): String? {
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    return when {
        path.endsWith(".vtt") || path.endsWith(".webvtt") -> MimeTypes.TEXT_VTT
        path.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
        path.endsWith(".ttml") || path.endsWith(".xml") || path.endsWith(".dfxp") -> MimeTypes.APPLICATION_TTML
        path.endsWith(".ssa") || path.endsWith(".ass") -> MimeTypes.TEXT_SSA
        else -> null
    }
}
