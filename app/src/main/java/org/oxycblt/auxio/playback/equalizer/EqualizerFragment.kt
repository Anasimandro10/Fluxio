/*
 * Copyright (c) 2026 Fluxio Project
 * EqualizerFragment.kt is part of Fluxio.
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
package org.oxycblt.auxio.playback.equalizer

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentEqualizerBinding

/** Screen that displays the 10-band equalizer with preset selector and on/off switch. */
@AndroidEntryPoint
class EqualizerFragment : Fragment() {

    private var _binding: FragmentEqualizerBinding? = null
    private val binding
        get() = _binding!!

    private val viewModel: EqualizerViewModel by viewModels()

    private val bandLabels =
        listOf("31Hz", "63Hz", "125Hz", "250Hz", "500Hz", "1kHz", "2kHz", "4kHz", "8kHz", "16kHz")
    private val seekBars = mutableListOf<SeekBar>()
    private val valueLabels = mutableListOf<TextView>()
    private var ignoreSpinner = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentEqualizerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        buildBandColumns()
        setupPresetSpinner()
        binding.eqSwitch.setOnCheckedChangeListener { _, checked -> viewModel.setEnabled(checked) }
        collectState()
    }

    /**
     * Dynamically creates 10 vertical band columns and adds them to the horizontal container.
     *
     * Each column layout (top → bottom):
     * 1. Frequency label (e.g. "1kHz")
     * 2. FrameLayout wrapping a rotated SeekBar — appears as a vertical slider
     * 3. dB value label (e.g. "+3.0")
     *
     * To make the rotated SeekBar render correctly without being clipped:
     * - The FrameLayout dimensions match the VISUAL size of the bar (BAR_W × BAR_H).
     * - The SeekBar inside has PRE-ROTATION dimensions (BAR_H × BAR_W) and rotation = -90°. After
     *   rotation it visually fills exactly BAR_W × BAR_H.
     * - clipChildren=false is set on the FrameLayout, the column, and the container so that the
     *   SeekBar's pre-rotation layout bounds (which overflow the FrameLayout horizontally) are
     *   never clipped away.
     */
    private fun buildBandColumns() {
        val d = resources.displayMetrics.density

        // Visual dimensions of the vertical slider track
        val barW = (28 * d).toInt() // visual width of the rendered vertical bar
        val barH = (140 * d).toInt() // visual height of the rendered vertical bar

        // Fixed column width — slightly wider than the bar to accommodate labels
        val colW = (44 * d).toInt()

        for (i in 0 until 10) {
            // Column: all children are centered horizontally; clip disabled so the rotated
            // SeekBar's pre-rotation layout bounds can overflow without being cut off.
            val column =
                LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    clipChildren = false
                    clipToPadding = false
                    layoutParams =
                        LinearLayout.LayoutParams(colW, LinearLayout.LayoutParams.WRAP_CONTENT)
                }

            // ── TOP: frequency label ──────────────────────────────────────────
            val freqLabel =
                TextView(requireContext()).apply {
                    text = bandLabels[i]
                    textSize = 9f
                    gravity = Gravity.CENTER
                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                }

            // ── MIDDLE: FrameLayout + rotated SeekBar ─────────────────────────
            // The FrameLayout is sized to the VISUAL bounds (barW × barH).
            // The SeekBar inside is (barH × barW) pre-rotation, centered, then rotated -90°.
            // After rotation its visual footprint is exactly (barW × barH) — fits perfectly.
            val wrapper =
                FrameLayout(requireContext()).apply {
                    clipChildren = false
                    clipToPadding = false
                    layoutParams =
                        LinearLayout.LayoutParams(barW, barH).also {
                            it.topMargin = (6 * d).toInt()
                            it.bottomMargin = (6 * d).toInt()
                        }
                }

            val bar =
                SeekBar(requireContext()).apply {
                    max = 240 // center=120 → 0 dB; range: -12 to +12 dB (step 0.1 dB)
                    progress = 120
                    rotation = -90f // renders as a vertical bar (min at bottom, max at top)
                    layoutParams = FrameLayout.LayoutParams(barH, barW, Gravity.CENTER)
                }

            // ── BOTTOM: dB value label ────────────────────────────────────────
            val valueLabel =
                TextView(requireContext()).apply {
                    text = "0.0"
                    textSize = 9f
                    gravity = Gravity.CENTER
                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                }

            bar.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            val db = (progress - 120) / 10f
                            valueLabel.text = String.format(Locale.US, "%.1f", db)
                            viewModel.setBand(i, db)
                        }
                    }

                    override fun onStartTrackingTouch(sb: SeekBar?) {}

                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                }
            )

            seekBars.add(bar)
            valueLabels.add(valueLabel)

            wrapper.addView(bar)
            // Order: freq (top) → bar (middle) → value (bottom)
            column.addView(freqLabel)
            column.addView(wrapper)
            column.addView(valueLabel)
            binding.eqBandsContainer.addView(column)
        }
    }

    private fun setupPresetSpinner() {
        val names = EqualizerSettings.PRESET_NAMES + listOf(getString(R.string.lbl_eq_custom))
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.eqPresetSpinner.adapter = adapter
        binding.eqPresetSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    if (!ignoreSpinner && position < EqualizerSettings.PRESET_NAMES.size) {
                        viewModel.applyPreset(position)
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
    }

    private fun collectState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.bands.collect { bands ->
                        for (i in bands.indices) {
                            val progress = ((bands[i] * 10f) + 120).toInt().coerceIn(0, 240)
                            seekBars[i].progress = progress
                            valueLabels[i].text = String.format(Locale.US, "%.1f", bands[i])
                        }
                    }
                }
                launch {
                    viewModel.enabled.collect { enabled ->
                        binding.eqSwitch.isChecked = enabled
                        seekBars.forEach { it.isEnabled = enabled }
                        binding.eqPresetSpinner.isEnabled = enabled
                    }
                }
                launch {
                    viewModel.activePreset.collect { preset ->
                        ignoreSpinner = true
                        val pos =
                            if (preset == EqualizerSettings.PRESET_CUSTOM) {
                                EqualizerSettings.PRESET_NAMES.size
                            } else {
                                preset
                            }
                        binding.eqPresetSpinner.setSelection(pos)
                        binding.eqPresetSpinner.post { ignoreSpinner = false }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        seekBars.clear()
        valueLabels.clear()
    }
}
