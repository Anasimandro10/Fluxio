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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.oxycblt.auxio.R
import org.oxycblt.auxio.playback.equalizer.EqualizerSettings
import org.oxycblt.auxio.playback.equalizer.EqualizerViewModel
import org.oxycblt.auxio.ui.theme.FluxioTheme

private val FREQ_LABELS = listOf("31", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

enum class AudioCard {
    NONE,
    EQUALIZER,
    SPEED,
    CROSSFADE,
    STEREO,
    TIMER,
}

@Composable
fun AudioTab(equalizerModel: EqualizerViewModel) {
    var expandedCard by remember { mutableStateOf(AudioCard.EQUALIZER) }
    val scrollState = rememberScrollState()

    val bands by equalizerModel.bands.collectAsState()
    val enabled by equalizerModel.enabled.collectAsState()
    val activePreset by equalizerModel.activePreset.collectAsState()
    val profileName by equalizerModel.autoEqProfileName.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // EQ Card
        AudioAccordionCard(
            title = stringResource(R.string.lbl_equalizer),
            stateLabel =
                if (enabled) {
                    profileName
                        ?: EqualizerSettings.PRESET_NAMES.getOrNull(activePreset)
                        ?: stringResource(R.string.lbl_eq_custom)
                } else {
                    stringResource(R.string.lbl_sleep_timer_off).let { "Off" }
                },
            isExpanded = expandedCard == AudioCard.EQUALIZER,
            onClick = {
                expandedCard =
                    if (expandedCard == AudioCard.EQUALIZER) AudioCard.NONE else AudioCard.EQUALIZER
            },
        ) {
            EqCardContent(
                bands = bands,
                enabled = enabled,
                activePreset = activePreset,
                onToggle = { equalizerModel.setEnabled(it) },
                onBandChange = { i, v -> equalizerModel.setBand(i, v) },
                onPreset = { equalizerModel.applyPreset(it) },
            )
        }

        // Speed Card (placeholder for 30-C-8c)
        AudioAccordionCard(
            title = stringResource(R.string.lbl_playback_speed),
            stateLabel = "",
            isExpanded = expandedCard == AudioCard.SPEED,
            onClick = {
                expandedCard =
                    if (expandedCard == AudioCard.SPEED) AudioCard.NONE else AudioCard.SPEED
            },
        ) {
            Text(
                text = "— (30-C-8c) —",
                color = FluxioTheme.colors.text3,
                style = FluxioTheme.typography.bodyMedium,
            )
        }

        // Crossfade Card (placeholder for 30-C-8c)
        AudioAccordionCard(
            title = stringResource(R.string.lbl_crossfade),
            stateLabel = "",
            isExpanded = expandedCard == AudioCard.CROSSFADE,
            onClick = {
                expandedCard =
                    if (expandedCard == AudioCard.CROSSFADE) AudioCard.NONE else AudioCard.CROSSFADE
            },
        ) {
            Text(
                text = "— (30-C-8c) —",
                color = FluxioTheme.colors.text3,
                style = FluxioTheme.typography.bodyMedium,
            )
        }

        // Stereo Card (placeholder for 30-C-8c)
        AudioAccordionCard(
            title = stringResource(R.string.lbl_stereo_widening),
            stateLabel = "",
            isExpanded = expandedCard == AudioCard.STEREO,
            onClick = {
                expandedCard =
                    if (expandedCard == AudioCard.STEREO) AudioCard.NONE else AudioCard.STEREO
            },
        ) {
            Text(
                text = "— (30-C-8c) —",
                color = FluxioTheme.colors.text3,
                style = FluxioTheme.typography.bodyMedium,
            )
        }

        // Timer Card (placeholder for 30-C-8d)
        AudioAccordionCard(
            title = stringResource(R.string.lbl_sleep_timer),
            stateLabel = "",
            isExpanded = expandedCard == AudioCard.TIMER,
            onClick = {
                expandedCard =
                    if (expandedCard == AudioCard.TIMER) AudioCard.NONE else AudioCard.TIMER
            },
        ) {
            Text(
                text = "— (30-C-8d) —",
                color = FluxioTheme.colors.text3,
                style = FluxioTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun EqCardContent(
    bands: FloatArray,
    enabled: Boolean,
    activePreset: Int,
    onToggle: (Boolean) -> Unit,
    onBandChange: (Int, Float) -> Unit,
    onPreset: (Int) -> Unit,
) {
    val pureColor = FluxioTheme.colors.text1
    val presetNames = EqualizerSettings.PRESET_NAMES

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Enable toggle
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.lbl_equalizer),
                style = FluxioTheme.typography.bodyMedium,
                color = FluxioTheme.colors.text1,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors =
                    SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = pureColor,
                    ),
            )
        }

        // 10 vertical sliders side by side
        Row(
            modifier = Modifier.fillMaxWidth().height(180.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            bands.forEachIndexed { i, gain ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f),
                ) {
                    // Gain label
                    Text(
                        text = if (gain >= 0) "+${gain.toInt()}" else "${gain.toInt()}",
                        fontSize = 9.sp,
                        color = FluxioTheme.colors.text2,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(2.dp))
                    // Vertical slider via rotation
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Slider(
                            value = gain,
                            onValueChange = { onBandChange(i, it) },
                            valueRange = -12f..12f,
                            enabled = enabled,
                            colors =
                                SliderDefaults.colors(
                                    thumbColor = pureColor,
                                    activeTrackColor = pureColor,
                                    inactiveTrackColor = FluxioTheme.colors.element,
                                ),
                            modifier = Modifier.width(120.dp).rotate(-90f),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    // Freq label
                    Text(
                        text = FREQ_LABELS[i],
                        fontSize = 10.sp,
                        color = FluxioTheme.colors.text2,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        // Preset chips (scrollable row)
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(presetNames.size) { idx ->
                val isActive = activePreset == idx
                Box(
                    modifier =
                        Modifier.clip(RoundedCornerShape(8.dp))
                            .background(if (isActive) pureColor else FluxioTheme.colors.element)
                            .clickable(enabled = enabled) { onPreset(idx) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = presetNames[idx],
                        style = FluxioTheme.typography.labelMedium,
                        color = if (isActive) FluxioTheme.colors.bg else FluxioTheme.colors.text2,
                    )
                }
            }
        }
    }
}

@Composable
fun AudioAccordionCard(
    title: String,
    stateLabel: String,
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
            if (stateLabel.isNotEmpty()) {
                Text(
                    text = stateLabel,
                    style = FluxioTheme.typography.bodyMedium,
                    color = FluxioTheme.colors.text2,
                )
            }
        }

        AnimatedVisibility(visible = isExpanded) {
            Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                content()
            }
        }
    }
}
