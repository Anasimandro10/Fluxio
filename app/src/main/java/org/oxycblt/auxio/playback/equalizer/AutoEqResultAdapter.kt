/*
 * Copyright (c) 2026 Fluxio Project
 * AutoEqResultAdapter.kt is part of Fluxio.
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

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.oxycblt.auxio.databinding.ItemAutoeqResultBinding

/**
 * [ListAdapter] that displays [AutoEqResult] items in the AutoEQ browser dialog.
 *
 * @param onItemClick Called when the user taps a headphone profile.
 */
class AutoEqResultAdapter(private val onItemClick: (AutoEqResult) -> Unit) :
    ListAdapter<AutoEqResult, AutoEqResultAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding =
            ItemAutoeqResultBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemAutoeqResultBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(result: AutoEqResult) {
            binding.autoeqResultName.text = result.name
            binding.autoeqResultSource.text = result.source.ifBlank { binding.root.context.getString(R.string.lbl_autoeq_unknown_source) }

            // Adjust margin for better look without source if it's completely empty but we put
            // Unknown source anyway
            binding.root.setOnClickListener { onItemClick(result) }
        }
    }

    private companion object {
        val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<AutoEqResult>() {
                override fun areItemsTheSame(oldItem: AutoEqResult, newItem: AutoEqResult) =
                    oldItem.path == newItem.path

                override fun areContentsTheSame(oldItem: AutoEqResult, newItem: AutoEqResult) =
                    oldItem == newItem
            }
    }
}
