package com.mlayfer.iptv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mlayfer.iptv.data.CrashLog

@Composable
fun AppRoot(viewModel: AppViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // A crash from last time is the first thing the app says, before it gets
    // the chance to do the same thing again. Read once: reading clears it.
    val context = LocalContext.current
    var crash by remember { mutableStateOf(CrashLog.take(context)) }
    crash?.let { trace ->
        CrashScreen(trace) { crash = null }
        return
    }

    when (state.screen) {
        Screen.SOURCES -> SourcesScreen(state, viewModel)
        Screen.CHOOSE -> ChooseScreen(state, viewModel)
        Screen.HOME -> HomeScreen(state, viewModel)
        Screen.CHANNELS -> ChannelsScreen(state, viewModel)
        Screen.TITLE -> TitleScreen(state, viewModel)
    }
}
