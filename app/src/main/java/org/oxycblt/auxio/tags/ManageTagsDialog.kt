/*
 * Copyright (c) 2026 Fluxio Project
 * ManageTagsDialog.kt is part of Fluxio.
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
 * along with this program.  If not, see .
 */
package org.oxycblt.auxio.tags

import android.app.Dialog
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R

/**
 * Dialog for managing tags on a song or album.
 *
 * Open it via [ManageTagsDialog.newInstance], passing the UID string and type.
 */
@AndroidEntryPoint
class ManageTagsDialog : DialogFragment() {

    private val tagModel: TagViewModel by activityViewModels()

    companion object {
        private const val ARG_UID = "uid"
        private const val ARG_TYPE = "type"

        /**
         * Create a new instance targeting [musicUid].
         *
         * @param musicUid String UID of the song or album.
         * @param musicType "song" or "album".
         */
        fun newInstance(musicUid: String, musicType: String): ManageTagsDialog {
            return ManageTagsDialog().apply {
                arguments =
                    Bundle().apply {
                        putString(ARG_UID, musicUid)
                        putString(ARG_TYPE, musicType)
                    }
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val uid = requireArguments().getString(ARG_UID, "")
        val type = requireArguments().getString(ARG_TYPE, "song")

        tagModel.loadForItem(uid, type)

        val ctx = requireContext()

        // Root layout
        val root =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 32, 48, 24)
            }

        // "New tag" input row
        val inputRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val tagInput =
            EditText(ctx).apply {
                hint = ctx.getString(R.string.hint_new_tag)
                layoutParams =
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                maxLines = 1
            }
        val addBtn = Button(ctx).apply { text = ctx.getString(R.string.lbl_add) }
        inputRow.addView(tagInput)
        inputRow.addView(addBtn)
        root.addView(inputRow)

        // Scrollable tag list
        val scroll =
            ScrollView(ctx).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
            }
        val tagList = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 0)
        }
        scroll.addView(tagList)
        root.addView(scroll)

        // Populate tag checkboxes whenever the tag list or selection changes
        lifecycleScope.launch {
            tagModel.allTags.collect { tags ->
                tagList.removeAllViews()
                val selected = tagModel.selectedTagIds.value
                for (tag in tags) {
                    val row =
                        LinearLayout(ctx).apply {
                            orientation = LinearLayout.HORIZONTAL
                            setPadding(0, 8, 0, 8)
                        }
                    val check =
                        CheckBox(ctx).apply {
                            text = tag.name
                            isChecked = tag.id in selected
                            layoutParams =
                                LinearLayout.LayoutParams(
                                    0,
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                    1f,
                                )
                            setOnCheckedChangeListener { _, _ -> tagModel.toggleTag(tag.id) }
                        }
                    val del =
                        TextView(ctx).apply {
                            text = "✕"
                            setPadding(16, 0, 0, 0)
                            setOnClickListener { tagModel.deleteTag(tag.id) }
                        }
                    row.addView(check)
                    row.addView(del)
                    tagList.addView(row)
                }
            }
        }

        // Refresh checkbox states when the selection changes (e.g. after toggle)
        lifecycleScope.launch {
            tagModel.selectedTagIds.collect { selected ->
                val tags = tagModel.allTags.value
                for (i in 0 until tagList.childCount) {
                    val row = tagList.getChildAt(i) as? LinearLayout ?: continue
                    val check = row.getChildAt(0) as? CheckBox ?: continue
                    val tag = tags.getOrNull(i) ?: continue
                    check.isChecked = tag.id in selected
                }
            }
        }

        // Add button
        addBtn.setOnClickListener {
            val name = tagInput.text.toString().trim()
            if (name.isBlank()) {
                Toast.makeText(ctx, ctx.getString(R.string.err_tag_empty), Toast.LENGTH_SHORT)
                    .show()
            } else {
                tagModel.createAndSelectTag(name)
                tagInput.setText("")
            }
        }

        // Dismiss when saved
        lifecycleScope.launch {
            tagModel.saved.collect { done ->
                if (done) {
                    tagModel.onSaveHandled()
                    dismiss()
                }
            }
        }

        return AlertDialog.Builder(ctx)
            .setTitle(
                if (type == "album") ctx.getString(R.string.lbl_manage_tags_album)
                else ctx.getString(R.string.lbl_manage_tags_song)
            )
            .setView(root)
            .setPositiveButton(android.R.string.ok) { _, _ -> tagModel.save() }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }
}