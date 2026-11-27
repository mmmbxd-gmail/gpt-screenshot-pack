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
import kotlin.time.TimeSource

data class PackResult(
    val output: PackOutput?, val inputCount: Int, val successCount: Int, val outputCount: Int,
    val inputBytes: Long, val unknownInputSizes: Int, val outputBytes: Long,
    val elapsedMs: Long, val settings: PackSettings, val errors: List<String>, val notes: List<String>,
)

class PackProcessor(private val context: Context) {
    private val resolver = context.contentResolver
    private val storage = PackStorage(context)
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

    private fun encode(bitmap: Bitmap, output: StoredDocument, format: OutputFormat, quality: Int) {
        if (format != OutputFormat.PNG && bitmap.hasAlpha()) {
            Canvas(bitmap).drawColor(Color.WHITE, PorterDuff.Mode.DST_OVER)
            bitmap.setHasAlpha(false)
        }
        if (format == OutputFormat.HEIC) {
            // Own the callback thread: 1.1.0 does not quit its internally created HandlerThread.
            storage.descriptor(output).use { descriptor ->
                val thread = HandlerThread("PackHeifCallbacks").apply { start() }
                try {
                    HeifWriter.Builder(descriptor.fileDescriptor, bitmap.width, bitmap.height, HeifWriter.INPUT_MODE_BITMAP)
                        .setHandler(Handler(thread.looper)).setQuality(quality).setMaxImages(1).build().use { writer ->
                            writer.start()
                            writer.addBitmap(bitmap)
                            writer.stop(30_000) // Published source uses milliseconds despite a doc typo.
                        }
                } finally {
                    thread.quitSafely()
                    thread.join(5_000)
                }
            }
            // Check actual device readability, sampled so validation stays small.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, output.uri)) { decoder, info, _ ->
                check(info.size.width == bitmap.width && info.size.height == bitmap.height) { "HEIC 尺寸校验失败" }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val factor = maxOf(info.size.width, info.size.height) / 64.0
                decoder.setTargetSize((info.size.width / factor.coerceAtLeast(1.0)).toInt().coerceAtLeast(1),
                    (info.size.height / factor.coerceAtLeast(1.0)).toInt().coerceAtLeast(1))
            }.recycle()
        } else {
            storage.write(output).buffered().use {
                check(bitmap.compress(if (format == OutputFormat.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, quality, it)) {
                    "图片编码失败"
                }
            }
        }
        check(storage.size(output) > 0) { "编码结果为空" }
    }

