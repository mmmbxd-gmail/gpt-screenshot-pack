package com.gptscreenshotpack.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class OutputNaming(val base: String = "GPT_Screenshots", val appendDateTime: Boolean = true) {
    companion object {
        private fun validBase(name: String): Boolean = name.isNotBlank() && name != "." && name != ".." &&
            name.none { it.code < 32 || it.code == 127 || it in "/\\:*?\"<>|" }

        fun requireFileName(name: String, container: OutputContainer = OutputContainer.ZIP) {
            val suffix = ".${container.extension}"
            require(name.endsWith(suffix) && name == name.trim() && validBase(name.removeSuffix(suffix)) &&
                name.toByteArray(Charsets.UTF_8).size <= 240) { "$container 文件名无效" }
        }
    }

    fun validated(container: OutputContainer = OutputContainer.ZIP): OutputNaming {
        val suffix = ".${container.extension}"
        var name = base.trim()
        while (name.endsWith(suffix, ignoreCase = true)) name = name.dropLast(suffix.length).trimEnd()
        require(name.isNotBlank() && name != "." && name != "..") { "请填写文件名主体" }
        require(validBase(name)) {
            "文件名不能包含路径分隔符、控制字符或 / \\ : * ? \" < > |"
        }
        // Reserve room for the timestamp, extension and collision suffix on FAT/ext filesystems.
        require(name.toByteArray(Charsets.UTF_8).size <= 200) { "文件名主体过长（最多 200 个 UTF-8 字节）" }
        return copy(base = name)
    }

    fun fileName(nowMillis: Long = System.currentTimeMillis(), container: OutputContainer = OutputContainer.ZIP): String {
        val naming = validated(container)
        val stamp = if (naming.appendDateTime)
            "_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(nowMillis)) else ""
        return "${naming.base}$stamp.${container.extension}"
    }
}
