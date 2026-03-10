/*
 * Copyright (c) 2024 Fluxio Project
 * StackComposeFetcher.kt is part of Fluxio.
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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import androidx.annotation.ColorInt
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer as CoilKeyer
import coil3.request.Options
import coil3.size.Size
import coil3.size.pxOrElse
import java.io.InputStream
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.buffer
import okio.source
import org.oxycblt.musikr.covers.CoverCollection

data class StackCoverCollection(
    val covers: CoverCollection,
    val cornerRadiusRatio: Float,
    val zOrder: List<Int>,
    @ColorInt val backgroundColor: Int,
)

class StackComposeFetcher
private constructor(
    private val context: Context,
    private val data: StackCoverCollection,
    private val size: Size,
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val streams = data.covers.covers.asFlow().mapNotNull { it.open() }.take(4).toList()
        if (streams.size == 4) {
            return createStack(streams, size).also {
                withContext(Dispatchers.IO) { streams.forEach(InputStream::close) }
            }
        }

        val first = streams.firstOrNull() ?: return null

        withContext(Dispatchers.IO) {
            for (i in 1 until streams.size) {
                streams[i].close()
            }
        }

        return SourceFetchResult(
            source = ImageSource(first.source().buffer(), FileSystem.SYSTEM, null),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    private fun createStack(streams: List<InputStream>, size: Size): FetchResult? {
        val outputSize = size.stackSize()
        val bitmaps = streams.mapNotNull { BitmapFactory.decodeStream(it) }
        if (bitmaps.size != streams.size) {
            return null
        }
        val cornerRadiusPx = outputSize * data.cornerRadiusRatio
        val stackBitmap =
            StackGenerator.generate(
                bitmaps,
                StackGenerator.Config(
                    outputSizePx = outputSize,
                    gapWidthPx = outputSize * ComposeCoverDefaults.GAP_RATIO,
                    cornerRadiusPx = cornerRadiusPx,
                    backgroundColor = data.backgroundColor,
                    zOrder = data.zOrder.validatedZOrder(),
                ),
            )

        return ImageFetchResult(
            image = stackBitmap.toDrawable(context.resources).asImage(),
            isSampled = true,
            dataSource = DataSource.DISK,
        )
    }

    private fun Size.stackSize(): Int {
        val widthPx = width.pxOrElse { 512 }
        val heightPx = height.pxOrElse { 512 }
        return min(widthPx, heightPx).coerceAtLeast(1)
    }

    private object StackGenerator {
        data class Config(
            val outputSizePx: Int,
            val gapWidthPx: Float,
            val cornerRadiusPx: Float,
            @ColorInt val backgroundColor: Int,
            val zOrder: List<Int> = listOf(0, 1, 2, 3),
        )

        fun generate(sourceImages: List<Bitmap>, config: Config): Bitmap {
            if (sourceImages.size != 4) {
                throw IllegalArgumentException("Stack requires exactly 4 images.")
            }

            val result = createBitmap(config.outputSizePx, config.outputSizePx)
            val canvas = Canvas(result)

            canvas.drawColor(config.backgroundColor)

            val gapPaint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = config.backgroundColor
                    style = Paint.Style.FILL
                }
            val imagePaint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    // Use the background color for the mask so anti-aliased edges blend
                    // toward it, avoiding dark/light fringing artifacts
                    color = config.backgroundColor
                }

            val totalSize = config.outputSizePx.toFloat()
            val inset = 0f
            val contentSize = totalSize
            val layoutCardSize =
                (totalSize * ComposeCoverDefaults.CARD_SIZE_PERCENT).coerceAtMost(contentSize)
            val drawCardSize = max(layoutCardSize, contentSize)
            val expansion = drawCardSize - layoutCardSize
            val availableSpan = (contentSize - layoutCardSize).coerceAtLeast(0f)
            val lastIndex = sourceImages.lastIndex
            val maxStep =
                when {
                    lastIndex <= 0 -> 0f
                    lastIndex == 1 -> availableSpan
                    else -> availableSpan / (lastIndex - 1)
                }
            val step = maxStep

            val baseX = inset
            val baseY = totalSize - inset - layoutCardSize

            val normalizedOrder = config.zOrder.validatedZOrder()
            val cornerRadiusPx =
                if (config.cornerRadiusPx > 0f) {
                    min(config.cornerRadiusPx, totalSize * ComposeCoverDefaults.MAX_CORNER_RATIO)
                } else {
                    0f
                }
            val gapWidthPx =
                if (cornerRadiusPx > 0f) {
                    max(
                        config.gapWidthPx,
                        cornerRadiusPx * ComposeCoverDefaults.MIN_GAP_CORNER_RATIO,
                    )
                } else {
                    config.gapWidthPx
                }
            val innerRadius = (cornerRadiusPx - gapWidthPx).coerceAtLeast(0f)

            for (stackIndex in sourceImages.indices) {
                val imageIndex = normalizedOrder.getOrElse(stackIndex) { stackIndex }
                val bitmap = sourceImages[imageIndex]

                val offsetX = baseX + (stackIndex * step)
                val offsetY = baseY - (stackIndex * step)

                val baseRect =
                    RectF(
                        offsetX,
                        offsetY - expansion,
                        offsetX + drawCardSize,
                        offsetY - expansion + drawCardSize,
                    )

                val hasGap = stackIndex > 0 && gapWidthPx > 0f
                val innerRect = RectF(baseRect)
                if (hasGap) {
                    innerRect.left += gapWidthPx
                    innerRect.bottom -= gapWidthPx
                }

                if (hasGap) {
                    val gapPath =
                        Path().apply {
                            addRoundRect(
                                baseRect,
                                floatArrayOf(
                                    cornerRadiusPx,
                                    cornerRadiusPx,
                                    cornerRadiusPx,
                                    cornerRadiusPx,
                                    cornerRadiusPx,
                                    cornerRadiusPx,
                                    cornerRadiusPx,
                                    cornerRadiusPx,
                                ),
                                Path.Direction.CW,
                            )
                        }
                    canvas.drawPath(gapPath, gapPaint)
                }

                if (innerRect.width() > 0 && innerRect.height() > 0) {
                    val savedLayer = canvas.saveLayer(innerRect, null)
                    val topLeftRadius = if (hasGap) innerRadius else cornerRadiusPx
                    val topRightRadius = cornerRadiusPx
                    val bottomRightRadius = if (hasGap) innerRadius else cornerRadiusPx
                    val bottomLeftRadius = if (hasGap) innerRadius else cornerRadiusPx
                    val maskPath =
                        Path().apply {
                            addRoundRect(
                                innerRect,
                                floatArrayOf(
                                    topLeftRadius,
                                    topLeftRadius,
                                    topRightRadius,
                                    topRightRadius,
                                    bottomRightRadius,
                                    bottomRightRadius,
                                    bottomLeftRadius,
                                    bottomLeftRadius,
                                ),
                                Path.Direction.CW,
                            )
                        }
                    canvas.drawPath(maskPath, imagePaint)

                    imagePaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                    drawBitmapCover(canvas, bitmap, innerRect, imagePaint)
                    imagePaint.xfermode = null

                    canvas.restoreToCount(savedLayer)
                }
            }

            return result
        }

        private fun drawBitmapCover(canvas: Canvas, bitmap: Bitmap, dest: RectF, paint: Paint) {
            val bitmapRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
            val destRatio = dest.width() / dest.height()

            val srcRect = Rect(0, 0, bitmap.width, bitmap.height)

            if (bitmapRatio > destRatio) {
                val newWidth = (bitmap.height * destRatio).toInt()
                val xOffset = (bitmap.width - newWidth) / 2
                srcRect.left = xOffset
                srcRect.right = xOffset + newWidth
            } else {
                val newHeight = (bitmap.width / destRatio).toInt()
                val yOffset = (bitmap.height - newHeight) / 2
                srcRect.top = yOffset
                srcRect.bottom = yOffset + newHeight
            }

            canvas.drawBitmap(bitmap, srcRect, dest, paint)
        }
    }

    class Factory @Inject constructor() : Fetcher.Factory<StackCoverCollection> {
        override fun create(
            data: StackCoverCollection,
            options: Options,
            imageLoader: ImageLoader,
        ) = StackComposeFetcher(options.context, data, options.size)
    }

    class Keyer @Inject constructor() : CoilKeyer<StackCoverCollection> {
        override fun key(data: StackCoverCollection, options: Options): String {
            val config =
                "${data.cornerRadiusRatio}.${data.zOrder.joinToString(".")}.${data.backgroundColor}"
            return "s:${data.covers.hashCode()}.${options.size.width}.${options.size.height}.$config"
        }
    }
}
