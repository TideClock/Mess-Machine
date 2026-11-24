package com.tideclock.messmachine.engine

import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Synths (SE-11 to SE-16): patches, their knobs, and the voices that play them. A synth sits on a pad like a
 * sound (key "synth:<id>"), so steps, Roll, keys, Hold and Repeat all play it; the engine's own envelope,
 * filters, effects and pan still apply on top.
 *
 * Each kind of synth is a list of knobs (Param) and a voice that reads them once per block, so turning a knob
 * changes notes already sounding. Nothing here allocates while it plays.
 *
 * Analog (SE-12): two band-limited oscillators that morph sine, triangle, saw and square, a sub, noise,
 * unison, a zero-delay ladder or state-variable filter with drive, two envelopes, an LFO. Three modes: Poly,
 * Mono (one voice, glides between held notes) and Acid (mono, a 3-pole squelchy filter, slides when notes
 * overlap, accent on fresh notes).
 */
class Synth private constructor() {

    /* ================= knobs ================= */

    class Param internal constructor(
        @JvmField val id: String, @JvmField val name: String, @JvmField val group: String, @JvmField val curve: Int,
        @JvmField val min: Double, @JvmField val max: Double, @JvmField val def: Double, @JvmField val unit: String,
        /** On the Simple view. */
        @JvmField val simple: Boolean, vararg choices: String
    ) {
        // a Kotlin vararg is an Array<out String>; it is the String[] Java passed, so the field keeps Java's type
        @Suppress("UNCHECKED_CAST")
        @JvmField val choices: Array<String> = choices as Array<String>

        /** Knob position 0..1 to a value. */
        fun fromKnob(k: Double): Double {
            var k = k
            k = maxOf(0.0, minOf(1.0, k))
            return when (curve) {
                LOG -> min * (max / min).pow(k)
                INT, CHOICE -> jround(min + (max - min) * k).toDouble()
                else -> min + (max - min) * k
            }
        }

        /** A value to a knob position 0..1. */
        fun toKnob(v: Double): Double {
            if (max == min) return 0.0
            if (curve == LOG) return ln(maxOf(min, minOf(max, v)) / min) / ln(max / min)
            return maxOf(0.0, minOf(1.0, (v - min) / (max - min)))
        }

        fun clamp(v: Double): Double {
            var v = v
            v = maxOf(minOf(min, max), minOf(maxOf(min, max), v))
            return if (curve == INT || curve == CHOICE) jround(v).toDouble() else v
        }

        /** The value as words, for the knob's label. */
        fun show(v: Double): String {
            if (curve == CHOICE) { val i = jround(v - min).toInt(); return if (i >= 0 && i < choices.size) choices[i] else "" }
            if (curve == INT) return (if (v > 0 && min < 0) "+" else "") + jround(v) + unit
            if ("s" == unit) return if (v < 1) jround(v * 1000).toString() + " ms" else fixed(v, 1) + " s" // format-ok: number only
            if ("Hz" == unit) return if (v < 1000) (if (v < 10) fixed(v, 1) + " Hz" else jround(v).toString() + " Hz") else fixed(v / 1000, 1) + " kHz" // format-ok: number only
            if ("%" == unit) return jround(v * 100).toString() + "%"
            if ("w" == unit) {
                // sine, triangle, saw and square, with how far it has morphed toward the next one (build 105: no more "200%")
                val n = arrayOf("sine", "tri", "saw", "square")
                val a = maxOf(0.0, minOf(3.0, floor(v))).toInt()
                val f = v - a
                if (a >= 3 || f < .05) return n[a]
                if (f > .95) return n[a + 1]
                return n[a] + " to " + n[a + 1] + " " + jround(f * 100) + "%"
            }
            if ("c" == unit) return (if (v > 0) "+" else "") + jround(v) + " cents"
            return (if (v > 0 && min < 0) "+" else "") + jround(v * 100) + "%"
        }
    }

    /** A kind of synth: its knobs, its presets and what it is called. */
    class Kind internal constructor(@JvmField val id: String, @JvmField val name: String, @JvmField val blurb: String, vararg params: Param) {
        // a Kotlin vararg is an Array<out Param>; it is the Param[] Java passed, so the field keeps Java's type
        @Suppress("UNCHECKED_CAST")
        @JvmField val params: Array<Param> = params as Array<Param>
        @JvmField val index: MutableMap<String, Int> = LinkedHashMap()
        @JvmField val presets: MutableList<Patch> = ArrayList()

        init {
            for (i in 0 until this.params.size) index[this.params[i].id] = i
        }

        fun at(id: String): Int { val i = index[id]; return i ?: -1 }

        internal fun preset(name: String, vararg kv: Any): Patch {
            val q = Patch(this, name)
            var i = 0
            while (i + 1 < kv.size) { q.set(kv[i] as String, (kv[i + 1] as Number).toDouble()); i += 2 }
            presets.add(q)
            return q
        }
    }

