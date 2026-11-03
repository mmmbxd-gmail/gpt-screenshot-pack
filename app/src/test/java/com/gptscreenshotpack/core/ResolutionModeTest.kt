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

    @Test fun secondScaleIsIndependentAndUsesExistingRoundingAndSlicing() {
        val settings = PackSettings(scalePercent = 50, scalePercentB = 75, resolutionMode = ResolutionMode.SCALE_B)
        val input = Dimensions(1440, 19399)
        assertEquals(Dimensions(1080, 14549), settings.targetDimensions(input))
        assertEquals(75, settings.effectiveScalePercent)
        assertEquals(1, Slicing.plan(settings.targetDimensions(input)).size)
        assertEquals(Dimensions(720, 9700), settings.copy(resolutionMode = ResolutionMode.REDUCED).targetDimensions(input))
        assertSame(input, settings.copy(resolutionMode = ResolutionMode.ORIGINAL).targetDimensions(input))
    }

    @Test fun specifiedWidthKeepsAspectForPortraitLandscapeAndMixedInputs() {
        val settings = PackSettings(scalePercent = 25, scalePercentB = 100,
            resolutionMode = ResolutionMode.WIDTH, targetWidth = 1080)
        assertEquals(Dimensions(1080, 14549), settings.targetDimensions(Dimensions(1440, 19399)))
        assertEquals(Dimensions(1080, 608), settings.targetDimensions(Dimensions(1920, 1080)))
        assertEquals(Dimensions(1080, 2160), settings.targetDimensions(Dimensions(540, 1080)))
        assertNull(settings.effectiveScalePercent)
    }

    @Test fun widthCanEnlargeAndRoundedHeightNeverBecomesZero() {
        assertEquals(Dimensions(20, 30), Dimensions(16, 24).withWidth(20))
        assertEquals(Dimensions(1, 1), Dimensions(10000, 1).withWidth(1))
        assertEquals(Dimensions(3, 5), Dimensions(2, 3).withWidth(3))
    }

    @Test fun specifiedWidthStillSlicesBothAxesAtExistingThreshold() {
        val size = PackSettings(resolutionMode = ResolutionMode.WIDTH, targetWidth = 20000)
            .targetDimensions(Dimensions(100, 200))
        val tiles = Slicing.plan(size)
        assertEquals(Dimensions(20000, 40000), size)
        assertEquals(8, tiles.size)
        assertEquals(size.width.toLong() * size.height, tiles.sumOf { it.width.toLong() * it.height })
    }

    @Test fun invalidWidthsAndOverflowingHeightsAreRejected() {
        for (width in listOf(0, -1)) {
            assertThrows(IllegalArgumentException::class.java) { PackSettings(targetWidth = width) }
            assertThrows(IllegalArgumentException::class.java) { Dimensions(16, 24).withWidth(width) }
        }
        assertThrows(IllegalArgumentException::class.java) { Dimensions(1, Int.MAX_VALUE).withWidth(2) }
        assertEquals(Dimensions(Int.MAX_VALUE, Int.MAX_VALUE), Dimensions(1, 1).withWidth(Int.MAX_VALUE))
        assertEquals(Dimensions(1073741824, 1073741824), Dimensions(Int.MAX_VALUE, Int.MAX_VALUE).scaled(50))
    }

    @Test fun newDefaultsAndLegacyModeNamesRemainStable() {
        assertEquals(100, PackSettings().scalePercent)
        assertEquals(75, PackSettings().scalePercentB)
        assertEquals(1080, PackSettings().targetWidth)
        assertEquals(ResolutionMode.REDUCED, ResolutionMode.valueOf("REDUCED"))
        assertThrows(IllegalArgumentException::class.java) { PackSettings(scalePercentB = 24) }
        assertThrows(IllegalArgumentException::class.java) { PackSettings(scalePercentB = 101) }
    }
}
