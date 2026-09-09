package com.mlayfer.iptv.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.mlayfer.iptv.data.Channel
import com.mlayfer.iptv.data.ChannelKind
import com.mlayfer.iptv.data.Http
import com.mlayfer.iptv.data.Programme
import com.mlayfer.iptv.data.StreamProbe
import com.mlayfer.iptv.data.StreamVariants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MAX_AUTO_RETRIES = 3

/** Some CDNs answer 403 to anything that doesn't look like a browser. */
private const val BROWSER_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Mobile Safari/537.36"

@Composable
fun PlayerPanel(
    channel: Channel?,
    now: Programme?,
    next: Programme?,
    isFavorite: Boolean,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onToggleFavorite: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    /** Seconds to start from, for something you were already part way through. */
    resumeAt: Long = 0,
    onProgress: (position: Long, duration: Long) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    // Kept across channel switches so per-channel headers can be swapped in place.
    val httpFactory = remember {
        DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setUserAgent(Http.DEFAULT_USER_AGENT)
    }
    val player = remember {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultDataSource.Factory(context, httpFactory))
            )
            .build()
    }

    var error by remember { mutableStateOf<String?>(null) }
    var errorDetail by remember { mutableStateOf<String?>(null) }
    var diagnosing by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) }
    var buffering by remember { mutableStateOf(false) }
    var retries by remember { mutableIntStateOf(0) }
    var reloadToken by remember { mutableIntStateOf(0) }
    // Every way this channel might be reachable, in the order worth trying.
    // Not an effect key: the ladder is climbed from inside the error listener,
    // without tearing playback setup down and building it again.
    val attempts = remember(channel?.id) { channel?.let(::attemptsFor) ?: emptyList() }
    val attemptIndex = remember { mutableIntStateOf(0) }
    val currentAttempts by rememberUpdatedState(attempts)
    val currentChannel by rememberUpdatedState(channel)

    // Everything this reads is remembered or a State, so the copy captured by the
    // error listener on first composition keeps seeing current values.
    fun start(index: Int) {
        val attempt = currentAttempts.getOrNull(index) ?: return
        val channelNow = currentChannel

        // Unlike a browser, the app can send the headers the playlist asks for.
        httpFactory.setUserAgent(attempt.userAgent)
        val headers = HashMap<String, String>()
        channelNow?.referrer?.let { headers["Referer"] = it }
        httpFactory.setDefaultRequestProperties(headers)

        val builder = MediaItem.Builder().setUri(attempt.url)
        attempt.mimeType?.let { builder.setMimeType(it) }
        player.setMediaItem(builder.build())
        player.prepare()
        // Live has no meaningful position; a film picks up where it stopped.
        if (resumeAt > 0 && channelNow?.kind != ChannelKind.LIVE) {
            player.seekTo(resumeAt * 1000)
        }
        player.playWhenReady = true
    }

    // Where playback got to, written down while watching rather than only on the
    // way out, so a set-top box losing power does not lose the position too.
    LaunchedEffect(channel?.id) {
        val watching = channel ?: return@LaunchedEffect
        if (watching.kind == ChannelKind.LIVE) return@LaunchedEffect
        while (true) {
            delay(10_000)
            val position = player.currentPosition / 1000
            val duration = player.duration.takeIf { it > 0 }?.div(1000) ?: 0
            if (position > 0) onProgress(position, duration)
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
            }

            override fun onPlayerError(e: PlaybackException) {
                // Providers lie about their streams constantly: extension-less
                // HLS, .m3u8 URLs that answer with MPEG-TS, panels whose HLS
                // endpoint is switched off and answer with an HTML page, CDNs
                // that 403 anything that isn't a browser. Rather than trust the
                // playlist, work down the ladder before calling a channel dead.
                // This comes first: each rung fails in a single request, so it is
                // far cheaper than retrying a wrong guess three times.
                val channelNow = currentChannel
                if (channelNow != null && attemptIndex.intValue + 1 < currentAttempts.size) {
                    attemptIndex.intValue += 1
                    retries = 0
                    start(attemptIndex.intValue)
                    return
                }

                // Nothing left to try: a live stream that dropped its connection
                // deserves a couple of silent retries before showing an error.
                if (isRecoverable(e) && retries < MAX_AUTO_RETRIES) {
                    retries += 1
                    scope.launch {
                        delay(1_500)
                        player.prepare()
                        player.play()
                    }
                    return
                }

                // The player's own error code can't tell a removed channel from a
                // blocked device from a bug here — so go ask the server directly.
                error = describe(e)
                errorDetail = detailOf(e)
                val failed = channelNow ?: return
                diagnosing = true
                scope.launch {
                    val probe = withContext(Dispatchers.IO) {
                        StreamProbe.probe(
                            url = failed.url,
                            userAgent = failed.userAgent ?: Http.DEFAULT_USER_AGENT,
                            referrer = failed.referrer,
                            alternatives = StreamVariants.of(failed.url),
                        )
                    }
                    error = StreamProbe.summarize(probe)
                    report = buildReport(failed, e, probe)
                    diagnosing = false
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(channel?.id, reloadToken) {
        error = null
        errorDetail = null
        report = null
        diagnosing = false
        retries = 0
        attemptIndex.intValue = 0
        if (channel == null) {
            player.stop()
            player.clearMediaItems()
            return@LaunchedEffect
        }
        start(0)
    }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (fullscreen) Modifier.weight(1f) else Modifier.aspectRatio(16f / 9f))
                .background(Color.Black)
        ) {
            AndroidView(
                modifier = Modifier.matchParentSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = true
                        keepScreenOn = true
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        isFocusable = true
                        setFullscreenButtonClickListener { onToggleFullscreen() }
                    }
                },
                update = { view ->
                    // On a TV the list normally holds focus; full screen has to hand
                    // it to the player so the remote drives playback.
                    if (fullscreen && !view.hasFocus()) view.requestFocus()
                },
            )

            if (channel == null) {
                Text(
                    text = "בחר ערוץ מהרשימה כדי להתחיל לצפות",
                    color = Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            if (buffering && error == null) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            val message = error
            if (message != null) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.8f))
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = message, color = Color.White)
                    if (diagnosing) {
                        Text(
                            text = "בודק מה השרת מחזיר…",
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    val detail = errorDetail
                    if (detail != null) {
                        Text(
                            text = detail,
                            color = Color.White.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    Row(
                        modifier = Modifier.padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = { reloadToken += 1 }) { Text("נסה שוב") }
                        OutlinedButton(onClick = {
                            channel?.let { openExternally(context, it.url) }
                        }) {
                            Text("נגן חיצוני")
                        }
                        OutlinedButton(onClick = {
                            val text = report ?: channel?.url
                            text?.let { clipboard.setText(AnnotatedString(it)) }
                        }) {
                            Text(if (report != null) "העתק דוח" else "העתק כתובת")
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(start = 4.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The name leads the row, the way the list reads.
            Text(
                text = channel?.name ?: "",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )

            IconButton(onClick = onToggleFavorite, enabled = channel != null) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "מועדפים",
                    tint = if (isFavorite) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            IconButton(onClick = { reloadToken += 1 }, enabled = channel != null) {
                Icon(Icons.Default.Refresh, contentDescription = "טעינה מחדש")
            }
            IconButton(onClick = onPrev) {
                Icon(Icons.Default.KeyboardArrowRight, contentDescription = "הערוץ הקודם")
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "הערוץ הבא")
            }
        }

        if (!fullscreen && now != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(text = now.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    Text(
                        text = "${formatTime(now.start)}–${formatTime(now.stop)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LinearProgressIndicator(
                    progress = { progressOf(now) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .padding(top = 4.dp),
                )
                if (next != null) {
                    Text(
                        text = "אחר כך: ${formatTime(next.start)} · ${next.title}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** One thing to try: a URL, an optional container hint, and the headers to use. */
private data class Attempt(val url: String, val mimeType: String?, val userAgent: String)

private const val MAX_ATTEMPTS = 8

/**
 * Ordered from "what the playlist says" to "what a browser hitting the panel's
 * other endpoints would get". Cheap to walk: a wrong guess fails in one request.
 */
private fun attemptsFor(channel: Channel): List<Attempt> {
    val listUserAgent = channel.userAgent ?: Http.DEFAULT_USER_AGENT
    val urls = StreamVariants.of(channel.url)
    val primary = urls.first()
    val out = LinkedHashSet<Attempt>()

    // The URL as given: extension first, then HLS for the extension-less case,
    // then no hint at all so the bytes themselves decide.
    out.add(Attempt(primary, mimeFor(primary), listUserAgent))
    out.add(Attempt(primary, MimeTypes.APPLICATION_M3U8, listUserAgent))
    out.add(Attempt(primary, null, listUserAgent))

    // The same channel at the panel's other endpoints.
    for (url in urls.drop(1)) out.add(Attempt(url, mimeFor(url), listUserAgent))

    // Last resort: some CDNs serve only what looks like a browser.
    if (listUserAgent != BROWSER_USER_AGENT) {
        for (url in urls) out.add(Attempt(url, mimeFor(url), BROWSER_USER_AGENT))
    }

    return out.take(MAX_ATTEMPTS)
}

private fun mimeFor(url: String): String? {
    val lower = url.lowercase()
    return when {
        lower.contains(".m3u8") -> MimeTypes.APPLICATION_M3U8
        lower.contains(".mpd") -> MimeTypes.APPLICATION_MPD
        else -> null
    }
}

/** The exact failure, so a report about a dead channel is actionable. */
private fun detailOf(e: PlaybackException): String {
    val status = (e.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
    return if (status != null) "${e.errorCodeName} · HTTP $status" else e.errorCodeName
}

/** Everything needed to understand the failure, in one pasteable block. */
private fun buildReport(
    channel: Channel,
    error: PlaybackException,
    probe: StreamProbe.Report,
): String = buildString {
    appendLine("ערוץ: ${channel.name}")
    appendLine("כתובת: ${channel.url}")
    channel.userAgent?.let { appendLine("User-Agent מהרשימה: $it") }
    channel.referrer?.let { appendLine("Referer מהרשימה: $it") }
    appendLine("שגיאת נגן: ${detailOf(error)}")
    appendLine("נוסו ${attemptsFor(channel).size} וריאציות של הכתובת")
    appendLine("בדיקת שרת:")
    append(StreamProbe.technical(probe))
}

private fun openExternally(context: android.content.Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(url), "video/*")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "פתיחה בנגן חיצוני"))
    }
}

private fun isRecoverable(e: PlaybackException): Boolean = when (e.errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    -> true
    else -> false
}

private fun describe(e: PlaybackException): String = when (e.errorCode) {
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
        "השרת של הספק דחה את הבקשה. ייתכן שהמנוי לא פעיל או שהערוץ הוסר."
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    -> "אין חיבור לשידור. בדוק את החיבור לאינטרנט ונסה שוב."
    PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
    -> "לא הצלחתי לנגן את השידור באף פורמט שניסיתי. ייתכן שהערוץ מת, שהספק החזיר דף שגיאה במקום וידאו, או שהוא חסום לאזור הזה."
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    -> "המכשיר לא הצליח לפענח את השידור."
    else -> "לא ניתן להפעיל את הערוץ הזה."
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

private fun progressOf(programme: Programme): Float {
    val span = (programme.stop - programme.start).toFloat()
    if (span <= 0f) return 0f
    val done = (System.currentTimeMillis() - programme.start) / span
    return done.coerceIn(0f, 1f)
}
