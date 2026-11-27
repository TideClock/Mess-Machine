package com.tideclock.messmachine.engine

import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Works out a sound's musical key, so a loop can say "A# minor" in the Sounds list and on its Sample sheet.
 * The app runs it on every sound a player makes (a resampled loop, a mic take, an import), and
 * tools/LoopKeys.java runs the same code over the built-in loops, so both give the same answers.
 *
 * How: the sound's notes are folded into the 12 pitch classes (tuning drift taken out first) and matched
 * against the usual major and minor key shapes. A key is only named when the match is strong and clearly
 * ahead of any key with different notes; drums, noise and unclear sounds get no key (an empty name).
 * A relative pair (A minor and C major) has the same notes, so either answer keeps a player in key.
 */
object KeyFinder {
    /** The app's own note names (sharps), as the root picker shows them. */
    @JvmField val NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val MAJ = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val MIN = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
    private const val N = 8192; private const val HOP = 2048
    /** How sure a key has to be before it is named: the match, the lead over other note sets, and how tonal the sound is. */
    const val MATCH = .72; const val AHEAD = .08; const val TONAL = .30
    /** Shorter sounds than this have too little in them to judge; longer ones are judged on their first part. */
    const val MIN_SECONDS = .4; const val MAX_SECONDS = 30.0

    /** What one sound holds: weight on each pitch class (sums to 1) and how much of it is clear notes (0 noise, 1 pure). */
    class Tone {
        @JvmField val chroma = DoubleArray(12)
        @JvmField var tonal = 0.0
        @JvmField var frames = 0
        /** How far the sound's notes sit from the nearest semitone, in semitones (-0.5..0.5); + is sharp. */
        @JvmField var drift = 0.0
    }

    /** One candidate key with how well it matches, and how far it leads the best key that has other notes. */
    class Key internal constructor(@JvmField val root: Int, @JvmField val minor: Boolean, @JvmField val match: Double) {
        @JvmField var ahead = 0.0
        fun name(): String { return NAMES[root] + (if (minor) " minor" else " major") }
        /** Keys with the same seven notes share this number (A minor and C major both give 0). */
        fun notes(): Int { return if (minor) (root + 3) % 12 else root }
    }

    /** The key's name when it is sure, else "". */
    @JvmStatic
    fun find(data: FloatArray?, rate: Int): String {
        if (data == null || rate <= 0 || data.size < rate * MIN_SECONDS) return ""
        val t = measure(data, rate)
        if (t.frames == 0) return ""
        val k = ranked(t.chroma)[0]
        return if (sure(k, t.tonal)) k.name() else ""
    }

    /**
     * The other name for the same seven notes: "A minor" for "C major" and back. "" for anything that is not a key name.
     * A loop shows both, because either one keeps a player in key.
     */
    @JvmStatic
    fun relative(name: String?): String {
        if (name == null) return ""
        val sp = name.indexOf(' ')
        if (sp < 0) return ""
        val root = NAMES.indexOf(name.substring(0, sp))
        val mode = name.substring(sp + 1)
        if (root < 0) return ""
        if (mode == "major") return NAMES[(root + 9) % 12] + " minor"
        if (mode == "minor") return NAMES[(root + 3) % 12] + " major"
        return ""
    }

    /** A key as a loop shows it: both names for its notes, like "C major / A minor". "" stays "". */
    @JvmStatic
    fun both(name: String?): String {
        val r = relative(name)
        return if (r.isEmpty()) (if (name == null) "" else name) else name + " / " + r
    }

    /** A key from its name ("D minor"), or null. */
    @JvmStatic
    fun parse(name: String?): Key? {
        if (name == null) return null
        val sp = name.indexOf(' ')
        if (sp < 0) return null
        val root = NAMES.indexOf(name.substring(0, sp))
        val mode = name.substring(sp + 1)
        if (root < 0 || (mode != "major" && mode != "minor")) return null
        return Key(root, mode == "minor", 1.0)
    }

    /** A key's usual weight on each pitch class (its shape turned to its root), summing to 1. */
    @JvmStatic
    fun shape(k: Key): DoubleArray {
        val sh = if (k.minor) MIN else MAJ
        var all = 0.0
        for (v in sh) all += v
        return DoubleArray(12) { sh[((it - k.root) % 12 + 12) % 12] / all }
    }

    @JvmStatic fun sure(k: Key, tonal: Double): Boolean { return k.match >= MATCH && k.ahead >= AHEAD && tonal >= TONAL }

    /** Every major and minor key, best match first; the first one's ahead is filled in. */
    @JvmStatic
    fun ranked(chroma: DoubleArray): MutableList<Key> {
        val all: MutableList<Key> = ArrayList()
        for (r in 0 until 12) {
            all.add(Key(r, false, corr(MAJ, r, chroma)))
            all.add(Key(r, true, corr(MIN, r, chroma)))
        }
        all.sortWith { a, b -> b.match.compareTo(a.match) }
        val best = all[0]
        for (k in all) if (k.notes() != best.notes()) { best.ahead = best.match - k.match; break }
        return all
    }

