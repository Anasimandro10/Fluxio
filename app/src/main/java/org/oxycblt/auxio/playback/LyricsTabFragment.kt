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

    // Album ambient color for the glow effect. Updated whenever the song changes.
    // Defaults to white so the glow is visible even before the color is resolved.
    private var ambientColor: Int = Color.WHITE

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
        val previousIndex = adapter.activeIndex
        adapter.setActiveIndex(index)
        // Smooth-scroll so the active line is roughly centred on screen.
        if (index >= 0 && index != previousIndex) {
            requireBinding().lyricsTabRecycler.smoothScrollToPosition(index)
        }
    }

    private fun updateActiveWord(index: Int) {
        lyricsAdapter?.setActiveWordIndex(index)
    }

    // -------------------------------------------------------------------------
    // Adapter
    // -------------------------------------------------------------------------

    // Not `inner` — Kotlin prohibits `companion object` and `object` declarations
    // inside inner classes. ViewHolder is still `inner` to LyricsAdapter (fine).
    private class LyricsAdapter : ListAdapter<LrcLine, LyricsAdapter.ViewHolder>(LrcLineDiff) {

        var activeIndex = -1
            private set

        private var activeWordIndex = -1
        private var isSynced = true

        // Album ambient color for the active-line glow (30% opacity).
        // Set externally from updateSong() when DynamicColorManager is wired up (step 31).
        private var ambientColor = Color.WHITE

        fun setActiveIndex(index: Int) {
            if (activeIndex == index) return
            activeIndex = index
            // Full rebind: every row's alpha/textSize depends on whether it is the active line.
            notifyDataSetChanged()
        }

        fun setActiveWordIndex(wordIndex: Int) {
            if (activeWordIndex == wordIndex) return
            activeWordIndex = wordIndex
            // Only the active line needs a word-level redraw — use payload to skip full rebind.
            if (activeIndex >= 0) notifyItemChanged(activeIndex, PAYLOAD_WORD)
        }

        fun setIsSynced(synced: Boolean) {
            isSynced = synced
            notifyDataSetChanged()
        }

        fun setAmbientColor(color: Int) {
            if (ambientColor == color) return
            ambientColor = color
            // Glow color changed — only the active line shows the glow.
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
                // Lightweight re-bind: only update text spans and glow, skip alpha/size.
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

        // ---- ViewHolder ----

        inner class ViewHolder(private val binding: ItemLyricLineBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(
                line: LrcLine,
                isActiveLine: Boolean,
                isSynced: Boolean,
                currentWordIdx: Int,
                ambientColor: Int,
            ) {
                // Text + word-level spans
                applyWordHighlight(line, currentWordIdx)

                // Per-spec visual state:
                //   unsynced  → full opacity, 20sp, no glow
                //   active    → full opacity, 22sp Medium, glow 8dp @30% album color
                //   inactive  → 35% opacity, 20sp, no glow, no scale
                //
                // Note: NO scaleX / scaleY transforms (context specifies only size+opacity diff).
                // Reset scale that may have been left from a recycled ViewHolder.
                binding.lyricLine.scaleX = 1f
                binding.lyricLine.scaleY = 1f

                if (!isSynced) {
                    binding.lyricLine.alpha = 1f
                    binding.lyricLine.textSize = 20f
                    binding.lyricLine.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
                } else if (isActiveLine) {
                    binding.lyricLine.alpha = 1f
                    binding.lyricLine.textSize = 22f
                    // Glow: 8dp radius, 30% opacity of the album's ambient color.
                    applyGlow(ambientColor)
                } else {
                    // All inactive lines: flat 35% opacity, 20sp — no cascade.
                    binding.lyricLine.alpha = 0.35f
                    binding.lyricLine.textSize = 20f
                    binding.lyricLine.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
                }

                binding.lyricLine.isSelected = isActiveLine
            }

            /**
             * Applies word-by-word highlighting inside the active line.
             *
             * Spec: current word = #FFFFFF full opacity. Words yet to be sung inherit the inactive
             * dimming (they're on the active line so the base text is already #FFFFFF at full
             * alpha; we reduce them to 35% so they visually "wait"). Past words stay full white.
             *
             * If the line has no word-level timing (plain LRC), this is a no-op and the full line
             * text is shown as-is at the active-line style.
             */
            fun applyWordHighlight(line: LrcLine, currentWordIdx: Int) {
                if (line.words.isEmpty() || currentWordIdx < 0) {
                    // No word timing data — show the whole line text unchanged.
                    binding.lyricLine.text = line.text
                    return
                }

                val spannable = SpannableString(line.text)

                for (i in line.words.indices) {
                    val w = line.words[i]
                    // Guard against parser producing out-of-bounds offsets.
                    if (w.startChar < 0 || w.endChar > line.text.length || w.startChar >= w.endChar)
                        continue

                    when {
                        i < currentWordIdx -> {
                            // Already sung: full #FFFFFF (no span needed — default text color).
                        }
                        i == currentWordIdx -> {
                            // Current word: explicit full white to override any inherited dimming.
                            spannable.setSpan(
                                ForegroundColorSpan(Color.WHITE),
                                w.startChar,
                                w.endChar,
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                        }
                        else -> {
                            // Upcoming words: 35% white so they visually recede while the active
                            // line glow draws attention to the word being sung.
                            spannable.setSpan(
                                ForegroundColorSpan(0x59FFFFFF.toInt()), // ~35% white
                                w.startChar,
                                w.endChar,
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                        }
                    }
                }

                binding.lyricLine.text = spannable
            }

            /**
             * (Re-)applies the active-line glow using [ambientColor] at 30% opacity. Called from
             * the lightweight payload path so we don't rebuild spans.
             */
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
}
