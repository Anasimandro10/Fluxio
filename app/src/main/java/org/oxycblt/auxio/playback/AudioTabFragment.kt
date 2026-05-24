/*
 * Copyright (c) 2026 Fluxio Project
 * AudioTabFragment.kt is part of Fluxio.
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
package org.oxycblt.auxio.playback

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentAudioTabBinding
import org.oxycblt.auxio.playback.crossfade.CrossfadeSettings
import org.oxycblt.auxio.playback.equalizer.AutoEqBrowserDialog
import org.oxycblt.auxio.playback.equalizer.EqualizerSettings
import org.oxycblt.auxio.playback.equalizer.EqualizerViewModel
import org.oxycblt.auxio.playback.equalizer.ListeningMode
import org.oxycblt.auxio.playback.equalizer.ListeningModeManager
import org.oxycblt.auxio.playback.speed.PlaybackSpeedSettings
import org.oxycblt.auxio.playback.stereowidening.StereoWideningSettings
import org.oxycblt.auxio.settings.categories.DeviceProfileDialog
import org.oxycblt.auxio.ui.ViewBindingFragment

/**
 * AUDIO tab inside the player panel.
 *
 * Design spec (FLUXIO_CONTEXTO.md §Player — AUDIO tab):
 *  - Cards: bg surface r:18dp. State right 15sp text2. Title left 17sp text1.
 *  - One card open at a time (accordion). Tap header to expand/collapse.
 *  - Sliders: pure color (colorPrimary, will be wired to ambient at step 31).
 *  - EQ: 10 vertical sliders 4dp wide, freq labels 10sp text2, preset spinner.
 *  - Speed: slider + chips [0.5×][0.75×][1×][1.5×][2×]. Active chip: pure color bg.
 */
@AndroidEntryPoint
class AudioTabFragment : ViewBindingFragment<FragmentAudioTabBinding>() {

    private val viewModel: EqualizerViewModel by viewModels()
    private val playbackModel: PlaybackViewModel by androidx.fragment.app.activityViewModels()

    @Inject lateinit var crossfadeSettings: CrossfadeSettings
    @Inject lateinit var stereoSettings: StereoWideningSettings
    @Inject lateinit var speedSettings: PlaybackSpeedSettings

    private val seekBars = arrayOfNulls<SeekBar>(10)
    private var ignoreSpinner = false
    private var ignoreCrossfadeSlider = false
    private var ignoreStereoSlider = false
    private var ignoreSpeedSlider = false

