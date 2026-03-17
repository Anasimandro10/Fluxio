/*
 * Copyright (c) 2026 Fluxio Project
 * TagDatabase.kt is part of Fluxio.
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

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Room database that stores user-defined tags and their assignments to songs or albums.
 *
 * - [TagEntity]: the tag definition (id + name).
 * - [TagAssignment]: links a tag to a song or album identified by its UID string.
 */
@Database(
    entities = [TagEntity::class, TagAssignment::class],
    version = 1,
    exportSchema = false,
)
abstract class TagDatabase : RoomDatabase() {
    abstract fun tagDao(): TagDao
}

/**
 * A user-defined tag label.
 *
 * @param id Auto-generated unique identifier.
 * @param name The human-readable label chosen by the user (e.g. "Gym", "Study").
 * @param createdAt Unix timestamp in milliseconds when this tag was created.
 */
@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Assignment of a [TagEntity] to a music item identified by its UID string.
 *
 * The [musicUid] is the string representation of [org.oxycblt.musikr.Music.UID].
 * The [musicType] is either "song" or "album" — stored as a plain string for simplicity.
 *
 * @param id Auto-generated unique identifier.
 * @param tagId Foreign key referencing [TagEntity.id].
 * @param musicUid UID string of the song or album.
 * @param musicType "song" or "album".
 */
@Entity(
    tableName = "tag_assignments",
    foreignKeys =
        [
            ForeignKey(
                entity = TagEntity::class,
                parentColumns = ["id"],
                childColumns = ["tagId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index("tagId"), Index("musicUid")],
)
data class TagAssignment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tagId: Long,
    val musicUid: String,
    val musicType: String,
)

/** Provides all data-access operations for the tags feature. */
@Dao
interface TagDao {

    // ── Tag definitions ─────────────────────────────────────────────────────

    /** Insert a new tag and return its generated id. */
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTag(tag: TagEntity): Long

    /** Delete a tag by id (assignments are deleted automatically via CASCADE). */
    @Query("DELETE FROM tags WHERE id = :tagId") suspend fun deleteTag(tagId: Long)

    /** Return all tags ordered alphabetically. */
    @Query("SELECT * FROM tags ORDER BY name ASC") fun getAllTags(): Flow>

    /** Return all tags as a one-shot list (for dialogs). */
    @Query("SELECT * FROM tags ORDER BY name ASC") suspend fun getAllTagsOnce(): List

    // ── Assignments ──────────────────────────────────────────────────────────

    /** Assign a tag to a music item. Silently ignores duplicates. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun assignTag(assignment: TagAssignment)

    /** Remove a tag assignment from a music item. */
    @Query(
        "DELETE FROM tag_assignments WHERE tagId = :tagId AND musicUid = :musicUid"
    )
    suspend fun removeAssignment(tagId: Long, musicUid: String)

    /** Return the ids of tags assigned to a specific music item. */
    @Query("SELECT tagId FROM tag_assignments WHERE musicUid = :musicUid")
    fun getTagIdsForItem(musicUid: String): Flow>

    /** Return the ids of tags assigned to a specific music item (one-shot, for dialogs). */
    @Query("SELECT tagId FROM tag_assignments WHERE musicUid = :musicUid")
    suspend fun getTagIdsForItemOnce(musicUid: String): List

    /** Return all music UIDs assigned to a given tag. */
    @Query("SELECT musicUid FROM tag_assignments WHERE tagId = :tagId")
    suspend fun getUidsForTag(tagId: Long): List

    /** Remove all assignments for a given music UID (e.g. when the item is no longer found). */
    @Query("DELETE FROM tag_assignments WHERE musicUid = :musicUid")
    suspend fun removeAllAssignmentsForItem(musicUid: String)
}