    // Kotlin has one companion per class: Java's statics (and static blocks) sit here in the order Java declared them
    companion object {
        const val LIN = 0
        const val LOG = 1
        const val INT = 2
        const val CHOICE = 3

        private fun p(id: String, name: String, group: String, curve: Int, min: Double, max: Double, def: Double, unit: String, simple: Boolean, vararg ch: String): Param {
            return Param(id, name, group, curve, min, max, def, unit, simple, *ch)
        }

        @JvmField val ANALOG = Kind("analog", "Analog", "Two oscillators, a warm filter, envelopes and an LFO. Poly for chords, Mono for leads, Acid for squelch.",
            p("mode", "Mode", "Voice", CHOICE, 0.0, 2.0, 0.0, "", true, "Poly", "Mono", "Acid"),
            p("w1", "Wave", "Oscillator 1", LIN, 0.0, 3.0, 2.0, "w", true),
            p("pw1", "Width", "Oscillator 1", LIN, 0.05, 0.95, 0.5, "", false),
            p("w2", "Wave", "Oscillator 2", LIN, 0.0, 3.0, 2.0, "w", false),
            p("semi2", "Semis", "Oscillator 2", INT, -24.0, 24.0, 0.0, "", false),
            p("fine2", "Fine", "Oscillator 2", LIN, -50.0, 50.0, 7.0, "c", false),
            p("mix2", "Level", "Oscillator 2", LIN, 0.0, 1.0, 0.5, "%", false),
            p("sync", "Sync", "Oscillator 2", CHOICE, 0.0, 1.0, 0.0, "", false, "Off", "On"),
            p("sub", "Sub", "Mixer", LIN, 0.0, 1.0, 0.0, "%", false),
            p("noise", "Noise", "Mixer", LIN, 0.0, 1.0, 0.0, "%", false),
            p("uni", "Unison", "Mixer", INT, 1.0, 5.0, 1.0, "", false),
            p("spread", "Spread", "Mixer", LIN, 0.0, 1.0, 0.3, "%", false),
            p("ftype", "Filter", "Filter", CHOICE, 0.0, 3.0, 0.0, "", false, "Low 24", "Low 12", "Band", "High"),
            p("cut", "Cutoff", "Filter", LOG, 30.0, 18000.0, 2400.0, "Hz", true),
            p("res", "Resonance", "Filter", LIN, 0.0, 1.0, 0.2, "%", true),
            p("fenv", "Env amount", "Filter", LIN, -1.0, 1.0, 0.35, "", true),
            p("track", "Key track", "Filter", LIN, 0.0, 1.0, 0.5, "%", false),
            p("drive", "Drive", "Filter", LIN, 0.0, 1.0, 0.1, "%", true),
            p("fa", "Attack", "Filter envelope", LOG, 0.001, 5.0, 0.005, "s", false),
            p("fd", "Decay", "Filter envelope", LOG, 0.005, 5.0, 0.4, "s", false),
            p("fs", "Sustain", "Filter envelope", LIN, 0.0, 1.0, 0.2, "%", false),
            p("fr", "Release", "Filter envelope", LOG, 0.005, 8.0, 0.3, "s", false),
            p("aa", "Attack", "Amp envelope", LOG, 0.001, 5.0, 0.004, "s", true),
            p("ad", "Decay", "Amp envelope", LOG, 0.005, 5.0, 0.5, "s", false),
            p("as", "Sustain", "Amp envelope", LIN, 0.0, 1.0, 0.8, "%", false),
            p("ar", "Release", "Amp envelope", LOG, 0.005, 8.0, 0.25, "s", true),
            p("lrate", "Rate", "LFO", LOG, 0.05, 20.0, 4.0, "Hz", false),
            p("lshape", "Shape", "LFO", CHOICE, 0.0, 4.0, 0.0, "", false, "Sine", "Triangle", "Saw", "Square", "Random"),
            p("lpitch", "To pitch", "LFO", LIN, 0.0, 1.0, 0.0, "%", false),
            p("lcut", "To filter", "LFO", LIN, 0.0, 1.0, 0.0, "%", false),
            p("lamp", "To volume", "LFO", LIN, 0.0, 1.0, 0.0, "%", false),
            p("lpw", "To width", "LFO", LIN, 0.0, 1.0, 0.0, "%", false),
            p("glide", "Glide", "Voice", LOG, 0.001, 1.5, 0.001, "s", false),
            p("accent", "Accent", "Voice", LIN, 0.0, 1.0, 0.5, "%", false),
            p("oct", "Octave", "Voice", INT, -3.0, 2.0, 0.0, "", false),
            p("level", "Level", "Voice", LIN, 0.0, 1.0, 0.7, "%", false)
        )

        init {
            val k = ANALOG
            k.preset("Warm pad", "w1", 2, "w2", 2, "fine2", 9, "mix2", 0.6, "uni", 3, "spread", 0.35, "cut", 1400, "res", 0.15, "fenv", 0.2, "fa", 0.8, "fd", 1.5, "fs", 0.5, "aa", 0.6, "ad", 1, "as", 0.9, "ar", 1.6, "lrate", 0.3, "lcut", 0.15)
            k.preset("Soft keys", "w1", 1.3, "w2", 2, "semi2", 12, "mix2", 0.25, "cut", 2200, "res", 0.1, "fenv", 0.3, "fd", 0.6, "fs", 0.1, "ad", 1.2, "as", 0.4, "ar", 0.4)
            k.preset("Brass", "w1", 2, "w2", 2, "fine2", 12, "mix2", 0.7, "cut", 900, "res", 0.25, "fenv", 0.55, "fa", 0.06, "fd", 0.5, "fs", 0.4, "aa", 0.03, "as", 0.85, "ar", 0.2, "drive", 0.3)
            k.preset("Chord stab", "w1", 3, "pw1", 0.35, "w2", 2, "fine2", 10, "cut", 1600, "res", 0.3, "fenv", 0.5, "fd", 0.18, "fs", 0, "ad", 0.3, "as", 0, "ar", 0.15)
            k.preset("Mono bass", "mode", 1, "w1", 2, "w2", 3, "semi2", -12, "mix2", 0.5, "sub", 0.5, "cut", 380, "res", 0.3, "fenv", 0.45, "fd", 0.25, "fs", 0.1, "as", 0.9, "ar", 0.08, "oct", -2, "glide", 0.05, "drive", 0.35)
            k.preset("Acid line", "mode", 2, "w1", 2, "mix2", 0, "cut", 420, "res", 0.78, "fenv", 0.7, "fd", 0.22, "fs", 0, "aa", 0.002, "ad", 0.4, "as", 0.7, "ar", 0.05, "oct", -1, "glide", 0.07, "accent", 0.7, "drive", 0.45)
            k.preset("Square acid", "mode", 2, "w1", 3, "mix2", 0, "cut", 350, "res", 0.72, "fenv", 0.65, "fd", 0.3, "fs", 0, "ar", 0.05, "oct", -1, "glide", 0.08, "accent", 0.6, "drive", 0.55)
            k.preset("Sync lead", "mode", 1, "w1", 2, "w2", 2, "semi2", 7, "sync", 1, "mix2", 0.8, "cut", 4000, "res", 0.2, "fenv", 0.3, "lrate", 5.5, "lpitch", 0.08, "glide", 0.08, "as", 0.9)
            k.preset("Wobble", "mode", 1, "w1", 2, "w2", 3, "semi2", -12, "sub", 0.4, "cut", 300, "res", 0.5, "fenv", 0.1, "lrate", 3, "lcut", 0.65, "oct", -2, "drive", 0.5)
            k.preset("Glass bell", "w1", 0, "w2", 1, "semi2", 19, "mix2", 0.45, "cut", 8000, "res", 0, "fenv", 0, "ad", 2.2, "as", 0, "ar", 2.2, "lrate", 6, "lamp", 0.08)
            k.preset("Noise sweep", "w1", 0, "mix2", 0, "noise", 1, "ftype", 2, "cut", 300, "res", 0.6, "fenv", 0.9, "fa", 2, "fd", 2, "fs", 0.3, "aa", 0.4, "ar", 1.5)
        }

        /* ---------- FM (SE-13) ---------- */

        /** Operator frequency ratios: whole numbers for tones, 1.41 and 3.5 for bells and metal. */
        @JvmField val RATIOS = doubleArrayOf(.5, 1.0, 1.41, 1.5, 2.0, 2.5, 3.0, 3.5, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 11.0, 14.0)
        private val RATIO_NAMES = arrayOf("0.5", "1", "1.41", "1.5", "2", "2.5", "3", "3.5", "4", "5", "6", "7", "8", "9", "11", "14")
        @JvmStatic internal fun ri(r: Double): Int { for (i in 0 until RATIOS.size) if (abs(RATIOS[i] - r) < .01) return i; return 1 }

        /** Who modulates whom: per algorithm, per operator, a bit for each operator feeding it; and which ones you hear. */
        @JvmField internal val MODS = arrayOf(
            intArrayOf(0b0010, 0b0100, 0b1000, 0), intArrayOf(0b0110, 0, 0b1000, 0), intArrayOf(0b1110, 0, 0, 0), intArrayOf(0b0010, 0, 0b1000, 0),
            intArrayOf(0b1000, 0b1000, 0b1000, 0), intArrayOf(0b0010, 0b0100, 0, 0), intArrayOf(0b0010, 0, 0, 0), intArrayOf(0, 0, 0, 0),
        )
        @JvmField internal val CARRIERS = intArrayOf(0b0001, 0b0001, 0b0001, 0b0101, 0b0111, 0b1001, 0b1101, 0b1111)

        private fun fmParams(): Array<Param> {
            val l: MutableList<Param> = ArrayList()
            l.add(p("mode", "Mode", "Voice", CHOICE, 0.0, 1.0, 0.0, "", true, "Poly", "Mono"))
            l.add(p("algo", "Algorithm", "Operators", CHOICE, 0.0, 7.0, 3.0, "", true, "Stack", "Branch", "Three into one", "Two stacks", "One drives three", "Stack + sine", "Pair + two sines", "Organ"))
            l.add(p("bright", "Brightness", "Operators", LIN, 0.0, 2.0, 1.0, "", true))
            l.add(p("fb", "Feedback", "Operators", LIN, 0.0, 1.0, 0.0, "%", true))
            l.add(p("det", "Detune", "Operators", LIN, 0.0, 20.0, 0.0, "c", false))
            val lv = doubleArrayOf(1.0, .5, 1.0, .3)
            for (i in 1..4) {
                val g = "Operator " + i
                l.add(p("r" + i, "Ratio", g, CHOICE, 0.0, (RATIOS.size - 1).toDouble(), 1.0, "", i == 2, *RATIO_NAMES))
                l.add(p("l" + i, "Level", g, LIN, 0.0, 1.0, lv[i - 1], "%", false))
                l.add(p("a" + i, "Attack", g, LOG, .001, 5.0, .002, "s", i == 1))
                l.add(p("d" + i, "Decay", g, LOG, .005, 8.0, .8, "s", i == 2))
                l.add(p("s" + i, "Sustain", g, LIN, 0.0, 1.0, .6, "%", false))
                l.add(p("rl" + i, "Release", g, LOG, .005, 8.0, .3, "s", i == 1))
            }
            l.add(p("lrate", "Rate", "LFO", LOG, 0.05, 20.0, 5.0, "Hz", false))
            l.add(p("lpitch", "Vibrato", "LFO", LIN, 0.0, 1.0, 0.0, "%", false))
            l.add(p("lamp", "Tremolo", "LFO", LIN, 0.0, 1.0, 0.0, "%", false))
            l.add(p("glide", "Glide", "Voice", LOG, 0.001, 1.5, 0.001, "s", false))
            l.add(p("oct", "Octave", "Voice", INT, -3.0, 2.0, 0.0, "", false))
            l.add(p("level", "Level", "Voice", LIN, 0.0, 1.0, 0.7, "%", false))
            return l.toTypedArray()
        }

        @JvmField val FM = Kind("fm", "FM", "Four sine operators that bend each other into bells, electric pianos, metal and growl. Brightness is how hard they bend.", *fmParams())

        init {
            val k = FM
            k.preset("E piano", "algo", 3, "r2", ri(1.0), "l2", 0.5, "d2", 1.5, "s2", 0.1, "r4", ri(14.0), "l4", 0.25, "d4", 0.3, "s4", 0, "d1", 2.5, "s1", 0.2, "rl1", 0.6, "l3", 0.5, "d3", 1.2, "s3", 0, "rl3", 0.4, "det", 3)
            k.preset("Bell", "algo", 3, "r2", ri(3.5), "l2", 0.45, "d2", 3, "s2", 0, "r4", ri(1.41), "l4", 0.4, "d4", 2, "s4", 0, "d1", 4, "s1", 0, "rl1", 2.5, "l3", 0.6, "d3", 3, "s3", 0, "rl3", 2)
            k.preset("FM bass", "mode", 1, "algo", 0, "r1", ri(0.5), "r2", ri(1.0), "l2", 0.55, "d2", 0.35, "s2", 0.15, "l3", 0, "l4", 0, "fb", 0.2, "d1", 1, "s1", 0.7, "rl1", 0.1, "oct", -1, "glide", 0.03)
            k.preset("Brass", "algo", 1, "r2", ri(1.0), "l2", 0.6, "a2", 0.08, "d2", 0.6, "s2", 0.5, "l3", 0.3, "a3", 0.05, "l4", 0, "a1", 0.04, "s1", 0.9, "rl1", 0.2, "fb", 0.1, "lpitch", 0.03)
            k.preset("Marimba", "algo", 3, "r2", ri(4.0), "l2", 0.4, "d2", 0.08, "s2", 0, "r4", ri(9.0), "l4", 0.2, "d4", 0.05, "s4", 0, "d1", 0.6, "s1", 0, "rl1", 0.3, "l3", 0.5, "d3", 0.4, "s3", 0)
            k.preset("Organ", "algo", 7, "r1", ri(0.5), "r2", ri(1.0), "r3", ri(2.0), "r4", ri(3.0), "l1", 0.8, "l2", 0.7, "l3", 0.5, "l4", 0.4, "s1", 1, "s2", 1, "s3", 1, "s4", 1, "rl1", 0.08, "rl2", 0.08, "rl3", 0.08, "rl4", 0.08, "lamp", 0.1, "lrate", 6)
            k.preset("Metal", "algo", 2, "r2", ri(1.41), "r3", ri(3.5), "r4", ri(7.0), "l2", 0.5, "l3", 0.4, "l4", 0.35, "d2", 2, "d3", 1.5, "d4", 1, "s2", 0, "s3", 0, "s4", 0, "d1", 3, "s1", 0, "rl1", 1.5)
            k.preset("Growl", "mode", 1, "algo", 0, "fb", 0.85, "r2", ri(1.0), "l2", 0.7, "r3", ri(2.0), "l3", 0.4, "l4", 0.3, "s1", 0.9, "s2", 0.8, "oct", -1)
            k.preset("Glass pad", "algo", 3, "a1", 0.8, "a3", 0.9, "r2", ri(2.0), "l2", 0.3, "r4", ri(5.0), "l4", 0.2, "s1", 0.8, "s2", 0.8, "s3", 0.8, "s4", 0.8, "d1", 2, "rl1", 1.5, "rl3", 1.5, "det", 6, "l3", 0.7)
        }

        /* ---------- Wavetable (SE-14) ---------- */

        @JvmField val WAVE = Kind("wave", "Wavetable", "One oscillator that sweeps through a table of shapes. Position picks the shape; the envelope and LFO can move it while the note plays.",
            p("mode", "Mode", "Voice", CHOICE, 0.0, 1.0, 0.0, "", true, "Poly", "Mono"),
            p("table", "Table", "Oscillator", CHOICE, 0.0, 5.0, 0.0, "", true, "Basic", "Pulse", "Sweep", "Vowels", "Hollow", "Grit"),
            p("pos", "Position", "Oscillator", LIN, 0.0, 1.0, 0.3, "%", true),
            p("penv", "Env to pos", "Oscillator", LIN, -1.0, 1.0, 0.0, "", true),
            p("plfo", "LFO to position", "Oscillator", LIN, 0.0, 1.0, 0.0, "%", false),
            p("warp", "Warp", "Oscillator", LIN, 0.0, 1.0, 0.0, "%", false),
            p("uni", "Unison", "Oscillator", INT, 1.0, 5.0, 1.0, "", false),
            p("spread", "Spread", "Oscillator", LIN, 0.0, 1.0, 0.3, "%", false),
            p("ftype", "Filter", "Filter", CHOICE, 0.0, 3.0, 0.0, "", false, "Low 24", "Low 12", "Band", "High"),
            p("cut", "Cutoff", "Filter", LOG, 30.0, 18000.0, 6000.0, "Hz", true),
            p("res", "Resonance", "Filter", LIN, 0.0, 1.0, 0.1, "%", false),
            p("fenv", "Env amount", "Filter", LIN, -1.0, 1.0, 0.2, "", false),
            p("track", "Key track", "Filter", LIN, 0.0, 1.0, 0.5, "%", false),
            p("drive", "Drive", "Filter", LIN, 0.0, 1.0, 0.0, "%", false),
            p("fa", "Attack", "Envelope 2 (filter, position)", LOG, 0.001, 5.0, 0.005, "s", false),
            p("fd", "Decay", "Envelope 2 (filter, position)", LOG, 0.005, 5.0, 0.6, "s", false),
            p("fs", "Sustain", "Envelope 2 (filter, position)", LIN, 0.0, 1.0, 0.3, "%", false),
            p("fr", "Release", "Envelope 2 (filter, position)", LOG, 0.005, 8.0, 0.4, "s", false),
            p("aa", "Attack", "Amp envelope", LOG, 0.001, 5.0, 0.005, "s", true),
            p("ad", "Decay", "Amp envelope", LOG, 0.005, 5.0, 0.6, "s", false),
            p("as", "Sustain", "Amp envelope", LIN, 0.0, 1.0, 0.8, "%", false),
            p("ar", "Release", "Amp envelope", LOG, 0.005, 8.0, 0.4, "s", true),
            p("lrate", "Rate", "LFO", LOG, 0.05, 20.0, 1.0, "Hz", false),
            p("lshape", "Shape", "LFO", CHOICE, 0.0, 4.0, 0.0, "", false, "Sine", "Triangle", "Saw", "Square", "Random"),
            p("lpitch", "To pitch", "LFO", LIN, 0.0, 1.0, 0.0, "%", false),
            p("lcut", "To filter", "LFO", LIN, 0.0, 1.0, 0.0, "%", false),
            p("lamp", "To volume", "LFO", LIN, 0.0, 1.0, 0.0, "%", false),
            p("glide", "Glide", "Voice", LOG, 0.001, 1.5, 0.001, "s", false),
            p("oct", "Octave", "Voice", INT, -3.0, 2.0, 0.0, "", false),
            p("level", "Level", "Voice", LIN, 0.0, 1.0, 0.7, "%", false)
        )

        init {
            val k = WAVE
            k.preset("Vowel pad", "table", 3, "pos", 0.3, "plfo", 0.5, "lrate", 0.2, "aa", 0.5, "ar", 1.5, "uni", 3, "spread", 0.3, "cut", 5000)
            k.preset("Sweep lead", "mode", 1, "table", 2, "pos", 0.1, "penv", 0.8, "fd", 0.6, "fs", 0.2, "glide", 0.06, "cut", 9000, "lpitch", 0.04, "lrate", 5)
            k.preset("PWM strings", "table", 1, "pos", 0.3, "plfo", 0.6, "lrate", 0.5, "uni", 3, "aa", 0.4, "ar", 1.2, "cut", 3500, "res", 0.1)
            k.preset("Hollow keys", "table", 4, "pos", 0.6, "ad", 0.8, "as", 0.3, "ar", 0.5, "cut", 7000, "fenv", 0.3, "fd", 0.4, "fs", 0)
            k.preset("Grit bass", "mode", 1, "table", 5, "pos", 0.4, "oct", -2, "cut", 600, "fenv", 0.4, "fd", 0.3, "fs", 0.1, "drive", 0.4, "res", 0.25)
            k.preset("Morph pluck", "table", 0, "pos", 1, "penv", -0.9, "fd", 0.25, "fs", 0, "ad", 0.5, "as", 0, "ar", 0.3, "cut", 8000)
            k.preset("Vowel talk", "mode", 1, "table", 3, "pos", 0, "plfo", 1, "lrate", 2.5, "lshape", 1, "oct", -1, "cut", 4000)
        }

        /* ---------- Granular (SE-15) ---------- */

        @JvmField val GRAIN = Kind("grain", "Granular", "Plays a sound from your mess as a cloud of tiny grains, at any pitch, frozen or crawling through it. Position picks where; Spray scatters it.",
            p("mode", "Mode", "Voice", CHOICE, 0.0, 1.0, 0.0, "", true, "Poly", "Mono"),
            p("pos", "Position", "Grains", LIN, 0.0, 1.0, 0.3, "%", true),
            p("spray", "Spray", "Grains", LIN, 0.0, 1.0, 0.1, "%", true),
            p("size", "Size", "Grains", LOG, 0.01, 0.5, 0.08, "s", true),
            p("dens", "Density", "Grains", LOG, 2.0, 120.0, 30.0, "Hz", true),
            p("scan", "Scan", "Grains", LIN, -1.0, 1.0, 0.0, "", false),
            p("pspray", "Pitch spray", "Grains", LIN, 0.0, 12.0, 0.0, "", false),
            p("rev", "Reverse", "Grains", LIN, 0.0, 1.0, 0.0, "%", false),
            p("gshape", "Shape", "Grains", CHOICE, 0.0, 1.0, 0.0, "", false, "Smooth", "Sharp"),
            p("ftype", "Filter", "Filter", CHOICE, 0.0, 3.0, 1.0, "", false, "Low 24", "Low 12", "Band", "High"),
            p("cut", "Cutoff", "Filter", LOG, 30.0, 18000.0, 12000.0, "Hz", false),
            p("res", "Resonance", "Filter", LIN, 0.0, 1.0, 0.0, "%", false),
            p("aa", "Attack", "Amp envelope", LOG, 0.001, 5.0, 0.05, "s", true),
            p("ad", "Decay", "Amp envelope", LOG, 0.005, 5.0, 0.5, "s", false),
            p("as", "Sustain", "Amp envelope", LIN, 0.0, 1.0, 1.0, "%", false),
            p("ar", "Release", "Amp envelope", LOG, 0.005, 8.0, 0.8, "s", true),
            p("lrate", "Rate", "LFO", LOG, 0.05, 20.0, 0.3, "Hz", false),
            p("lpos", "To position", "LFO", LIN, 0.0, 1.0, 0.0, "%", false),
            p("lpitch", "To pitch", "LFO", LIN, 0.0, 1.0, 0.0, "%", false),
            p("glide", "Glide", "Voice", LOG, 0.001, 1.5, 0.001, "s", false),
            p("oct", "Octave", "Voice", INT, -3.0, 2.0, 0.0, "", false),
            p("level", "Level", "Voice", LIN, 0.0, 1.0, 0.8, "%", false)
        )

        init {
            val k = GRAIN
            k.preset("Cloud", "pos", 0.3, "spray", 0.15, "size", 0.12, "dens", 40, "aa", 0.4, "ar", 1.5)
            k.preset("Freeze", "pos", 0.5, "spray", 0.02, "size", 0.2, "dens", 25, "aa", 0.2, "ar", 1.2)
            k.preset("Crawl", "pos", 0, "scan", 0.25, "spray", 0.05, "size", 0.1, "dens", 30, "aa", 0.1, "ar", 1)
            k.preset("Shimmer", "pos", 0.4, "spray", 0.2, "size", 0.06, "dens", 70, "pspray", 12, "aa", 0.5, "ar", 2, "cut", 9000)
            k.preset("Stutter", "pos", 0.2, "spray", 0, "size", 0.03, "dens", 12, "gshape", 1, "aa", 0.002, "ar", 0.2)
            k.preset("Backwards wash", "pos", 0.6, "spray", 0.3, "size", 0.25, "dens", 20, "rev", 1, "aa", 0.8, "ar", 2.5)
            k.preset("Glitch", "mode", 1, "pos", 0.5, "spray", 0.6, "size", 0.02, "dens", 90, "pspray", 7, "rev", 0.5, "gshape", 1, "aa", 0.002, "ar", 0.1)
        }

        /* ---------- Drums (SE-16) ---------- */

        @JvmField val DRUM = Kind("drum", "Drums", "Drum sounds made from scratch: pick a model, then Tune, Decay, Tone and Snap. Played as keys they go up and down in pitch.",
            p("model", "Model", "Drum", CHOICE, 0.0, 7.0, 0.0, "", true, "Kick", "Snare", "Hat", "Open hat", "Clap", "Tom", "Cowbell", "Zap"),
            p("tune", "Tune", "Drum", INT, -24.0, 24.0, 0.0, "", true),
            p("decay", "Decay", "Drum", LOG, 0.02, 3.0, 0.5, "s", true),
            p("tone", "Tone", "Drum", LIN, 0.0, 1.0, 0.5, "%", true),
            p("snap", "Snap", "Drum", LIN, 0.0, 1.0, 0.4, "%", true),
            p("sweep", "Sweep", "Drum", LIN, 0.0, 1.0, 0.5, "%", false),
            p("stime", "Sweep time", "Drum", LOG, 0.005, 0.5, 0.04, "s", false),
            p("noise", "Noise", "Drum", LIN, 0.0, 1.0, 0.5, "%", false),
            p("drive", "Drive", "Drum", LIN, 0.0, 1.0, 0.2, "%", false),
            p("level", "Level", "Drum", LIN, 0.0, 1.0, 0.8, "%", false)
        )

        init {
            val k = DRUM
            k.preset("Deep kick", "model", 0, "decay", 0.6, "sweep", 0.55, "stime", 0.045, "snap", 0.35, "drive", 0.25)
            k.preset("Punch kick", "model", 0, "tune", 3, "decay", 0.3, "sweep", 0.7, "stime", 0.025, "snap", 0.7, "drive", 0.5)
            k.preset("Boom 808", "model", 0, "tune", -3, "decay", 1.8, "sweep", 0.35, "stime", 0.06, "snap", 0.15, "drive", 0.35)
            k.preset("Snare", "model", 1, "decay", 0.25, "tone", 0.55, "snap", 0.6, "noise", 0.6)
            k.preset("Rim snare", "model", 1, "tune", 7, "decay", 0.12, "tone", 0.8, "snap", 0.9, "noise", 0.3)
            k.preset("Closed hat", "model", 2, "decay", 0.06, "tone", 0.7)
            k.preset("Open hat", "model", 3, "decay", 0.5, "tone", 0.65)
            k.preset("Clap", "model", 4, "decay", 0.3, "tone", 0.5)
            k.preset("Tom", "model", 5, "decay", 0.45, "sweep", 0.3, "stime", 0.08, "noise", 0.2)
            k.preset("Cowbell", "model", 6, "decay", 0.3, "tone", 0.6)
            k.preset("Zap", "model", 7, "decay", 0.25, "sweep", 0.9, "stime", 0.03)
        }

        /* ---------- Plucks (SE-16) ---------- */

        @JvmField val PLUCK = Kind("pluck", "Pluck", "A plucked string: a burst of noise ringing through a tuned delay. Bright is the pick, Damp the string, Body the wood around it.",
            p("mode", "Mode", "Voice", CHOICE, 0.0, 1.0, 0.0, "", true, "Poly", "Mono"),
            p("decay", "Decay", "String", LOG, 0.1, 12.0, 2.5, "s", true),
            p("bright", "Bright", "String", LIN, 0.0, 1.0, 0.7, "%", true),
            p("damp", "Damp", "String", LIN, 0.0, 1.0, 0.3, "%", true),
            p("pick", "Pick position", "String", LIN, 0.0, 1.0, 0.2, "%", false),
            p("body", "Body", "String", LIN, 0.0, 1.0, 0.3, "%", true),
            p("mute", "Let go", "String", LOG, 0.01, 3.0, 0.6, "s", false),
            p("drive", "Drive", "String", LIN, 0.0, 1.0, 0.0, "%", false),
            p("lrate", "Rate", "LFO", LOG, 0.05, 20.0, 5.0, "Hz", false),
            p("lpitch", "Vibrato", "LFO", LIN, 0.0, 1.0, 0.0, "%", false),
            p("glide", "Glide", "Voice", LOG, 0.001, 1.5, 0.001, "s", false),
            p("oct", "Octave", "Voice", INT, -3.0, 2.0, 0.0, "", false),
            p("level", "Level", "Voice", LIN, 0.0, 1.0, 0.8, "%", false)
        )

        init {
            val k = PLUCK
            k.preset("Nylon", "decay", 2.5, "bright", 0.55, "damp", 0.45, "pick", 0.25, "body", 0.45)
            k.preset("Steel", "decay", 4, "bright", 0.85, "damp", 0.15, "pick", 0.12, "body", 0.3)
            k.preset("Harp", "decay", 5, "bright", 0.5, "damp", 0.35, "pick", 0.5, "body", 0.2, "mute", 2)
            k.preset("Koto", "decay", 2, "bright", 0.9, "damp", 0.2, "pick", 0.08, "body", 0.1, "lpitch", 0.02)
            k.preset("Pluck bass", "mode", 1, "decay", 1.2, "bright", 0.45, "damp", 0.6, "body", 0.5, "oct", -2, "mute", 0.15)
            k.preset("Muted", "decay", 0.35, "bright", 0.6, "damp", 0.7, "pick", 0.3, "body", 0.3, "mute", 0.05)
            k.preset("Bell string", "decay", 8, "bright", 1, "damp", 0, "pick", 0.4, "body", 0)
        }

        /** Every kind, in the order the Synth tab shows them. */
        @JvmField val KINDS = arrayOf(ANALOG, FM, WAVE, GRAIN, DRUM, PLUCK)

        /** How long a note with no length rings: its attack and decay (FM: the slowest carrier's). */
        @JvmStatic fun ringSec(p: Patch): Double {
            if (p.kind === FM) {
                var m = 0.0; val cm = CARRIERS[p.get("algo").toInt()]
                for (i in 0 until 4) if (((cm shr i) and 1) != 0) m = maxOf(m, p.v[p.kind.at(FM_A[i])] + p.v[p.kind.at(FM_D[i])])
                return minOf(4.0, m)
            }
            if (p.kind === DRUM) return minOf(6.0, p.get("decay") * (if (p.get("model").toInt() == 3) 2.0 else 1.2))
            if (p.kind === PLUCK) return minOf(12.0, p.get("decay"))
            return minOf(2.0, p.get("aa") + p.get("ad"))
        }

        @JvmField internal val FM_R = arrayOf("r1", "r2", "r3", "r4")
        @JvmField internal val FM_L = arrayOf("l1", "l2", "l3", "l4")
        @JvmField internal val FM_A = arrayOf("a1", "a2", "a3", "a4")
        @JvmField internal val FM_D = arrayOf("d1", "d2", "d3", "d4")
        @JvmField internal val FM_S = arrayOf("s1", "s2", "s3", "s4")
        @JvmField internal val FM_RL = arrayOf("rl1", "rl2", "rl3", "rl4")

        /** Make the wavetables now (off the audio thread), so the first wavetable note does not wait for them. */
        @JvmStatic fun warm() { if (WT.T[0][0][0] == null) throw IllegalStateException() }

        @JvmStatic fun kind(id: String): Kind { for (k in KINDS) if (k.id == id) return k; return ANALOG }

        /* ================= shared pieces ================= */

        private val SINE = FloatArray(2049)
        init { for (i in 0..2048) SINE[i] = sin(2 * PI * i / 2048).toFloat() }

        /** sin(2 pi ph) for ph in [0, 1), from a table. */
        @JvmStatic internal fun sin1(ph: Double): Float {
            val x = (ph - floor(ph)) * 2048
            val i = x.toInt()
            val f = (x - i).toFloat()
            return SINE[i] + (SINE[i + 1] - SINE[i]) * f
        }

        /** The PolyBLEP step correction for a phase t in [0, 1) with increment dt. */
        @JvmStatic internal fun blep(t: Double, dt: Double): Double {
            var t = t
            if (t < dt) { t /= dt; return t + t - t * t - 1 }
            if (t > 1 - dt) { t = (t - 1) / dt; return t * t + t + t + 1 }
            return 0.0
        }

        /** A soft clip that costs a few multiplies instead of tanh. */
        @JvmStatic internal fun sat(x: Double): Double {
            if (x > 3) return 1.0
            if (x < -3) return -1.0
            val x2 = x * x
            return x * (27 + x2) / (27 + 9 * x2)
        }

        /** One oscillator sample: wave 0 sine, 1 triangle, 2 saw, 3 square (pw), and the shapes between. */
        @JvmStatic internal fun osc(ph: Double, dt: Double, wave: Double, pw: Double): Double {
            val a = floor(wave).toInt()
            if (a >= 3) return shape(3, ph, dt, pw)
            val f = wave - a
            val x = shape(a, ph, dt, pw)
            return if (f < 1e-4) x else x + (shape(a + 1, ph, dt, pw) - x) * f
        }

        private fun shape(s: Int, ph: Double, dt: Double, pw: Double): Double {
            when (s) {
                0 -> return sin1(ph).toDouble()
                1 -> return if (ph < .5) 4 * ph - 1 else 3 - 4 * ph
                2 -> return 2 * ph - 1 - blep(ph, dt)
                else -> {
                    var v = if (ph < pw) 1.0 else -1.0
                    v += blep(ph, dt)
                    var t2 = ph - pw; if (t2 < 0) t2 += 1
                    v -= blep(t2, dt)
                    return v
                }
            }
        }
    }

