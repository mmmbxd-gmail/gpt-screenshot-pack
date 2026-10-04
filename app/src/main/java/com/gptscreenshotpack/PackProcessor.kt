package com.gptscreenshotpack

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.PorterDuff
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.OpenableColumns
import androidx.heifwriter.HeifWriter
import com.gptscreenshotpack.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.time.TimeSource

data class PackResult(
    val zip: File?, val inputCount: Int, val successCount: Int, val outputCount: Int,
    val inputBytes: Long, val unknownInputSizes: Int, val outputBytes: Long,
    val elapsedMs: Long, val settings: PackSettings, val errors: List<String>, val notes: List<String>,
)

class PackCache(context: Context) {
    val root = File(context.cacheDir, "packs")
    fun clean(expiredOnly: Boolean = true) {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        root.listFiles()?.filter { !expiredOnly || it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
    }
    fun newRun(): File = File(root, UUID.randomUUID().toString()).also { check(it.mkdirs()) }
}

class PackProcessor(private val context: Context) {
    private val resolver = context.contentResolver
    private class Header(val dimensions: Dimensions) : RuntimeException()

    private fun dimensions(uri: Uri): Dimensions {
        try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { _, info, _ ->
                throw Header(Dimensions(info.size.width, info.size.height))
            }.recycle()
        } catch (header: Header) { return header.dimensions }
        error("无法读取图片尺寸")
    }

