/*
 * Copyright (c) 2026 Fluxio Project
 * LoudnessAnalyzer.kt is part of Fluxio.
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
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * Decodes an audio file and measures its ITU-R BS.1770-4 K-weighted loudness.
 *
 * ## Adaptive early termination
 *
 * Instead of a fixed analysis cap, this analyzer uses RMS variance tracking to decide when the
 * measurement has converged to a stable value:
 * - The running K-weighted RMS is sampled into 10-second windows.
 * - When the last [CONVERGENCE_WINDOW_COUNT] windows (default 3 = 30 seconds) have less than
 *   [CONVERGENCE_THRESHOLD_DB] dB of peak-to-peak variation, the measurement has converged and
 *   analysis stops early.
 * - For typical pop/rock/electronic music: converges in 30–60 seconds.
 * - For dynamic music (classical, jazz, progressive rock): high window variance keeps the analysis
 *   running up to the [MAX_ANALYSIS_SECONDS] cap (3 minutes) for maximum accuracy — exactly the
 *   content that needs it most.
 *
 * This simultaneously improves both precision (dynamic songs get full treatment) and speed (simple
 * songs finish 4–6x faster than the fixed-cap approach).
 */
@Singleton
class LoudnessAnalyzer @Inject constructor(@ApplicationContext private val context: Context) {

    /**
     * Decodes [song] and returns the K-weighted RMS in dBFS, or null on failure. Must be called
     * from Dispatchers.IO. Respects coroutine cancellation.
     */
    suspend fun analyze(song: Song): Float? = decodeAndMeasure(song.uri)

    // ─── Core decode + measure ────────────────────────────────────────────────