    /**
     * The wavetables, made once on first use (about 2 MB): 6 tables of 16 shapes, each at 6 levels of detail so
     * high notes never fold harmonics back down (the right level is picked by pitch).
     */
    internal object WT {
        const val N = 1024
        const val FR = 16
        const val MIPS = 6
        @JvmField val HARM = intArrayOf(256, 128, 64, 32, 16, 8)
        @JvmField val T: Array<Array<Array<FloatArray?>>> = Array(6) { Array(MIPS) { arrayOfNulls<FloatArray>(FR) } }
        init {
            val a = DoubleArray(257)
            val re = DoubleArray(N)
            val im = DoubleArray(N)
            val rnd = JavaRandom(505)
            val grit = Array(FR) { DoubleArray(257) }
            for (f in 0 until FR) for (h in 1..256) grit[f][h] = rnd.nextDouble()
            for (t in 0 until 6) for (f in 0 until FR) {
                val x = f / (FR - 1).toDouble()
                a.fill(0.0)
                for (h in 1..256) a[h] = spectrum(t, x, h, grit[f][h])
                var norm = 0.0
                for (m in 0 until MIPS) {
                    re.fill(0.0); im.fill(0.0)
                    for (h in 1..HARM[m]) {
                        // fade the top quarter of each level's harmonics so levels blend instead of stepping
                        val fade = if (h > HARM[m] * .75) (HARM[m] - h) / (HARM[m] * .25) else 1.0
                        im[h] = -N * a[h] * fade / 2; im[N - h] = N * a[h] * fade / 2
                    }
                    Bake.fft(re, im, true)
                    if (m == 0) { for (i in 0 until N) norm = maxOf(norm, abs(re[i])); norm = maxOf(norm, 1e-9) }
                    val w = FloatArray(N + 1)
                    for (i in 0 until N) w[i] = (re[i] / norm).toFloat()
                    w[N] = w[0]
                    T[t][m][f] = w
                }
            }
        }

