package com.gptscreenshotpack

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
            it.zip?.let(outputs::add)
        }
    }
    private fun archive(result: PackResult): ZipFile {
        val output = checkNotNull(result.zip) { result.errors.toString() }
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
        assertNull(process(listOf(broken), settings).zip)
    }

    @Test fun publicPublicationTempCleanupAndCollisionKeepOutput() = runBlocking {
        val storage = PackStorage(context)
        val png = fixture("public.png")
        val result = process(listOf(png), PackSettings(format = OutputFormat.PNG))
        val output = checkNotNull(result.zip)
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
        val second = storage.publish(staged).also(outputs::add)
        storage.cleanRun(secondRun)
        assertNotEquals(output.uri, second.uri)
        assertNotEquals(output.name, second.name)
        assertEquals(output.size, second.size)
        context.contentResolver.openInputStream(output.uri)!!.use { assertTrue(it.read() >= 0) }
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
