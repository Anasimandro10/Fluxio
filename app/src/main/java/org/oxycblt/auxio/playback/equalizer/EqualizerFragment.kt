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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
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
        buildBandRows()
        setupPresetSpinner()
        binding.eqSwitch.setOnCheckedChangeListener { _, checked -> viewModel.setEnabled(checked) }
        collectState()
    }

    /** Dynamically creates the 10 band rows and adds them to the container. */
    private fun buildBandRows() {
        val d = resources.displayMetrics.density
        val rowLp =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        val pad = (8 * d).toInt()
        val labelW = (56 * d).toInt()
        val valueW = (58 * d).toInt()

        for (i in 0 until 10) {
            val row =
                LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, pad, 0, pad)
                    layoutParams = rowLp
                }
            val label =
                TextView(requireContext()).apply {
                    text = bandLabels[i]
                    textSize = 12f
                    layoutParams =
                        LinearLayout.LayoutParams(labelW, LinearLayout.LayoutParams.WRAP_CONTENT)
                }
            val bar =
                SeekBar(requireContext()).apply {
                    max = 240 // center=120, 1 step=0.1 dB, range -12 to +12 dB
                    progress = 120
                    layoutParams =
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
            val value =
                TextView(requireContext()).apply {
                    text = "0.0"
                    textSize = 12f
                    textAlignment = View.TEXT_ALIGNMENT_TEXT_END
                    layoutParams =
                        LinearLayout.LayoutParams(valueW, LinearLayout.LayoutParams.WRAP_CONTENT)
                }

            bar.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            val db = (progress - 120) / 10f
                            value.text = String.format(Locale.US, "%.1f", db)
                            viewModel.setBand(i, db)
                        }
                    }

                    override fun onStartTrackingTouch(sb: SeekBar?) {}

                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                }
            )

            seekBars.add(bar)
            valueLabels.add(value)
            row.addView(label)
            row.addView(bar)
            row.addView(value)
            binding.eqBandsContainer.addView(row)
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
