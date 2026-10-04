package com.gptscreenshotpack

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

object CodecDiagnostics {
    fun report(): String = buildString {
        appendLine("Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}")
        appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("SoC：${if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else "Unknown"}")
        appendLine("当前策略：HeifWriter 1.1.0 Auto · CQ Auto · Grid Auto")
        appendLine("实际 codec：Unknown")
        appendLine("实际硬件加速：Unknown")
        appendLine("实际 bitrate mode：Unknown")
        appendLine("稳定版 HeifWriter 没有公开实际选择查询接口。以下为设备报告的候选能力。")
        try {
            val codecs = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { it.isEncoder }
            for (mime in listOf(MediaFormat.MIMETYPE_IMAGE_ANDROID_HEIC, MediaFormat.MIMETYPE_VIDEO_HEVC)) {
                appendLine("\n$mime")
                val matches = codecs.filter { codec -> codec.supportedTypes.any { it.equals(mime, true) } }
                if (matches.isEmpty()) appendLine("未报告编码器")
                for (codec in matches) {
                    appendLine("\n${codec.name}")
                    if (Build.VERSION.SDK_INT >= 29) {
                        appendLine("Hardware Accelerated：${codec.isHardwareAccelerated}")
                        appendLine("Software Only：${codec.isSoftwareOnly}")
                        appendLine("Vendor Codec：${codec.isVendor}")
                    } else appendLine("Hardware Accelerated / Software Only / Vendor Codec：Unknown")
                    try {
                        val caps = codec.getCapabilitiesForType(mime)
                        val enc = checkNotNull(caps.encoderCapabilities) { "未报告 EncoderCapabilities" }
                        appendLine("CQ Supported：${enc.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)}")
                        appendLine("CBR Supported：${enc.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)}")
                        appendLine("VBR Supported：${enc.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)}")
                        appendLine("Quality Range：${enc.qualityRange}")
                        appendLine("Complexity Range：${enc.complexityRange}")
                        appendLine("宽度范围：${caps.videoCapabilities?.supportedWidths ?: "Unknown"}")
                        appendLine("高度范围：${caps.videoCapabilities?.supportedHeights ?: "Unknown"}")
                    } catch (e: Exception) { appendLine("能力：Unknown（${e.message}）") }
                }
            }
        } catch (e: Exception) { appendLine("查询失败：${e.message}") }
    }
}
