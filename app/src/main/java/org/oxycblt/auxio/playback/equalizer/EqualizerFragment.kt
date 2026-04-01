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
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
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
                launch { viewModel.searchResults.collect { onSearchResultsChanged(binding, it) } }
                launch {
                    viewModel.isSearching.collect { binding.eqAutoeqSearchBtn.isEnabled = !it }
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
                    // Fix: NestedScrollView intercepts the vertical drag produced by the
                    // rotated SeekBar. Disallow parent interception on every touch event.
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
                                if (fromUser) {
                                    viewModel.setBand(i, (progress - 120) / 10f)
                                }
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
        // When the search field gains focus, scroll to bring the AutoEQ card to the top.
        // This creates the "search bar moves up" effect without custom view translation.
        binding.eqAutoeqSearch.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.eqCardAutoeq.post {
                    binding.eqScroll.smoothScrollTo(0, binding.eqCardAutoeq.top - 16)
                }
            }
        }

        binding.eqAutoeqSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch(binding)
                true
            } else {
                false
            }
        }

        binding.eqAutoeqSearchBtn.setOnClickListener { doSearch(binding) }
    }

    private fun doSearch(binding: FragmentEqualizerBinding) {
        val query = binding.eqAutoeqSearch.text?.toString()?.trim() ?: return
        viewModel.searchAutoEq(query)
        hideKeyboard(binding.eqAutoeqSearch)
    }

    // ---- State handlers ----

    private fun onEnabledChanged(binding: FragmentEqualizerBinding, enabled: Boolean) {
        binding.eqSwitch.isChecked = enabled
        seekBars.forEach { it?.isEnabled = enabled }
        binding.eqPresetSpinner.isEnabled = enabled
        binding.eqAutoeqSearch.isEnabled = enabled
        binding.eqAutoeqSearchBtn.isEnabled = enabled
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

    private fun onSearchResultsChanged(
        binding: FragmentEqualizerBinding,
        results: List<AutoEqResult>,
    ) {
        binding.eqAutoeqResults.removeAllViews()
        if (results.isEmpty()) {
            animateResultsOut(binding)
            return
        }

        // Add one button per result with staggered slide-in animation
        for ((index, result) in results.withIndex()) {
            val btn =
                Button(requireContext()).apply {
                    text = "${result.name}  —  ${result.source}"
                    isAllCaps = false
                    textSize = 13f
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                    alpha = 0f
                    translationY = (10 * resources.displayMetrics.density)
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
                .setDuration(220)
                .setStartDelay((index * 55).toLong())
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        animateResultsIn(binding)
    }

    private fun onProfileLabelChanged(
        binding: FragmentEqualizerBinding,
        name: String?,
        modified: Boolean,
    ) {
        if (name == null) {
            binding.eqAutoeqProfileLabel.visibility = View.GONE
            return
        }
        val suffix = if (modified) " (${getString(R.string.lbl_autoeq_modified)})" else ""
        binding.eqAutoeqProfileLabel.text =
            "${getString(R.string.lbl_autoeq_profile)}: $name$suffix"
        binding.eqAutoeqProfileLabel.visibility = View.VISIBLE
    }

    // ---- Animations ----

    private fun animateResultsIn(binding: FragmentEqualizerBinding) {
        val results = binding.eqAutoeqResults
        if (results.visibility == View.VISIBLE) return
        results.alpha = 0f
        results.visibility = View.VISIBLE
        results
            .animate()
            .alpha(1f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .start()
        // After animation, scroll down to show all results
        results.postDelayed(
            {
                if (isAdded) {
                    binding.eqScroll.smoothScrollTo(0, binding.eqCardAutoeq.bottom + 32)
                }
            },
            260,
        )
    }

    private fun animateResultsOut(binding: FragmentEqualizerBinding) {
        val results = binding.eqAutoeqResults
        if (results.visibility != View.VISIBLE) return
        results
            .animate()
            .alpha(0f)
            .setDuration(150)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { results.visibility = View.GONE }
            .start()
    }

    // ---- Util ----

    private fun hideKeyboard(view: View) {
        requireContext()
            .getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(view.windowToken, 0)
    }
}
