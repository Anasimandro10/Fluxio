/*
 * Copyright (c) 2026 Fluxio Project
 * LyricsTabFragment.kt is part of Fluxio.
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

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.databinding.FragmentLyricsTabBinding
import org.oxycblt.auxio.databinding.ItemLyricLineBinding
import org.oxycblt.auxio.lyrics.LrcLine
import org.oxycblt.auxio.lyrics.LyricsViewModel
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.musikr.Song

/**
 * LYRICS tab inside the player panel.
 *
 * Design spec (FLUXIO_CONTEXTO.md §Player — LYRICS tab):
 * - Header: artwork 64dp r:8dp top-left + song title + artist.
 * - Active line: #FFFFFF, 22sp Medium, glow shadow 8dp ~30% album color.
 * - Inactive lines: #FFFFFF at 35% opacity, 20sp Regular. No scale transforms.
 * - Auto-scroll centered on active line, smooth.
 * - No lyrics → "Sin letras disponibles" centered 17sp text3.
 * - Word-by-word highlight inside active line: current word keeps full #FFFFFF, upcoming words
 *   inherit the 35% inactive dimming so they visually "wait".
 */
@AndroidEntryPoint
class LyricsTabFragment : ViewBindingFragment<FragmentLyricsTabBinding>() {

    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val lyricsModel: LyricsViewModel by activityViewModels()

    private var lyricsAdapter: LyricsAdapter? = null

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentLyricsTabBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentLyricsTabBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        lyricsAdapter = LyricsAdapter()
        binding.lyricsTabRecycler.apply {
            adapter = lyricsAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }

