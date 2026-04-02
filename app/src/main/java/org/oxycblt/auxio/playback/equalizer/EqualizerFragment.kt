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
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentEqualizerBinding
import org.oxycblt.auxio.ui.ViewBindingFragment

@AndroidEntryPoint
class EqualizerFragment : ViewBindingFragment<FragmentEqualizerBinding>() {

    private val viewModel: EqualizerViewModel by viewModels()
    private val seekBars = arrayOfNulls<SeekBar>(10)
    private var ignoreSpinner = false

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentEqualizerBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentEqualizerBinding, savedInstanceState: Bundle?) {
        ViewCompat.setOnApplyWindowInsetsListener(binding.eqScroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top)
            insets
        }

        buildBandViews(binding)
        setupPresetSpinner(binding)
        setupSwitch(binding)
        setupAutoEqSearch(binding)

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.enabled.collect { onEnabledChanged(binding, it) } }
                launch { viewModel.bands.collect { onBandsChanged(it) } }
                launch { viewModel.activePreset.collect { onPresetChanged(binding, it) } }
                launch { viewModel.searchState.collect { onSearchStateChanged(binding, it) } }
                launch {
                    viewModel.isApplyingProfile.collect { onApplyingProfileChanged(binding, it) }
                }
                launch {
                    combine(viewModel.autoEqProfileName, viewModel.isModifiedFromProfile) {
                            name,
                            modified ->
                            Pair(name, modified)
                        }
                        .collect { (name, modified) ->
                            onProfileLabelChanged(binding, name, modified)
                        }
                }
            }
        }
    }

    override fun onDestroyBinding(binding: FragmentEqualizerBinding) {
        seekBars.fill(null)
    }

    // ---- Build ----

    private fun buildBandViews(binding: FragmentEqualizerBinding) {
        val density = resources.displayMetrics.density
        val trackLenPx = (172 * density + 0.5f).toInt()
        val thumbSizePx = (32 * density + 0.5f).toInt()
        val freqLabels = listOf("31", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

        binding.eqBandsContainer.removeAllViews()
        binding.eqFreqLabels.removeAllViews()
        binding.eqDbLabels.removeAllViews()

        for (i in 0 until 10) {
            val frame =
                FrameLayout(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(0, trackLenPx, 1f)
                }

            val seekBar =
                SeekBar(requireContext()).apply {
                    max = 240
                    progress = 120
                    rotation = -90f
                    layoutParams =
                        FrameLayout.LayoutParams(trackLenPx, thumbSizePx).apply {
                            gravity = Gravity.CENTER
                        }
                    setOnTouchListener { v, _ ->
                        v.parent.requestDisallowInterceptTouchEvent(true)
                        false
                    }
                    setOnSeekBarChangeListener(
                        object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(
                                sb: SeekBar,
                                progress: Int,
                                fromUser: Boolean,
                            ) {
                                if (fromUser) viewModel.setBand(i, (progress - 120) / 10f)
                            }

                            override fun onStartTrackingTouch(sb: SeekBar) {
                                sb.parent.requestDisallowInterceptTouchEvent(true)
                            }

                            override fun onStopTrackingTouch(sb: SeekBar) {}
                        }
                    )
                }

            seekBars[i] = seekBar
            frame.addView(seekBar)
            binding.eqBandsContainer.addView(frame)

            binding.eqFreqLabels.addView(
                TextView(requireContext()).apply {
                    text = freqLabels[i]
                    textSize = 9f
                    gravity = Gravity.CENTER
                    layoutParams =
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
            )
        }
    }

    // ---- Setup ----

    private fun setupPresetSpinner(binding: FragmentEqualizerBinding) {
        val names = EqualizerSettings.PRESET_NAMES + listOf(getString(R.string.lbl_eq_custom))
        val adapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, names).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        binding.eqPresetSpinner.adapter = adapter
        binding.eqPresetSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    if (ignoreSpinner) return
                    val presetIdx =
                        if (position < EqualizerSettings.PRESET_NAMES.size) position
                        else EqualizerSettings.PRESET_CUSTOM
                    if (presetIdx != EqualizerSettings.PRESET_CUSTOM) {
                        viewModel.applyPreset(presetIdx)
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
    }

    private fun setupSwitch(binding: FragmentEqualizerBinding) {
        binding.eqSwitch.setOnCheckedChangeListener { _, checked -> viewModel.setEnabled(checked) }
    }

    private fun setupAutoEqSearch(binding: FragmentEqualizerBinding) {
        // Live search: triggers viewModel.onQueryChanged on every text change.
        // The ViewModel debounces for 300 ms before executing the actual search.
        binding.eqAutoeqSearch.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    viewModel.onQueryChanged(s?.toString() ?: "")
                }

                override fun afterTextChanged(s: Editable?) {}
            }
        )

        // Scroll AutoEQ card into view when search field gets focus
        binding.eqAutoeqSearch.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.eqCardAutoeq.post {
                    binding.eqScroll.smoothScrollTo(0, binding.eqCardAutoeq.top - 16)
                }
            }
        }
    }

    // ---- State handlers ----

    private fun onEnabledChanged(binding: FragmentEqualizerBinding, enabled: Boolean) {
        binding.eqSwitch.isChecked = enabled
        seekBars.forEach { it?.isEnabled = enabled }
        binding.eqPresetSpinner.isEnabled = enabled
        binding.eqAutoeqSearch.isEnabled = enabled
    }

    private fun onBandsChanged(bands: FloatArray) {
        for (i in 0 until 10) {
            val seekBar = seekBars[i] ?: continue
            val progress = (bands[i] * 10 + 120).toInt().coerceIn(0, 240)
            seekBar.post { seekBar.progress = progress }
        }
    }

    private fun onPresetChanged(binding: FragmentEqualizerBinding, preset: Int) {
        ignoreSpinner = true
        val pos =
            if (preset == EqualizerSettings.PRESET_CUSTOM) EqualizerSettings.PRESET_NAMES.size
            else preset
        binding.eqPresetSpinner.post {
            binding.eqPresetSpinner.setSelection(pos)
            binding.eqPresetSpinner.post { ignoreSpinner = false }
        }
    }

    private fun onSearchStateChanged(binding: FragmentEqualizerBinding, state: AutoEqSearchState) {
        when (state) {
            is AutoEqSearchState.Idle -> {
                binding.eqAutoeqProgress.visibility = View.GONE
                binding.eqAutoeqStatus.visibility = View.GONE
                hideResults(binding)
            }
            is AutoEqSearchState.Loading -> {
                binding.eqAutoeqProgress.visibility = View.VISIBLE
                binding.eqAutoeqStatus.visibility = View.GONE
                hideResults(binding)
            }
            is AutoEqSearchState.Results -> {
                binding.eqAutoeqProgress.visibility = View.GONE
                binding.eqAutoeqStatus.visibility = View.GONE
                showResults(binding, state.items)
            }
            is AutoEqSearchState.NoResults -> {
                binding.eqAutoeqProgress.visibility = View.GONE
                binding.eqAutoeqStatus.text = getString(R.string.lbl_autoeq_no_results)
                binding.eqAutoeqStatus.visibility = View.VISIBLE
                hideResults(binding)
            }
            is AutoEqSearchState.Error -> {
                binding.eqAutoeqProgress.visibility = View.GONE
                binding.eqAutoeqStatus.text = getString(R.string.lbl_autoeq_error)
                binding.eqAutoeqStatus.visibility = View.VISIBLE
                hideResults(binding)
            }
        }
    }

    private fun onApplyingProfileChanged(binding: FragmentEqualizerBinding, applying: Boolean) {
        // Show the loading bar while the profile is being downloaded
        if (applying) {
            binding.eqAutoeqProgress.visibility = View.VISIBLE
            binding.eqAutoeqSearch.isEnabled = false
        } else {
            binding.eqAutoeqProgress.visibility = View.GONE
            binding.eqAutoeqSearch.isEnabled = _enabled.value
        }
    }

    // Helper to read the current enabled state without a Flow
    private val _enabled
        get() = viewModel.enabled.value

    private fun onProfileLabelChanged(
        binding: FragmentEqualizerBinding,
        name: String?,
        modified: Boolean,
    ) {
        if (name == null) {
            binding.eqAutoeqProfileLabel.visibility = View.GONE
            return
        }
        binding.eqAutoeqProfileLabel.text =
            if (modified) getString(R.string.lbl_autoeq_modified, name)
            else getString(R.string.lbl_autoeq_profile, name)
        binding.eqAutoeqProfileLabel.visibility = View.VISIBLE
    }

    // ---- Results display ----

    private fun showResults(binding: FragmentEqualizerBinding, results: List<AutoEqResult>) {
        binding.eqAutoeqResults.removeAllViews()

        for ((index, result) in results.withIndex()) {
            val btn =
                Button(requireContext()).apply {
                    text = buildResultLabel(result)
                    isAllCaps = false
                    textSize = 13f
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                    alpha = 0f
                    translationY = (8 * resources.displayMetrics.density)
                    setOnClickListener {
                        viewModel.applyAutoEqProfile(result)
                        binding.eqAutoeqSearch.text?.clear()
                        binding.eqAutoeqSearch.clearFocus()
                        hideKeyboard(binding.eqAutoeqSearch)
                    }
                }

            binding.eqAutoeqResults.addView(btn)
            btn.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(180)
                .setStartDelay((index * 40).toLong())
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        // Fade in the container and scroll it into view
        if (binding.eqAutoeqResults.visibility != View.VISIBLE) {
            binding.eqAutoeqResults.alpha = 0f
            binding.eqAutoeqResults.visibility = View.VISIBLE
            binding.eqAutoeqResults
                .animate()
                .alpha(1f)
                .setDuration(180)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        binding.eqAutoeqResults.postDelayed(
            { if (isAdded) binding.eqScroll.smoothScrollTo(0, binding.eqCardAutoeq.bottom + 32) },
            220,
        )
    }

    private fun hideResults(binding: FragmentEqualizerBinding) {
        val results = binding.eqAutoeqResults
        if (results.visibility == View.GONE) return
        results
            .animate()
            .alpha(0f)
            .setDuration(120)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                results.visibility = View.GONE
                results.removeAllViews()
            }
            .start()
    }

    /** Builds the display text for a search result button. */
    private fun buildResultLabel(result: AutoEqResult): String {
        return if (result.source.isNotBlank()) "${result.name}  —  ${result.source}"
        else result.name
    }

    // ---- Util ----

    private fun hideKeyboard(view: View) {
        requireContext()
            .getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(view.windowToken, 0)
    }
}
