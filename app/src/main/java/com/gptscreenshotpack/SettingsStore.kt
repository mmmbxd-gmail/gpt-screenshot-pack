package com.gptscreenshotpack

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gptscreenshotpack.core.OutputFormat
import com.gptscreenshotpack.core.PackSettings
import com.gptscreenshotpack.core.ZipNaming
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException

private val Context.settingsDataStore by preferencesDataStore("pack_settings")

class SettingsStore(context: Context) {
    private val store = context.applicationContext.settingsDataStore
    private val scale = intPreferencesKey("scale")
    private val format = stringPreferencesKey("format")
    private val heic = intPreferencesKey("heic_quality")
    private val jpeg = intPreferencesKey("jpeg_quality")
    private val zipBase = stringPreferencesKey("zip_name_base")
    private val zipDateTime = booleanPreferencesKey("zip_append_datetime")
    val zipNaming = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map {
        ZipNaming(it[zipBase] ?: "GPT_Screenshots", it[zipDateTime] ?: true)
    }
    suspend fun saveZipNaming(value: ZipNaming) {
        val naming = value.validated()
        store.edit { it[zipBase] = naming.base; it[zipDateTime] = naming.appendDateTime }
    }
    val settings = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map {
        PackSettings((it[scale] ?: 100).coerceIn(25, 100),
            OutputFormat.entries.firstOrNull { f -> f.name == it[format] } ?: OutputFormat.HEIC,
            (it[heic] ?: 95).coerceIn(1, 100), (it[jpeg] ?: 95).coerceIn(1, 100))
    }
    suspend fun save(value: PackSettings) {
        store.edit { it[scale] = value.scalePercent; it[format] = value.format.name
            it[heic] = value.heicQuality; it[jpeg] = value.jpegQuality }
    }
}
