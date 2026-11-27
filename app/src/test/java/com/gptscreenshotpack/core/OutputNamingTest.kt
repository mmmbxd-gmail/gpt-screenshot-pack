package com.gptscreenshotpack.core

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

class OutputNamingTest {
    @Test fun pdfUsesTheSameNamingRules() {
        val naming = OutputNaming(" 聊天.PDF.pdf ", false)
        val name = naming.fileName(container = OutputContainer.PDF)
        assertEquals("聊天.pdf", name)
        OutputNaming.requireFileName(name, OutputContainer.PDF)
        assertThrows(IllegalArgumentException::class.java) {
            OutputNaming.requireFileName(name, OutputContainer.ZIP)
        }
    }
    @Test fun defaultsKeepLegacyBaseAndEnableDateTime() {
        assertEquals("GPT_Screenshots", OutputNaming().base)
        assertTrue(OutputNaming().appendDateTime)
    }

    @Test fun timestampUsesDeviceTimeZoneAndRequestedFormat() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            val now = LocalDateTime.of(2026, 10, 5, 13, 25, 30).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
            assertEquals("聊天记录_20261005_132530.zip", OutputNaming("聊天记录").fileName(now))
        } finally { TimeZone.setDefault(previous) }
    }

    @Test fun disablingDateTimeAndPastedExtensionsDoNotDuplicateZip() {
        val value = OutputNaming("  聊天记录.ZIP.zip  ", false)
        assertEquals(OutputNaming("聊天记录", false), value.validated())
        assertEquals("聊天记录.zip", value.fileName(0))
        assertEquals("Chat log 1.zip", OutputNaming("Chat log 1", false).fileName())
    }

    @Test fun rejectsBlankTraversalControlAndUnsupportedFileCharacters() {
        listOf("", " ", ".", "..", ".zip", "../escape", "a/b", "a\\b", "a:b", "a*b", "a?b",
            "a\"b", "a<b", "a>b", "a|b", "a\nb", "a\u007fb").forEach { name ->
            assertThrows("Must reject $name", IllegalArgumentException::class.java) { OutputNaming(name).fileName() }
        }
        listOf("no extension", "../bad.zip", " .zip", "a.zip ", ".zip", "..zip").forEach {
            assertThrows(IllegalArgumentException::class.java) { OutputNaming.requireFileName(it) }
        }
    }

    @Test fun reservesUtf8BytesForTimeAndCollisionSuffix() {
        val name = "a".repeat(200)
        OutputNaming.requireFileName(OutputNaming(name).fileName())
        OutputNaming.requireFileName(OutputNaming("聊".repeat(66)).fileName())
        assertThrows(IllegalArgumentException::class.java) { OutputNaming("a".repeat(201)).fileName() }
        assertThrows(IllegalArgumentException::class.java) { OutputNaming("聊".repeat(67)).fileName() }
        assertThrows(IllegalArgumentException::class.java) { OutputNaming.requireFileName("a".repeat(241) + ".zip") }
    }
}
