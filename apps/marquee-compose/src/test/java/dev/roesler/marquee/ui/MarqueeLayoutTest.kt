package dev.roesler.marquee.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarqueeLayoutTest {
    @Test
    fun shieldFourKOverrideUsesCompactLogicalLayout() {
        assertEquals(MarqueeSizeClass.COMPACT_TV, marqueeSizeClass(960f, 540f))
    }

    @Test
    fun fullHdLogicalCanvasUsesExpandedLayout() {
        assertEquals(MarqueeSizeClass.EXPANDED_TV, marqueeSizeClass(1_920f, 1_080f))
    }

    @Test
    fun compactLayoutKeepsOneCompleteShelfInsideShieldViewport() {
        val layout = marqueeLayout(MarqueeSizeClass.COMPACT_TV)
        val estimatedShelfHeight = layout.posterWidth.value * 1.5f + 54f

        assertTrue(estimatedShelfHeight < 230f)
    }
}
