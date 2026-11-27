package com.gptscreenshotpack

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import androidx.core.graphics.createBitmap
import androidx.exifinterface.media.ExifInterface
import com.gptscreenshotpack.core.Dimensions
import com.gptscreenshotpack.core.Tile
import java.io.IOException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Uses source-region decoding so a tall screenshot never needs one full-size bitmap. */
internal class RegionImage private constructor(
    private val decoder: BitmapRegionDecoder,
    orientation: Int,
    source: Dimensions,
    target: Dimensions,
) : AutoCloseable {
    private val rawWidth = decoder.width
    private val rawHeight = decoder.height
    private val usesOriginalPixels = source == target &&
        orientation in ExifInterface.ORIENTATION_UNDEFINED..ExifInterface.ORIENTATION_NORMAL
    private val sourcePerPixel = minOf(source.width.toDouble() / target.width, source.height.toDouble() / target.height)
    private val sample = Integer.highestOneBit(sourcePerPixel.toInt().coerceAtLeast(1))
    private val rawToTarget = orientationMatrix(rawWidth.toFloat(), rawHeight.toFloat(), orientation).apply {
        postScale(target.width.toFloat() / source.width, target.height.toFloat() / source.height)
    }
    private val targetToRaw = Matrix().also { check(rawToTarget.invert(it)) }
    private val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        inMutable = usesOriginalPixels
    }

    fun maxTilePixels(budgetBytes: Long): Long {
        if (usesOriginalPixels) return (budgetBytes / 4).coerceAtLeast(1)
        val inverse = FloatArray(9).also(targetToRaw::getValues)
        val sampledX = (abs(inverse[Matrix.MSCALE_X]) + abs(inverse[Matrix.MSKEW_X])) / sample
        val sampledY = (abs(inverse[Matrix.MSCALE_Y]) + abs(inverse[Matrix.MSKEW_Y])) / sample
        // Allow for the decoded source and rendered destination bitmaps, including edge padding.
        val bytesPerPixel = 4 * (1 + sampledX.coerceAtLeast(1f) * sampledY.coerceAtLeast(1f))
        return (budgetBytes / bytesPerPixel).toLong().coerceAtLeast(1)
    }

    fun decode(tile: Tile): Bitmap {
        if (usesOriginalPixels) {
            val bitmap = decodeRegion(Rect(tile.left, tile.top, tile.right, tile.bottom))
            if (bitmap.isMutable && bitmap.config == Bitmap.Config.ARGB_8888) return bitmap
            try { return checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, true)) }
            finally { bitmap.recycle() }
        }
        val bounds = RectF(tile.left.toFloat(), tile.top.toFloat(), tile.right.toFloat(), tile.bottom.toFloat())
        targetToRaw.mapRect(bounds)
        // Use the same sampling grid around adjacent tiles and leave room for filtered edges.
        val region = Rect(
            lowerEdge(bounds.left), lowerEdge(bounds.top),
            upperEdge(bounds.right, rawWidth), upperEdge(bounds.bottom, rawHeight),
        )
        val decoded = decodeRegion(region)
        try {
            val bitmap = createBitmap(tile.width, tile.height)
            try {
                val transform = Matrix(rawToTarget).apply { postTranslate(-tile.left.toFloat(), -tile.top.toFloat()) }
                Canvas(bitmap).apply {
                    concat(transform)
                    drawBitmap(decoded, null, RectF(region), Paint(Paint.FILTER_BITMAP_FLAG))
                }
                return bitmap
            } catch (e: Throwable) {
                bitmap.recycle()
                throw e
            }
        } finally {
            decoded.recycle()
        }
    }

    private fun decodeRegion(region: Rect): Bitmap =
        checkNotNull(decoder.decodeRegion(region, options)) { "无法读取图片区域" }

    private fun lowerEdge(value: Float): Int = ((floor(value.toDouble() / sample).toLong() - 1) * sample)
        .coerceAtLeast(0).toInt()

    private fun upperEdge(value: Float, limit: Int): Int = ((ceil(value.toDouble() / sample).toLong() + 1) * sample)
        .coerceAtMost(limit.toLong()).toInt()

    override fun close() = decoder.recycle()

    companion object {
        @Suppress("DEPRECATION") // The single-argument constructors were added in API 31.
        fun open(resolver: ContentResolver, uri: Uri, source: Dimensions, target: Dimensions): RegionImage? {
            val decoder = try {
                resolver.openFileDescriptor(uri, "r")?.use { BitmapRegionDecoder.newInstance(it.fileDescriptor, false) }
            } catch (_: IOException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } ?: try {
                resolver.openInputStream(uri)?.use { BitmapRegionDecoder.newInstance(it, false) }
            } catch (_: IOException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } ?: return null
            var result: RegionImage? = null
            try {
                val orientation = readOrientation(resolver, uri)
                val rotated = orientation in ExifInterface.ORIENTATION_TRANSPOSE..ExifInterface.ORIENTATION_ROTATE_270
                val raw = Dimensions(decoder.width, decoder.height)
                val oriented = if (rotated) Dimensions(raw.height, raw.width) else raw
                if (source == oriented) result = RegionImage(decoder, orientation, source, target)
                return result
            } finally {
                if (result == null) decoder.recycle()
            }
        }

        private fun readOrientation(resolver: ContentResolver, uri: Uri): Int = try {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: IOException) {
            ExifInterface.ORIENTATION_NORMAL
        }

        private fun orientationMatrix(w: Float, h: Float, orientation: Int): Matrix {
            val corners = when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> floatArrayOf(w, 0f, 0f, 0f, w, h)
                ExifInterface.ORIENTATION_ROTATE_180 -> floatArrayOf(w, h, 0f, h, w, 0f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> floatArrayOf(0f, h, w, h, 0f, 0f)
                ExifInterface.ORIENTATION_TRANSPOSE -> floatArrayOf(0f, 0f, 0f, w, h, 0f)
                ExifInterface.ORIENTATION_ROTATE_90 -> floatArrayOf(h, 0f, h, w, 0f, 0f)
                ExifInterface.ORIENTATION_TRANSVERSE -> floatArrayOf(h, w, h, 0f, 0f, w)
                ExifInterface.ORIENTATION_ROTATE_270 -> floatArrayOf(0f, w, 0f, 0f, h, w)
                else -> return Matrix()
            }
            return Matrix().apply { setPolyToPoly(floatArrayOf(0f, 0f, w, 0f, 0f, h), 0, corners, 0, 3) }
        }
    }
}
