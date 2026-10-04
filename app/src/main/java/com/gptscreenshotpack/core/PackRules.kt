package com.gptscreenshotpack.core

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

enum class OutputFormat(val extension: String) { HEIC("heic"), PNG("png"), JPEG("jpg") }

data class PackSettings(
    val scalePercent: Int = 50,
    val format: OutputFormat = OutputFormat.HEIC,
    val heicQuality: Int = 95,
    val jpegQuality: Int = 95,
) {
    init {
        require(scalePercent in 25..100)
        require(heicQuality in 1..100 && jpegQuality in 1..100)
    }
    val quality: Int get() = if (format == OutputFormat.JPEG) jpegQuality else heicQuality
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

object ImageZip {
    fun write(images: List<File>, destination: File, checkpoint: () -> Unit = {}) {
        require(images.isNotEmpty()) { "不生成空 ZIP" }
        require(images.all { it.isFile && it.extension.lowercase() in setOf("heic", "png", "jpg") })
        require(images.map { it.name }.distinct().size == images.size)
        try {
            ZipOutputStream(destination.outputStream().buffered()).use { zip ->
                val buffer = ByteArray(64 * 1024)
                for (file in images) {
                    checkpoint()
                    zip.putNextEntry(ZipEntry(file.name))
                    file.inputStream().buffered().use { input ->
                        while (true) {
                            checkpoint()
                            val n = input.read(buffer)
                            if (n < 0) break
                            zip.write(buffer, 0, n)
                        }
                    }
                    zip.closeEntry()
                }
            }
        } catch (e: Throwable) {
            destination.delete()
            throw e
        }
    }
}
