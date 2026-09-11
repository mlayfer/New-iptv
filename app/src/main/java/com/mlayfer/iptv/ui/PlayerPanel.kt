package com.mlayfer.iptv.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
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
import androidx.media3.common.C
import androidx.media3.common.Tracks
import com.mlayfer.iptv.R
import com.mlayfer.iptv.data.TrackChoices
import com.mlayfer.iptv.data.Channel
import com.mlayfer.iptv.data.ChannelKind
import com.mlayfer.iptv.data.Http
import com.mlayfer.iptv.data.Playback
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

/** Ten seconds a press, the same step the Tizen player moves by. */
private val SEEK_STEP_MS = Playback.SEEK_STEP * 1000

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
    /**
     * Parked beside a channel list rather than filling the screen. The strip of
     * controls stays — it is how the picture is paused — but the guide under it
     * goes, because the list beside it is already saying what is on.
     */
    compact: Boolean = false,
    /**
     * The rest of this channel's evening, drawn under the picture while the
     * list is open beside it. The space under a picture parked in a corner is
     * the natural place for it, and it is otherwise black.
     */
    schedule: List<Programme> = emptyList(),
    /**
     * Opens the channel list beside the picture. Null where there is nothing to
     * browse — a film has no other channels. A phone has no D-pad to open it
     * with, so this button is the only way in there, and it is the discoverable
     * way in on a television too.
     */
    onBrowse: (() -> Unit)? = null,
    onToggleFullscreen: () -> Unit,
    onToggleFavorite: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    /** Seconds to start from, for something you were already part way through. */
    resumeAt: Long = 0,
    onProgress: (position: Long, duration: Long) -> Unit = { _, _ -> },
    /** What is playing after this — the episodes of an open series, in order. */
    queue: List<Channel> = emptyList(),
    onPlayItem: (Channel) -> Unit = {},
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
            // The rewind and fast-forward buttons, and the D-pad on a TV, move by
            // these — the same ten seconds the Tizen player uses.
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .setSeekForwardIncrementMs(SEEK_STEP_MS)
            .build()
    }

    var error by remember { mutableStateOf<String?>(null) }
    var errorDetail by remember { mutableStateOf<String?>(null) }
    var diagnosing by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) }
    var buffering by remember { mutableStateOf(false) }
    // What the stream turned out to carry. Read from the player rather than
    // guessed, because it only knows once the stream is open.
    var tracks by remember { mutableStateOf<Tracks>(Tracks.EMPTY) }
    var trackMenu by remember { mutableStateOf<Int?>(null) }
    // Whether subtitles are off is a setting on the player, not a snapshot the
    // UI is watching; kept here so the icon reflects it the moment it changes.
    var subsOff by remember { mutableStateOf(false) }
    var retries by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    // The controls come and go the way they do on a television: any press brings
    // them back, and a few seconds of stillness takes them away again. A strip
    // of buttons parked across the bottom of a film is not a control, it is a
    // thing in the way.
    var controlsShown by remember { mutableStateOf(true) }
    var wake by remember { mutableIntStateOf(0) }
    var reloadToken by remember { mutableIntStateOf(0) }
    // Every way this channel might be reachable, in the order worth trying.
    // Not an effect key: the ladder is climbed from inside the error listener,
    // without tearing playback setup down and building it again.
    val attempts = remember(channel?.id) { channel?.let(::attemptsFor) ?: emptyList() }
    val attemptIndex = remember { mutableIntStateOf(0) }
    val currentAttempts by rememberUpdatedState(attempts)
    val currentChannel by rememberUpdatedState(channel)
    val currentQueue by rememberUpdatedState(queue)
    val currentPlayItem by rememberUpdatedState(onPlayItem)

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
                // A finished episode rolls into the next one, the way a season
                // is watched. Nothing follows a film, or the last episode.
                if (playbackState == Player.STATE_ENDED) {
                    val watching = currentChannel ?: return
                    val next = currentQueue.indexOfFirst { it.id == watching.id }
                        .takeIf { it >= 0 }
                        ?.let { currentQueue.getOrNull(it + 1) }
                    if (next != null) currentPlayItem(next)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onTracksChanged(available: Tracks) {
                tracks = available
                subsOff = TrackChoices.subtitlesOff(player)
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

    // Windowed, the row is part of the layout and always there. Full screen, it
    // is an overlay, and it leaves.
    LaunchedEffect(wake, fullscreen, channel?.id) {
        controlsShown = true
        if (!fullscreen) return@LaunchedEffect
        delay(CONTROLS_LINGER)
        controlsShown = false
    }

    // Only worth reading while someone is looking at it.
    LaunchedEffect(controlsShown, channel?.id) {
        while (controlsShown) {
            position = player.currentPosition
            duration = player.duration.takeIf { it > 0 } ?: 0L
            delay(500)
        }
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

    val transport = remember { FocusRequester() }
    val picture = remember { FocusRequester() }

    // Focus has to live somewhere, always.
    //
    // When the strip leaves, its buttons leave with it, and Compose is left with
    // nothing focused at all — at which point no key reaches anything and the
    // remote looks broken, which is exactly what it did. The picture itself
    // holds focus in between, so a press always has somewhere to land.
    LaunchedEffect(controlsShown, fullscreen, channel?.id) {
        if (!fullscreen) return@LaunchedEffect
        // The node being asked for focus has only just entered the tree.
        withFrameNanos { }
        runCatching {
            if (controlsShown && channel != null) transport.requestFocus() else picture.requestFocus()
        }
    }

    // The same strip in both places: parked under the picture when the player
    // shares the screen, laid over the bottom of it when it has the lot.
    /** Everything that is not playback: it keeps out of the transport's way. */
    val extras: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBrowse != null) {
                IconButton(onClick = onBrowse, enabled = channel != null) {
                    Icon(
                        Icons.AutoMirrored.Filled.List,
                        contentDescription = "ערוצים",
                        tint = if (compact) Ink.Accent else Ink.Dim,
                    )
                }
            }
            IconButton(onClick = onToggleFavorite, enabled = channel != null) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "מועדפים",
                    tint = if (isFavorite) Ink.Accent else Ink.Dim,
                )
            }
            IconButton(onClick = { reloadToken += 1 }, enabled = channel != null) {
                Icon(Icons.Default.Refresh, contentDescription = "טעינה מחדש", tint = Ink.Dim)
            }

            // Offered only when the stream actually carries a choice; a button
            // that opens an empty list is worse than no button.
            val audio = remember(tracks) { TrackChoices.choicesFor(tracks, C.TRACK_TYPE_AUDIO) }
            val subs = remember(tracks) { TrackChoices.choicesFor(tracks, C.TRACK_TYPE_TEXT) }

            if (audio.size > 1) {
                Box {
                    IconButton(onClick = { trackMenu = C.TRACK_TYPE_AUDIO }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_audio_track),
                            contentDescription = "שמע",
                            tint = Ink.Dim,
                        )
                    }
                    TrackMenu(
                        open = trackMenu == C.TRACK_TYPE_AUDIO,
                        choices = audio,
                        offLabel = null,
                        offSelected = false,
                        onDismiss = { trackMenu = null },
                        onPick = { TrackChoices.choose(player, tracks, C.TRACK_TYPE_AUDIO, it); trackMenu = null },
                        onOff = {},
                    )
                }
            }

            if (subs.isNotEmpty()) {
                Box {
                    IconButton(onClick = { trackMenu = C.TRACK_TYPE_TEXT }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_subtitles),
                            contentDescription = "כתוביות",
                            tint = if (subsOff) Ink.Dim else Ink.Accent,
                        )
                    }
                    TrackMenu(
                        open = trackMenu == C.TRACK_TYPE_TEXT,
                        choices = subs,
                        offLabel = "בלי כתוביות",
                        offSelected = subsOff,
                        onDismiss = { trackMenu = null },
                        onPick = {
                            TrackChoices.choose(player, tracks, C.TRACK_TYPE_TEXT, it)
                            subsOff = false
                            trackMenu = null
                        },
                        onOff = {
                            TrackChoices.turnOff(player, C.TRACK_TYPE_TEXT)
                            subsOff = true
                            trackMenu = null
                        },
                    )
                }
            }
        }
    }

    val controlRow: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (fullscreen) {
                        Color.Black.copy(alpha = 0.82f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    }
                )
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            // The name, and beside it everything that is not playback. On a
            // phone these used to sit at the end of the same row the transport
            // was centred in, and the two drew straight over each other.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Full screen, the channel's name is not the answer to "what am
                // I watching" — the programme is. The strip under the picture is
                // the only place that can say so, so it says both: the channel,
                // and what is on it now, with what follows underneath.
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = channel?.name.orEmpty(),
                        fontSize = tzSp(19),
                        fontWeight = if (fullscreen && now != null) FontWeight.Bold else null,
                        color = if (fullscreen && now != null) Ink.Bright else Ink.Dim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (fullscreen && now != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = now.title,
                                fontSize = tzSp(21),
                                fontWeight = FontWeight.Bold,
                                color = Ink.Accent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(Modifier.width(tz(12)))
                            Text(
                                text = "${formatTime(now.start)}–${formatTime(now.stop)}",
                                fontSize = tzSp(17),
                                color = Ink.Faint,
                                maxLines = 1,
                            )
                        }
                        LinearProgressIndicator(
                            progress = { progressOf(now) },
                            color = Ink.Accent,
                            trackColor = Ink.Line,
                            modifier = Modifier
                                .padding(top = tz(6))
                                .fillMaxWidth(if (isWide) 0.5f else 1f)
                                .height(tz(5)),
                        )
                        if (next != null) {
                            Text(
                                text = "אחר כך · ${formatTime(next.start)} ${next.title}",
                                fontSize = tzSp(17),
                                color = Ink.Dim,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = tz(4)),
                            )
                        }
                    }
                }
                extras()
            }
            // How far into a film this is — and a way to move it. A line that
            // only reports is half a control. Live television has neither.
            if (duration > 0) {
                Scrubber(
                    position = position,
                    duration = duration,
                    onSeek = { player.seekTo(it) },
                )
            }
            // Playback on its own line, read left to right like every player
            // ever made: back, then the big one, then on.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                ) {
                        // These two step through whatever list you are in, so
                        // they are named after it: a channel out in the guide, an
                        // episode inside a series.
                        val unit = if (channel?.kind == ChannelKind.LIVE) "הערוץ" else "הפרק"
                        IconButton(onClick = onPrev) {
                            Icon(
                                Icons.Default.KeyboardArrowLeft,
                                contentDescription = "$unit הקודם",
                                tint = Ink.Bright,
                            )
                        }
                        // Only a recording can be moved through; live has nowhere
                        // to go.
                        if (duration > 0) {
                            IconButton(onClick = { player.seekBack() }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_rewind),
                                    contentDescription = "אחורה",
                                    tint = Ink.Bright,
                                )
                            }
                        }

                        // The button this whole strip exists for. It was one icon
                        // among nine at the end of a row, which is not where
                        // anybody looks for it: it is the middle, and it is big.
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(if (channel != null) Ink.Accent else Ink.Surface)
                                .focusRequester(transport)
                                .focusHighlight(CircleShape, border = false)
                                .clickable(enabled = channel != null) {
                                    if (player.isPlaying) player.pause() else player.play()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (playing) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_pause),
                                    contentDescription = "השהיה",
                                    tint = Ink.OnAccent,
                                    modifier = Modifier.size(30.dp),
                                )
                            } else {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = "הפעלה",
                                    tint = Ink.OnAccent,
                                    modifier = Modifier.size(34.dp),
                                )
                            }
                        }

                        if (duration > 0) {
                            IconButton(onClick = { player.seekForward() }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_forward),
                                    contentDescription = "קדימה",
                                    tint = Ink.Bright,
                                )
                            }
                        }
                        IconButton(onClick = onNext) {
                            Icon(
                                Icons.Default.KeyboardArrowRight,
                                contentDescription = "$unit הבא",
                                tint = Ink.Bright,
                            )
                        }
                }
            }
        }
    }

    // Full screen, the strip is the only thing that can hold focus, and it should
    // hold it the moment it appears — otherwise OK does nothing.
    LaunchedEffect(controlsShown, fullscreen) {
        if (controlsShown && fullscreen && channel != null) {
            runCatching { transport.requestFocus() }
        }
    }

    Column(
        modifier = modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            // Back is not ours to swallow: consuming it here would leave a film
            // with no way out of it. The remote's own keys are the only ones
            // this strip has any business with.
            val fromTheRemote = event.key == Key.DirectionUp ||
                event.key == Key.DirectionDown ||
                event.key == Key.DirectionLeft ||
                event.key == Key.DirectionRight ||
                event.key == Key.DirectionCenter ||
                event.key == Key.Enter ||
                event.key == Key.NumPadEnter
            if (!fromTheRemote) return@onPreviewKeyEvent false

            val wasHidden = !controlsShown
            wake += 1
            // While the strip is away, the first press only brings it back. That
            // is what every television does, and it stops a stray arrow from
            // skipping an episode you could not see you were about to skip.
            wasHidden && fullscreen
        },
    ) {
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
                        // Media3 brings its own controls, and on a television
                        // the two layers fought each other for the remote: the
                        // built-in one never appeared, and this one had no
                        // pause, so an episode could be started and not stopped.
                        // There is one set of controls now, and it is this file's.
                        useController = false
                        keepScreenOn = true
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        isFocusable = false
                    }
                },
                update = { view ->
                    if (view.player !== player) view.player = player
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

            // A finger has no D-pad. The strip hides itself after a few seconds
            // and the only thing that brought it back was a key press, so on a
            // phone it hid in the middle of an episode and stayed hidden. The
            // picture is the button there: a tap shows the strip, another hides
            // it. Keyed on the flag so the tap always reads the current one.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(controlsShown) {
                        detectTapGestures {
                            if (controlsShown) controlsShown = false else wake += 1
                        }
                    }
            )

            if (fullscreen && !controlsShown) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .focusRequester(picture)
                        .focusable()
                )
            }

            if (fullscreen && controlsShown) {
                Box(modifier = Modifier.align(Alignment.BottomCenter)) { controlRow() }
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

        if (!fullscreen) controlRow()

        // Parked beside the list: the picture, its controls, and then what is
        // on this channel for the rest of the evening.
        if (compact && schedule.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = tz(14), start = tz(4), end = tz(4)),
                verticalArrangement = Arrangement.spacedBy(tz(8)),
            ) {
                Text(
                    text = "לוח השידורים",
                    fontSize = tzSp(20),
                    fontWeight = FontWeight.Bold,
                    lineHeight = tzSp(24),
                    color = Ink.Bright,
                )
                for (entry in schedule) {
                    val onAir = entry === now
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = formatTime(entry.start),
                            fontSize = tzSp(18),
                            lineHeight = tzSp(22),
                            color = if (onAir) Ink.Accent else Ink.Faint,
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(tz(14)))
                        Text(
                            text = entry.title,
                            fontSize = tzSp(18),
                            lineHeight = tzSp(22),
                            fontWeight = if (onAir) FontWeight.Bold else FontWeight.Normal,
                            color = if (onAir) Ink.Bright else Ink.Dim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        if (!fullscreen && !compact && now != null) {
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

/**
 * Where the film is, and where you want it.
 *
 * This was a progress line: it said how far in you were and offered no way to
 * change it, which on a phone is the one thing a finger is for. Now it is the
 * bar itself — drag it, or touch a point in it.
 *
 * Drawn left to right whatever the page does. A timeline is read as a timeline
 * rather than as a sentence, and the Tizen build flips its control row to LTR
 * for exactly this reason.
 */
@Composable
private fun Scrubber(position: Long, duration: Long, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var held by remember { mutableFloatStateOf(0f) }
    val playhead = (position.toFloat() / duration).coerceIn(0f, 1f)
    val shown = if (dragging) held else playhead

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
        ) {
            Text(
                text = clockOf((shown * duration).toLong()),
                fontSize = tzSp(17),
                color = Ink.Dim,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(28.dp)
                    .padding(horizontal = 10.dp)
                    // A four-dp line is a hard thing to hit with a thumb, so the
                    // whole height of this box takes the touch.
                    .pointerInput(duration) {
                        detectTapGestures { at ->
                            onSeek(((at.x / size.width).coerceIn(0f, 1f) * duration).toLong())
                        }
                    }
                    .pointerInput(duration) {
                        detectHorizontalDragGestures(
                            onDragStart = { at ->
                                dragging = true
                                held = (at.x / size.width).coerceIn(0f, 1f)
                            },
                            onDragEnd = {
                                onSeek((held * duration).toLong())
                                dragging = false
                            },
                            onDragCancel = { dragging = false },
                        ) { change, _ ->
                            held = (change.position.x / size.width).coerceIn(0f, 1f)
                        }
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.25f))
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(shown)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(Ink.Accent)
                )
                // The handle, so it reads as something you may take hold of.
                Box(
                    modifier = Modifier.fillMaxWidth(shown),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Box(
                        modifier = Modifier
                            .size(if (dragging) 18.dp else 14.dp)
                            .clip(CircleShape)
                            .background(Ink.Accent)
                    )
                }
            }
            Text(text = clockOf(duration), fontSize = tzSp(17), color = Ink.Dim)
        }
    }
}

