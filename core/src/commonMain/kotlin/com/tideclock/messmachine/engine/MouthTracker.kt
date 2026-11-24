package com.tideclock.messmachine.engine

import kotlin.concurrent.Volatile
import kotlin.jvm.JvmField
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Mouth control (Tyler: "make wubby noises and convert it to automation"): reads the mic and turns your mouth into
 * two moving numbers, like a finger on an XY pad.
 *
 * <ul>
 *   <li><b>vowel</b>, 0 to 1: how bright the sound is. "Ooo" is dark (near 0), "aah" sits in the middle, "eee" is
 *   bright (near 1). Measured as the spectral centroid between 150 Hz and 4 kHz (pre-emphasized, as speech
 *   analysis does), on a log scale from 600 Hz to 1.8 kHz. A "wow wow" sweeps it up and down, which is the wub.</li>
 *   <li><b>level</b>, 0 to 1: how loud, from about -50 dB to -10 dB.</li>
 *   <li><b>on</b>: you're making a sound (over the gate, with a little hysteresis so it doesn't flicker).</li>
 * </ul>
 *
 * Feed it mono samples at any rate as they arrive; it analyses 32 ms windows every 8 ms. No memory is made after
 * it is built, so it can run on the mic thread without hiccups.
 */
class MouthTracker(@JvmField val rate: Int) {
    private val n: Int
    private val hop: Int
    private val ring: FloatArray
    private var at = 0; private var sinceHop = 0; private var filled = 0
    private val win: DoubleArray; private val re: DoubleArray; private val im: DoubleArray

    /** Smoothed outputs, read them any time. */
    @Volatile @JvmField var vowel = 0f
    @Volatile @JvmField var level = 0f
    @Volatile @JvmField var on = false
    /** The raw numbers of the last window, for the meter and for tests. */
    @Volatile @JvmField var centroidHz = 0f
    @Volatile @JvmField var db = -120f
    /** The gate in dB: under it you're quiet. */
    @JvmField var gateDb = -46f
    private var quietHops = 0

    init {
        var nn = 256
        while (nn < rate * .032) nn *= 2
        n = nn
        hop = maxOf(32, jround(rate * .008).toInt())
        ring = FloatArray(n)
        win = DoubleArray(n)
        for (i in 0 until n) win[i] = .5 - .5 * cos(2 * PI * i / (n - 1))
        re = DoubleArray(n)
        im = DoubleArray(n)
    }

    /** Add samples; true when at least one new window was measured. */
    fun feed(x: FloatArray, off: Int, len: Int): Boolean {
        var any = false
        for (i in 0 until len) {
            ring[at] = x[off + i]
            at = (at + 1) % n
            if (filled < n) filled++
            if (++sinceHop >= hop && filled >= n) { sinceHop = 0; measure(); any = true }
        }
        return any
    }

    private fun measure() {
        var sq = 0.0
        for (i in 0 until n) {
            val v = ring[(at + i) % n].toDouble()
            sq += v * v
            re[i] = v * win[i]
            im[i] = 0.0
        }
        val rms = sqrt(sq / n)
        val d = (20 * log10(maxOf(1e-6, rms))).toFloat()
        db = d
        Bake.fft(re, im, false)
        val lo = 150.0; val hi = 4000.0; var num = 0.0; var den = 0.0
        val a = ceil(lo * n / rate).toInt(); val z = minOf(n / 2 - 1, floor(hi * n / rate).toInt())
        for (k in a..z) {
            // pre-emphasis (+6 dB an octave, as speech analysis does) so the upper formant that makes "eee"
            // bright counts as much as the strong low one
            val f = k * rate.toDouble() / n
            val m = sqrt(re[k] * re[k] + im[k] * im[k]) * f
            num += m * f
            den += m
        }
        val c = if (den > 0) num / den else 0.0
        centroidHz = c.toFloat()
        // the gate: on above it, off after 80 ms under it (3 dB lower), so a word's gaps don't flicker it
        if (d > gateDb) { on = true; quietHops = 0 }
        else if (d < gateDb - 3 && ++quietHops * hop > rate * .08) on = false
        val lvl = maxOf(0.0, minOf(1.0, (d + 50) / 40.0)).toFloat()
        level += (lvl - level) * (if (lvl > level) .6f else .2f)
        if (on && c > 0) {
            val v = maxOf(0.0, minOf(1.0, ln(c / 600.0) / ln(1800.0 / 600.0))).toFloat()
            vowel += (v - vowel) * .45f
        }
    }

    /** Start over (when the mic opens again). */
    fun reset() {
        ring.fill(0f)
        at = 0; sinceHop = 0; filled = 0; quietHops = 0
        vowel = 0f; level = 0f; on = false; db = -120f; centroidHz = 0f
    }
}
