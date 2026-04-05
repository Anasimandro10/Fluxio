/*
 * Copyright (c) 2026 Fluxio Project
 * AutoEqBrowserDialog.kt is part of Fluxio.
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
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogAutoeqBrowserBinding

/**
 * Bottom sheet that displays the full AutoEQ profile index and filters it locally as the user
 * types. Profiles are loaded from the API once per session and cached in memory.
 */
@AndroidEntryPoint
class AutoEqBrowserDialog : BottomSheetDialogFragment() {

    private var _binding: DialogAutoeqBrowserBinding? = null
    private val binding get() = _binding!!

    // Retrieves the EqualizerViewModel instance owned by EqualizerFragment.
    private val viewModel: EqualizerViewModel by viewModels({ requireParentFragment() })

    /** Current text in the search field — used to re-filter after the index loads. */
    private var currentQuery = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DialogAutoeqBrowserBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupSearch()
        collectState()
        // Trigger the index download (no-op if already loaded or loading).
        viewModel.loadAllProfiles()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ---- Setup ----

    private fun setupSearch() {
        binding.autoeqBrowserSearch.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    currentQuery = s?.toString() ?: ""
                    // Filter is instant because it works on the in-memory list.
                    showFiltered()
                }

                override fun afterTextChanged(s: Editable?) {}
            }
        )
    }

    private fun collectState() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.allProfilesState.collect { onAllProfilesStateChanged(it) } }
                launch { viewModel.isApplyingProfile.collect { onApplyingChanged(it) } }
            }
        }
    }

    // ---- State handlers ----

    private fun onAllProfilesStateChanged(state: AllProfilesState) {
        when (state) {
            is AllProfilesState.Idle,
            is AllProfilesState.Loading -> {
                binding.autoeqBrowserProgress.visibility = View.VISIBLE
                binding.autoeqBrowserCount.visibility = View.GONE
                binding.autoeqBrowserStatus.visibility = View.GONE
                clearResults()
            }
            is AllProfilesState.Ready -> {
                binding.autoeqBrowserProgress.visibility = View.GONE
                binding.autoeqBrowserCount.text =
                    getString(R.string.lbl_autoeq_count, state.profiles.size)
                binding.autoeqBrowserCount.visibility = View.VISIBLE
                binding.autoeqBrowserStatus.visibility = View.GONE
                // Re-apply any query that was typed while the index was loading.
                showFiltered()
            }
            is AllProfilesState.Error -> {
                binding.autoeqBrowserProgress.visibility = View.GONE
                binding.autoeqBrowserCount.visibility = View.GONE
                binding.autoeqBrowserStatus.text = getString(R.string.lbl_autoeq_error)
                binding.autoeqBrowserStatus.visibility = View.VISIBLE
                clearResults()
            }
        }
    }

    private fun onApplyingChanged(applying: Boolean) {
        binding.autoeqBrowserSearch.isEnabled = !applying
        binding.autoeqBrowserProgress.visibility =
            if (applying) View.VISIBLE else View.GONE
    }

    // ---- Filtering ----

    /**
     * Asks the ViewModel to filter the in-memory list by [currentQuery] and renders the result.
     * Does nothing if the index is not yet ready.
     */
    private fun showFiltered() {
        val state = viewModel.allProfilesState.value
        if (state !is AllProfilesState.Ready) return

        val results = viewModel.filterProfiles(currentQuery)
        if (results.isEmpty() && currentQuery.isNotBlank()) {
            clearResults()
            binding.autoeqBrowserStatus.text = getString(R.string.lbl_autoeq_no_results)
            binding.autoeqBrowserStatus.visibility = View.VISIBLE
        } else {
            binding.autoeqBrowserStatus.visibility = View.GONE
            showResults(results)
        }
    }

    // ---- Results rendering ----

    private fun showResults(results: List<AutoEqResult>) {
        binding.autoeqBrowserResults.removeAllViews()

        for ((index, result) in results.withIndex()) {
            val btn =
                Button(requireContext()).apply {
                    text = buildLabel(result)
                    isAllCaps = false
                    textSize = 14f
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                    alpha = 0f
                    translationY = 8f * resources.displayMetrics.density
                    setOnClickListener {
                        viewModel.applyAutoEqProfile(result)
                        hideKeyboard()
                        dismiss()
                    }
                }

            binding.autoeqBrowserResults.addView(btn)
            btn.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(180)
                .setStartDelay((index * 30).toLong())
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        binding.autoeqBrowserResults.visibility = View.VISIBLE
    }

    private fun clearResults() {
        binding.autoeqBrowserResults.removeAllViews()
        binding.autoeqBrowserResults.visibility = View.GONE
    }

    private fun buildLabel(result: AutoEqResult): String =
        if (result.source.isNotBlank()) "${result.name}  —  ${result.source}" else result.name

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(binding.autoeqBrowserSearch.windowToken, 0)
    }

    companion object {
        const val TAG = "AutoEqBrowserDialog"
    }
}
