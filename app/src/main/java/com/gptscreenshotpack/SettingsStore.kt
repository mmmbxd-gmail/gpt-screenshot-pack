package com.gptscreenshotpack

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gptscreenshotpack.core.OutputContainer
import com.gptscreenshotpack.core.OutputFormat
import com.gptscreenshotpack.core.PackSettings
import com.gptscreenshotpack.core.ResolutionMode
import com.gptscreenshotpack.core.OutputNaming
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException

private val Context.settingsDataStore by preferencesDataStore("pack_settings")

data class ShareTaskOptions(val resolutionMode: ResolutionMode, val format: OutputFormat,
    val targetWidth: Int, val container: OutputContainer)

class SettingsStore(context: Context) {
    private val store = context.applicationContext.settingsDataStore
    private val scale = intPreferencesKey("scale")
    private val scaleB = intPreferencesKey("scale_b")
    private val width = intPreferencesKey("target_width")
    private val format = stringPreferencesKey("format")
    private val heic = intPreferencesKey("heic_quality")
    private val jpeg = intPreferencesKey("jpeg_quality")
    private val pdf = intPreferencesKey("pdf_quality")
    private val container = stringPreferencesKey("output_container")
    private val zipBase = stringPreferencesKey("zip_name_base")
    private val zipDateTime = booleanPreferencesKey("zip_append_datetime")
    private val mode = stringPreferencesKey("resolution_mode")
    private val defaults = PackSettings()
    val outputNaming = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map {
        OutputNaming(it[zipBase] ?: "GPT_Screenshots", it[zipDateTime] ?: true)
    }
    suspend fun saveShareOptions(value: OutputNaming, resolutionMode: ResolutionMode, outputFormat: OutputFormat,
        targetWidth: Int? = null, outputContainer: OutputContainer = OutputContainer.ZIP) {
        val naming = value.validated(outputContainer)
        require(targetWidth == null || targetWidth > 0)
        // Reuse the existing format key; main-window saves never write this task preference.
        store.edit { it[zipBase] = naming.base; it[zipDateTime] = naming.appendDateTime
            it[mode] = resolutionMode.name; it[format] = outputFormat.name; it[container] = outputContainer.name
            if (targetWidth != null) it[width] = targetWidth }
    }
    val settings = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map {
        PackSettings((it[scale] ?: defaults.scalePercent).coerceIn(25, 100),
            OutputFormat.entries.firstOrNull { f -> f.name == it[format] } ?: defaults.format,
            (it[heic] ?: defaults.heicQuality).coerceIn(1, 100), (it[jpeg] ?: defaults.jpegQuality).coerceIn(1, 100),
            ResolutionMode.entries.firstOrNull { m -> m.name == it[mode] } ?: defaults.resolutionMode,
            (it[scaleB] ?: defaults.scalePercentB).coerceIn(25, 100),
            (it[width] ?: defaults.targetWidth).takeIf { value -> value > 0 } ?: defaults.targetWidth,
            OutputContainer.entries.firstOrNull { option -> option.name == it[container] } ?: defaults.container,
            (it[pdf] ?: defaults.pdfQuality).coerceIn(1, 100))
    }
    val taskOptions = settings.map {
        ShareTaskOptions(it.resolutionMode, it.format, it.targetWidth, it.container)
    }.distinctUntilChanged()
    suspend fun save(value: PackSettings) {
        store.edit { it[scale] = value.scalePercent
            it[scaleB] = value.scalePercentB; it[width] = value.targetWidth
            it[heic] = value.heicQuality; it[jpeg] = value.jpegQuality; it[pdf] = value.pdfQuality
            it[mode] = value.resolutionMode.name }
    }
}
