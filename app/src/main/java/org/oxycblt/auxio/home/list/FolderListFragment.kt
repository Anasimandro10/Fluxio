/*
 * Copyright (c) 2026 Fluxio Project
 * FolderListFragment.kt is part of Fluxio.
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
package org.oxycblt.auxio.home.list

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentHomeListBinding
import org.oxycblt.auxio.home.HomeViewModel
import org.oxycblt.auxio.home.folders.Folder
import org.oxycblt.auxio.list.SelectableListListener
import org.oxycblt.auxio.list.adapter.SelectionIndicatorAdapter
import org.oxycblt.auxio.list.adapter.SimpleDiffCallback
import org.oxycblt.auxio.list.recycler.FastScrollRecyclerView
import org.oxycblt.auxio.list.recycler.FolderViewHolder
import org.oxycblt.auxio.list.sort.Sort
import org.oxycblt.auxio.music.IndexingState
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collectImmediately

/**
 * A [ViewBindingFragment] that shows a list of [Folder]s in the library.
 */
@AndroidEntryPoint
class FolderListFragment :
    ViewBindingFragment<FragmentHomeListBinding>(),
    FastScrollRecyclerView.PopupProvider,
    FastScrollRecyclerView.Listener,
    SelectableListListener<Folder> {
    private val homeModel: HomeViewModel by activityViewModels()
    private val musicModel: MusicViewModel by activityViewModels()
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val folderAdapter = FolderAdapter(this)

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentHomeListBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentHomeListBinding, savedInstanceState: Bundle?) {
        binding.homeRecycler.apply {
            id = R.id.home_folder_recycler
            adapter = folderAdapter
            popupProvider = this@FolderListFragment
            listener = this@FolderListFragment
        }

        binding.homeNoMusicPlaceholder.apply {
            setImageResource(R.drawable.ic_folder_48)
            contentDescription = getString(R.string.lbl_folders)
        }
        binding.homeNoMusicMsg.text = getString(R.string.lng_empty_folders)
        binding.homeNoMusicAction.setOnClickListener { homeModel.startChooseMusicLocations() }

        collectImmediately(homeModel.folderList, ::updateFolders)
        collectImmediately(homeModel.empty, musicModel.indexingState, ::updateNoMusicIndicator)
    }

    override fun onDestroyBinding(binding: FragmentHomeListBinding) {
        binding.homeRecycler.apply {
            adapter = null
            popupProvider = null
            listener = null
        }
    }

    // --- SelectableListListener ---

    override fun onClick(item: Folder, viewHolder: RecyclerView.ViewHolder) {
        playbackModel.play(item.songs)
    }

    override fun onOpenMenu(item: Folder) {
        // Folders do not have a context menu in this version
    }

    override fun onSelect(item: Folder) {
        // Folders do not support selection in this version
    }

    // --- FastScrollRecyclerView.PopupProvider ---

    override fun getPopupData(pos: Int): FastScrollRecyclerView.PopupProvider.PopupData? {
        val folder = homeModel.folderList.value.getOrNull(pos) ?: return null
        return when (homeModel.folderSort.mode) {
            is Sort.Mode.ByName ->
                FastScrollRecyclerView.PopupProvider.PopupData(
                    folder.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                )
            else -> null
        }
    }

    // --- FastScrollRecyclerView.Listener ---

    override fun onFastScrollingChanged(isFastScrolling: Boolean) {
        homeModel.setFastScrolling(isFastScrolling)
    }

    // --- Private helpers ---

    private fun updateFolders(folders: List<Folder>) {
        folderAdapter.update(folders, homeModel.folderInstructions.consume())
    }

    private fun updateNoMusicIndicator(empty: Boolean, indexingState: IndexingState?) {
        val binding = requireBinding()
        binding.homeRecycler.isInvisible = empty
        binding.homeNoMusic.isInvisible = !empty
        binding.homeNoMusicAction.isVisible =
            indexingState == null || (empty && indexingState is IndexingState.Completed)
    }

    /**
     * A [SelectionIndicatorAdapter] that shows a list of [Folder]s using [FolderViewHolder].
     */
    private class FolderAdapter(private val listener: SelectableListListener<Folder>) :
        SelectionIndicatorAdapter<Folder, FolderViewHolder>(
            object : SimpleDiffCallback<Folder>() {
                override fun areContentsTheSame(oldItem: Folder, newItem: Folder) =
                    oldItem.name == newItem.name && oldItem.songs.size == newItem.songs.size
            }
        ) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            FolderViewHolder.from(parent)

        override fun onBindViewHolder(holder: FolderViewHolder, position: Int) {
            holder.bind(getItem(position), listener)
        }
    }
}
