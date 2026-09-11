package com.mlayfer.iptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * The look, taken from the Tizen build.
 *
 * The two apps are meant to be one product, and until now they only shared their
 * logic: the Android screens were assembled out of whatever Material offered,
 * and they looked it. This file is the other half — the shapes, sizes and
 * colours the Tizen stylesheet uses, written once so a screen can be built out
 * of them rather than invented again.
 */

/**
 * A Tizen pixel, as an Android size.
 *
 * The Tizen app draws on a canvas fixed at 1920x1080, so one of its pixels is
 * exactly one dp on a 960dp-wide television. Halve a number from style.css and
 * the two apps measure the same thing.
 *
 * A phone is held at arm's length rather than watched from a sofa, so the same
 * numbers are drawn larger there — the proportions survive, the reading distance
 * does not have to.
 */
val LocalTzScale = staticCompositionLocalOf { 0.5f }

@Composable
@ReadOnlyComposable
fun tz(px: Int): Dp = (px * LocalTzScale.current).dp

@Composable
@ReadOnlyComposable
fun tzSp(px: Int): TextUnit = (px * LocalTzScale.current).sp

/**
 * Whether there is room for the layout this app was designed around.
 *
 * Every screen here was drawn for a 960dp television: five channels across, a
 * poster beside its description, a column of seasons next to a grid of episodes.
 * On a phone held in one hand none of that fits, and squeezing it in is what
 * turned the episode cards into seventy-dp stamps with "נ..." written on them.
 */
val isWide: Boolean
    @Composable
    @ReadOnlyComposable
    get() = LocalConfiguration.current.screenWidthDp >= 600

/** The palette, by the names the stylesheet gives them. */
object Ink {
    val Surface = Color(0xFF18181B)
    val SurfaceLow = Color(0xFF111113)
    val Line = Color(0xFF2E2E33)
    val LineSoft = Color(0xFF242428)
    val Bright = Color(0xFFE9EEFA)
    val Dim = Color(0xFF93A3BF)
    val Faint = Color(0xFF65748F)
    val Accent = Color(0xFF4D9BFF)
    val OnAccent = Color(0xFF04142E)
}

/**
 * The bar every browsing screen wears: who this is on the right, where you can
 * go on the left. Same order as the Tizen build, so moving between the two does
 * not mean learning the room again.
 */
@Composable
fun TopChrome(
    title: String,
    tagline: String,
    counts: String?,
    actions: @Composable () -> Unit,
) {
    // The name comes first, so it lands on the right — where a Hebrew page
    // starts — and what you can do lands on the left. It was the other way
    // round, which read as somebody else's app.
    val name: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(tz(16)),
        ) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = title,
                    fontSize = if (isWide) tzSp(38) else tzSp(30),
                    fontWeight = FontWeight.ExtraBold,
                    color = Ink.Bright,
                    maxLines = 1,
                )
                // A television is looked at from a sofa and has the room for a
                // strapline and a count. A phone has neither: three lines of
                // grey at the top of a small screen is a third of the screen
                // spent saying what the screen is already showing.
                if (isWide) {
                    Text(text = tagline, fontSize = tzSp(18), color = Ink.Faint, maxLines = 1)
                    if (counts != null) {
                        Text(text = counts, fontSize = tzSp(18), color = Ink.Faint, maxLines = 1)
                    }
                }
            }
            BrandMark()
        }
    }

    if (isWide) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = tz(88)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            name()
            Row(horizontalArrangement = Arrangement.spacedBy(tz(12))) { actions() }
        }
    } else {
        // Five pills and a name do not fit across a phone, and a Row does not
        // say so — it draws the overflow past the edge of the glass, which is
        // how the name ended up sliced in half. Here they get a line of their
        // own, and one that scrolls.
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
            ) { name() }
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(tz(12)),
                contentPadding = PaddingValues(vertical = tz(16)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                item { Row(horizontalArrangement = Arrangement.spacedBy(tz(12))) { actions() } }
            }
        }
    }
}

/**
 * The app's own mark, drawn rather than loaded: a light screen with a blue halo
 * behind it, which is what the launcher icon is. At this size a drawing beats a
 * bitmap, and it costs no decode.
 */
