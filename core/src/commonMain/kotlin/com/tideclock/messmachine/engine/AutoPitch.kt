package com.tideclock.messmachine.engine

import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Re-pitch (Nov 26, Tyler: "a button that sets it to C for C"). Finds what a sound plays and how far to move it so it
 * sits in the mess.
 *
 * - A sound with one clear note (a pluck, a hum, a bass hit, a held chord) is moved so that note is a C, the note the
 *   app treats as every sound's home: keys, chords and Roll notes then play the notes they say. Works on sounds as
 *   short as NOTE_MIN_SECONDS, judged on their first NOTE_SECONDS.
 * - A sound with a key instead (a melody or a loop, KeyFinder) is moved by the fewest semitones that put its notes in
 *   the mess's scale on the mess's root, and follows the mess when its key changes.
 * - Drums, noise and anything unclear get nothing.
 * Both also take out the sound's own tuning drift, so a sample a little sharp lands on the note.
 */
object AutoPitch {
    const val NOTE_MIN_SECONDS = .04
    const val NOTE_SECONDS = 2.0
    /** Never moves a sound more than this many semitones, so it does not turn to chipmunk or sludge. */
    const val MAX_MOVE = 6.0

    /** What a sound plays. kind "note": midi is the note (C4 = 60, with cents). kind "key": key, chroma and drift. */
    class Found internal constructor(@JvmField val kind: String) {
        @JvmField var midi = 0.0
        @JvmField var key: KeyFinder.Key? = null
        @JvmField var chroma = DoubleArray(12)
        @JvmField var drift = 0.0
        /** "G#, 18 cents sharp" or "D minor": what the Sample sheet shows. */
        fun label(): String {
            if (kind == "key") return key!!.name()
            val n = round(midi)
            val c = round((midi - n) * 100).toInt()
            return KeyFinder.NAMES[((n.toInt() % 12) + 12) % 12] + when {
                abs(c) < 3 -> ""
                c > 0 -> ", $c cents sharp"
                else -> ", ${-c} cents flat"
            }
        }
    }

    /** A key is still used to re-pitch when it matches this well, below KeyFinder's bar for naming one (short loop slices). */
    const val LOOSE = .6

    /**
     * What the trimmed part (st..en, fractions) of a sound plays, or null when it has no clear note or key.
     * known: the key the library already has for the sound ("" for none), used when the sound alone is not clear enough.
     */
    @JvmStatic
    fun detect(data: FloatArray?, rate: Int, st: Double, en: Double): Found? = detect(data, rate, st, en, "")

    @JvmStatic
    fun detect(data: FloatArray?, rate: Int, st: Double, en: Double, known: String?): Found? {
        if (data == null || rate <= 0) return null
        val a = (maxOf(0.0, minOf(1.0, st)) * data.size).toInt()
        val z = (maxOf(0.0, minOf(1.0, en)) * data.size).toInt()
        if (z - a < NOTE_MIN_SECONDS * rate) return null
        val kn = KeyFinder.parse(known)
        val t = if (z - a < KeyFinder.MIN_SECONDS * rate) null else KeyFinder.measure(data.copyOfRange(a, z), rate)
        val k = if (t == null || t.frames == 0) null else KeyFinder.ranked(t.chroma)[0]
        val sureKey = k != null && KeyFinder.sure(k, t!!.tonal)
        // one note first (noteOf asks a long sound to agree far more than a short hit, so a loop with a bass line
        // under it is not taken for one note), then a key
        val m = noteOf(data, rate, a, z)
        if (m != null) return Found("note").also { it.midi = m }
        if (sureKey) return Found("key").also { it.key = k; it.chroma = t!!.chroma.copyOf(); it.drift = t.drift }
        if (kn != null) return Found("key").also { it.key = kn; it.chroma = KeyFinder.shape(kn); it.drift = if (t != null && t.frames > 0) t.drift else 0.0 }
        if (k != null && k.match >= LOOSE && t!!.tonal >= KeyFinder.TONAL) return Found("key").also { it.key = k; it.chroma = t.chroma.copyOf(); it.drift = t.drift }
        return null
    }

