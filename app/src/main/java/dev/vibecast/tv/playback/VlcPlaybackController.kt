package dev.vibecast.tv.playback

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
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
        val media = Media(libVlc, Uri.parse(request.url)).apply {
            setHWDecoderEnabled(true, false)
            setDefaultMediaPlayerOptions()
            request.userAgent?.takeIf { it.isNotBlank() }?.let { addOption(":http-user-agent=$it") }
            request.referer?.takeIf { it.isNotBlank() }?.let { addOption(":http-referrer=$it") }
        }

        mediaPlayer.setMedia(media)
        media.release()
        mediaPlayer.play()
        positionMs?.takeIf { it > 0L }?.let { mediaPlayer.setTime(it) }
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
}

@Immutable
data class VlcPlaybackSnapshot(
    val isPlaying: Boolean,
    val currentPositionMs: Long,
    val durationMs: Long,
    val volume: Double,
    val currentMediaUrl: String?,
)
