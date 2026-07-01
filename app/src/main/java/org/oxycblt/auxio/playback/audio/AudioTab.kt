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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── EQ card ───────────────────────────────────────────────────────────
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

        // ── Speed card ────────────────────────────────────────────────────────
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

        // ── Crossfade card ────────────────────────────────────────────────────
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

        // ── Stereo card ───────────────────────────────────────────────────────
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

        // ── Timer card ────────────────────────────────────────────────────────
        val timerStateLabel = run {
            val rem = timerRemainingMs
            if (rem == null) {
                stringResource(R.string.lbl_audio_off)
            } else {
                val mins = (rem / 60_000L).toInt()
                val secs = ((rem % 60_000L) / 1_000L).toInt()
                if (mins > 0) "${mins}m ${secs.toString().padStart(2, '0')}s" else "${secs}s"
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

// ─────────────────────────────────────────────────────────────────────────────
// Accordion card shell
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AudioAccordionCard(
    title: String,
    stateLabel: String,
    isExpanded: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    // Chevron rotates 90° when the card is open — smooth 250ms easing
    val chevronRotation by
        animateFloatAsState(
            targetValue = if (isExpanded) 90f else 0f,
            animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
            label = "chevron",
        )

    Column(
        modifier =
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(FluxioTheme.colors.surface)
    ) {
        // Header row — always visible, tappable
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .clickable(onClick = onClick)
                    .padding(horizontal = 16.dp, vertical = 18.dp),
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
                // Animated chevron
                Text(
                    text = "\u203a", // ›
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Normal,
                    color = FluxioTheme.colors.text2,
                    modifier = Modifier.rotate(chevronRotation),
                )
            }
        }

        // Expandable content — separator line then padding
        AnimatedVisibility(visible = isExpanded) {
            Column {
                HorizontalDivider(color = FluxioTheme.colors.separator, thickness = 1.dp)
                Box(
                    modifier =
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 20.dp)
                ) {
                    content()
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// EQ
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun EqCardContent(
    bands: FloatArray,
    enabled: Boolean,
    activePreset: Int,
    onToggle: (Boolean) -> Unit,
    onBandChange: (Int, Float) -> Unit,
    onPreset: (Int) -> Unit,
) {
    // "Pure color" — will be replaced by ambient color at step 31
    val accentColor = FluxioTheme.colors.text1
    val trackColor = FluxioTheme.colors.element
    val zeroLineColor = FluxioTheme.colors.text3
    val presetNames = EqualizerSettings.PRESET_NAMES

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

        // ── Enable / disable toggle ──────────────────────────────────────────
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
                        checkedTrackColor = accentColor,
                    ),
            )
        }

        // ── 10 custom vertical band sliders ──────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().height(200.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            bands.forEachIndexed { i, gain ->
                EqBandSlider(
                    gain = gain,
                    onGainChange = { onBandChange(i, it) },
                    enabled = enabled,
                    freqLabel = FREQ_LABELS[i],
                    // When disabled, desaturate accent to text3 so sliders look inactive
                    accentColor = if (enabled) accentColor else zeroLineColor,
                    trackColor = trackColor,
                    zeroLineColor = zeroLineColor,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // ── Preset chips — pill shape, scrollable ────────────────────────────
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(presetNames.size) { idx ->
                val isActive = activePreset == idx
                Box(
                    modifier =
                        Modifier.clip(RoundedCornerShape(20.dp))
                            .background(if (isActive) accentColor else FluxioTheme.colors.element)
                            .clickable(enabled = enabled) { onPreset(idx) }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
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

// ─────────────────────────────────────────────────────────────────────────────
// EQ band — custom Canvas slider
// Spec: track 3dp wide, full-height vertical, thumb 10dp, 0 dB reference line
// Touch: tap to jump, drag to sweep
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun EqBandSlider(
    gain: Float,
    onGainChange: (Float) -> Unit,
    enabled: Boolean,
    freqLabel: String,
    accentColor: Color,
    trackColor: Color,
    zeroLineColor: Color,
    modifier: Modifier = Modifier,
) {
    val trackWidthDp = 3.dp
    val thumbRadiusDp = 5.dp

    val isNonZero = kotlin.math.abs(gain) > 0.5f
    val gainText =
        when {
            gain > 0.5f -> "+${gain.toInt()}"
            gain < -0.5f -> "${gain.toInt()}"
            else -> "\u00b7" // · middle dot — neutral
        }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        // Gain value label — accent + SemiBold when band is boosted/cut
        Text(
            text = gainText,
            fontSize = 9.sp,
            fontWeight = if (isNonZero) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isNonZero) accentColor else zeroLineColor,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )

        Spacer(Modifier.height(4.dp))

        // Canvas — draws track, active fill, 0 dB line, thumb
        Canvas(
            modifier =
                Modifier.weight(1f).fillMaxWidth().pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        // Tap: jump thumb to finger position
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val h = size.height.toFloat()
                        onGainChange((12f - (down.position.y / h) * 24f).coerceIn(-12f, 12f))
                        // Drag: sweep continuously
                        do {
                            val event = awaitPointerEvent()
                            val drag = event.changes.firstOrNull() ?: break
                            if (drag.pressed) {
                                drag.consume()
                                onGainChange(
                                    (12f - (drag.position.y / h) * 24f).coerceIn(-12f, 12f)
                                )
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
        ) {
            val trackW = trackWidthDp.toPx()
            val thumbR = thumbRadiusDp.toPx()
            val cx = size.width / 2f
            val h = size.height

            // Y mapping: top edge = +12 dB, bottom edge = -12 dB
            val thumbY = ((12f - gain) / 24f) * h
            val centerY = h / 2f // 0 dB

            // 1. Full-height inactive track
            drawRect(
                color = trackColor,
                topLeft = Offset(cx - trackW / 2f, 0f),
                size = Size(trackW, h),
            )

            // 2. Active fill (thumb ↔ 0 dB), shown only when band ≠ 0
            if (isNonZero) {
                val fillTop = minOf(thumbY, centerY)
                val fillH = maxOf(thumbY, centerY) - fillTop
                drawRect(
                    color = accentColor.copy(alpha = if (enabled) 1f else 0.35f),
                    topLeft = Offset(cx - trackW / 2f, fillTop),
                    size = Size(trackW, fillH),
                )
            }

            // 3. 0 dB reference line — subtle tick extending 2.5× the track width each side
            drawLine(
                color = zeroLineColor.copy(alpha = 0.45f),
                start = Offset(cx - trackW * 2.5f, centerY),
                end = Offset(cx + trackW * 2.5f, centerY),
                strokeWidth = 1f,
            )

            // 4. Thumb circle
            drawCircle(
                color = if (enabled) accentColor else trackColor,
                radius = thumbR,
                center = Offset(cx, thumbY),
            )
        }

        Spacer(Modifier.height(4.dp))

        // Frequency label — always text3
        Text(
            text = freqLabel,
            fontSize = 10.sp,
            color = zeroLineColor,
            textAlign = TextAlign.Center,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Speed
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SpeedCardContent(speedValue: Float, onSpeedChange: (Float) -> Unit) {
    val accentColor = FluxioTheme.colors.text1
    val chips = listOf(0.5f, 0.75f, 1.0f, 1.5f, 2.0f)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Slider(
            value = speedValue,
            onValueChange = onSpeedChange,
            valueRange = 0.25f..3.0f,
            colors =
                SliderDefaults.colors(
                    thumbColor = accentColor,
                    activeTrackColor = accentColor,
                    inactiveTrackColor = FluxioTheme.colors.element,
                ),
            modifier = Modifier.fillMaxWidth(),
        )
        // Speed preset chips — pill shape
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            chips.forEach { speed ->
                val isActive = kotlin.math.abs(speedValue - speed) < 0.01f
                val label = "${String.format("%.2f", speed).trimEnd('0').trimEnd('.')}\u00d7"
                Box(
                    modifier =
                        Modifier.clip(RoundedCornerShape(20.dp))
                            .background(if (isActive) accentColor else FluxioTheme.colors.element)
                            .clickable { onSpeedChange(speed) }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
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

// ─────────────────────────────────────────────────────────────────────────────
// Crossfade
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CrossfadeCardContent(durationSecs: Int, onDurationChange: (Int) -> Unit) {
    val accentColor = FluxioTheme.colors.text1
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
                    thumbColor = accentColor,
                    activeTrackColor = accentColor,
                    inactiveTrackColor = FluxioTheme.colors.element,
                ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Stereo Widening
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StereoCardContent(amount: Int, onAmountChange: (Int) -> Unit) {
    val accentColor = FluxioTheme.colors.text1
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
                    thumbColor = accentColor,
                    activeTrackColor = accentColor,
                    inactiveTrackColor = FluxioTheme.colors.element,
                ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sleep Timer
// ─────────────────────────────────────────────────────────────────────────────

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
    val accentColor = FluxioTheme.colors.text1
    val ctx = LocalContext.current
    val timerActive = timerRemainingMs != null

    // Which preset chip matches the active timer, if any
    val activePresetMinutes: Int? =
        timerRemainingMs?.let { ms ->
            val remainingMins = ((ms + 30_000L) / 60_000L).toInt()
            TIMER_PRESETS.firstOrNull { it.minutes == remainingMins }?.minutes
        }
    val customChipActive = timerActive && activePresetMinutes == null

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

        // Preset chips — pill shape, wrapping row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TIMER_PRESETS.forEach { preset ->
                val isActive = activePresetMinutes == preset.minutes
                Box(
                    modifier =
                        Modifier.clip(RoundedCornerShape(20.dp))
                            .background(if (isActive) accentColor else FluxioTheme.colors.element)
                            .clickable {
                                if (isActive) onCancelTimer() else onStartTimer(preset.minutes)
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = preset.label,
                        style = FluxioTheme.typography.labelMedium,
                        color = if (isActive) FluxioTheme.colors.bg else FluxioTheme.colors.text2,
                    )
                }
            }

            // Custom chip
            Box(
                modifier =
                    Modifier.clip(RoundedCornerShape(20.dp))
                        .background(
                            if (customChipActive) accentColor else FluxioTheme.colors.element
                        )
                        .clickable {
                            if (customChipActive) onCancelTimer()
                            else showCustomTimerDialog(ctx, onStartTimer)
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.lbl_timer_custom),
                    style = FluxioTheme.typography.labelMedium,
                    color =
                        if (customChipActive) FluxioTheme.colors.bg else FluxioTheme.colors.text2,
                )
            }
        }

        // Stop at end of song toggle
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
                        checkedTrackColor = accentColor,
                    ),
            )
        }
    }
}

private fun showCustomTimerDialog(ctx: android.content.Context, onStartTimer: (Int) -> Unit) {
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
