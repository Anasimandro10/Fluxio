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

import android.os.Bundle
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
    }

    private fun updateLyrics(lines: List<LrcLine>) {
        val binding = requireBinding()
        binding.lyricsTabRecycler.isVisible = lines.isNotEmpty()
        binding.lyricsTabEmpty.isVisible = lines.isEmpty()
        lyricsAdapter?.submitList(lines)
    }

    private fun updateIsSynced(isSynced: Boolean) {
        lyricsAdapter?.setIsSynced(isSynced)
    }

    private fun updateCurrentLine(index: Int) {
        val adapter = lyricsAdapter ?: return
        val previousIndex = adapter.activeIndex
        adapter.setActiveIndex(index)
        if (index >= 0 && index != previousIndex) {
            requireBinding().lyricsTabRecycler.smoothScrollToPosition(index)
        }
    }

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
                if (!isSynced) {
                    binding.lyricLine.alpha = 1f
                    binding.lyricLine.textSize = 20f
                    binding.lyricLine.setShadowLayer(0f, 0f, 0f, 0)
                } else if (isActive) {
                    binding.lyricLine.alpha = 1f
                    binding.lyricLine.textSize = 22f
                    // 30% opacity glow (0x4DFFFFFF)
                    binding.lyricLine.setShadowLayer(8f, 0f, 0f, 0x4DFFFFFF)
                } else {
                    binding.lyricLine.alpha = 0.35f
                    binding.lyricLine.textSize = 20f
                    binding.lyricLine.setShadowLayer(0f, 0f, 0f, 0)
                }
                binding.lyricLine.isSelected = isActive
            }
        }

        private object LrcLineDiff : DiffUtil.ItemCallback<LrcLine>() {
            override fun areItemsTheSame(old: LrcLine, new: LrcLine) =
                old.startMs == new.startMs && old.text == new.text

            override fun areContentsTheSame(old: LrcLine, new: LrcLine) = old == new
        }
    }
}
