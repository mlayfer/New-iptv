package com.mlayfer.iptv.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
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

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
      BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val wide = maxWidth >= 600.dp
        val card = RoundedCornerShape(24.dp)
        Column(
            modifier = Modifier
                // A sign-in form is one object, not a row of fields flung across
                // a television. The Tizen build puts it on a card of its own and
                // sets it in the middle; this is the same card.
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                // Keeps the form clear of the status bar, the navigation bar and
                // the keyboard — this screen is nothing but text fields.
                .windowInsetsPadding(WindowInsets.systemBars)
                .imePadding()
                .tvSafeArea()
                .clip(card)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, card)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.playlists.isNotEmpty()) {
                    IconButton(onClick = { viewModel.setScreen(Screen.CHOOSE) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "חזרה")
                    }
                }
                Text(
                    text = "ברוכים הבאים לטלוהים",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                // Balances the back arrow so the title sits in the middle.
                if (state.playlists.isNotEmpty()) Spacer(Modifier.width(48.dp))
            }

            Text(
                text = if (LocalIsTv.current) {
                    "הזן מקור IPTV, ואחר כך תוכל לבחור בין טלוויזיה בלייב לבין סרטים וסדרות · אישור פותח את המקלדת"
                } else {
                    "האפליקציה היא נגן בלבד — התוכן מגיע מהרשימה או מהמנוי שלך."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.playlists.isNotEmpty()) {
                Text("הרשימות שלי", style = MaterialTheme.typography.titleSmall)
                state.playlists.forEach { playlist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .focusHighlight()
                            .clickable {
                                viewModel.selectPlaylist(playlist.id)
                                viewModel.setScreen(Screen.CHOOSE)
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = playlist.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = describe(playlist) +
                                    if (playlist.id == state.activeId) " · פעילה" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { viewModel.removePlaylist(playlist.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "מחיקה")
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = kind == SourceKind.URL,
                    onClick = { kind = SourceKind.URL },
                    label = { Text("קישור") },
                )
                FilterChip(
                    selected = kind == SourceKind.FILE,
                    onClick = { kind = SourceKind.FILE },
                    label = { Text("קובץ") },
                )
                FilterChip(
                    selected = kind == SourceKind.XTREAM,
                    onClick = { kind = SourceKind.XTREAM },
                    label = { Text("Xtream") },
                )
            }

            when (kind) {
                SourceKind.URL -> {
                    FormTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = "כתובת M3U",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FieldPair(
                        wide = wide,
                        first = {
                            FormTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = "שם לרשימה (רשות)",
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                        second = {
                            FormTextField(
                                value = epgUrl,
                                onValueChange = { epgUrl = it },
                                label = "מדריך שידורים (רשות)",
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                    )
                    AddButton(
                        enabled = url.isNotBlank() && !state.addBusy,
                        busy = state.addBusy,
                        label = "הוספה וטעינה",
                    ) {
                        submit(PlaylistSource.Url(url.trim()))
                    }
                }

                SourceKind.FILE -> {
                    OutlinedButton(
                        onClick = { picker.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(fileName.ifBlank { "בחירת קובץ M3U מהמכשיר" })
                    }
                    FormTextField(
                        value = content,
                        onValueChange = { content = it },
                        label = "או הדבקה של תוכן ה-M3U",
                        singleLine = false,
                        minLines = 4,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FieldPair(
                        wide = wide,
                        first = {
                            FormTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = "שם לרשימה (רשות)",
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                        second = {
                            FormTextField(
                                value = epgUrl,
                                onValueChange = { epgUrl = it },
                                label = "מדריך שידורים (רשות)",
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                    )
                    AddButton(
                        enabled = content.isNotBlank() && !state.addBusy,
                        busy = state.addBusy,
                        label = "הוספה וטעינה",
                    ) {
                        submit(PlaylistSource.Text(content))
                    }
                }

                SourceKind.XTREAM -> {
                    FormTextField(
                        value = server,
                        onValueChange = { server = it },
                        label = "כתובת השרת",
                        placeholder = "http://portal.example.com:8080",
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // Typing a portal address on a remote is miserable; one press
                    // beats forty. As chips they cost one line instead of three.
                    val suggestions = KNOWN_SERVERS.filter { option ->
                        server.isBlank() || option.contains(server.trim(), ignoreCase = true)
                    }
                    if (suggestions.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            suggestions.forEach { option ->
                                SuggestionChip(
                                    onClick = { server = option },
                                    label = { Text(option) },
                                    modifier = Modifier.focusHighlight(border = false),
                                )
                            }
                        }
                    }

                    FieldPair(
                        wide = wide,
                        first = {
                            FormTextField(
                                value = username,
                                onValueChange = { username = it },
                                label = "שם משתמש",
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                        second = {
                            FormTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = "סיסמה",
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("לכלול סרטים וסדרות", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "טעינה איטית יותר במנויים גדולים",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = includeVod,
                            onCheckedChange = { includeVod = it },
                            modifier = Modifier.focusHighlight(border = false),
                        )
                    }

                    AddButton(
                        enabled = server.isNotBlank() && username.isNotBlank() &&
                            password.isNotBlank() && !state.addBusy,
                        busy = state.addBusy,
                        label = "התחברות לפורטל",
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
                    Text(
                        // Which build this is, said out loud. "Is this the new
                        // one?" should be readable off the screen, not guessed.
                        text = "פרטי ההתחברות נשמרים במכשיר בלבד · גרסה ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val addError = state.addError
            if (addError != null) {
                Text(text = addError, color = MaterialTheme.colorScheme.error)
            }
        }
      }
    }
}

/**
 * Two fields that sit side by side where there is room for two, and one under
 * the other where there is not. A phone is not a narrow television.
 */
@Composable
private fun FieldPair(
    wide: Boolean,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    if (wide) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(modifier = Modifier.weight(1f)) { first() }
            Box(modifier = Modifier.weight(1f)) { second() }
        }
    } else {
        first()
        second()
    }
}

@Composable
private fun AddButton(enabled: Boolean, busy: Boolean, label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (busy) {
            Box(modifier = Modifier.padding(end = 8.dp)) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.padding(2.dp),
                )
            }
        }
        Text(label)
    }
}

private fun describe(playlist: Playlist): String = when (val source = playlist.source) {
    is PlaylistSource.Url -> source.url
    is PlaylistSource.Xtream -> "Xtream · ${source.server}"
    is PlaylistSource.Text -> "קובץ מקומי"
}
