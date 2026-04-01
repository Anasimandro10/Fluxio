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
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentEqualizerBinding
import org.oxycblt.auxio.ui.ViewBindingFragment

@AndroidEntryPoint
class EqualizerFragment : ViewBindingFragment<FragmentEqualizerBinding>() {

    private val viewModel: EqualizerViewModel by viewModels()
    private var ignoreSpinner = false

    override fun onCreateBinding(inflater: LayoutInflater): FragmentEqualizerBinding =
        FragmentEqualizerBinding.inflate(inflater)

    override fun onBindingCreated(
        binding: FragmentEqualizerBinding,
        savedInstanceState: Bundle?,
    ) {
        binding.eqToolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        binding.eqSwitch.setOnCheckedChangeListener { _, checked -> viewModel.setEnabled(checked) }

        val presetNames =
            EqualizerSettings.PRESET_NAMES.toMutableList() +
                listOf(getString(R.string.lbl_eq_custom))
        val spinnerAdapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, presetNames)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.eqPresetSpinner.adapter = spinnerAdapter
        binding.eqPresetSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    if (ignoreSpinner) return
                    if (position < EqualizerSettings.PRESET_NAMES.size) {
                        viewModel.applyPreset(position)
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>) {}
            }

        buildBandRows(binding)

        binding.eqAutoEqSearchBtn.setOnClickListener {
            val query = binding.eqAutoEqSearch.text?.toString().orEmpty()
            viewModel.searchAutoEq(query)
        }

        binding.eqAutoEqSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = binding.eqAutoEqSearch.text?.toString().orEmpty()
                viewModel.searchAutoEq(query)
                true
            } else {
                false
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.bands.collect { bands -> updateBandViews(binding, bands) } }
                launch {
                    viewModel.enabled.collect { enabled ->
                        binding.eqSwitch.isChecked = enabled
                        binding.eqPresetSpinner.isEnabled = enabled
                        binding.eqAutoEqSearch.isEnabled = enabled
                        binding.eqAutoEqSearchBtn.isEnabled = enabled
                        for (i in 0 until binding.eqBandsContainer.childCount) {
                            val col =
                                binding.eqBandsContainer.getChildAt(i) as? LinearLayout ?: continue
                            (col.getChildAt(1) as? SeekBar)?.isEnabled = enabled
                        }
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
                launch {
                    combine(viewModel.autoEqProfileName, viewModel.isModifiedFromProfile) {
                            name,
                            modified ->
                            name to modified
                        }
                        .collect { (name, modified) ->
                            updateProfileLabel(binding, name, modified)
                        }
                }
                launch {
                    combine(viewModel.searchResults, viewModel.isSearching) { results, searching ->
                            results to searching
                        }
                        .collect { (results, searching) ->
                            binding.eqAutoEqSearchBtn.isEnabled = !searching
                            buildAutoEqResults(binding, results)
                        }
                }
            }
        }
    }

    private fun buildBandRows(binding: FragmentEqualizerBinding) {
        val freqs = listOf("31", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
        binding.eqBandsContainer.removeAllViews()
        val seekBarWidthPx = (140 * resources.displayMetrics.density).toInt()

        freqs.forEachIndexed { index, freq ->
            val col =
                LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams =
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                    gravity = Gravity.CENTER_HORIZONTAL
                }

            val dbLabel =
                TextView(requireContext()).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                    textSize = 9f
                    gravity = Gravity.CENTER
                    text = "0.0"
                }

            val seekBar =
                SeekBar(requireContext()).apply {
                    max = 240
                    progress = 120
                    rotation = -90f
                    layoutParams =
                        LinearLayout.LayoutParams(seekBarWidthPx, seekBarWidthPx / 4)
                    setOnSeekBarChangeListener(
                        object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(
                                sb: SeekBar,
                                progress: Int,
                                fromUser: Boolean,
                            ) {
                                if (fromUser) {
                                    val db = (progress - 120) / 10f
                                    dbLabel.text = String.format("%.1f", db)
                                    viewModel.setBand(index, db)
                                }
                            }

                            override fun onStartTrackingTouch(sb: SeekBar) {}

                            override fun onStopTrackingTouch(sb: SeekBar) {}
                        }
                    )
                }

            val freqLabel =
                TextView(requireContext()).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                    textSize = 9f
                    gravity = Gravity.CENTER
                    text = freq
                }

            col.addView(dbLabel)
            col.addView(seekBar)
            col.addView(freqLabel)
            binding.eqBandsContainer.addView(col)
        }
    }

    private fun updateBandViews(binding: FragmentEqualizerBinding, bands: FloatArray) {
        for (i in 0 until binding.eqBandsContainer.childCount) {
            val col = binding.eqBandsContainer.getChildAt(i) as? LinearLayout ?: continue
            val dbLabel = col.getChildAt(0) as? TextView ?: continue
            val seekBar = col.getChildAt(1) as? SeekBar ?: continue
            val progress = (bands[i] * 10 + 120).toInt().coerceIn(0, 240)
            seekBar.progress = progress
            dbLabel.text = String.format("%.1f", bands[i])
        }
    }

    private fun updateProfileLabel(
        binding: FragmentEqualizerBinding,
        name: String?,
        modified: Boolean,
    ) {
        if (name == null) {
            binding.eqAutoEqProfileLabel.visibility = View.GONE
        } else {
            binding.eqAutoEqProfileLabel.visibility = View.VISIBLE
            binding.eqAutoEqProfileLabel.text =
                if (modified) {
                    getString(R.string.lbl_autoeq_modified, name)
                } else {
                    getString(R.string.lbl_autoeq_profile, name)
                }
        }
    }

    private fun buildAutoEqResults(
        binding: FragmentEqualizerBinding,
        results: List<AutoEqResult>,
    ) {
        binding.eqAutoEqResults.removeAllViews()
        results.forEach { result ->
            val btn =
                Button(requireContext()).apply {
                    text = buildString {
                        append(result.name)
                        append(" \u2014 ")
                        append(result.source)
                    }
                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                    setOnClickListener {
                        viewModel.applyAutoEqProfile(result)
                        binding.eqAutoEqSearch.setText("")
                    }
                }
            binding.eqAutoEqResults.addView(btn)
        }
    }

    override fun onDestroyBinding(binding: FragmentEqualizerBinding) {}
}
