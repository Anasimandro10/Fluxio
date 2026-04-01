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
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentEqualizerBinding
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collectImmediately

/** Fragment for the 10-band parametric equalizer with AutoEQ integration. */
@AndroidEntryPoint
class EqualizerFragment : ViewBindingFragment<FragmentEqualizerBinding>() {

    private val viewModel: EqualizerViewModel by viewModels()

    private val seekBars = mutableListOf<SeekBar>()
    private val bandValueLabels = mutableListOf<TextView>()
    private lateinit var autoEqAdapter: AutoEqResultAdapter

    private var searchJob: Job? = null
    private var ignoreSpinner = false

    private val bandFrequencies = listOf("31", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

    override fun onCreateBinding(inflater: LayoutInflater): FragmentEqualizerBinding =
        FragmentEqualizerBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentEqualizerBinding, savedInstanceState: Bundle?) {
        buildBandRows(binding)
        setupPresetButton(binding)
        setupSwitch(binding)
        setupAutoEqSearch(binding)
        setupObservers(binding)
    }

    // ── Band rows ──────────────────────────────────────────────────────────────

    private fun buildBandRows(binding: FragmentEqualizerBinding) {
        val container = binding.eqBandsContainer
        container.removeAllViews()
        seekBars.clear()
        bandValueLabels.clear()

        val inflater = LayoutInflater.from(requireContext())
        repeat(10) { index ->
            val col = inflater.inflate(R.layout.item_eq_band_column, container, false)

            val valueLabel = col.findViewById<TextView>(R.id.eq_band_value)
            val seekBar = col.findViewById<SeekBar>(R.id.eq_band_seekbar)
            val freqLabel = col.findViewById<TextView>(R.id.eq_band_freq)

            freqLabel.text = bandFrequencies[index]
            seekBar.max = 240
            seekBar.progress = 120

            seekBar.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                        val db = (progress - 120) / 10f
                        valueLabel.text = String.format("%.1f", db)
                        if (fromUser) viewModel.setBand(index, db)
                    }

                    override fun onStartTrackingTouch(sb: SeekBar) {}

                    override fun onStopTrackingTouch(sb: SeekBar) {}
                })

            seekBars.add(seekBar)
            bandValueLabels.add(valueLabel)
            container.addView(col)
        }
    }

    // ── Preset button / dialog ────────────────────────────────────────────────

    private fun setupPresetButton(binding: FragmentEqualizerBinding) {
        val presets =
            EqualizerSettings.PRESET_NAMES.toMutableList().apply { add("Custom") }.toTypedArray()

        binding.eqPresetButton.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Preset")
                .setItems(presets) { _, which ->
                    if (which < EqualizerSettings.PRESET_NAMES.size) {
                        viewModel.applyPreset(which)
                    }
                }
                .show()
        }
    }

    // ── Switch ────────────────────────────────────────────────────────────────

    private fun setupSwitch(binding: FragmentEqualizerBinding) {
        binding.eqSwitch.setOnCheckedChangeListener { _, checked ->
            viewModel.setEnabled(checked)
        }
    }

    // ── AutoEQ search ─────────────────────────────────────────────────────────

    private fun setupAutoEqSearch(binding: FragmentEqualizerBinding) {
        autoEqAdapter =
            AutoEqResultAdapter { result ->
                viewModel.applyAutoEqProfile(result)
                binding.autoeqSearchInput.text?.clear()
                binding.autoeqResults.isVisible = false
                binding.autoeqNoResults.isVisible = false
            }

        binding.autoeqResults.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = autoEqAdapter
            isNestedScrollingEnabled = false
        }

        // Live search as the user types — debounced 350ms
        binding.autoeqSearchInput.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                override fun afterTextChanged(s: Editable?) {
                    val query = s?.toString()?.trim() ?: ""
                    searchJob?.cancel()
                    if (query.length < 2) {
                        binding.autoeqResults.isVisible = false
                        binding.autoeqNoResults.isVisible = false
                        return
                    }
                    searchJob =
                        viewLifecycleOwner.lifecycleScope.launch {
                            delay(350)
                            viewModel.searchAutoEq(query)
                        }
                }
            })

        // Also search on keyboard "Search" action
        binding.autoeqSearchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = binding.autoeqSearchInput.text?.toString()?.trim() ?: ""
                if (query.length >= 2) viewModel.searchAutoEq(query)
                true
            } else {
                false
            }
        }
    }

    // ── Observers ─────────────────────────────────────────────────────────────

    private fun setupObservers(binding: FragmentEqualizerBinding) {
        collectImmediately(viewModel.enabled) { enabled ->
            if (binding.eqSwitch.isChecked != enabled) {
                binding.eqSwitch.isChecked = enabled
            }
            binding.eqStatusText.text = if (enabled) "On" else "Off"
            val alpha = if (enabled) 1f else 0.45f
            binding.eqBandsCard.animate().alpha(alpha).setDuration(180).start()
        }

        collectImmediately(viewModel.bands) { bands ->
            bands.forEachIndexed { index, gainDb ->
                val progress = (gainDb * 10 + 120).toInt().coerceIn(0, 240)
                if (seekBars.size > index && seekBars[index].progress != progress) {
                    seekBars[index].progress = progress
                }
            }
        }

        collectImmediately(viewModel.activePreset) { preset ->
            val name =
                if (preset == EqualizerSettings.PRESET_CUSTOM) "Custom"
                else EqualizerSettings.PRESET_NAMES.getOrElse(preset) { "Custom" }
            binding.eqPresetButton.text = name
        }

        collectImmediately(viewModel.isSearching) { searching ->
            binding.autoeqProgress.isVisible = searching
        }

        collectImmediately(viewModel.searchResults) { results ->
            val hasQuery = (binding.autoeqSearchInput.text?.length ?: 0) >= 2
            if (!hasQuery) return@collectImmediately
            autoEqAdapter.submitList(results)
            binding.autoeqResults.isVisible = results.isNotEmpty()
            binding.autoeqNoResults.isVisible = results.isEmpty() && !viewModel.isSearching.value
        }

        collectImmediately(viewModel.autoEqProfileName) { name ->
            binding.autoeqActiveChip.isVisible = !name.isNullOrBlank()
            if (!name.isNullOrBlank()) {
                binding.autoeqActiveChip.text = name
            }
        }
    }
}

// ── AutoEQ results adapter ─────────────────────────────────────────────────

private class AutoEqResultAdapter(private val onClick: (AutoEqResult) -> Unit) :
    RecyclerView.Adapter<AutoEqResultAdapter.ViewHolder>() {

    private val items = mutableListOf<AutoEqResult>()

    fun submitList(newItems: List<AutoEqResult>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_autoeq_result, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val nameView: TextView = view.findViewById(R.id.autoeq_result_name)
        private val sourceView: TextView = view.findViewById(R.id.autoeq_result_source)

        fun bind(result: AutoEqResult) {
            nameView.text = result.name
            sourceView.text = result.source
            itemView.setOnClickListener { onClick(result) }
        }
    }
}
