/*
 * Copyright (c) 2026 Fluxio Project
 * NormalizationDatabase.kt is part of Fluxio.
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
package org.oxycblt.auxio.playback.normalizer

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

/**
 * Cached K-weighted loudness measurement for a single song (ITU-R BS.1770-4).
 *
 * Stores [measuredRmsDb] (level in dBFS) rather than the computed gain. This means the cache stays
 * valid when the user changes the target loudness level — only the gain needs to be recomputed, not
 * the audio measurement.
 */
@Entity(tableName = "normalization_gains")
data class NormalizationRecord(
    @PrimaryKey val songUid: String,
    val measuredRmsDb: Float,
    val measuredAt: Long,
)

@Dao
interface NormalizationDao {
    @Query("SELECT * FROM normalization_gains WHERE songUid = :uid LIMIT 1")
    suspend fun getForSong(uid: String): NormalizationRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(record: NormalizationRecord)

    /**
     * Batch insert: writes multiple records in a single transaction. Called by
     * [NormalizationScanner] every [NormalizationScanner.BATCH_SIZE] songs to minimize the number
     * of DB round-trips during bulk library scans.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(records: List<NormalizationRecord>)

    @Query("DELETE FROM normalization_gains") suspend fun deleteAll()

    @Query("SELECT songUid FROM normalization_gains WHERE songUid IN (:uids)")
    suspend fun getUidsIn(uids: List<String>): List<String>
}

@Database(entities = [NormalizationRecord::class], version = 1, exportSchema = false)
abstract class NormalizationDatabase : RoomDatabase() {
    abstract fun normalizationDao(): NormalizationDao
}
