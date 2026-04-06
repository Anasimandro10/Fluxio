/*
 * Copyright (c) 2025 Fluxio Project
 * DBCache.kt is part of Fluxio.
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
package org.oxycblt.musikr.cache.db

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.oxycblt.musikr.cache.Audio
import org.oxycblt.musikr.cache.Cache
import org.oxycblt.musikr.cache.CacheResult
import org.oxycblt.musikr.cache.CachedFile
import org.oxycblt.musikr.cache.MutableCache
import org.oxycblt.musikr.fs.File
import org.oxycblt.musikr.metadata.Properties
import org.oxycblt.musikr.tag.parse.ParsedTags

/**
 * An immutable [Cache] backed by an internal Room database.
 *
 * Create an instance with [from].
 */
class DBCache private constructor(private val readDao: CacheReadDao) : Cache {
    private var mapping: Map<Uri, CachedFileData>? = null
    private val mappingLock = Mutex()

    /**
     * Eagerly loads all cached songs into memory. Call this before the pipeline starts to avoid
     * blocking extraction coroutines on the first [read] call.
     *
     * If the mapping is already loaded this is a no-op.
     */
    override suspend fun preload() {
        mappingLock.withLock {
            if (mapping == null) {
                mapping = readDao.selectAllSongs().associateBy { it.uri }
            }
        }
    }

    override suspend fun read(file: File): CacheResult {
        val currentMapping =
            mappingLock.withLock {
                mapping ?: readDao.selectAllSongs().associateBy { it.uri }.also { mapping = it }
            }
        val dbSong = currentMapping[file.uri] ?: return CacheResult.Miss(file)
        if (dbSong.modifiedMs != file.modifiedMs) {
            return CacheResult.Stale(file, dbSong.addedMs)
        }
        val song =
            CachedFile(
                file,
                dbSong.mimeType?.let {
                    Audio(
                        Properties(
                            dbSong.mimeType,
                            dbSong.durationMs!!,
                            dbSong.bitrateKbps!!,
                            dbSong.sampleRateHz!!,
                        ),
                        ParsedTags(
                            musicBrainzId = dbSong.musicBrainzId,
                            name = dbSong.name,
                            sortName = dbSong.sortName,
                            durationMs = dbSong.durationMs,
                            track = dbSong.track,
                            disc = dbSong.disc,
                            subtitle = dbSong.subtitle,
                            date = dbSong.date,
                            albumMusicBrainzId = dbSong.albumMusicBrainzId,
                            albumName = dbSong.albumName,
                            albumSortName = dbSong.albumSortName,
                            releaseTypes = dbSong.releaseTypes!!,
                            artistMusicBrainzIds = dbSong.artistMusicBrainzIds!!,
                            artistNames = dbSong.artistNames!!,
                            artistSortNames = dbSong.artistSortNames!!,
                            albumArtistMusicBrainzIds = dbSong.albumArtistMusicBrainzIds!!,
                            albumArtistNames = dbSong.albumArtistNames!!,
                            albumArtistSortNames = dbSong.albumArtistSortNames!!,
                            genreNames = dbSong.genreNames!!,
                            replayGainTrackAdjustment = dbSong.replayGainTrackAdjustment,
                            replayGainAlbumAdjustment = dbSong.replayGainAlbumAdjustment,
                        ),
                        coverId = dbSong.coverId,
                    )
                },
                addedMs = dbSong.addedMs,
            )
        return CacheResult.Hit(song)
    }

    companion object {
        /**
         * Create a new instance of [DBCache] from the given [context].
         *
         * This instance should be a singleton, since it implicitly holds a Room database. As a
         * result, you should only create EITHER a [DBCache] or a [MutableDBCache].
         *
         * @param context The context to use to create the Room database.
         * @return A new instance of [DBCache].
         */
        fun from(context: Context) = from(CacheDatabase.from(context))

        internal fun from(db: CacheDatabase) = DBCache(db.readDao())
    }
}

/**
 * A mutable [Cache] backed by an internal Room database.
 *
 * Create an instance with [from].
 */
class MutableDBCache
private constructor(private val inner: DBCache, private val writeDao: CacheWriteDao) :
    MutableCache {
    override suspend fun preload() = inner.preload()

    override suspend fun read(file: File) = inner.read(file)

    override suspend fun write(cachedFile: CachedFile) {
        writeDao.updateSong(cachedFile.toCachedFileData())
    }

    /**
     * Writes all given [cachedFiles] in a single Room insert, avoiding N individual transactions.
     * Skips the call entirely if the list is empty.
     */
    override suspend fun writeBatch(cachedFiles: List<CachedFile>) {
        if (cachedFiles.isEmpty()) return
        writeDao.updateSongs(cachedFiles.map { it.toCachedFileData() })
    }

    override suspend fun cleanup(excluding: List<CachedFile>) {
        writeDao.deleteExcludingUris(excluding.mapTo(mutableSetOf()) { it.file.uri.toString() })
    }

    /** Converts a [CachedFile] to its Room entity representation. */
    private fun CachedFile.toCachedFileData() =
        CachedFileData(
            uri = file.uri,
            modifiedMs = file.modifiedMs,
            addedMs = addedMs,
            mimeType = audio?.properties?.mimeType,
            durationMs = audio?.properties?.durationMs,
            bitrateKbps = audio?.properties?.bitrateKbps,
            sampleRateHz = audio?.properties?.sampleRateHz,
            musicBrainzId = audio?.tags?.musicBrainzId,
            name = audio?.tags?.name,
            sortName = audio?.tags?.sortName,
            track = audio?.tags?.track,
            disc = audio?.tags?.disc,
            subtitle = audio?.tags?.subtitle,
            date = audio?.tags?.date,
            albumMusicBrainzId = audio?.tags?.albumMusicBrainzId,
            albumName = audio?.tags?.albumName,
            albumSortName = audio?.tags?.albumSortName,
            releaseTypes = audio?.tags?.releaseTypes,
            artistMusicBrainzIds = audio?.tags?.artistMusicBrainzIds,
            artistNames = audio?.tags?.artistNames,
            artistSortNames = audio?.tags?.artistSortNames,
            albumArtistMusicBrainzIds = audio?.tags?.albumArtistMusicBrainzIds,
            albumArtistNames = audio?.tags?.albumArtistNames,
            albumArtistSortNames = audio?.tags?.albumArtistSortNames,
            genreNames = audio?.tags?.genreNames,
            replayGainTrackAdjustment = audio?.tags?.replayGainTrackAdjustment,
            replayGainAlbumAdjustment = audio?.tags?.replayGainAlbumAdjustment,
            coverId = audio?.coverId,
        )

    companion object {
        /**
         * Create a new instance of [MutableDBCache] from the given [context].
         *
         * This instance should be a singleton, since it implicitly holds a Room database. As a
         * result, you should only create EITHER a [DBCache] or a [MutableDBCache].
         *
         * @param context The context to use to create the Room database.
         * @return A new instance of [MutableDBCache].
         */
        fun from(context: Context): MutableDBCache {
            val db = CacheDatabase.from(context)
            return MutableDBCache(DBCache.from(db), db.writeDao())
        }
    }
}
