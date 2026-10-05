package com.gptscreenshotpack.core

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

class ZipNamingTest {
    @Test fun defaultsKeepLegacyBaseAndEnableDateTime() {
        assertEquals("GPT_Screenshots", ZipNaming().base)
        assertTrue(ZipNaming().appendDateTime)
    }

    @Test fun timestampUsesDeviceTimeZoneAndRequestedFormat() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            val now = LocalDateTime.of(2026, 10, 5, 13, 25, 30).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
            assertEquals("聊天记录_20261005_132530.zip", ZipNaming("聊天记录").fileName(now))
        } finally { TimeZone.setDefault(previous) }
    }

    @Test fun disablingDateTimeAndPastedExtensionsDoNotDuplicateZip() {
        val value = ZipNaming("  聊天记录.ZIP.zip  ", false)
        assertEquals(ZipNaming("聊天记录", false), value.validated())
        assertEquals("聊天记录.zip", value.fileName(0))
        assertEquals("Chat log 1.zip", ZipNaming("Chat log 1", false).fileName())
    }

    @Test fun rejectsBlankTraversalControlAndUnsupportedFileCharacters() {
        listOf("", " ", ".", "..", ".zip", "../escape", "a/b", "a\\b", "a:b", "a*b", "a?b",
            "a\"b", "a<b", "a>b", "a|b", "a\nb", "a\u007fb").forEach { name ->
            assertThrows("Must reject $name", IllegalArgumentException::class.java) { ZipNaming(name).fileName() }
        }
        listOf("no extension", "../bad.zip", " .zip", "a.zip ", ".zip", "..zip").forEach {
            assertThrows(IllegalArgumentException::class.java) { ZipNaming.requireFileName(it) }
        }
    }

    @Test fun reservesUtf8BytesForTimeAndCollisionSuffix() {
        val name = "a".repeat(200)
        ZipNaming.requireFileName(ZipNaming(name).fileName())
        ZipNaming.requireFileName(ZipNaming("聊".repeat(66)).fileName())
        assertThrows(IllegalArgumentException::class.java) { ZipNaming("a".repeat(201)).fileName() }
        assertThrows(IllegalArgumentException::class.java) { ZipNaming("聊".repeat(67)).fileName() }
        assertThrows(IllegalArgumentException::class.java) { ZipNaming.requireFileName("a".repeat(241) + ".zip") }
    }
}
