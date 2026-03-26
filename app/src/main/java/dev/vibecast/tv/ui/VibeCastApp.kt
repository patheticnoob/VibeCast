package dev.vibecast.tv.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.vibecast.tv.PlaybackPhase
import dev.vibecast.tv.ReceiverUiState
import dev.vibecast.tv.playback.PlaybackBackend
import dev.vibecast.tv.playback.VlcPlaybackController
import org.videolan.libvlc.util.VLCVideoLayout

@Composable
fun VibeCastApp(
    uiState: ReceiverUiState,
    player: Player,
    vlcController: VlcPlaybackController,
    onTogglePlayback: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onCycleAudioTrack: () -> Unit,
    onCycleSubtitleTrack: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var controlsVisible by remember(uiState.currentMediaUrl) { mutableStateOf(true) }
    var overlayPulse by remember { mutableIntStateOf(0) }
    val forceControlsVisible = uiState.currentMediaUrl.isNullOrBlank() || when (uiState.playbackPhase) {
        PlaybackPhase.BUFFERING,
        PlaybackPhase.PAUSED,
        PlaybackPhase.ERROR,
        PlaybackPhase.READY,
        PlaybackPhase.ENDED,
        PlaybackPhase.IDLE -> true
        PlaybackPhase.PLAYING -> false
    }

    fun pulseOverlay() {
        controlsVisible = true
        overlayPulse++
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(uiState.currentMediaUrl, uiState.playbackPhase, overlayPulse) {
        controlsVisible = true
        if (!forceControlsVisible) {
            kotlinx.coroutines.delay(3500)
            controlsVisible = false
        }
    }

    MaterialTheme(
        colorScheme = vibeCastColors(),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        handlePlayerKeyEvent(
                            eventType = event.type,
                            key = event.key,
                            hasMedia = !uiState.currentMediaUrl.isNullOrBlank(),
                            controlsVisible = controlsVisible || forceControlsVisible,
                            showOverlay = ::pulseOverlay,
                            onTogglePlayback = onTogglePlayback,
                            onSeekBack = onSeekBack,
                            onSeekForward = onSeekForward,
                        )
                    }
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF050816),
                                Color(0xFF0D1D34),
                                Color(0xFF102A43),
                            ),
                        ),
                    ),
            ) {
                if (!uiState.currentMediaUrl.isNullOrBlank()) {
                    if (uiState.playbackBackend == PlaybackBackend.VLC) {
                        VlcVideoPlayer(controller = vlcController)
                    } else {
                        VideoPlayer(player = player)
                    }
                }

                if (uiState.currentMediaUrl.isNullOrBlank()) {
                    PairingScreen(uiState = uiState)
                } else {
                    PlaybackOverlay(
                        uiState = uiState,
                        visible = controlsVisible || forceControlsVisible,
                        onTogglePlayback = {
                            pulseOverlay()
                            onTogglePlayback()
                        },
                        onSeekBack = {
                            pulseOverlay()
                            onSeekBack()
                        },
                        onSeekForward = {
                            pulseOverlay()
                            onSeekForward()
                        },
                        onCycleAudioTrack = {
                            pulseOverlay()
                            onCycleAudioTrack()
                        },
                        onCycleSubtitleTrack = {
                            pulseOverlay()
                            onCycleSubtitleTrack()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PairingScreen(uiState: ReceiverUiState) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 56.dp, vertical = 40.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Vibe Cast",
                style = MaterialTheme.typography.displaySmall.copy(
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.4.sp,
                ),
                color = Color.White,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Scan. Connect. Play.",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Color(0xFFE8F1FF),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Scan the QR code from your phone, open the controller page, and stream local video to the receiver over the same Wi-Fi.",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFA9BDD6),
            )
            Spacer(modifier = Modifier.height(28.dp))
            StatusLine(label = "HTTP", value = uiState.connectionUrl ?: "Waiting for local IP")
            Spacer(modifier = Modifier.height(12.dp))
            StatusLine(label = "WebSocket", value = uiState.webSocketUrl ?: "Waiting for local IP")
            Spacer(modifier = Modifier.height(12.dp))
            StatusLine(
                label = "Clients",
                value = if (uiState.clientCount == 0) "No controllers connected" else "${uiState.clientCount} connected",
            )
            if (!uiState.lastError.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(18.dp))
                Text(
                    text = uiState.lastError,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFFFFC1B6),
                )
            }
        }

        Spacer(modifier = Modifier.width(32.dp))

        Card(
            modifier = Modifier.width(420.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xEAF7FAFF),
            ),
            shape = RoundedCornerShape(28.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Scan to connect",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color(0xFF09111F),
                )
                Spacer(modifier = Modifier.height(20.dp))
                QrPanel(bitmap = uiState.qrBitmap)
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = uiState.hostAddress ?: "Looking for local network",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF44566C),
                )
            }
        }
    }
}