/** Hours only when there are hours. */
private fun clockOf(millis: Long): String {
    val total = (millis / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

private fun progressOf(programme: Programme): Float {
    val span = (programme.stop - programme.start).toFloat()
    if (span <= 0f) return 0f
    val done = (System.currentTimeMillis() - programme.start) / span
    return done.coerceIn(0f, 1f)
}

/**
 * The list behind the sound and subtitle buttons. Short enough that a dropdown
 * beats a dialog, and the one in use carries a tick so you can see where you
 * are without reading every line.
 */
@Composable
private fun TrackMenu(
    open: Boolean,
    choices: List<TrackChoices.Choice>,
    /** Non-null only for subtitles: sound cannot be switched off. */
    offLabel: String?,
    offSelected: Boolean,
    onDismiss: () -> Unit,
    onPick: (TrackChoices.Choice) -> Unit,
    onOff: () -> Unit,
) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        if (offLabel != null) {
            DropdownMenuItem(
                text = { Text(offLabel) },
                onClick = onOff,
                leadingIcon = {
                    if (offSelected) Icon(Icons.Default.Check, contentDescription = null)
                },
            )
        }
        choices.forEach { choice ->
            DropdownMenuItem(
                text = { Text(choice.label) },
                onClick = { onPick(choice) },
                leadingIcon = {
                    if (choice.selected && !offSelected) {
                        Icon(Icons.Default.Check, contentDescription = null)
                    }
                },
            )
        }
    }
}

/** Long enough to read the row, short enough not to sit on top of a film. */
private const val CONTROLS_LINGER = 4_500L
