package com.gptscreenshotpack

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import com.gptscreenshotpack.core.OutputFormat
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val model: PackViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) receive(intent)
        setContent {
            MaterialTheme {
                val state by model.state.collectAsStateWithLifecycle()
                val lifecycleState by lifecycle.currentStateAsState()
                LaunchedEffect(state.autoSharePending, lifecycleState) {
                    if (state.autoSharePending && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) {
                        model.consumeShare()
                        state.result?.zip?.let { share(it) }
                    }
                }
                PackScreen(state, model) { share(it) }
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); receive(intent) }

    private fun receive(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return
        val streams = if (intent.action == Intent.ACTION_SEND_MULTIPLE)
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        val incoming = streams.ifEmpty {
            intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }.orEmpty()
        }
        model.start(incoming, autoShare = true)
    }

    private fun share(file: File) {
        try {
            check(file.isFile) { "ZIP 缓存已被清理" }
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("截图 ZIP", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "分享截图 ZIP"))
        } catch (e: Exception) { model.message("无法分享：${e.message}") }
    }
}

@Composable
private fun PackScreen(state: PackUiState, model: PackViewModel, share: (File) -> Unit) {
    var diagnosticPage by remember { mutableStateOf(false) }
    var confirmClean by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        if (it.isNotEmpty()) model.start(it, autoShare = false)
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("GPT Screenshot Pack", style = MaterialTheme.typography.headlineSmall)
            if (state.busy) {
                Text("正在处理图片  ${state.done} / ${state.total}")
                if (state.total > 0) LinearProgressIndicator(progress = { state.done.toFloat() / state.total }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedButton(onClick = model::cancel) { Text("取消") }
            }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            if (diagnosticPage) {
                OutlinedButton(onClick = { diagnosticPage = false }) { Text("返回") }
                Text(state.diagnostics ?: "正在查询编码器…", style = MaterialTheme.typography.bodySmall)
            } else {
                val settings = state.settings
                val enabled = state.ready && !state.busy
                Text("缩放比例：${settings.scalePercent}%")
                Slider(value = settings.scalePercent.toFloat(), onValueChange = {
                    model.setSettings(settings.copy(scalePercent = it.roundToInt()))
                }, valueRange = 25f..100f, steps = 74, enabled = enabled)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(33, 50, 60, 67, 75, 100).forEach { value ->
                        TextButton(onClick = { model.setSettings(settings.copy(scalePercent = value)) },
                            enabled = enabled, contentPadding = PaddingValues(4.dp), modifier = Modifier.weight(1f)) { Text("$value") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutputFormat.entries.forEach { format ->
                        FilterChip(selected = settings.format == format, onClick = { model.setSettings(settings.copy(format = format)) },
                            label = { Text(format.name) }, enabled = enabled)
                    }
                }
                if (settings.format != OutputFormat.PNG) {
                    Text("${settings.format} 质量：${settings.quality}")
                    fun quality(value: Int) {
                        model.setSettings(if (settings.format == OutputFormat.HEIC) settings.copy(heicQuality = value) else settings.copy(jpegQuality = value))
                    }
                    Slider(value = settings.quality.toFloat(), onValueChange = { quality(it.roundToInt()) },
                        valueRange = 1f..100f, steps = 98, enabled = enabled)
                    Row { listOf(85, 90, 95, 100).forEach { q -> TextButton(onClick = { quality(q) }, enabled = enabled) { Text("$q") } } }
                }
                Text("编码器：自动 · CQ：自动 · Grid：自动")
                Text("最终尺寸超过 16384 px 自动均匀切片\n文件名前缀：resized_\nHEIC 编码失败时回退 PNG", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { picker.launch(arrayOf("image/*")) }, enabled = enabled) { Text("选择图片并测试") }
                state.result?.let { result ->
                    HorizontalDivider()
                    Text("成功：${result.successCount} · 失败：${result.errors.size} · 输入：${result.inputCount} · 输出：${result.outputCount}")
                    Text("原始已知大小：${bytes(result.inputBytes)}" + if (result.unknownInputSizes > 0) "（${result.unknownInputSizes} 张大小未知）" else "")
                    Text("输出图片：${bytes(result.outputBytes)} · ZIP：${bytes(result.zip?.length() ?: 0)}")
                    Text("耗时：${result.elapsedMs} ms · 缩放：${result.settings.scalePercent}% · 格式：${result.settings.format}" +
                        if (result.settings.format == OutputFormat.PNG) "" else " · 质量：${result.settings.quality}")
                    Text("实际 codec / 硬件加速 / bitrate mode：Unknown", style = MaterialTheme.typography.bodySmall)
                    result.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    result.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    result.zip?.let { file -> Button(onClick = { share(file) }, enabled = !state.busy) { Text("分享 ZIP") } }
                    if (result.zip == null) Text("所有输入均失败，未生成 ZIP")
                }
                HorizontalDivider()
                OutlinedButton(onClick = { diagnosticPage = true; model.diagnostics() }) { Text("编码器信息") }
                OutlinedButton(onClick = { confirmClean = true }, enabled = enabled) { Text("立即清理缓存") }
                Text("旧缓存于启动时清理（24 小时）。清理后旧分享链接将失效。\n全部处理在本地完成，不申请网络权限。\n0.1.0 · Android 9+", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (confirmClean) AlertDialog(onDismissRequest = { confirmClean = false }, title = { Text("清理应用缓存？") },
        text = { Text("已生成 ZIP 的分享链接将失效。原始图片不受影响。") },
        confirmButton = { TextButton(onClick = { confirmClean = false; model.clean() }) { Text("清理") } },
        dismissButton = { TextButton(onClick = { confirmClean = false }) { Text("取消") } })
}

private fun bytes(value: Long): String = String.format(Locale.getDefault(), "%.2f MiB", value / 1048576.0)
