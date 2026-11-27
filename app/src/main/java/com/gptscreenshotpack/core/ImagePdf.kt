package com.gptscreenshotpack.core

import java.io.InputStream
import java.io.OutputStream
import java.math.BigDecimal
import java.math.RoundingMode

internal data class PdfImage(val tile: Tile, val bytes: Long, val open: () -> InputStream)
internal data class PdfPage(val size: Dimensions, val images: List<PdfImage>)

/**
 * Android PdfDocument only accepts drawing commands and does not expose the embedded JPEG
 * encoder or its quality. Write JPEG image XObjects directly so the selected quality is retained.
 */
internal object ImagePdf {
    fun write(pages: List<PdfPage>, destination: OutputStream, checkpoint: () -> Unit) {
        require(pages.isNotEmpty()) { "不生成空 PDF" }
        val writer = Writer(destination, 2 + pages.sumOf { 2 + it.images.size })
        writer.text("%PDF-1.4\n%\u00E2\u00E3\u00CF\u00D3\n")
        writer.obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
        val pageIds = buildList {
            var next = 3
            for (page in pages) {
                add(next)
                next += 2 + page.images.size
            }
        }
        writer.obj(2, "<< /Type /Pages /Count ${pages.size} /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] >>")
        for ((index, page) in pages.withIndex()) {
            checkpoint()
            val id = pageIds[index]
            val longest = maxOf(page.size.width, page.size.height)
            // PDF 1.4 readers support a page dimension up to 14,400 points.
            val scale = if (longest <= 14400) BigDecimal.ONE else
                BigDecimal.valueOf(14400).divide(BigDecimal.valueOf(longest.toLong()), 12, RoundingMode.DOWN)
            val width = scale.multiply(BigDecimal.valueOf(page.size.width.toLong())).stripTrailingZeros().toPlainString()
            val height = scale.multiply(BigDecimal.valueOf(page.size.height.toLong())).stripTrailingZeros().toPlainString()
            val resources = page.images.indices.joinToString(" ") { "/Im$it ${id + 2 + it} 0 R" }
            writer.obj(id, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $width $height] " +
                "/Resources << /XObject << $resources >> >> /Contents ${id + 1} 0 R >>")
            val commands = buildString {
                val factor = scale.stripTrailingZeros().toPlainString()
                append("q $factor 0 0 $factor 0 0 cm\n")
                page.images.forEachIndexed { imageIndex, image ->
                    val tile = image.tile
                    append("q ${tile.width} 0 0 ${tile.height} ${tile.left} ${page.size.height - tile.bottom} cm /Im$imageIndex Do Q\n")
                }
                append("Q\n")
            }
            writer.stream(id + 1, "", commands.length.toLong()) { writer.text(commands) }
            page.images.forEachIndexed { imageIndex, image ->
                val tile = image.tile
                writer.stream(id + 2 + imageIndex,
                    "/Type /XObject /Subtype /Image /Width ${tile.width} /Height ${tile.height} " +
                        "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode ",
                    image.bytes) {
                    image.open().use { writer.copy(it, checkpoint) }
                }
            }
        }
        checkpoint()
        writer.finish()
    }

    private class Writer(private val destination: OutputStream, objectCount: Int) {
        private val offsets = LongArray(objectCount + 1)
        private val buffer = ByteArray(64 * 1024)
        private var position = 0L

        fun text(value: String) {
            val bytes = value.toByteArray(Charsets.ISO_8859_1)
            destination.write(bytes)
            position += bytes.size
        }

        fun copy(input: InputStream, checkpoint: () -> Unit) {
            while (true) {
                checkpoint()
                val count = input.read(buffer)
                if (count < 0) return
                destination.write(buffer, 0, count)
                position += count
            }
        }

        private fun begin(id: Int) {
            check(position <= 9_999_999_999L) { "PDF 文件过大" }
            offsets[id] = position
            text("$id 0 obj\n")
        }

        fun obj(id: Int, value: String) {
            begin(id)
            text("$value\nendobj\n")
        }

        fun stream(id: Int, attributes: String, length: Long, write: () -> Unit) {
            begin(id)
            text("<< ${attributes}/Length $length >>\nstream\n")
            val start = position
            write()
            check(position - start == length) { "PDF 写入期间图片大小发生变化" }
            text("\nendstream\nendobj\n")
        }

        fun finish() {
            val xref = position
            text("xref\n0 ${offsets.size}\n0000000000 65535 f \n")
            for (id in 1 until offsets.size) {
                text(offsets[id].toString().padStart(10, '0') + " 00000 n \n")
            }
            text("trailer\n<< /Size ${offsets.size} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        }
    }
}
