package com.gptscreenshotpack.core

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.io.InputStream
import java.io.ByteArrayInputStream

class PackRulesTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun defaultsAndLongScreenshot() {
        val settings = PackSettings()
        assertEquals(OutputFormat.HEIC, settings.format)
        assertEquals(85, settings.quality)
        assertEquals(95, settings.copy(format = OutputFormat.JPEG).quality)
        assertEquals(100, settings.scalePercent)
        assertEquals(Dimensions(1440, 3200), Dimensions(1440, 3200).scaled(settings.scalePercent))
        assertEquals(2, Slicing.plan(Dimensions(1440, 19399).scaled(settings.scalePercent)).size)
        val long = Dimensions(1440, 19399).scaled(50)
        assertEquals(Dimensions(720, 9700), long)
        assertEquals(1, Slicing.plan(long).size)
        assertEquals(1, Slicing.plan(Dimensions(16384, 16384)).size)
    }
    @Test fun qualityAndScalePresetsKeepRequestedValues() {
        assertEquals(listOf(33, 50, 60, 67, 75, 100), PackPresets.scales)
        assertEquals(listOf(50, 75, 85, 90, 95, 100), PackPresets.qualities)
        PackPresets.qualities.forEach { q ->
            assertEquals(q, PackSettings(heicQuality = q).quality)
            assertEquals(q, PackSettings(format = OutputFormat.JPEG, jpegQuality = q).quality)
        }
    }
    @Test fun slicesAreBalancedOrderedAndCoverImage() {
        for (size in listOf(Dimensions(720, 28000), Dimensions(40000, 720), Dimensions(17000, 39000))) {
            val tiles = Slicing.plan(size)
            assertTrue(tiles.size > 1)
            assertEquals(size.width.toLong() * size.height, tiles.sumOf { it.width.toLong() * it.height })
            assertTrue(tiles.all { it.width <= 16384 && it.height <= 16384 && it.width > 0 && it.height > 0 })
            tiles.zipWithNext().forEach { (a, b) -> assertTrue(b.top > a.top || b.top == a.top && b.left > a.left) }
            val heights = tiles.map { it.height }
            assertTrue(heights.max() - heights.min() <= 1)
            assertEquals(0, tiles.first().top)
            assertEquals(size.height, tiles.last().bottom)
        }
        val vertical = Slicing.plan(Dimensions(720, 28000))
        assertEquals(3, vertical.size)
        assertEquals(vertical[0].bottom, vertical[1].top)
        assertEquals(vertical[1].bottom, vertical[2].top)
    }
    @Test fun namingPreservesUnicodeAndTimeAndHandlesAllCollisions() {
        val names = OutputNames()
        assertEquals(listOf("resized_截图_20261005_013022.heic"), names.allocate("截图_20261005_013022.jpg", OutputFormat.HEIC, 1))
        assertEquals(listOf("resized_截图_20261005_013022_copy2.heic"), names.allocate("截图_20261005_013022.png", OutputFormat.HEIC, 1))
        assertEquals(listOf("resized_截图_20261005_013022_copy3_1.heic", "resized_截图_20261005_013022_copy3_2.heic"), names.allocate("截图_20261005_013022.jpg", OutputFormat.HEIC, 2))
        assertEquals(listOf("resized_截图_20261005_013022_copy4_1.heic", "resized_截图_20261005_013022_copy4_2.heic"), names.allocate("截图_20261005_013022.jpg", OutputFormat.HEIC, 2))
        val collision = OutputNames()
        collision.allocate("截图.png", OutputFormat.HEIC, 2)
        assertEquals(listOf("resized_截图_1_copy2.heic"), collision.allocate("截图_1.jpg", OutputFormat.HEIC, 1))
        val duplicateSliced = OutputNames()
        duplicateSliced.allocate("Screenshot.jpg", OutputFormat.HEIC, 1)
        assertEquals(listOf("resized_Screenshot_copy2_1.heic", "resized_Screenshot_copy2_2.heic"), duplicateSliced.allocate("Screenshot.jpg", OutputFormat.HEIC, 2))
        assertFalse(names.allocate("../../evil.jpg", OutputFormat.PNG, 1).single().contains('/'))
        assertEquals(listOf("resized_IMG20261005123801.heic"), OutputNames().allocate("IMG20261005123801.jpg", OutputFormat.HEIC, 1))
        assertEquals(listOf("resized_IMG20261005123801_1.heic", "resized_IMG20261005123801_2.heic", "resized_IMG20261005123801_3.heic"),
            OutputNames().allocate("IMG20261005123801.jpg", OutputFormat.HEIC, 3))
    }
    @Test fun zipContainsOnlyOrderedImagesAndDoesNotModifyInputs() {
        val dir = temporary.newFolder()
        val files = listOf("resized_第一.png", "resized_第二_1.heic", "resized_第二_2.jpg").mapIndexed { i, name ->
            File(dir, name).apply { writeBytes(byteArrayOf(i.toByte(), 42)) }
        }
        val zip = File(dir, "pack.zip")
        ImageZip.write(files, zip)
        ZipFile(zip).use { archive ->
            assertEquals(files.map { it.name }, archive.entries().asSequence().map { it.name }.toList())
            files.forEach { file ->
                val entry = archive.getEntry(file.name)
                assertEquals(ZipEntry.STORED, entry.method)
                assertEquals(file.length(), entry.size)
                assertEquals(entry.size, entry.compressedSize)
                assertEquals(CRC32().apply { update(file.readBytes()) }.value, entry.crc)
                assertArrayEquals(file.readBytes(), archive.getInputStream(entry).use { it.readBytes() })
            }
        }
        assertArrayEquals(byteArrayOf(0, 42), files[0].readBytes())
    }
    @Test fun storedZipUsesTwoStreamingPassesAndClosesEachInput() {
        val length = 10L * 1024 * 1024 + 17
        var opens = 0
        var closes = 0
        val image = ZipImage("resized_large.heic") {
            opens++
            object : InputStream() {
                var remaining = length
                override fun read(): Int = if (remaining-- > 0) 42 else -1
                override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                    assertTrue("I/O buffer must stay bounded", count <= 64 * 1024)
                    if (remaining == 0L) return -1
                    val n = minOf(count.toLong(), remaining).toInt()
                    bytes.fill(42, offset, offset + n)
                    remaining -= n
                    return n
                }
                override fun close() { closes++ }
            }
        }
        val zip = temporary.newFile("large.zip")
        zip.outputStream().use { ImageZip.writeStreams(listOf(image), it) }
        assertEquals(2, opens)
        assertEquals(2, closes)
        ZipFile(zip).use {
            val entry = it.entries().nextElement()
            assertEquals(length, entry.size)
            assertEquals(length, entry.compressedSize)
            assertEquals(ZipEntry.STORED, entry.method)
            assertEquals(length, it.getInputStream(entry).use { input -> input.copyTo(java.io.OutputStream.nullOutputStream()) })
        }
    }
    @Test fun changedInputCrcIsRejectedAndStreamsAreClosed() {
        var opens = 0
        var closes = 0
        val image = ZipImage("changed.png") {
            val bytes = if (opens++ == 0) byteArrayOf(1, 2, 3) else byteArrayOf(1, 2, 4)
            object : ByteArrayInputStream(bytes) { override fun close() { closes++; super.close() } }
        }
        val zip = temporary.newFile("changed.zip")
        assertThrows(ZipException::class.java) { zip.outputStream().use { ImageZip.writeStreams(listOf(image), it) } }
        assertEquals(2, closes)
    }
    @Test fun cancellationDuringCrcAndPayloadRemovesPartialFile() {
        val png = temporary.newFile("cancel.png").apply { writeBytes(ByteArray(100000) { 42 }) }
        for (cancelAt in listOf(3, 5)) {
            val zip = java.io.File(temporary.newFolder(), "cancelled.zip")
            var checks = 0
            assertThrows(IllegalStateException::class.java) {
                ImageZip.write(listOf(png), zip) { if (++checks == cancelAt) error("cancel") }
            }
            assertFalse(zip.exists())
        }
    }
    @Test fun rejectsEmptyOrNonImageZipAndRemovesCancelledZip() {
        val zip = File(temporary.newFolder(), "pack.zip")
        assertThrows(IllegalArgumentException::class.java) { ImageZip.write(emptyList(), zip) }
        assertFalse(zip.exists())
        val txt = temporary.newFile("manifest.txt")
        assertThrows(IllegalArgumentException::class.java) { ImageZip.write(listOf(txt), zip) }
        val png = temporary.newFile("image.png").apply { writeBytes(ByteArray(100000)) }
        assertThrows(IllegalStateException::class.java) { ImageZip.write(listOf(png), zip) { error("cancel") } }
        assertFalse(zip.exists())
    }
}
