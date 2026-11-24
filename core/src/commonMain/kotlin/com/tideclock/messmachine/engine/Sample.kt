package com.tideclock.messmachine.engine

import kotlin.concurrent.Volatile
import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** One decoded sound, mono floats at its own sample rate. */
class Sample(@JvmField val key: String, @JvmField val data: FloatArray, @JvmField val rate: Int) {
    // was a locked lazy field: now computed once and published (a rare second computation is harmless)
    @Volatile private var reversed: FloatArray? = null
    // was a HashMap behind a lock: a CowMap, read without locks
    private val peaks = CowMap<Long, Double>()

    fun seconds(): Double { return data.size / rate.toDouble() }

    /** SE-3: baked versions at other pitches with the length kept, index semitones + Bake.RANGE; null until baked. */
    @Volatile @JvmField var baked: Array<FloatArray?>? = null

    /** SE-4: a loop's own tempo (0 for one-shots), so it can play at the mess's tempo. */
    @JvmField var bpm: Double = 0.0
    @JvmField var beats: Int = 0

    /** The baked version for a pitch ratio, when it lands within 3 cents of a whole semitone in range; else null. */
    fun bakedFor(ratio: Double): FloatArray? {
        val b = baked
        if (b == null || ratio <= 0) return null
        val semis = 12 * ln(ratio) / ln(2.0)
        val n = jround(semis)
        if (abs(semis - n) > .03 || n == 0L || abs(n) > Bake.RANGE) return null
        return b[n.toInt() + Bake.RANGE]
    }

    /** Loudest point inside a trim region, for normalize. Cached for the last region asked. */
    fun regionPeak(st: Double, en: Double): Double {
        val id = jround(st * 1e6) * 2_000_003L + jround(en * 1e6)
        val c = peaks[id]
        if (c != null) return c
        val a = floor(st * data.size).toInt()
        val z = ceil(en * data.size).toInt()
        var pk = 1e-4
        for (k in maxOf(0, a) until minOf(data.size, z)) { val v = abs(data[k]).toDouble(); if (v > pk) pk = v }
        if (peaks.size > 256) peaks.clear()
        peaks[id] = pk
        return pk
    }

    /** Min/max pairs for a window of the sound (fractions a..z), for a zoomed waveform. */
    fun overview(a: Double, z: Double, cols: Int): FloatArray {
        val o = FloatArray(cols * 2)
        val n = data.size
        val from = (a * n).toLong()
        val span = maxOf(1L, ((z - a) * n).toLong())
        for (c in 0 until cols) {
            val x0 = (from + span * c / cols).toInt()
            val x1 = maxOf((x0 + 1).toLong(), from + span * (c + 1) / cols).toInt()
            var lo = 0f
            var hi = 0f
            for (k in maxOf(0, x0) until minOf(n, x1)) { val v = data[k]; if (v < lo) lo = v; if (v > hi) hi = v }
            o[c * 2] = lo; o[c * 2 + 1] = hi
        }
        return o
    }

    /** The nearest zero crossing to f (a fraction) within 4 ms, for clean trims. */
    fun snapZero(f: Double): Double {
        val i0 = jround(f * data.size).toInt()
        val R = jround(rate * .004).toInt()
        for (r in 0 until R) {
            for (i in intArrayOf(i0 + r, i0 - r)) {
                if (i > 0 && i < data.size && (data[i - 1] <= 0) != (data[i] <= 0)) return i / data.size.toDouble()
            }
        }
        return f
    }

    /** Where the hits are, as fractions, for auto chop. Same method as the site: energy flux over a threshold. */
    fun onsets(st: Double, en: Double, sens: Double): DoubleArray {
        val hop = 512
        val a = floor(st * data.size).toInt()
        val z = floor(en * data.size).toInt()
        val e = ArrayList<Double>()
        var i0 = a
        while (i0 + hop < z) { var s = 0.0; for (j in 0 until hop) s += data[i0 + j] * data[i0 + j]; e.add(sqrt(s / hop)); i0 += hop }
        val n = e.size
        if (n < 3) return DoubleArray(0)
        val fl = DoubleArray(n)
        var mean = 0.0
        for (i in 0 until n) { fl[i] = maxOf(0.0, e[i] - (if (i > 0) e[i - 1] else e[i])); mean += fl[i] }
        mean /= n
        var sd = 0.0
        for (v in fl) sd += (v - mean) * (v - mean)
        sd = sqrt(sd / n)
        val th = mean + (2.6 - sens * 2) * sd
        val gap = jround(.08 * rate / hop).toInt()
        val out = ArrayList<IntArray>()
        var last = -1000
        for (i in 2 until n) if (fl[i] > th && i - last >= gap) { out.add(intArrayOf(i)); last = i }
        out.sortWith { x, y -> fl[y[0]].compareTo(fl[x[0]]) }
        val keep = minOf(15, out.size)
        val r = DoubleArray(keep)
        for (k in 0 until keep) r[k] = (a + out[k][0] * hop.toDouble()) / data.size
        r.sort()
        return r
    }

    fun reversedData(): FloatArray {
        val have = reversed
        if (have != null) return have
        val n = data.size
        val r = FloatArray(n)
        for (k in 0 until n) r[k] = data[n - 1 - k]
        reversed = r
        return r
    }

    /** Min/max pairs for drawing a waveform with the given number of columns. */
    fun overview(cols: Int): FloatArray {
        val o = FloatArray(cols * 2)
        val n = data.size
        for (c in 0 until cols) {
            val a = (c.toLong() * n / cols).toInt()
            val z = maxOf((a + 1).toLong(), (c + 1).toLong() * n / cols).toInt()
            var lo = 0f
            var hi = 0f
            for (k in a until minOf(n, z)) { val v = data[k]; if (v < lo) lo = v; if (v > hi) hi = v }
            o[c * 2] = lo; o[c * 2 + 1] = hi
        }
        return o
    }

    companion object {
        /** Mix interleaved stereo down to one sound. */
        @JvmStatic
        fun fromStereo(key: String, lr: FloatArray, rate: Int): Sample {
            val d = FloatArray(lr.size / 2)
            for (k in d.indices) d[k] = (lr[k * 2] + lr[k * 2 + 1]) * .5f
            return Sample(key, d, rate)
        }

        /** A slice of a longer sprite file, start and length in seconds. */
        @JvmStatic
        fun slice(key: String, sprite: Sample, start: Double, dur: Double): Sample {
            val a = maxOf(0L, jround(start * sprite.rate)).toInt()
            val z = minOf(sprite.data.size.toLong(), jround((start + dur) * sprite.rate)).toInt()
            val d = FloatArray(maxOf(1, z - a))
            sprite.data.copyInto(d, 0, a, a + maxOf(0, z - a))
            return Sample(key, d, sprite.rate)
        }

        /** Rounds like the Java engine did (half up): roundToLong, but NaN gives 0 instead of throwing. */
        private fun jround(d: Double): Long = if (d.isNaN()) 0L else d.roundToLong()
    }
}
