/*
 * Copyright (c) 2026 Fluxio Project
 * QueueTab.kt is part of Fluxio.
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
package org.oxycblt.auxio.playback.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.flow.collectLatest
import org.oxycblt.auxio.R
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.ui.components.EqualizerIndicator
import org.oxycblt.auxio.ui.theme.FluxioTheme
import org.oxycblt.musikr.Song

@Composable
fun QueueTab(queueModel: QueueViewModel, playbackModel: PlaybackViewModel) {
    val queue by queueModel.queue.collectAsState()
    val currentIndex by queueModel.index.collectAsState()
    val isPlaying by playbackModel.isPlaying.collectAsState()

    val listState = rememberLazyListState()

    LaunchedEffect(queueModel.scrollTo) {
        queueModel.scrollTo.flow.collectLatest { targetIndex ->
            if (targetIndex != null) {
                queueModel.scrollTo.consume()
                if (targetIndex in queue.indices) {
                    // Determine if we need to scroll upwards or downwards for better UX
                    val firstVisible = listState.firstVisibleItemIndex
                    if (targetIndex < firstVisible || targetIndex > firstVisible + 10) {
                        listState.scrollToItem(targetIndex)
                    }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top Bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Siguiente",
                style = FluxioTheme.typography.labelMedium,
                color = FluxioTheme.colors.text2,
            )

            // "Borrar" button
            Box(
                modifier =
                    Modifier.clip(RoundedCornerShape(percent = 50))
                        .background(FluxioTheme.colors.element)
                        .clickable {
                            // TODO: Implement clear queue if possible, or clear after current
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Borrar",
                    style = FluxioTheme.typography.labelSmall,
                    color = FluxioTheme.colors.text2,
                )
            }
        }

        // List
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(bottom = 80.dp), // extra padding for scrolling
        ) {
            itemsIndexed(queue, key = { index, song -> "${song.uid}_$index" }) { index, song ->
                val isCurrent = index == currentIndex
                val isPast = index < currentIndex

                QueueItem(
                    song = song,
                    isCurrent = isCurrent,
                    isPlaying = isPlaying,
                    isPast = isPast,
                    onClick = { queueModel.goto(index) },
                )
            }
        }
    }
}

@Composable
fun QueueItem(
    song: Song,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isPast: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    // Active: bg-tinte-8%
    val backgroundColor =
        if (isCurrent) FluxioTheme.colors.text1.copy(alpha = 0.08f) else FluxioTheme.colors.bg

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .height(72.dp)
                .clickable(onClick = onClick)
                .background(backgroundColor)
                .padding(horizontal = 16.dp)
                .alpha(if (isPast) 0.5f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Artwork 48dp, r:6dp. Active: borde-artwork-puro
        val artworkModifier = Modifier.size(48.dp)
        val finalArtworkModifier =
            if (isCurrent) {
                artworkModifier.border(2.dp, FluxioTheme.colors.text1, RoundedCornerShape(6.dp))
            } else {
                artworkModifier
            }

        Box(modifier = finalArtworkModifier.clip(RoundedCornerShape(6.dp))) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(song.cover).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                error = painterResource(id = R.drawable.ic_album_24),
            )

            if (isCurrent) {
                Box(
                    modifier =
                        Modifier.fillMaxSize().background(FluxioTheme.colors.bg.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center,
                ) {
                    EqualizerIndicator(isPlaying = isPlaying)
                }
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Text
        Column(modifier = Modifier.weight(1f)) {
            // Título: 17sp (titleMedium)
            Text(
                text = song.name.resolve(context),
                style = FluxioTheme.typography.titleMedium,
                color = FluxioTheme.colors.text1,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(4.dp))
            // Artista: 15sp text2 (bodyMedium)
            Text(
                text = song.artists.resolveNames(context),
                style = FluxioTheme.typography.bodyMedium,
                color = FluxioTheme.colors.text2,
                maxLines = 1,
            )
        }

        // Drag handle (drag-text3-right)
        Icon(
            painter = painterResource(id = R.drawable.ic_handle_24),
            contentDescription = "Reordenar",
            tint = FluxioTheme.colors.text3,
        )
    }
}
