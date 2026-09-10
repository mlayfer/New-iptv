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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlayfer.iptv.data.ChannelKind

/**
 * The door. Television and a library of films are two different things to be in
 * the mood for, and asking once — before either — is what keeps each of them
 * clean. The Tizen build opens on the same choice.
 */
/** How much of each world there is, said on its own door. */
private data class WorldCounts(val live: Int, val movies: Int, val series: Int)

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
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "טלוהים",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { viewModel.setScreen(Screen.SOURCES) },
                modifier = Modifier.focusHighlight(),
            ) { Text("מקורות") }
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

        Text(
            text = "במה נתחיל?",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )

        // Written once and placed twice, so the two layouts cannot drift apart.
        val television: @Composable (Modifier) -> Unit = { place ->
            WorldCard(
                title = "טלוויזיה בלייב",
                subtitle = if (counts.live > 0) "${counts.live} ערוצים" else "",
                blurb = "כל הערוצים, מדריך שידורים, וזפזופ עם החצים",
                tint = Color(0xFF1D4ED8),
                onClick = { viewModel.enterWorld(Catalog.LIVE) },
                modifier = place,
            )
        }
        val library: @Composable (Modifier) -> Unit = { place ->
            WorldCard(
                title = "סרטים וסדרות",
                subtitle = "${counts.movies} סרטים · ${counts.series} סדרות",
                blurb = "המשך לצפות, המועדפים שלך, וכל הקטלוג",
                tint = Color(0xFF7C3AED),
                onClick = { viewModel.enterWorld(Catalog.MOVIES) },
                modifier = place,
            )
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
        ) {
            // Two doors side by side where there is room, stacked where there
            // is not — a phone held upright has no room for two.
            if (maxWidth > 560.dp) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    television(Modifier.weight(1f).fillMaxHeight())
                    library(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    television(Modifier.fillMaxWidth().height(180.dp))
                    library(Modifier.fillMaxWidth().height(180.dp))
                }
            }
        }
    }
}

@Composable
private fun WorldCard(
    title: String,
    subtitle: String,
    blurb: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(tint.copy(alpha = 0.85f), tint.copy(alpha = 0.22f)),
                ),
            )
            .border(1.dp, Color.White.copy(alpha = 0.14f), shape)
            .focusHighlight(shape = shape)
            .clickable(onClick = onClick)
            .padding(24.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        Column {
            Text(
                text = title,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.86f),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = blurb,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.72f),
            )
        }
    }
}
