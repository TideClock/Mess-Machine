package com.tideclock.messmachine.ui

/** The rate strip's stops, slowest on the left: steps per full wave. */
val LFO_STOPS = listOf(32.0 to "2 bars", 16.0 to "1 bar", 8.0 to "1/2", 4.0 to "1/4", 2.0 to "1/8", 1.0 to "1/16", .5 to "1/32")

/** A rate split into its stop and its feel: 1 straight, 2/3 triplet, 1.5 dotted. */
fun lfoParts(r: Double): Pair<Double, Double> {
    for ((b, _) in LFO_STOPS) for (m in listOf(1.0, 2.0 / 3, 1.5)) if (kotlin.math.abs(b * m - r) < 1e-6) return b to m
    return 4.0 to 1.0
}

