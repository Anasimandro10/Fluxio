/*
 * Copyright (c) 2026 Fluxio Project
 * PlaybackSpeedSettings.kt is part of Fluxio.
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
package org.oxycblt.auxio.playback.speed

import android.content.Context
import androidx.preference.PreferenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists playback speed preference and exposes it as a [StateFlow].
 *
 * The UI (AudioTabFragment) calls [setSpeed] when the user moves the slider or taps a chip. Step
 * 40-1 will observe [speedFlow] in ExoPlaybackStateHolder to call ExoPlayer.setPlaybackParameters()
 * and make the speed audible.
 *
 * Follows the same pattern as [CrossfadeSettings] and [StereoWideningSettings].
 */
@Singleton
class PlaybackSpeedSettings @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    companion object {
        /** SharedPreferences key for playback speed. */
        const val KEY_SPEED = "fluxio_playback_speed"

        /** Default playback speed — normal tempo. */
        const val DEFAULT_SPEED = 1.0f

        /** Minimum allowed speed (0.25×). */
        const val MIN_SPEED = 0.25f

        /** Maximum allowed speed (3.0×). */
        const val MAX_SPEED = 3.0f
    }

    private val _speedFlow =
        MutableStateFlow(prefs.getFloat(KEY_SPEED, DEFAULT_SPEED).coerceIn(MIN_SPEED, MAX_SPEED))

    /**
     * Current playback speed as a [StateFlow]. Step 40-1 observes this in ExoPlaybackStateHolder.
     */
    val speedFlow: StateFlow<Float> = _speedFlow.asStateFlow()

    /** Current playback speed value [0.25–3.0]. */
    val speedX: Float
        get() = _speedFlow.value

    /**
     * Updates the playback speed, persists it to SharedPreferences, and notifies observers.
     *
     * @param speed The desired speed in the range [[MIN_SPEED], [MAX_SPEED]]. Values outside this
     *   range are clamped.
     */
    fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        prefs.edit().putFloat(KEY_SPEED, clamped).apply()
        _speedFlow.value = clamped
    }
}
