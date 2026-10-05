package com.gptscreenshotpack

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState

internal fun sharedImages(intent: Intent): List<Uri> {
    if (intent.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return emptyList()
    val streams = if (intent.action == Intent.ACTION_SEND_MULTIPLE)
        IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
    else listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
    return streams.ifEmpty {
        intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }.orEmpty()
    }
}

class ShareActivity : ComponentActivity() {
    private val model: ShareViewModel by viewModels()
    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        model.storagePermissionResult(it)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model.prepare(sharedImages(intent))
        setContent {
            MaterialTheme {
                val state by model.state.collectAsStateWithLifecycle()
                val lifecycleState by lifecycle.currentStateAsState()
                BackHandler { model.close() }
                LaunchedEffect(state.askStoragePermission) {
                    if (state.askStoragePermission) {
                        model.permissionDialogLaunching()
                        storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                }
                LaunchedEffect(state.phase, lifecycleState) {
                    if (lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) {
                        if (state.phase == SharePhase.SAVED) {
                            val result = checkNotNull(state.result)
                            val output = checkNotNull(result.zip)
                            val details = if (result.errors.isNotEmpty() || result.notes.isNotEmpty())
                                "\n成功 ${result.successCount}，失败 ${result.errors.size}" +
                                    if (result.notes.any { "回退 PNG" in it }) "；部分 HEIC 已回退 PNG" else "" else ""
                            Toast.makeText(this@ShareActivity, "已保存：${output.directory}${output.name}$details", Toast.LENGTH_LONG).show()
                            finish() // Do not open a chooser or remove the source application's task.
                        } else if (state.phase == SharePhase.CLOSED) finish()
                    }
                }
                if (state.phase !in setOf(SharePhase.SAVED, SharePhase.CLOSED)) ShareDialog(state, model)
            }
        }
    }
}

@Composable
private fun ShareDialog(state: ShareUiState, model: ShareViewModel) {
    val naming = state.phase == SharePhase.NAMING
    AlertDialog(
        onDismissRequest = model::close,
        title = { Text(when (state.phase) {
            SharePhase.NAMING -> "ZIP 文件名"
            SharePhase.ERROR -> "未生成 ZIP"
            SharePhase.CANCELLING -> "正在取消"
            else -> "GPT Screenshot Pack"
        }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (naming) {
                    Text("收到 ${state.inputCount} 张图片，使用主界面中保存的图像设置。")
                    OutlinedTextField(value = state.naming.base, onValueChange = model::editName,
                        label = { Text("文件名主体") }, singleLine = true,
                        isError = state.error != null, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("自动附加日期时间", Modifier.weight(1f))
                        Switch(checked = state.naming.appendDateTime, onCheckedChange = model::editDateTime)
                    }
                    Text("自动补 .zip" + if (state.naming.appendDateTime) "；日期格式 yyyyMMdd_HHmmss" else "")
                    Text("保存到：${PackStorage.OUTPUT_PATH}", style = MaterialTheme.typography.bodySmall)
                } else if (state.phase != SharePhase.ERROR) {
                    Text(when (state.phase) {
                        SharePhase.LOADING -> "正在读取保存的设置…"
                        SharePhase.PREPARING -> "正在保存命名设置…"
                        SharePhase.PERMISSION -> "请允许保存到公共 Downloads"
                        SharePhase.CANCELLING -> "正在清理本次临时文件，已保存的 ZIP 保留。"
                        else -> "正在处理 ${state.done} / ${state.inputCount}；保存完成后自动退出。"
                    })
                    if (state.phase == SharePhase.PROCESSING)
                        LinearProgressIndicator(progress = { state.done.toFloat() / state.inputCount.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (naming) TextButton(onClick = model::confirm) { Text("开始处理") }
            else if (state.phase == SharePhase.ERROR) TextButton(onClick = model::close) { Text("关闭") }
        },
        dismissButton = {
            if (state.phase == SharePhase.ERROR && state.canRetry) TextButton(onClick = model::retry) { Text("重新命名") }
            else if (state.phase == SharePhase.ERROR) Unit
            else TextButton(onClick = model::close, enabled = state.phase != SharePhase.CANCELLING) { Text("取消") }
        },
    )
}