    /** Semitones (with cents) to add so the sound sits in the mess: root 0..11, scale a Theory scale name. */
    @JvmStatic
    fun shift(f: Found, root: Int, scale: String): Double {
        if (f.kind == "note") return 12 * round(f.midi / 12) - f.midi
        var best = 0; var top = -1.0
        if (scale == "Chromatic" || !Theory.SCALES.containsKey(scale)) {
            // every note is in a chromatic scale: line the key up with the root (its major, or its relative major)
            best = wrap(root - f.key!!.notes())
        } else {
            for (s in listOf(0, 1, -1, 2, -2, 3, -3, 4, -4, 5, -5, 6)) {
                var fit = 0.0
                for (pc in 0 until 12) if (Theory.inScale(pc + s, scale, root)) fit += f.chroma[pc]
                if (fit > top + 1e-9) { top = fit; best = s }
            }
        }
        return best - f.drift
    }

    private fun wrap(s: Int): Int { val w = ((s % 12) + 12) % 12; return if (w > 6) w - 12 else w }

    /**
     * The one note a sound holds (midi, with cents), or null when it has none: YIN on the first NOTE_SECONDS after the
     * attack, every loud frame voted, kept only when most of them agree on the pitch class within half a semitone.
     */
    @JvmStatic
    fun noteOf(data: FloatArray, rate: Int, from: Int, to: Int): Double? {
        val f = frames(data, rate, from, to) ?: return null
        // a short hit gets the benefit of the doubt; a long sound must agree more, so a melody or a loop is not one note
        val sec = (to - from) / rate.toDouble()
        val need = .5 + .35 * maxOf(0.0, minOf(1.0, (sec - .3) / .9))
        val bar = f.loudWeight(0, f.midi.size)
        // then once more with a whole semitone of give, for a wobbly note (vibrato, a spring's boing)
        return vote(f.midi, f.weight, 0, f.midi.size, bar, need) ?: vote(f.midi, f.weight, 0, f.midi.size, bar, need, 1.0)
    }

    /** The pitch of each short frame of a sound (YIN), for noteOf and for chopping a melody into its notes. */
    class Frames internal constructor(
        /** midi of each frame, NaN where the frame has no clear pitch or is quiet */
        @JvmField val midi: DoubleArray,
        /** how much each pitched frame counts (louder and clearer count more), 0 where midi is NaN */
        @JvmField val weight: DoubleArray,
        /** each frame's loudness (RMS), and whether it is loud enough to judge */
        @JvmField val rms: DoubleArray,
        @JvmField val loud: BooleanArray,
        /** where frame i starts and how long it is, in samples of the original data */
        @JvmField val start: Int, @JvmField val hop: Int, @JvmField val len: Int,
    ) {
        /**
         * The bar votes are measured against: what the loud frames in a range could carry. A loud frame with no pitch
         * (an attack's click, a breath) counts for less than a pitched one, so a short pluck is not sunk by its attack.
         */
        fun loudWeight(a: Int, z: Int): Double { var s = 0.0; for (i in a until z) if (loud[i]) s += if (midi[i].isNaN()) sqrt(rms[i]) * .6 else weight[i]; return s }
    }