@Composable
private fun PlaybackOverlay(
    uiState: ReceiverUiState,
    visible: Boolean,
    onTogglePlayback: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onCycleAudioTrack: () -> Unit,
    onCycleSubtitleTrack: () -> Unit,
) {
    if (!visible) {
        return
    }

    val selectedAudio = uiState.audioTracks.firstOrNull { it.selected }?.label ?: "Default audio"
    val selectedSubtitle = uiState.subtitleTracks.firstOrNull { it.selected }?.label
        ?: if (uiState.subtitleTracks.isEmpty()) "No subtitles" else "Subtitles off"
    val title = uiState.currentTitle ?: uiState.currentMediaUrl ?: "Now playing"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(340.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0xE6081020),
                            Color(0xF4081020),
                        ),
                    ),
                ),
        )

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0x88101828))
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Vibe Cast",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                color = Color.White,
            )
            Text(
                text = playbackLabel(uiState.playbackPhase),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Color(0xFFE6EEF9),
            )
            Text(
                text = uiState.playbackBackend.name.lowercase(),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFFC857),
            )
        }

        if (!uiState.lastError.isNullOrBlank()) {
            OverlayCard(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .widthIn(max = 480.dp),
            ) {
                Text(
                    text = "Playback issue",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = uiState.lastError,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFFFC1B6),
                )
            }
        }

        if (uiState.playbackPhase == PlaybackPhase.BUFFERING || uiState.playbackPhase == PlaybackPhase.PAUSED) {
            OverlayCard(
                modifier = Modifier.align(Alignment.Center),
            ) {
                Text(
                    text = if (uiState.playbackPhase == PlaybackPhase.BUFFERING) "Buffering..." else "Paused",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFFD6E5F7),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        OverlayCard(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = uiState.currentMediaUrl ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF99AFC7),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(18.dp))
            LinearProgressIndicator(
                progress = {
                    if (uiState.durationMs > 0L) {
                        (uiState.currentPositionMs.toFloat() / uiState.durationMs.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(999.dp)),
                color = Color(0xFFFFC857),
                trackColor = Color(0x332A3A55),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${formatTime(uiState.currentPositionMs)} / ${formatTime(uiState.durationMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFDCE8F7),
                )
                Text(
                    text = "${uiState.clientCount} controller${if (uiState.clientCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF9CB4D1),
                )
            }
            Spacer(modifier = Modifier.height(18.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VibeControlButton(label = "Replay 10", onClick = onSeekBack)
                VibeControlButton(
                    label = if (uiState.playbackPhase == PlaybackPhase.PLAYING) "Pause" else "Play",
                    primary = true,
                    onClick = onTogglePlayback,
                )
                VibeControlButton(label = "Forward 10", onClick = onSeekForward)
                VibeControlButton(
                    label = "Audio",
                    supportingText = selectedAudio,
                    enabled = uiState.audioTracks.isNotEmpty(),
                    onClick = onCycleAudioTrack,
                )
                VibeControlButton(
                    label = "Subtitles",
                    supportingText = selectedSubtitle,
                    enabled = uiState.subtitleTracks.isNotEmpty(),
                    onClick = onCycleSubtitleTrack,
                )
            }
        }
    }
}

@Composable
private fun VideoPlayer(player: Player) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                this.player = player
                keepScreenOn = true
            }
        },
        update = { it.player = player },
    )
}

