package com.gptscreenshotpack

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gptscreenshotpack.core.PackSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive

data class PackUiState(
    val settings: PackSettings = PackSettings(), val ready: Boolean = false,
    val busy: Boolean = false, val done: Int = 0, val total: Int = 0,
    val result: PackResult? = null, val message: String? = null,
    val autoSharePending: Boolean = false, val diagnostics: String? = null,
    val askStoragePermission: Boolean = false, val awaitingStoragePermission: Boolean = false,
    val busyLabel: String = "正在处理图片",
)

class PackViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SettingsStore(application)
    private val storage = PackStorage(application)
    private val mutable = MutableStateFlow(PackUiState())
    val state: StateFlow<PackUiState> = mutable
    private var job: Job? = null
    private var pendingStart: Pair<List<Uri>, Boolean>? = null
    init {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { storage.cleanTemp(expiredOnly = true) }
                val saved = store.settings.first()
                mutable.update { it.copy(settings = saved, ready = true) }
            } catch (e: Exception) {
                mutable.update { it.copy(ready = true, message = "设置读取失败，使用默认参数：${e.message}") }
            }
        }
    }
    fun setSettings(settings: PackSettings) {
        if (!mutable.value.ready || mutable.value.busy) return
        mutable.update { it.copy(settings = settings) }
        viewModelScope.launch {
            try { store.save(settings) }
            catch (e: Exception) { mutable.update { it.copy(message = "设置保存失败：${e.message}") } }
        }
    }
    fun start(uris: List<Uri>, autoShare: Boolean) {
        if (mutable.value.busy || mutable.value.awaitingStoragePermission) { message("正在处理上一个请求，请稍后重试分享"); return }
        if (uris.isEmpty()) { message("没有收到可读取的图片"); return }
        if (!PackStorage.hasPermission(getApplication())) {
            pendingStart = uris.toList() to autoShare
            mutable.update { it.copy(askStoragePermission = true, awaitingStoragePermission = true) }
            return
        }
        mutable.update { it.copy(busy = true, done = 0, total = uris.size, result = null, message = null,
            autoSharePending = false, busyLabel = "正在处理图片") }
        job = viewModelScope.launch {
            try {
                // Wait for initialization without using stale defaults on an incoming share.
                state.first { it.ready }
                val settings = mutable.value.settings
                val result = PackProcessor(getApplication()).process(uris, settings) { done, total ->
                    mutable.update { it.copy(done = done, total = total) }
                }
                mutable.update { it.copy(busy = false, result = result, autoSharePending = autoShare && result.zip != null) }
            } catch (e: CancellationException) {
                mutable.update { it.copy(busy = false, message = "已取消，本次未完成的临时文件已清理；已发布 ZIP 保留在 Output") }
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(busy = false, message = "处理失败：${e.message}") }
            }
        }
    }
    fun cancel() { job?.cancel() }
    fun permissionDialogLaunching() { mutable.update { it.copy(askStoragePermission = false) } }
    fun storagePermissionResult(granted: Boolean) {
        val pending = pendingStart
        pendingStart = null
        mutable.update { it.copy(askStoragePermission = false, awaitingStoragePermission = false) }
        if (granted && pending != null) start(pending.first, pending.second)
        else if (!granted) message("Android 9 需要存储权限才能写入公共 Downloads，请在应用设置中授予后重试")
    }
    fun consumeShare() { mutable.update { it.copy(autoSharePending = false) } }
    fun message(value: String) { mutable.update { it.copy(message = value) } }
    fun diagnostics() {
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) { CodecDiagnostics.report() }
            mutable.update { it.copy(diagnostics = report) }
        }
    }
    fun cleanTemp() = clean(output = false)
    fun cleanOutput() = clean(output = true)
    private fun clean(output: Boolean) {
        if (mutable.value.busy || mutable.value.awaitingStoragePermission) return
        if (!PackStorage.hasPermission(getApplication())) { message("Android 9 需要先授予存储权限"); return }
        mutable.update { it.copy(busy = true, done = 0, total = 0,
            result = if (output) null else it.result, autoSharePending = false,
            busyLabel = if (output) "正在清理生成文件" else "正在清理临时文件") }
        job = viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    val coroutine = kotlinx.coroutines.currentCoroutineContext()
                    val checkpoint = { coroutine.ensureActive() }
                    if (output) storage.cleanOutput(checkpoint) else storage.cleanTemp(checkpoint = checkpoint)
                }
                mutable.update { it.copy(message = "已清理 $count 个${if (output) "生成" else "临时"}文件") }
            } catch (e: CancellationException) {
                mutable.update { it.copy(message = "清理已取消") }
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(message = "清理失败：${e.message}") }
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }
}
