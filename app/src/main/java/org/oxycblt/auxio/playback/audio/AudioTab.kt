/*
 * Copyright (c) 2026 Fluxio Project
 * AudioTab.kt is part of Fluxio.
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
package org.oxycblt.auxio.playback.audio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.oxycblt.auxio.R
import org.oxycblt.auxio.ui.theme.FluxioTheme

enum class AudioCard {
    NONE,
    EQUALIZER,
    SPEED,
    CROSSFADE,
    STEREO,
    TIMER,
}

@Composable
fun AudioTab() {
    var expandedCard by remember { mutableStateOf(AudioCard.EQUALIZER) }
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AudioAccordionCard(
            title = stringResource(R.string.lbl_equalizer),
            isExpanded = expandedCard == AudioCard.EQUALIZER,
            onClick = {
                expandedCard =
                    if (expandedCard == AudioCard.EQUALIZER) AudioCard.NONE else AudioCard.EQUALIZER
            },
        ) {
            Text("Equalizer WIP", color = FluxioTheme.colors.text2)
        }

        AudioAccordionCard(
            title = stringResource(R.string.lbl_playback_speed),
            isExpanded = expandedCard == AudioCard.SPEED,
            onClick = {
                expandedCard =
                    if (expandedCard == AudioCard.SPEED) AudioCard.NONE else AudioCard.SPEED
            },
        ) {
            Text("Speed WIP", color = FluxioTheme.colors.text2)
        }

        AudioAccordionCard(
            title = stringResource(R.string.lbl_crossfade),
            isExpanded = expandedCard == AudioCard.CROSSFADE,
            onClick = {
                expandedCard =
                    if (expandedCard == AudioCard.CROSSFADE) AudioCard.NONE else AudioCard.CROSSFADE
            },
        ) {
            Text("Crossfade WIP", color = FluxioTheme.colors.text2)
        }

        AudioAccordionCard(
            title = stringResource(R.string.lbl_stereo_widening),
            isExpanded = expandedCard == AudioCard.STEREO,
            onClick = {
                expandedCard =
                    if (expandedCard == AudioCard.STEREO) AudioCard.NONE else AudioCard.STEREO
            },
        ) {
            Text("Stereo Widening WIP", color = FluxioTheme.colors.text2)
        }

        AudioAccordionCard(
            title = stringResource(R.string.lbl_sleep_timer),
            isExpanded = expandedCard == AudioCard.TIMER,
            onClick = {
                expandedCard =
                    if (expandedCard == AudioCard.TIMER) AudioCard.NONE else AudioCard.TIMER
            },
        ) {
            Text("Timer WIP", color = FluxioTheme.colors.text2)
        }
    }
}

@Composable
fun AudioAccordionCard(
    title: String,
    isExpanded: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(FluxioTheme.colors.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = FluxioTheme.typography.titleMedium,
                color = FluxioTheme.colors.text1,
                modifier = Modifier.weight(1f),
            )
        }

        AnimatedVisibility(visible = isExpanded) {
            Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                content()
            }
        }
    }
}