@Composable
private fun VlcVideoPlayer(controller: VlcPlaybackController) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            VLCVideoLayout(context).also(controller::bind)
        },
        update = controller::bind,
    )

    DisposableEffect(controller) {
        onDispose {
            controller.unbind()
        }
    }
}

@Composable
private fun QrPanel(bitmap: Bitmap?) {
    Box(
        modifier = Modifier
            .size(320.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Pairing QR code",
                modifier = Modifier
                    .size(280.dp)
                    .clip(RoundedCornerShape(18.dp)),
            )
        } else {
            Text(
                text = "Preparing QR",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF5C6B7C),
            )
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$label  ",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = Color(0xFFFFC857),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFFE8F1FF),
        )
    }
}

@Composable
private fun OverlayCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = Color(0xA6111722),
        ),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
            content = content,
        )
    }
}

@Composable
private fun VibeControlButton(
    label: String,
    supportingText: String? = null,
    enabled: Boolean = true,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) Color(0xFFFFC857) else Color(0x1FFFFFFF),
            contentColor = if (primary) Color(0xFF171000) else Color.White,
            disabledContainerColor = Color(0x1027354D),
            disabledContentColor = Color(0x667D92AA),
        ),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            )
            if (!supportingText.isNullOrBlank()) {
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun handlePlayerKeyEvent(
    eventType: KeyEventType,
    key: Key,
    hasMedia: Boolean,
    controlsVisible: Boolean,
    showOverlay: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
): Boolean {
    if (eventType != KeyEventType.KeyDown || !hasMedia) {
        return false
    }

    return when (key) {
        Key.Enter,
        Key.NumPadEnter,
        Key.DirectionCenter,
        Key.MediaPlayPause,
        Key.MediaPlay,
        Key.MediaPause -> {
            showOverlay()
            onTogglePlayback()
            true
        }

        Key.MediaFastForward -> {
            showOverlay()
            onSeekForward()
            true
        }

        Key.MediaRewind -> {
            showOverlay()
            onSeekBack()
            true
        }

        Key.DirectionLeft -> {
            if (!controlsVisible) {
                showOverlay()
                onSeekBack()
                true
            } else {
                false
            }
        }

        Key.DirectionRight -> {
            if (!controlsVisible) {
                showOverlay()
                onSeekForward()
                true
            } else {
                false
            }
        }

        Key.DirectionUp,
        Key.DirectionDown -> {
            if (!controlsVisible) {
                showOverlay()
                true
            } else {
                false
            }
        }

        else -> false
    }
}

private fun playbackLabel(playbackPhase: PlaybackPhase): String {
    return when (playbackPhase) {
        PlaybackPhase.BUFFERING -> "Buffering"
        PlaybackPhase.PLAYING -> "Playing"
        PlaybackPhase.PAUSED -> "Paused"
        PlaybackPhase.ERROR -> "Playback error"
        PlaybackPhase.ENDED -> "Ended"
        PlaybackPhase.READY -> "Ready"
        PlaybackPhase.IDLE -> "Idle"
    }
}

private fun formatTime(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1_000).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

private fun vibeCastColors() = androidx.compose.material3.darkColorScheme(
    primary = Color(0xFFFFC857),
    onPrimary = Color(0xFF1C1300),
    secondary = Color(0xFF6EC5FF),
    background = Color(0xFF050816),
    surface = Color(0xFF10233E),
    onSurface = Color(0xFFF3F7FC),
)
