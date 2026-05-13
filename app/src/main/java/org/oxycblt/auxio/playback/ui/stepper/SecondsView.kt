/*
 * Copyright (c) 2025 Fluxio Project
 * SecondsView.kt is part of Fluxio.
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
package org.oxycblt.auxio.playback.ui.stepper

import android.content.Context
import android.provider.Settings
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.PlayerFastSeekSecondsViewBinding

class SecondsView(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {

    companion object {
        const val ICON_ANIMATION_DURATION = 750L
    }

    var cycleDuration: Long = ICON_ANIMATION_DURATION

    var seconds: Int = 0
        set(value) {
            binding.tvSeconds.text =
                context.resources.getQuantityString(R.plurals.fmt_seconds, value, value)
            field = value
        }

    // Done as a field so that we don't have to compute on each tab if animations are enabled
    private val animationsEnabled =
        Settings.System.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1F,
        ) != 0F

    val binding = PlayerFastSeekSecondsViewBinding.inflate(LayoutInflater.from(context), this)

    init {
        orientation = VERTICAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }

    fun setForwarding(isForward: Boolean) {
        // no-op, design removed arrows
    }

    fun startAnimation() {
        // no-op, design removed arrows
    }

    fun stopAnimation() {
        // no-op, design removed arrows
    }
}