    suspend fun process(uris: List<Uri>, settings: PackSettings, fileName: String? = null,
        progress: (Int, Int) -> Unit): PackResult = withContext(Dispatchers.IO) {
        require(uris.isNotEmpty())
        val container = settings.container
        val pdf = container == OutputContainer.PDF
        val format = if (pdf) OutputFormat.JPEG else settings.format
        val mime = when (format) {
            OutputFormat.HEIC -> "image/heic"
            OutputFormat.PNG -> "image/png"
            OutputFormat.JPEG -> "image/jpeg"
        }
        val outputName = fileName ?: OutputNaming().fileName(container = container)
        OutputNaming.requireFileName(outputName, container)
        val clock = TimeSource.Monotonic.markNow()
        val coroutine = currentCoroutineContext()
        val budget = minOf(192L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 3)
        val run = storage.newRun()
        val names = OutputNames()
        val images = mutableListOf<StoredDocument>()
        val pages = mutableListOf<PdfPage>()
        val errors = mutableListOf<String>()
        val notes = mutableListOf<String>()
        var totalInput = 0L
        var unknownSizes = 0
        var successes = 0
        try {
            uris.forEachIndexed { index, uri ->
                coroutine.ensureActive()
                var display = "图片 ${index + 1}"
                val created = mutableListOf<StoredDocument>()
                val noteStart = notes.size
                try {
                    require(uri.scheme == "content") { "仅接受 content:// 图片 URI" }
                    val meta = metadata(uri, index)
                    display = meta.first
                    meta.second?.let { totalInput += it } ?: run { unknownSizes++ }
                    val source = dimensions(uri)
                    val size = settings.targetDimensions(source)
                    val region = if (format == OutputFormat.JPEG && (needsRegion(source, budget) || needsRegion(size, budget)))
                        RegionImage.open(resolver, uri, source, size) else null
                    val (tiles, processed) = region.use {
                        // ImageDecoder's crop bounds the returned bitmap, not internal decoder allocations.
                        if (region == null) require(size.width.toLong() * size.height <= budget / 4) {
                            "目标图片超出本机安全内存预算，请调低缩放比例或指定宽度"
                        }
                        val tiles = Slicing.plan(size, region?.maxTilePixels(budget) ?: Long.MAX_VALUE)
                        val primaryNames = if (pdf) tiles.indices.map { "page_${index + 1}_${it + 1}.jpg" }
                            else names.allocate(display, format, tiles.size)
                        var fallbackNames: List<String>? = null
                        val files = tiles.mapIndexed { tileIndex, tile ->
                            coroutine.ensureActive()
                            val bitmap = region?.decode(tile) ?: decode(uri, size, tile)
                            try {
                                var file = storage.createTemp(run, primaryNames[tileIndex], mime)
                                created.add(file)
                                try { encode(bitmap, file, format, settings.quality) }
                                catch (e: Exception) {
                                    coroutine.ensureActive()
                                    if (format != OutputFormat.HEIC) throw e
                                    storage.delete(file)
                                    if (fallbackNames == null) fallbackNames = names.allocate(display, OutputFormat.PNG, tiles.size)
                                    file = storage.createTemp(run, fallbackNames!![tileIndex], "image/png")
                                    created.add(file)
                                    encode(bitmap, file, OutputFormat.PNG, 100)
                                    notes.add("${file.name}：HEIC 失败，已回退 PNG（${e.message ?: e.javaClass.simpleName}）")
                                }
                                file
                            } finally { bitmap.recycle() }
                        }
                        tiles to files
                    }
                    if (pdf) {
                        val pageImages = tiles.zip(processed) { tile, jpeg ->
                            PdfImage(tile, storage.size(jpeg)) { storage.read(jpeg) }
                        }
                        pages.add(PdfPage(size, pageImages))
                    } else images.addAll(processed)
                    successes++
                } catch (e: Exception) {
                    coroutine.ensureActive()
                    created.forEach { runCatching { storage.delete(it) } }
                    while (notes.size > noteStart) notes.removeAt(notes.lastIndex)
                    errors.add("$display：${e.message ?: e.javaClass.simpleName}")
                } catch (e: OutOfMemoryError) {
                    coroutine.ensureActive()
                    created.forEach { runCatching { storage.delete(it) } }
                    while (notes.size > noteStart) notes.removeAt(notes.lastIndex)
                    errors.add("$display：设备解码或编码内存不足，请调低缩放比例或指定宽度")
                }
                progress(index + 1, uris.size)
            }
            coroutine.ensureActive()
            if (successes == 0) {
                runCatching { storage.cleanRun(run) }.onFailure { notes.add("临时文件清理未完成：${it.message}") }
                return@withContext PackResult(null, uris.size, 0, 0, totalInput, unknownSizes, 0,
                    clock.elapsedNow().inWholeMilliseconds, settings, errors, notes)
            }
            val imageBytes = if (pdf) pages.sumOf { page -> page.images.sumOf { it.bytes } }
                else images.sumOf { storage.size(it) }
            val staged = storage.createTemp(run, outputName, container.mimeType)
            storage.write(staged).buffered().use { output ->
                if (pdf) ImagePdf.write(pages, output) { coroutine.ensureActive() }
                else ImageZip.writeStreams(images.map { file -> ZipImage(file.name) { storage.read(file) } }, output) { coroutine.ensureActive() }
            }
            coroutine.ensureActive()
            val output = storage.publish(staged, container)
            runCatching { storage.cleanRun(run) }.onFailure { notes.add("$container 已保存，临时文件清理未完成：${it.message}") }
            PackResult(output, uris.size, successes, if (pdf) pages.size else images.size, totalInput, unknownSizes, imageBytes,
                clock.elapsedNow().inWholeMilliseconds, settings, errors, notes)
        } catch (e: Throwable) {
            runCatching { storage.cleanRun(run) }.onFailure { e.addSuppressed(it) }
            throw e
        }
    }

    private fun needsRegion(size: Dimensions, budget: Long): Boolean =
        maxOf(size.width, size.height) > Slicing.SAFE_LIMIT || size.width.toLong() * size.height > budget / 4
}
