package org.oxycblt.auxio.playback

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.oxycblt.auxio.R
import org.oxycblt.auxio.playback.PlaybackPanelFragment.PlayerTab
import org.oxycblt.auxio.ui.theme.FluxioTheme

@Composable
fun PlaybackTabs(
    currentTab: PlayerTab,
    onTabSelected: (PlayerTab) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Tab Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TabItem(
                text = stringResource(R.string.lbl_tab_queue),
                isSelected = currentTab == PlayerTab.QUEUE,
                onClick = { onTabSelected(if (currentTab == PlayerTab.QUEUE) PlayerTab.NONE else PlayerTab.QUEUE) },
                modifier = Modifier.weight(1f)
            )
            TabItem(
                text = stringResource(R.string.lbl_tab_lyrics),
                isSelected = currentTab == PlayerTab.LYRICS,
                onClick = { onTabSelected(if (currentTab == PlayerTab.LYRICS) PlayerTab.NONE else PlayerTab.LYRICS) },
                modifier = Modifier.weight(1f)
            )
            TabItem(
                text = stringResource(R.string.lbl_tab_audio),
                isSelected = currentTab == PlayerTab.AUDIO,
                onClick = { onTabSelected(if (currentTab == PlayerTab.AUDIO) PlayerTab.NONE else PlayerTab.AUDIO) },
                modifier = Modifier.weight(1f)
            )
        }

        // Pager Content Placeholder
        AnimatedVisibility(
            visible = currentTab != PlayerTab.NONE,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 4 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 4 }),
            modifier = Modifier.weight(1f)
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (currentTab) {
                    PlayerTab.QUEUE -> Text("Queue Compose Content (WIP)", style = FluxioTheme.typography.bodyMedium, color = FluxioTheme.colors.text1)
                    PlayerTab.LYRICS -> Text("Lyrics Compose Content (WIP)", style = FluxioTheme.typography.bodyMedium, color = FluxioTheme.colors.text1)
                    PlayerTab.AUDIO -> Text("Audio Compose Content (WIP)", style = FluxioTheme.typography.bodyMedium, color = FluxioTheme.colors.text1)
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun TabItem(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = FluxioTheme.typography.labelMedium,
                color = if (isSelected) FluxioTheme.colors.text1 else FluxioTheme.colors.text2
            )
        }
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.3f)
                    .height(2.dp)
                    .background(FluxioTheme.colors.text1)
            )
        }
    }
}
