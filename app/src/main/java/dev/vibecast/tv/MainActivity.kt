package dev.vibecast.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.vibecast.tv.ui.VibeCastApp

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<VibeCastViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val uiState by viewModel.uiState.collectAsState()
            VibeCastApp(
                uiState = uiState,
                player = viewModel.player,
                vlcController = viewModel.vlcController,
            )
        }
    }
}
