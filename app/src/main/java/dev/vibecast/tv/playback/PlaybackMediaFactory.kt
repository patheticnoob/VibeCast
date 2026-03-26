package dev.vibecast.tv.playback

import android.net.Uri
import androidx.media3.common.C
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.extractor.DefaultExtractorsFactory

@UnstableApi
class PlaybackMediaFactory(private val context: Context) {
    fun createMediaSource(request: PlaybackRequest): MediaSource {
        val requestHeaders = buildMap {
            putAll(request.headers)
            request.referer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
            request.origin?.takeIf { it.isNotBlank() }?.let { put("Origin", it) }
        }

        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setUserAgent(request.userAgent ?: DEFAULT_USER_AGENT)

        val upstreamFactory = DefaultDataSource.Factory(context, httpFactory)
        val dataSourceFactory = if (requestHeaders.isEmpty()) {
            upstreamFactory
        } else {
            ResolvingDataSource.Factory(upstreamFactory) { dataSpec ->
                dataSpec.withRequestHeaders(requestHeaders)
            }
        }

        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)
        val mediaItemBuilder = MediaItem.Builder()
            .setUri(request.url)
            .apply {
                request.mediaMimeType?.let(::setMimeType)
                request.subtitle?.let { subtitle ->
                    val subtitleMimeType = request.subtitleMimeType ?: MimeTypes.TEXT_VTT
                    setSubtitleConfigurations(
                        listOf(
                            MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle.url))
                                .setMimeType(subtitleMimeType)
                                .setLanguage(subtitle.language)
                                .setLabel(subtitle.label)
                                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                .build(),
                        ),
                    )
                }
            }
        val mediaItem = mediaItemBuilder.build()

        return mediaSourceFactory.createMediaSource(mediaItem)
    }

    companion object {
        private const val DEFAULT_USER_AGENT = "VibeCast/1.1"
    }
}
