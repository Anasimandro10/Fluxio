/*
 * Copyright (c) 2026 Fluxio Project
 * FastSeekOverlay.kt is part of Fluxio.
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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.graphics.ColorUtils

/**
 * Displays a fast-seek overlay following the Fluxio design spec:
 * - Circle at [accentColor] with 15% opacity in the left (backward) or right (forward) half.
 * - Label "±Ns" centered in the circle: 22sp SemiBold, #FFFFFF.
 * - Fades in over 150 ms, fades out over 200 ms.
 *
 * Call [show] on each seek event, [hide] after the seek series ends, and [applyAccentColor]
 * whenever the album art color changes (DynamicColorManager, step 31).
 */
class FastSeekOverlay
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
    View(context, attrs, defStyleAttr) {

    private var accentColor: Int = resolvePrimaryColor(context)

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize =
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 22f, resources.displayMetrics)
            // SemiBold; will be replaced by Inter SemiBold once the font is loaded at this site
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

    private var seekSeconds: Int = 0
    private var seekForward: Boolean = true

    init {
        visibility = GONE
    }

    /**
     * Show the overlay for a seek of [seconds] in the given [isForward] direction.
     *
     * If the view is already visible the label is updated without restarting the fade.
     */
    fun show(seconds: Int, isForward: Boolean) {
        seekSeconds = seconds
        seekForward = isForward
        animate().cancel()
        if (visibility != VISIBLE) {
            alpha = 0f
            visibility = VISIBLE
            animate().alpha(1f).setDuration(150).start()
        }
        invalidate()
    }

    /** Fade out over 200 ms then set visibility to GONE. */
    fun hide() {
        animate().cancel()
        animate().alpha(0f).setDuration(200).withEndAction { visibility = GONE }.start()
    }

    /**
     * Update the accent color used for the circle background.
     *
     * Intended to be called by DynamicColorManager (step 31) whenever the album art color changes.
     */
    fun applyAccentColor(color: Int) {
        accentColor = color
        if (visibility == VISIBLE) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        val cx = if (seekForward) w * 0.75f else w * 0.25f
        val cy = h * 0.5f
        // Circle radius: fits comfortably in a quarter of the view width
        val radius = minOf(w * 0.22f, h * 0.35f)

        // Circle: accent color at 15% opacity
        circlePaint.color = ColorUtils.setAlphaComponent(accentColor, (255 * 0.15f).toInt())
        canvas.drawCircle(cx, cy, radius, circlePaint)

        // Label: "+10s" or "-10s"
        val label = if (seekForward) "+${seekSeconds}s" else "-${seekSeconds}s"
        val fm = textPaint.fontMetrics
        val textY = cy - (fm.ascent + fm.descent) / 2f
        canvas.drawText(label, cx, textY, textPaint)
    }

    private companion object {
        fun resolvePrimaryColor(context: Context): Int {
            val tv = TypedValue()
            context.theme.resolveAttribute(android.R.attr.colorPrimary, tv, true)
            return tv.data
        }
    }
}
