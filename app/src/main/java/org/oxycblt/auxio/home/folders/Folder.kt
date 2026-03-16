/*
 * Copyright (c) 2026 Fluxio Project
 * Folder.kt is part of Fluxio.
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
package org.oxycblt.auxio.home.folders

import android.content.Context
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.fs.Path

/**
 * Represents a folder (directory) that contains one or more songs. Derived from [Song.path] at
 * runtime — not stored in any database.
 *
 * @param path The [Path] of this folder.
 * @param songs The [Song]s contained directly in this folder.
 */
data class Folder(val path: Path, val songs: List<Song>) {
    /**
     * The display name of the folder (last component of the path). Falls back to the full path
     * string if the last component is not available.
     */
    val name: String
        get() = path.name ?: path.components.toString()

    /**
     * Resolves this folder's full path as a human-readable string.
     *
     * @param context [Context] needed to resolve volume names.
     */
    fun resolvePath(context: Context): String = path.resolve(context)
}
