package com.tideclock.messmachine

import com.tideclock.messmachine.ui.BarPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nov 22 sizing: the arithmetic behind the bottom dock, checked without a phone. */
class BarPlanTest {
    private val prio = listOf(0, 4, 1, 6, 5, 2, 3)  // Edit, Pack, Hold, Chords, Repeat, Mute, Drop

    @Test fun roomyBarKeepsEveryKeyAndCapsTheirWidth() {
        val p = BarPlan.plan(prio, 900f, 48f, 5f, 38f, 64f)
        assertEquals((0 until 7).toList(), p.shown)
        assertTrue(p.overflow.isEmpty())
        assertEquals("no key wider than 1.5x its base", 72f, p.slot, .01f)
    }

    @Test fun tightBarSendsChordsThenRepeatThenPack() {
        val p = BarPlan.plan(prio, 400f, 48f, 5f, 38f, 64f)
        assertEquals(listOf(3, 4), p.overflow)
        assertTrue("keys never shrink under their base", p.slot >= 48f)
        val narrower = BarPlan.plan(prio, 330f, 48f, 5f, 38f, 64f)
        assertEquals(listOf(1, 3, 4), narrower.overflow)
    }

    @Test fun whatIsShownAlwaysFits() {
        for (avail in 200..1000 step 7) {
            val p = BarPlan.plan(prio, avail.toFloat(), 48f, 5f, 38f, 64f)
            val used = p.shown.size * p.slot + (p.shown.size - 1) * 5f + (if (p.overflow.isEmpty()) 0f else 5f + 38f) + 5f + 64f
            assertTrue("fits in $avail ($used)", used <= avail + .01f || p.shown == listOf(0))
            assertTrue("Edit always shows", 0 in p.shown)
        }
    }
}
