package com.mlayfer.iptv.ui

import android.view.KeyEvent

/**
 * TV remotes deliver their keys to whichever view holds focus, and on a TV that
 * is usually the channel list — which legitimately wants the D-pad for itself.
 * The activity therefore gets first look at every key press and offers it to the
 * screen, which decides per key whether it means "change channel" or should fall
 * through to normal focus navigation.
 */
object RemoteKeys {

    private var handler: ((KeyEvent) -> Boolean)? = null

    fun setHandler(newHandler: ((KeyEvent) -> Boolean)?) {
        handler = newHandler
    }

    fun dispatch(event: KeyEvent): Boolean = handler?.invoke(event) ?: false
}
