/*
 * Copyright (c) 2026 Fluxio Project
 * NormalizationWorker.kt is part of Fluxio.
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

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.oxycblt.auxio.music.MusicRepository
import timber.log.Timber as L

/**
 * WorkManager worker that runs the bulk loudness analysis in the background.
 *
 * Survives app closure: even if the user swipes the app away, WorkManager keeps this running as a
 * foreground service (Android 12+) or background task (older versions). If killed (low battery,
 * reboot), the DB already contains results for analyzed songs, so the next execution resumes
 * automatically from where it left off.
 *
 * Uses [EntryPoint] to access Hilt singletons from a non-Hilt context (WorkManager creates workers
 * outside of Hilt's normal injection flow).
 */
class NormalizationWorker(private val context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface NormalizationWorkerEntryPoint {
        fun normalizationScanner(): NormalizationScanner

        fun musicRepository(): MusicRepository
    }

    override suspend fun doWork(): Result {
        L.d("NormalizationWorker: starting")

        val entryPoint =
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                NormalizationWorkerEntryPoint::class.java,
            )

        val scanner = entryPoint.normalizationScanner()
        val musicRepository = entryPoint.musicRepository()

        val songs = musicRepository.library?.songs
        if (songs.isNullOrEmpty()) {
            L.d("NormalizationWorker: no songs in library, finishing")
            return Result.success()
        }

        L.d("NormalizationWorker: starting bulk scan for ${songs.size} songs")
        scanner.startBulkScan(songs)

        // Wait until the scan finishes or this worker is cancelled
        scanner.awaitBulkScanComplete()

        L.d("NormalizationWorker: finished")
        return Result.success()
    }

    // WorkManager calls this to show a foreground notification on Android 12+
    // (required for long-running workers). Returns a minimal notification so the
    // system does not kill the worker while it is active.
    override suspend fun getForegroundInfo(): ForegroundInfo {
        return scanner_foreground_info(context)
    }
}

/** Creates a minimal [ForegroundInfo] for the normalization worker notification. */
private fun scanner_foreground_info(context: Context): ForegroundInfo {
    val channelId = "fluxio_normalization_scan"
    val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        val channel =
            android.app.NotificationChannel(
                channelId,
                "Library analysis",
                android.app.NotificationManager.IMPORTANCE_LOW,
            )
        channel.setShowBadge(false)
        notificationManager.createNotificationChannel(channel)
    }
    val notification =
        androidx.core.app.NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Fluxio")
            .setContentText("Analyzing library loudness…")
            .setOngoing(true)
            .setSilent(true)
            .build()
    return ForegroundInfo(0x1770, notification)
}
