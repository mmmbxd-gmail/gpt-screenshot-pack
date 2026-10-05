package com.gptscreenshotpack.core

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

enum class OutputFormat(val extension: String) { HEIC("heic"), PNG("png"), JPEG("jpg") }
enum class ResolutionMode { ORIGINAL, REDUCED }

data class PackSettings(
    val scalePercent: Int = 100,
    val format: OutputFormat = OutputFormat.HEIC,
    val heicQuality: Int = 85,
    val jpegQuality: Int = 95,
    val resolutionMode: ResolutionMode = ResolutionMode.ORIGINAL,
) {
    init {
        require(scalePercent in 25..100)
        require(heicQuality in 1..100 && jpegQuality in 1..100)
    }
    val quality: Int get() = if (format == OutputFormat.JPEG) jpegQuality else heicQuality
    val effectiveScalePercent: Int get() = if (resolutionMode == ResolutionMode.ORIGINAL) 100 else scalePercent
    fun targetDimensions(input: Dimensions): Dimensions =
        if (resolutionMode == ResolutionMode.ORIGINAL) input else input.scaled(scalePercent)
}

object PackPresets {
    val scales = listOf(33, 50, 60, 67, 75, 100)
    val qualities = listOf(50, 75, 85, 90, 95, 100)
}

data class Dimensions(val width: Int, val height: Int) {
    init { require(width > 0 && height > 0) }
    fun scaled(percent: Int): Dimensions {
        require(percent in 25..100)
        return Dimensions((width * percent / 100.0).roundToInt().coerceAtLeast(1),
            (height * percent / 100.0).roundToInt().coerceAtLeast(1))
    }
}

data class Tile(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

object Slicing {
    const val SAFE_LIMIT = 16384
    private const val TILE_TARGET = 12000
    fun plan(size: Dimensions): List<Tile> {
        fun edges(length: Int): List<Int> {
            val count = if (length <= SAFE_LIMIT) 1 else ((length.toLong() + TILE_TARGET - 1) / TILE_TARGET).toInt()
            require(count <= 10000) { "图片尺寸过大" }
            return (0..count).map { (length.toLong() * it / count).toInt() }
        }
        val xs = edges(size.width)
        val ys = edges(size.height)
        require((xs.size - 1).toLong() * (ys.size - 1) <= 10000) { "切片数量过多" }
        return ys.zipWithNext().flatMap { (top, bottom) ->
            xs.zipWithNext().map { (left, right) -> Tile(left, top, right, bottom) }
        }
    }
}

class OutputNames {
    private val used = mutableSetOf<String>()
    private val usedBases = mutableSetOf<String>()
    fun allocate(original: String, format: OutputFormat, count: Int): List<String> {
        require(count > 0)
        val safe = original.map { if (it == '/' || it == '\\' || it.code < 32 || it.code == 127) '_' else it }.joinToString("")
        val dot = safe.lastIndexOf('.')
        val stem = (if (dot > 0) safe.substring(0, dot) else safe).ifBlank { "image" }
        // Leave room for extension and collision/slice suffix on typical filesystems.
        require(stem.toByteArray(Charsets.UTF_8).size <= 210) { "文件名主体过长，无法安全保留" }
        var copy = 1
        while (true) {
            val base = "resized_$stem" + if (copy == 1) "" else "_copy$copy"
            val names = (1..count).map { base + (if (count == 1) "" else "_$it") + ".${format.extension}" }
            if ("$base.${format.extension}" !in usedBases && names.none { it in used }) {
                used.addAll(names)
                usedBases.add("$base.${format.extension}")
                return names
            }
            copy++
        }
    }
}

data class ZipImage(val name: String, val open: () -> InputStream)

object ImageZip {
    fun write(images: List<File>, destination: File, checkpoint: () -> Unit = {}) {
        require(images.all { it.isFile })
        try {
            destination.outputStream().buffered().use { stream ->
                writeStreams(images.map { file -> ZipImage(file.name) { file.inputStream() } }, stream, checkpoint)
            }
        } catch (e: Throwable) {
            destination.delete()
            throw e
        }
    }

    /** Two bounded-memory passes per image: size/CRC first, then unchanged STORED bytes. */
    fun writeStreams(images: List<ZipImage>, destination: OutputStream, checkpoint: () -> Unit = {}) {
        require(images.isNotEmpty()) { "不生成空 ZIP" }
        require(images.all { it.name.substringAfterLast('.', "").lowercase() in setOf("heic", "png", "jpg") &&
            '/' !in it.name && '\\' !in it.name })
        require(images.map { it.name }.distinct().size == images.size)
        ZipOutputStream(destination).use { zip ->
            val buffer = ByteArray(64 * 1024)
            for (image in images) {
                checkpoint()
                val crc = CRC32()
                var size = 0L
                image.open().use { input ->
                    while (true) {
                        checkpoint()
                        val n = input.read(buffer)
                        if (n < 0) break
                        crc.update(buffer, 0, n)
                        size = Math.addExact(size, n.toLong())
                    }
                }
                val entry = ZipEntry(image.name).apply {
                    method = ZipEntry.STORED
                    this.size = size
                    compressedSize = size
                    this.crc = crc.value
                }
                zip.putNextEntry(entry)
                image.open().use { input ->
                    while (true) {
                        checkpoint()
                        val n = input.read(buffer)
                        if (n < 0) break
                        zip.write(buffer, 0, n)
                    }
                }
                zip.closeEntry() // Verifies actual size/CRC; changed inputs cannot silently corrupt the ZIP.
            }
        }
    }
}
