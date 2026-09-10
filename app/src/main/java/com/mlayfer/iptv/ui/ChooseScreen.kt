package com.mlayfer.iptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mlayfer.iptv.BuildConfig
import com.mlayfer.iptv.data.ChannelKind

/** The Tizen build's card: a near-black plate with one coloured glow in a corner. */
private val CardBase = Color(0xFF141417)
private val CardEdge = Color(0xFF2E2E33)
private val LiveGlow = Color(0xFF4D9BFF)
private val LiveWash = Color(0xFF16243D)
private val VodGlow = Color(0xFFC8322B)
private val VodWash = Color(0xFF2A1420)

/** How much of each world there is, said on its own door. */
private data class WorldCounts(val live: Int, val movies: Int, val series: Int)

private fun Int.grouped(): String = "%,d".format(this)

/**
 * The door. Television and a library of films are two different things to be in
 * the mood for, and asking once — before either — is what keeps each of them
 * clean. Drawn to match the Tizen build, down to the glow in the corner.
 */
@Composable
fun ChooseScreen(state: UiState, viewModel: AppViewModel) {
    val counts = remember(state.channels, state.series) {
        val live = state.channels.count { it.kind == ChannelKind.LIVE }
        WorldCounts(live, state.channels.size - live, state.series.size)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .tvSafeArea(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            TextButton(
                onClick = { viewModel.setScreen(Screen.SOURCES) },
                modifier = Modifier.focusHighlight(),
            ) { Text("החלף מקור") }

            Spacer(Modifier.weight(1f))

            // The brand block sits in the corner, the way it does on the TV.
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "טלוהים",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "טלוויזיה בלייב • סרטים • סדרות",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "גרסה ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.channels.isEmpty() && state.series.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (state.loading) {
                    CircularProgressIndicator()
                } else {
                    Text(
                        text = state.error ?: "אין תוכן להצגה",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@Column
        }

        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            // The cards are a fixed share of the screen rather than all of it —
            // the room around them is what stops the page shouting.
            val sideBySide = maxWidth > 560.dp
            val cardWidth = if (sideBySide) (maxWidth - 20.dp) / 2 * 0.82f else maxWidth
            val cardHeight = if (sideBySide) cardWidth * 0.68f else 150.dp

            val television: @Composable () -> Unit = {
                WorldCard(
                    title = "טלוויזיה בשידור חי",
                    count = if (counts.live > 0) "${counts.live.grouped()} ערוצים" else "",
                    blurb = "ערוצים, ספורט, חדשות וילדים",
                    glow = LiveGlow,
                    wash = LiveWash,
                    glowAtX = 0.78f,
                    onClick = { viewModel.enterWorld(Catalog.LIVE) },
                    modifier = Modifier.width(cardWidth).height(cardHeight),
                )
            }
            val library: @Composable () -> Unit = {
                WorldCard(
                    title = "סרטים וסדרות",
                    count = "${counts.movies.grouped()} סרטים · ${counts.series.grouped()} סדרות",
                    blurb = "הספרייה, ומה שהתחלת לראות",
                    glow = VodGlow,
                    wash = VodWash,
                    glowAtX = 0.22f,
                    onClick = { viewModel.enterWorld(Catalog.MOVIES) },
                    modifier = Modifier.width(cardWidth).height(cardHeight),
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Text(
                    text = "מה בא לך לראות?",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (sideBySide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        television()
                        library()
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        television()
                        library()
                    }
                }
            }
        }
    }
}

@Composable
private fun WorldCard(
    title: String,
    count: String,
    blurb: String,
    glow: Color,
    wash: Color,
    /** Where the glow sits across the card: the two doors mirror each other. */
    glowAtX: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(24.dp)
    var focused by remember { mutableStateOf(false) }
    val edge = if (focused) MaterialTheme.colorScheme.primary else CardEdge

    Box(
        modifier = modifier
            .clip(shape)
            .background(CardBase)
            .drawBehind {
                drawRect(
                    Brush.linearGradient(
                        colors = listOf(wash, Color(0xFF0A0A0B)),
                        start = Offset(size.width, 0f),
                        end = Offset(0f, size.height),
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(glow.copy(alpha = 0.40f), Color.Transparent),
                        center = Offset(size.width * glowAtX, size.height * 0.18f),
                        radius = size.width * 0.75f,
                    )
                )
            }
            .border(if (focused) 2.dp else 1.dp, edge, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE9EEFA),
            )
            if (count.isNotBlank()) {
                Text(
                    text = count,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = blurb,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF93A3BF),
            )
        }
    }
}
