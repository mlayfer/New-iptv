package com.mlayfer.iptv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mlayfer.iptv.R

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
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = if (isWide) tz(88) else tz(72)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandBlock(title, tagline, counts)
        Spacer(Modifier.width(if (isWide) tz(16) else tz(8)))
        // One row on every screen. The phone used to get a second line of its
        // own because five pills and a name do not fit across it — but a Row
        // that scrolls holds as many as it likes and keeps them all on the far
        // side, which is where they belong whatever the width.
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(tz(12), Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(vertical = tz(8)),
        ) {
            item { Row(horizontalArrangement = Arrangement.spacedBy(tz(12))) { actions() } }
        }
    }
}

/**
 * Who this is: the icon and the name, in that order from the right.
 *
 * Every screen that carries a heading carries this one, so the sign-in screen
 * cannot end up the only page in the app without a logo on it — which is what
 * it was, because it had written its own.
 */
@Composable
fun BrandBlock(title: String, tagline: String, counts: String?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (isWide) tz(16) else tz(10)),
    ) {
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = title,
                fontSize = if (isWide) tzSp(38) else tzSp(26),
                fontWeight = FontWeight.ExtraBold,
                color = Ink.Bright,
                maxLines = 1,
            )
            // A television is looked at from a sofa and has the room for a
            // strapline and a count. A phone has neither: three lines of grey at
            // the top of a small screen is a third of the screen spent saying
            // what the screen is already showing.
            if (isWide) {
                Text(
                    text = tagline,
                    fontSize = tzSp(18),
                    color = Ink.Faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (counts != null) {
                    Text(
                        text = counts,
                        fontSize = tzSp(18),
                        color = Ink.Faint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        BrandMark()
    }
}

/** The launcher icon itself, rather than a drawing of it. */
@Composable
private fun BrandMark() {
    Image(
        painter = painterResource(R.mipmap.ic_launcher),
        contentDescription = null,
        modifier = Modifier
            .size(if (isWide) tz(60) else tz(46))
            .clip(RoundedCornerShape(tz(14))),
    )
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
            // A phone fits four of these and the app's name across it, and the
            // row scrolls rather than shrinks — so the last one was drawn half
            // off the glass. Narrower pills are what makes them all fit; they
            // are pressed with a finger here, not aimed at from a sofa.
            .padding(
                horizontal = if (isWide) tz(24) else tz(13),
                vertical = if (isWide) tz(14) else tz(10),
            ),
    ) {
        Text(
            text = label,
            fontSize = if (isWide) tzSp(22) else tzSp(18),
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
        // The words sit on the right — where a Hebrew page starts, and, on a
        // television, clear of the few per cent of the left edge that the panel
        // crops. They were on the left, which is how a long title ended up
        // half-off a 75-inch screen. The wash darkens that right-hand side: a
        // gradient is drawn in pixels and knows nothing of direction, so its
        // dark end stays where the words actually are. Both washes match the
        // band rather than fill it, so neither has a say in how tall it is.
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
                .align(Alignment.CenterEnd)
                .padding(end = tz(24))
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
                .align(Alignment.CenterStart)
                .fillMaxWidth(0.62f)
                .padding(horizontal = tz(30)),
            horizontalAlignment = Alignment.Start,
        ) {
            if (kicker != null) {
                // Ellipsis, not the default clip: a portal's category names run
                // long, and a line that simply stops mid-word reads as a screen
                // that is cut off rather than a name that is.
                Text(
                    text = kicker,
                    fontSize = tzSp(20),
                    color = Ink.Accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = title,
                fontSize = tzSp(40),
                fontWeight = FontWeight.ExtraBold,
                color = Ink.Bright,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start,
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
    /**
     * Landed on with the remote. The band at the top of the screen says what is
     * under the cursor, and until this existed the only thing that moved the
     * cursor as far as the band was concerned was pressing OK — by which point
     * you had already left the screen.
     */
    onFocus: () -> Unit = {},
) {
    val shape = RoundedCornerShape(tz(14))
    Column(
        modifier = modifier
            .width(width)
            .clip(shape)
            .onFocusChanged { if (it.isFocused) onFocus() }
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
    /** What is on it now. A wall of channel names says what exists; this is
     *  what turns it into a guide you can choose from without opening one. */
    now: String? = null,
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
        if (now != null) {
            Text(
                text = now,
                fontSize = tzSp(18),
                lineHeight = tzSp(22),
                color = Ink.Accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            text = meta,
            fontSize = tzSp(17),
            lineHeight = tzSp(21),
            color = Ink.Faint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * A tick, drawn rather than typed.
 *
 * It was the character "✓" in a Text, and in a box this size, in a
 * right-to-left layout, the system chose a font for it and clipped what came
 * back: the checkbox showed a faint diagonal smudge and nothing that reads as a
 * tick from a sofa. Two strokes on a canvas are the same mark at every size,
 * in every font, in either direction.
 */
@Composable
fun TickMark(modifier: Modifier = Modifier, color: Color = Ink.OnAccent) {
    Canvas(modifier = modifier) {
        val wide = size.width
        val tall = size.height
        val stroke = (minOf(wide, tall) * 0.18f).coerceAtLeast(2f)
        val path = Path().apply {
            moveTo(wide * 0.20f, tall * 0.52f)
            lineTo(wide * 0.42f, tall * 0.74f)
            lineTo(wide * 0.80f, tall * 0.26f)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
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
        TickMark(Modifier.size(tz(16)))
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
    ) {
        // What is on now reads first — which in Hebrew means the right-hand
        // side. It was drawn on the left, behind what comes after it.
        Column(modifier = Modifier.weight(0.55f)) {
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
        Spacer(Modifier.width(tz(20)))
        Text(
            text = next ?: "",
            fontSize = tzSp(19),
            color = Ink.Faint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.45f),
        )
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

/**
 * One channel on a line, with its schedule beside it.
 *
 * A wall of logos is the fastest way to find a channel you already know, and
 * useless for the other question — what is actually on. A guide answers that by
 * giving each channel a line long enough to carry it: what is playing, how far
 * in, and the next couple of programmes after it.
 */
@Composable
fun ChannelLine(
    name: String,
    meta: String,
    logo: String?,
    playing: Boolean,
    now: String?,
    nowRange: String?,
    progress: Float,
    upcoming: List<String>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(tz(14))
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Ink.Surface)
            .border(1.dp, if (playing) Ink.Accent else Ink.LineSoft, shape)
            .focusHighlight(shape, border = false)
            .clickable(onClick = onClick)
            .padding(horizontal = tz(18), vertical = tz(14)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.width(tz(120)).height(tz(68)),
            contentAlignment = Alignment.Center,
        ) {
            if (logo != null) {
                AsyncImage(
                    model = logo,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = name.take(2),
                    fontSize = tzSp(26),
                    fontWeight = FontWeight.ExtraBold,
                    color = Ink.Accent,
                )
            }
        }
        Spacer(Modifier.width(tz(18)))

        // On a television the name and the schedule stand in two columns, so the
        // eye runs down one of them. A phone has no room for two, so the same
        // pieces stack instead of being squeezed until neither can be read.
        val identity: @Composable () -> Unit = {
            Text(
                text = name,
                fontSize = tzSp(23),
                fontWeight = FontWeight.Bold,
                color = Ink.Bright,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = meta,
                fontSize = tzSp(17),
                color = Ink.Faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // What is on, and how far in.
        val onAir: @Composable () -> Unit = {
            if (now == null) {
                Faint("אין לוח שידורים לערוץ הזה", size = 18)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = now,
                        fontSize = tzSp(20),
                        fontWeight = FontWeight.Bold,
                        color = Ink.Bright,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (nowRange != null) {
                        Spacer(Modifier.width(tz(12)))
                        Faint(nowRange, size = 17)
                    }
                }
                Box(
                    modifier = Modifier
                        .padding(top = tz(8))
                        .fillMaxWidth()
                        .height(tz(5))
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

        /** And what follows it. */
        val ahead: @Composable () -> Unit = {
            for (line in upcoming) {
                Text(
                    text = line,
                    fontSize = tzSp(17),
                    color = Ink.Dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = tz(6)),
                )
            }
        }

        // Across a television the line reads as three columns — who, what is on,
        // what is next — so the eye can run down any one of them. A phone has
        // room for one, so the same pieces stack rather than being squeezed
        // until none of them can be read.
        if (isWide) {
            Column(modifier = Modifier.width(tz(300))) { identity() }
            Spacer(Modifier.width(tz(28)))
            Column(modifier = Modifier.weight(1f)) { onAir() }
            Spacer(Modifier.width(tz(28)))
            Column(modifier = Modifier.width(tz(420))) { ahead() }
        } else {
            Column(modifier = Modifier.weight(1f)) {
                identity()
                Spacer(Modifier.height(tz(8)))
                onAir()
                ahead()
            }
        }
    }
}
