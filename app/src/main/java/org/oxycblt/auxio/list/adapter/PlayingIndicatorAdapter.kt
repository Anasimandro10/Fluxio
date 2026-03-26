/*
 * Copyright (c) 2021 Fluxio Project
 * PlayingIndicatorAdapter.kt is part of Fluxio.
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
package org.oxycblt.auxio.list.adapter

import android.view.View
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import timber.log.Timber as L

/**
 * A [RecyclerView.Adapter] that supports indicating the playback status of a particular item.
 *
 * @param diffCallback A [DiffUtil.ItemCallback] to compare list updates with.
 * @author Alexander Capehart (OxygenCobalt)
 */
abstract class PlayingIndicatorAdapter<T, VH : RecyclerView.ViewHolder>(
    diffCallback: DiffUtil.ItemCallback<T>
) : FlexibleListAdapter<T, VH>(diffCallback) {
    private var currentItem: T? = null
    private var isPlaying = false

    /**
     * Position index map: item -> adapter position.
     * Replaces O(n) indexOfFirst searches with O(1) lookups on every song change.
     */
    private val positionMap = HashMap<T, Int>()

    override fun onCurrentListChanged(previousList: List<T>, currentList: List<T>) {
        super.onCurrentListChanged(previousList, currentList)
        // Rebuild the position map whenever the list changes.
        positionMap.clear()
        for (i in currentList.indices) {
            positionMap[currentList[i]] = i
        }
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: List<Any>) {
        if (holder is ViewHolder) {
            holder.updatePlayingIndicator(getItem(position) == currentItem, isPlaying)
        }

        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
        }
    }

    /**
     * Update the currently playing item in the list.
     *
     * @param item The [T] currently being played, or null if it is not being played.
     * @param isPlaying Whether playback is ongoing or paused.
     */
    fun setPlaying(item: T?, isPlaying: Boolean) {
        L.d("Updating playing item [old: $currentItem new: $item]")

        var updatedItem = false
        if (currentItem != item) {
            val oldItem = currentItem
            currentItem = item

            // Remove the playing indicator from the old item — O(1) lookup.
            if (oldItem != null) {
                val pos = positionMap[oldItem] ?: -1
                if (pos > -1) {
                    notifyItemChanged(pos, PAYLOAD_PLAYING_INDICATOR_CHANGED)
                } else {
                    L.w("oldItem was not in adapter data")
                }
            }

            // Enable the playing indicator on the new item — O(1) lookup.
            if (item != null) {
                val pos = positionMap[item] ?: -1
                if (pos > -1) {
                    notifyItemChanged(pos, PAYLOAD_PLAYING_INDICATOR_CHANGED)
                } else {
                    L.w("newItem was not in adapter data")
                }
            }

            updatedItem = true
        }

        if (this.isPlaying != isPlaying) {
            this.isPlaying = isPlaying

            if (!updatedItem && item != null) {
                val pos = positionMap[item] ?: -1
                if (pos > -1) {
                    notifyItemChanged(pos, PAYLOAD_PLAYING_INDICATOR_CHANGED)
                } else {
                    L.w("newItem was not in adapter data")
                }
            }
        }
    }

    /** A [RecyclerView.ViewHolder] that can display a playing indicator. */
    abstract class ViewHolder(root: View) : RecyclerView.ViewHolder(root) {
        /**
         * Update the playing indicator within this [RecyclerView.ViewHolder].
         *
         * @param isActive True if this item is playing, false otherwise.
         * @param isPlaying True if playback is ongoing, false if paused.
         */
        abstract fun updatePlayingIndicator(isActive: Boolean, isPlaying: Boolean)
    }

    private companion object {
        val PAYLOAD_PLAYING_INDICATOR_CHANGED = Any()
    }
}
