/*
 * Copyright (c) 2023 Fluxio Project
 * CoilModule.kt is part of Fluxio.
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
package org.oxycblt.auxio.image.coil

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.transitionFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import okio.Path.Companion.toOkioPath

@Module
@InstallIn(SingletonComponent::class)
class CoilModule {
    @Singleton
    @Provides
    fun imageLoader(
        @ApplicationContext context: Context,
        coverKeyer: CoverFetcher.Keyer,
        coverFactory: CoverFetcher.Factory,
        galleryKeyer: GalleryComposeFetcher.Keyer,
        galleryFetcherFactory: GalleryComposeFetcher.Factory,
        smatteringKeyer: SmatteringComposeFetcher.Keyer,
        smatteringFetcherFactory: SmatteringComposeFetcher.Factory,
        stackKeyer: StackComposeFetcher.Keyer,
        stackFetcherFactory: StackComposeFetcher.Factory,
    ) =
        ImageLoader.Builder(context)
            .components {
                add(coverKeyer)
                add(coverFactory)
                add(galleryKeyer)
                add(galleryFetcherFactory)
                add(smatteringKeyer)
                add(smatteringFetcherFactory)
                add(stackKeyer)
                add(stackFetcherFactory)
            }
            // Use our own crossfade with error drawable support.
            .transitionFactory(ErrorCrossfadeTransitionFactory())
            // Explicit memory cache at 25% of available RAM.
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
            // Disk cache: persists decoded album art across cold starts.
            // Cover art doesn't change without a library rescan (which invalidates covers),
            // so stale cache is not a concern. 50 MB covers ~500-1000 albums.
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("coil_image_cache").toOkioPath())
                    .maxSizeBytes(50L * 1024 * 1024) // 50 MB
                    .build()
            }
            .build()
}
