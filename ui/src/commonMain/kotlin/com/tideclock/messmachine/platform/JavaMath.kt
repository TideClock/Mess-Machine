package com.tideclock.messmachine.platform

import kotlin.math.abs
import kotlin.math.floor

/** Java's Math.round for doubles: halves go up (CMP step 4; common Kotlin's round() goes to even). */
fun jround(x: Double): Long = floor(x + 0.5).toLong()

/** Java's Math.round for floats. */
fun jround(x: Float): Int = floor(x + 0.5f).toInt()

/**
 * "%.Nf".format(x) in common code: [digits] places, halves up, a dot for the decimal point on every phone (Java used
 * the phone's language, so a German phone showed a comma; the app's text is English, so the dot is right).
 */
fun fmt(x: Double, digits: Int): String {
    if (x.isNaN()) return "NaN"
    if (x.isInfinite()) return if (x > 0) "Infinity" else "-Infinity"
    var p = 1L
    repeat(digits) { p *= 10 }
    val r = floor(abs(x) * p + 0.5).toLong()
    val whole = (r / p).toString()
    val s = if (digits == 0) whole else whole + "." + (r % p).toString().padStart(digits, '0')
    return if (x < 0) "-$s" else s
}
