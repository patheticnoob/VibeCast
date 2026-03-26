package dev.vibecast.tv.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
) {
    MaterialTheme(
        colorScheme = vibeCastColors(),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
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
                    PlaybackOverlay(uiState = uiState)
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
private fun PlaybackOverlay(uiState: ReceiverUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            OverlayCard {
                Text(
                    text = "Vibe Cast",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = uiState.connectionUrl ?: "Waiting for local address",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFFD5E4F8),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Backend: ${uiState.playbackBackend.name.lowercase()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFA9BDD6),
                )
            }

            OverlayCard {
                Text(
                    text = when (uiState.playbackPhase) {
                        PlaybackPhase.BUFFERING -> "Buffering"
                        PlaybackPhase.PLAYING -> "Playing"
                        PlaybackPhase.PAUSED -> "Paused"
                        PlaybackPhase.ERROR -> "Playback error"
                        PlaybackPhase.ENDED -> "Ended"
                        PlaybackPhase.READY -> "Ready"
                        PlaybackPhase.IDLE -> "Idle"
                    },
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "${formatTime(uiState.currentPositionMs)} / ${formatTime(uiState.durationMs)}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFFD5E4F8),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "${uiState.clientCount} controller${if (uiState.clientCount == 1) "" else "s"} connected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFA9BDD6),
                )
            }
        }

        OverlayCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = uiState.currentMediaUrl ?: "",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFFE9F3FF),
            )
            if (!uiState.lastError.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = uiState.lastError,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFFFC1B6),
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
