package com.gptscreenshotpack

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gptscreenshotpack.core.OutputContainer
import com.gptscreenshotpack.core.OutputFormat
import com.gptscreenshotpack.core.PackSettings
import com.gptscreenshotpack.core.ResolutionMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class PackDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val outputs = mutableListOf<PackOutput>()
    private val fixtures by lazy { File(context.cacheDir, "packs/test_${UUID.randomUUID()}").apply { check(mkdirs()) } }

    @Before fun legacyPermission() {
        if (Build.VERSION.SDK_INT == 28) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.WRITE_EXTERNAL_STORAGE}")
                .use { descriptor -> ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() } }
        }
    }
    @After fun cleanupOnlyTestFiles() {
        // Explicit test teardown, never the application's automatic Output cleanup.
        outputs.forEach { context.contentResolver.delete(it.uri, null, null) }
        fixtures.deleteRecursively()
    }
    private fun fixture(name: String, width: Int = 144, height: Int = 320, jpeg: Boolean = false): File {
        val file = File(fixtures, name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            file.outputStream().use { assertTrue(bitmap.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 95, it)) }
        } finally { bitmap.recycle() }
        return file
    }
    private fun uri(file: File) = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    private suspend fun process(files: List<File>, settings: PackSettings = PackSettings()): PackResult {
        return PackProcessor(context).process(files.map { uri(it) }, settings) { _, _ -> }.also {
            it.output?.let(outputs::add)
        }
    }
    private fun archive(result: PackResult): ZipFile {
        val output = checkNotNull(result.output) { result.errors.toString() }
        val file = File(fixtures, "inspect_${UUID.randomUUID()}.zip")
        context.contentResolver.openInputStream(output.uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        return ZipFile(file)
    }

    @Test fun jpgToHeicRoundtripAndHeicInputAtOriginalResolution() = runBlocking {
        val source = fixture("IMG20261005123801.jpg", jpeg = true)
        val original = source.readBytes()
        val result = process(listOf(source))
        assertEquals(result.errors.toString(), 1, result.successCount)
        // This is intentionally a device acceptance test: PNG fallback is not HEIC success.
        assertTrue("本机 HEIC 不可用：${result.notes}", result.notes.isEmpty())
        val heic = File(fixtures, "roundtrip.heic")
        archive(result).use { zip ->
            val entry = zip.entries().nextElement()
            assertEquals("resized_IMG20261005123801.heic", entry.name)
            assertEquals(ZipEntry.DEFLATED, entry.method)
            assertTrue(entry.compressedSize > 0)
            zip.getInputStream(entry).use { input -> heic.outputStream().use { input.copyTo(it) } }
        }
        val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(heic))
        try { assertEquals(144, decoded.width); assertEquals(320, decoded.height) } finally { decoded.recycle() }
        val again = process(listOf(heic), PackSettings(format = OutputFormat.PNG))
        assertEquals(1, again.successCount)
        assertArrayEquals(original, source.readBytes())
    }

    @Test fun pngFiftyImagesLongScreenshotSlicingAndFailures() = runBlocking {
        val source = fixture("截图.png")
        val settings = PackSettings(format = OutputFormat.PNG)
        val batch = process(List(50) { source }, settings)
        assertEquals(50, batch.successCount)
        assertEquals(50, batch.outputCount)
        archive(batch).use { zip ->
            assertEquals(50, zip.size())
            zip.entries().asSequence().forEach { assertEquals(ZipEntry.DEFLATED, it.method) }
        }
        val long = fixture("long.png", 32, 19399)
        val longResult = process(listOf(long), settings)
        assertEquals(2, longResult.outputCount) // 100% now slices this long image.
        val half = process(listOf(long), settings.copy(scalePercent = 50, resolutionMode = ResolutionMode.REDUCED))
        assertEquals(1, half.outputCount)
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
        val sliced = process(listOf(huge), settings)
        assertEquals(3, sliced.outputCount)
        archive(sliced).use { zip ->
            assertEquals(listOf("resized_tall_1.png", "resized_tall_2.png", "resized_tall_3.png"), zip.entries().asSequence().map { it.name }.toList())
            var totalHeight = 0
            zip.entries().asSequence().forEachIndexed { i, entry ->
                assertEquals(ZipEntry.DEFLATED, entry.method)
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
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
        val partial = process(listOf(broken, source), settings)
        assertEquals(1, partial.successCount)
        assertEquals(1, partial.errors.size)
        assertNull(process(listOf(broken), settings).output)
    }

    @Test fun publicPublicationTempCleanupAndCollisionKeepOutput() = runBlocking {
        val storage = PackStorage(context)
        val png = fixture("public.png")
        val result = process(listOf(png), PackSettings(format = OutputFormat.PNG))
        val output = checkNotNull(result.output)
        assertEquals("Download/GPT Screenshot Pack/Output/", output.directory)
        assertTrue(output.name.startsWith("GPT_Screenshots_"))
        assertTrue(output.size > 0)
        if (Build.VERSION.SDK_INT >= 29) {
            context.contentResolver.query(output.uri,
                arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.IS_PENDING), null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertEquals(PackStorage.OUTPUT_PATH, it.getString(0))
                assertEquals(0, it.getInt(1))
            }
        }
        val run = storage.newRun()
        val temp = storage.createTemp(run, "resized_pending.png", "image/png")
        storage.write(temp).use { it.write(byteArrayOf(1, 2, 3)) }
        storage.cleanTemp()
        storage.read(temp).use { assertEquals(1, it.read()) } // Active share tasks survive main-screen cleanup.
        storage.cleanRun(run)
        assertTrue(storage.cleanTemp(expiredOnly = true) >= 0)
        context.contentResolver.openInputStream(output.uri)!!.use { assertTrue(it.read() >= 0) }
        // Publishing a same-name ZIP twice must preserve both contents and return distinct URIs.
        val secondRun = storage.newRun()
        val staged = storage.createTemp(secondRun, output.name, "application/zip")
        context.contentResolver.openInputStream(output.uri)!!.use { input -> storage.write(staged).use { input.copyTo(it) } }
        val second = storage.publish(staged, OutputContainer.ZIP).also(outputs::add)
        storage.cleanRun(secondRun)
        assertNotEquals(output.uri, second.uri)
        assertNotEquals(output.name, second.name)
        assertEquals(output.size, second.size)
        context.contentResolver.openInputStream(output.uri)!!.use { assertTrue(it.read() >= 0) }
    }

    @Test fun pdfUsesScaledJpegPagesInInputOrder() = runBlocking {
        val first = colorBands("first.png", 480, 320, listOf(Color.RED, Color.TRANSPARENT))
        val long = colorBands("long.png", 96, 28_000, listOf(Color.RED, Color.GREEN, Color.BLUE))
        val last = colorBands("last.png", 320, 480, listOf(Color.BLUE))
        val invalid = File(fixtures, "invalid.png").apply { writeText("broken input") }
        val settings = PackSettings(container = OutputContainer.PDF, format = OutputFormat.PNG,
            resolutionMode = ResolutionMode.SCALE_B, scalePercentB = 50)
        val result = process(listOf(first, invalid, long, last), settings)
        val output = checkNotNull(result.output) { result.errors.toString() }
        assertEquals(3, result.successCount)
        assertEquals(1, result.errors.size)
        assertEquals(3, result.outputCount)
        assertEquals("application/pdf", context.contentResolver.getType(output.uri))
        assertEquals(PackStorage.OUTPUT_PATH, output.directory)
        assertTrue(output.name.endsWith(".pdf"))
        assertTrue("PDF should be much smaller than raw RGB", output.size < 250_000)
        PdfRenderer(checkNotNull(context.contentResolver.openFileDescriptor(output.uri, "r"))).use { pdf ->
            assertEquals(3, pdf.pageCount)
            pdf.openPage(0).use { page ->
                assertEquals(240, page.width)
                assertEquals(160, page.height)
                assertPageColors(page, listOf(Color.RED, Color.WHITE))
            }
            pdf.openPage(1).use { page ->
                assertEquals(48, page.width)
                assertEquals(14_000, page.height)
                assertPageColors(page, listOf(Color.RED, Color.GREEN, Color.BLUE))
            }
            pdf.openPage(2).use { page ->
                assertEquals(160, page.width)
                assertEquals(240, page.height)
                assertPageColors(page, listOf(Color.BLUE))
            }
        }
        val verification = File(context.getExternalFilesDir(null), "verification").apply { mkdirs() }
        context.contentResolver.openInputStream(output.uri)!!.use { input ->
            File(verification, "pdf-output.pdf").outputStream().use { input.copyTo(it) }
        }
    }

    @Test fun pdfUsesItsOwnQualityAndKeepsOnePageAfterSplitting() = runBlocking {
        val source = colorBands("very-long.png", 96, 28_000, listOf(Color.RED, Color.GREEN, Color.BLUE), textured = true)
        val settings = PackSettings(container = OutputContainer.PDF, jpegQuality = 100)
        val high = checkNotNull(process(listOf(source), settings.copy(pdfQuality = 100)).output)
        val low = checkNotNull(process(listOf(source), settings.copy(pdfQuality = 1)).output)
        assertTrue(low.size < high.size)
        PdfRenderer(checkNotNull(context.contentResolver.openFileDescriptor(high.uri, "r"))).use { pdf ->
            assertEquals(1, pdf.pageCount)
            pdf.openPage(0).use { page ->
                assertTrue(page.height in 14_399..14_400)
                assertEquals(49.37, page.width.toDouble(), 1.0)
                assertPageColors(page, listOf(Color.RED, Color.GREEN, Color.BLUE))
            }
        }
        assertNull(process(listOf(File(fixtures, "missing.png")), settings).output)
    }

    @Test fun jpegRegionsHandleLargeTargetsAndExifOrientation() = runBlocking {
        val source = colorBands("screenshot.jpg", 320, 19_400, listOf(Color.RED, Color.BLUE), jpeg = true)
        ExifInterface(source.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val settings = PackSettings(format = OutputFormat.JPEG, resolutionMode = ResolutionMode.WIDTH,
            targetWidth = 60_000)
        val result = process(listOf(source), settings)
        assertEquals(result.errors.toString(), 1, result.successCount)
        archive(result).use { zip ->
            assertTrue(zip.size() > 3)
            val entries = zip.entries().toList()
            val dimensions = entries.map { entry ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, bounds) }
                assertTrue(bounds.outWidth in 1..16_384 && bounds.outHeight in 1..16_384)
                bounds.outWidth to bounds.outHeight
            }
            val columns = dimensions.runningFold(0) { width, tile -> width + tile.first }.indexOf(60_000)
            assertTrue(columns > 0)
            val rows = dimensions.chunked(columns)
            rows.forEach { row -> assertEquals(60_000, row.sumOf { it.first }) }
            assertEquals(990, rows.sumOf { it.first().second })
            val first = zip.getInputStream(entries.first()).use { BitmapFactory.decodeStream(it) }
            val last = zip.getInputStream(entries.last()).use { BitmapFactory.decodeStream(it) }
            try {
                assertColorNear(Color.BLUE, first.getPixel(first.width / 2, first.height / 2))
                assertColorNear(Color.RED, last.getPixel(last.width / 2, last.height / 2))
            } finally { first.recycle(); last.recycle() }
        }
    }

    private fun colorBands(name: String, width: Int, height: Int, colors: List<Int>, jpeg: Boolean = false,
        textured: Boolean = false): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(bitmap)
            val paint = Paint()
            colors.forEachIndexed { index, color ->
                paint.color = color
                canvas.drawRect(0f, (height * index / colors.size).toFloat(), width.toFloat(),
                    (height * (index + 1) / colors.size).toFloat(), paint)
            }
            if (textured) {
                paint.color = Color.BLACK
                for (y in 0 until height step 5) canvas.drawRect(0f, y.toFloat(), 20f, (y + 2).toFloat(), paint)
            }
            File(fixtures, name).apply {
                outputStream().use {
                    assertTrue(bitmap.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 95, it))
                }
            }
        } finally { bitmap.recycle() }
    }

    private fun assertPageColors(page: PdfRenderer.Page, colors: List<Int>) {
        val width = 64
        val height = 240
        val rendered = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val transform = android.graphics.Matrix().apply {
                setScale(width.toFloat() / page.width, height.toFloat() / page.height)
            }
            page.render(rendered, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            colors.forEachIndexed { index, color ->
                assertColorNear(color, rendered.getPixel(width / 2, height * (2 * index + 1) / (2 * colors.size)))
            }
        } finally { rendered.recycle() }
    }

    private fun assertColorNear(expected: Int, actual: Int) {
        assertTrue("expected ${Integer.toHexString(expected)}, got ${Integer.toHexString(actual)}",
            kotlin.math.abs(Color.red(expected) - Color.red(actual)) <= 6 &&
                kotlin.math.abs(Color.green(expected) - Color.green(actual)) <= 6 &&
                kotlin.math.abs(Color.blue(expected) - Color.blue(actual)) <= 6)
    }

    @Test fun noNetworkPermissionAndShareFilters() {
        val info = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        assertFalse(info.requestedPermissions.orEmpty().contains("android.permission.INTERNET"))
        for (action in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
            val intent = Intent(action).setType("image/png").setPackage(context.packageName)
            val resolved = context.packageManager.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            assertEquals(listOf(ShareActivity::class.java.name), resolved.map { it.activityInfo.name })
        }
        assertEquals(MainActivity::class.java.name, context.packageManager.getLaunchIntentForPackage(context.packageName)?.component?.className)
    }
}