    /**
     * YIN pitch per frame over data[from, to), at most NOTE_SECONDS of it after the first moment of attack. The window
     * shrinks for short sounds (down to about 30 ms), so a 50 ms blip still gets several frames; the lowest note it can
     * hear is then higher (about 60 Hz for 50 ms), which is fine for anything that short.
     */
    @JvmStatic
    fun frames(data: FloatArray, rate: Int, from: Int, to: Int, maxSeconds: Double = NOTE_SECONDS): Frames? {
        if (rate <= 0 || to - from <= 0) return null
        val step = if (rate >= 32000) 2 else 1
        val sr = rate / step.toDouble()
        val a = from + (minOf(.012, (to - from) / rate.toDouble() * .08) * rate).toInt()
        val z = minOf(to, a + (maxSeconds * rate).toInt())
        val n = (z - a) / step
        if (n < sr * .03) return null
        val x = FloatArray(n)
        for (i in 0 until n) { val j = a + i * step; x[i] = if (step == 2) (data[j] + data[minOf(j + 1, data.size - 1)]) * .5f else data[j] }
        val lag = minOf((sr / 40).toInt(), 1024, n / 3)
        val lag0 = maxOf(2, (sr / 2500).toInt())
        if (lag <= lag0 + 2) return null
        val w = maxOf(lag, minOf(1024, n / 3))
        if (w + lag > n) return null
        val hop = maxOf(32, w / 4)
        val count = (n - w - lag) / hop + 1
        val rms = DoubleArray(count)
        for (k in 0 until count) {
            var e = 0.0
            val at = k * hop
            for (i in at until at + w) e += x[i] * x[i].toDouble()
            rms[k] = sqrt(e / w)
        }
        var top = 0.0
        for (v in rms) if (v > top) top = v
        if (top < 1e-4) return null
        val midi = DoubleArray(count) { Double.NaN }
        val weight = DoubleArray(count)
        val loud = BooleanArray(count)
        val d = DoubleArray(lag + 1)
        for (k in 0 until count) {
            if (rms[k] < top * .08) continue
            loud[k] = true
            val at = k * hop
            for (t in 1..lag) {
                var s = 0.0
                for (i in at until at + w) { val q = x[i] - x[i + t].toDouble(); s += q * q }
                d[t] = s
            }
            // cumulative mean normalised difference
            var run = 0.0
            d[0] = 1.0
            for (t in 1..lag) { run += d[t]; d[t] = if (run > 0) d[t] * t / run else 1.0 }
            // the first dip under .15 (YIN's rule, which avoids octave errors); failing that the deepest dip if it is
            // still clearly periodic (a noisy recording or a fast decay never gets under .15)
            var got = -1
            var t = lag0
            while (t < lag) {
                if (d[t] < .15) { while (t + 1 < lag && d[t + 1] < d[t]) t++; got = t; break }
                t++
            }
            if (got < 0) {
                var lo = .3
                for (u in lag0 until lag) if (d[u] < lo && d[u] <= d[u - 1] && d[u] <= d[u + 1]) { lo = d[u]; got = u }
            }
            if (got <= 0 || got >= lag) continue
            val y0 = d[got - 1]; val y1 = d[got]; val y2 = d[got + 1]
            val den = y0 - 2 * y1 + y2
            val tau = got + (if (abs(den) > 1e-12) .5 * (y0 - y2) / den else 0.0)
            if (tau <= 0) continue
            midi[k] = 69 + 12 * ln(sr / tau / 440.0) / ln(2.0)
            weight[k] = sqrt(rms[k]) * (1 - minOf(.9, y1))
        }
        return Frames(midi, weight, rms, loud, a, hop * step, w * step)
    }

    /**
     * The pitch class most of frames [i0, i1) agree on (octave slips count as agreeing), averaged, or null when they
     * do not agree enough: the agreeing frames must carry at least `need` of what every loud frame could carry.
     */
    @JvmStatic
    fun vote(midi: DoubleArray, weight: DoubleArray, i0: Int, i1: Int, bar: Double, need: Double = .5, give: Double = .5): Double? {
        var bestM = 0.0; var bestW = -1.0; var bestN = 0
        for (i in i0 until i1) {
            val c = midi[i]; if (c.isNaN()) continue
            var s = 0.0; var k = 0
            for (j in i0 until i1) { val o = midi[j]; if (!o.isNaN() && abs(pcDiff(o, c)) < give) { s += weight[j]; k++ } }
            if (s > bestW) { bestW = s; bestM = c; bestN = k }
        }
        if (bestN < 2 || bestW < bar * need) return null
        var sum = 0.0
        for (i in i0 until i1) { val o = midi[i]; if (o.isNaN()) continue; val dv = pcDiff(o, bestM); if (abs(dv) < give) sum += dv * weight[i] }
        return bestM + sum / bestW
    }

    /** a minus b in semitones, folded into -6..6 so octaves do not count. */
    private fun pcDiff(a: Double, b: Double): Double { var v = (a - b) % 12; if (v > 6) v -= 12; if (v < -6) v += 12; return v }
}