        /** Harmonic h's level in table t at position x (0..1). */
        private fun spectrum(t: Int, x: Double, h: Int, r: Double): Double {
            when (t) {
                0 -> { // sine, triangle, saw, square
                    val s = x * 3; val i = minOf(2, s.toInt()); val fr = s - i
                    return basic(i, h) * (1 - fr) + basic(i + 1, h) * fr
                }
                1 -> { val w = .5 - x * .47; return abs(2 / (h * PI) * sin(PI * h * w)) }
                2 -> { val n = 2.0.pow(x * 6); return if (h <= n) 1.0 / h else (if (h < n + 1) (n + 1 - h) / h else 0.0) }
                3 -> { // vowels a, e, i, o, u by formants at harmonic numbers (for a voice near 110 Hz)
                    val V = arrayOf(doubleArrayOf(6.6, 9.9), doubleArrayOf(4.8, 16.7), doubleArrayOf(2.5, 20.8), doubleArrayOf(5.2, 7.6), doubleArrayOf(2.7, 7.9))
                    val s = x * 4; val i = minOf(3, s.toInt()); val fr = s - i
                    val f1 = V[i][0] + (V[i + 1][0] - V[i][0]) * fr; val f2 = V[i][1] + (V[i + 1][1] - V[i][1]) * fr
                    val g = exp(-((h - f1) / 1.5).pow(2.0)) + .7 * exp(-((h - f2) / 2.5).pow(2.0)) + .04
                    return g / h.toDouble().pow(.7)
                }
                4 -> return if (h == 1) 1.0 else (if (h % 2 == 1) 1 - x else x) / h
                else -> return if (h == 1) 1.0 else r.pow(3 - 2 * x) / h.toDouble().pow(1.4 - x * .7)
            }
        }

