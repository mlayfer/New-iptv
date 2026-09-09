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
import com.mlayfer.iptv.data.Http
import com.mlayfer.iptv.data.Programme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MAX_AUTO_RETRIES = 3

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
    var buffering by remember { mutableStateOf(false) }
    var retries by remember { mutableIntStateOf(0) }
    var reloadToken by remember { mutableIntStateOf(0) }
    // Which container guess we are on. Not an effect key: the ladder is climbed
    // from inside the error listener, without restarting playback setup.
    val attempt = remember { mutableIntStateOf(0) }
    val currentChannel by rememberUpdatedState(channel)

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
            }

            override fun onPlayerError(e: PlaybackException) {
                // Live streams drop connections routinely; a couple of silent
                // retries beat showing an error for a hiccup.
                if (isRecoverable(e) && retries < MAX_AUTO_RETRIES) {
                    retries += 1
                    scope.launch {
                        delay(1_500)
                        player.prepare()
                        player.play()
                    }
                    return
                }

                // Providers lie about containers constantly: extension-less HLS,
                // .m3u8 URLs that answer with MPEG-TS, playlists served as
                // text/html. Rather than trust the URL, work down the ladder of
                // container guesses before calling the channel unplayable.
                val channelNow = currentChannel
                if (isContainerError(e) && channelNow != null && attempt.intValue < LAST_ATTEMPT) {
                    attempt.intValue += 1
                    retries = 0
                    player.setMediaItem(mediaItemFor(channelNow, attempt.intValue))
                    player.prepare()
                    player.play()
                    return
                }

                error = describe(e)
                errorDetail = detailOf(e)
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
        retries = 0
        attempt.intValue = 0
        val current = channel
        if (current == null) {
            player.stop()
            player.clearMediaItems()
            return@LaunchedEffect
        }

        // Unlike a browser, the app can send the headers the playlist asks for.
        httpFactory.setUserAgent(current.userAgent ?: Http.DEFAULT_USER_AGENT)
        val headers = HashMap<String, String>()
        current.referrer?.let { headers["Referer"] = it }
        httpFactory.setDefaultRequestProperties(headers)

        player.setMediaItem(mediaItemFor(current, 0))
        player.prepare()
        player.playWhenReady = true
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
                            channel?.let { clipboard.setText(AnnotatedString(it.url)) }
                        }) {
                            Text("העתק כתובת")
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrev) {
                Icon(Icons.Default.KeyboardArrowRight, contentDescription = "הערוץ הקודם")
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "הערוץ הבא")
            }
            IconButton(onClick = { reloadToken += 1 }, enabled = channel != null) {
                Icon(Icons.Default.Refresh, contentDescription = "טעינה מחדש")
            }
            IconButton(onClick = onToggleFavorite, enabled = channel != null) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "מועדפים",
                    tint = if (isFavorite) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = channel?.name ?: "",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }

        if (!fullscreen && now != null) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
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

/** Last rung of the container ladder in [mediaItemFor]. */
private const val LAST_ATTEMPT = 2

/**
 * One rung of the container ladder:
 * 0 — believe the URL's extension (or let ExoPlayer infer when there is none)
 * 1 — force HLS, which covers the very common extension-less live playlist
 * 2 — no hint at all, so the bytes themselves decide (MPEG-TS, MP4, …)
 */
private fun mediaItemFor(channel: Channel, attempt: Int): MediaItem {
    val builder = MediaItem.Builder().setUri(channel.url)
    when (attempt) {
        0 -> mimeFor(channel.url)?.let { builder.setMimeType(it) }
        1 -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        else -> Unit
    }
    return builder.build()
}

private fun mimeFor(url: String): String? {
    val lower = url.lowercase()
    return when {
        lower.contains(".m3u8") -> MimeTypes.APPLICATION_M3U8
        lower.contains(".mpd") -> MimeTypes.APPLICATION_MPD
        else -> null
    }
}

/** Errors that a different container guess could plausibly fix. */
private fun isContainerError(e: PlaybackException): Boolean = when (e.errorCode) {
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
    PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
    -> true
    else -> false
}

/** The exact failure, so a report about a dead channel is actionable. */
private fun detailOf(e: PlaybackException): String {
    val status = (e.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
    return if (status != null) "${e.errorCodeName} · HTTP $status" else e.errorCodeName
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
