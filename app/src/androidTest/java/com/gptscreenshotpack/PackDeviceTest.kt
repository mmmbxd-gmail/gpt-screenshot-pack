package com.gptscreenshotpack

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.ImageDecoder
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gptscreenshotpack.core.OutputFormat
import com.gptscreenshotpack.core.PackSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class PackDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(name: String, width: Int = 144, height: Int = 320, jpeg: Boolean = false): File {
        val file = File(PackCache(context).newRun(), name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            file.outputStream().use { assertTrue(bitmap.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 95, it)) }
        } finally { bitmap.recycle() }
        return file
    }
    private fun uri(file: File) = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    @Test fun jpgToHeicRoundtripAndHeicInput() = runBlocking {
        val source = fixture("Screenshot_20261005_013022.jpg", jpeg = true)
        val original = source.readBytes()
        val result = PackProcessor(context).process(listOf(uri(source)), PackSettings()) { _, _ -> }
        assertEquals(result.errors.toString(), 1, result.successCount)
        // This is intentionally a device acceptance test: PNG fallback is not HEIC success.
        assertTrue("本机 HEIC 不可用：${result.notes}", result.notes.isEmpty())
        val heic = File(source.parentFile, "roundtrip.heic")
        ZipFile(result.zip!!).use { archive ->
            val entry = archive.entries().nextElement()
            assertEquals("resized_Screenshot_20261005_013022.heic", entry.name)
            archive.getInputStream(entry).use { input -> heic.outputStream().use { input.copyTo(it) } }
        }
        val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(heic))
        try { assertEquals(72, decoded.width); assertEquals(160, decoded.height) } finally { decoded.recycle() }
        val again = PackProcessor(context).process(listOf(uri(heic)), PackSettings(format = OutputFormat.PNG)) { _, _ -> }
        assertEquals(1, again.successCount)
        assertArrayEquals(original, source.readBytes())
    }
    @Test fun pngFiftyImagesLongScreenshotSlicingAndFailures() = runBlocking {
        val source = fixture("截图.png")
        val settings = PackSettings(format = OutputFormat.PNG)
        val batch = PackProcessor(context).process(List(50) { uri(source) }, settings) { _, _ -> }
        assertEquals(50, batch.successCount)
        assertEquals(50, batch.outputCount)
        ZipFile(batch.zip!!).use { assertEquals(50, it.size()) }
        val long = fixture("long.png", 32, 19399)
        val longResult = PackProcessor(context).process(listOf(uri(long)), settings) { _, _ -> }
        assertEquals(1, longResult.outputCount)
        val huge = fixture("tall.png", 32, 28000)
        val banded = Bitmap.createBitmap(32, 28000, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(banded)
            val paint = Paint()
            listOf(Color.RED, Color.GREEN, Color.BLUE).forEachIndexed { i, color ->
                paint.color = color
                canvas.drawRect(0f, (28000L * i / 3).toFloat(), 32f, (28000L * (i + 1) / 3).toFloat(), paint)
            }
            huge.outputStream().use { assertTrue(banded.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { banded.recycle() }
        val sliced = PackProcessor(context).process(listOf(uri(huge)), settings.copy(scalePercent = 100)) { _, _ -> }
        assertEquals(3, sliced.outputCount)
        ZipFile(sliced.zip!!).use { archive ->
            assertEquals(listOf("resized_tall_1.png", "resized_tall_2.png", "resized_tall_3.png"), archive.entries().asSequence().map { it.name }.toList())
            var totalHeight = 0
            archive.entries().asSequence().forEachIndexed { i, entry ->
                val bytes = archive.getInputStream(entry).use { it.readBytes() }
                // PNG IHDR: true color (RGB=2 or RGBA=6), 8 bits/channel.
                assertEquals(8, bytes[24].toInt())
                assertTrue(bytes[25].toInt() in listOf(2, 6))
                val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                try {
                    assertEquals(32, bitmap.width)
                    assertEquals(listOf(Color.RED, Color.GREEN, Color.BLUE)[i], bitmap.getPixel(16, bitmap.height / 2))
                    totalHeight += bitmap.height
                } finally { bitmap.recycle() }
            }
            assertEquals(28000, totalHeight)
        }
        val broken = fixture("broken.png").apply { writeText("not an image") }
        val partial = PackProcessor(context).process(listOf(uri(broken), uri(source)), settings) { _, _ -> }
        assertEquals(1, partial.successCount)
        assertEquals(1, partial.errors.size)
        val allFailed = PackProcessor(context).process(listOf(uri(broken)), settings) { _, _ -> }
        assertNull(allFailed.zip)
    }
    @Test fun noNetworkPermissionAndShareFilters() {
        val info = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        assertFalse(info.requestedPermissions.orEmpty().contains("android.permission.INTERNET"))
        for (action in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
            val intent = Intent(action).setType("image/png").setPackage(context.packageName)
            assertTrue(context.packageManager.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty())
        }
    }
}