@Composable
private fun BrandMark() {
    Box(
        modifier = Modifier
            .size(tz(60))
            .clip(RoundedCornerShape(tz(16)))
            .background(Brush.linearGradient(listOf(Color(0xFFF2F4F8), Color(0xFFBFC7D6)))),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.74f)
                .fillMaxHeight(0.50f)
                .clip(RoundedCornerShape(tz(6)))
                .background(Ink.OnAccent),
            contentAlignment = Alignment.Center,
        ) {
            Text("▶", fontSize = tzSp(22), color = Ink.Accent)
        }
    }
}

/** A top-bar button: a bordered pill, the shape the whole app is built from. */
@Composable
fun NavPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val shape = RoundedCornerShape(tz(14))
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (selected) Ink.Accent else Ink.Surface)
            .border(1.dp, if (selected) Ink.Accent else Ink.Line, shape)
            .focusHighlight(shape, border = false)
            .clickable(onClick = onClick)
            .padding(horizontal = tz(24), vertical = tz(14)),
    ) {
        Text(
            text = label,
            fontSize = tzSp(22),
            color = if (selected) Ink.OnAccent else Ink.Bright,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/**
 * A category. Fully filled when it is the one in force, because on a television
 * the difference between "selected" and "not" has to survive a glance from six
 * feet away.
 */
@Composable
fun CategoryChip(label: String, active: Boolean, onClick: () -> Unit) {
    val shape = CircleShape
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (active) Ink.Accent else Ink.Surface)
            .border(1.dp, if (active) Ink.Accent else Ink.Line, shape)
            .focusHighlight(shape, border = false)
            .clickable(onClick = onClick)
            .padding(horizontal = tz(18), vertical = tz(10)),
    ) {
        Text(
            text = label,
            fontSize = tzSp(19),
            maxLines = 1,
            color = if (active) Ink.OnAccent else Ink.Dim,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/** A shelf's name, with the blue dot the Tizen rows carry. */
@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(vertical = tz(10)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tz(10)),
    ) {
        Text(
            text = text,
            fontSize = tzSp(24),
            fontWeight = FontWeight.Bold,
            color = Ink.Bright,
        )
        Box(
            modifier = Modifier
                .size(tz(10))
                .clip(CircleShape)
                .background(Ink.Accent)
        )
    }
}

/**
 * The band across the top of a library screen: what is under the cursor, said
 * large, over a wash of its own artwork. It is the Tizen hero, and it is what
 * makes a wall of posters feel like somewhere rather than a list.
 */
@Composable
fun HeroBand(
    title: String,
    kicker: String?,
    meta: String?,
    art: String?,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(tz(18))
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(tz(196))
            .clip(shape)
            .background(Ink.SurfaceLow)
            .border(1.dp, Ink.LineSoft, shape),
    ) {
        if (art != null) {
            AsyncImage(
                model = art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.35f,
                modifier = Modifier.matchParentSize(),
            )
        }
        // The words sit on the right, so a wash of artwork on the left never
        // gets underneath them. Both washes match the band rather than fill it,
        // so neither has a say in how tall it is.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, Ink.SurfaceLow.copy(alpha = 0.92f))
                    )
                )
        )
        // The thumbnail stands at the far end, away from the words: the Tizen
        // band carries one, and without it the strip reads as an empty box with
        // a title floating in it.
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = tz(24))
                .height(tz(150))
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(tz(10)))
                .background(Brush.linearGradient(listOf(Color(0xFF232327), Color(0xFF111113))))
                .border(1.dp, Ink.LineSoft, RoundedCornerShape(tz(10))),
            contentAlignment = Alignment.Center,
        ) {
            if (art != null) {
                AsyncImage(
                    model = art,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = title.take(2),
                    fontSize = tzSp(26),
                    fontWeight = FontWeight.ExtraBold,
                    color = Ink.Accent,
                )
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxWidth(0.62f)
                .padding(horizontal = tz(30)),
            horizontalAlignment = Alignment.End,
        ) {
            if (kicker != null) {
                Text(text = kicker, fontSize = tzSp(20), color = Ink.Accent, maxLines = 1)
            }
            Text(
                text = title,
                fontSize = tzSp(40),
                fontWeight = FontWeight.ExtraBold,
                color = Ink.Bright,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
            )
            if (meta != null) {
                Text(
                    text = meta,
                    fontSize = tzSp(20),
                    color = Ink.Dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * A poster on a shelf: two by three, the name underneath, and nothing else
 * unless it has been seen — in which case the artwork steps back and takes a
 * tick, so a shelf reads as what is left rather than what exists.
 */
@Composable
fun PosterCard(
    name: String,
    art: String?,
    seen: Boolean,
    onClick: () -> Unit,
    width: Dp,
    modifier: Modifier = Modifier,
    /** How far in, between nothing and all of it. Nothing draws no bar. */
    progress: Float = 0f,
) {
    val shape = RoundedCornerShape(tz(14))
    Column(
        modifier = modifier
            .width(width)
            .clip(shape)
            .focusHighlight(shape)
            .clickable(onClick = onClick)
            .padding(tz(6)),
        horizontalAlignment = Alignment.End,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(tz(10)))
                .background(
                    Brush.linearGradient(listOf(Color(0xFF232327), Color(0xFF111113)))
                )
                .border(1.dp, Ink.LineSoft, RoundedCornerShape(tz(10))),
            contentAlignment = Alignment.Center,
        ) {
            if (art != null) {
                AsyncImage(
                    model = art,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alpha = if (seen) 0.45f else 1f,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = name.take(2),
                    fontSize = tzSp(34),
                    fontWeight = FontWeight.ExtraBold,
                    color = Ink.Accent,
                )
            }
            if (seen) SeenTick(Modifier.align(Alignment.TopStart).padding(tz(6)))
            // How far in, drawn on the artwork itself: a shelf then says what is
            // half-finished without anyone having to open anything.
            if (progress > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(tz(6))
                        .background(Color.Black.copy(alpha = 0.55f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(Ink.Accent)
                    )
                }
            }
        }
        Text(
            text = name,
            fontSize = tzSp(17),
            color = if (seen) Ink.Faint else Ink.Dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = tz(8)).fillMaxWidth(),
            textAlign = TextAlign.End,
        )
    }
}

/**
 * A live channel: the logo it is known by, its name, and where it sits in the
 * guide. Wide rather than tall, because a channel's mark is a wide thing.
 */
@Composable
fun ChannelTile(
    name: String,
    meta: String,
    logo: String?,
    playing: Boolean,
    seen: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(tz(16))
    Column(
        // A minimum rather than a height: the Tizen tile is 172 tall, but a name
        // that wraps or a larger type scale must push the card open rather than
        // have its last line cut off at the edge.
        modifier = modifier
            .heightIn(min = tz(172))
            .clip(shape)
            .background(Ink.Surface)
            .border(1.dp, if (playing) Ink.Accent else Ink.LineSoft, shape)
            .focusHighlight(shape, border = false)
            .clickable(onClick = onClick)
            .padding(tz(14)),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.width(tz(140)).height(tz(64)),
            contentAlignment = Alignment.Center,
        ) {
            if (logo != null) {
                AsyncImage(
                    model = logo,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    alpha = if (seen) 0.45f else 1f,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = name.take(2),
                    fontSize = tzSp(30),
                    fontWeight = FontWeight.ExtraBold,
                    color = Ink.Accent,
                )
            }
        }
        Text(
            text = name,
            fontSize = tzSp(22),
            fontWeight = FontWeight.Bold,
            color = Ink.Bright,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = tz(10)).fillMaxWidth(),
        )
        Text(
            text = meta,
            fontSize = tzSp(17),
            color = Ink.Faint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The mark on something already watched. */
@Composable
fun SeenTick(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(tz(26))
            .clip(CircleShape)
            .background(Ink.Accent),
        contentAlignment = Alignment.Center,
    ) {
        Text("✓", fontSize = tzSp(16), fontWeight = FontWeight.ExtraBold, color = Ink.OnAccent)
    }
}

/** The line under a live channel's name: what is on now, and what follows. */
@Composable
fun NowNextStrip(now: String, next: String?, progress: Float, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(tz(14))
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Ink.SurfaceLow)
            .border(1.dp, Ink.LineSoft, shape)
            .padding(horizontal = tz(20), vertical = tz(14)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = next ?: "",
            fontSize = tzSp(19),
            color = Ink.Faint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth(0.55f)) {
            Text(
                text = now,
                fontSize = tzSp(21),
                fontWeight = FontWeight.Bold,
                color = Ink.Bright,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                modifier = Modifier
                    .padding(top = tz(8))
                    .fillMaxWidth()
                    .height(tz(6))
                    .clip(CircleShape)
                    .background(Ink.Line),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(Ink.Accent)
                )
            }
        }
    }
}

/** Text that is not the point: counts, hints, the bottom of a card. */
@Composable
fun Faint(text: String, modifier: Modifier = Modifier, size: Int = 19) {
    Text(
        text = text,
        fontSize = tzSp(size),
        color = Ink.Faint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
