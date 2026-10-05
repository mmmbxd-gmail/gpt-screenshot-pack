package com.gptscreenshotpack.core

import org.junit.Assert.*
import org.junit.Test

class ResolutionModeTest {
    @Test fun originalIgnoresStoredReductionRatioAndStillSlicesLongImages() {
        val input = Dimensions(1440, 19399)
        assertEquals(ResolutionMode.ORIGINAL, PackSettings().resolutionMode)
        for (percent in listOf(25, 33, 50, 60, 67, 75, 100)) {
            val settings = PackSettings(scalePercent = percent)
            assertSame(input, settings.targetDimensions(input))
            assertEquals(100, settings.effectiveScalePercent)
            assertEquals(2, Slicing.plan(settings.targetDimensions(input)).size)
        }
    }

    @Test fun reducedUsesConfiguredRatioBeforeSlicing() {
        val input = Dimensions(1440, 19399)
        val settings = PackSettings(scalePercent = 50, resolutionMode = ResolutionMode.REDUCED)
        assertEquals(50, settings.effectiveScalePercent)
        assertEquals(Dimensions(720, 9700), settings.targetDimensions(input))
        assertEquals(1, Slicing.plan(settings.targetDimensions(input)).size)
        assertEquals(Dimensions(965, 12997), settings.copy(scalePercent = 67).targetDimensions(input))
        assertEquals(input, settings.copy(scalePercent = 100).targetDimensions(input))
    }

    @Test fun modeChangesLeaveRatioFormatAndIndependentQualitiesUnchanged() {
        val reduced = PackSettings(50, OutputFormat.PNG, 75, 90, ResolutionMode.REDUCED)
        val original = reduced.copy(resolutionMode = ResolutionMode.ORIGINAL)
        assertEquals(50, original.scalePercent)
        assertEquals(OutputFormat.PNG, original.format)
        assertEquals(75, original.heicQuality)
        assertEquals(90, original.jpegQuality)
        assertEquals(100, original.effectiveScalePercent)
        assertEquals(reduced, original.copy(resolutionMode = ResolutionMode.REDUCED))
    }
}
