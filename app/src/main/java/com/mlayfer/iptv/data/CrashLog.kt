package com.mlayfer.iptv.data

import android.content.Context
import android.os.Build
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the app has to say for itself after it dies.
 *
 * There is no cable into a television box and no developer console on a sofa,
 * so a crash is reported as "the app crashes" and nothing else — which is a
 * true sentence with nothing actionable in it. The trace is worth more than
 * every guess that follows from not having it.
 *
 * The handler writes the trace down synchronously, because the process is on
 * its way out and an asynchronous write would not survive the trip, then hands
 * the crash on to whoever had the job before — the platform still gets to do
 * its own reporting and the app still dies, as it should.
 */
object CrashLog {

    private const val PREFS = "mask_hai_crash"
    private const val KEY_TRACE = "trace"

    fun install(context: Context, versionName: String) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, describe(thread, error, versionName)) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The trace from last time, if there was one. Reading it clears it. */
    fun take(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val trace = prefs.getString(KEY_TRACE, null)
        if (trace != null) prefs.edit().remove(KEY_TRACE).commit()
        return trace
    }

    private fun write(context: Context, text: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TRACE, text)
            // Not apply(): the process is about to end, and an edit that has not
            // reached the disk by then is an edit that never happened.
            .commit()
    }

    /**
     * Everything needed to act on it and nothing that identifies anyone: no
     * portal address, no user name, no password — a stack trace and the make of
     * the box it happened on.
     */
    private fun describe(thread: Thread, error: Throwable, versionName: String): String {
        val stack = StringWriter()
        error.printStackTrace(PrintWriter(stack))
        val when_ = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return buildString {
            appendLine("טלוהים $versionName · $when_")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})")
            appendLine("thread: ${thread.name}")
            appendLine()
            append(stack.toString().take(MAX_TRACE))
        }
    }

    /** Long enough for the frames that matter, short enough to survive a screen. */
    private const val MAX_TRACE = 6000
}
