/*
 * Copyright (c) 2026 Fluxio Project
 * LyricsTab.kt is part of Fluxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.oxycblt.auxio.playback.lyrics

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlin.math.abs
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.lyrics.LrcLine
import org.oxycblt.auxio.lyrics.LyricsViewModel
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.ui.theme.FluxioTheme

// ─────────────────────────────────────────────────────────────────────────────
// Data model: lyrics lines and instrumental gap markers, ported from Rhythm
// ─────────────────────────────────────────────────────────────────────────────

/** Represents either a real lyric line or an instrumental silence section. */
private sealed class LyricsItem {
    data class Line(val line: LrcLine, val origIndex: Int) : LyricsItem()

    data class Gap(val startMs: Long, val durationMs: Long) : LyricsItem()
}

/** Minimum gap in ms required to insert an instrumental indicator between two vocal lines. */
private const val MIN_VOCAL_GAP_MS = 5_000L

/**
 * How many list positions apart target and current can be before we pre-jump close first. Avoids an
 * uncomfortably long kinetic scroll after a seek across the whole song.
 */
private const val LARGE_SCROLL_CATCH_UP_DELTA = 8

/**
 * Builds the mixed [LyricsItem] list from the raw [LrcLine] list:
 * - Filters silence markers (they control the highlight via isSilence, not via a Gap item)
 * - Detects long instrumental gaps between vocal lines and inserts [LyricsItem.Gap] entries
 */
