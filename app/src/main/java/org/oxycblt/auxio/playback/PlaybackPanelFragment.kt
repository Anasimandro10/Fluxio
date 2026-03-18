/*
 * Copyright (c) 2021 Fluxio Project
 * PlaybackPanelFragment.kt is part of Fluxio.
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

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.dynamicanimation.animation.SpringForce
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentPlaybackPanelBinding
import org.oxycblt.auxio.databinding.ItemLyricLineBinding
import org.oxycblt.auxio.detail.DetailViewModel
import org.oxycblt.auxio.list.ListViewModel
import org.oxycblt.auxio.lyrics.LrcLine
import org.oxycblt.auxio.lyrics.LyricsViewModel
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.playback.sleeptimer.SleepTimerDialog
import org.oxycblt.auxio.playback.sleeptimer.SleepTimerViewModel
import org.oxycblt.auxio.playback.state.RepeatMode
import org.oxycblt.auxio.playback.ui.StyledSeekBar
import org.oxycblt.auxio.playback.ui.stepper.DisplayPortion
import org.oxycblt.auxio.playback.ui.stepper.PlayerFastSeekOverlay
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.showToast
import org.oxycblt.auxio.util.systemBarInsetsCompat
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * A [ViewBindingFragment] showing more information about the currently playing song, alongside all
 * available controls and synced lyrics when an LRC file is present.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class PlaybackPanelFragment :
    ViewBindingFragment<FragmentPlaybackPanelBinding>(),
    Toolbar.OnMenuItemClickListener,
    StyledSeekBar.Listener,
    ViewTreeObserver.OnGlobalLayoutListener,
    PlayerFastSeekOverlay.PerformListener {

    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val detailModel: DetailViewModel by activityViewModels()
    private val listModel: ListViewModel by activityViewModels()
    private val lyricsModel: LyricsViewModel by activityViewModels()
    private val timerModel: SleepTimerViewModel by activityViewModels()

    private var equalizerLauncher: ActivityResultLauncher<Intent>? = null
    private var lastCoverWidth = 0
    private var lyricsAdapter: LyricsAdapter? = null

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentPlaybackPanelBinding.inflate(inflater)

    override fun onBindingCreated(
        binding: FragmentPlaybackPanelBinding,
        savedInstanceState: Bundle?,
    ) {
        super.onBindingCreated(binding, savedInstanceState)

        equalizerLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

        binding.root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.systemBarInsetsCompat
            view.updatePadding(bottom = bars.bottom)
            insets
        }

        binding.playbackToolbar.apply {
            setNavigationOnClickListener { playbackModel.openMain() }
            setOnMenuItemClickListener(this@PlaybackPanelFragment)
        }

        binding.playbackCover.onSwipeListener = null

        binding.playbackFastSeekOverlay?.apply {
            performListener(this@PlaybackPanelFragment)
            seekSecondsSupplier { 10 }
        }

        binding.playbackSong.apply {
            isSelected = true
            setOnClickListener { navigateToCurrentSong() }
        }
        binding.playbackArtist.apply {
            isSelected = true
            setOnClickListener { navigateToCurrentArtist() }
        }
        binding.playbackAlbum?.apply {
            isSelected = true
            setOnClickListener { navigateToCurrentAlbum() }
        }

        binding.playbackSeekBar?.listener = this

        binding.playbackRepeat.setOnClickListener { playbackModel.toggleRepeatMode() }
        binding.playbackSkipPrev.setOnClickListener { playbackModel.prev() }
        binding.playbackPlayPause.apply {
            @SuppressLint("RestrictedApi")
            setCornerSpringForce(
                SpringForce().apply {
                    stiffness = 700f
                    dampingRatio = 0.9f
                }
            )
            setOnClickListener { playbackModel.togglePlaying() }
        }
        binding.playbackSkipNext.setOnClickListener { playbackModel.next() }
        binding.playbackShuffle.setOnClickListener { playbackModel.toggleShuffled() }
        binding.playbackMore?.setOnClickListener {
            playbackModel.song.value?.let {
                listModel.openMenu(R.menu.playback_song, it, PlaySong.ByItself)
            }
        }

        lyricsAdapter = LyricsAdapter()
        binding.playbackLyrics?.adapter = lyricsAdapter
        binding.playbackLyrics?.layoutManager = LinearLayoutManager(requireContext())

        collectImmediately(playbackModel.song, ::updateSong)
        collectImmediately(playbackModel.parent, ::updateParent)
        collectImmediately(playbackModel.positionDs, ::updatePosition)
        collectImmediately(playbackModel.repeatMode, ::updateRepeat)
        collectImmediately(playbackModel.isPlaying, ::updatePlaying)
        collectImmediately(playbackModel.isShuffled, ::updateShuffled)
        collectImmediately(lyricsModel.lines, ::updateLyrics)
        collectImmediately(lyricsModel.isSynced, ::updateIsSynced)
        collectImmediately(lyricsModel.currentLineIndex, ::updateCurrentLine)
        collectImmediately(timerModel.timerFired, ::onTimerFired)
    }

    override fun onStart() {
        super.onStart()
        playbackModel.song.value?.let { requireBinding().playbackCover.bind(it) }
        requireBinding().root.viewTreeObserver.addOnGlobalLayoutListener(this)
    }

    override fun onStop() {
        super.onStop()
        requireBinding().root.viewTreeObserver.removeOnGlobalLayoutListener(this)
    }

    override fun onGlobalLayout() {
        if (binding == null || lastCoverWidth < 0) return
        val binding = requireBinding()
        val coverWidth = binding.playbackCover.width
        if (lastCoverWidth != coverWidth) {
            lastCoverWidth = coverWidth
        } else {
            playbackModel.song.value?.let { binding.playbackCover.bind(it) }
            lastCoverWidth = -1
        }
    }

    override fun onDestroyBinding(binding: FragmentPlaybackPanelBinding) {
        equalizerLauncher = null
        lyricsAdapter = null
        binding.playbackLyrics?.adapter = null
        binding.playbackRepeat.clearPendingIcon()
        binding.playbackSong.isSelected = false
        binding.playbackArtist.isSelected = false
        binding.playbackAlbum?.isSelected = false
        binding.playbackToolbar.setOnMenuItemClickListener(null)
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_open_sleep_timer) {
            L.d("Opening sleep timer dialog")
            SleepTimerDialog().show(childFragmentManager, "sleep_timer")
            return true
        }
        if (item.itemId == R.id.action_open_equalizer) {
            L.d("Launching equalizer")
            val equalizerIntent =
                Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                    .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, playbackModel.currentAudioSessionId)
                    .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            try {
                requireNotNull(equalizerLauncher) { "Equalizer panel launcher was not available" }
                    .launch(equalizerIntent)
            } catch (e: ActivityNotFoundException) {
                requireContext().showToast(R.string.err_no_app)
            }
            return true
        }
        return false
    }

    override fun onSeekConfirmed(positionDs: Long) {
        playbackModel.seekTo(positionDs)
    }

    private fun updateSong(song: Song?) {
        if (song == null) return
        val binding = requireBinding()
        val context = requireContext()
        L.d("Updating song display: $song")
        binding.playbackCover.bind(song)
        binding.playbackSong.text = song.name.resolve(context)
        binding.playbackArtist.text = song.artists.resolveNames(context)
        binding.playbackAlbum?.text = song.album.name.resolve(context)
        binding.playbackSeekBar?.durationDs = song.durationMs.msToDs()
    }

    private fun updateParent(parent: MusicParent?) {
        val binding = requireBinding()
        val context = requireContext()
        binding.playbackToolbar.subtitle =
            parent?.run { name.resolve(context) } ?: context.getString(R.string.lbl_all_songs)
    }

    private fun updatePosition(positionDs: Long) {
        requireBinding().playbackSeekBar?.positionDs = positionDs
    }

    private fun updateRepeat(repeatMode: RepeatMode) {
        val repeatButton = requireBinding().playbackRepeat
        repeatButton.isChecked = repeatMode != RepeatMode.NONE
        repeatButton.setIconResource(repeatMode.icon)
    }

    private fun updatePlaying(isPlaying: Boolean) {
        requireBinding().playbackPlayPause.isChecked = isPlaying
        requireBinding().playbackSeekBar?.setWaveEnabled(isPlaying)
    }

    private fun updateShuffled(isShuffled: Boolean) {
        requireBinding().playbackShuffle.isChecked = isShuffled
    }

    /** Shows the lyrics list when lyrics are available, hides it otherwise. */
    private fun updateLyrics(lines: List<LrcLine>) {
        requireBinding().playbackLyrics?.isVisible = lines.isNotEmpty()
        lyricsAdapter?.submitList(lines)
    }

    /**
     * Updates the adapter when the sync state changes. Plain-text lyrics show all lines at full
     * opacity; synced lyrics dim inactive lines.
     */
    private fun updateIsSynced(isSynced: Boolean) {
        lyricsAdapter?.setIsSynced(isSynced)
    }

    /** Scrolls to keep the active lyric line visible and highlights it. */
    private fun updateCurrentLine(index: Int) {
        val adapter = lyricsAdapter ?: return
        val previousIndex = adapter.activeIndex
        adapter.setActiveIndex(index)
        // Only scroll when the active line actually changed.
        // Scrolling on every emission interrupted manual scrolling by the user.
        if (index >= 0 && index != previousIndex) {
            requireBinding().playbackLyrics?.smoothScrollToPosition(index)
        }
    }

    /**
     * When the sleep timer fires, pause playback immediately. The "finish current song first"
     * behaviour is handled by observing this flag right after the song-transition callback.
     */
    private fun onTimerFired(fired: Boolean) {
        if (!fired) return
        L.d("Sleep timer fired — pausing after current song")
        playbackModel.pauseAfterCurrentSong()
        timerModel.acknowledgeTimerFired()
    }

    private fun navigateToCurrentSong() {
        playbackModel.song.value?.let(detailModel::showAlbum)
    }

    private fun navigateToCurrentArtist() {
        playbackModel.song.value?.let(detailModel::showArtist)
    }

    private fun navigateToCurrentAlbum() {
        playbackModel.song.value?.let { detailModel.showAlbum(it.album) }
    }

    override fun onDoubleTap() {}

    override fun onDoubleTapEnd() {}

    override fun getFastSeekDirection(
        portion: DisplayPortion
    ): PlayerFastSeekOverlay.PerformListener.FastSeekDirection {
        return when (portion) {
            DisplayPortion.LEFT,
            DisplayPortion.LEFT_HALF ->
                PlayerFastSeekOverlay.PerformListener.FastSeekDirection.BACKWARD
            DisplayPortion.RIGHT,
            DisplayPortion.RIGHT_HALF ->
                PlayerFastSeekOverlay.PerformListener.FastSeekDirection.FORWARD
            else -> PlayerFastSeekOverlay.PerformListener.FastSeekDirection.NONE
        }
    }

    override fun seek(forward: Boolean) {
        if (forward) playbackModel.stepForward() else playbackModel.stepBack()
    }

    // -------------------------------------------------------------------------
    // Inner adapter for the lyrics RecyclerView
    // -------------------------------------------------------------------------

    /** Adapter that renders a list of [LrcLine] items and highlights the active one. */
    private class LyricsAdapter : ListAdapter<LrcLine, LyricsAdapter.ViewHolder>(LrcLineDiff) {

        var activeIndex = -1
            private set

        private var isSynced = true

        fun setActiveIndex(index: Int) {
            val old = activeIndex
            activeIndex = index
            if (old >= 0) notifyItemChanged(old)
            if (index >= 0) notifyItemChanged(index)
        }

        /** Called when lyrics change between synced and plain text. */
        fun setIsSynced(synced: Boolean) {
            isSynced = synced
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding =
                ItemLyricLineBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position), isSynced && position == activeIndex, isSynced)
        }

        inner class ViewHolder(private val binding: ItemLyricLineBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(line: LrcLine, isActive: Boolean, isSynced: Boolean) {
                binding.lyricLine.text = line.text
                // Plain text: all lines full opacity. Synced: dim inactive lines.
                binding.lyricLine.alpha =
                    when {
                        !isSynced -> 1f
                        isActive -> 1f
                        else -> 0.35f
                    }
                binding.lyricLine.isSelected = isActive
            }
        }

        private object LrcLineDiff : DiffUtil.ItemCallback<LrcLine>() {
            // Use text as identity key so plain-text lines with startMs=0 are handled correctly
            override fun areItemsTheSame(old: LrcLine, new: LrcLine) =
                old.startMs == new.startMs && old.text == new.text

            override fun areContentsTheSame(old: LrcLine, new: LrcLine) = old == new
        }
    }
}
