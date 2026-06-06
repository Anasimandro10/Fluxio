/*
 * Copyright (c) 2026 Fluxio Project
 * EqualizerIndicator.kt is part of Fluxio.
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
package org.oxycblt.auxio.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.oxycblt.auxio.ui.theme.FluxioTheme

@Composable
fun EqualizerIndicator(isPlaying: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.height(12.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        EqualizerBar(isPlaying = isPlaying, duration = 400, targetHeight = 10f)
        EqualizerBar(isPlaying = isPlaying, duration = 300, targetHeight = 12f)
        EqualizerBar(isPlaying = isPlaying, duration = 500, targetHeight = 9f)
    }
}

@Composable
private fun EqualizerBar(isPlaying: Boolean, duration: Int, targetHeight: Float) {
    val infiniteTransition = rememberInfiniteTransition(label = "eq_transition")
    val animatedHeight by
        infiniteTransition.animateFloat(
            initialValue = 4f,
            targetValue = targetHeight,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = duration, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
            label = "eq_height",
        )

    // Smoothly transition between animating and static states
    val finalHeight by
        animateFloatAsState(
            targetValue = if (isPlaying) animatedHeight else 4f,
            animationSpec = tween(150),
            label = "eq_stop_anim",
        )

    Box(
        modifier =
            Modifier.width(3.dp)
                .height(finalHeight.dp)
                .clip(RoundedCornerShape(1.5.dp))
                .background(FluxioTheme.colors.text1)
    )
}
