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
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentEqualizerBinding
import org.oxycblt.auxio.ui.ViewBindingFragment

/** Fragment that shows the 10-band equalizer and AutoEQ profile search. */
@AndroidEntryPoint
class EqualizerFragment : ViewBindingFragment<FragmentEqualizerBinding>() {

    private val viewModel: EqualizerViewModel by viewModels()

    private val freqLabels =
        arrayOf("31", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
    private val seekBars = mutableListOf<SeekBar>()
    private val dbTextViews = mutableListOf<TextView>()
    private var ignoreSpinner = false

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentEqualizerBinding.inflate(inflater)

    override fun onBindingCreated(
        binding: FragmentEqualizerBinding,
        savedInstanceState: Bundle?,
    ) {
        // Push content below the status bar / notch
        ViewCompat.setOnApplyWindowInsetsListener(binding.eqScroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top)
            insets
        }

        buildBandViews(binding)
        setupSpinner(binding)
        setupSwitch(binding)
        setupAutoEq(binding)
        observeViewModel(binding)
    }

    // -------------------------------------------------------------------------
    // Band slider construction
    // -------------------------------------------------------------------------

    private fun buildBandViews(binding: FragmentEqualizerBinding) {
        val density = resources.displayMetrics.density
        // trackLen = visual height after -90° rotation = SeekBar layoutWidth
        val trackLen = (190 * density).toInt()
        // thumbW = visual width after rotation = SeekBar layoutHeight
        val thumbW = (32 * density).toInt()

        binding.eqBandsContainer.removeAllViews()
        binding.eqDbLabels.removeAllViews()
        binding.eqFreqLabels.removeAllViews()
        seekBars.clear()
        dbTextViews.clear()

        for (i in 0..9) {
            // dB label above the slider column
            val dbView =
                TextView(requireContext()).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    textSize = 10f
                    textAlignment = View.TEXT_ALIGNMENT_CENTER
                    text = "0,0"
                }
            dbTextViews.add(dbView)
            binding.eqDbLabels.addView(dbView)

            // SeekBar: layoutWidth = trackLen (becomes visual height after -90° rotation)
            val sb =
                SeekBar(requireContext()).apply {
                    layoutParams = FrameLayout.LayoutParams(trackLen, thumbW, Gravity.CENTER)
                    max = 240
                    progress = 120 // 0 dB
                    rotation = -90f
                }
            val bandIdx = i
            sb.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        bar: SeekBar,
                        progress: Int,
                        fromUser: Boolean,
                    ) {
                        if (fromUser) {
                            viewModel.setBand(bandIdx, (progress - 120) / 10f)
                        }
                    }

                    override fun onStartTrackingTouch(bar: SeekBar) {}

