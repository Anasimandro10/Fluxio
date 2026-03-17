/*
 * Copyright (c) 2026 Fluxio Project
 * TagViewModel.kt is part of Fluxio.
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
package org.oxycblt.auxio.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Manages UI state for the "Manage tags" dialog.
 *
 * The dialog shows all existing tags and lets the user toggle which ones are
 * assigned to the current song or album. The user can also create new tags inline.
 */
@HiltViewModel
class TagViewModel @Inject constructor(private val repo: TagRepository) : ViewModel() {

    /** All tags that exist in the database, sorted alphabetically. */
    private val _allTags = MutableStateFlow<List<TagEntity>>(emptyList())
    val allTags: StateFlow<List<TagEntity>> = _allTags

    /** Tag ids currently selected in the dialog (before the user saves). */
    private val _selectedTagIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedTagIds: StateFlow<Set<Long>> = _selectedTagIds

    /** Set when the dialog has been saved — the Fragment observes this to dismiss. */
    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved

    /** UID of the item being tagged (song or album). */
    private var currentUid: String = ""

    /** "song" or "album". */
    private var currentType: String = ""

    /** Load all tags and the current assignments for a given item. Call once when opening the dialog. */
    fun loadForItem(musicUid: String, musicType: String) {
        currentUid = musicUid
        currentType = musicType
        viewModelScope.launch {
            _allTags.value = repo.getAllTagsOnce()
            _selectedTagIds.value = repo.getTagIdsForItemOnce(musicUid).toSet()
        }
    }

    /** Toggle a tag on/off in the in-memory selection (not saved yet). */
    fun toggleTag(tagId: Long) {
        val current = _selectedTagIds.value.toMutableSet()
        if (tagId in current) current.remove(tagId) else current.add(tagId)
        _selectedTagIds.value = current
    }

    /**
     * Create a new tag with [name], add it to the in-memory list and pre-select it.
     * No-ops if the name is blank.
     */
    fun createAndSelectTag(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val newId = repo.createTag(name)
            if (newId > 0) {
                _allTags.value = repo.getAllTagsOnce()
                _selectedTagIds.value = _selectedTagIds.value + newId
            }
        }
    }

    /** Delete a tag (and all its assignments across all songs/albums). */
    fun deleteTag(tagId: Long) {
        viewModelScope.launch {
            repo.deleteTag(tagId)
            _allTags.value = repo.getAllTagsOnce()
            _selectedTagIds.value = _selectedTagIds.value - tagId
        }
    }

    /**
     * Persist the current selection to the database.
     * Computes the diff and applies only additions/removals.
     */
    fun save(previousIds: List<Long>) {
        viewModelScope.launch {
            repo.syncTagsForItem(
                musicUid = currentUid,
                musicType = currentType,
                currentTagIds = previousIds,
                newTagIds = _selectedTagIds.value.toList(),
            )
            _saved.value = true
        }
    }

    /** Reset the saved flag after the Fragment has handled it. */
    fun onSaveHandled() {
        _saved.value = false
    }
}