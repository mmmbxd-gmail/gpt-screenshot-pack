package com.gptscreenshotpack

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gptscreenshotpack.core.OutputFormat
import com.gptscreenshotpack.core.ResolutionMode
import com.gptscreenshotpack.core.ZipNaming
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ShareLayoutTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun dropdownAndWidthModeFitPortraitWithoutScrolling() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = SettingsStore(context)
        val settings = store.settings.first()
        val naming = store.zipNaming.first()
        val source = File(context.cacheDir, "packs/layout/input.png").apply { parentFile!!.mkdirs() }
        val bitmap = Bitmap.createBitmap(16, 24, Bitmap.Config.ARGB_8888)
        try { source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", source)
        try {
            store.saveShareOptions(ZipNaming(), ResolutionMode.ORIGINAL, OutputFormat.HEIC)
            val intent = Intent(context, ShareActivity::class.java).apply {
                action = Intent.ACTION_SEND
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ActivityScenario.launch<ShareActivity>(intent).use { scenario ->
                lateinit var model: ShareViewModel
                scenario.onActivity { model = ViewModelProvider(it)[ShareViewModel::class.java] }
                withTimeout(10_000) { model.state.first { it.phase == SharePhase.NAMING } }
                compose.waitForIdle()
                assertFormFits()
                capture("share-original.png")
                compose.onNodeWithText("原始分辨率").performClick()
                compose.onNodeWithText("按缩放比例 A · ${settings.scalePercent}%").assertIsDisplayed()
                compose.onNodeWithText("按缩放比例 B · ${settings.scalePercentB}%").assertIsDisplayed()
                compose.onNodeWithText("按指定宽度（保持比例）").performClick()
                compose.waitForIdle()
                compose.onNodeWithText("指定宽度").assertIsDisplayed()
                assertFormFits()
                capture("share-width.png")
                compose.onNodeWithText("取消").performClick()
            }
        } finally {
            store.save(settings)
            store.saveShareOptions(naming, settings.resolutionMode, settings.format)
            source.delete()
        }
    }

    private fun assertFormFits() {
        listOf("本次任务设置", "文件名主体", "自动附加日期时间", "HEIC", "JPEG", "PNG", "开始处理", "取消").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        val scroll = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals("Settings should fit the portrait viewport", 0f, scroll.maxValue(), 0.5f)
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "verification").apply { mkdirs() }
            File(directory, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { image.recycle() }
    }
}