                    override fun onStopTrackingTouch(bar: SeekBar) {}
                }
            )
            seekBars.add(sb)

            // FrameLayout column: equal share of horizontal space, height = trackLen
            val frame =
                FrameLayout(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(0, trackLen, 1f)
                    clipChildren = false
                    clipToPadding = false
                }
            frame.addView(sb)
            binding.eqBandsContainer.addView(frame)

            // Frequency label below
            val freqView =
                TextView(requireContext()).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    textSize = 10f
                    textAlignment = View.TEXT_ALIGNMENT_CENTER
                    text = freqLabels[i]
                }
            binding.eqFreqLabels.addView(freqView)
        }
    }

    // -------------------------------------------------------------------------
    // Preset spinner
    // -------------------------------------------------------------------------

    private fun setupSpinner(binding: FragmentEqualizerBinding) {
        val items = EqualizerSettings.PRESET_NAMES + listOf(getString(R.string.lbl_eq_custom))
        val adapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, items).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        binding.eqPresetSpinner.adapter = adapter
        binding.eqPresetSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>,
                    view: View?,
                    pos: Int,
                    id: Long,
                ) {
                    if (!ignoreSpinner && pos < EqualizerSettings.PRESET_NAMES.size) {
                        viewModel.applyPreset(pos)
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>) {}
            }
    }

    // -------------------------------------------------------------------------
    // Enable/disable switch
    // -------------------------------------------------------------------------

    private fun setupSwitch(binding: FragmentEqualizerBinding) {
        binding.eqSwitch.setOnCheckedChangeListener { _, checked ->
            viewModel.setEnabled(checked)
        }
    }

    // -------------------------------------------------------------------------
    // AutoEQ search
    // -------------------------------------------------------------------------

    private fun setupAutoEq(binding: FragmentEqualizerBinding) {
        // Note: ViewBinding generates eqAutoeqSearch from ID eq_autoeq_search
        binding.eqAutoeqSearchBtn.setOnClickListener { triggerSearch(binding) }
        binding.eqAutoeqSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                triggerSearch(binding)
                true
            } else {
                false
            }
        }
    }

    private fun triggerSearch(binding: FragmentEqualizerBinding) {
        val query = binding.eqAutoeqSearch.text?.toString().orEmpty().trim()
        viewModel.searchAutoEq(query)
    }

    // -------------------------------------------------------------------------
    // Observe ViewModel state
    // -------------------------------------------------------------------------

    private fun observeViewModel(binding: FragmentEqualizerBinding) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                // Update sliders and dB labels when band values change
                launch {
                    viewModel.bands.collect { gains ->
                        gains.forEachIndexed { i, gain ->
                            if (i >= seekBars.size) return@forEachIndexed
                            val progress = (gain * 10f + 120f).toInt().coerceIn(0, 240)
                            // post() ensures the view is laid out before setting progress
                            seekBars[i].post { seekBars[i].progress = progress }
                            dbTextViews[i].text = formatDb(gain)
                        }
                    }
                }

                // Enable or disable all controls
                launch {
                    viewModel.enabled.collect { enabled ->
                        if (binding.eqSwitch.isChecked != enabled) {
                            binding.eqSwitch.isChecked = enabled
                        }
                        seekBars.forEach { it.isEnabled = enabled }
                        binding.eqPresetSpinner.isEnabled = enabled
                        binding.eqAutoeqSearch.isEnabled = enabled
                        binding.eqAutoeqSearchBtn.isEnabled = enabled
                    }
                }

                // Sync spinner with active preset
                launch {
                    viewModel.activePreset.collect { preset ->
                        ignoreSpinner = true
                        val pos =
                            if (preset == EqualizerSettings.PRESET_CUSTOM) {
                                EqualizerSettings.PRESET_NAMES.size
                            } else {
                                preset.coerceIn(0, EqualizerSettings.PRESET_NAMES.size - 1)
                            }
                        binding.eqPresetSpinner.post {
                            binding.eqPresetSpinner.setSelection(pos)
                            binding.eqPresetSpinner.post { ignoreSpinner = false }
                        }
                    }
                }

                // Show active AutoEQ profile name
                launch {
                    combine(viewModel.autoEqProfileName, viewModel.isModifiedFromProfile) {
                            name,
                            modified ->
                        Pair(name, modified)
                    }.collect { (name, modified) ->
                        if (name != null) {
                            binding.eqAutoeqProfileLabel.visibility = View.VISIBLE
                            binding.eqAutoeqProfileLabel.text =
                                if (modified) {
                                    "${getString(R.string.lbl_autoeq_profile)}: $name (${getString(R.string.lbl_autoeq_modified)})"
                                } else {
                                    "${getString(R.string.lbl_autoeq_profile)}: $name"
                                }
                        } else {
                            binding.eqAutoeqProfileLabel.visibility = View.GONE
                        }
                    }
                }

                // Show AutoEQ search results
                launch {
                    combine(viewModel.searchResults, viewModel.isSearching) {
                            results,
                            searching ->
                        Pair(results, searching)
                    }.collect { (results, searching) ->
                        binding.eqAutoeqSearchBtn.isEnabled = !searching
                        binding.eqAutoeqResults.removeAllViews()

                        if (results.isNotEmpty()) {
                            binding.eqAutoeqResults.visibility = View.VISIBLE
                            for (result in results) {
                                val btn =
                                    MaterialButton(requireContext()).apply {
                                        layoutParams =
                                            LinearLayout.LayoutParams(
                                                ViewGroup.LayoutParams.MATCH_PARENT,
                                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                            )
                                        text = "${result.name} — ${result.source}"
                                        setOnClickListener {
                                            viewModel.applyAutoEqProfile(result)
                                            binding.eqAutoeqSearch.setText("")
                                            binding.eqAutoeqResults.removeAllViews()
                                            binding.eqAutoeqResults.visibility = View.GONE
                                        }
                                    }
                                binding.eqAutoeqResults.addView(btn)
                            }
                        } else {
                            binding.eqAutoeqResults.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

    // Formats gain as "5,0" or "-1,5" (comma as decimal separator)
    private fun formatDb(gain: Float): String =
        String.format("%.1f", gain).replace('.', ',')
}
