package com.mlayfer.iptv

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.mlayfer.iptv.ui.AppRoot
import com.mlayfer.iptv.ui.MaskHaiTheme
import com.mlayfer.iptv.ui.RemoteKeys

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaskHaiTheme {
                // The whole UI is Hebrew, so it reads right-to-left regardless of
                // the device locale.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    AppRoot()
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Only fresh presses: holding the button down must not zap through the
        // whole playlist.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (RemoteKeys.dispatch(event)) return true
        }
        return super.dispatchKeyEvent(event)
    }
}
