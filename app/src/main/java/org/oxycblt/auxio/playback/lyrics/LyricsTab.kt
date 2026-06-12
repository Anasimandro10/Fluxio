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

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.lyrics.LrcLine
import org.oxycblt.auxio.lyrics.LyricsViewModel
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.ui.theme.FluxioTheme

@Composable
fun LyricsTab(lyricsModel: LyricsViewModel, playbackModel: PlaybackViewModel) {
    val context = LocalContext.current
    val song by playbackModel.song.collectAsState()
    val lines by lyricsModel.lines.collectAsState()
    val isSynced by lyricsModel.isSynced.collectAsState()
    val currentLineIndex by lyricsModel.currentLineIndex.collectAsState()
    val activeWordIndex by lyricsModel.activeWordIndex.collectAsState()

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Smooth scroll to active line, centering it
    LaunchedEffect(currentLineIndex) {
        if (currentLineIndex >= 0 && currentLineIndex < lines.size) {
            val layoutInfo = listState.layoutInfo
            val viewportHeight = layoutInfo.viewportSize.height
            val itemHeight = layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 0
            val offset = if (itemHeight > 0) (viewportHeight - itemHeight) / 2 else 0

            coroutineScope.launch {
                listState.animateScrollToItem(index = currentLineIndex, scrollOffset = -offset)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(song?.cover).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
                error = painterResource(id = R.drawable.ic_album_48),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = song?.name?.resolve(context) ?: "",
                    style = FluxioTheme.typography.titleMedium,
                    color = FluxioTheme.colors.text1,
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = song?.artists?.resolveNames(context) ?: "",
                    style = FluxioTheme.typography.labelMedium,
                    color = FluxioTheme.colors.text2,
                    maxLines = 1,
                )
            }
        }

        if (lines.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(id = R.string.lbl_no_lyrics),
                    style = FluxioTheme.typography.titleMedium,
                    color = FluxioTheme.colors.text3,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 32.dp),
            ) {
                itemsIndexed(lines, key = { _, line -> "${line.startMs}_${line.text}" }) {
                    index,
                    line ->
                    val isActive = isSynced && index == currentLineIndex
                    val currentWordIdx = if (isActive) activeWordIndex else -1
                    // TODO: Dynamically pass ambient color when DynamicColorManager is implemented
                    val ambientColor = Color.White

                    LyricLineView(
                        line = line,
                        isActiveLine = isActive,
                        isSynced = isSynced,
                        currentWordIdx = currentWordIdx,
                        ambientColor = ambientColor,
                    )
                }
            }
        }
    }
}

@Composable
fun LyricLineView(
    line: LrcLine,
    isActiveLine: Boolean,
    isSynced: Boolean,
    currentWordIdx: Int,
    ambientColor: Color,
) {
    val alpha = if (!isSynced) 1f else if (isActiveLine) 1f else 0.35f
    val fontSize = if (!isSynced) 20.sp else if (isActiveLine) 22.sp else 20.sp

    val shadow =
        if (isSynced && isActiveLine) {
            Shadow(
                color = ambientColor.copy(alpha = 0.3f), // 30% opacity glow
                blurRadius = 8f,
            )
        } else null

    val annotatedText =
        remember(line.text, line.words, currentWordIdx, isSynced, isActiveLine) {
            if (line.words.isEmpty() || currentWordIdx < 0 || !isActiveLine) {
                AnnotatedString(line.text)
            } else {
                buildAnnotatedString {
                    var lastEnd = 0
                    for (i in line.words.indices) {
                        val w = line.words[i]
                        if (
                            w.startChar < 0 ||
                                w.endChar > line.text.length ||
                                w.startChar >= w.endChar
                        )
                            continue

                        if (w.startChar > lastEnd) {
                            append(line.text.substring(lastEnd, w.startChar))
                        }

                        if (i <= currentWordIdx) {
                            // Already sung or current: full white
                            withStyle(SpanStyle(color = Color.White)) {
                                append(line.text.substring(w.startChar, w.endChar))
                            }
                        } else {
                            // Upcoming: 35% white
                            withStyle(SpanStyle(color = Color.White.copy(alpha = 0.35f))) {
                                append(line.text.substring(w.startChar, w.endChar))
                            }
                        }
                        lastEnd = w.endChar
                    }
                    if (lastEnd < line.text.length) {
                        append(line.text.substring(lastEnd))
                    }
                }
            }
        }

    Text(
        text = annotatedText,
        fontSize = fontSize,
        color = Color.White,
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).alpha(alpha),
        style = FluxioTheme.typography.bodyMedium.copy(shadow = shadow),
    )
}
