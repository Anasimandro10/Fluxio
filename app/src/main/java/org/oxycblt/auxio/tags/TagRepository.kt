/*
 * Copyright (c) 2026 Fluxio Project
 * TagRepository.kt is part of Fluxio.
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

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * Provides all business logic for the user-defined tags feature.
 *
 * Songs and albums are identified by the string representation of their
 * [org.oxycblt.musikr.Music.UID]. The repository does not hold references to music objects to stay
 * free of library-reload cycles.
 */
@Singleton
class TagRepository @Inject constructor(private val dao: TagDao) {

    // ── Read ─────────────────────────────────────────────────────────────────

    /** Flow of all tags sorted alphabetically. Emits on every change. */
    fun getAllTags(): Flow<List<TagEntity>> = dao.getAllTags()

    /** Flow of tag ids currently assigned to the given music item. */
    fun getTagIdsForItem(musicUid: String): Flow<List<Long>> = dao.getTagIdsForItem(musicUid)

    // ── Write ────────────────────────────────────────────────────────────────

    /**
     * Create a new tag with the given [name] and return its generated id.
     *
     * Returns -1 if the name is blank or a tag with that exact name already exists.
     */
    suspend fun createTag(name: String): Long {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return -1L
        return dao.insertTag(TagEntity(name = trimmed))
    }

    /** Delete a tag and all its assignments (CASCADE handles the assignments). */
    suspend fun deleteTag(tagId: Long) {
        dao.deleteTag(tagId)
    }

    /**
     * Set the complete tag list for a music item in one atomic operation.
     *
     * Computes the diff between [currentTagIds] and [newTagIds], then applies only additions and
     * removals.
     *
     * @param musicUid UID string of the song or album.
     * @param musicType "song" or "album".
     * @param currentTagIds The ids currently assigned to this item (DB snapshot).
     * @param newTagIds The ids the user has chosen in the dialog.
     */
    suspend fun syncTagsForItem(
        musicUid: String,
        musicType: String,
        currentTagIds: List<Long>,
        newTagIds: List<Long>,
    ) {
        val current = currentTagIds.toSet()
        val next = newTagIds.toSet()
        for (id in next - current) {
            dao.assignTag(TagAssignment(tagId = id, musicUid = musicUid, musicType = musicType))
        }
        for (id in current - next) {
            dao.removeAssignment(id, musicUid)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Return all tags as a plain list (for dialog population). */
    suspend fun getAllTagsOnce(): List<TagEntity> = dao.getAllTagsOnce()

    /** Return the tag ids assigned to [musicUid] as a plain list (for dialog population). */
    suspend fun getTagIdsForItemOnce(musicUid: String): List<Long> =
        dao.getTagIdsForItemOnce(musicUid)
}
