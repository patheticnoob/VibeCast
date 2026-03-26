package dev.vibecast.tv

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import dev.vibecast.tv.cast.CastServer
import dev.vibecast.tv.cast.NetworkAddressResolver
import dev.vibecast.tv.cast.QrCodeBitmapFactory
import dev.vibecast.tv.playback.PlaybackBackend
import dev.vibecast.tv.playback.PlaybackMediaFactory
import dev.vibecast.tv.playback.PlaybackRequest
import dev.vibecast.tv.playback.VlcPlaybackController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

@UnstableApi
class VibeCastViewModel(application: Application) : AndroidViewModel(application) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val playbackMediaFactory = PlaybackMediaFactory(application)
    val vlcController = VlcPlaybackController(application)
    private var currentRequest: PlaybackRequest? = null

    val player: ExoPlayer = ExoPlayer.Builder(
        application,
        DefaultRenderersFactory(application)
            .forceEnableMediaCodecAsynchronousQueueing()
            .setEnableDecoderFallback(true),
    ).build().apply {
        playWhenReady = true
        repeatMode = Player.REPEAT_MODE_OFF
    }

    private val _uiState = MutableStateFlow(ReceiverUiState())
    val uiState: StateFlow<ReceiverUiState> = _uiState.asStateFlow()

    private var server: CastServer? = null
    private var pollingJob: Job? = null
    private var networkMonitorJob: Job? = null

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            refreshPlaybackState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            refreshPlaybackState()
        }

        override fun onPlayerError(error: PlaybackException) {
            val request = currentRequest
            if (
                request != null &&
                _uiState.value.playbackBackend == PlaybackBackend.EXOPLAYER &&
                shouldRetryWithVlc(error, request)
            ) {
                switchBackend(PlaybackBackend.VLC)
                vlcController.play(request)
                _uiState.update {
                    it.copy(
                        playbackBackend = PlaybackBackend.VLC,
                        lastError = null,
                    )
                }
                publishState()
                return
            }

            _uiState.update {
                it.copy(
                    lastError = buildPlaybackErrorMessage(error),
                    playbackPhase = PlaybackPhase.ERROR,
                )
            }
            publishState()
        }
    }

    init {
        player.addListener(playerListener)
        startServer()
        startPlaybackTicker()
        startNetworkMonitor()
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
        networkMonitorJob?.cancel()
        server?.stop()
        player.removeListener(playerListener)
        player.release()
        vlcController.release()
    }

    private fun startServer() {
        viewModelScope.launch(Dispatchers.IO) {
            val portsToTry = listOf(8080, 8081, 8082, 8090, 9000)
            var started = false

            for (candidate in portsToTry) {
                runCatching {
                    val castServer = CastServer(
                        port = candidate,
                        assetManager = getApplication<Application>().assets,
                        listener = object : CastServer.Listener {
                            override fun onClientCountChanged(count: Int) {
                                _uiState.update { it.copy(clientCount = count) }
                            }

                            override fun onCommand(message: String) {
                                handleIncomingCommand(message)
                            }

                            override fun provideStateJson(): String = statePayload()
                        },
                    )
                    castServer.start(CastServer.SOCKET_READ_TIMEOUT, false)
                    server = castServer
                    started = true
                    updateConnection(port = candidate)
                }.onFailure { throwable ->
                    _uiState.update {
                        it.copy(lastError = "Port $candidate unavailable: ${throwable.localizedMessage}")
                    }
                }

                if (started) {
                    break
                }
            }

            if (!started) {
                _uiState.update {
                    it.copy(lastError = "Could not start the local Vibe Cast server on the default ports.")
                }
            }
        }
    }

    private fun startPlaybackTicker() {
        pollingJob = viewModelScope.launch {
            while (isActive) {
                refreshPlaybackState()
                delay(1_000)
            }
        }
    }

    private fun startNetworkMonitor() {
        networkMonitorJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                updateConnection(port = _uiState.value.port)
                delay(5_000)
            }
        }
    }

    private fun updateConnection(port: Int?) {
        if (port == null) {
            return
        }

        val host = NetworkAddressResolver.findLocalIpv4Address()
        val httpUrl = host?.let { "http://$it:$port" }
        val wsUrl = host?.let { "ws://$it:$port/ws" }
        val currentUrl = _uiState.value.connectionUrl

        val qrBitmap = if (!httpUrl.isNullOrBlank() && httpUrl != currentUrl) {
            QrCodeBitmapFactory.create(httpUrl, size = 720)
        } else {
            _uiState.value.qrBitmap
        }

        _uiState.update {
            it.copy(
                hostAddress = host,
                port = port,
                connectionUrl = httpUrl,
                webSocketUrl = wsUrl,
                qrBitmap = qrBitmap,
            )
        }
    }

    private fun handleIncomingCommand(message: String) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            runCatching {
                val payload = json.parseToJsonElement(message).jsonObject
                when (payload["action"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
                    "play" -> handlePlay(payload)
                    "pause" -> pausePlayback()
                    "resume" -> resumePlayback()
                    "seek" -> handleSeek(payload)
                    "volume" -> handleVolume(payload)
                    "stop" -> stopPlayback()
                    "get_state" -> Unit
                    else -> _uiState.update {
                        it.copy(lastError = "Unsupported command received.")
                    }
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(lastError = "Invalid command: ${throwable.localizedMessage}")
                }
            }

            refreshPlaybackState()
            publishState()
        }
    }

    private fun handlePlay(payload: JsonObject) {
        val request = PlaybackRequest.fromJson(payload)
        if (request == null) {
            _uiState.update { it.copy(lastError = "Missing media URL in play command.") }
            return
        }

        currentRequest = request
        val backend = request.preferredBackend
        switchBackend(backend)

        if (backend == PlaybackBackend.VLC) {
            val positionMs = payload["positionMs"]?.jsonPrimitive?.longOrNull
                ?: payload["time"]?.jsonPrimitive?.doubleOrNull?.let { (it * 1_000).toLong() }
            vlcController.play(request, positionMs)
        } else {
            val mediaSource = playbackMediaFactory.createMediaSource(request)
            player.setMediaSource(mediaSource)
            player.prepare()

            payload["positionMs"]?.jsonPrimitive?.longOrNull?.let(player::seekTo)
            payload["time"]?.jsonPrimitive?.doubleOrNull?.let { seconds ->
                player.seekTo((seconds * 1_000).toLong())
            }

            player.play()
        }

        _uiState.update {
            it.copy(
                currentMediaUrl = request.url,
                playbackBackend = backend,
                lastError = null,
            )
        }
    }

    private fun handleSeek(payload: JsonObject) {
        val duration = _uiState.value.durationMs
        val positionMs = payload["positionMs"]?.jsonPrimitive?.longOrNull
            ?: payload["time"]?.jsonPrimitive?.doubleOrNull?.let { (it * 1_000).toLong() }
            ?: payload["progress"]?.jsonPrimitive?.doubleOrNull?.let { fraction ->
                (duration * fraction.coerceIn(0.0, 1.0)).toLong()
            }

        positionMs?.let { seekPlayback(it.coerceAtLeast(0L)) }
    }

    private fun handleVolume(payload: JsonObject) {
        val raw = payload["value"]?.jsonPrimitive?.doubleOrNull
            ?: payload["volume"]?.jsonPrimitive?.doubleOrNull
            ?: payload["level"]?.jsonPrimitive?.doubleOrNull
        val normalized = when {
            raw == null -> null
            raw > 1.0 -> (raw / 100.0).coerceIn(0.0, 1.0)
            else -> raw.coerceIn(0.0, 1.0)
        }
        normalized?.let { setPlaybackVolume(it) }
    }

    private fun refreshPlaybackState() {
        if (_uiState.value.playbackBackend == PlaybackBackend.VLC) {
            val snapshot = vlcController.snapshot()
            val playbackPhase = when {
                _uiState.value.lastError != null -> PlaybackPhase.ERROR
                snapshot.isPlaying -> PlaybackPhase.PLAYING
                snapshot.durationMs > 0L || snapshot.currentPositionMs > 0L -> PlaybackPhase.PAUSED
                else -> PlaybackPhase.IDLE
            }

            _uiState.update {
                it.copy(
                    playbackPhase = playbackPhase,
                    currentPositionMs = snapshot.currentPositionMs,
                    durationMs = snapshot.durationMs,
                    bufferedPercentage = if (snapshot.durationMs > 0L) {
                        ((snapshot.currentPositionMs * 100L) / snapshot.durationMs).toInt().coerceIn(0, 100)
                    } else {
                        0
                    },
                    currentMediaUrl = snapshot.currentMediaUrl ?: it.currentMediaUrl,
                    playbackVolume = snapshot.volume,
                )
            }
            return
        }

        val playbackPhase = when {
            _uiState.value.lastError != null -> PlaybackPhase.ERROR
            player.playbackState == Player.STATE_BUFFERING -> PlaybackPhase.BUFFERING
            player.isPlaying -> PlaybackPhase.PLAYING
            player.playbackState == Player.STATE_READY && player.playWhenReady -> PlaybackPhase.READY
            player.playbackState == Player.STATE_READY -> PlaybackPhase.PAUSED
            player.playbackState == Player.STATE_ENDED -> PlaybackPhase.ENDED
            else -> PlaybackPhase.IDLE
        }

        val durationMs = player.duration.takeUnless { it == C.TIME_UNSET || it < 0L } ?: 0L
        val currentPositionMs = player.currentPosition.coerceAtLeast(0L)
        val currentUrl = player.currentMediaItem?.localConfiguration?.uri?.toString()

        _uiState.update {
            it.copy(
                playbackPhase = playbackPhase,
                currentPositionMs = currentPositionMs,
                durationMs = durationMs,
                bufferedPercentage = player.bufferedPercentage,
                currentMediaUrl = currentUrl ?: it.currentMediaUrl,
                playbackVolume = player.volume.toDouble(),
            )
        }
    }

    private fun publishState() {
        server?.broadcast(statePayload())
    }

    private fun statePayload(): String {
        val state = _uiState.value
        return json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("type", JsonPrimitive("state"))
                put("host", JsonPrimitive(state.hostAddress ?: ""))
                put("port", JsonPrimitive(state.port ?: 0))
                put("connectionUrl", JsonPrimitive(state.connectionUrl ?: ""))
                put("webSocketUrl", JsonPrimitive(state.webSocketUrl ?: ""))
                put("clientCount", JsonPrimitive(state.clientCount))
                put("playbackBackend", JsonPrimitive(state.playbackBackend.name.lowercase()))
                put("playbackPhase", JsonPrimitive(state.playbackPhase.name.lowercase()))
                put("isPlaying", JsonPrimitive(state.playbackPhase == PlaybackPhase.PLAYING))
                put("currentPositionMs", JsonPrimitive(state.currentPositionMs))
                put("durationMs", JsonPrimitive(state.durationMs))
                put("bufferedPercentage", JsonPrimitive(state.bufferedPercentage))
                put("volume", JsonPrimitive(state.playbackVolume))
                put("currentMediaUrl", JsonPrimitive(state.currentMediaUrl ?: ""))
                put("lastError", JsonPrimitive(state.lastError ?: ""))
                putJsonObject("protocol") {
                    put("play", JsonPrimitive("play + url"))
                    put("pause", JsonPrimitive("pause"))
                    put("seek", JsonPrimitive("seek + positionMs|time|progress"))
                    put("volume", JsonPrimitive("volume + value"))
                    put("format", JsonPrimitive("optional: auto|hls|dash|smoothstreaming|rtsp|progressive|mp4|mkv|webm"))
                    put("container", JsonPrimitive("optional for progressive: mkv|mp4|webm|mp3|aac|flac|wav|ogg"))
                    put("audioCodec", JsonPrimitive("optional: eac3|ddp|ac3|dts|truehd|aac"))
                    put("videoCodec", JsonPrimitive("optional: hevc|h265|avc|vp9"))
                    put("player", JsonPrimitive("optional: exoplayer|vlc"))
                    put("headers", JsonPrimitive("optional: request headers object"))
                }
            },
        )
    }

    private fun buildPlaybackErrorMessage(error: PlaybackException): String {
        val name = error.errorCodeName
        val detail = error.localizedMessage ?: error.message

        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED ->
                "This TV does not support this codec in hardware/software decode. $name${detail?.let { ": $it" } ?: ""}"
            else -> "$name${detail?.let { ": $it" } ?: ""}"
        }
    }

    private fun pausePlayback() {
        if (_uiState.value.playbackBackend == PlaybackBackend.VLC) {
            vlcController.pause()
        } else {
            player.pause()
        }
    }

    private fun resumePlayback() {
        if (_uiState.value.playbackBackend == PlaybackBackend.VLC) {
            vlcController.resume()
        } else {
            player.play()
        }
    }

    private fun stopPlayback() {
        if (_uiState.value.playbackBackend == PlaybackBackend.VLC) {
            vlcController.stop()
        } else {
            player.stop()
            player.clearMediaItems()
        }
    }

    private fun seekPlayback(positionMs: Long) {
        if (_uiState.value.playbackBackend == PlaybackBackend.VLC) {
            vlcController.seekTo(positionMs)
        } else {
            player.seekTo(positionMs)
        }
    }

    private fun setPlaybackVolume(normalized: Double) {
        if (_uiState.value.playbackBackend == PlaybackBackend.VLC) {
            vlcController.setVolume(normalized)
        } else {
            player.volume = normalized.toFloat()
        }
    }

    private fun switchBackend(target: PlaybackBackend) {
        if (target == PlaybackBackend.VLC) {
            player.stop()
            player.clearMediaItems()
        } else {
            vlcController.stop()
        }
        _uiState.update { it.copy(playbackBackend = target) }
    }

    private fun shouldRetryWithVlc(error: PlaybackException, request: PlaybackRequest): Boolean {
        val message = "${error.errorCodeName} ${error.localizedMessage ?: error.message.orEmpty()}".lowercase()
        return request.formatHint?.lowercase() == "progressive" && (
            message.contains("audio") ||
                message.contains("decoder") ||
                message.contains("eac3") ||
                message.contains("ec-3") ||
                message.contains("dolby")
            )
    }
}

data class ReceiverUiState(
    val hostAddress: String? = null,
    val port: Int? = null,
    val connectionUrl: String? = null,
    val webSocketUrl: String? = null,
    val clientCount: Int = 0,
    val playbackBackend: PlaybackBackend = PlaybackBackend.EXOPLAYER,
    val playbackPhase: PlaybackPhase = PlaybackPhase.IDLE,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPercentage: Int = 0,
    val playbackVolume: Double = 1.0,
    val currentMediaUrl: String? = null,
    val lastError: String? = null,
    val qrBitmap: Bitmap? = null,
)

enum class PlaybackPhase {
    IDLE,
    BUFFERING,
    READY,
    PLAYING,
    PAUSED,
    ENDED,
    ERROR,
}
