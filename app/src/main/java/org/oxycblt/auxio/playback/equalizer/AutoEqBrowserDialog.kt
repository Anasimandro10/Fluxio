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
import android.view.LayoutInflater
import android.view.inputmethod.InputMethodManager
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogAutoeqBrowserBinding
import org.oxycblt.auxio.ui.ViewBindingBottomSheetDialogFragment

/**
 * Bottom sheet that displays the full AutoEQ headphone profile index and filters it locally as the
 * user types. Profiles are downloaded from the API once per process lifetime and cached in memory.
 * Extends [ViewBindingBottomSheetDialogFragment] to use the project's backport sheet.
 */
@AndroidEntryPoint
class AutoEqBrowserDialog : ViewBindingBottomSheetDialogFragment<DialogAutoeqBrowserBinding>() {

    // ViewModel owned by AudioTabFragment — shared so applyAutoEqProfile reaches the EQ.
    private val viewModel: EqualizerViewModel by viewModels({ requireParentFragment() })

    private lateinit var resultsAdapter: AutoEqResultAdapter
    private var currentQuery = ""

    // ---- ViewBindingBottomSheetDialogFragment ----

    override fun onCreateBinding(inflater: LayoutInflater) =
        DialogAutoeqBrowserBinding.inflate(inflater)

    override fun onBindingCreated(
        binding: DialogAutoeqBrowserBinding,
        savedInstanceState: Bundle?,
    ) {
        resultsAdapter = AutoEqResultAdapter { result ->
            viewModel.applyAutoEqProfile(result)
            hideKeyboard()
            dismiss()
        }

        binding.autoeqBrowserRetryButton.setOnClickListener { viewModel.loadAllProfiles() }

        binding.autoeqBrowserResults.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = resultsAdapter
            setHasFixedSize(false)
        }

        setupSearch(binding)
        collectState()
        // Start the index download (no-op when already loading or ready).
        viewModel.loadAllProfiles()
    }

    override fun onDestroyBinding(binding: DialogAutoeqBrowserBinding) {
        binding.autoeqBrowserResults.adapter = null
    }

    // ---- Search setup ----

    private fun setupSearch(binding: DialogAutoeqBrowserBinding) {
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
                    applyFilter()
                }

                override fun afterTextChanged(s: Editable?) {}
            }
        )
    }

    // ---- State collection ----

    private fun collectState() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.allProfilesState.collect { onAllProfilesStateChanged(it) } }
                launch { viewModel.isApplyingProfile.collect { onApplyingChanged(it) } }
                launch { viewModel.recentProfiles.collect { applyFilter() } }
            }
        }
    }

    // ---- State handlers ----

    private fun onAllProfilesStateChanged(state: AllProfilesState) {
        val binding = requireBinding()
        when (state) {
            is AllProfilesState.Idle,
            is AllProfilesState.Loading -> {
                binding.autoeqBrowserProgress.visibility = android.view.View.VISIBLE
                binding.autoeqBrowserCount.visibility = android.view.View.GONE
                binding.autoeqBrowserErrorLayout.visibility = android.view.View.GONE
                binding.autoeqBrowserResults.visibility = android.view.View.GONE
                binding.autoeqBrowserRecentLabel.visibility = android.view.View.GONE
                resultsAdapter.submitList(null)
            }
            is AllProfilesState.Ready -> {
                binding.autoeqBrowserProgress.visibility = android.view.View.GONE
                binding.autoeqBrowserCount.text =
                    getString(R.string.lbl_autoeq_count, state.profiles.size)
                binding.autoeqBrowserCount.visibility = android.view.View.VISIBLE
                binding.autoeqBrowserErrorLayout.visibility = android.view.View.GONE
                // Apply any query typed while the index was loading.
                applyFilter()
            }
            is AllProfilesState.Error -> {
                binding.autoeqBrowserProgress.visibility = android.view.View.GONE
                binding.autoeqBrowserCount.visibility = android.view.View.GONE
                binding.autoeqBrowserStatus.text = getString(R.string.lbl_autoeq_error)
                binding.autoeqBrowserErrorLayout.visibility = android.view.View.VISIBLE
                binding.autoeqBrowserRetryButton.visibility = android.view.View.VISIBLE
                binding.autoeqBrowserResults.visibility = android.view.View.GONE
                binding.autoeqBrowserRecentLabel.visibility = android.view.View.GONE
                resultsAdapter.submitList(null)
            }
        }
    }

    private fun onApplyingChanged(applying: Boolean) {
        val binding = requireBinding()
        binding.autoeqBrowserSearch.isEnabled = !applying
        if (applying) {
            binding.autoeqBrowserProgress.visibility = android.view.View.VISIBLE
        }
    }

    // ---- Filtering ----

    /** Filters the in-memory profile list and updates the RecyclerView. */
    private fun applyFilter() {
        val binding = requireBinding()
        val state = viewModel.allProfilesState.value
        if (state !is AllProfilesState.Ready) return

        if (currentQuery.isBlank()) {
            val recents = viewModel.recentProfiles.value
            if (recents.isNotEmpty()) {
                binding.autoeqBrowserRecentLabel.visibility = android.view.View.VISIBLE
                binding.autoeqBrowserResults.visibility = android.view.View.VISIBLE
                binding.autoeqBrowserErrorLayout.visibility = android.view.View.GONE
                resultsAdapter.submitList(recents)
            } else {
                binding.autoeqBrowserRecentLabel.visibility = android.view.View.GONE
                binding.autoeqBrowserResults.visibility = android.view.View.VISIBLE
                binding.autoeqBrowserErrorLayout.visibility = android.view.View.GONE
                resultsAdapter.submitList(viewModel.filterProfiles(""))
            }
            return
        }

        binding.autoeqBrowserRecentLabel.visibility = android.view.View.GONE
        val results = viewModel.filterProfiles(currentQuery)

        if (results.isEmpty()) {
            binding.autoeqBrowserStatus.text = getString(R.string.lbl_autoeq_no_results)
            binding.autoeqBrowserErrorLayout.visibility = android.view.View.VISIBLE
            binding.autoeqBrowserRetryButton.visibility = android.view.View.GONE
            binding.autoeqBrowserResults.visibility = android.view.View.GONE
            resultsAdapter.submitList(null)
        } else {
            binding.autoeqBrowserErrorLayout.visibility = android.view.View.GONE
            binding.autoeqBrowserResults.visibility = android.view.View.VISIBLE
            resultsAdapter.submitList(results)
        }
    }

    // ---- Keyboard ----

    private fun hideKeyboard() {
        val binding = binding ?: return
        val imm = requireContext().getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(binding.autoeqBrowserSearch.windowToken, 0)
    }

    companion object {
        const val TAG = "AutoEqBrowserDialog"
    }
}
