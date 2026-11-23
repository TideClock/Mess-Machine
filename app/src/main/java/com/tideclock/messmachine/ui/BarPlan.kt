package com.tideclock.messmachine.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Nov 22 sizing (Tyler: "build it", after two reviews of the sizing proposal). Every floor is in real dp, on the
 * glass: the app's own dp shrink with View size (App.kt multiplies the density by it), so a floor written in app dp
 * would shrink too. 44 real dp for a key, 64 for a drum pad, whatever the view size.
 */
object Floor {
    const val KEY = 44f
    /** Tyler (Nov 22): on a narrow screen the bottom bar's keys may go down to this rather than leave the bar. */
    const val KEY_TIGHT = 38f
    const val PAD = 64f
    const val READOUT = 56f
}

/** A size in real dp on the glass, whatever View size is. */
val Float.rdp: Dp get() = (this / UiLook.scale.coerceIn(.4f, 2f)).dp
val Int.rdp: Dp get() = toFloat().rdp

/**
 * How a played bar lays out its keys. Pure arithmetic, so a plain test checks it. Every key in the bar gets the same
 * width (hands find keys blind): [base] (the widest word or icon, never under the key floor) where there is room, at
 * most half again as wide, and down to [tight] on a narrow screen. The bar never scrolls: only when keys don't fit even
 * at [tight] do the keys with the highest priority number go into a More key at the end. Priority 0 never goes. App dp.
 */
object BarPlan {
    class Plan(val slot: Float, val shown: List<Int>, val overflow: List<Int>)

    fun plan(prio: List<Int>, avail: Float, base: Float, gap: Float, more: Float, readoutMin: Float, tight: Float = base): Plan {
        val n = prio.size
        val least = minOf(tight, base)
        fun need(k: Int, withMore: Boolean): Float =
            k * least + (k - 1).coerceAtLeast(0) * gap + (if (withMore) gap + more else 0f) + (if (readoutMin > 0f) gap + readoutMin else 0f)
        val order = (0 until n).sortedByDescending { prio[it] }
        val out = ArrayList<Int>()
        for (i in order) {
            if (need(n - out.size, out.isNotEmpty()) <= avail) break
            if (prio[i] == 0) break
            out.add(i)
        }
        val shown = (0 until n).filter { it !in out }
        val k = shown.size.coerceAtLeast(1)
        // what is left after the readout's least and the More key, shared out: keys go down to [tight] before any leaves
        // the bar (Tyler, Nov 22: he needs Chords and Repeat there), and no key is wider than 1.5x its base
        val room = avail - (if (readoutMin > 0f) gap + readoutMin else 0f) - (if (out.isNotEmpty()) gap + more else 0f) - (k - 1) * gap
        val slot = (room / k).coerceIn(least, base * 1.5f)
        return Plan(slot, shown, out.sorted())
    }
}