        collectImmediately(playbackModel.song, ::updateSong)
        collectImmediately(lyricsModel.lines, ::updateLyrics)
        collectImmediately(lyricsModel.isSynced, ::updateIsSynced)
        collectImmediately(lyricsModel.currentLineIndex, ::updateCurrentLine)
        collectImmediately(lyricsModel.activeWordIndex, ::updateActiveWord)
    }

    override fun onDestroyBinding(binding: FragmentLyricsTabBinding) {
        lyricsAdapter = null
        binding.lyricsTabRecycler.adapter = null
    }

    private fun updateSong(song: Song?) {
        if (song == null) return
        val binding = requireBinding()
        val context = requireContext()
        binding.lyricsTabCover.bind(song)
        binding.lyricsTabSong.text = song.name.resolve(context)
        binding.lyricsTabArtist.text = song.artists.resolveNames(context)
        // TODO (step 31): observe DynamicColorManager.ambientColor here
        // and call lyricsAdapter?.setAmbientColor(it) so the glow tracks the album.
    }

    private fun updateLyrics(lines: List<LrcLine>) {
        val binding = requireBinding()
        val isEmpty = lines.isEmpty()
        binding.lyricsTabRecycler.isVisible = !isEmpty
        binding.lyricsTabEmpty.isVisible = isEmpty
        lyricsAdapter?.submitList(lines)
    }

    private fun updateIsSynced(isSynced: Boolean) {
        lyricsAdapter?.setIsSynced(isSynced)
    }

    private fun updateCurrentLine(index: Int) {
        val adapter = lyricsAdapter ?: return
        adapter.setActiveIndex(index)
        if (index >= 0) {
            scrollToCenter(requireBinding().lyricsTabRecycler, index)
        }
    }

    private fun updateActiveWord(index: Int) {
        lyricsAdapter?.setActiveWordIndex(index)
    }

    /**
     * Smooth-scrolls [recyclerView] so that the item at [position] is vertically centered in the
     * viewport. Uses a [LinearSmoothScroller] that calculates an offset to place the item's
     * midpoint at the RecyclerView's midpoint, instead of merely scrolling it into view.
     */
    private fun scrollToCenter(recyclerView: RecyclerView, position: Int) {
        val scroller = CenterSmoothScroller(recyclerView.context)
        scroller.targetPosition = position
        recyclerView.layoutManager?.startSmoothScroll(scroller)
    }

    // -------------------------------------------------------------------------
    // Adapter
    // -------------------------------------------------------------------------

    private class LyricsAdapter : ListAdapter<LrcLine, LyricsAdapter.ViewHolder>(LrcLineDiff) {

        var activeIndex = -1
            private set

        private var activeWordIndex = -1
        private var isSynced = true
        private var ambientColor = Color.WHITE

        /**
         * Updates the active line index and triggers targeted rebinds only for the two rows whose
         * visual state changed (previous active → inactive, new index → active). Avoids
         * [notifyDataSetChanged] which would rebind every row on each lyric tick.
         */
        fun setActiveIndex(index: Int) {
            if (activeIndex == index) return
            val previous = activeIndex
            activeIndex = index
            if (previous >= 0 && previous < itemCount) notifyItemChanged(previous)
            if (index >= 0 && index < itemCount) notifyItemChanged(index)
        }

        fun setActiveWordIndex(wordIndex: Int) {
            if (activeWordIndex == wordIndex) return
            activeWordIndex = wordIndex
            if (activeIndex >= 0) notifyItemChanged(activeIndex, PAYLOAD_WORD)
        }

        fun setIsSynced(synced: Boolean) {
            if (isSynced == synced) return
            isSynced = synced
            notifyDataSetChanged()
        }

        fun setAmbientColor(color: Int) {
            if (ambientColor == color) return
            ambientColor = color
            if (activeIndex >= 0) notifyItemChanged(activeIndex, PAYLOAD_GLOW)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding =
                ItemLyricLineBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(
            holder: ViewHolder,
            position: Int,
            payloads: MutableList<Any>,
        ) {
            if (
                payloads.isNotEmpty() && payloads.all { it == PAYLOAD_WORD || it == PAYLOAD_GLOW }
            ) {
                val isActiveLine = position == activeIndex
                if (isActiveLine) {
                    holder.applyWordHighlight(getItem(position), activeWordIndex)
                    holder.applyGlow(ambientColor)
                }
            } else {
                super.onBindViewHolder(holder, position, payloads)
            }
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(
                line = getItem(position),
                isActiveLine = isSynced && position == activeIndex,
                isSynced = isSynced,
                currentWordIdx = if (isSynced && position == activeIndex) activeWordIndex else -1,
                ambientColor = ambientColor,
            )
        }

        inner class ViewHolder(private val binding: ItemLyricLineBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(
                line: LrcLine,
                isActiveLine: Boolean,
                isSynced: Boolean,
                currentWordIdx: Int,
                ambientColor: Int,
            ) {
                applyWordHighlight(line, currentWordIdx)

                binding.lyricLine.scaleX = 1f
                binding.lyricLine.scaleY = 1f

                if (!isSynced) {
                    binding.lyricLine.alpha = 1f
                    binding.lyricLine.textSize = 20f
                    binding.lyricLine.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
                } else if (isActiveLine) {
                    binding.lyricLine.alpha = 1f
                    binding.lyricLine.textSize = 22f
                    applyGlow(ambientColor)
                } else {
                    binding.lyricLine.alpha = 0.35f
                    binding.lyricLine.textSize = 20f
                    binding.lyricLine.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
                }

                binding.lyricLine.isSelected = isActiveLine
            }

            fun applyWordHighlight(line: LrcLine, currentWordIdx: Int) {
                if (line.words.isEmpty() || currentWordIdx < 0) {
                    binding.lyricLine.text = line.text
                    return
                }

                val spannable = SpannableString(line.text)

                for (i in line.words.indices) {
                    val w = line.words[i]
                    if (w.startChar < 0 || w.endChar > line.text.length || w.startChar >= w.endChar)
                        continue

                    when {
                        i < currentWordIdx -> {
                            // Already sung: full #FFFFFF — no span needed.
                        }
                        i == currentWordIdx -> {
                            spannable.setSpan(
                                ForegroundColorSpan(Color.WHITE),
                                w.startChar,
                                w.endChar,
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                        }
                        else -> {
                            // Upcoming: 35% white so they visually recede.
                            spannable.setSpan(
                                ForegroundColorSpan(0x59FFFFFF.toInt()),
                                w.startChar,
                                w.endChar,
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                        }
                    }
                }

                binding.lyricLine.text = spannable
            }

            fun applyGlow(ambientColor: Int) {
                val r = Color.red(ambientColor)
                val g = Color.green(ambientColor)
                val b = Color.blue(ambientColor)
                // 30% opacity = 0x4D (77/255 ≈ 0.30)
                val glowColor = Color.argb(0x4D, r, g, b)
                binding.lyricLine.setShadowLayer(8f, 0f, 0f, glowColor)
            }
        }

        companion object {
            private const val PAYLOAD_WORD = "word_update"
            private const val PAYLOAD_GLOW = "glow_update"
        }

        private object LrcLineDiff : DiffUtil.ItemCallback<LrcLine>() {
            override fun areItemsTheSame(old: LrcLine, new: LrcLine) =
                old.startMs == new.startMs && old.text == new.text

            override fun areContentsTheSame(old: LrcLine, new: LrcLine) = old == new
        }
    }

    /**
     * A [LinearSmoothScroller] that positions the target item so its vertical midpoint aligns with
     * the RecyclerView's vertical midpoint, fulfilling the "centered on active line" spec.
     */
    private class CenterSmoothScroller(context: Context) : LinearSmoothScroller(context) {
        override fun calculateDtToFit(
            viewStart: Int,
            viewEnd: Int,
            boxStart: Int,
            boxEnd: Int,
            snapPreference: Int,
        ): Int = (boxStart + (boxEnd - boxStart) / 2) - (viewStart + (viewEnd - viewStart) / 2)
    }
}
