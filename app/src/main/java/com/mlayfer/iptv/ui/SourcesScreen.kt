package com.mlayfer.iptv.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mlayfer.iptv.BuildConfig
import com.mlayfer.iptv.data.Playlist
import com.mlayfer.iptv.data.PlaylistSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

private enum class SourceKind { URL, FILE, XTREAM }

/** Portals worth offering as one-press choices instead of typing them out. */
private val KNOWN_SERVERS = listOf(
    "http://ilvips.com:80",
    "http://ilvip.net:80",
)

/**
 * Where the content comes from.
 *
 * The Tizen build puts this on a single panel in the middle of the screen: a
 * title, a row of source tabs, the two or three fields that source needs, and
 * one button. This is that panel, at those measurements — which is the whole
 * difference between a form and a screen you can read from a sofa.
 */
@Composable
fun SourcesScreen(state: UiState, viewModel: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var kind by remember { mutableStateOf(SourceKind.URL) }
    var name by remember { mutableStateOf("") }
    var epgUrl by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var includeVod by remember { mutableStateOf(true) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()
                            ?.use { it.readText() }
                    }.getOrNull()
                }
                if (text != null) {
                    content = text
                    fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "קובץ נבחר"
                    if (name.isBlank()) name = fileName.removeSuffix(".m3u").removeSuffix(".m3u8")
                }
            }
        }
    }

    fun submit(source: PlaylistSource) {
        viewModel.addPlaylist(
            Playlist(
                id = "pl_" + System.currentTimeMillis().toString(36) + Random.nextInt(1000),
                name = name.trim(),
                source = source,
                epgUrl = epgUrl.trim().ifBlank { null },
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Ink.SurfaceLow) {
        // The scroll is outside the panel, not inside it. A field being typed
        // into has to be able to climb to the top of the screen, and it can only
        // do that if there is something below it to scroll past.
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Nothing but text fields on this screen, so it has to stay clear
                // of the status bar, the navigation bar and the keyboard alike.
                .windowInsetsPadding(WindowInsets.systemBars)
                .imePadding()
                .tvSafeArea()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                Text(
                    text = "טלוהים",
                    fontSize = tzSp(38),
                    fontWeight = FontWeight.ExtraBold,
                    color = Ink.Bright,
                )
                Text("טלוויזיה בלייב • סרטים • סדרות", fontSize = tzSp(18), color = Ink.Faint)
                Text(
                    text = if (LocalIsTv.current) "Android TV" else "Android",
                    fontSize = tzSp(18),
                    color = Ink.Faint,
                )
            }
            Spacer(Modifier.height(tz(40)))

            val panel = RoundedCornerShape(tz(26))
            Column(
                modifier = Modifier
                    .widthIn(max = tz(1000))
                    .fillMaxWidth()
                    .clip(panel)
                    .background(
                        Brush.verticalGradient(listOf(Color(0xFF1A1A1D), Color(0xFF0E0E10)))
                    )
                    .border(1.dp, Ink.Line, panel)
                    .padding(horizontal = tz(40), vertical = tz(26)),
                verticalArrangement = Arrangement.spacedBy(tz(14)),
            ) {
                Text(
                    text = if (state.playlists.isEmpty()) "ברוכים הבאים לטלוהים" else "מקורות",
                    fontSize = tzSp(38),
                    fontWeight = FontWeight.ExtraBold,
                    color = Ink.Bright,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "הזן מקור IPTV ולאחר מכן תוכל לבחור בין טלוויזיה בלייב לבין סרטים וסדרות.",
                    fontSize = tzSp(19),
                    color = Ink.Dim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (LocalIsTv.current) {
                    Text(
                        text = "אישור פותח את המקלדת · \"סיום\" או Back סוגרים אותה",
                        fontSize = tzSp(17),
                        color = Ink.Faint,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (state.playlists.isNotEmpty()) {
                    SavedPlaylists(state, viewModel)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(tz(12))) {
                    SourceTab("קישור M3U", kind == SourceKind.URL, Modifier.weight(1f)) {
                        kind = SourceKind.URL
                    }
                    SourceTab("קובץ", kind == SourceKind.FILE, Modifier.weight(1f)) {
                        kind = SourceKind.FILE
                    }
                    SourceTab("Xtream Codes", kind == SourceKind.XTREAM, Modifier.weight(1f)) {
                        kind = SourceKind.XTREAM
                    }
                }

                when (kind) {
                    SourceKind.URL -> {
                        LabeledField(
                            label = "כתובת M3U",
                            value = url,
                            onValueChange = { url = it },
                            placeholder = "http://.../playlist.m3u",
                        )
                        Optionals(name, { name = it }, epgUrl, { epgUrl = it })
                        BigButton(
                            label = "טען רשימה",
                            enabled = url.isNotBlank() && !state.addBusy,
                            busy = state.addBusy,
                        ) { submit(PlaylistSource.Url(url.trim())) }
                    }

                    SourceKind.FILE -> {
                        BigButton(
                            label = fileName.ifBlank { "בחירת קובץ M3U מהמכשיר" },
                            enabled = !state.addBusy,
                            busy = false,
                            primary = false,
                        ) { picker.launch(arrayOf("*/*")) }
                        LabeledField(
                            label = "או הדבקה של תוכן ה-M3U",
                            value = content,
                            onValueChange = { content = it },
                            singleLine = false,
                            minLines = 4,
                        )
                        Optionals(name, { name = it }, epgUrl, { epgUrl = it })
                        BigButton(
                            label = "טען רשימה",
                            enabled = content.isNotBlank() && !state.addBusy,
                            busy = state.addBusy,
                        ) { submit(PlaylistSource.Text(content)) }
                    }

                    SourceKind.XTREAM -> {
                        LabeledField(
                            label = "כתובת השרת",
                            value = server,
                            onValueChange = { server = it },
                            placeholder = "http://portal.example.com:8080",
                        )

                        // Typing a portal address on a remote is miserable, so the
                        // known ones are one press. They are only worth offering
                        // while the box is empty: once it holds an address, a chip
                        // repeating it says nothing and reads as a stray button.
                        if (server.isBlank()) {
                            Text("או בחר פורטל מוכר", fontSize = tzSp(16), color = Ink.Faint)
                            Row(horizontalArrangement = Arrangement.spacedBy(tz(8))) {
                                KNOWN_SERVERS.forEach { option ->
                                    ServerSuggestion(option, Modifier.weight(1f)) { server = option }
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(tz(12))) {
                            LabeledField(
                                label = "שם משתמש",
                                value = username,
                                onValueChange = { username = it },
                                modifier = Modifier.weight(1f),
                            )
                            LabeledField(
                                label = "סיסמה",
                                value = password,
                                onValueChange = { password = it },
                                password = true,
                                modifier = Modifier.weight(1f),
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(tz(14)))
                                .focusHighlight(RoundedCornerShape(tz(14)), border = false)
                                .clickable { includeVod = !includeVod }
                                .padding(vertical = tz(6)),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Tick(includeVod)
                            Spacer(Modifier.width(tz(10)))
                            Text(
                                text = "לכלול סרטים וסדרות · טעינה איטית יותר במנויים גדולים",
                                fontSize = tzSp(19),
                                color = Ink.Dim,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        BigButton(
                            label = "התחברות לפורטל",
                            enabled = server.isNotBlank() && username.isNotBlank() &&
                                password.isNotBlank() && !state.addBusy,
                            busy = state.addBusy,
                        ) {
                            submit(
                                PlaylistSource.Xtream(
                                    server = server.trim(),
                                    username = username.trim(),
                                    password = password,
                                    includeVod = includeVod,
                                )
                            )
                        }
                    }
                }

                val addError = state.addError
                if (addError != null) {
                    Text(
                        text = addError,
                        fontSize = tzSp(19),
                        color = Color(0xFFFFB4A8),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Text(
                    // Which build this is, said out loud. "Is this the new one?"
                    // should be readable off the screen, not guessed.
                    text = "פרטי ההתחברות נשמרים במכשיר בלבד · גרסה ${BuildConfig.VERSION_NAME}",
                    fontSize = tzSp(17),
                    color = Ink.Faint,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // The empty space that lets the last field reach the top of the
            // screen when the keyboard is up. Below the fold, so it is never seen.
            Spacer(Modifier.height(tz(760)))
        }
    }
}

/** The two fields nobody has to fill in, kept together and out of the way. */
@Composable
private fun Optionals(
    name: String,
    onName: (String) -> Unit,
    epgUrl: String,
    onEpgUrl: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(tz(12))) {
        LabeledField(
            label = "שם לרשימה (רשות)",
            value = name,
            onValueChange = onName,
            modifier = Modifier.weight(1f),
        )
        LabeledField(
            label = "מדריך שידורים (רשות)",
            value = epgUrl,
            onValueChange = onEpgUrl,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Which kind of source this is. Filled when chosen, the way Tizen fills it. */
@Composable
private fun SourceTab(label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(tz(14))
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (active) Ink.Accent else Ink.Surface)
            .border(1.dp, if (active) Ink.Accent else Ink.Line, shape)
            .focusHighlight(shape, border = false)
            .clickable(onClick = onClick)
            .padding(tz(12)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = tzSp(22),
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) Ink.OnAccent else Ink.Bright,
            maxLines = 1,
        )
    }
}

@Composable
private fun ServerSuggestion(address: String, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(tz(12))
    Box(
        modifier = modifier
            .clip(shape)
            .background(Ink.SurfaceLow)
            .border(1.dp, Ink.Line, shape)
            .focusHighlight(shape, border = false)
            .clickable(onClick = onClick)
            .padding(tz(9)),
        contentAlignment = Alignment.Center,
    ) {
        Text(address, fontSize = tzSp(18), color = Ink.Bright, maxLines = 1)
    }
}

/** A checkbox that reads as one from across a room. */
@Composable
private fun Tick(checked: Boolean) {
    val shape = RoundedCornerShape(tz(7))
    Box(
        modifier = Modifier
            .size(tz(26))
            .clip(shape)
            .background(if (checked) Ink.Accent else Color.Transparent)
            .border(1.dp, if (checked) Ink.Accent else Ink.Line, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Text("✓", fontSize = tzSp(18), fontWeight = FontWeight.ExtraBold, color = Ink.OnAccent)
        }
    }
}

@Composable
private fun BigButton(
    label: String,
    enabled: Boolean,
    busy: Boolean,
    primary: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(tz(14))
    val fill = when {
        !enabled -> Ink.Surface
        primary -> Ink.Accent
        else -> Ink.Surface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(fill)
            .border(1.dp, if (enabled && primary) Ink.Accent else Ink.Line, shape)
            .focusHighlight(shape, border = false)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(tz(14)),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(
                strokeWidth = tz(4),
                color = Ink.OnAccent,
                modifier = Modifier.size(tz(34)).padding(end = tz(8)),
            )
        }
        Text(
            text = label,
            fontSize = tzSp(23),
            fontWeight = FontWeight.Bold,
            color = when {
                !enabled -> Ink.Faint
                primary -> Ink.OnAccent
                else -> Ink.Bright
            },
            maxLines = 1,
        )
    }
}

/** The lists already added, and the way back into one of them. */
@Composable
private fun SavedPlaylists(state: UiState, viewModel: AppViewModel) {
    Text("הרשימות שלי", fontSize = tzSp(18), color = Ink.Dim)
    state.playlists.forEach { playlist ->
        val shape = RoundedCornerShape(tz(12))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Ink.Surface)
                .border(1.dp, Ink.Line, shape)
                .focusHighlight(shape, border = false)
                .clickable {
                    viewModel.selectPlaylist(playlist.id)
                    viewModel.setScreen(Screen.CHOOSE)
                }
                .padding(horizontal = tz(16), vertical = tz(10)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.label,
                    fontSize = tzSp(21),
                    color = Ink.Bright,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Faint(
                    text = describe(playlist) +
                        if (playlist.id == state.activeId) " · פעילה" else "",
                    size = 17,
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(tz(10)))
                    .focusHighlight(RoundedCornerShape(tz(10)), border = false)
                    .clickable { viewModel.removePlaylist(playlist.id) }
                    .padding(horizontal = tz(12), vertical = tz(6)),
            ) {
                Text("מחיקה", fontSize = tzSp(18), color = Ink.Dim)
            }
        }
    }
}

private fun describe(playlist: Playlist): String = when (val source = playlist.source) {
    is PlaylistSource.Url -> source.url
    is PlaylistSource.Xtream -> "Xtream · ${source.server}"
    is PlaylistSource.Text -> "קובץ מקומי"
}
