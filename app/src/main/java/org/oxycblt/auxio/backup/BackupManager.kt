/*
 * Copyright (c) 2026 Fluxio Project
 * BackupManager.kt is part of Fluxio.
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
package org.oxycblt.auxio.backup

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject
import org.oxycblt.auxio.stats.PlaybackRecord
import org.oxycblt.auxio.stats.PlaybackRecordDao
import org.oxycblt.auxio.tags.TagAssignment
import org.oxycblt.auxio.tags.TagDao
import org.oxycblt.auxio.tags.TagEntity
import timber.log.Timber as L

/**
 * Handles exporting and importing all Fluxio user data to/from a JSON file.
 *
 * Format version 1 includes: tags, tag_assignments, and playback_records.
 */
@Singleton
class BackupManager
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val tagDao: TagDao,
    private val recordDao: PlaybackRecordDao,
) {
    // ── Export ────────────────────────────────────────────────────────────────

    /**
     * Export all user data to a JSON file at [uri].
     *
     * @return [BackupResult.Success] with the number of items written, or [BackupResult.Error].
     */
    suspend fun export(uri: Uri): BackupResult {
        return try {
            val tags = tagDao.getAllTagsOnce()
            val assignments = tagDao.getAllAssignmentsOnce()
            val records = recordDao.getAllRecordsOnce()

            val root =
                JSONObject().apply {
                    put("version", BACKUP_VERSION)
                    put("app", "Fluxio")
                    put("exportedAt", System.currentTimeMillis())
                    put("tags", tags.tagsToJson())
                    put("tag_assignments", assignments.assignmentsToJson())
                    put("stats", records.recordsToJson())
                }

            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(root.toString(2).toByteArray(Charsets.UTF_8))
            } ?: return BackupResult.Error("Could not open output stream")

            val count = tags.size + assignments.size + records.size
            L.d(
                "Exported backup: ${tags.size} tags, ${assignments.size} assignments, ${records.size} records"
            )
            BackupResult.Success(count)
        } catch (e: Exception) {
            L.e("Export failed: $e")
            BackupResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Estimate the backup size in bytes without writing to disk.
     *
     * @return Estimated size in bytes.
     */
    suspend fun estimatedSizeBytes(): Long {
        return try {
            val tags = tagDao.getAllTagsOnce()
            val assignments = tagDao.getAllAssignmentsOnce()
            val records = recordDao.getAllRecordsOnce()
            // Rough estimate: 80 bytes per tag, 60 bytes per assignment, 120 bytes per record
            val estimate = (tags.size * 80L) + (assignments.size * 60L) + (records.size * 120L)
            estimate.coerceAtLeast(100L)
        } catch (e: Exception) {
            0L
        }
    }

    // ── Import ────────────────────────────────────────────────────────────────

    /**
     * Import user data from a JSON file at [uri], replacing all existing data.
     *
     * @return [BackupResult.Success] with items restored, or [BackupResult.Error].
     */
    suspend fun import(uri: Uri): BackupResult {
        return try {
            val json =
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.readBytes().toString(Charsets.UTF_8)
                } ?: return BackupResult.Error("Could not open input stream")

            val root = JSONObject(json)

            val version = root.optInt("version", -1)
            if (version == -1) {
                return BackupResult.Error("Not a valid Fluxio backup file")
            }
            if (version > BACKUP_VERSION) {
                return BackupResult.Error(
                    "Backup was created with a newer version of Fluxio and cannot be imported"
                )
            }

            // Clear existing data before restoring
            tagDao.deleteAllAssignments()
            tagDao.deleteAllTags()
            recordDao.nukeAll()

            // Restore tags first (assignments depend on tag ids via FK)
            val tagsArray = root.optJSONArray("tags") ?: JSONArray()
            val tagIdMap = mutableMapOf<Long, Long>() // old id -> new id
            for (i in 0 until tagsArray.length()) {
                val obj = tagsArray.getJSONObject(i)
                val oldId = obj.getLong("id")
                val entity =
                    TagEntity(
                        name = obj.getString("name"),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                    )
                val newId = tagDao.insertTag(entity)
                tagIdMap[oldId] = newId
            }

            // Restore assignments using remapped tag ids
            val assignmentsArray = root.optJSONArray("tag_assignments") ?: JSONArray()
            for (i in 0 until assignmentsArray.length()) {
                val obj = assignmentsArray.getJSONObject(i)
                val oldTagId = obj.getLong("tagId")
                val newTagId = tagIdMap[oldTagId] ?: continue // skip orphan
                tagDao.assignTag(
                    TagAssignment(
                        tagId = newTagId,
                        musicUid = obj.getString("musicUid"),
                        musicType = obj.getString("musicType"),
                    )
                )
            }

            // Restore stats
            val statsArray = root.optJSONArray("stats") ?: JSONArray()
            for (i in 0 until statsArray.length()) {
                val obj = statsArray.getJSONObject(i)
                recordDao.insert(
                    PlaybackRecord(
                        songTitle = obj.getString("songTitle"),
                        artistName = obj.getString("artistName"),
                        albumName = obj.getString("albumName"),
                        startedAt = obj.getLong("startedAt"),
                        secondsPlayed = obj.getInt("secondsPlayed"),
                    )
                )
            }

            val count = tagsArray.length() + assignmentsArray.length() + statsArray.length()
            L.d(
                "Imported backup v$version: ${tagsArray.length()} tags, ${assignmentsArray.length()} assignments, ${statsArray.length()} records"
            )
            BackupResult.Success(count)
        } catch (e: Exception) {
            L.e("Import failed: $e")
            BackupResult.Error(e.message ?: "Unknown error")
        }
    }

    // ── JSON helpers ──────────────────────────────────────────────────────────

    private fun List<TagEntity>.tagsToJson(): JSONArray {
        val arr = JSONArray()
        for (tag in this) {
            arr.put(
                JSONObject().apply {
                    put("id", tag.id)
                    put("name", tag.name)
                    put("createdAt", tag.createdAt)
                }
            )
        }
        return arr
    }

    private fun List<TagAssignment>.assignmentsToJson(): JSONArray {
        val arr = JSONArray()
        for (a in this) {
            arr.put(
                JSONObject().apply {
                    put("id", a.id)
                    put("tagId", a.tagId)
                    put("musicUid", a.musicUid)
                    put("musicType", a.musicType)
                }
            )
        }
        return arr
    }

    private fun List<PlaybackRecord>.recordsToJson(): JSONArray {
        val arr = JSONArray()
        for (r in this) {
            arr.put(
                JSONObject().apply {
                    put("songTitle", r.songTitle)
                    put("artistName", r.artistName)
                    put("albumName", r.albumName)
                    put("startedAt", r.startedAt)
                    put("secondsPlayed", r.secondsPlayed)
                }
            )
        }
        return arr
    }

    companion object {
        /** Current backup format version. Increment when the schema changes. */
        const val BACKUP_VERSION = 1

        /** MIME type used when creating the backup file via SAF. */
        const val MIME_TYPE = "application/json"

        /** Default file name suggested when exporting. */
        const val DEFAULT_FILE_NAME = "fluxio_backup.json"
    }
}

/** Result of a backup export or import operation. */
sealed class BackupResult {
    /** Operation succeeded. [count] is the number of items written/restored. */
    data class Success(val count: Int) : BackupResult()

    /** Operation failed. [reason] contains a human-readable description. */
    data class Error(val reason: String) : BackupResult()
}
