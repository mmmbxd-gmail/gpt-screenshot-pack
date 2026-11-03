package com.gptscreenshotpack

import android.Manifest
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gptscreenshotpack.core.OutputFormat
import com.gptscreenshotpack.core.PackSettings
import com.gptscreenshotpack.core.ResolutionMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class MainRetestTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun repeatUsesOriginalSelectionAndCurrentSettingsAfterRotation() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = SettingsStore(context)
        val previousSettings = store.settings.first()
        val previousNaming = store.zipNaming.first()
        val outputs = mutableListOf<PackOutput>()
        val fixtures = File(context.cacheDir, "packs/retest_${UUID.randomUUID()}").apply { check(mkdirs()) }
        val source = File(fixtures, "original.png")
        val bitmap = Bitmap.createBitmap(16, 24, Bitmap.Config.ARGB_8888)
        try { source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        val original = source.readBytes()
        try {
            store.save(PackSettings(scalePercent = 50, resolutionMode = ResolutionMode.REDUCED, scalePercentB = 75))
            store.saveShareOptions(previousNaming, ResolutionMode.REDUCED, OutputFormat.PNG)
            if (Build.VERSION.SDK_INT == 28) {
                instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.WRITE_EXTERNAL_STORAGE}")
                    .use { ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes() } }
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: PackViewModel
                scenario.onActivity { model = ViewModelProvider(it)[PackViewModel::class.java] }
                withTimeout(30_000) { model.state.first { it.ready } }
                compose.onNodeWithText("再次测试").performScrollTo().assertIsNotEnabled()
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", source)
                scenario.onActivity { model.start(listOf(uri, uri)) }
                var result = withTimeout(30_000) { model.state.first { !it.busy && it.result != null }.result!! }
                assertEquals(2, model.state.value.selectedInputCount)
                assertDimensions(checkNotNull(result.zip).also(outputs::add), fixtures, 8, 12)

                scenario.recreate()
                scenario.onActivity { assertSame(model, ViewModelProvider(it)[PackViewModel::class.java]) }
                assertEquals(2, model.state.value.selectedInputCount)
                scenario.onActivity { model.setSettings(model.state.value.settings.copy(resolutionMode = ResolutionMode.SCALE_B)) }
                compose.onNodeWithText("再次测试").performScrollTo().assertIsEnabled().performClick()
                val first = result
                result = withTimeout(30_000) { model.state.first { !it.busy && it.result != null && it.result !== first }.result!! }
                assertEquals(ResolutionMode.SCALE_B, result.settings.resolutionMode)
                assertDimensions(checkNotNull(result.zip).also(outputs::add), fixtures, 12, 18)

                scenario.onActivity { model.setSettings(model.state.value.settings.copy(resolutionMode = ResolutionMode.WIDTH, targetWidth = 20)) }
                compose.onNodeWithText("再次测试").performScrollTo().assertIsEnabled().performClick()
                val second = result
                result = withTimeout(30_000) { model.state.first { !it.busy && it.result != null && it.result !== second }.result!! }
                assertDimensions(checkNotNull(result.zip).also(outputs::add), fixtures, 20, 30)
                assertArrayEquals(original, source.readBytes())
                assertEquals(3, outputs.map { it.uri }.distinct().size)
            }
        } finally {
            outputs.forEach { context.contentResolver.delete(it.uri, null, null) }
            fixtures.deleteRecursively()
            store.save(previousSettings)
            store.saveShareOptions(previousNaming, previousSettings.resolutionMode, previousSettings.format)
        }
    }

    private fun assertDimensions(output: PackOutput, directory: File, width: Int, height: Int) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(directory, "inspect.zip")
        context.contentResolver.openInputStream(output.uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        ZipFile(file).use { zip ->
            assertEquals(2, zip.size())
            zip.entries().asSequence().forEach { entry ->
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
                try { assertEquals(width, bitmap.width); assertEquals(height, bitmap.height) }
                finally { bitmap.recycle() }
            }
        }
    }
}
