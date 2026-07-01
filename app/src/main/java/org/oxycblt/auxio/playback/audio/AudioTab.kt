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

import android.text.InputType
import android.widget.EditText
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.oxycblt.auxio.R
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.playback.crossfade.CrossfadeSettings
import org.oxycblt.auxio.playback.equalizer.EqualizerSettings
import org.oxycblt.auxio.playback.equalizer.EqualizerViewModel
import org.oxycblt.auxio.playback.speed.PlaybackSpeedSettings
import org.oxycblt.auxio.playback.stereowidening.StereoWideningSettings
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
fun AudioTab(
    equalizerModel: EqualizerViewModel,
    playbackModel: PlaybackViewModel,
    crossfadeSettings: CrossfadeSettings,
    stereoSettings: StereoWideningSettings,
    speedSettings: PlaybackSpeedSettings,
) {
    var expandedCard by remember { mutableStateOf(AudioCard.EQUALIZER) }
    val scrollState = rememberScrollState()

    val bands by equalizerModel.bands.collectAsState()
    val eqEnabled by equalizerModel.enabled.collectAsState()
    val activePreset by equalizerModel.activePreset.collectAsState()
    val profileName by equalizerModel.autoEqProfileName.collectAsState()

    var speedValue by remember { mutableFloatStateOf(speedSettings.speedX) }
    var crossfadeSecs by remember { mutableIntStateOf(crossfadeSettings.durationSeconds) }
    var crossfadeEnabled by remember { mutableStateOf(crossfadeSettings.enabled) }
    var stereoAmount by remember { mutableIntStateOf(stereoSettings.amountPercent) }

    val timerRemainingMs by playbackModel.timerRemainingMs.collectAsState()
    val stopAtEndOfSong by playbackModel.stopAtEndOfSong.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // EQ card
        val eqLabel =
            if (eqEnabled) {
                profileName
                    ?: EqualizerSettings.PRESET_NAMES.getOrNull(activePreset)
                    ?: stringResource(R.string.lbl_eq_custom)
            } else {
                "Off"
            }
        AudioAccordionCard(
            title = stringResource(R.string.lbl_equalizer),
            stateLabel = eqLabel,
            isExpanded = expandedCard == AudioCard.EQUALIZER,
            onClick = {
                expandedCard =
                    if (expandedCard == AudioCard.EQUALIZER) AudioCard.NONE else AudioCard.EQUALIZER
            },
        ) {
            EqCardContent(
                bands = bands,
                enabled = eqEnabled,
                activePreset = activePreset,
                onToggle = { equalizerModel.setEnabled(it) },
                onBandChange = { i, v -> equalizerModel.setBand(i, v) },
                onPreset = { equalizerModel.applyPreset(it) },
            )
        }

        // Speed card
        val speedLabel = run {
            val s = String.format("%.2f", speedValue).trimEnd('0').trimEnd('.')
            "${s}\u00d7"
        }
        AudioAccordionCard(
            title = stringResource(R.string.lbl_playback_speed),
            stateLabel = speedLabel,
            isExpanded = expandedCard == AudioCard.SPEED,
            onClick = {
                expandedCard =
                    if (expandedCard == AudioCard.SPEED) AudioCard.NONE else AudioCard.SPEED
            },
        ) {
            SpeedCardContent(
                speedValue = speedValue,
                onSpeedChange = {
                    speedValue = it
                    speedSettings.setSpeed(it)
                },
            )
        }

        // Crossfade card
        val crossfadeLabel =
            if (!crossfadeEnabled || crossfadeSecs == 0) "Off" else "${crossfadeSecs}s"
        AudioAccordionCard(
            title = stringResource(R.string.lbl_crossfade),
            stateLabel = crossfadeLabel,
            isExpanded = expandedCard == AudioCard.CROSSFADE,
            onClick = {
                expandedCard =
                    if (expandedCard == AudioCard.CROSSFADE) AudioCard.NONE else AudioCard.CROSSFADE
            },
        ) {
            CrossfadeCardContent(
                durationSecs = crossfadeSecs,
                onDurationChange = { secs ->
                    crossfadeSecs = secs
                    crossfadeEnabled = secs > 0
                    crossfadeSettings.setEnabled(secs > 0)
                    crossfadeSettings.setDuration(secs)
                },
            )
        }

        // Stereo card
        val stereoLabel = if (stereoAmount == 0) "Off" else "${stereoAmount}%"
        AudioAccordionCard(
            title = stringResource(R.string.lbl_stereo_widening),
            stateLabel = stereoLabel,
            isExpanded = expandedCard == AudioCard.STEREO,
            onClick = {
                expandedCard =
                    if (expandedCard == AudioCard.STEREO) AudioCard.NONE else AudioCard.STEREO
            },
        ) {
            StereoCardContent(
                amount = stereoAmount,
                onAmountChange = {
                    stereoAmount = it
                    stereoSettings.setAmount(it)
                },
            )
        }

        // Timer card
        val timerStateLabel = run {
            val rem = timerRemainingMs
            if (rem == null) {
                stringResource(R.string.lbl_audio_off)
            } else {
                val mins = (rem / 60_000L).toInt()
                val secs = ((rem % 60_000L) / 1_000L).toInt()
                if (mins > 0) "${mins}m ${secs.toString().padStart(2,'0')}s" else "${secs}s"
            }
        }
        AudioAccordionCard(
            title = stringResource(R.string.lbl_sleep_timer),
            stateLabel = timerStateLabel,
            isExpanded = expandedCard == AudioCard.TIMER,
            onClick = {
                expandedCard =
                    if (expandedCard == AudioCard.TIMER) AudioCard.NONE else AudioCard.TIMER
            },
        ) {
            TimerCardContent(
                timerRemainingMs = timerRemainingMs,
                stopAtEndOfSong = stopAtEndOfSong,
                onStartTimer = { playbackModel.startSleepTimer(it) },
                onCancelTimer = { playbackModel.cancelSleepTimer() },
                onStopAtEndOfSongChanged = { playbackModel.setStopAtEndOfSong(it) },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Speed
// ---------------------------------------------------------------------------

@Composable
private fun SpeedCardContent(speedValue: Float, onSpeedChange: (Float) -> Unit) {
    val pureColor = FluxioTheme.colors.text1
    val chips = listOf(0.5f, 0.75f, 1.0f, 1.5f, 2.0f)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Slider(
            value = speedValue,
            onValueChange = onSpeedChange,
            valueRange = 0.25f..3.0f,
            colors =
                SliderDefaults.colors(
                    thumbColor = pureColor,
                    activeTrackColor = pureColor,
                    inactiveTrackColor = FluxioTheme.colors.element,
                ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            chips.forEach { speed ->
                val isActive = kotlin.math.abs(speedValue - speed) < 0.01f
                val label = "${String.format("%.2f", speed).trimEnd('0').trimEnd('.')}\u00d7"
                Box(
                    modifier =
                        Modifier.clip(RoundedCornerShape(8.dp))
                            .background(if (isActive) pureColor else FluxioTheme.colors.element)
                            .clickable { onSpeedChange(speed) }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = label,
                        style = FluxioTheme.typography.labelMedium,
                        color = if (isActive) FluxioTheme.colors.bg else FluxioTheme.colors.text2,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Crossfade
// ---------------------------------------------------------------------------

@Composable
private fun CrossfadeCardContent(durationSecs: Int, onDurationChange: (Int) -> Unit) {
    val pureColor = FluxioTheme.colors.text1
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = if (durationSecs == 0) "Off" else "${durationSecs}s",
            style = FluxioTheme.typography.bodyMedium,
            color = FluxioTheme.colors.text2,
        )
        Slider(
            value = durationSecs.toFloat(),
            onValueChange = { onDurationChange(it.toInt()) },
            valueRange = 0f..12f,
            steps = 11,
            colors =
                SliderDefaults.colors(
                    thumbColor = pureColor,
                    activeTrackColor = pureColor,
                    inactiveTrackColor = FluxioTheme.colors.element,
                ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---------------------------------------------------------------------------
// Stereo
// ---------------------------------------------------------------------------

@Composable
private fun StereoCardContent(amount: Int, onAmountChange: (Int) -> Unit) {
    val pureColor = FluxioTheme.colors.text1
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = if (amount == 0) "Off" else "${amount}%",
            style = FluxioTheme.typography.bodyMedium,
            color = FluxioTheme.colors.text2,
        )
        Slider(
            value = amount.toFloat(),
            onValueChange = { onAmountChange(it.toInt()) },
            valueRange = 0f..100f,
            colors =
                SliderDefaults.colors(
                    thumbColor = pureColor,
                    activeTrackColor = pureColor,
                    inactiveTrackColor = FluxioTheme.colors.element,
                ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---------------------------------------------------------------------------
// Timer
// ---------------------------------------------------------------------------

private data class TimerPreset(val label: String, val minutes: Int)

private val TIMER_PRESETS =
    listOf(
        TimerPreset("15m", 15),
        TimerPreset("30m", 30),
        TimerPreset("45m", 45),
        TimerPreset("1h", 60),
        TimerPreset("2h", 120),
    )

@Composable
private fun TimerCardContent(
    timerRemainingMs: Long?,
    stopAtEndOfSong: Boolean,
    onStartTimer: (Int) -> Unit,
    onCancelTimer: () -> Unit,
    onStopAtEndOfSongChanged: (Boolean) -> Unit,
) {
    val pureColor = FluxioTheme.colors.text1
    val ctx = LocalContext.current
    val timerActive = timerRemainingMs != null

    // Determine which preset chip (if any) matches the active timer.
    val activePresetMinutes: Int? =
        timerRemainingMs?.let { ms ->
            val remainingMins = ((ms + 30_000L) / 60_000L).toInt()
            TIMER_PRESETS.firstOrNull { it.minutes == remainingMins }?.minutes
        }
    val customChipActive = timerActive && activePresetMinutes == null

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Preset chips row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TIMER_PRESETS.forEach { preset ->
                val isActive = activePresetMinutes == preset.minutes
                Box(
                    modifier =
                        Modifier.clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isActive) pureColor else FluxioTheme.colors.element
                            )
                            .clickable {
                                if (isActive) onCancelTimer() else onStartTimer(preset.minutes)
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = preset.label,
                        style = FluxioTheme.typography.labelMedium,
                        color =
                            if (isActive) FluxioTheme.colors.bg else FluxioTheme.colors.text2,
                    )
                }
            }

            // Custom chip
            Box(
                modifier =
                    Modifier.clip(RoundedCornerShape(8.dp))
                        .background(
                            if (customChipActive) pureColor else FluxioTheme.colors.element
                        )
                        .clickable {
                            if (customChipActive) {
                                onCancelTimer()
                            } else {
                                showCustomTimerDialog(ctx, onStartTimer)
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.lbl_timer_custom),
                    style = FluxioTheme.typography.labelMedium,
                    color =
                        if (customChipActive) FluxioTheme.colors.bg
                        else FluxioTheme.colors.text2,
                )
            }
        }

        // Stop at end of song toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.lbl_timer_stop_at_end),
                style = FluxioTheme.typography.bodyMedium,
                color = FluxioTheme.colors.text1,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = stopAtEndOfSong,
                onCheckedChange = onStopAtEndOfSongChanged,
                colors =
                    SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = pureColor,
                    ),
            )
        }
    }
}

private fun showCustomTimerDialog(
    ctx: android.content.Context,
    onStartTimer: (Int) -> Unit,
) {
    val paddingPx = (16 * ctx.resources.displayMetrics.density).toInt()
    val editText =
        EditText(ctx).apply {
            hint = ctx.getString(R.string.hint_sleep_custom_minutes)
            inputType = InputType.TYPE_CLASS_NUMBER
            setPadding(paddingPx, paddingPx / 2, paddingPx, paddingPx / 2)
        }
    MaterialAlertDialogBuilder(ctx)
        .setTitle(R.string.lbl_sleep_timer)
        .setView(editText)
        .setPositiveButton(R.string.lbl_sleep_start_timer) { _, _ ->
            val minutes = editText.text?.toString()?.trim()?.toIntOrNull()
            if (minutes != null && minutes > 0) {
                onStartTimer(minutes)
            } else {
                Toast.makeText(ctx, R.string.err_sleep_timer_invalid, Toast.LENGTH_SHORT).show()
            }
        }
        .setNegativeButton(R.string.lbl_cancel, null)
        .show()
}

// ---------------------------------------------------------------------------
// EQ
// ---------------------------------------------------------------------------

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
                fontSize = 17.sp,
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

        // 10 vertical sliders
        Row(
            modifier = Modifier.fillMaxWidth().height(180.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            bands.forEachIndexed { i, gain ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = if (gain >= 0) "+${gain.toInt()}" else "${gain.toInt()}",
                        fontSize = 9.sp,
                        color = FluxioTheme.colors.text2,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(2.dp))
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
                    Text(
                        text = FREQ_LABELS[i],
                        fontSize = 10.sp,
                        color = FluxioTheme.colors.text2,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        // Preset chips
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

// ---------------------------------------------------------------------------
// Accordion card shell
// ---------------------------------------------------------------------------

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
                fontSize = 17.sp,
                color = FluxioTheme.colors.text1,
                modifier = Modifier.weight(1f),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (stateLabel.isNotEmpty()) {
                    Text(text = stateLabel, fontSize = 15.sp, color = FluxioTheme.colors.text2)
                }
                Text(
                    text = "›",
                    fontSize = 17.sp,
                    color = FluxioTheme.colors.text2,
                    modifier = Modifier.padding(bottom = 2.dp),
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