        private fun basic(s: Int, h: Int): Double {
            when (s) {
                0 -> return if (h == 1) 1.0 else 0.0
                1 -> return if (h % 2 == 1) 8 / (PI * PI * h * h) else 0.0
                2 -> return 2 / (PI * h)
                else -> return if (h % 2 == 1) 4 / (PI * h) else 0.0
            }
        }
    }

    /* ================= patches ================= */

    /** A synth's settings: its kind, its name and one value per knob. */
    class Patch(@JvmField val kind: Kind, @JvmField var name: String) {
        @JvmField val v: DoubleArray
        /** Granular (SE-15): the sound its grains come from, a library key; empty for other kinds. */
        @JvmField var src: String = ""

        init {
            v = DoubleArray(kind.params.size)
            for (i in 0 until v.size) v[i] = kind.params[i].def
        }

        fun get(id: String): Double { val i = kind.at(id); return if (i < 0) 0.0 else v[i] }
        fun set(id: String, x: Double) { val i = kind.at(id); if (i >= 0) v[i] = kind.params[i].clamp(x) }
        fun copy(): Patch { val q = Patch(kind, name); v.copyInto(q.v, 0, 0, v.size); q.src = src; return q }

        fun toMap(): MutableMap<String, Any?> {
            val m: MutableMap<String, Any?> = LinkedHashMap()
            m["kind"] = kind.id
            m["name"] = name
            val vals: MutableMap<String, Any?> = LinkedHashMap()
            for (i in 0 until v.size) vals[kind.params[i].id] = v[i]
            m["v"] = vals
            if (!src.isEmpty()) m["src"] = src
            return m
        }

        companion object {
            @JvmStatic fun fromMap(m: Map<String, Any?>): Patch {
                val k = Synth.kind(Json.str(m["kind"], "analog"))
                val q = Patch(k, Json.str(m["name"], k.name))
                val vals = Json.map(m["v"])
                for ((key, value) in vals) if (value is Number) q.set(key, value.toDouble())
                q.src = Json.str(m["src"], "")
                return q
            }
        }
    }

    /** ADSR with exponential decay and release; levels 0..1. */
    internal class Env {
        @JvmField var stage = 0 // 0 idle, 1 attack, 2 decay/sustain, 3 release
        @JvmField var v = 0.0
        @JvmField var aInc = 0.0
        @JvmField var dK = 0.0
        @JvmField var rK = 0.0
        @JvmField var s = 0.0
        fun set(a: Double, d: Double, sus: Double, r: Double, rate: Int) {
            aInc = 1.0 / maxOf(1.0, a * rate)
            dK = exp(-4.6 / maxOf(1.0, d * rate))   // about 99% of the way in d seconds
            rK = exp(-4.6 / maxOf(1.0, r * rate))
            s = sus
        }
        fun on(fromZero: Boolean) { if (fromZero) v = 0.0; stage = 1 }
        fun off() { if (stage != 0) stage = 3 }
        fun next(): Double {
            when (stage) {
                1 -> { v += aInc; if (v >= 1) { v = 1.0; stage = 2 } }
                2 -> v = s + (v - s) * dK
                3 -> { v *= rK; if (v < 1e-5) { v = 0.0; stage = 0 } }
                else -> v = 0.0
            }
            return v
        }
        /** Advance n samples at once (control rate), for the filter envelope. */
        fun skip(n: Int): Double {
            when (stage) {
                1 -> { v += aInc * n; if (v >= 1) { v = 1.0; stage = 2 } }
                2 -> v = s + (v - s) * dK.pow(n.toDouble())
                3 -> { v *= rK.pow(n.toDouble()); if (v < 1e-5) { v = 0.0; stage = 0 } }
                else -> v = 0.0
            }
            return v
        }
    }

    /* ================= a voice ================= */

    /** Everything one playing note needs. One per engine voice, made once, reused. */
    class Voice {
        @JvmField var patch: Patch? = null
        @JvmField internal var rate = 0
        @JvmField var on = false
        @JvmField var gate = false
        @JvmField internal var hz = 0.0
        @JvmField internal var hzTarget = 0.0
        @JvmField internal var glideK = 1.0
        @JvmField internal var vel = 0f
        @JvmField internal var fresh = false
        // oscillators: unison copies of osc 1 and osc 2, and the sub
        @JvmField internal val ph1 = DoubleArray(5)
        @JvmField internal val ph2 = DoubleArray(5)
        @JvmField internal var phSub = 0.0
        @JvmField internal var rng = 0x9E3779B9.toInt()
        // filter (ladder stages or state-variable states)
        @JvmField internal var s1 = 0.0
        @JvmField internal var s2 = 0.0
        @JvmField internal var s3 = 0.0
        @JvmField internal var s4 = 0.0
        @JvmField internal var ic1 = 0.0
        @JvmField internal var ic2 = 0.0
        @JvmField internal var g = 0.0
        @JvmField internal var k = 0.0
        @JvmField internal var G = 0.0
        @JvmField internal val amp = Env()
        @JvmField internal val fe = Env()
        @JvmField internal var lph = 0.0
        @JvmField internal var lsh = 0.0
        @JvmField internal var ctl = 0
        // knob values read once per block
        @JvmField internal var mode = 0
        @JvmField internal var uni = 0
        @JvmField internal var ftype = 0
        @JvmField internal var lshape = 0
        @JvmField internal var w1 = 0.0
        @JvmField internal var pw1 = 0.0
        @JvmField internal var w2 = 0.0
        @JvmField internal var semi2r = 0.0
        @JvmField internal var mix2 = 0.0
        @JvmField internal var sub = 0.0
        @JvmField internal var noise = 0.0
        @JvmField internal var spread = 0.0
        @JvmField internal var cut = 0.0
        @JvmField internal var res = 0.0
        @JvmField internal var fenv = 0.0
        @JvmField internal var track = 0.0
        @JvmField internal var drive = 0.0
        @JvmField internal var lrate = 0.0
        @JvmField internal var lpitch = 0.0
        @JvmField internal var lcut = 0.0
        @JvmField internal var lamp = 0.0
        @JvmField internal var lpw = 0.0
        @JvmField internal var glide = 0.0
        @JvmField internal var accent = 0.0
        @JvmField internal var oct = 0.0
        @JvmField internal var level = 0.0
        @JvmField internal var sync = false
        @JvmField internal var fNow = 1000.0
        @JvmField internal var lfoV = 0.0
        @JvmField internal var lfoPitchMul = 1.0
        @JvmField internal val det = DoubleArray(5)
        /** 0 analog, 1 FM, 2 wavetable. */
        @JvmField internal var kindNo = 0
        // FM: four operators
        @JvmField internal val fph = DoubleArray(4)
        @JvmField internal val fr = DoubleArray(4)
        @JvmField internal val fl = DoubleArray(4)
        @JvmField internal val fdet = DoubleArray(4)
        @JvmField internal val mo = DoubleArray(4)
        @JvmField internal val co = DoubleArray(4)
        @JvmField internal val fe4 = arrayOf(Env(), Env(), Env(), Env())
        @JvmField internal var algo = 0
        @JvmField internal var fbAmt = 0.0
        @JvmField internal var bright = 0.0
        @JvmField internal var fb1 = 0.0
        @JvmField internal var fb2 = 0.0
        // wavetable
        @JvmField internal var table = 0
        @JvmField internal var pos = 0.0
        @JvmField internal var penv = 0.0
        @JvmField internal var plfo = 0.0
        @JvmField internal var warp = 0.0
        @JvmField internal var wposNow = 0.0
        // granular: up to 24 grains at once, each a read head with its own speed, age and length
        @JvmField internal var src: FloatArray? = null
        @JvmField internal var srcRate = 44100
        @JvmField internal val gPos = DoubleArray(NG)
        @JvmField internal val gInc = DoubleArray(NG)
        @JvmField internal val gAge = IntArray(NG)
        @JvmField internal val gLen = IntArray(NG)
        @JvmField internal val gOn = BooleanArray(NG)
        @JvmField internal var spray = 0.0
        @JvmField internal var gsize = 0.0
        @JvmField internal var dens = 0.0
        @JvmField internal var scan = 0.0
        @JvmField internal var pspray = 0.0
        @JvmField internal var grev = 0.0
        @JvmField internal var lpos = 0.0
        @JvmField internal var head = 0.0
        @JvmField internal var gshape = 0
        @JvmField internal var nextGrain = 0L

