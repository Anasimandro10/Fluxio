package org.oxycblt.auxio.playback.queue

import androidx.compose.foundation.background
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
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.flow.collectLatest
import org.oxycblt.auxio.R
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.ui.components.EqualizerIndicator
import org.oxycblt.auxio.ui.theme.FluxioTheme
import org.oxycblt.musikr.Song

@Composable
fun QueueTab(
    queueModel: QueueViewModel = hiltViewModel(),
    playbackModel: PlaybackViewModel = hiltViewModel()
) {
    val queue by queueModel.queue.collectAsState()
    val currentIndex by queueModel.index.collectAsState()
    val isPlaying by playbackModel.isPlaying.collectAsState()
    
    val listState = rememberLazyListState()
    
    LaunchedEffect(queueModel.scrollTo) {
        queueModel.scrollTo.consumeAsFlow().collectLatest { targetIndex ->
            if (targetIndex in queue.indices) {
                // Determine if we need to scroll upwards or downwards for better UX
                val firstVisible = listState.firstVisibleItemIndex
                if (targetIndex < firstVisible || targetIndex > firstVisible + 10) {
                    listState.scrollToItem(targetIndex)
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Siguiente",
                style = FluxioTheme.typography.labelMedium,
                color = FluxioTheme.colors.text2
            )
            
            // "Borrar" button
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(FluxioTheme.colors.element)
                    .clickable {
                        // TODO: Implement clear queue if possible, or clear after current
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Borrar",
                    style = FluxioTheme.typography.labelSmall,
                    color = FluxioTheme.colors.text2
                )
            }
        }

        // List
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(bottom = 80.dp) // extra padding for scrolling
        ) {
            itemsIndexed(queue, key = { index, song -> "${song.uid}_$index" }) { index, song ->
                val isCurrent = index == currentIndex
                val isPast = index < currentIndex
                
                QueueItem(
                    song = song,
                    isCurrent = isCurrent,
                    isPlaying = isPlaying,
                    isPast = isPast,
                    onClick = { queueModel.goto(index) }
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
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val backgroundColor = if (isCurrent) FluxioTheme.colors.element.copy(alpha = 0.5f) else FluxioTheme.colors.bg
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(backgroundColor)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .alpha(if (isPast) 0.5f else 1f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Artwork
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(song.album.coverUri)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                error = painterResource(id = R.drawable.ic_album_24)
            )
            
            if (isCurrent) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(FluxioTheme.colors.bg.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    EqualizerIndicator(isPlaying = isPlaying)
                }
            }
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        // Text
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.name.resolve(context),
                style = FluxioTheme.typography.bodyMedium,
                color = FluxioTheme.colors.text1,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = song.artists.resolveNames(context),
                style = FluxioTheme.typography.labelMedium,
                color = FluxioTheme.colors.text2,
                maxLines = 1
            )
        }
        
        // Drag handle (just visual for v1)
        Icon(
            painter = painterResource(id = R.drawable.ic_handle_24),
            contentDescription = "Reordenar",
            tint = FluxioTheme.colors.text2
        )
    }
}
