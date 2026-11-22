package com.example.codeassistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                val vm: ChatViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                ChatScreen(
                    state = state,
                    onSend = vm::send,
                    onStop = vm::stop,
                    onClear = vm::clear,
                    onDismissError = vm::dismissError,
                    onSaveSettings = vm::saveSettings,
                )
            }
        }
    }
}
