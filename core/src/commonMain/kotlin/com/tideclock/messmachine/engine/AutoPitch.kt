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
    const val NOTE_MIN_SECONDS = .1
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
        val m = noteOf(data, rate, a, z)
        if (m != null) return Found("note").also { it.midi = m }
        val kn = KeyFinder.parse(known)
        val t = if (z - a < KeyFinder.MIN_SECONDS * rate) null else KeyFinder.measure(data.copyOfRange(a, z), rate)
        val k = if (t == null || t.frames == 0) null else KeyFinder.ranked(t.chroma)[0]
        if (k != null && KeyFinder.sure(k, t!!.tonal)) return Found("key").also { it.key = k; it.chroma = t.chroma.copyOf(); it.drift = t.drift }
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
        val step = if (rate >= 32000) 2 else 1
        val sr = rate / step.toDouble()
        val a = from + (minOf(.03, (to - from) / rate.toDouble() * .2) * rate).toInt()
        val z = minOf(to, a + (NOTE_SECONDS * rate).toInt())
        val w = 1024
        val lag = minOf((sr / 45).toInt(), 1024)
        val lag0 = maxOf(2, (sr / 2000).toInt())
        val n = (z - a) / step
        if (n < w + lag) return null
        val x = FloatArray(n)
        for (i in 0 until n) { val j = a + i * step; x[i] = if (step == 2) (data[j] + data[minOf(j + 1, data.size - 1)]) * .5f else data[j] }
        var peak = 0f
        for (v in x) if (abs(v) > peak) peak = abs(v)
        if (peak < 1e-4f) return null
        val hop = 512
        val d = DoubleArray(lag + 1)
        val notes = ArrayList<Double>()
        var loud = 0
        var at = 0
        while (at + w + lag <= n) {
            var e = 0.0
            for (i in at until at + w) e += x[i] * x[i].toDouble()
            if (sqrt(e / w) >= peak * .05) {
                loud++
                for (t in 1..lag) {
                    var s = 0.0
                    for (i in at until at + w) { val q = x[i] - x[i + t].toDouble(); s += q * q }
                    d[t] = s
                }
                // cumulative mean normalised difference
                var run = 0.0
                d[0] = 1.0
                for (t in 1..lag) { run += d[t]; d[t] = if (run > 0) d[t] * t / run else 1.0 }
                var t = lag0
                var got = -1
                while (t < lag) {
                    if (d[t] < .15) { while (t + 1 < lag && d[t + 1] < d[t]) t++; got = t; break }
                    t++
                }
                if (got > 0) {
                    val y0 = d[got - 1]; val y1 = d[got]; val y2 = d[got + 1]
                    val den = y0 - 2 * y1 + y2
                    val tau = got + (if (abs(den) > 1e-12) .5 * (y0 - y2) / den else 0.0)
                    notes.add(69 + 12 * ln(sr / tau / 440.0) / ln(2.0))
                }
            }
            at += hop
        }
        if (notes.size < 3 || notes.size < loud * .6) return null
        // the pitch class most frames agree on (octave slips count as agreeing), then the average of those frames
        var bestM = 0.0; var bestN = -1
        for (c in notes) {
            var k = 0
            for (o in notes) if (abs(pcDiff(o, c)) < .5) k++
            if (k > bestN) { bestN = k; bestM = c }
        }
        if (bestN < notes.size * .7) return null
        var sum = 0.0
        for (o in notes) { val dv = pcDiff(o, bestM); if (abs(dv) < .5) sum += dv }
        return bestM + sum / bestN
    }

    /** a minus b in semitones, folded into -6..6 so octaves do not count. */
    private fun pcDiff(a: Double, b: Double): Double { var v = (a - b) % 12; if (v > 6) v -= 12; if (v < -6) v += 12; return v }
}
