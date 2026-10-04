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

data class PackUiState(
    val settings: PackSettings = PackSettings(), val ready: Boolean = false,
    val busy: Boolean = false, val done: Int = 0, val total: Int = 0,
    val result: PackResult? = null, val message: String? = null,
    val autoSharePending: Boolean = false, val diagnostics: String? = null,
)

class PackViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SettingsStore(application)
    private val cache = PackCache(application)
    private val mutable = MutableStateFlow(PackUiState())
    val state: StateFlow<PackUiState> = mutable
    private var job: Job? = null
    init {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { cache.clean() }
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
        if (mutable.value.busy) { message("正在处理上一批图片，请稍后重试分享"); return }
        if (uris.isEmpty()) { message("没有收到可读取的图片"); return }
        mutable.update { it.copy(busy = true, done = 0, total = uris.size, result = null, message = null, autoSharePending = false) }
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
                mutable.update { it.copy(busy = false, message = "已取消，已删除本次缓存") }
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(busy = false, message = "处理失败：${e.message}") }
            }
        }
    }
    fun cancel() { job?.cancel() }
    fun consumeShare() { mutable.update { it.copy(autoSharePending = false) } }
    fun message(value: String) { mutable.update { it.copy(message = value) } }
    fun diagnostics() {
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) { CodecDiagnostics.report() }
            mutable.update { it.copy(diagnostics = report) }
        }
    }
    fun clean() {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, result = null, autoSharePending = false) }
        job = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { cache.clean(false) }
                mutable.update { it.copy(message = "应用缓存已清理") }
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }
}
