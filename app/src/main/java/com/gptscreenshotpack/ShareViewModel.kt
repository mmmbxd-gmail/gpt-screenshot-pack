package com.gptscreenshotpack

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.gptscreenshotpack.core.PackSettings
import com.gptscreenshotpack.core.ResolutionMode
import com.gptscreenshotpack.core.ZipNaming
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SharePhase { LOADING, NAMING, PREPARING, PERMISSION, PROCESSING, CANCELLING, SAVED, ERROR, CLOSED }

data class ShareUiState(
    val phase: SharePhase = SharePhase.LOADING,
    val naming: ZipNaming = ZipNaming(), val inputCount: Int = 0, val done: Int = 0,
    val error: String? = null, val askStoragePermission: Boolean = false,
    val result: PackResult? = null, val canRetry: Boolean = false,
    val resolutionMode: ResolutionMode = ResolutionMode.ORIGINAL, val reducedScalePercent: Int = 100,
)

/** Separate request lifetime: launching from Sharesheet never constructs the main screen/model. */
class ShareViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val store = SettingsStore(application)
    private val mutable = MutableStateFlow(ShareUiState())
    val state: StateFlow<ShareUiState> = mutable
    private var prepared = false
    private var inputs: List<Uri> = emptyList()
    private var settings = PackSettings()
    private var fileName: String? = null
    private var job: Job? = null

    fun prepare(uris: List<Uri>) {
        if (prepared) return // The same ViewModel survives rotation, including permission requests.
        prepared = true
        inputs = uris.toList()
        mutable.update { it.copy(inputCount = inputs.size) }
        if (savedState.get<Boolean>("submitted") == true) {
            fail("处理流程已被系统中断，请关闭后重新分享；已保存的 ZIP 仍保留在 Output")
            return // Never reprocess a request after process death and create a duplicate archive.
        }
        if (inputs.isEmpty()) { fail("没有收到可读取的图片，请关闭后重新分享"); return }
        job = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { PackStorage(getApplication()).cleanTemp(expiredOnly = true) }
                settings = store.settings.first()
                val saved = store.zipNaming.first()
                val naming = saved.copy(
                    base = savedState.get<String>("base") ?: saved.base,
                    appendDateTime = savedState.get<Boolean>("dateTime") ?: saved.appendDateTime,
                )
                val mode = savedState.get<String>("resolutionMode")?.let { name ->
                    ResolutionMode.entries.firstOrNull { it.name == name }
                } ?: settings.resolutionMode
                mutable.update { it.copy(phase = SharePhase.NAMING, naming = naming,
                    resolutionMode = mode, reducedScalePercent = settings.scalePercent) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail("无法读取设置：${e.message}") }
        }
    }

    fun editName(base: String) {
        if (mutable.value.phase != SharePhase.NAMING) return
        savedState["base"] = base
        mutable.update { it.copy(naming = it.naming.copy(base = base), error = null) }
    }
    fun editDateTime(enabled: Boolean) {
        if (mutable.value.phase != SharePhase.NAMING) return
        savedState["dateTime"] = enabled
        mutable.update { it.copy(naming = it.naming.copy(appendDateTime = enabled), error = null) }
    }
    fun editResolutionMode(mode: ResolutionMode) {
        if (mutable.value.phase != SharePhase.NAMING) return
        savedState["resolutionMode"] = mode.name
        mutable.update { it.copy(resolutionMode = mode) }
    }

    fun confirm() {
        if (mutable.value.phase != SharePhase.NAMING) return
        val naming = try { mutable.value.naming.validated() }
        catch (e: IllegalArgumentException) { mutable.update { it.copy(error = e.message) }; return }
        val mode = mutable.value.resolutionMode
        fileName = naming.fileName() // Device-local time at confirmation, fixed for this request.
        mutable.update { it.copy(phase = SharePhase.PREPARING, naming = naming, error = null) }
        job = viewModelScope.launch {
            try {
                settings = store.settings.first().copy(resolutionMode = mode)
                store.saveShareOptions(naming, mode)
                if (PackStorage.hasPermission(getApplication())) process()
                else mutable.update { it.copy(phase = SharePhase.PERMISSION, askStoragePermission = true) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail("处理失败：${e.message}") }
        }
    }

    fun permissionDialogLaunching() { mutable.update { it.copy(askStoragePermission = false) } }
    fun storagePermissionResult(granted: Boolean) {
        if (mutable.value.phase != SharePhase.PERMISSION) return
        if (!granted) { fail("Android 9 需要存储权限才能保存到公共 Downloads，可重新命名后重试"); return }
        job = viewModelScope.launch {
            try { process() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail("处理失败：${e.message}") }
        }
    }

    private suspend fun process() {
        savedState["submitted"] = true
        mutable.update { it.copy(phase = SharePhase.PROCESSING, done = 0) }
        val result = PackProcessor(getApplication()).process(inputs, settings, checkNotNull(fileName)) { done, _ ->
            mutable.update { it.copy(done = done) }
        }
        if (result.zip == null) {
            fail("所有图片均失败，未生成 ZIP。\n" + result.errors.joinToString("\n"))
        } else mutable.update { it.copy(phase = SharePhase.SAVED, result = result) }
    }

    private fun fail(message: String) {
        mutable.update { it.copy(phase = SharePhase.ERROR, error = message, askStoragePermission = false,
            canRetry = inputs.isNotEmpty() && fileName != null) }
    }
    fun retry() {
        if (mutable.value.phase != SharePhase.ERROR || inputs.isEmpty() || fileName == null) return
        savedState["submitted"] = false
        mutable.update { it.copy(phase = SharePhase.NAMING, error = null) }
    }
    fun close() {
        if (mutable.value.phase in setOf(SharePhase.CANCELLING, SharePhase.CLOSED, SharePhase.SAVED)) return
        mutable.update { it.copy(phase = SharePhase.CANCELLING, askStoragePermission = false) }
        val running = job
        viewModelScope.launch {
            running?.cancelAndJoin() // Wait for Temp cleanup before returning to the sending app.
            mutable.update { it.copy(phase = SharePhase.CLOSED) }
        }
    }
}
