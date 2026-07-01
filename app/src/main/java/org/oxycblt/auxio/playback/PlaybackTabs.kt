/*
 * Copyright (c) 2026 Fluxio Project
 * PlaybackTabs.kt is part of Fluxio.
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
package org.oxycblt.auxio.playback

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.unit.dp
import org.oxycblt.auxio.lyrics.LyricsViewModel
import org.oxycblt.auxio.playback.PlaybackPanelFragment.PlayerTab
import org.oxycblt.auxio.playback.audio.AudioTab
import org.oxycblt.auxio.playback.crossfade.CrossfadeSettings
import org.oxycblt.auxio.playback.equalizer.EqualizerViewModel
import org.oxycblt.auxio.playback.lyrics.LyricsTab
import org.oxycblt.auxio.playback.queue.QueueTab
import org.oxycblt.auxio.playback.queue.QueueViewModel
import org.oxycblt.auxio.playback.speed.PlaybackSpeedSettings
import org.oxycblt.auxio.playback.stereowidening.StereoWideningSettings
import org.oxycblt.auxio.ui.theme.FluxioTheme

@Composable
fun PlaybackTabs(
    currentTab: PlayerTab,
    onTabSelected: (PlayerTab) -> Unit,
    playbackModel: PlaybackViewModel,
    queueModel: QueueViewModel,
    lyricsModel: LyricsViewModel,
    equalizerModel: EqualizerViewModel,
    crossfadeSettings: CrossfadeSettings,
    stereoSettings: StereoWideningSettings,
    speedSettings: PlaybackSpeedSettings,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Tab bar — always visible
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabItem(
                label = "Cola",
                isSelected = currentTab == PlayerTab.QUEUE,
                onClick = {
                    onTabSelected(
                        if (currentTab == PlayerTab.QUEUE) PlayerTab.NONE else PlayerTab.QUEUE
                    )
                },
                modifier = Modifier.weight(1f),
            )
            TabItem(
                label = "Letras",
                isSelected = currentTab == PlayerTab.LYRICS,
                onClick = {
                    onTabSelected(
                        if (currentTab == PlayerTab.LYRICS) PlayerTab.NONE else PlayerTab.LYRICS
                    )
                },
                modifier = Modifier.weight(1f),
            )
            TabItem(
                label = "Audio",
                isSelected = currentTab == PlayerTab.AUDIO,
                onClick = {
                    onTabSelected(
                        if (currentTab == PlayerTab.AUDIO) PlayerTab.NONE else PlayerTab.AUDIO
                    )
                },
                modifier = Modifier.weight(1f),
            )
        }

        // Tab content — only shown when a tab is active
        AnimatedVisibility(
            visible = currentTab != PlayerTab.NONE,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 4 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 4 }),
            modifier = Modifier.weight(1f),
        ) {
            // AnimatedContent swaps between tabs with a crossfade.
            // Using a simple when here avoids all pager state management bugs.
            AnimatedContent(
                targetState = currentTab,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                modifier = Modifier.fillMaxSize(),
                label = "tab_content",
            ) { tab ->
                when (tab) {
                    PlayerTab.QUEUE ->
                        QueueTab(queueModel = queueModel, playbackModel = playbackModel)
                    PlayerTab.LYRICS ->
                        LyricsTab(lyricsModel = lyricsModel, playbackModel = playbackModel)
                    PlayerTab.AUDIO ->
                        AudioTab(
                            equalizerModel = equalizerModel,
                            playbackModel = playbackModel,
                            crossfadeSettings = crossfadeSettings,
                            stereoSettings = stereoSettings,
                            speedSettings = speedSettings,
                        )
                    PlayerTab.NONE -> Box(modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun TabItem(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.clickable(onClick = onClick).fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = FluxioTheme.typography.labelMedium,
                color = if (isSelected) FluxioTheme.colors.text1 else FluxioTheme.colors.text2,
            )
        }
        if (isSelected) {
            Box(
                modifier =
                    Modifier.fillMaxWidth(0.3f).height(2.dp).background(FluxioTheme.colors.text1)
            )
        }
    }
}