    /** How alike a key shape (turned to start on root) and the sound's pitch classes are: 1 the same, 0 unrelated. */
    private fun corr(shape: DoubleArray, root: Int, c: DoubleArray): Double {
        var ma = 0.0; var mb = 0.0
        for (i in 0 until 12) { ma += shape[i]; mb += c[i] }
        ma /= 12; mb /= 12
        var num = 0.0; var da = 0.0; var db = 0.0
        for (i in 0 until 12) {
            val a = shape[((i - root) % 12 + 12) % 12] - ma; val b = c[i] - mb
            num += a * b; da += a * a; db += b * b
        }
        return if (da <= 0 || db <= 0) 0.0 else num / sqrt(da * db)
    }

    @JvmStatic
    fun measure(data: FloatArray, rate: Int): Tone {
        val out = Tone()
        // half the rate is plenty for notes up to 2 kHz and halves the work
        val step = if (rate >= 32000) 2 else 1
        val sr = rate / step.toDouble()
        val len = minOf((data.size / step).toLong(), (sr * MAX_SECONDS).toLong()).toInt()
        if (len < N / 4) return out
        val x = FloatArray(maxOf(len, N))
        if (step == 2) for (i in 0 until len) x[i] = (data[2 * i] + data[2 * i + 1]) * .5f
        else data.copyInto(x, 0, 0, len)
        val total = x.size
        // the bins between 60 Hz and 2100 Hz, with one spare each side for peak picking
        val lo = maxOf(1, ceil(60.0 * N / sr).toInt()); val hi = minOf(N / 2 - 2, floor(2100.0 * N / sr).toInt())
        val nb = hi - lo + 1
        if (nb < 24) return out
        val midi = DoubleArray(nb)
        for (k in 0 until nb) midi[k] = 69 + 12 * ln((lo + k) * sr / N / 440.0) / ln(2.0)
        val win = DoubleArray(N)
        for (i in 0 until N) win[i] = .5 - .5 * cos(2 * PI * i / (N - 1))
        val frames = (total - N) / HOP + 1
        val mag = Array(frames) { FloatArray(nb) }
        val re = DoubleArray(N); val im = DoubleArray(N)
        for (f in 0 until frames) {
            val at = f * HOP
            for (i in 0 until N) { re[i] = x[at + i] * win[i]; im[i] = 0.0 }
            fft(re, im)
            for (k in 0 until nb) mag[f][k] = hypot(re[lo + k], im[lo + k]).toFloat()
        }
        // tuning: where do the strongest peaks sit between two semitones?
        val devs = DoubleArray(frames * 12)
        var nd = 0
        val used = BooleanArray(nb)
        for (f in 0 until frames) {
            used.fill(false)
            val m = mag[f]
            for (n in 0 until 12) {
                var top = -1
                for (k in 0 until nb) if (!used[k] && (top < 0 || m[k] > m[top])) top = k
                if (top < 0) break
                used[top] = true
                val left = if (top > 0) m[top - 1] else 0f; val right = if (top < nb - 1) m[top + 1] else 0f
                if (m[top] > left && m[top] > right && m[top] > 0) devs[nd++] = midi[top] - round(midi[top])
            }
        }
        var tune = 0.0
        if (nd > 0) {
            val d = devs.copyOf(nd)
            d.sort()
            tune = if (nd % 2 == 1) d[nd / 2] else (d[nd / 2 - 1] + d[nd / 2]) / 2
        }
        // only energy close to a real note counts toward its pitch class
        val pc = IntArray(nb)
        val w = DoubleArray(nb)
        for (k in 0 until nb) {
            val m2 = midi[k] - tune; val near = abs(m2 - round(m2))
            pc[k] = (round(m2).toInt() % 12 + 12) % 12
            w[k] = sqrt(maxOf(0.0, minOf(1.0, 1 - near / .35)))
        }
        val c = out.chroma
        var tonal = 0.0
        var counted = 0
        val p = FloatArray(nb)
        for (f in 0 until frames) {
            val m = mag[f]
            var sum = 0.0
            for (k in 0 until nb) { c[pc[k]] += m[k] * w[k]; p[k] = m[k] * m[k]; sum += p[k] }
            if (sum <= 1e-12) continue
            // how much of the frame's energy sits in its 24 strongest bins: notes are a few peaks, noise is spread
            val s = p.copyOf(nb)
            s.sort()
            var peaks = 0.0
            for (k in nb - 24 until nb) peaks += s[k]
            tonal += peaks / sum
            counted++
        }
        var all = 0.0
        for (v in c) all += v
        if (all <= 1e-12 || counted == 0) return out
        for (i in 0 until 12) c[i] /= all
        out.tonal = tonal / counted
        out.frames = counted
        out.drift = tune
        return out
    }

    /** A plain in-place FFT for a power of two length. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var size = 2
        while (size <= n) {
            val ang = -2 * PI / size; val wr = cos(ang); val wi = sin(ang)
            var at = 0
            while (at < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until size / 2) {
                    val a = at + k; val b = a + size / 2
                    val xr = re[b] * cr - im[b] * ci; val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr; cr = nr
                }
                at += size
            }
            size = size shl 1
        }
    }
}
