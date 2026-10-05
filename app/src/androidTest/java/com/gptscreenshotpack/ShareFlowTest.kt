package com.gptscreenshotpack

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gptscreenshotpack.core.OutputFormat
import com.gptscreenshotpack.core.PackSettings
import com.gptscreenshotpack.core.ResolutionMode
import com.gptscreenshotpack.core.ZipNaming
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
class ShareFlowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val store by lazy { SettingsStore(context) }
    private lateinit var previousSettings: PackSettings
    private lateinit var previousNaming: ZipNaming
    private val outputs = mutableListOf<PackOutput>()
    private val fixtures by lazy { File(context.cacheDir, "packs/share_${UUID.randomUUID()}").apply { check(mkdirs()) } }

    @Before fun setup() = runBlocking {
        previousSettings = store.settings.first()
        previousNaming = store.zipNaming.first()
        store.save(PackSettings(format = OutputFormat.PNG))
        store.saveZipNaming(ZipNaming())
        if (Build.VERSION.SDK_INT == 28) {
            instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.WRITE_EXTERNAL_STORAGE}")
                .use { ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes() } }
        }
    }
    @After fun cleanup() = runBlocking {
        outputs.forEach { context.contentResolver.delete(it.uri, null, null) }
        fixtures.deleteRecursively()
        store.save(previousSettings)
        store.saveZipNaming(previousNaming)
    }
    private fun image(name: String): File = File(fixtures, name).also { file ->
        val bitmap = Bitmap.createBitmap(16, 24, Bitmap.Config.ARGB_8888)
        try { file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
    private fun uri(file: File) = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    private fun single(file: File) = Intent(context, ShareActivity::class.java).apply {
        action = Intent.ACTION_SEND; type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri(file))
        clipData = ClipData.newRawUri("input", uri(file))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    private fun model(scenario: ActivityScenario<ShareActivity>): ShareViewModel {
        lateinit var value: ShareViewModel
        scenario.onActivity { value = ViewModelProvider(it)[ShareViewModel::class.java] }
        return value
    }
    private suspend fun awaitPhase(model: ShareViewModel, phase: SharePhase) =
        withTimeout(30_000) { model.state.first { it.phase == phase } }
    private suspend fun awaitClosed(scenario: ActivityScenario<ShareActivity>) = withTimeout(10_000) {
        while (scenario.state != Lifecycle.State.DESTROYED) delay(25)
    }

    private fun assertDimensions(output: PackOutput, width: Int, height: Int) {
        val inspect = File(fixtures, "dimensions.zip")
        context.contentResolver.openInputStream(output.uri)!!.use { input -> inspect.outputStream().use { input.copyTo(it) } }
        ZipFile(inspect).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes)))
                try { assertEquals(width, bitmap.width); assertEquals(height, bitmap.height) }
                finally { bitmap.recycle() }
            }
        }
    }

    @Test fun namingRotationStoredZipAutoExitAndRememberedNextRequest() = runBlocking {
        val configured = PackSettings(50, OutputFormat.PNG, 75, 90)
        store.save(configured)
        val first = image("IMG20261005123801.png")
        val second = image("截图.png")
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        try {
            val intent = Intent(context, ShareActivity::class.java).apply {
                action = Intent.ACTION_SEND_MULTIPLE; type = "image/png"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri(first), uri(second)))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ActivityScenario.launch<ShareActivity>(intent).use { scenario ->
                val model = model(scenario)
                val initial = awaitPhase(model, SharePhase.NAMING)
                assertTrue(initial.naming.appendDateTime)
                assertEquals(ResolutionMode.ORIGINAL, initial.resolutionMode)
                assertEquals(50, initial.reducedScalePercent)
                assertNull(initial.result)
                val base = "聊天记录_${UUID.randomUUID()}"
                scenario.onActivity {
                    model.editName("$base.zip"); model.editDateTime(false)
                    model.editResolutionMode(ResolutionMode.REDUCED)
                }
                scenario.recreate()
                assertSame(model, model(scenario))
                assertEquals("$base.zip", model.state.value.naming.base)
                assertFalse(model.state.value.naming.appendDateTime)
                assertEquals(ResolutionMode.REDUCED, model.state.value.resolutionMode)
                scenario.onActivity { model.confirm() }
                val result = checkNotNull(awaitPhase(model, SharePhase.SAVED).result)
                val output = checkNotNull(result.zip).also(outputs::add)
                assertEquals("$base.zip", output.name)
                assertEquals(PackStorage.OUTPUT_PATH, output.directory)
                val inspect = File(fixtures, "inspect.zip")
                context.contentResolver.openInputStream(output.uri)!!.use { input -> inspect.outputStream().use { input.copyTo(it) } }
                ZipFile(inspect).use { zip ->
                    assertEquals(listOf("resized_IMG20261005123801.png", "resized_截图.png"), zip.entries().asSequence().map { it.name }.toList())
                    zip.entries().asSequence().forEach { assertEquals(ZipEntry.STORED, it.method) }
                }
                awaitClosed(scenario)
                assertDimensions(output, 8, 12)
                assertEquals(configured.copy(resolutionMode = ResolutionMode.REDUCED), store.settings.first())
                assertEquals(ZipNaming(base, false), store.zipNaming.first())
            }
            ActivityScenario.launch<ShareActivity>(single(first)).use { scenario ->
                val model = model(scenario)
                val next = awaitPhase(model, SharePhase.NAMING)
                assertEquals(store.zipNaming.first(), next.naming)
                assertEquals(ResolutionMode.REDUCED, next.resolutionMode)
                assertEquals(50, next.reducedScalePercent)
                scenario.onActivity { model.editResolutionMode(ResolutionMode.ORIGINAL); model.confirm() }
                val result = checkNotNull(awaitPhase(model, SharePhase.SAVED).result)
                val output = checkNotNull(result.zip).also(outputs::add)
                assertDimensions(output, 16, 24) // Original ignores the saved 50% reduction ratio.
                assertEquals(100, result.settings.effectiveScalePercent)
                assertEquals(50, result.settings.scalePercent)
                assertEquals(configured, store.settings.first()) // Both saved qualities are untouched.
                awaitClosed(scenario)
            }
            assertEquals("Sharing must never open the main screen", 0, monitor.hits)
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun clipDataFallbackKeepsOrderAndFailureRemainsVisibleUntilClose() = runBlocking {
        val broken = File(fixtures, "broken.png").apply { writeText("not an image") }
        val intent = single(broken).apply { removeExtra(Intent.EXTRA_STREAM) }
        assertEquals(listOf(uri(broken)), sharedImages(intent))
        assertTrue(sharedImages(Intent(Intent.ACTION_MAIN)).isEmpty())
        ActivityScenario.launch<ShareActivity>(intent).use { scenario ->
            val model = model(scenario)
            awaitPhase(model, SharePhase.NAMING)
            scenario.onActivity { model.confirm() }
            val failure = awaitPhase(model, SharePhase.ERROR)
            assertNull(failure.result)
            assertTrue(failure.error!!.contains("未生成 ZIP"))
            assertNotEquals(Lifecycle.State.DESTROYED, scenario.state)
            scenario.onActivity { model.retry() }
            assertEquals(SharePhase.NAMING, model.state.value.phase)
            scenario.onActivity { model.close() }
            awaitClosed(scenario)
        }
    }
}
