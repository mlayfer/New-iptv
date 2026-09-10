package com.mlayfer.iptv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun AppRoot(viewModel: AppViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    when (state.screen) {
        Screen.SOURCES -> SourcesScreen(state, viewModel)
        Screen.CHOOSE -> ChooseScreen(state, viewModel)
        Screen.HOME -> HomeScreen(state, viewModel)
        Screen.CHANNELS -> ChannelsScreen(state, viewModel)
    }
}
