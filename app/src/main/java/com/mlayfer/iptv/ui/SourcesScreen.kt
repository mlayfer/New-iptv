package com.mlayfer.iptv.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.mlayfer.iptv.data.Playlist
import com.mlayfer.iptv.data.PlaylistSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

private enum class SourceKind { URL, FILE, XTREAM }

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Keeps the form clear of the status bar, the navigation bar and
                // the keyboard — this screen is nothing but text fields.
                .windowInsetsPadding(WindowInsets.systemBars)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.playlists.isNotEmpty()) {
                    IconButton(onClick = { viewModel.setScreen(Screen.CHANNELS) }) {
                        Icon(Icons.Default.ArrowForward, contentDescription = "חזרה")
                    }
                }
                Text("מקורות תוכן", style = MaterialTheme.typography.titleLarge)
            }

            Text(
                text = "האפליקציה היא נגן בלבד — התוכן מגיע מהרשימה או מהמנוי שלך.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.playlists.isNotEmpty()) {
                Text("הרשימות שלי", style = MaterialTheme.typography.titleSmall)
                state.playlists.forEach { playlist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .clickable {
                                viewModel.selectPlaylist(playlist.id)
                                viewModel.setScreen(Screen.CHANNELS)
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

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("שם לרשימה (רשות)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            when (kind) {
                SourceKind.URL -> {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("כתובת M3U") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    EpgField(epgUrl) { epgUrl = it }
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
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        label = { Text("או הדבקה של תוכן ה-M3U") },
                        minLines = 4,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    EpgField(epgUrl) { epgUrl = it }
                    AddButton(
                        enabled = content.isNotBlank() && !state.addBusy,
                        busy = state.addBusy,
                        label = "הוספה וטעינה",
                    ) {
                        submit(PlaylistSource.Text(content))
                    }
                }

                SourceKind.XTREAM -> {
                    OutlinedTextField(
                        value = server,
                        onValueChange = { server = it },
                        label = { Text("כתובת השרת") },
                        placeholder = { Text("http://portal.example.com:8080") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("שם משתמש") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("סיסמה") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("לכלול גם ספריית סרטים")
                            Text(
                                text = "טעינה איטית יותר במנויים גדולים",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = includeVod, onCheckedChange = { includeVod = it })
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
                        text = "פרטי ההתחברות נשמרים במכשיר בלבד.",
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

@Composable
private fun EpgField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text("כתובת מדריך שידורים XMLTV (רשות)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
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
