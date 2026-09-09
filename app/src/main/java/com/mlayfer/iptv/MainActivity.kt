package com.mlayfer.iptv

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.WindowCompat
import com.mlayfer.iptv.ui.AppRoot
import com.mlayfer.iptv.ui.LocalIsTv
import com.mlayfer.iptv.ui.TelohimTheme
import com.mlayfer.iptv.ui.isTelevision
import com.mlayfer.iptv.ui.RemoteKeys

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // The app is dark everywhere, so the system bar icons have to be light.
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        setContent {
            TelohimTheme {
                // The whole UI is Hebrew, so it reads right-to-left regardless of
                // the device locale.
                CompositionLocalProvider(
                    LocalLayoutDirection provides LayoutDirection.Rtl,
                    LocalIsTv provides isTelevision(this),
                ) {
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
