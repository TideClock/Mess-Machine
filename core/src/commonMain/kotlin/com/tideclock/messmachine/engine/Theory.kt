package com.tideclock.messmachine.engine

import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic
import kotlin.math.abs
import kotlin.math.sqrt

/** Scales, keys mode and chords, ported one to one from the Mess Machine on the site. */
object Theory {
    /** The roll and the Padiano's keys: C1 to C6 (0 is C4). Build 84 widened it from C3 to C5 (note #18). */
    const val ROLL_HI = 24; const val ROLL_LO = -36
    @JvmField val NN = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    @JvmField val SCALES: MutableMap<String, IntArray> = LinkedHashMap()
    init {
        SCALES["Chromatic"] = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)
        SCALES["Major"] = intArrayOf(0, 2, 4, 5, 7, 9, 11)
        SCALES["Minor"] = intArrayOf(0, 2, 3, 5, 7, 8, 10)
        SCALES["Harmonic minor"] = intArrayOf(0, 2, 3, 5, 7, 8, 11)
        SCALES["Dorian"] = intArrayOf(0, 2, 3, 5, 7, 9, 10)
        SCALES["Phrygian"] = intArrayOf(0, 1, 3, 5, 7, 8, 10)
        SCALES["Mixolydian"] = intArrayOf(0, 2, 4, 5, 7, 9, 10)
        SCALES["Major pentatonic"] = intArrayOf(0, 2, 4, 7, 9)
        SCALES["Minor pentatonic"] = intArrayOf(0, 3, 5, 7, 10)
        SCALES["Blues"] = intArrayOf(0, 3, 5, 6, 7, 10)
    }

    @JvmField val QUAL: MutableMap<String, IntArray> = LinkedHashMap()
    @JvmField val QSUF: MutableMap<String, String> = LinkedHashMap()
    private fun q(k: String, suf: String, vararg iv: Int) { QUAL[k] = iv; QSUF[k] = suf }
    init {
        q("maj", "", 0, 4, 7); q("min", "m", 0, 3, 7); q("dim", "°", 0, 3, 6); q("aug", "+", 0, 4, 8)
        q("5", "5", 0, 7, 12); q("sus2", "sus2", 0, 2, 7); q("sus4", "sus4", 0, 5, 7); q("add9", "add9", 0, 4, 7, 14)
        q("7", "7", 0, 4, 7, 10); q("maj7", "maj7", 0, 4, 7, 11); q("m7", "m7", 0, 3, 7, 10); q("m7b5", "m7♭5", 0, 3, 6, 10)
        q("mMaj7", "m(maj7)", 0, 3, 7, 11); q("m9", "m9", 0, 3, 7, 10, 14); q("9", "9", 0, 4, 7, 10, 14)
        q("maj9", "maj9", 0, 4, 7, 11, 14); q("6", "6", 0, 4, 7, 9); q("m6", "m6", 0, 3, 7, 9); q("7sus4", "7sus4", 0, 5, 7, 10)
        q("dim7", "°7", 0, 3, 6, 9)
    }

    private val IVSUF: MutableMap<String, String> = LinkedHashMap()
    init {
        IVSUF["0,4,7"] = ""; IVSUF["0,3,7"] = "m"; IVSUF["0,3,6"] = "°"; IVSUF["0,4,8"] = "+"; IVSUF["0,7,12"] = "5"
        IVSUF["0,4,7,11"] = "maj7"; IVSUF["0,4,7,10"] = "7"; IVSUF["0,3,7,10"] = "m7"; IVSUF["0,3,6,10"] = "m7♭5"
        IVSUF["0,3,7,11"] = "m(maj7)"; IVSUF["0,3,6,9"] = "°7"; IVSUF["0,4,8,11"] = "+maj7"; IVSUF["0,2,7"] = "sus2"; IVSUF["0,5,7"] = "sus4"; IVSUF["0,12"] = " oct"; IVSUF["0,6,7"] = "sus♯4"
    }

    /**
     * A chord set (Nov 22, Tyler: twelve chords, all in key, smooth changes). Each chord is a root (semitones above
     * the mess's root, inside the set's home scale) and a quality that stays in that scale. The first seven are one
     * chord on each degree of the scale, in order, so a key in the scale plays the chord rooted on itself; the last
     * five are colour chords (sevenths, sus, add9) that the keys outside the scale play. Picking a set whose home is
     * not the mess's scale moves the scale to it, so every chord stays in key.
     */
    class ChordSet internal constructor(@JvmField val name: String, @JvmField val home: String, @JvmField val roots: IntArray, @JvmField val quals: Array<String>)

    @JvmField val CHORDSETS: MutableMap<String, ChordSet> = LinkedHashMap()
    /** spec: twelve "root quality" pairs, e.g. "0 maj, 2 min, ..." */
    private fun cs(n: String, home: String, spec: String) {
        val parts = spec.split(",").dropLastWhile { it.isEmpty() }
        if (parts.size != 12) throw IllegalArgumentException("$n needs 12 chords")
        val r = IntArray(12); val q = Array(12) { "" }  // Kotlin note: Java's new String[12]; every slot is set below
        for (k in 0 until 12) { val rq = parts[k].trim { it <= ' ' }.split(" ").dropLastWhile { it.isEmpty() }; r[k] = rq[0].toInt(); q[k] = rq[1] }
        CHORDSETS[n] = ChordSet(n, home, r, q)
    }
    init {
        //  degrees 1 to 7 of the home scale, then 5 colour chords
        cs("Plain Major", "Major", "0 maj, 2 min, 4 min, 5 maj, 7 maj, 9 min, 11 dim, 0 maj7, 2 m7, 5 maj7, 7 7, 9 m7")
        cs("Plain Minor", "Minor", "0 min, 2 dim, 3 maj, 5 min, 7 min, 8 maj, 10 maj, 0 m7, 3 maj7, 5 m7, 8 maj7, 10 7")
        cs("Sunday Pop", "Major", "0 maj, 2 min, 4 min, 5 maj, 7 maj, 9 min, 11 m7b5, 0 sus2, 5 add9, 7 sus4, 7 7, 9 m7")
        cs("Dreamy Sus", "Major", "0 sus2, 2 m7, 4 m7, 5 maj7, 7 sus4, 9 m7, 11 m7b5, 0 add9, 2 sus2, 5 sus2, 7 add9, 9 sus4")
        cs("Gospel Drip", "Major", "0 maj9, 2 m9, 4 m7, 5 maj7, 7 9, 9 m9, 11 m7b5, 0 6, 2 7sus4, 5 6, 7 7sus4, 0 add9")
        cs("Lo-fi Jazz", "Major", "0 maj7, 2 m7, 4 m7, 5 maj7, 7 7, 9 m7, 11 m7b5, 0 maj9, 2 m9, 5 maj9, 7 9, 9 m9")
        cs("Basement Minor", "Minor", "0 min, 2 dim, 3 maj, 5 min, 7 min, 8 maj, 10 maj, 0 sus2, 0 m7, 5 m7, 8 maj7, 10 sus2")
        cs("Neo Soul", "Dorian", "0 m9, 2 m7, 3 maj7, 5 9, 7 m7, 9 m7b5, 10 maj7, 0 m7, 3 maj9, 5 7, 7 m9, 10 maj9")
        cs("Cinematic", "Minor", "0 min, 2 dim, 3 maj, 5 min, 7 sus4, 8 maj, 10 maj, 0 sus2, 3 add9, 5 sus2, 8 maj7, 10 sus2")
        cs("Haunted", "Harmonic minor", "0 mMaj7, 2 m7b5, 3 aug, 5 m7, 7 7, 8 maj7, 11 dim7, 0 min, 2 dim, 5 min, 7 maj, 8 maj")
        cs("Trap Dread", "Phrygian", "0 min, 1 maj, 3 maj, 5 min, 7 dim, 8 maj, 10 min, 0 m7, 1 maj7, 5 m7, 8 maj7, 10 m7")
        cs("Power Stabs", "Minor", "0 5, 2 dim, 3 5, 5 5, 7 5, 8 5, 10 5, 0 sus4, 3 sus2, 5 sus2, 7 sus4, 10 sus2")
    }

    @JvmStatic fun pcOf(p: Int): Int { return ((p % 12) + 12) % 12 }

    @JvmStatic fun noteName(p: Int): String { return NN[pcOf(p)] + (4 + p.floorDiv(12)) }

    @JvmStatic fun scale(name: String): IntArray { val s = SCALES[name]; return if (s != null) s else SCALES["Chromatic"]!! }

    @JvmStatic fun contains(a: IntArray, v: Int): Boolean { for (x in a) if (x == v) return true; return false }

    @JvmStatic fun inScale(p: Int, scale: String, root: Int): Boolean { return contains(scale(scale), pcOf(p - root)) }

    @JvmStatic fun foldP(p: Int): Int { var p = p; while (p > ROLL_HI) p -= 12; while (p < ROLL_LO) p += 12; return p }

    @JvmStatic
    fun setFits(set: ChordSet, scale: String): Boolean {
        val sc = scale(scale)
        for (k in 0 until set.roots.size) for (iv in QUAL[set.quals[k]]!!) if (!contains(sc, pcOf(set.roots[k] + iv))) return false
        return true
    }

    @JvmStatic
    fun harmScale(scale: String): IntArray {
        if (scale == "Chromatic" || scale == "Major pentatonic") return SCALES["Major"]!!
        if (scale == "Minor pentatonic" || scale == "Blues") return SCALES["Minor"]!!
        return scale(scale)
    }

    @JvmStatic fun stripScale(scale: String): IntArray { return if (scale == "Chromatic") SCALES["Major"]!! else scale(scale) }

    /** The keys strip: every in-scale note of the roll's range. */
    @JvmStatic
    fun stripNotes(scale: String, root: Int): IntArray {
        val sc = stripScale(scale)
        val out: MutableList<Int> = ArrayList()
        for (p in ROLL_LO..ROLL_HI) if (contains(sc, pcOf(p - root))) out.add(p)
        val r = IntArray(out.size)
        for (k in 0 until r.size) r[k] = out[k]
        return r
    }

    /** Keys mode on the 4x4 grid: d counts from the top left, the lowest note sits bottom left. */
    @JvmStatic
    fun keyNote(d: Int, scale: String, root: Int): Int {
        val r = d / 4; val c = d % 4; val idx = (3 - r) * 4 + c
        val sc = scale(scale)
        return root + sc[idx % sc.size] + 12 * (idx / sc.size) - (if (root > 5) 12 else 0)
    }

    @JvmStatic fun chordGain(n: Int): Float { return if (n > 1) (.85 * minOf(1.0, 1.15 / sqrt(n.toDouble()))).toFloat() else .85f }

    /** The chord a key plays before folding into the roll. mode: off, triad, seventh, power, or set:Name */
    @JvmStatic
    fun chordRaw(p: Int, idx: Int, mode: String, scale: String, root: Int): IntArray {
        if (mode == "power") return intArrayOf(p, p + 7, p + 12)
        if (mode == "octave") return intArrayOf(p, p + 12)
        if (mode.startsWith("set:")) {
            // a fixed slot: idx = slot + set size * octave. Slot N is always the same chord, moved only by the
            // mess's root and whole octaves, so a set never drifts across the keys (note #18)
            val set = CHORDSETS[mode.substring(4)]
            if (set == null) return intArrayOf(p)
            val n = set.roots.size; val j = idx.mod(n); val o = idx.floorDiv(n)
            val base = rootBase(root) + set.roots[j] + 12 * o
            val iv = QUAL[set.quals[j]]!!; val out = IntArray(iv.size)
            for (k in 0 until iv.size) out[k] = base + iv[k]
            return out
        }
        if (mode == "triad" || mode == "seventh" || mode == "sus") {
            // harmonizing the scale: scale notes n, n+2, n+4 (n+6 for a seventh; n+3, n+4 for 1-4-5, the sus sound)
            val sc = harmScale(scale)
            val n = sc.size; val rel = pcOf(p - root); var deg = -1
            for (k in 0 until n) if (sc[k] == rel) deg = k
            val sev = mode == "seventh"; val sus = mode == "sus"
            if (deg < 0) {
                val iv = if (sev) intArrayOf(0, 4, 7, 10) else if (sus) intArrayOf(0, 5, 7) else intArrayOf(0, 4, 7); val out = IntArray(iv.size)
                for (k in 0 until iv.size) out[k] = p + iv[k]
                return out
            }
            val stack = if (sev) intArrayOf(0, 2, 4, 6) else if (sus) intArrayOf(0, 3, 4) else intArrayOf(0, 2, 4); val out = IntArray(stack.size)
            val base = p - rel
            for (k in 0 until stack.size) { val j = deg + stack[k]; out[k] = base + sc[j % n] + 12 * (j / n) }
            return out
        }
        return intArrayOf(p)
    }

    /**
     * The chord a key plays: root on the bottom, never folded note by note (build 84, note #18). A chord that runs
     * past the top or bottom of the range moves by whole octaves, so it keeps its shape.
     */
    @JvmStatic
    fun chordOf(p: Int, idx: Int, mode: String, scale: String, root: Int): IntArray {
        if (mode == "off") return intArrayOf(p)
        // Kotlin note: Java used a TreeSet; this is the same set, kept without duplicates and sorted
        val set: MutableList<Int> = ArrayList()
        for (x in chordRaw(p, idx, mode, scale, root)) if (!set.contains(x)) set.add(x)
        set.sort()
        var shift = 0
        while (set.last() + shift > ROLL_HI && set.first() + shift - 12 >= ROLL_LO) shift -= 12
        while (set.first() + shift < ROLL_LO && set.last() + shift + 12 <= ROLL_HI) shift += 12
        val r = IntArray(set.size)
        var k = 0
        for (x in set) r[k++] = x + shift
        return r
    }

    /** Where the mess's root sits for chord sets: C to F on C4 and up, F# to B just under it. */
    @JvmStatic fun rootBase(root: Int): Int { return if (root > 5) root - 12 else root }

    /** How many chords a set mode has (0 when it is not a set). */
    @JvmStatic
    fun setSize(mode: String): Int {
        if (!mode.startsWith("set:")) return 0
        val set = CHORDSETS[mode.substring(4)]
        return if (set == null) 0 else set.roots.size
    }

    /**
     * A piano key's slot in a chord set (Nov 22): a key in the scale plays its degree's chord (rooted on the key), a
     * key outside the scale plays one of the colour chords. The same key in every octave plays the same chord, an
     * octave apart. Integer.MIN_VALUE when the set has no such slot.
     */
    @JvmStatic
    fun setIndexOfKey(p: Int, mode: String, scale: String, root: Int): Int {
        val n = setSize(mode)
        if (n == 0) return Int.MIN_VALUE
        // keys in the scale take the first slots in scale order (each plays the chord on its own degree); keys
        // outside the scale take the colour slots after them, low to high, so every one of the 12 keys plays
        val rel = pcOf(p - root)
        val sc = stripScale(scale)
        val `in` = BooleanArray(12)
        for (iv in sc) `in`[pcOf(iv)] = true
        var slot = 0
        if (`in`[rel]) { for (k in 0 until rel) if (`in`[k]) slot++ }
        else { for (k in 0 until 12) if (`in`[k]) slot++; for (k in 0 until rel) if (!`in`[k]) slot++ }
        if (slot >= n) return Int.MIN_VALUE
        return slot + n * (p - rootBase(root)).floorDiv(12)
    }

    /**
     * Smooth chord changes (Nov 22, the default): the inversion of a chord that moves the fewest semitones from the
     * last chord played, kept within a fifth of where the chord was asked for, so the keys' octaves still count.
     * With no last chord, the chord as asked. Chords of one or two notes are left alone.
     */
    @JvmStatic
    fun smooth(chord: IntArray, prev: IntArray?): IntArray {
        val n = chord.size
        if (n < 3) return chord
        val c = chord.copyOf()
        c.sort()
        if (prev == null || prev.size == 0) return c
        val target = mean(c); var bestCost = Double.MAX_VALUE
        var best = c
        for (r in 0 until n) for (sh in -2..1) {
            val v = IntArray(n)
            for (k in 0 until n) v[k] = c[k] + (if (k < r) 12 else 0) + 12 * sh
            v.sort()
            if (v[0] < ROLL_LO || v[n - 1] > ROLL_HI) continue
            val m = mean(v)
            if (abs(m - target) > 7.5) continue
            val cost = move(v, prev) + .25 * abs(m - target)
            if (cost < bestCost - 1e-9) { bestCost = cost; best = v }
        }
        return best
    }

    private fun mean(v: IntArray): Double { var t = 0.0; for (x in v) t += x; return t / v.size }

    /** How far a hand moves from one chord to the next: each note to the nearest note of the other chord, both ways. */
    private fun move(a: IntArray, b: IntArray): Double {
        var d = 0.0
        for (x in a) { var m = Int.MAX_VALUE; for (y in b) m = minOf(m, abs(x - y)); d += m }
        for (y in b) { var m = Int.MAX_VALUE; for (x in a) m = minOf(m, abs(x - y)); d += m }
        return d
    }

    /** The name of slot j of a set, in the mess's root. */
    @JvmStatic
    fun slotName(mode: String, j: Int, root: Int): String {
        val set = CHORDSETS[if (mode.startsWith("set:")) mode.substring(4) else mode]
        if (set == null) return ""
        val i = j.mod(set.roots.size)
        return NN[pcOf(root + set.roots[i])] + QSUF[set.quals[i]]
    }

    @JvmStatic
    fun chordLabel(p: Int, idx: Int, mode: String, scale: String, root: Int): String {
        if (mode == "off") return noteName(p)
        if (mode.startsWith("set:")) {
            return if (CHORDSETS[mode.substring(4)] == null) noteName(p) else slotName(mode, idx, root)
        }
        val t = chordRaw(p, idx, mode, scale, root)
        val iv = StringBuilder()
        for (k in 0 until t.size) { if (k > 0) iv.append(','); iv.append(t[k] - t[0]) }
        val suf = IVSUF[iv.toString()]
        return NN[pcOf(t[0])] + (if (suf != null) suf else "")
    }

    @JvmStatic
    fun chordNotes(ps: IntArray): String {
        val b = StringBuilder()
        for (p in ps) { if (b.length > 0) b.append(' '); b.append(noteName(p)) }
        return b.toString()
    }

    @JvmStatic fun describe(a: IntArray?): String { return a.contentToString() }
}