    private fun metadata(uri: Uri, index: Int): Pair<String, Long?> {
        var name = "image_${index + 1}"
        var size: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) {
                val n = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val s = it.getColumnIndex(OpenableColumns.SIZE)
                if (n >= 0 && !it.isNull(n)) name = it.getString(n)
                if (s >= 0 && !it.isNull(s)) size = it.getLong(s).takeIf { bytes -> bytes >= 0 }
            }
        }
        return name to size
    }

    private fun decode(uri: Uri, size: Dimensions, tile: Tile): Bitmap {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            decoder.setTargetSize(size.width, size.height)
            decoder.crop = Rect(tile.left, tile.top, tile.right, tile.bottom)
            decoder.setOnPartialImageListener { false }
        }
        if (bitmap.config == Bitmap.Config.ARGB_8888) return bitmap
        try { return checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, true)) }
        finally { bitmap.recycle() }
    }

    private fun encode(bitmap: Bitmap, output: File, format: OutputFormat, quality: Int) {
        if (format != OutputFormat.PNG && bitmap.hasAlpha()) {
            Canvas(bitmap).drawColor(Color.WHITE, PorterDuff.Mode.DST_OVER)
            bitmap.setHasAlpha(false)
        }
        if (format == OutputFormat.HEIC) {
            // Own the callback thread: 1.1.0 does not quit its internally created HandlerThread.
            val thread = HandlerThread("PackHeifCallbacks").apply { start() }
            try {
                HeifWriter.Builder(output.absolutePath, bitmap.width, bitmap.height, HeifWriter.INPUT_MODE_BITMAP)
                    .setHandler(Handler(thread.looper)).setQuality(quality).setMaxImages(1).build().use { writer ->
                        writer.start()
                        writer.addBitmap(bitmap)
                        writer.stop(30_000) // Published source uses milliseconds despite a doc typo.
                    }
            } finally {
                thread.quitSafely()
                thread.join(5_000)
            }
            // Check actual device readability, sampled so validation stays small.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(output)) { decoder, info, _ ->
                check(info.size.width == bitmap.width && info.size.height == bitmap.height) { "HEIC 尺寸校验失败" }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val factor = maxOf(info.size.width, info.size.height) / 64.0
                decoder.setTargetSize((info.size.width / factor.coerceAtLeast(1.0)).toInt().coerceAtLeast(1),
                    (info.size.height / factor.coerceAtLeast(1.0)).toInt().coerceAtLeast(1))
            }.recycle()
        } else {
            output.outputStream().buffered().use {
                check(bitmap.compress(if (format == OutputFormat.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, quality, it)) {
                    "图片编码失败"
                }
            }
        }
        check(output.length() > 0) { "编码结果为空" }
    }

    suspend fun process(uris: List<Uri>, settings: PackSettings, progress: (Int, Int) -> Unit): PackResult = withContext(Dispatchers.IO) {
        require(uris.isNotEmpty())
        val clock = TimeSource.Monotonic.markNow()
        val coroutine = currentCoroutineContext()
        val run = PackCache(context).newRun()
        val names = OutputNames()
        val images = mutableListOf<File>()
        val errors = mutableListOf<String>()
        val notes = mutableListOf<String>()
        var totalInput = 0L
        var unknownSizes = 0
        var successes = 0
        try {
            uris.forEachIndexed { index, uri ->
                coroutine.ensureActive()
                var display = "图片 ${index + 1}"
                val created = mutableListOf<File>()
                val noteStart = notes.size
                try {
                    require(uri.scheme == "content") { "仅接受 content:// 图片 URI" }
                    val meta = metadata(uri, index)
                    display = meta.first
                    meta.second?.let { totalInput += it } ?: run { unknownSizes++ }
                    val size = dimensions(uri).scaled(settings.scalePercent)
                    // Cropping limits returned bitmap size, not all decoder internal allocations.
                    val budget = minOf(192L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 3)
                    require(size.width.toLong() * size.height * 4 <= budget) { "目标图片超出本机安全内存预算，请降低缩放比例" }
                    val tiles = Slicing.plan(size)
                    val primaryNames = names.allocate(display, settings.format, tiles.size)
                    var fallbackNames: List<String>? = null
                    tiles.forEachIndexed { tileIndex, tile ->
                        coroutine.ensureActive()
                        val bitmap = decode(uri, size, tile)
                        try {
                            var file = File(run, primaryNames[tileIndex])
                            created.add(file)
                            try { encode(bitmap, file, settings.format, settings.quality) }
                            catch (e: Exception) {
                                coroutine.ensureActive()
                                if (settings.format != OutputFormat.HEIC) throw e
                                file.delete()
                                if (fallbackNames == null) fallbackNames = names.allocate(display, OutputFormat.PNG, tiles.size)
                                file = File(run, fallbackNames!![tileIndex])
                                created.add(file)
                                encode(bitmap, file, OutputFormat.PNG, 100)
                                notes.add("${file.name}：HEIC 失败，已回退 PNG（${e.message ?: e.javaClass.simpleName}）")
                            }
                            images.add(file)
                        } finally { bitmap.recycle() }
                    }
                    successes++
                } catch (e: Exception) {
                    coroutine.ensureActive()
                    images.removeAll(created.toSet())
                    created.forEach { it.delete() }
                    while (notes.size > noteStart) notes.removeAt(notes.lastIndex)
                    errors.add("$display：${e.message ?: e.javaClass.simpleName}")
                } catch (e: OutOfMemoryError) {
                    coroutine.ensureActive()
                    images.removeAll(created.toSet())
                    created.forEach { it.delete() }
                    while (notes.size > noteStart) notes.removeAt(notes.lastIndex)
                    errors.add("$display：设备解码或编码内存不足，请降低缩放比例")
                }
                progress(index + 1, uris.size)
            }
            coroutine.ensureActive()
            if (images.isEmpty()) {
                run.deleteRecursively()
                return@withContext PackResult(null, uris.size, 0, 0, totalInput, unknownSizes, 0,
                    clock.elapsedNow().inWholeMilliseconds, settings, errors, notes)
            }
            val imageBytes = images.sumOf { it.length() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val zip = File(run, "GPT_Screenshots_$stamp.zip")
            ImageZip.write(images, zip) { coroutine.ensureActive() }
            images.forEach { it.delete() }
            run.setLastModified(System.currentTimeMillis())
            PackResult(zip, uris.size, successes, images.size, totalInput, unknownSizes, imageBytes,
                clock.elapsedNow().inWholeMilliseconds, settings, errors, notes)
        } catch (e: Throwable) { run.deleteRecursively(); throw e }
    }
}