        // drums
        @JvmField internal var model = 0
        @JvmField internal var dtune = 0.0
        @JvmField internal var ddecay = 0.0
        @JvmField internal var dtone = 0.0
        @JvmField internal var dsnap = 0.0
        @JvmField internal var dsweep = 0.0
        @JvmField internal var dstime = 0.0
        @JvmField internal var dnoise = 0.0
        @JvmField internal var dt0 = 0.0
        @JvmField internal var dT = 0L
        @JvmField internal var dph1 = 0.0
        @JvmField internal var dph2 = 0.0
        @JvmField internal val mph = DoubleArray(6)
        // plucks: the string is a delay line
        @JvmField internal val ks = FloatArray(4096)
        @JvmField internal var ksW = 0
        @JvmField internal var ksLp = 0.0
        @JvmField internal var kdecay = 0.0
        @JvmField internal var kbright = 0.0
        @JvmField internal var kdamp = 0.0
        @JvmField internal var kpick = 0.0
        @JvmField internal var kbody = 0.0
        @JvmField internal var kmute = 0.0
        @JvmField internal var relEnv = 1.0
        @JvmField internal var ksAvg = 0.0
        @JvmField internal var ksT = 0L

        /** The sound a granular voice reads (null for silence). Set before noteOn. */
        fun source(s: Sample?) { src = if (s == null) null else s.data; srcRate = if (s == null) 44100 else s.rate }

        /** Start a note (or slide an already sounding mono note there when slide is true). */
        fun noteOn(p: Patch, hz: Double, vel: Float, rate: Int, slide: Boolean) {
            this.rate = rate
            this.patch = p
            read(p)
            hzTarget = hz
            if (!slide || !on) {
                this.hz = hz
                for (i in 0 until 4) { fph[i] = 0.0; fe4[i].on(true) }
                fb2 = 0.0; fb1 = fb2
                for (i in 0 until NG) gOn[i] = false
                head = 0.0; nextGrain = 0
                dT = 0; dph2 = 0.0; dph1 = dph2; for (i in 0 until 6) mph[i] = i * .17
                if (kindNo == 5) pluckOn(hz * oct)
                for (i in 0 until 5) { ph1[i] = (i * .2137) % 1; ph2[i] = (i * .3971 + .5) % 1 }
                phSub = 0.0; ic2 = 0.0; ic1 = ic2; s4 = ic1; s3 = s4; s2 = s3; s1 = s2
                lph = 0.0
                amp.on(true); fe.on(true)
                fresh = true
            } else {
                // a slide: no new envelopes, the pitch glides there
                fresh = false
            }
            // slides glide there: acid always takes a moment, mono as long as Glide says
            val gt = if (mode == 2) maxOf(glide, .04) else glide
            glideK = if (slide && gt > .002) exp(-4.6 / maxOf(1.0, gt * rate)) else 0.0
            this.vel = vel
            on = true; gate = true; ctl = 0
        }

        fun noteOff() { gate = false; amp.off(); fe.off(); for (e in fe4) e.off() }

        fun done(): Boolean {
            if (on && kindNo == 4) return dT > ringSec(patch!!) * rate + rate / 20
            if (on && kindNo == 5 && ksT > rate / 10 && ksAvg < 2e-5) return true
            if (!on || gate) return false
            if (kindNo == 5) return relEnv < 1e-4
            if (kindNo == 1) {
                val cm = CARRIERS[algo]
                for (i in 0 until 4) if (((cm shr i) and 1) != 0 && fe4[i].stage != 0) return false
                return true
            }
            return amp.stage == 0
        }

        fun velocity(): Float = vel

        /** Seconds the release lasts at most, so the engine knows when the note is over. */
        fun releaseSec(): Double {
            val patch = patch ?: return .3
            if (patch.kind === FM) {
                var m = 0.0; val cm = CARRIERS[patch.get("algo").toInt()]
                for (i in 0 until 4) if (((cm shr i) and 1) != 0) m = maxOf(m, patch.v[patch.kind.at(FM_RL[i])])
                return minOf(8.0, m) * 1.3 + .02
            }
            if (patch.kind === DRUM) return ringSec(patch) + .05
            if (patch.kind === PLUCK) return minOf(3.0, patch.get("mute")) * 1.5 + .02
            return minOf(8.0, patch.get("ar")) * 1.3 + .02
        }

        /** Read the knobs; called at the note's start and once per block, so knob turns reach held notes. */
        fun read(p: Patch) {
            patch = p
            val v = p.v
            val K = p.kind
            kindNo = if (K === FM) 1 else if (K === WAVE) 2 else if (K === GRAIN) 3 else if (K === DRUM) 4 else if (K === PLUCK) 5 else 0
            mode = g(v, K, "mode", 0.0).toInt()
            w1 = g(v, K, "w1", 2.0); pw1 = g(v, K, "pw1", .5); w2 = g(v, K, "w2", 2.0)
            semi2r = 2.0.pow((g(v, K, "semi2", 0.0) + g(v, K, "fine2", 0.0) / 100) / 12)
            mix2 = g(v, K, "mix2", 0.0); sync = g(v, K, "sync", 0.0) > .5
            sub = g(v, K, "sub", 0.0); noise = g(v, K, "noise", 0.0)
            uni = g(v, K, "uni", 1.0).toInt(); spread = g(v, K, "spread", .3)
            ftype = g(v, K, "ftype", 0.0).toInt(); cut = g(v, K, "cut", 18000.0); res = g(v, K, "res", 0.0); fenv = g(v, K, "fenv", 0.0)
            track = g(v, K, "track", .5); drive = g(v, K, "drive", 0.0)
            lrate = g(v, K, "lrate", 1.0); lshape = g(v, K, "lshape", 0.0).toInt()
            lpitch = g(v, K, "lpitch", 0.0); lcut = g(v, K, "lcut", 0.0); lamp = g(v, K, "lamp", 0.0); lpw = g(v, K, "lpw", 0.0)
            glide = g(v, K, "glide", .001); accent = g(v, K, "accent", .5); oct = 2.0.pow(g(v, K, "oct", 0.0)); level = g(v, K, "level", .7)
            if (kindNo == 1) {
                algo = g(v, K, "algo", 0.0).toInt(); fbAmt = g(v, K, "fb", 0.0); bright = g(v, K, "bright", 1.0)
                val dc = g(v, K, "det", 0.0)
                for (i in 0 until 4) {
                    fr[i] = RATIOS[v[K.at(FM_R[i])].toInt()]
                    fl[i] = v[K.at(FM_L[i])]
                    fdet[i] = if (i == 1) 2.0.pow(dc / 1200) else if (i == 3) 2.0.pow(-dc / 1200) else 1.0
                    if (rate > 0) fe4[i].set(v[K.at(FM_A[i])], v[K.at(FM_D[i])], v[K.at(FM_S[i])], v[K.at(FM_RL[i])], rate)
                }
            } else if (kindNo == 2) {
                table = g(v, K, "table", 0.0).toInt(); pos = g(v, K, "pos", 0.0); penv = g(v, K, "penv", 0.0); plfo = g(v, K, "plfo", 0.0); warp = g(v, K, "warp", 0.0)
            } else if (kindNo == 4) {
                model = g(v, K, "model", 0.0).toInt(); dtune = 2.0.pow(g(v, K, "tune", 0.0) / 12); ddecay = g(v, K, "decay", .5); dtone = g(v, K, "tone", .5)
                dsnap = g(v, K, "snap", .4); dsweep = g(v, K, "sweep", .5); dstime = g(v, K, "stime", .04); dnoise = g(v, K, "noise", .5)
            } else if (kindNo == 5) {
                kdecay = g(v, K, "decay", 2.5); kbright = g(v, K, "bright", .7); kdamp = g(v, K, "damp", .3); kpick = g(v, K, "pick", .2)
                kbody = g(v, K, "body", .3); kmute = g(v, K, "mute", .6)
            } else if (kindNo == 3) {
                pos = g(v, K, "pos", 0.0); spray = g(v, K, "spray", 0.0); gsize = g(v, K, "size", .08); dens = g(v, K, "dens", 30.0)
                scan = g(v, K, "scan", 0.0); pspray = g(v, K, "pspray", 0.0); grev = g(v, K, "rev", 0.0); gshape = g(v, K, "gshape", 0.0).toInt(); lpos = g(v, K, "lpos", 0.0)
            }
            if (rate > 0) {
                fe.set(g(v, K, "fa", .005), g(v, K, "fd", .4), g(v, K, "fs", .2), g(v, K, "fr", .3), rate)
                amp.set(g(v, K, "aa", .004), g(v, K, "ad", .5), g(v, K, "as", .8), g(v, K, "ar", .25), rate)
            }
        }

