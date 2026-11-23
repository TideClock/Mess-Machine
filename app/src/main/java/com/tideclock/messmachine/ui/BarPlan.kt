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
    const val PAD = 64f
    const val READOUT = 64f
}

/** A size in real dp on the glass, whatever View size is. */
val Float.rdp: Dp get() = (this / UiLook.scale.coerceIn(.4f, 2f)).dp
val Int.rdp: Dp get() = toFloat().rdp

/**
 * How a played bar lays out its keys. Pure arithmetic, so a plain test checks it. Every key in the bar gets the same
 * width (hands find keys blind), at least [base] (the widest word or icon, and never under the key floor) and at most
 * half again as wide. Keys never shrink under that and the bar never scrolls: when they don't fit, the keys with the
 * highest priority number go into a ⋯ key at the end, first to go first. Priority 0 never goes. Widths in app dp.
 */
object BarPlan {
    class Plan(val slot: Float, val shown: List<Int>, val overflow: List<Int>)

    fun plan(prio: List<Int>, avail: Float, base: Float, gap: Float, more: Float, readoutMin: Float): Plan {
        val n = prio.size
        fun need(k: Int, withMore: Boolean): Float =
            k * base + (k - 1).coerceAtLeast(0) * gap + (if (withMore) gap + more else 0f) + (if (readoutMin > 0f) gap + readoutMin else 0f)
        val order = (0 until n).sortedByDescending { prio[it] }
        val out = ArrayList<Int>()
        for (i in order) {
            if (need(n - out.size, out.isNotEmpty()) <= avail) break
            if (prio[i] == 0) break
            out.add(i)
        }
        val shown = (0 until n).filter { it !in out }
        val k = shown.size.coerceAtLeast(1)
        // what is left after the readout's least and the ⋯ key, shared out, but no key wider than 1.5x its base
        val room = avail - (if (readoutMin > 0f) gap + readoutMin else 0f) - (if (out.isNotEmpty()) gap + more else 0f) - (k - 1) * gap
        val slot = (room / k).coerceIn(base, base * 1.5f)
        return Plan(slot, shown, out.sorted())
    }
}
