package com.no5.preparation.ui

import org.junit.Assert.*
import org.junit.Test

class KnobSafeDialogBoundsTest {
    @Test fun completePanelAvoidsBothKnobsAcrossStageScalesAndLetterboxing() {
        for ((width, height) in listOf(2000f to 1200f, 1000f to 600f, 2000f to 928f, 1200f to 1800f, 2400f to 1200f)) {
            val bounds = knobSafeDialogBounds(width, height, 24f)
            val scale = minOf(width / 2000f, height / 1200f)
            val stageLeft = (width - 2000f * scale) / 2f
            val stageTop = (height - 1200f * scale) / 2f
            assertTrue("Panel has useful content space", bounds.width > width * .5f && bounds.height > height * .9f)
            assertTrue(bounds.left >= 24f && bounds.right <= width - 24f)
            assertTrue(KnobGeometry.safe((bounds.left - stageLeft) / scale, (bounds.top - stageTop) / scale,
                (bounds.right - stageLeft) / scale, (bounds.bottom - stageTop) / scale))
        }
    }

    @Test fun actualLuminaApplyButtonWasInTheExcludedArea() {
        // User-visible MAP57 dialog, UI readback on 10 October. Its footer was
        // rendered after the shell masks, so those masks could not protect it.
        assertFalse(KnobGeometry.safe(1754f, 1032f, 1896f, 1128f))
        val safe = knobSafeDialogBounds(2000f, 1200f, 24f)
        assertTrue(safe.right < 1754f)
        assertTrue(safe.height > 1100f)
    }

    @Test fun largerAndroidMarginNeverShrinksThePhysicalClearance() {
        val small = knobSafeDialogBounds(2000f, 1200f, 12f)
        val large = knobSafeDialogBounds(2000f, 1200f, 36f)
        assertTrue(large.left > small.left && large.right < small.right)
        assertTrue(KnobGeometry.safe(large.left, large.top, large.right, large.bottom))
    }

    @Test fun keyboardOnlyReducesHeightAndKeepsPhysicalStageClearance() {
        val normal = knobSafeDialogBounds(2000f, 1200f, 24f)
        val keyboard = knobSafeDialogBounds(2000f, 1200f, 24f, imeBottom = 440f)
        assertEquals(normal.left, keyboard.left, 0f)
        assertEquals(normal.right, keyboard.right, 0f)
        assertEquals(normal.top, keyboard.top, 0f)
        assertEquals(736f, keyboard.bottom, 0f)
        assertTrue(KnobGeometry.safe(keyboard.left, keyboard.top, keyboard.right, keyboard.bottom))
    }
}