    private suspend fun decodeAndMeasure(uri: Uri): Float? {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)

        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)

            val trackIndex =
                (0 until extractor.trackCount).firstOrNull { i ->
                    extractor
                        .getTrackFormat(i)
                        .getString(MediaFormat.KEY_MIME)
                        ?.startsWith("audio/") == true
                } ?: return null

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val sampleRate = format.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44100)
            val channelCount =
                format.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 2).coerceIn(1, 8)

            val (kw1, kw2) = kWeightingCoeffs(sampleRate)
            val filterState = Array(channelCount) { Array(2) { FloatArray(4) } }

            // Hard cap: never analyze more than MAX_ANALYSIS_SECONDS of audio.
            val maxSamples = MAX_ANALYSIS_SECONDS.toLong() * sampleRate * channelCount

            // Samples per 10-second convergence window.
            val windowSize = 10L * sampleRate * channelCount

            val codec =
                try {
                    MediaCodec.createDecoderByType(mime)
                } catch (e: Exception) {
                    L.w("LoudnessAnalyzer: no decoder for $mime — ${e.message}")
                    return null
                }

            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            codec.configure(format, null, null, 0)
            codec.start()

            // ── Accumulators ──────────────────────────────────────────────────

            var globalSumSq = 0.0 // cumulative K-weighted energy across all samples
            var globalSamples = 0L // cumulative sample count

            var windowSumSq = 0.0 // K-weighted energy in the current 10s window
            var windowSamples = 0L // sample count in the current 10s window

            // Circular buffer of last CONVERGENCE_WINDOW_COUNT window RMS values (in dBFS).
            // We track dBFS directly (not linear RMS) because dB is perceptually linear —
            // 1 dB variation is equally significant at any loudness level.
            val windowRmsDb = FloatArray(CONVERGENCE_WINDOW_COUNT) { Float.NaN }
            var windowIndex = 0
            var windowsFilled = 0

            var inputDone = false
            val bufferInfo = MediaCodec.BufferInfo()
            val chunk = ShortArray(32768)

            try {
                while (currentCoroutineContext().isActive) {
                    if (!inputDone) {
                        val inputIdx = codec.dequeueInputBuffer(10_000L)
                        if (inputIdx >= 0) {
                            val inputBuf = codec.getInputBuffer(inputIdx) ?: break
                            val size = extractor.readSampleData(inputBuf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(
                                    inputIdx,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inputIdx, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outputIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                    when {
                        outputIdx >= 0 -> {
                            val outputBuf = codec.getOutputBuffer(outputIdx)
                            if (outputBuf != null && bufferInfo.size > 0) {
                                val shorts =
                                    outputBuf.order(ByteOrder.nativeOrder()).asShortBuffer()
                                var ch = 0
                                while (shorts.hasRemaining()) {
                                    val count = minOf(shorts.remaining(), chunk.size)
                                    shorts.get(chunk, 0, count)
                                    for (j in 0 until count) {
                                        val f =
                                            applyKWeighting(
                                                chunk[j].toFloat(),
                                                ch,
                                                filterState,
                                                kw1,
                                                kw2,
                                            )
                                        val fSq = f * f
                                        globalSumSq += fSq
                                        globalSamples++
                                        windowSumSq += fSq
                                        windowSamples++
                                        if (++ch == channelCount) ch = 0

                                        // End of 10-second window — check convergence
                                        if (windowSamples >= windowSize) {
                                            val windowRms = sqrt(windowSumSq / windowSamples)
                                            val windowDb =
                                                if (windowRms > 1.0)
                                                    (20.0 * log10(windowRms / 32768.0)).toFloat()
                                                else Float.NaN

                                            windowRmsDb[windowIndex] = windowDb
                                            windowIndex =
                                                (windowIndex + 1) % CONVERGENCE_WINDOW_COUNT
                                            if (windowsFilled < CONVERGENCE_WINDOW_COUNT)
                                                windowsFilled++

                                            windowSumSq = 0.0
                                            windowSamples = 0L

                                            // Convergence check: enough windows + small variation
                                            if (windowsFilled >= CONVERGENCE_WINDOW_COUNT) {
                                                val valid = windowRmsDb.filter { !it.isNaN() }
                                                if (valid.size == CONVERGENCE_WINDOW_COUNT) {
                                                    val spread = valid.max() - valid.min()
                                                    if (spread < CONVERGENCE_THRESHOLD_DB) {
                                                        // Converged — stop early
                                                        L.d(
                                                            "LoudnessAnalyzer: converged " +
                                                                "spread=${spread}dB " +
                                                                "at ${globalSamples / (sampleRate * channelCount)}s"
                                                        )
                                                        // Break out of the chunk loop
                                                        inputDone = true
                                                        break
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    // Propagate early exit from inner loop
                                    if (inputDone && windowSamples == 0L) break
                                }
                            }
                            codec.releaseOutputBuffer(outputIdx, false)

                            if (globalSamples >= maxSamples) break
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
                                break
                            if (inputDone && windowSamples == 0L) break
                        }
                        outputIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            /* ignore */
                        }
                    }
                }
            } finally {
                codec.stop()
                codec.release()
            }

            if (globalSamples == 0L) return null
            val rms = sqrt(globalSumSq / globalSamples.toDouble())
            if (rms < 100.0) return null // silence guard: < -50 dBFS

            (20.0 * log10(rms / 32768.0)).toFloat()
        } catch (e: Exception) {
            L.w("LoudnessAnalyzer: failed for $uri — ${e.message}")
            null
        } finally {
            extractor.release()
        }
    }

    // ─── K-weighting (ITU-R BS.1770-4) ───────────────────────────────────────

    private fun applyKWeighting(
        x: Float,
        ch: Int,
        state: Array<Array<FloatArray>>,
        kw1: FloatArray,
        kw2: FloatArray,
    ): Float = applyBiquad(applyBiquad(x, state[ch][0], kw1), state[ch][1], kw2)

    private fun applyBiquad(x: Float, s: FloatArray, c: FloatArray): Float {
        val y = c[0] * x + c[1] * s[0] + c[2] * s[1] - c[3] * s[2] - c[4] * s[3]
        s[1] = s[0]
        s[0] = x
        s[3] = s[2]
        s[2] = y
        return y
    }

    private fun kWeightingCoeffs(sr: Int): Pair<FloatArray, FloatArray> {
        if (sr == 44100) return Pair(KW_STAGE1_44100, KW_STAGE2_44100)
        if (sr == 48000) return Pair(KW_STAGE1_48000, KW_STAGE2_48000)
        val fs = sr.toDouble()
        val fc1 = 1681.974450955533
        val g1 = 3.999843853973347
        val q1 = 0.7071752369554196
        val vh1 = 10.0.pow(g1 / 20.0)
        val vb1 = 10.0.pow(g1 / 40.0)
        val k1 = tan(PI * fc1 / fs)
        val d1 = vh1 + vb1 * k1 / q1 + k1 * k1
        val d1a = 1.0 + k1 / q1 + k1 * k1
        val s1 =
            floatArrayOf(
                ((vh1 + vb1 * k1 / q1 + k1 * k1) / d1).toFloat(),
                (2.0 * (k1 * k1 - vh1) / d1).toFloat(),
                ((vh1 - vb1 * k1 / q1 + k1 * k1) / d1).toFloat(),
                (2.0 * (k1 * k1 - 1.0) / d1a).toFloat(),
                ((1.0 - k1 / q1 + k1 * k1) / d1a).toFloat(),
            )
        val fc2 = 38.13547087602444
        val q2 = 0.5003270373238773
        val k2 = tan(PI * fc2 / fs)
        val d2 = k2 * k2 + k2 / q2 + 1.0
        val s2 =
            floatArrayOf(
                (1.0 / d2).toFloat(),
                (-2.0 / d2).toFloat(),
                (1.0 / d2).toFloat(),
                (2.0 * (k2 * k2 - 1.0) / d2).toFloat(),
                ((k2 * k2 - k2 / q2 + 1.0) / d2).toFloat(),
            )
        return Pair(s1, s2)
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, default: Int) =
        if (containsKey(key)) getInteger(key) else default

    private companion object {
        // Hard cap: never analyze more than this many seconds of audio.
        const val MAX_ANALYSIS_SECONDS = 180 // 3 minutes

        // Number of 10-second windows to use for convergence detection.
        // 3 windows = 30 seconds of history required before checking convergence.
        const val CONVERGENCE_WINDOW_COUNT = 3

        // Peak-to-peak dB spread across the last CONVERGENCE_WINDOW_COUNT windows
        // below which the measurement is considered converged.
        //
        // 1.0 dB is approximately the JND (Just Noticeable Difference) for loudness.
        // If consecutive 10-second windows vary by less than 1 dB, the overall RMS
        // will not change meaningfully with more data. Dynamic content (classical,
        // jazz, prog rock) regularly exceeds this threshold and gets full analysis.
        const val CONVERGENCE_THRESHOLD_DB = 1.0f

        val KW_STAGE1_44100 =
            floatArrayOf(1.53512486f, -2.69169619f, 1.19839281f, -1.69065929f, 0.73248077f)
        val KW_STAGE2_44100 = floatArrayOf(1.0f, -2.0f, 1.0f, -1.99004745f, 0.99007225f)
        val KW_STAGE1_48000 =
            floatArrayOf(1.53084123f, -2.65097995f, 1.16907868f, -1.66365511f, 0.71259543f)
        val KW_STAGE2_48000 = floatArrayOf(1.0f, -2.0f, 1.0f, -1.99219848f, 0.99225010f)
    }
}
