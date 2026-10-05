package com.gptscreenshotpack

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.util.UUID

data class StoredDocument(val uri: Uri, val name: String, internal val legacyFile: File? = null)
data class PackOutput(val uri: Uri, val name: String, val size: Long, val directory: String = PackStorage.OUTPUT_PATH)

/** Public Downloads only; no absolute DATA column and no broad file-access permission on API 29+. */
class PackStorage(private val context: Context) {
    companion object {
        const val ROOT_PATH = "Download/GPT Screenshot Pack/"
        const val TEMP_PATH = "${ROOT_PATH}Temp/"
        const val OUTPUT_PATH = "${ROOT_PATH}Output/"
        private val activeRuns = mutableSetOf<String>()
        fun hasPermission(context: Context): Boolean = Build.VERSION.SDK_INT >= 29 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    data class Run(val id: String = UUID.randomUUID().toString())
    private val resolver = context.contentResolver

    fun newRun(): Run {
        check(hasPermission(context)) { "Android 9 需要存储权限才能写入公共 Downloads" }
        return synchronized(activeRuns) { Run().also { activeRuns.add(it.id) } }
    }

    @RequiresApi(29)
    private fun collection(): Uri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    @Suppress("DEPRECATION") // API 28 has no MediaStore.Downloads/RELATIVE_PATH.
    private fun legacyRoot(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "GPT Screenshot Pack")

    fun createTemp(run: Run, name: String, mime: String): StoredDocument {
        require(name.isNotBlank() && '/' !in name && '\\' !in name)
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$TEMP_PATH${run.id}/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = checkNotNull(resolver.insert(collection(), values)) { "无法创建公共临时文件" }
            return StoredDocument(uri, name)
        }
        val dir = File(legacyRoot(), "Temp/${run.id}")
        check(dir.isDirectory || dir.mkdirs()) { "无法创建公共 Temp 目录" }
        val file = File(dir, name)
        check(file.createNewFile()) { "临时文件重名" }
        return StoredDocument(legacyUri(file), name, file)
    }

    private fun legacyUri(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    fun read(file: StoredDocument): InputStream = checkNotNull(resolver.openInputStream(file.uri)) { "无法读取临时图片" }
    fun write(file: StoredDocument): OutputStream = checkNotNull(resolver.openOutputStream(file.uri, "wt")) { "无法写入临时文件" }
    fun descriptor(file: StoredDocument): ParcelFileDescriptor = checkNotNull(resolver.openFileDescriptor(file.uri, "rw")) { "无法打开编码目标" }
    fun size(file: StoredDocument): Long = resolver.openFileDescriptor(file.uri, "r")?.use { it.statSize }?.takeIf { it >= 0 }
        ?: error("无法确定输出文件大小")
    fun delete(file: StoredDocument) {
        if (file.legacyFile != null) check(!file.legacyFile.exists() || file.legacyFile.delete()) { "临时文件删除失败" }
        else resolver.delete(file.uri, null, null)
    }

    /** Complete ZIP is moved/published atomically; Output is never an automatic cleanup target. */
    fun publish(zip: StoredDocument): PackOutput {
        if (Build.VERSION.SDK_INT >= 29) {
            val size = size(zip)
            check(size > 0)
            var desiredName = zip.name
            var copy = 1
            while (outputNameExists(desiredName)) {
                desiredName = "${zip.name.removeSuffix(".zip")}_copy${++copy}.zip"
            }
            check(resolver.update(zip.uri, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, desiredName)
                put(MediaStore.MediaColumns.RELATIVE_PATH, OUTPUT_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null) == 1) { "无法发布 ZIP 到公共 Output 目录" }
            // The provider can suffix a same-second collision; never overwrite an earlier output.
            var actualName = desiredName
            resolver.query(zip.uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) actualName = it.getString(0)
            }
            return PackOutput(zip.uri, actualName, size)
        }
        val source = checkNotNull(zip.legacyFile)
        val dir = File(legacyRoot(), "Output")
        check(dir.isDirectory || dir.mkdirs()) { "无法创建公共 Output 目录" }
        val bytes = source.length()
        check(bytes > 0)
        var copy = 1
        while (true) {
            val name = if (copy == 1) zip.name else "${zip.name.removeSuffix(".zip")}_copy$copy.zip"
            val target = File(dir, name)
            try {
                Files.move(source.toPath(), target.toPath()) // No REPLACE_EXISTING.
                return PackOutput(legacyUri(target), target.name, bytes)
            } catch (_: FileAlreadyExistsException) { copy++ }
        }
    }

