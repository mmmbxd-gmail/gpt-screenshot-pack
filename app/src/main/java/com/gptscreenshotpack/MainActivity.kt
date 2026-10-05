package com.gptscreenshotpack

import android.content.ClipData
import android.content.Intent
import android.Manifest
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gptscreenshotpack.core.OutputFormat
import com.gptscreenshotpack.core.PackPresets
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val model: PackViewModel by viewModels()
    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        model.storagePermissionResult(it)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val state by model.state.collectAsStateWithLifecycle()
                LaunchedEffect(state.askStoragePermission) {
                    if (state.askStoragePermission) {
                        model.permissionDialogLaunching()
                        storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                }
                PackScreen(state, model) { share(it) }
            }
        }
    }

    private fun share(output: PackOutput) {
        try {
            contentResolver.openFileDescriptor(output.uri, "r")?.use { } ?: error("ZIP 已被删除")
            val uri = output.uri
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
private fun PackScreen(state: PackUiState, model: PackViewModel, share: (PackOutput) -> Unit) {
    var diagnosticPage by remember { mutableStateOf(false) }
    var confirmCleanOutput by remember { mutableStateOf<Boolean?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        if (it.isNotEmpty()) model.start(it)
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("GPT Screenshot Pack", style = MaterialTheme.typography.headlineSmall)
            if (state.busy) {
                Text(state.busyLabel + if (state.total > 0) "  ${state.done} / ${state.total}" else "")
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
                val enabled = state.ready && !state.busy && !state.awaitingStoragePermission
                Text("缩放比例：${settings.scalePercent}%")
                Slider(value = settings.scalePercent.toFloat(), onValueChange = {
                    model.setSettings(settings.copy(scalePercent = it.roundToInt()))
                }, valueRange = 25f..100f, steps = 74, enabled = enabled)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PackPresets.scales.forEach { value ->
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
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PackPresets.qualities.forEach { q ->
                            TextButton(onClick = { quality(q) }, enabled = enabled,
                                contentPadding = PaddingValues(4.dp), modifier = Modifier.weight(1f)) { Text("$q") }
                        }
                    }
                }
                Text("编码器：自动 · CQ：自动 · Grid：自动")
                Text("最终尺寸超过 16384 px 自动均匀切片\n文件名前缀：resized_\nHEIC 编码失败时回退 PNG", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { picker.launch(arrayOf("image/*")) }, enabled = enabled) { Text("选择图片并测试") }
                state.result?.let { result ->
                    HorizontalDivider()
                    Text("成功：${result.successCount} · 失败：${result.errors.size} · 输入：${result.inputCount} · 输出：${result.outputCount}")
                    Text("原始已知大小：${bytes(result.inputBytes)}" + if (result.unknownInputSizes > 0) "（${result.unknownInputSizes} 张大小未知）" else "")
                    Text("输出图片：${bytes(result.outputBytes)} · ZIP：${bytes(result.zip?.size ?: 0)}")
                    Text("耗时：${result.elapsedMs} ms · 缩放：${result.settings.scalePercent}% · 格式：${result.settings.format}" +
                        if (result.settings.format == OutputFormat.PNG) "" else " · 质量：${result.settings.quality}")
                    Text("实际 codec / 硬件加速 / bitrate mode：Unknown", style = MaterialTheme.typography.bodySmall)
                    result.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    result.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    result.zip?.let { output ->
                        Text("已保存：${output.directory}${output.name}", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { share(output) }, enabled = !state.busy) { Text("分享 ZIP") }
                    }
                    if (result.zip == null) Text("所有输入均失败，未生成 ZIP")
                }
                HorizontalDivider()
                OutlinedButton(onClick = { diagnosticPage = true; model.diagnostics() }) { Text("编码器信息") }
                OutlinedButton(onClick = { confirmCleanOutput = false }, enabled = enabled) { Text("清理临时文件") }
                OutlinedButton(onClick = { confirmCleanOutput = true }, enabled = enabled) { Text("清理生成文件") }
                Text("公共输出目录：${PackStorage.OUTPUT_PATH}\n在 ChatGPT 上传文件时，从“下载”进入 GPT Screenshot Pack → Output 选择 ZIP。\nTemp 过期 24 小时可在启动时清理；Output 永不自动删除。ZIP 仅存放图片，不再压缩。\n全部处理在本地完成，不申请网络权限。\n${BuildConfig.VERSION_NAME} · Android 9+", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (confirmCleanOutput != null) {
        val output = confirmCleanOutput == true
        AlertDialog(onDismissRequest = { confirmCleanOutput = null },
            title = { Text(if (output) "清理生成的 ZIP？" else "清理临时文件？") },
            text = { Text(if (output) "删除本应用在公共 Output 中生成的 ZIP，已有分享链接将失效。原始图片不受影响。"
                else "清理本应用公共 Temp 中的中间文件。Output 中的 ZIP 和原始图片保留。") },
            confirmButton = { TextButton(onClick = {
                confirmCleanOutput = null
                if (output) model.cleanOutput() else model.cleanTemp()
            }) { Text("清理") } },
            dismissButton = { TextButton(onClick = { confirmCleanOutput = null }) { Text("取消") } })
    }
}

private fun bytes(value: Long): String = String.format(Locale.getDefault(), "%.2f MiB", value / 1048576.0)
