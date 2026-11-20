package com.whereq.dosbox

import com.whereq.dosbox.GameModeView.Companion.COLOR_GOLD
import com.whereq.dosbox.GameModeView.Companion.COLOR_KEY
import com.whereq.dosbox.GameModeView.Companion.COLOR_KEY_ARMED
import com.whereq.dosbox.GameModeView.Companion.COLOR_KEY_DARK
import com.whereq.dosbox.GameModeView.Companion.COLOR_TEXT_ON_GOLD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The outline that marks a button as remappable.
 *
 * The remap mode outlines the buttons that can be remapped, so the outline
 * has to be visible on the chip it marks. The FIRE chip is filled with the
 * gold, and a gold outline on a gold chip is invisible - the button looks
 * like any other one, and the user does not know it can be remapped.
 *
 * The rule is therefore: the outline contrasts with the fill, and it is
 * chosen by the luminance of the fill.
 *
 * This is a JVM test on purpose: the rule is arithmetic, and arithmetic does
 * not need a device.
 */
class RemapOutlineTest {

    @Test
    fun theGoldChipGetsADarkOutline() {
        // The bug this rule exists for: gold on gold is invisible.
        assertEquals(
            "the gold FIRE chip should get a dark outline",
            COLOR_TEXT_ON_GOLD,
            GameModeView.outlineFor(COLOR_GOLD)
        )
    }

    @Test
    fun theDarkChipsGetTheGoldOutline() {
        assertEquals(
            "a dark chip should get the gold outline",
            COLOR_GOLD,
            GameModeView.outlineFor(COLOR_KEY)
        )
        assertEquals(
            "a dark chip should get the gold outline",
            COLOR_GOLD,
            GameModeView.outlineFor(COLOR_KEY_DARK)
        )
        assertEquals(
            "the armed chip should get the gold outline",
            COLOR_GOLD,
            GameModeView.outlineFor(COLOR_KEY_ARMED)
        )
    }

    @Test
    fun noChipEverGetsAnOutlineOfItsOwnColour() {
        // The invariant the bug broke: the outline is never the fill.
        for (fill in listOf(COLOR_GOLD, COLOR_KEY, COLOR_KEY_DARK, COLOR_KEY_ARMED)) {
            assertNotEquals(
                "the outline must not be the colour of the chip it marks",
                fill,
                GameModeView.outlineFor(fill)
            )
        }
    }

    @Test
    fun theGoldIsLightAndTheKeysAreNot() {
        assertTrue("the gold should read as light", GameModeView.isLight(COLOR_GOLD))
        assertTrue(
            "the dark key colour should not read as light",
            !GameModeView.isLight(COLOR_KEY)
        )
        assertTrue(
            "the darker key colour should not read as light",
            !GameModeView.isLight(COLOR_KEY_DARK)
        )
    }

    @Test
    fun aPlainAverageWouldCallTheGoldDark() {
        // Why the luminance is weighted rather than averaged: the average of
        // the gold's channels is 140, which is exactly the threshold - and it
        // is the *bright* amber, not a dark colour. A plain average would put
        // the gold on the wrong side of the rule, and the FIRE chip would get
        // its gold outline back.
        val r = (COLOR_GOLD shr 16) and 0xFF
        val g = (COLOR_GOLD shr 8) and 0xFF
        val b = COLOR_GOLD and 0xFF
        val average = (r + g + b) / 3
        assertEquals(
            "the plain average of the gold's channels is the threshold itself",
            140,
            average
        )
        assertTrue(
            "the weighted luminance still reads the gold as light",
            GameModeView.isLight(COLOR_GOLD)
        )
    }
}
