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
import android.view.MotionEvent
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
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import org.oxycblt.auxio.detail.DetailViewModel
import org.oxycblt.auxio.list.ListViewModel
import org.oxycblt.auxio.lyrics.LrcLine
import org.oxycblt.auxio.lyrics.LyricsViewModel
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.playback.sleeptimer.SleepTimerDialog
import org.oxycblt.auxio.playback.state.RepeatMode
import org.oxycblt.auxio.playback.ui.StyledSeekBar
import org.oxycblt.auxio.playback.ui.stepper.DisplayPortion
import org.oxycblt.auxio.playback.ui.stepper.PlayerFastSeekOverlay
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.getAttrColorCompat
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

    private var equalizerLauncher: ActivityResultLauncher<Intent>? = null
    private var lastCoverWidth = 0

    enum class PlayerTab {
        NONE,
        QUEUE,
        LYRICS,
        AUDIO,
    }

    private var currentTab = PlayerTab.NONE

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
            setOnClickListener { navigateToCurrentAlbum() }
        }
        binding.playbackArtist.apply {
            isSelected = true
            setOnClickListener { navigateToCurrentArtist() }
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

        binding.playbackTabQueue?.setOnClickListener { setTab(PlayerTab.QUEUE) }
        binding.playbackTabLyrics?.setOnClickListener { setTab(PlayerTab.LYRICS) }
        binding.playbackTabAudio?.setOnClickListener { setTab(PlayerTab.AUDIO) }

        setupGestures(binding)
        updateTabUI()

        binding.playbackPager?.apply {
            adapter = object : FragmentStateAdapter(this@PlaybackPanelFragment) {
                override fun getItemCount() = 3
                override fun createFragment(position: Int): Fragment {
                    return when (position) {
                        0 -> org.oxycblt.auxio.playback.queue.QueueFragment()
                        1 -> LyricsTabFragment()
                        2 -> AudioTabFragment()
                        else -> throw IllegalArgumentException()
                    }
                }
            }
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    val tab = when (position) {
                        0 -> PlayerTab.QUEUE
                        1 -> PlayerTab.LYRICS
                        else -> PlayerTab.AUDIO
                    }
                    if (currentTab != tab) {
                        currentTab = tab
                        updateTabUI()
                    }
                }
            })
        }

        collectImmediately(playbackModel.song, ::updateSong)
        collectImmediately(playbackModel.parent, ::updateParent)
        collectImmediately(playbackModel.positionDs, ::updatePosition)
        collectImmediately(playbackModel.repeatMode, ::updateRepeat)
        collectImmediately(playbackModel.isPlaying, ::updatePlaying)
        collectImmediately(playbackModel.isShuffled, ::updateShuffled)
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
        binding.playbackPager?.adapter = null
        binding.playbackRepeat.clearPendingIcon()
        binding.playbackSong.isSelected = false
        binding.playbackArtist.isSelected = false
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

    private fun setTab(tab: PlayerTab) {
        currentTab = if (currentTab == tab) PlayerTab.NONE else tab
        updateTabUI()
        val pager = binding?.playbackPager ?: return
        if (currentTab != PlayerTab.NONE) {
            val target = when (currentTab) {
                PlayerTab.QUEUE -> 0
                PlayerTab.LYRICS -> 1
                PlayerTab.AUDIO -> 2
                else -> 0
            }
            if (pager.currentItem != target) {
                pager.setCurrentItem(target, true)
            }
        }
    }

    private fun shiftTab(direction: Int) {
        val tabs = arrayOf(PlayerTab.QUEUE, PlayerTab.LYRICS, PlayerTab.AUDIO)
        val currentIndex = tabs.indexOf(currentTab)
        if (currentIndex != -1) {
            val nextIndex = (currentIndex + direction + tabs.size) % tabs.size
            setTab(tabs[nextIndex])
        } else {
            setTab(if (direction > 0) PlayerTab.QUEUE else PlayerTab.AUDIO)
        }
    }

    private fun updateTabUI() {
        val b = binding ?: return
        val context = requireContext()
        val activeColor = context.getAttrColorCompat(android.R.attr.colorPrimary).defaultColor
        val inactiveColor =
            context.getAttrColorCompat(android.R.attr.textColorSecondary).defaultColor

        fun updateTextView(tv: android.widget.TextView?, tab: PlayerTab) {
            if (tv == null) return
            if (currentTab == tab) {
                tv.setTextColor(activeColor)
                tv.paintFlags = tv.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            } else {
                tv.setTextColor(inactiveColor)
                tv.paintFlags = tv.paintFlags and android.graphics.Paint.UNDERLINE_TEXT_FLAG.inv()
            }
        }

        updateTextView(b.playbackTabQueue, PlayerTab.QUEUE)
        updateTextView(b.playbackTabLyrics, PlayerTab.LYRICS)
        updateTextView(b.playbackTabAudio, PlayerTab.AUDIO)

        val showMain = currentTab == PlayerTab.NONE
        b.playbackCover.isVisible = showMain
        b.playbackFastSeekOverlay?.isVisible = showMain
        b.playbackInfoContainer.isVisible = showMain
        b.playbackSeekBar?.isVisible = showMain
        b.playbackControlsContainer.isVisible = showMain
        b.playbackSecondaryControls?.isVisible = showMain

        b.playbackPager?.isVisible = currentTab != PlayerTab.NONE
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures(binding: FragmentPlaybackPanelBinding) {
        val gestureDetector =
            android.view.GestureDetector(
                requireContext(),
                object : android.view.GestureDetector.SimpleOnGestureListener() {
                    private val SWIPE_THRESHOLD = 100
                    private val SWIPE_VELOCITY_THRESHOLD = 100

                    override fun onFling(
                        e1: MotionEvent?,
                        e2: MotionEvent,
                        velocityX: Float,
                        velocityY: Float,
                    ): Boolean {
                        if (e1 == null) return false
                        val diffY = e2.y - e1.y
                        val diffX = e2.x - e1.x
                        if (Math.abs(diffX) > Math.abs(diffY)) {
                            if (
                                Math.abs(diffX) > SWIPE_THRESHOLD &&
                                    Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD
                            ) {
                                if (diffX > 0) shiftTab(-1) else shiftTab(1)
                                return true
                            }
                        } else if (
                            diffY > 0 &&
                                Math.abs(diffY) > SWIPE_THRESHOLD &&
                                Math.abs(velocityY) > SWIPE_VELOCITY_THRESHOLD
                        ) {
                            if (currentTab != PlayerTab.NONE) {
                                setTab(PlayerTab.NONE)
                                return true
                            }
                        }
                        return false
                    }
                },
            )
        val touchListener =
            android.view.View.OnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)
                false
            }
        binding.root.setOnTouchListener(touchListener)

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
        repeatButton.isActivated = repeatMode != RepeatMode.NONE
        repeatButton.setIconResource(repeatMode.icon)
    }

    private fun updatePlaying(isPlaying: Boolean) {
        requireBinding().playbackPlayPause.isChecked = isPlaying
        requireBinding().playbackSeekBar?.setWaveEnabled(isPlaying)
    }

    private fun updateShuffled(isShuffled: Boolean) {
        requireBinding().playbackShuffle.isChecked = isShuffled
        requireBinding().playbackShuffle.isActivated = isShuffled
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

}