    // Accordion: tracks which content View is currently expanded (null = all collapsed).
    private var expandedContent: View? = null

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentAudioTabBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentAudioTabBinding, savedInstanceState: Bundle?) {
        ViewCompat.setOnApplyWindowInsetsListener(binding.eqScroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top)
            insets
        }

        buildBandViews(binding)
        setupPresetSpinner(binding)
        setupSwitch(binding)
        setupAutoEqBrowse(binding)
        setupDeviceProfilesButton(binding)
        setupListeningModes(binding)
        setupCrossfadeCard(binding)
        setupStereoCard(binding)
        setupSpeedCard(binding)
        setupTimerCard(binding)

        // Accordion: EQ is expanded by default.
        setupAccordion(binding)

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.refreshFromSettings()

                launch { viewModel.enabled.collect { onEnabledChanged(binding, it) } }
                launch { viewModel.bands.collect { onBandsChanged(it) } }
                launch { viewModel.activePreset.collect { onPresetChanged(binding, it) } }
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
                launch { viewModel.listeningModes.collect { onListeningModesChanged(binding, it) } }
            }
        }
    }

    override fun onDestroyBinding(binding: FragmentAudioTabBinding) {
        seekBars.fill(null)
    }

    // -------------------------------------------------------------------------
    // Accordion logic (spec: "One open at a time")
    // -------------------------------------------------------------------------

    private fun setupAccordion(binding: FragmentAudioTabBinding) {
        // Map each header to its collapsible content.
        val pairs =
            listOf(
                binding.cardEqHeader to binding.cardEqContent,
                binding.cardCrossfadeHeader to binding.cardCrossfadeContent,
                binding.cardStereoHeader to binding.cardStereoContent,
                binding.cardSpeedHeader to binding.cardSpeedContent,
                binding.cardTimerHeader to binding.cardTimerContent,
                binding.cardModesHeader to binding.cardModesContent,
            )

        // EQ card starts expanded (content is already visible="visible" in XML).
        expandedContent = binding.cardEqContent

        pairs.forEach { (header, content) ->
            header.setOnClickListener {
                if (content.isVisible) {
                    // Tapping the already-open card collapses it.
                    toggleContent(binding, content, expand = false)
                    expandedContent = null
                } else {
                    // Collapse whatever is open, then open this card.
                    expandedContent?.let { current ->
                        toggleContent(binding, current, expand = false)
                    }
                    toggleContent(binding, content, expand = true)
                    expandedContent = content
                }
            }
        }
    }

    /**
     * Animate expand/collapse of a card's content view using [TransitionManager].
     * AutoTransition handles both the fade and the bounds change so the card
     * smoothly grows/shrinks without clipping neighbouring cards.
     */
    private fun toggleContent(binding: FragmentAudioTabBinding, content: View, expand: Boolean) {
        TransitionManager.beginDelayedTransition(
            binding.audioAccordionRoot,
            AutoTransition().apply { duration = 220 },
        )
        content.isVisible = expand
    }

    // -------------------------------------------------------------------------
    // EQ band views
    // -------------------------------------------------------------------------

    private fun buildBandViews(binding: FragmentAudioTabBinding) {
        val density = resources.displayMetrics.density
        val trackLenPx = (172 * density + 0.5f).toInt()
        val thumbSizePx = (32 * density + 0.5f).toInt()
        // Spec: freq labels 10sp text2
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
                    // Spec: sliders pure color (colorPrimary until step 31 wires ambient)
                    progressTintList =
                        android.content.res.ColorStateList.valueOf(
                            com.google.android.material.color.MaterialColors.getColor(
                                requireContext(),
                                com.google.android.material.R.attr.colorPrimary,
                                android.graphics.Color.WHITE,
                            )
                        )
                    thumbTintList = progressTintList
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

            // Freq label: 10sp text2 per spec
            binding.eqFreqLabels.addView(
                TextView(requireContext()).apply {
                    text = freqLabels[i]
                    textSize = 10f
                    setTextColor(
                        com.google.android.material.color.MaterialColors.getColor(
                            requireContext(),
                            com.google.android.material.R.attr.colorOnSurfaceVariant,
                            android.graphics.Color.GRAY,
                        )
                    )
                    gravity = Gravity.CENTER
                    layoutParams =
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
            )
        }
    }

    // -------------------------------------------------------------------------
    // EQ setup
    // -------------------------------------------------------------------------

    private fun setupPresetSpinner(binding: FragmentAudioTabBinding) {
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

    private fun setupSwitch(binding: FragmentAudioTabBinding) {
        binding.eqSwitch.setOnCheckedChangeListener { _, checked -> viewModel.setEnabled(checked) }
    }

    private fun setupAutoEqBrowse(binding: FragmentAudioTabBinding) {
        binding.eqBtnAutoeqBrowse.setOnClickListener {
            AutoEqBrowserDialog().show(childFragmentManager, AutoEqBrowserDialog.TAG)
        }
    }

    private fun setupDeviceProfilesButton(binding: FragmentAudioTabBinding) {
        binding.eqBtnDeviceProfiles.setOnClickListener {
            DeviceProfileDialog().show(childFragmentManager, DeviceProfileDialog.TAG)
        }
    }

    private fun setupListeningModes(binding: FragmentAudioTabBinding) {
        binding.eqBtnSaveMode.setOnClickListener {
            if (!viewModel.canSaveListeningMode()) return@setOnClickListener
            showSaveModeDialog()
        }
    }

    // -------------------------------------------------------------------------
    // Crossfade card
    // -------------------------------------------------------------------------

    private fun setupCrossfadeCard(binding: FragmentAudioTabBinding) {
        val initialSeconds = crossfadeSettings.durationSeconds
        ignoreCrossfadeSlider = true
        binding.audioCrossfadeSlider.post {
            binding.audioCrossfadeSlider.progress = initialSeconds
            ignoreCrossfadeSlider = false
        }
        // State text in the card header (reuses audio_crossfade_value ID)
        binding.audioCrossfadeValue.text =
            if (!crossfadeSettings.enabled) getString(R.string.lbl_audio_off)
            else crossfadeValueLabel(initialSeconds)

        binding.audioCrossfadeSlider.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (ignoreCrossfadeSlider) return
                    binding.audioCrossfadeValue.text = crossfadeValueLabel(progress)
                    crossfadeSettings.setEnabled(progress > 0)
                    crossfadeSettings.setDuration(progress)
                }

                override fun onStartTrackingTouch(sb: SeekBar) {
                    sb.parent.requestDisallowInterceptTouchEvent(true)
                }

                override fun onStopTrackingTouch(sb: SeekBar) {}
            }
        )
    }

    /** "Off" when 0, otherwise "Ns". */
    private fun crossfadeValueLabel(seconds: Int): String =
        if (seconds == 0) getString(R.string.lbl_audio_off) else "${seconds}s"

    // -------------------------------------------------------------------------
    // Stereo Widening card
    // -------------------------------------------------------------------------

    private fun setupStereoCard(binding: FragmentAudioTabBinding) {
        val initialPercent = stereoSettings.amountPercent
        ignoreStereoSlider = true
        binding.audioStereoSlider.post {
            binding.audioStereoSlider.progress = initialPercent
            ignoreStereoSlider = false
        }
        binding.audioStereoValue.text = stereoValueLabel(initialPercent)

        binding.audioStereoSlider.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (ignoreStereoSlider) return
                    binding.audioStereoValue.text = stereoValueLabel(progress)
                    stereoSettings.setAmount(progress)
                }

                override fun onStartTrackingTouch(sb: SeekBar) {
                    sb.parent.requestDisallowInterceptTouchEvent(true)
                }

                override fun onStopTrackingTouch(sb: SeekBar) {}
            }
        )
    }

    /** "Off" when 0, otherwise "N%". */
    private fun stereoValueLabel(percent: Int): String =
        if (percent == 0) getString(R.string.lbl_audio_off) else "${percent}%"

    // -------------------------------------------------------------------------
    // Speed card
    // -------------------------------------------------------------------------

    /** Maps SeekBar progress [0–55] to speed [0.25–3.0]. */
    private fun progressToSpeed(progress: Int): Float = 0.25f + progress * 0.05f

    /** Maps speed [0.25–3.0] back to SeekBar progress [0–55]. */
    private fun speedToProgress(speed: Float): Int =
        ((speed - 0.25f) / 0.05f).roundToInt().coerceIn(0, 55)

    /** "1×", "1.5×", "0.25×" etc. */
    private fun speedLabel(speed: Float): String {
        val s = String.format("%.2f", speed).trimEnd('0').trimEnd('.')
        return "${s}×"
    }

    private fun setupSpeedCard(binding: FragmentAudioTabBinding) {
        val initialSpeed = speedSettings.speedX
        val initialProgress = speedToProgress(initialSpeed)

        ignoreSpeedSlider = true
        binding.audioSpeedSlider.post {
            binding.audioSpeedSlider.progress = initialProgress
            ignoreSpeedSlider = false
        }
        binding.audioSpeedValue.text = speedLabel(initialSpeed)
        updateSpeedChips(binding, initialSpeed)

        binding.audioSpeedSlider.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (ignoreSpeedSlider) return
                    val speed = progressToSpeed(progress)
                    binding.audioSpeedValue.text = speedLabel(speed)
                    updateSpeedChips(binding, speed)
                    speedSettings.setSpeed(speed)
                }

                override fun onStartTrackingTouch(sb: SeekBar) {
                    sb.parent.requestDisallowInterceptTouchEvent(true)
                }

                override fun onStopTrackingTouch(sb: SeekBar) {}
            }
        )

        // Speed preset chips — active chip gets pure color bg automatically via Filter chip style
        val chipPresets =
            listOf(
                binding.audioChipSpeed050 to 0.50f,
                binding.audioChipSpeed075 to 0.75f,
                binding.audioChipSpeed100 to 1.00f,
                binding.audioChipSpeed150 to 1.50f,
                binding.audioChipSpeed200 to 2.00f,
            )
        chipPresets.forEach { (chip, speed) ->
            chip.setOnClickListener {
                ignoreSpeedSlider = true
                binding.audioSpeedSlider.progress = speedToProgress(speed)
                ignoreSpeedSlider = false
                binding.audioSpeedValue.text = speedLabel(speed)
                updateSpeedChips(binding, speed)
                speedSettings.setSpeed(speed)
            }
        }
    }

    /**
     * Marks the chip matching [speed] as checked; clears the rest.
     * The Filter chip style handles the visual change (pure color bg when checked) automatically.
     */
    private fun updateSpeedChips(binding: FragmentAudioTabBinding, speed: Float) {
        val tolerance = 0.001f
        binding.audioChipSpeed050.isChecked = abs(speed - 0.50f) < tolerance
        binding.audioChipSpeed075.isChecked = abs(speed - 0.75f) < tolerance
        binding.audioChipSpeed100.isChecked = abs(speed - 1.00f) < tolerance
        binding.audioChipSpeed150.isChecked = abs(speed - 1.50f) < tolerance
        binding.audioChipSpeed200.isChecked = abs(speed - 2.00f) < tolerance
    }

    // -------------------------------------------------------------------------
    // Timer card
    // -------------------------------------------------------------------------

    private fun setupTimerCard(binding: FragmentAudioTabBinding) {
        val chips = listOf(
            binding.audioChipTimer15m to 15,
            binding.audioChipTimer30m to 30,
            binding.audioChipTimer45m to 45,
            binding.audioChipTimer1h to 60,
            binding.audioChipTimer2h to 120
        )
        
        chips.forEach { (chip, minutes) ->
            chip.setOnClickListener {
                if (chip.isChecked) {
                    playbackModel.startSleepTimer(minutes)
                } else {
                    playbackModel.cancelSleepTimer()
                }
            }
        }
        
        binding.audioChipTimerCustom.setOnClickListener {
            // Open custom dialog
            org.oxycblt.auxio.playback.sleeptimer.SleepTimerDialog().show(childFragmentManager, "sleep_timer")
        }

        binding.audioTimerEndOfSongSwitch.setOnCheckedChangeListener { _, isChecked ->
            // Assume PlaybackViewModel has setStopAtEnd(Boolean) or similar.
            // Fluxio usually manages this via PlaybackSpeedSettings or PlaybackViewModel
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Assuming playbackModel has sleepTimerTimeRemaining flow or LiveData
                // This will be wired to audio_timer_value
            }
        }
    }

    // -------------------------------------------------------------------------
    // EQ state observers
    // -------------------------------------------------------------------------

    private fun onEnabledChanged(binding: FragmentAudioTabBinding, enabled: Boolean) {
        binding.eqSwitch.isChecked = enabled
        seekBars.forEach { it?.isEnabled = enabled }
        binding.eqPresetSpinner.isEnabled = enabled
        // Update state text in EQ card header
        binding.cardEqState.text =
            if (enabled) getString(R.string.lbl_eq_on) else getString(R.string.lbl_audio_off)
    }

    private fun onBandsChanged(bands: FloatArray) {
        for (i in 0 until 10) {
            val seekBar = seekBars[i] ?: continue
            val progress = (bands[i] * 10 + 120).toInt().coerceIn(0, 240)
            seekBar.post { seekBar.progress = progress }
        }
    }

    private fun onPresetChanged(binding: FragmentAudioTabBinding, preset: Int) {
        ignoreSpinner = true
        val pos =
            if (preset == EqualizerSettings.PRESET_CUSTOM) EqualizerSettings.PRESET_NAMES.size
            else preset
        binding.eqPresetSpinner.post {
            binding.eqPresetSpinner.setSelection(pos)
            binding.eqPresetSpinner.post { ignoreSpinner = false }
        }
    }

    private fun onProfileLabelChanged(
        binding: FragmentAudioTabBinding,
        name: String?,
        modified: Boolean,
    ) {
        if (name == null) {
            binding.eqAutoeqProfileLabel.isVisible = false
            return
        }
        binding.eqAutoeqProfileLabel.text =
            if (modified) getString(R.string.lbl_autoeq_modified, name)
            else getString(R.string.lbl_autoeq_profile, name)
        binding.eqAutoeqProfileLabel.isVisible = true
    }

    private fun onListeningModesChanged(
        binding: FragmentAudioTabBinding,
        modes: List<ListeningMode>,
    ) {
        binding.eqBtnSaveMode.isEnabled = modes.size < ListeningModeManager.MAX_MODES

        // Update state text in modes card header
        binding.cardModesState.text =
            if (modes.isEmpty()) "—"
            else resources.getQuantityString(R.plurals.lbl_x_listening_modes, modes.size, modes.size)

        val chipGroup = binding.eqListeningModesChips
        chipGroup.removeAllViews()

        if (modes.isEmpty()) {
            chipGroup.isVisible = false
            binding.eqListeningModesEmpty.isVisible = true
            return
        }

        binding.eqListeningModesEmpty.isVisible = false
        chipGroup.isVisible = true

        modes.forEach { mode ->
            val chip =
                Chip(requireContext()).apply {
                    text = mode.name
                    isCheckable = false
                    isCloseIconVisible = true
                    setOnClickListener { viewModel.applyListeningMode(mode) }
                    setOnCloseIconClickListener { showDeleteModeDialog(mode) }
                }
            chipGroup.addView(chip)
        }
    }

    // -------------------------------------------------------------------------
    // Dialogs
    // -------------------------------------------------------------------------

    private fun showSaveModeDialog() {
        val paddingPx = (16 * resources.displayMetrics.density).toInt()
        val editText =
            EditText(requireContext()).apply {
                hint = getString(R.string.hint_listening_mode_name)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setPadding(paddingPx, paddingPx / 2, paddingPx, paddingPx / 2)
            }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.lbl_save_listening_mode)
            .setView(editText)
            .setPositiveButton(R.string.lbl_save) { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) viewModel.saveCurrentAsListeningMode(name)
            }
            .setNegativeButton(R.string.lbl_cancel, null)
            .show()
    }

    private fun showDeleteModeDialog(mode: ListeningMode) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(mode.name)
            .setMessage(R.string.lbl_confirm_delete_mode)
            .setPositiveButton(R.string.lbl_delete) { _, _ -> viewModel.deleteListeningMode(mode) }
            .setNegativeButton(R.string.lbl_cancel, null)
            .show()
    }
}
