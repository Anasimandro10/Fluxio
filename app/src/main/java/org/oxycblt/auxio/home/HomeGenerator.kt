/*
 * Copyright (c) 2024 Fluxio Project
 * HomeGenerator.kt is part of Fluxio.
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
package org.oxycblt.auxio.home

import javax.inject.Inject
import org.oxycblt.auxio.home.folders.Folder
import org.oxycblt.auxio.home.tabs.Tab
import org.oxycblt.auxio.list.ListSettings
import org.oxycblt.auxio.list.adapter.UpdateInstructions
import org.oxycblt.auxio.list.sort.Sort
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.music.MusicType
import org.oxycblt.musikr.Album
import org.oxycblt.musikr.Artist
import org.oxycblt.musikr.Genre
import org.oxycblt.musikr.Playlist
import org.oxycblt.musikr.Song
import timber.log.Timber as L

interface HomeGenerator {
    fun attach()

    fun release()

    fun empty(): Boolean

    fun songs(): List<Song>

    fun albums(): List<Album>

    fun artists(): List<Artist>

    fun genres(): List<Genre>

    fun playlists(): List<Playlist>

    fun folders(): List<Folder>

    fun tabs(): List<MusicType>

    interface Invalidator {
        fun invalidateEmpty() {}

        fun invalidateMusic(type: MusicType, instructions: UpdateInstructions)

        fun invalidateTabs()
    }

    interface Factory {
        fun create(invalidator: Invalidator): HomeGenerator
    }
}

class HomeGeneratorFactoryImpl
@Inject
constructor(
    private val homeSettings: HomeSettings,
    private val listSettings: ListSettings,
    private val musicRepository: MusicRepository,
) : HomeGenerator.Factory {
    override fun create(invalidator: HomeGenerator.Invalidator): HomeGenerator =
        HomeGeneratorImpl(invalidator, homeSettings, listSettings, musicRepository)
}

private class HomeGeneratorImpl(
    private val invalidator: HomeGenerator.Invalidator,
    private val homeSettings: HomeSettings,
    private val listSettings: ListSettings,
    private val musicRepository: MusicRepository,
) : HomeGenerator, HomeSettings.Listener, ListSettings.Listener, MusicRepository.UpdateListener {

    /**
     * Cached folder list. Recomputed only when the library or sort direction changes. Previously
     * folders() called groupBy+sortedWith on every single RecyclerView render.
     */
    private var cachedFolders: List<Folder>? = null

    override fun attach() {
        homeSettings.registerListener(this)
        listSettings.registerListener(this)
        musicRepository.addUpdateListener(this)
    }

    override fun onTabsChanged() {
        invalidator.invalidateTabs()
    }

    override fun onHideCollaboratorsChanged() {
        L.d("Collaborator setting changed, forwarding update")
        invalidator.invalidateMusic(MusicType.ARTISTS, UpdateInstructions.Diff)
    }

    override fun onSongSortChanged() {
        super.onSongSortChanged()
        invalidator.invalidateMusic(MusicType.SONGS, UpdateInstructions.Replace(0))
    }

    override fun onAlbumSortChanged() {
        super.onAlbumSortChanged()
        invalidator.invalidateMusic(MusicType.ALBUMS, UpdateInstructions.Replace(0))
    }

    override fun onArtistSortChanged() {
        super.onArtistSortChanged()
        invalidator.invalidateMusic(MusicType.ARTISTS, UpdateInstructions.Replace(0))
    }

    override fun onGenreSortChanged() {
        super.onGenreSortChanged()
        invalidator.invalidateMusic(MusicType.GENRES, UpdateInstructions.Replace(0))
    }

    override fun onPlaylistSortChanged() {
        super.onPlaylistSortChanged()
        invalidator.invalidateMusic(MusicType.PLAYLISTS, UpdateInstructions.Replace(0))
    }

    override fun onFolderSortChanged() {
        super.onFolderSortChanged()
        // Invalidate the folder cache when sort direction changes.
        cachedFolders = null
        invalidator.invalidateMusic(MusicType.FOLDERS, UpdateInstructions.Replace(0))
    }

    override fun onMusicChanges(changes: MusicRepository.Changes) {
        invalidator.invalidateEmpty()

        val library = musicRepository.library
        if (changes.deviceLibrary && library != null) {
            L.d("Refreshing library")
            // Invalidate the folder cache when the library changes.
            cachedFolders = null
            invalidator.invalidateMusic(MusicType.SONGS, UpdateInstructions.Diff)
            invalidator.invalidateMusic(MusicType.ALBUMS, UpdateInstructions.Diff)
            invalidator.invalidateMusic(MusicType.ARTISTS, UpdateInstructions.Diff)
            invalidator.invalidateMusic(MusicType.GENRES, UpdateInstructions.Diff)
            invalidator.invalidateMusic(MusicType.FOLDERS, UpdateInstructions.Diff)
        }

        if (changes.userLibrary && library != null) {
            L.d("Refreshing playlists")
            invalidator.invalidateMusic(MusicType.PLAYLISTS, UpdateInstructions.Diff)
        }
    }

    override fun release() {
        musicRepository.removeUpdateListener(this)
        listSettings.unregisterListener(this)
        homeSettings.unregisterListener(this)
    }

    override fun empty() = musicRepository.library?.empty() ?: true

    override fun songs() =
        musicRepository.library?.let { listSettings.songSort.songs(it.songs) } ?: emptyList()

    override fun albums() =
        musicRepository.library?.let { listSettings.albumSort.albums(it.albums) } ?: emptyList()

    override fun artists() =
        musicRepository.library?.let { deviceLibrary ->
            val sorted = listSettings.artistSort.artists(deviceLibrary.artists)
            if (homeSettings.shouldHideCollaborators) {
                sorted.filter { it.explicitAlbums.isNotEmpty() }
            } else {
                sorted
            }
        } ?: emptyList()

    override fun genres() =
        musicRepository.library?.let { listSettings.genreSort.genres(it.genres) } ?: emptyList()

    override fun playlists() =
        musicRepository.library?.let { listSettings.playlistSort.playlists(it.playlists) }
            ?: emptyList()

    override fun folders(): List<Folder> {
        // Return the cached result when available — avoids groupBy+sort on every render call.
        cachedFolders?.let {
            return it
        }

        val library = musicRepository.library ?: return emptyList()

        val comparator: Comparator<Folder> =
            when (listSettings.folderSort.direction) {
                Sort.Direction.ASCENDING -> compareBy { it.name.lowercase() }
                Sort.Direction.DESCENDING -> compareByDescending { it.name.lowercase() }
            }

        val result =
            library.songs
                .groupBy { song -> song.path.directory }
                .map { (dirPath, songs) -> Folder(dirPath, songs) }
                .sortedWith(comparator)

        cachedFolders = result
        return result
    }

    override fun tabs() = homeSettings.homeTabs.filterIsInstance<Tab.Visible>().map { it.type }
}
