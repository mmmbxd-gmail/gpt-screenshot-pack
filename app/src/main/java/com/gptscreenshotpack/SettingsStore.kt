package com.gptscreenshotpack

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gptscreenshotpack.core.OutputFormat
import com.gptscreenshotpack.core.PackSettings
import com.gptscreenshotpack.core.ResolutionMode
import com.gptscreenshotpack.core.ZipNaming
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException

private val Context.settingsDataStore by preferencesDataStore("pack_settings")

class SettingsStore(context: Context) {
    private val store = context.applicationContext.settingsDataStore
    private val scale = intPreferencesKey("scale")
    private val scaleB = intPreferencesKey("scale_b")
    private val width = intPreferencesKey("target_width")
    private val format = stringPreferencesKey("format")
    private val heic = intPreferencesKey("heic_quality")
    private val jpeg = intPreferencesKey("jpeg_quality")
    private val zipBase = stringPreferencesKey("zip_name_base")
    private val zipDateTime = booleanPreferencesKey("zip_append_datetime")
    private val mode = stringPreferencesKey("resolution_mode")
    private val defaults = PackSettings()
    val zipNaming = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map {
        ZipNaming(it[zipBase] ?: "GPT_Screenshots", it[zipDateTime] ?: true)
    }
    suspend fun saveZipNaming(value: ZipNaming) {
        val naming = value.validated()
        store.edit { it[zipBase] = naming.base; it[zipDateTime] = naming.appendDateTime }
    }
    suspend fun saveShareOptions(value: ZipNaming, resolutionMode: ResolutionMode, outputFormat: OutputFormat,
        targetWidth: Int? = null) {
        val naming = value.validated()
        require(targetWidth == null || targetWidth > 0)
        // Reuse the existing format key; main-window saves never write this task preference.
        store.edit { it[zipBase] = naming.base; it[zipDateTime] = naming.appendDateTime
            it[mode] = resolutionMode.name; it[format] = outputFormat.name
            if (targetWidth != null) it[width] = targetWidth }
    }
    val settings = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map {
        PackSettings((it[scale] ?: defaults.scalePercent).coerceIn(25, 100),
            OutputFormat.entries.firstOrNull { f -> f.name == it[format] } ?: defaults.format,
            (it[heic] ?: defaults.heicQuality).coerceIn(1, 100), (it[jpeg] ?: defaults.jpegQuality).coerceIn(1, 100),
            ResolutionMode.entries.firstOrNull { m -> m.name == it[mode] } ?: defaults.resolutionMode,
            (it[scaleB] ?: defaults.scalePercentB).coerceIn(25, 100),
            (it[width] ?: defaults.targetWidth).takeIf { value -> value > 0 } ?: defaults.targetWidth)
    }
    val taskOptions = settings.map { Triple(it.resolutionMode, it.format, it.targetWidth) }.distinctUntilChanged()
    suspend fun save(value: PackSettings) {
        store.edit { it[scale] = value.scalePercent
            it[scaleB] = value.scalePercentB; it[width] = value.targetWidth
            it[heic] = value.heicQuality; it[jpeg] = value.jpegQuality; it[mode] = value.resolutionMode.name }
    }
}