        private fun nz(): Double { rng = rng xor (rng shl 13); rng = rng xor (rng ushr 17); rng = rng xor (rng shl 5); return (rng and 0xFFFF) / 32767.5 - 1 }

        /** A one-shot state-variable filter step on the shared states: 0 low, 1 band, 2 high. */
        private fun svf(x: Double, fc: Double, q: Double, out: Int): Double {
            val gg = tan(PI * minOf(fc, rate * .45) / rate); val kk = 1 / q
            val a1 = 1 / (1 + gg * (gg + kk)); val a2 = gg * a1; val a3 = gg * a2
            val v3 = x - ic2; val v1 = a1 * ic1 + a2 * v3; val v2 = ic2 + a2 * ic1 + a3 * v3
            ic1 = 2 * v1 - ic1; ic2 = 2 * v2 - ic2
            return if (out == 0) v2 else if (out == 1) v1 else x - kk * v1 - v2
        }

        /** Drums: each model built from sines, squares and noise, with its own fall. */
        private fun drumNext(f: Double): Float {
            val t = dT++ / rate.toDouble()
            val r = f / 261.6256 * dtune  // the note moves the drum's pitch; pitch 0 is the drum's own
            val tau = maxOf(.005, ddecay) / 4.6
            val env = exp(-t / tau)
            val click = dsnap * exp(-t / .0015) * nz()
            var o: Double
            when (model) {
                0, 5, 7 -> { // kick, tom, zap: a sine swept down from above
                    val base = (if (model == 0) 50 else if (model == 5) 110 else 180) * r
                    val depth = if (model == 7) dsweep * 48 else if (model == 5) dsweep * 18 else dsweep * 30
                    val fNow = base * 2.0.pow(depth / 12 * exp(-t / maxOf(.002, dstime)))
                    dph1 += fNow / rate; if (dph1 >= 1) dph1 -= 1
                    o = sin1(dph1) * env + click * .6
                    if (model == 5) o += dnoise * .25 * nz() * exp(-t / (tau * .4))
                }
                1 -> { // snare: two tones and filtered noise
                    val base = 180 * r
                    dph1 += base / rate; if (dph1 >= 1) dph1 -= 1
                    dph2 += base * 1.83 / rate; if (dph2 >= 1) dph2 -= 1
                    val body = (sin1(dph1) + .5 * sin1(dph2)) * exp(-t / (tau * .45))
                    val nzv = svf(nz(), 1500 + dtone * 6000, .7, 2) * env
                    o = body * (1 - dnoise * .5) + nzv * (.4 + dnoise * .9) + click * .5
                }
                2, 3 -> { // hats: six metallic squares through a high filter
                    var s = 0.0
                    for (i in 0 until 6) { mph[i] += 400 * r * HAT[i] / rate; if (mph[i] >= 1) mph[i] -= 1; s += if (mph[i] < .5) 1 else -1 }
                    s = s / 6 + nz() * .3
                    o = svf(s, 5000 + dtone * 6000, .8, 2) * env * 1.6 + click * .2
                }
                4 -> { // clap: three quick bursts of band noise, then the tail
                    var burst = 0.0
                    for (i in 0 until 3) { val tt = t - i * .011; if (tt >= 0) burst = maxOf(burst, exp(-tt / .006)) }
                    val tail = if (t > .022) exp(-(t - .022) / tau) else 0.0
                    o = svf(nz(), (900 + dtone * 1800) * r, 1.6, 1) * maxOf(burst, tail * .8) * 2.2
                }
                else -> { // cowbell: two squares through a band filter
                    dph1 += 540 * r / rate; if (dph1 >= 1) dph1 -= 1
                    dph2 += 800 * r / rate; if (dph2 >= 1) dph2 -= 1
                    val s = ((if (dph1 < .5) 1 else -1) + (if (dph2 < .5) 1 else -1)) * .5
                    val fall = exp(-t / .01) * .5 + .5
                    o = svf(s, 2000 + dtone * 2000, 2.0, 1) * env * fall * 1.5
                }
            }
            if (drive > 0) o = sat(o * (1 + drive * 4)) / (1 + drive)
            return (o * level * vel * .9).toFloat()
        }

        /** Plucks: fill the string (a delay one period long) with a shaped burst. */
        private fun pluckOn(f0: Double) {
            val L = maxOf(2.0, minOf((ks.size - 2).toDouble(), rate / maxOf(12.0, f0))).toInt()
            ks.fill(0f)
            var lp = 0.0; val a = .05 + kbright * .95
            val pk = jround(kpick * .5 * L).toInt()
            for (i in 0 until L) { lp += (nz() - lp) * a; ks[i] = lp.toFloat() }
            if (pk > 0) for (i in L - 1 downTo pk) ks[i] -= ks[i - pk]   // a pick away from the end loses the notes that have a node there
            // take out the burst's average and bring it to a steady level, so every pluck starts as loud
            var mean = 0.0; var peak = 1e-9
            for (i in 0 until L) mean += ks[i]
            mean /= L
            for (i in 0 until L) { ks[i] -= mean.toFloat(); peak = maxOf(peak, abs(ks[i]).toDouble()) }
            for (i in 0 until L) ks[i] = (ks[i] * .6 / peak).toFloat()
            ksW = L; ksLp = 0.0; ksT = 0; ksAvg = .1; relEnv = 1.0; ic2 = 0.0; ic1 = ic2
        }

        private fun pluckNext(f: Double): Float {
            // the damping filter in the loop delays the string a little; take that off so high notes stay in tune
            val d0 = .02 + kdamp * .9; val c0 = 1 - d0 * .5
            val L = maxOf(2.0, minOf((ks.size - 4).toDouble(), rate / maxOf(12.0, f) - (1 - c0) / c0 * c0))
            val M = ks.size
            var rp = ksW - L
            if (rp < 0) rp += M
            val i = rp.toInt(); val fr = rp - i
            val a = ks[i % M]; val b = ks[(i + 1) % M]
            val y = a + (b - a) * fr
            // loss per trip round the string, so the whole string dies away in Decay seconds
            val gfb = exp(-4.6 * L / (maxOf(.05, kdecay) * rate))
            val d = .02 + kdamp * .9
            ksLp += (y - ksLp) * (1 - d * .5)
            val w = (ksLp * (1 - d * .5) + y * d * .5) * gfb
            ks[ksW] = w.toFloat()
            if (++ksW >= M) ksW = 0
            ksT++
            ksAvg += (abs(y) - ksAvg) * .001
            if (!gate) relEnv *= exp(-4.6 / maxOf(1.0, kmute * rate))
            var o = y
            if (kbody > 0) o += svf(y, 220.0, 1.5, 1) * kbody * 1.5
            if (drive > 0) o = sat(o * (1 + drive * 4)) / (1 + drive)
            return (o * relEnv * level * vel).toFloat()
        }

        private fun rnd01(): Double { rng = rng xor (rng shl 13); rng = rng xor (rng ushr 17); rng = rng xor (rng shl 5); return (rng and 0xFFFFFF) / 0x1000000.toDouble() }

        /**
         * Granular: a new grain every 1/density seconds (a little early or late, so it never buzzes), each reading the
         * sound around the position at the note's pitch (the sound counts as middle C), faded in and out.
         */
        private fun grainNext(f: Double): Float {
            val d = src
            if (d == null || d.size < 64) { amp.next(); return 0f }
            val N = d.size
            if (--nextGrain <= 0) {
                nextGrain = maxOf(1L, jround(rate / dens * (.7 + .6 * rnd01())))
                for (i in 0 until NG) if (!gOn[i]) {
                    var at = pos + head / N + (rnd01() - .5) * spray * .5 + (if (lpos > 0) lpos * lfoV * .25 else 0.0)
                    at -= floor(at)
                    var ratio = f / 261.6256 * srcRate / rate
                    if (pspray > 0) ratio *= 2.0.pow(jround((rnd01() * 2 - 1) * pspray) / 12.0)
                    val back = grev > 0 && rnd01() < grev
                    gPos[i] = at * N; gInc[i] = if (back) -ratio else ratio
                    gLen[i] = maxOf(32.0, gsize * rate).toInt(); gAge[i] = 0; gOn[i] = true
                    break
                }
            }
            head += scan * srcRate / rate
            if (head > N || head < -N) head -= floor(head / N) * N
            var o = 0.0; var live = 0
            for (i in 0 until NG) {
                if (!gOn[i]) continue
                val x = gAge[i] / gLen[i].toDouble()
                val w: Double
                if (gshape == 1) w = if (x < .1) x * 10 else if (x > .9) (1 - x) * 10 else 1.0
                else { val s = sin1(x * .5).toDouble(); w = s * s }
                var p = gPos[i]
                p -= floor(p / N) * N
                val j = p.toInt(); val fr2 = p - j; val j1 = if (j + 1 >= N) 0 else j + 1
                o += (d[j] + (d[j1] - d[j]) * fr2) * w
                gPos[i] += gInc[i]
                if (++gAge[i] >= gLen[i]) gOn[i] = false
                live++
            }
            // overlapping grains add up; keep the cloud near one sound's level
            o /= sqrt(maxOf(1.0, dens * gsize * .5))
            // the shared filter (no envelope on it here)
            val y: Double
            if (ftype == 0) {
                val gg = G; val S = (gg * gg * gg * s1 + gg * gg * s2 + gg * s3 + s4) * (1 - gg)
                val u = (o - k * S) / (1 + k * gg * gg * gg * gg)
                val v1 = (u - s1) * gg; val y1 = v1 + s1; s1 = y1 + v1
                val v2 = (y1 - s2) * gg; val y2 = v2 + s2; s2 = y2 + v2
                val v3 = (y2 - s3) * gg; val y3 = v3 + s3; s3 = y3 + v3
                val v4 = (y3 - s4) * gg; val y4 = v4 + s4; s4 = y4 + v4
                y = y4
            } else {
                val a1 = 1 / (1 + g * (g + k)); val a2 = g * a1; val a3 = g * a2
                val v3 = o - ic2; val v1 = a1 * ic1 + a2 * v3; val v2 = ic2 + a2 * ic1 + a3 * v3
                ic1 = 2 * v1 - ic1; ic2 = 2 * v2 - ic2
                y = if (ftype == 1) v2 else if (ftype == 2) v1 * k else o - k * v1 - v2
            }
            val e = amp.next()
            return (y * e * level * vel * .9).toFloat()
        }

