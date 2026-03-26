package dev.vibecast.tv.playback

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout

class VlcPlaybackController(context: Context) {
    private val appContext = context.applicationContext
    private val libVlc = LibVLC(
        appContext,
        arrayListOf(
            "--audio-time-stretch",
            "--network-caching=1500",
            "--file-caching=1500",
            "--avcodec-fast",
        ),
    )

    private val mediaPlayer = MediaPlayer(libVlc).apply {
        setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT)
        configureAudioOutput()
        setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing,
                MediaPlayer.Event.ESAdded,
                MediaPlayer.Event.ESSelected -> ensureAudioTrackSelected()
            }
        }
    }

    private var attachedLayout: VLCVideoLayout? = null
    private var currentRequest: PlaybackRequest? = null

    fun bind(layout: VLCVideoLayout) {
        if (attachedLayout === layout) {
            return
        }

        unbind()
        attachedLayout = layout
        mediaPlayer.attachViews(layout, null, false, false)
        mediaPlayer.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT)
    }

    fun unbind() {
        if (attachedLayout != null) {
            runCatching { mediaPlayer.detachViews() }
            attachedLayout = null
        }
    }

    fun play(request: PlaybackRequest, positionMs: Long? = null) {
        currentRequest = request
        configureAudioOutput()
        val media = Media(libVlc, Uri.parse(request.url)).apply {
            // Favor VLC's software path for codec-heavy progressive files.
            setHWDecoderEnabled(false, false)
            setDefaultMediaPlayerOptions()
            request.userAgent?.takeIf { it.isNotBlank() }?.let { addOption(":http-user-agent=$it") }
            request.referer?.takeIf { it.isNotBlank() }?.let { addOption(":http-referrer=$it") }
            addOption(":codec=all")
        }

        mediaPlayer.setMedia(media)
        media.release()
        request.subtitle?.let { subtitle ->
            mediaPlayer.addSlave(IMedia.Slave.Type.Subtitle, Uri.parse(subtitle.url), true)
        }
        mediaPlayer.play()
        positionMs?.takeIf { it > 0L }?.let { mediaPlayer.setTime(it) }
    }

    private fun configureAudioOutput() {
        runCatching { mediaPlayer.setAudioDigitalOutputEnabled(false) }
        val configured = runCatching { mediaPlayer.setAudioOutput("audiotrack") }.getOrDefault(false)
        if (!configured) {
            runCatching { mediaPlayer.setAudioOutput("opensles") }
        }
    }

    private fun ensureAudioTrackSelected() {
        val audioTrackType = IMedia.Track.Type.Audio
        val selectedTracks = runCatching { mediaPlayer.getSelectedTracks(audioTrackType) }
            .getOrNull()
            .orEmpty()
        if (selectedTracks.isNotEmpty()) {
            return
        }

        val firstAudioTrackId = runCatching { mediaPlayer.getTracks(audioTrackType) }
            .getOrNull()
            .orEmpty()
            .firstOrNull()
            ?.id
            ?.takeIf { it.isNotBlank() }
            ?: return

        runCatching { mediaPlayer.selectTracks(audioTrackType, firstAudioTrackId) }
    }

    fun pause() {
        mediaPlayer.pause()
    }

    fun resume() {
        mediaPlayer.play()
    }

    fun stop() {
        mediaPlayer.stop()
    }

    fun seekTo(positionMs: Long) {
        mediaPlayer.setTime(positionMs.coerceAtLeast(0L))
    }

    fun setVolume(normalized: Double) {
        mediaPlayer.setVolume((normalized.coerceIn(0.0, 1.0) * 100.0).toInt())
    }

    fun audioTracks(): List<MediaTrackInfo> = trackInfos(IMedia.Track.Type.Audio, "Audio")

    fun subtitleTracks(): List<MediaTrackInfo> = trackInfos(IMedia.Track.Type.Text, "Subtitle")

    fun selectAudioTrack(trackId: String): Boolean {
        if (trackId.isBlank()) {
            return false
        }
        return runCatching {
            mediaPlayer.selectTracks(IMedia.Track.Type.Audio, trackId)
            true
        }.getOrDefault(false)
    }

    fun selectSubtitleTrack(trackId: String?): Boolean {
        return runCatching {
            if (trackId.isNullOrBlank() || trackId == OFF_TRACK_ID) {
                mediaPlayer.unselectTrackType(IMedia.Track.Type.Text)
            } else {
                mediaPlayer.selectTracks(IMedia.Track.Type.Text, trackId)
            }
            true
        }.getOrDefault(false)
    }

    fun snapshot(): VlcPlaybackSnapshot {
        val volume = mediaPlayer.getVolume().takeIf { it >= 0 }?.div(100.0)?.coerceIn(0.0, 1.0) ?: 1.0
        return VlcPlaybackSnapshot(
            isPlaying = mediaPlayer.isPlaying(),
            currentPositionMs = mediaPlayer.getTime().coerceAtLeast(0L),
            durationMs = mediaPlayer.getLength().coerceAtLeast(0L),
            volume = volume,
            currentMediaUrl = currentRequest?.url,
        )
    }

    fun release() {
        unbind()
        mediaPlayer.stop()
        mediaPlayer.release()
        libVlc.release()
    }

    private fun trackInfos(trackType: Int, fallbackPrefix: String): List<MediaTrackInfo> {
        return runCatching { mediaPlayer.getTracks(trackType) }
            .getOrNull()
            .orEmpty()
            .mapIndexed { index, track ->
                MediaTrackInfo(
                    id = track.id.orEmpty(),
                    label = buildTrackLabel(track, index, fallbackPrefix),
                    language = track.language,
                    selected = track.selected,
                )
            }
            .filter { it.id.isNotBlank() }
    }

    private fun buildTrackLabel(track: IMedia.Track, index: Int, fallbackPrefix: String): String {
        val parts = listOf(
            track.description,
            track.name,
            track.language,
            track.codec,
        ).filter { !it.isNullOrBlank() }

        return parts.firstOrNull() ?: "$fallbackPrefix ${index + 1}"
    }
}

@Immutable
data class VlcPlaybackSnapshot(
    val isPlaying: Boolean,
    val currentPositionMs: Long,
    val durationMs: Long,
    val volume: Double,
    val currentMediaUrl: String?,
)