private fun buildLyricsItems(lines: List<LrcLine>): List<LyricsItem> {
    // Only real vocal lines (non-silence) participate in gap detection
    val vocal = lines.mapIndexedNotNull { i, line -> if (!line.isSilence) i to line else null }
    if (vocal.isEmpty()) return emptyList()

    // Estimate median interval to set an adaptive gap threshold
    val intervals =
        vocal
            .zipWithNext { (_, a), (_, b) -> (b.startMs - a.startMs).coerceAtLeast(0L) }
            .filter { it > 0L }

    val medianInterval =
        if (intervals.isNotEmpty()) {
            val sorted = intervals.sorted()
            sorted[sorted.size / 2]
        } else 2_000L

    val longGapThreshold = maxOf(MIN_VOCAL_GAP_MS, (medianInterval * 2.4f).toLong())
    // Estimate how long the last word takes before silence begins
    val vocalEstimate = (medianInterval * 0.9f).toLong().coerceIn(800L, 2_600L)

    return buildList {
        vocal.forEachIndexed { idx, (origIdx, line) ->
            add(LyricsItem.Line(line, origIdx))
            if (idx < vocal.lastIndex) {
                val nextLine = vocal[idx + 1].second
                val intervalToNext = nextLine.startMs - line.startMs
                if (intervalToNext >= longGapThreshold) {
                    val gapStart = line.startMs + vocalEstimate
                    val gapDuration = (nextLine.startMs - gapStart).coerceAtLeast(0L)
                    if (gapDuration >= 1_800L) {
                        add(LyricsItem.Gap(startMs = gapStart, durationMs = gapDuration))
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Intelligent scroll helper (ported from Rhythm)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Scrolls to [targetIndex] smoothly. If the jump is very large we first snap close to avoid a
 * multi-second kinetic scroll, then finish with a smooth animated scroll.
 */
private suspend fun LazyListState.animateToItemWithCatchUp(
    targetIndex: Int,
    scrollOffset: Int,
    noAnimation: Boolean = false,
) {
    if (noAnimation) {
        scrollToItem(targetIndex, scrollOffset)
        return
    }
    val currentIndex = firstVisibleItemIndex
    val delta = abs(currentIndex - targetIndex)
    if (delta >= LARGE_SCROLL_CATCH_UP_DELTA) {
        val preIndex =
            if (targetIndex > currentIndex) {
                (targetIndex - 1).coerceAtLeast(0)
            } else {
                (targetIndex + 1).coerceAtMost(layoutInfo.totalItemsCount - 1)
            }
        if (preIndex != targetIndex) scrollToItem(preIndex, scrollOffset)
    }
    animateScrollToItem(targetIndex, scrollOffset)
}

// ─────────────────────────────────────────────────────────────────────────────
// Root composable
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun LyricsTab(lyricsModel: LyricsViewModel, playbackModel: PlaybackViewModel) {
    val context = LocalContext.current
    val songState by playbackModel.song.collectAsState()
    val lines by lyricsModel.lines.collectAsState()
    val isSynced by lyricsModel.isSynced.collectAsState()

    // OPTIMISATION: We collect these as State but DO NOT use 'by'.
    // Reading their .value directly in this scope would cause the entire
    // LazyColumn to recompose multiple times a second for word-by-word sync.
    // Instead, we pass the State down to individual lines.
    val currentLineIndexState = lyricsModel.currentLineIndex.collectAsState()
    val activeWordIndexState = lyricsModel.activeWordIndex.collectAsState()

    // Build the mixed list (lines + gap markers) once whenever lines change
    val lyricsItems = remember(lines) { buildLyricsItems(lines) }

    // Map from original line index → position inside lyricsItems (for scrolling)
    val lineToItemIndex =
        remember(lyricsItems) {
            buildMap {
                lyricsItems.forEachIndexed { itemIdx, item ->
                    if (item is LyricsItem.Line) put(item.origIndex, itemIdx)
                }
            }
        }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Intelligent scroll: reacts to currentLineIndex without recomposing LyricsTab
    LaunchedEffect(Unit) {
        snapshotFlow { currentLineIndexState.value }.collectLatest { currentLineIndex ->
            if (currentLineIndex >= 0) {
                val targetItemIdx = lineToItemIndex[currentLineIndex] ?: return@collectLatest
                val viewportH = listState.layoutInfo.viewportSize.height
                // Centre the active line vertically
                val offset = -(viewportH / 3)
                scope.launch {
                    try {
                        listState.animateToItemWithCatchUp(targetItemIdx, offset)
                    } catch (e: Exception) {
                        // Ignore scroll cancellations by user
                    }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Header: small artwork + song/artist ──────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model =
                    ImageRequest.Builder(context)
                        .data(songState?.cover ?: R.drawable.ic_album_48)
                        .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
                error = painterResource(R.drawable.ic_album_48),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = songState?.name?.resolve(context) ?: "",
                    style = FluxioTheme.typography.titleMedium,
                    color = FluxioTheme.colors.text1,
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = songState?.artists?.resolveNames(context) ?: "",
                    style = FluxioTheme.typography.labelMedium,
                    color = FluxioTheme.colors.text2,
                    maxLines = 1,
                )
            }
        }

        // ── Body: lyrics or empty state ───────────────────────────────────────
        if (lines.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.lbl_no_lyrics),
                    style = FluxioTheme.typography.titleMedium,
                    color = FluxioTheme.colors.text3,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 40.dp),
            ) {
                itemsIndexed(
                    items = lyricsItems,
                    key = { _, item ->
                        when (item) {
                            is LyricsItem.Line -> "line_${item.origIndex}"
                            is LyricsItem.Gap -> "gap_${item.startMs}"
                        }
                    },
                ) { _, item ->
                    when (item) {
                        is LyricsItem.Line -> {
                            FluxioLyricLine(
                                line = item.line,
                                origIndex = item.origIndex,
                                isSynced = isSynced,
                                currentLineIndexState = currentLineIndexState,
                                activeWordIndexState = activeWordIndexState,
                                onClick = {
                                    if (isSynced && item.line.startMs >= 0) {
                                        playbackModel.seekTo(item.line.startMs / 100L)
                                    }
                                },
                            )
                        }
                        is LyricsItem.Gap -> {
                            FluxioInstrumentalGap(gap = item)
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Individual lyric line — Fluxio aesthetic
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Renders one lyric line following the Fluxio design spec:
 * - Active line: 22sp Medium, full white, shadow-glow 30% blur 8dp
 * - Inactive lines: 20sp Normal, white at 35% — NEVER in artwork colour
 * - Spring physics for scale/alpha so the animation feels physical, not mechanical
 * - Word-by-word highlighting when word timing data is present
 *
 * Performance: Derives its own active state from [currentLineIndexState] and [activeWordIndexState]
 * to prevent the parent list from recomposing constantly.
 */
@Composable
fun FluxioLyricLine(
    line: LrcLine,
    origIndex: Int,
    isSynced: Boolean,
    currentLineIndexState: State<Int>,
    activeWordIndexState: State<Int>,
    onClick: () -> Unit,
) {
    // Determine activity states locally to shield parent from recomposition
    val isActiveLine by
        remember(isSynced, origIndex) {
            derivedStateOf { isSynced && currentLineIndexState.value == origIndex }
        }

    val currentWordIdx by
        remember(isActiveLine) {
            derivedStateOf { if (isActiveLine) activeWordIndexState.value else -1 }
        }

    // ── Animation targets per Fluxio spec ──────────────────────────────────
    val targetAlpha =
        when {
            !isSynced -> 1f
            isActiveLine -> 1f
            else -> 0.35f // Spec: "Resto del texto: #FFFFFF al 35%"
        }
    val targetScale =
        when {
            !isSynced -> 1f
            isActiveLine -> 1.05f // Subtle grow — keeps it below Rhythm's heavier 1.10
            else -> 1f
        }

    val alpha by
        animateFloatAsState(
            targetValue = targetAlpha,
            animationSpec =
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            label = "lyricAlpha",
        )
    val scale by
        animateFloatAsState(
            targetValue = targetScale,
            animationSpec =
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            label = "lyricScale",
        )

    // ── Typography — Fluxio spec ──────────────────────────────────────────
    val fontSize =
        if (!isSynced || isActiveLine) 22.sp else 20.sp // "22sp Medium activa, 20sp demás"
    val fontWeight = if (isSynced && isActiveLine) FontWeight.Medium else FontWeight.Normal

    // Glow shadow on the active line: colour puro de carátula al 30%, blur 8dp
    // We use white here because the ambient colour system lives one layer above (in the player bg).
    val shadow =
        if (isSynced && isActiveLine) {
            Shadow(color = Color.White.copy(alpha = 0.30f), blurRadius = 8f)
        } else null

    // ── Build annotated string for word-by-word sync ──────────────────────
    val annotatedText =
        remember(line.text, line.words, currentWordIdx, isSynced, isActiveLine) {
            if (line.words.isEmpty() || currentWordIdx < 0 || !isActiveLine) {
                AnnotatedString(line.text)
            } else {
                buildAnnotatedString {
                    var lastEnd = 0
                    for (i in line.words.indices) {
                        val w = line.words[i]
                        // Guard against malformed word ranges
                        if (
                            w.startChar < 0 ||
                                w.endChar > line.text.length ||
                                w.startChar >= w.endChar
                        )
                            continue
                        // Text between words (spaces, punctuation)
                        if (w.startChar > lastEnd) append(line.text.substring(lastEnd, w.startChar))

                        if (i <= currentWordIdx) {
                            // Already sung — full white
                            withStyle(SpanStyle(color = Color.White)) {
                                append(line.text.substring(w.startChar, w.endChar))
                            }
                        } else {
                            // Upcoming words — dimmed to 35% like inactive lines
                            withStyle(SpanStyle(color = Color.White.copy(alpha = 0.35f))) {
                                append(line.text.substring(w.startChar, w.endChar))
                            }
                        }
                        lastEnd = w.endChar
                    }
                    if (lastEnd < line.text.length) append(line.text.substring(lastEnd))
                }
            }
        }

    Text(
        text = annotatedText,
        fontSize = fontSize,
        fontWeight = fontWeight,
        color = Color.White,
        textAlign = TextAlign.Center,
        modifier =
            Modifier.fillMaxWidth()
                .padding(vertical = 12.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .alpha(alpha)
                .clickable(enabled = isSynced) { onClick() },
        style = FluxioTheme.typography.bodyMedium.copy(shadow = shadow),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Instrumental gap indicator — Fluxio aesthetic
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Shows a musical note indicator during instrumental sections. Height is proportional to the gap
 * duration so it feels like breathing room. Infinite breathing animation provides life without
 * requiring 60fps time-sync updates.
 */
@Composable
private fun FluxioInstrumentalGap(gap: LyricsItem.Gap) {
    // Height proportional to duration, clamped so it never dominates the screen
    val gapHeightDp = (gap.durationMs / 1_000f).coerceIn(20f, 72f)

    val infiniteTransition = rememberInfiniteTransition(label = "gapPulse")
    val iconAlpha by
        infiniteTransition.animateFloat(
            initialValue = 0.20f,
            targetValue = 0.50f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(2000, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
            label = "gapAlpha",
        )

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = (gapHeightDp / 2).dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "♪",
            fontSize = 24.sp,
            color = Color.White.copy(alpha = iconAlpha),
            // Fluxio text3 (#4A4A4A) would be invisible on dark bg; use white-dimmed instead
        )
    }
}
