/*
 * Copyright (c) 2026 Fluxio Project
 * GalleryComposeFetcher.kt is part of Fluxio.
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
 * along with this program.  If not, see .
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

data class GalleryCoverCollection(
    val covers: CoverCollection,
    val cornerRadiusRatio: Float,
    val zOrder: List,
    @ColorInt val backgroundColor: Int,
)

class GalleryComposeFetcher
private constructor(
    private val context: Context,
    private val data: GalleryCoverCollection,
    private val size: Size,
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val streams = data.covers.covers.asFlow().mapNotNull { it.open() }.take(4).toList()
        if (streams.size == 4) {
            return createCollage(streams, size).also {
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

    private fun createCollage(streams: List, size: Size): FetchResult? {
        val outputSize = size.collageSize()
        // Calculate inSampleSize from outputSize — avoids decoding full-res JPEGs into RAM.
        // canvas.drawBitmap scales to dest regardless, so visual quality is identical.
        var inSampleSize = 1
        while (outputSize * inSampleSize * 2 <= 1024) inSampleSize *= 2
        val bitmapOptions = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
        val bitmaps = streams.mapNotNull { BitmapFactory.decodeStream(it, null, bitmapOptions) }
        if (bitmaps.size != streams.size) {
            return null
        }
        val cornerRadiusPx = outputSize * data.cornerRadiusRatio
        val collageBitmap =
            CollageGenerator.generate(
                bitmaps,
                CollageGenerator.Config(
                    outputSizePx = outputSize,
                    gapWidthPx = outputSize * ComposeCoverDefaults.GAP_RATIO,
                    cornerRadiusPx = cornerRadiusPx,
                    backgroundColor = data.backgroundColor,
                    zOrder = data.zOrder.validatedZOrder(),
                ),
            )

        return ImageFetchResult(
            image = collageBitmap.toDrawable(context.resources).asImage(),
            isSampled = true,
            dataSource = DataSource.DISK,
        )
    }

    private fun Size.collageSize(): Int {
        val widthPx = width.pxOrElse { 512 }
        val heightPx = height.pxOrElse { 512 }
        return min(widthPx, heightPx).coerceAtLeast(1)
    }

    private object CollageGenerator {
        data class Config(
            val outputSizePx: Int,
            val gapWidthPx: Float,
            val cornerRadiusPx: Float,
            @ColorInt val backgroundColor: Int,
            val zOrder: List = listOf(0, 1, 2, 3),
        )

        fun generate(sourceImages: List, config: Config): Bitmap {
            if (sourceImages.size != 4) {
                throw IllegalArgumentException("Collage requires exactly 4 images.")
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
            val cardSize = totalSize * ComposeCoverDefaults.CARD_SIZE_PERCENT
            val inset = 0f
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

            val p0 = RectF(inset, inset, inset + cardSize, inset + cardSize)
            val p1 = RectF(totalSize - cardSize - inset, inset, totalSize - inset, inset + cardSize)
            val p2 = RectF(inset, totalSize - cardSize - inset, inset + cardSize, totalSize - inset)
            val p3 =
                RectF(
                    totalSize - cardSize - inset,
                    totalSize - cardSize - inset,
                    totalSize - inset,
                    totalSize - inset,
                )

            val positions = listOf(p0, p1, p2, p3)
            val tiles =
                positions.mapIndexed { index, baseRect ->
                    val isTop = index < 2
                    val isLeft = index % 2 == 0
                    val isBottom = !isTop
                    val isRight = !isLeft

                    val innerRect = RectF(baseRect)
                    innerRect.left += if (isLeft) 0f else gapWidthPx
                    innerRect.top += if (isTop) 0f else gapWidthPx
                    innerRect.right -= if (isRight) 0f else gapWidthPx
                    innerRect.bottom -= if (isBottom) 0f else gapWidthPx

                    val gapRect = RectF(innerRect)
                    gapRect.inset(-gapWidthPx, -gapWidthPx)

                    val gapRadius = cornerRadiusPx
                    val gapTopLeft = if (!isTop && !isLeft) gapRadius else 0f
                    val gapTopRight = if (!isTop && !isRight) gapRadius else 0f
                    val gapBottomRight = if (!isBottom && !isRight) gapRadius else 0f
                    val gapBottomLeft = if (!isBottom && !isLeft) gapRadius else 0f
                    val gapPath =
                        Path().apply {
                            addRoundRect(
                                gapRect,
                                floatArrayOf(
                                    gapTopLeft,
                                    gapTopLeft,
                                    gapTopRight,
                                    gapTopRight,
                                    gapBottomRight,
                                    gapBottomRight,
                                    gapBottomLeft,
                                    gapBottomLeft,
                                ),
                                Path.Direction.CW,
                            )
                        }

                    val innerRadius = (cornerRadiusPx - gapWidthPx).coerceAtLeast(0f)
                    val topLeftRadius = if (!isTop && !isLeft) innerRadius else 0f
                    val topRightRadius = if (!isTop && !isRight) innerRadius else 0f
                    val bottomRightRadius = if (!isBottom && !isRight) innerRadius else 0f
                    val bottomLeftRadius = if (!isBottom && !isLeft) innerRadius else 0f
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

                    TileGeometry(innerRect, gapPath, maskPath)
                }

            val zOrder = config.zOrder
            for ((orderIndex, imageIndex) in zOrder.withIndex()) {
                val bitmap = sourceImages[imageIndex]
                val tile = tiles[imageIndex]
                val visiblePath = Path(tile.gapPath)
                for (index in orderIndex + 1 until zOrder.size) {
                    visiblePath.op(tiles[zOrder[index]].gapPath, Path.Op.DIFFERENCE)
                    if (visiblePath.isEmpty) {
                        break
                    }
                }

                if (visiblePath.isEmpty) {
                    continue
                }

                canvas.drawPath(visiblePath, gapPaint)

                if (tile.innerRect.width() > 0 && tile.innerRect.height() > 0) {
                    val clipSave = canvas.save()
                    canvas.clipPath(visiblePath)
                    val savedLayer = canvas.saveLayer(tile.innerRect, null)
                    canvas.drawPath(tile.maskPath, imagePaint)

                    imagePaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                    drawBitmapCover(canvas, bitmap, tile.innerRect, imagePaint)
                    imagePaint.xfermode = null

                    canvas.restoreToCount(savedLayer)
                    canvas.restoreToCount(clipSave)
                }
            }

            return result
        }

        private data class TileGeometry(val innerRect: RectF, val gapPath: Path, val maskPath: Path)

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

    class Factory @Inject constructor() : Fetcher.Factory {
        override fun create(
            data: GalleryCoverCollection,
            options: Options,
            imageLoader: ImageLoader,
        ) = GalleryComposeFetcher(options.context, data, options.size)
    }

    class Keyer @Inject constructor() : CoilKeyer {
        override fun key(data: GalleryCoverCollection, options: Options): String {
            val config =
                "${data.cornerRadiusRatio}.${data.zOrder.joinToString(".")}.${data.backgroundColor}"
            return "g:${data.covers.hashCode()}.${options.size.width}.${options.size.height}.$config"
        }
    }
}