    @RequiresApi(29)
    private fun outputNameExists(name: String): Boolean = resolver.query(collection(),
        arrayOf(MediaStore.MediaColumns._ID),
        "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
        arrayOf(OUTPUT_PATH, name), null)?.use { it.moveToFirst() } ?: false

    fun cleanRun(run: Run) {
        try {
            if (Build.VERSION.SDK_INT >= 29) cleanMediaStore("$TEMP_PATH${run.id}/", false) {}
            else File(legacyRoot(), "Temp/${run.id}").deleteRecursively()
        } finally { synchronized(activeRuns) { activeRuns.remove(run.id) } }
    }

    fun cleanTemp(expiredOnly: Boolean = false, checkpoint: () -> Unit = {}): Int = synchronized(activeRuns) {
        // A main-screen cleanup must not delete another ShareActivity's active intermediate files.
        if (!hasPermission(context)) return@synchronized 0
        if (Build.VERSION.SDK_INT >= 29) return@synchronized cleanMediaStore(TEMP_PATH, expiredOnly, checkpoint)
        var deleted = 0
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        File(legacyRoot(), "Temp").listFiles()?.forEach { run ->
            checkpoint()
            // Only task directories created by this app; ignore unrelated manually placed files.
            if (run.isDirectory && run.name !in activeRuns && run.name.matches(Regex("[0-9a-fA-F-]{36}")) && (!expiredOnly || run.lastModified() < cutoff)) {
                run.walkTopDown().filter { it.isFile }.forEach { checkpoint(); if (it.delete()) deleted++ }
                run.deleteRecursively()
            }
        }
        deleted
    }

    fun cleanOutput(checkpoint: () -> Unit = {}): Int {
        check(hasPermission(context)) { "Android 9 需要存储权限" }
        if (Build.VERSION.SDK_INT >= 29) return cleanMediaStore(OUTPUT_PATH, false, checkpoint)
        var deleted = 0
        File(legacyRoot(), "Output").listFiles()?.forEach { file ->
            checkpoint()
            // API 28 has no owner column; Output is the app's dedicated generated-ZIP directory.
            if (file.isFile && file.extension == "zip" && file.delete()) deleted++
        }
        return deleted
    }

    @RequiresApi(29)
    private fun cleanMediaStore(path: String, expiredOnly: Boolean, checkpoint: () -> Unit): Int {
        val isOutput = path == OUTPUT_PATH
        val excludedRuns = if (path == TEMP_PATH) activeRuns.map { "$TEMP_PATH$it/" } else emptyList()
        val selection = buildString {
            append("${MediaStore.MediaColumns.RELATIVE_PATH} ${if (isOutput) "= ?" else "LIKE ?"}")
            append(" AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?")
            if (isOutput) append(" AND ${MediaStore.MediaColumns.MIME_TYPE} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} GLOB ?")
            if (expiredOnly) append(" AND ${MediaStore.MediaColumns.DATE_ADDED} < ?")
            if (excludedRuns.isNotEmpty()) append(" AND ${MediaStore.MediaColumns.RELATIVE_PATH} NOT IN (${excludedRuns.joinToString(",") { "?" }})")
        }
        val args = mutableListOf(if (isOutput) path else "$path%", context.packageName)
        if (isOutput) args.addAll(listOf("application/zip", "*.zip"))
        if (expiredOnly) args.add((System.currentTimeMillis() / 1000 - 24 * 60 * 60).toString())
        args.addAll(excludedRuns)
        val queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args.toTypedArray())
            if (Build.VERSION.SDK_INT >= 30) putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        @Suppress("DEPRECATION")
        val uri = if (Build.VERSION.SDK_INT == 29) MediaStore.setIncludePending(collection()) else collection()
        val entries = mutableListOf<Uri>()
        resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), queryArgs, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                checkpoint()
                entries.add(ContentUris.withAppendedId(collection(), cursor.getLong(0)))
            }
        }
        var deleted = 0
        entries.forEach { checkpoint(); deleted += resolver.delete(it, null, null) }
        return deleted
    }
}