        /** FM: operators 4 down to 1, each bent by the ones the algorithm feeds into it; carriers are what you hear. */
        private fun fmNext(f: Double): Float {
            val mods = MODS[algo]
            for (i in 3 downTo 0) {
                var m = 0.0
                val mask = mods[i]
                for (j in i + 1 until 4) if (((mask shr j) and 1) != 0) m += mo[j]
                if (i == 3) m += fbAmt * (fb1 + fb2) * .25
                val e = fe4[i].next()
                val s = sin1(fph[i] + m).toDouble()
                fph[i] += f * fr[i] * fdet[i] / rate
                if (fph[i] >= 1) fph[i] -= floor(fph[i])
                val lv = fl[i]
                mo[i] = s * lv * lv * e * 2 * bright   // as a modulator: phase in cycles
                co[i] = s * lv * e                     // as a carrier
                if (i == 3) { fb2 = fb1; fb1 = s * e }
            }
            val cm = CARRIERS[algo]
            var out = 0.0; var nc = 0
            for (i in 0 until 4) if (((cm shr i) and 1) != 0) { out += co[i]; nc++ }
            out /= sqrt(maxOf(1, nc).toDouble())
            if (lamp > 0) out *= 1 - lamp * .5 * (1 + lfoV)
            return (out * level * vel * .7).toFloat()
        }

        /** One sample. pitchMul carries bends, glides and wobble from the engine. */
        fun next(pitchMul: Double): Float {
            // control rate, every 16 samples: LFO, filter envelope and cutoff
            if (ctl-- <= 0) {
                ctl = 15
                val dl = lrate * 16 / rate
                val prev = lph
                lph += dl; if (lph >= 1) lph -= 1
                when (lshape) {
                    1 -> lfoV = if (lph < .5) 4 * lph - 1 else 3 - 4 * lph
                    2 -> lfoV = 1 - 2 * lph
                    3 -> lfoV = if (lph < .5) 1.0 else -1.0
                    4 -> { if (lph < prev) { rng = rng xor (rng shl 13); rng = rng xor (rng ushr 17); rng = rng xor (rng shl 5); lsh = (rng and 0xFFFF) / 32767.5 - 1 }; lfoV = lsh }
                    else -> lfoV = sin1(lph).toDouble()
                }
                lfoPitchMul = if (lpitch > 0) 2.0.pow(lpitch * lfoV) else 1.0
                val nu = maxOf(1, uni)
                for (u in 0 until nu) det[u] = if (nu == 1) 1.0 else 2.0.pow(spread * (u / (nu - 1).toDouble() - .5) / 12)
                val fv = fe.skip(16)
                if (kindNo == 2) wposNow = maxOf(0.0, minOf(1.0, pos + penv * fv + plfo * lfoV * .5))
                val acc = if (mode == 2 && fresh) 1 + accent * .8 else 1.0
                val oct4 = fenv * fv * 6 * acc + lcut * lfoV * 3
                val base = cut * (hz * oct / 261.63).pow(track)
                fNow = maxOf(20.0, minOf(rate * .45, base * 2.0.pow(oct4)))
                g = tan(PI * fNow / rate)
                if (ftype == 0 || mode == 2) {
                    k = if (mode == 2) res * 3.6 else res * 4.0  // ladder feedback; acid a touch below self-oscillation
                    G = g / (1 + g)
                } else {
                    k = 2 - 2 * minOf(.98, res)      // SVF damping
                }
            }
            hz = if (glideK > 0) hzTarget + (hz - hzTarget) * glideK else hzTarget
            val f = hz * oct * pitchMul * lfoPitchMul
            if (kindNo == 1) return fmNext(f)
            if (kindNo == 3) return grainNext(f)
            if (kindNo == 4) return drumNext(f)
            if (kindNo == 5) return pluckNext(f)
            val dt1 = f / rate; val dt2 = dt1 * semi2r
            val pw = maxOf(.05, minOf(.95, pw1 + lpw * lfoV * .4))
            // oscillators, with unison spread around the note
            var o = 0.0
            val n = maxOf(1, uni)
            if (kindNo == 2) {
                // wavetable: the right level of detail for this pitch, two shapes either side of the position, mixed
                var m = 0
                val nh = .45 / maxOf(1e-9, dt1)
                while (m < WT.MIPS - 1 && WT.HARM[m] > nh) m++
                val tb = WT.T[table][m]
                val fp = wposNow * (WT.FR - 1)
                val f0 = minOf(WT.FR - 2, fp.toInt())
                val ff = fp - f0
                val A = tb[f0]!!; val B = tb[f0 + 1]!!
                for (u in 0 until n) {
                    val d1 = dt1 * det[u]
                    var ph = ph1[u]
                    if (warp > 0) { ph *= 1 + warp * 3; ph -= floor(ph) }
                    val xi = ph * WT.N
                    val i = xi.toInt()
                    val fr2 = xi - i
                    val av = A[i] + (A[i + 1] - A[i]) * fr2; val bv = B[i] + (B[i + 1] - B[i]) * fr2
                    o += av + (bv - av) * ff
                    ph1[u] += d1; if (ph1[u] >= 1) ph1[u] -= 1
                }
            } else for (u in 0 until n) {
                val d1 = dt1 * det[u]; val d2 = dt2 * det[u]
                val a = osc(ph1[u], d1, w1, pw)
                val b = if (mix2 > 0) osc(ph2[u], d2, w2, pw) else 0.0
                o += a * (1 - mix2 * .5) + b * mix2
                ph1[u] += d1
                if (ph1[u] >= 1) { ph1[u] -= 1; if (sync) ph2[u] = ph1[u] * (d2 / d1) }
                ph2[u] += d2; if (ph2[u] >= 1) ph2[u] -= 1
            }
            o /= sqrt(n.toDouble())
            if (sub > 0) {
                val ds = dt1 * .5
                var sq = if (phSub < .5) 1.0 else -1.0
                sq += blep(phSub, ds)
                var t2 = phSub - .5; if (t2 < 0) t2 += 1
                sq -= blep(t2, ds)
                o += sq * sub * .7
                phSub += ds; if (phSub >= 1) phSub -= 1
            }
            if (noise > 0) { rng = rng xor (rng shl 13); rng = rng xor (rng ushr 17); rng = rng xor (rng shl 5); o += noise * ((rng and 0xFFFF) / 32767.5 - 1) }
            // filter
            val x = o * (1 + drive * 3)
            var y: Double
            if (ftype == 0 || mode == 2) {
                // zero-delay ladder (TPT one-poles), the feedback solved each sample; saturation in the loop
                // what the four stages' states add to the output when the input is 0: y4 = G^4 u + S
                val gg = G; val S = (gg * gg * gg * s1 + gg * gg * s2 + gg * s3 + s4) * (1 - gg)
                var u = (x - k * S) / (1 + k * gg * gg * gg * gg)
                u = sat(u)
                val v1 = (u - s1) * gg; val y1 = v1 + s1; s1 = y1 + v1
                val v2 = (y1 - s2) * gg; val y2 = v2 + s2; s2 = y2 + v2
                val v3 = (y2 - s3) * gg; val y3 = v3 + s3; s3 = y3 + v3
                val v4 = (y3 - s4) * gg; val y4 = v4 + s4; s4 = y4 + v4
                y = if (mode == 2) y3 else y4
                // keep the passband up as resonance rises, like the real thing loses less than the math does
                y *= 1 + k * .25
            } else {
                // TPT state-variable filter: low 12, band, high
                val a1 = 1 / (1 + g * (g + k)); val a2 = g * a1; val a3 = g * a2
                val v3 = x - ic2; val v1 = a1 * ic1 + a2 * v3; val v2 = ic2 + a2 * ic1 + a3 * v3
                ic1 = 2 * v1 - ic1; ic2 = 2 * v2 - ic2
                y = if (ftype == 1) v2 else if (ftype == 2) v1 * k else x - k * v1 - v2
                y = sat(y)
            }
            if (drive > 0) y = sat(y * (1 + drive)) / (1 + drive * .3)
            var e = amp.next()
            if (lamp > 0) e *= 1 - lamp * .5 * (1 + lfoV)
            val acc = if (mode == 2 && fresh) 1 + accent * .35 else 1.0
            return (y * e * level * vel * acc * .7).toFloat()
        }

        companion object {
            internal const val NG = 24

            /** The six square oscillators of a hat, at the classic metallic ratios. */
            private val HAT = doubleArrayOf(1.0, 1.4471, 1.617, 1.9265, 2.5028, 2.6637)

            private fun g(v: DoubleArray, K: Kind, id: String, def: Double): Double { val i = K.at(id); return if (i < 0) def else v[i] }
        }
    }
}
