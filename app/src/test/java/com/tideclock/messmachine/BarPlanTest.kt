package com.tideclock.messmachine

import com.tideclock.messmachine.ui.BarPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nov 22 sizing: the arithmetic behind the bottom dock, checked without a phone. */
class BarPlanTest {
    private val prio = listOf(0, 6, 3, 2, 1, 4, 5)  // Edit, Pack, Hold, Chords, Repeat, Mute, Drop

    @Test fun roomyBarKeepsEveryKeyAndCapsTheirWidth() {
        val p = BarPlan.plan(prio, 900f, 48f, 5f, 38f, 64f)
        assertEquals((0 until 7).toList(), p.shown)
        assertTrue(p.overflow.isEmpty())
        assertEquals("no key wider than 1.5x its base", 72f, p.slot, .01f)
    }

    @Test fun narrowBarShrinksKeysBeforeAnyLeave() {
        // Tyler's folded phone at 70 percent, in app dp: keys go down toward the tight floor and every one stays
        val p = BarPlan.plan(prio, 514f, 62.9f, 5f, 62.9f, 80f, 54.3f)
        assertTrue(p.overflow.isEmpty())
        assertTrue("smaller than the base, not under the tight floor (${p.slot})", p.slot < 62.9f && p.slot >= 54.3f)
        // narrower still: Pack goes first; Chords and Repeat last of all
        val narrow = BarPlan.plan(prio, 420f, 62.9f, 5f, 62.9f, 80f, 54.3f)
        assertEquals(1, narrow.overflow.first())
        assertTrue(3 in narrow.shown && 4 in narrow.shown)
    }

    @Test fun whatIsShownAlwaysFits() {
        for (avail in 200..1000 step 7) {
            val p = BarPlan.plan(prio, avail.toFloat(), 48f, 5f, 38f, 64f, 42f)
            val used = p.shown.size * p.slot + (p.shown.size - 1) * 5f + (if (p.overflow.isEmpty()) 0f else 5f + 38f) + 5f + 64f
            assertTrue("fits in $avail ($used)", used <= avail + .01f || p.shown == listOf(0))
            assertTrue("never under the tight floor", p.slot >= 42f)
            assertTrue("Edit always shows", 0 in p.shown)
        }
    }
}
