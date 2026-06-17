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
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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

    val scrollTarget by queueModel.scrollTo.flow.collectAsState()

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Drag state — tracks which item is being dragged and its visual offset.
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var draggingOffset by remember { mutableFloatStateOf(0f) }
    var autoScrollJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(scrollTarget) {
        val target = scrollTarget ?: return@LaunchedEffect
        queueModel.scrollTo.consume()
        if (target in queue.indices) {
            val firstVisible = listState.firstVisibleItemIndex
            val lastVisible = firstVisible + listState.layoutInfo.visibleItemsInfo.size
            if (target < firstVisible || target > lastVisible) {
                listState.scrollToItem(target)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar
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
            Box(
                modifier =
                    Modifier.clip(RoundedCornerShape(percent = 50))
                        .background(FluxioTheme.colors.element)
                        .clickable { /* TODO: clear queue */ }
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

        // Queue list
        LazyColumn(
            modifier =
                Modifier.fillMaxSize().pointerInput(queue) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            // Find which item the long-press hit.
                            val layoutInfo = listState.layoutInfo
                            val hit =
                                layoutInfo.visibleItemsInfo.firstOrNull { itemInfo ->
                                    offset.y >= itemInfo.offset &&
                                        offset.y <= itemInfo.offset + itemInfo.size
                                }
                            draggingIndex = hit?.index ?: -1
                            draggingOffset = 0f
                        },
                        onDragEnd = {
                            draggingIndex = -1
                            draggingOffset = 0f
                            autoScrollJob?.cancel()
                            autoScrollJob = null
                        },
                        onDragCancel = {
                            draggingIndex = -1
                            draggingOffset = 0f
                            autoScrollJob?.cancel()
                            autoScrollJob = null
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            if (draggingIndex < 0) return@detectDragGesturesAfterLongPress
                            draggingOffset += dragAmount.y

                            // Auto-scroll at edges
                            val viewportH = listState.layoutInfo.viewportSize.height
                            val edgeZone = 80f
                            when {
                                change.position.y < edgeZone -> {
                                    if (autoScrollJob?.isActive != true) {
                                        autoScrollJob =
                                            coroutineScope.launch {
                                                while (true) {
                                                    listState.scrollBy(-8f)
                                                    kotlinx.coroutines.delay(16)
                                                }
                                            }
                                    }
                                }
                                change.position.y > viewportH - edgeZone -> {
                                    if (autoScrollJob?.isActive != true) {
                                        autoScrollJob =
                                            coroutineScope.launch {
                                                while (true) {
                                                    listState.scrollBy(8f)
                                                    kotlinx.coroutines.delay(16)
                                                }
                                            }
                                    }
                                }
                                else -> {
                                    autoScrollJob?.cancel()
                                    autoScrollJob = null
                                }
                            }

                            // Compute target index from drag position
                            val layoutInfo = listState.layoutInfo
                            val draggingItem =
                                layoutInfo.visibleItemsInfo.firstOrNull {
                                    it.index == draggingIndex
                                } ?: return@detectDragGesturesAfterLongPress

                            val draggingCenter =
                                draggingItem.offset + draggingItem.size / 2 + draggingOffset.toInt()
                            val targetItem =
                                layoutInfo.visibleItemsInfo.minByOrNull {
                                    kotlin.math.abs(it.offset + it.size / 2 - draggingCenter)
                                } ?: return@detectDragGesturesAfterLongPress

                            if (targetItem.index != draggingIndex) {
                                queueModel.moveQueueDataItems(draggingIndex, targetItem.index)
                                draggingOffset +=
                                    (draggingIndex - targetItem.index) * draggingItem.size.toFloat()
                                draggingIndex = targetItem.index
                            }
                        },
                    )
                },
            state = listState,
            contentPadding = PaddingValues(bottom = 80.dp),
        ) {
            itemsIndexed(items = queue, key = { _, song -> song.uid.toString() }) { index, song ->
                val isCurrent = index == currentIndex
                val isPast = index < currentIndex
                val isDragging = index == draggingIndex

                QueueItem(
                    song = song,
                    isCurrent = isCurrent,
                    isPlaying = isPlaying,
                    isPast = isPast,
                    isDragging = isDragging,
                    dragOffsetY = if (isDragging) draggingOffset else 0f,
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
    isDragging: Boolean = false,
    dragOffsetY: Float = 0f,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val backgroundColor =
        if (isCurrent) FluxioTheme.colors.text1.copy(alpha = 0.08f) else FluxioTheme.colors.bg

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .height(72.dp)
                .graphicsLayer {
                    translationY = dragOffsetY
                    shadowElevation = if (isDragging) 8f else 0f
                    scaleX = if (isDragging) 1.02f else 1f
                    scaleY = if (isDragging) 1.02f else 1f
                }
                .clickable(onClick = onClick)
                .background(if (isDragging) FluxioTheme.colors.element else backgroundColor)
                .padding(horizontal = 16.dp)
                .alpha(if (isPast && !isDragging) 0.5f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val baseArtworkModifier = Modifier.size(48.dp)
        val artworkModifier =
            if (isCurrent) {
                baseArtworkModifier.border(2.dp, FluxioTheme.colors.text1, RoundedCornerShape(6.dp))
            } else {
                baseArtworkModifier
            }

        Box(modifier = artworkModifier.clip(RoundedCornerShape(6.dp))) {
            AsyncImage(
                model =
                    ImageRequest.Builder(context)
                        .data(song.cover ?: R.drawable.ic_album_24)
                        .build(),
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

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.name.resolve(context),
                style = FluxioTheme.typography.titleMedium,
                color = FluxioTheme.colors.text1,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = song.artists.resolveNames(context),
                style = FluxioTheme.typography.bodyMedium,
                color = FluxioTheme.colors.text2,
                maxLines = 1,
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Drag handle — visual hint; drag is activated by long-press anywhere on the item
        Icon(
            painter = painterResource(id = R.drawable.ic_handle_24),
            contentDescription = null,
            tint = FluxioTheme.colors.text3,
        )
    }
}
