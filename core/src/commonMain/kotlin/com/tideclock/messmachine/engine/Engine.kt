package com.tideclock.messmachine.engine

import kotlin.concurrent.Volatile
import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

// Java's round(double) and round(float): NaN gives 0 (Kotlin's roundToLong / roundToInt throw on NaN), everything else is the same
private fun jRound(x: Double): Long = if (x.isNaN()) 0L else x.roundToLong()
private fun jRound(x: Float): Int = if (x.isNaN()) 0 else x.roundToInt()

/**
 * The Mess Machine's sound engine. Everything that makes noise happens inside render(), on the
 * audio thread, counted in frames: steps, swing, note repeat, the arp and every voice. That keeps
 * the timing exact no matter what the screen is doing. The UI talks to it through a command queue
 * and an immutable Snapshot of the mess, and hears back about recorded hits through a second queue.
 *
 * The rules for how a sound plays (trim, tune, gate, loop, attack, release, choke, tone, pan,
 * normalize) follow voiceEd() on the site.
 */
class Engine(private val rate: Int) {

    /* ================= what the engine plays from ================= */

    fun interface SampleSource { fun get(key: String): Sample? }

    /** Toggles that live on the transport, not in the saved mess. */
    class Live {
        @JvmField var click = false
        @JvmField var rec = false
        @JvmField var nr = false
        /** Rec exact (Nov 18): live hits record where they were played on the finer grid, not snapped to 16ths. */
        @JvmField var recExact = false
        @JvmField var nrRate = 1.0      // steps between repeats: 4 = 1/4, 2 = 1/8, 1 = 1/16, 0.5 = 1/32; x 2/3 triplet, x 1.5 dotted
        @JvmField var arp = "rep"     // rep, up, down, updown, rand
        /** SE-6: how repeat plays each hit. "cut" stops it before the next, "ring" lets it play out, "flow" plays it once and pulses its volume. */
        @JvmField var nrStyle = "cut"
        /** Repeat's pattern, one letter per repeat: x plays, . rests ("xx." is hit, hit, skip). Empty plays every one. */
        @JvmField var nrPat = ""
        fun copy(): Live { val l = Live(); l.click = click; l.rec = rec; l.nr = nr; l.nrRate = nrRate; l.arp = arp; l.nrStyle = nrStyle; l.nrPat = nrPat; l.recExact = recExact; return l }
        /** Does repeat number i (counted from 0) play, by the pattern? */
        fun patOn(i: Long): Boolean {
            val p: String? = nrPat
            if (p == null || p.isEmpty()) return true
            val c = p[i.mod(p.length.toLong()).toInt()]
            return c != '.' && c != '-'
        }
    }

    class PadSlot(
        @JvmField val index: Int, @JvmField val key: String, @JvmField val sample: Sample, @JvmField val edit: SoundEdit,
        @JvmField val audible: Boolean, @JvmField val opts: PadOpts,
        /** SE-11: the synth this pad plays, or null for a sound. */
        @JvmField val synth: Synth.Patch?,
        /** SE-15: the sound a granular synth reads, decoded; null when not loaded yet or not granular. */
        @JvmField val grainSrc: Sample?
    ) {
        constructor(index: Int, key: String, sample: Sample, edit: SoundEdit, audible: Boolean) : this(index, key, sample, edit, audible, PadOpts.DEFAULT)
        constructor(index: Int, key: String, sample: Sample, edit: SoundEdit, audible: Boolean, opts: PadOpts) : this(index, key, sample, edit, audible, opts, null, null)
    }

    internal class Track(@JvmField val pad: PadSlot, @JvmField val steps: BooleanArray, @JvmField val gap: IntArray, @JvmField val notes: Array<IntArray>) { // notes per step: pitch, length, pitch, length...
        /** The finer grid: per step, hits between the sixteenths as ticks into the step (1 to TICKS - 1). */
        @JvmField var offs: Array<IntArray>? = null
        /** Build 108: each step's level, from its strength: ghost, normal or accent. */
        @JvmField var gain: FloatArray? = null
        /** Build 6 of the overhaul: each step's chance or loop condition (null when every step always plays). */
        @JvmField var chance: ByteArray? = null
        /** Step locks: which controls are locked on some step (lockT), and per control the value on each step (NaN none). */
        @JvmField var lockT: IntArray? = null
        @JvmField var lockV: Array<FloatArray>? = null
    }

    internal class Pat(@JvmField val len: Int, @JvmField val tracks: Array<Track>, @JvmField val lanePad: IntArray, @JvmField val laneT: IntArray, @JvmField val laneV: Array<FloatArray>)

    class Snapshot private constructor(
        @JvmField internal val bpm: Int, @JvmField internal val swing: Int, @JvmField internal val cur: Int, @JvmField internal val songLen: Int,
        @JvmField internal val songMode: Boolean,
        @JvmField internal val song: IntArray, @JvmField internal val pads: Array<PadSlot?>, @JvmField internal val pats: Array<Pat>, @JvmField internal val live: Live
    ) {
        /** the master bus: how hard the mix is squashed (0 to 1) and its level after */
        @JvmField internal var squash = .5f
        @JvmField internal var level = 1f

        companion object {
            @JvmStatic
            fun build(p: Project, src: SampleSource, live: Live?): Snapshot {
                // Nov 21: one slot past the 64 pads for the Synth tab's scratch synth (Project.SCRATCH)
                val pads = arrayOfNulls<PadSlot>(Project.SLOTS)
                for (i in 0 until Project.SLOTS) {
                    val k = p.kit[i]
                    if (k.isEmpty()) continue
                    val s = src.get(k) ?: continue
                    val sp = p.synth(k)
                    val gs = if (sp != null && !sp.src.isEmpty()) src.get(sp.src) else null
                    pads[i] = PadSlot(i, k, s, p.edit(k).copy(), p.audible(k), p.opt(i).copy(), sp?.copy(), gs)
                }
                val pats = arrayOfNulls<Pat>(Project.NSLOTS)
                for (pi in 0 until Project.NSLOTS) {
                    val P = p.pats[pi]
                    val len = P.len
                    val tr: MutableList<Track> = ArrayList()
                    for (ps in pads) {
                        if (ps == null || !P.usedBy(ps.key)) continue
                        val st = BooleanArray(len)
                        for (j in 0 until len) st[j] = P.step(ps.key, j)
                        val gap = IntArray(len)
                        for (j in 0 until len) { var d = 1; while (d < len && !st[(j + d) % len]) d++; gap[j] = d }
                        val nt = arrayOfNulls<IntArray>(len)
                        val nl = P.notes[ps.key]
                        for (j in 0 until len) {
                            var c = 0
                            if (nl != null) for (n in nl) if (n.s == j) c++
                            val a = IntArray(c * 2)
                            var q = 0
                            if (nl != null) for (n in nl) if (n.s == j) { a[q++] = n.p; a[q++] = n.l }
                            nt[j] = a
                        }
                        val tk = Track(ps, st, gap, nt.requireNoNulls())
                        val gn = FloatArray(len)
                        for (j in 0 until len) gn[j] = STEP_GAIN[P.strength(ps.key, j)]
                        tk.gain = gn
                        val ch = P.chance[ps.key]
                        if (ch != null) tk.chance = ch.copyOf(len)
                        val lt: MutableList<Int> = ArrayList(); val lv: MutableList<FloatArray> = ArrayList()
                        for (le in P.locks.entries) {
                            val lk = le.key; val at = lk.lastIndexOf('@')
                            if (at < 0 || lk.substring(0, at) != ps.key) continue
                            val t = Project.parseIntOr(lk.substring(at + 1), -1)
                            if (t < 0 || t >= NT) continue
                            lt.add(t); lv.add(le.value.copyOf(len))
                        }
                        if (!lt.isEmpty()) { val ltA = IntArray(lt.size); val lvA = arrayOfNulls<FloatArray>(lt.size); for (q in 0 until lt.size) { ltA[q] = lt[q]; lvA[q] = lv[q] }; tk.lockT = ltA; tk.lockV = lvA.requireNoNulls() }
                        val ol = P.offs[ps.key]
                        if (ol != null && !ol.isEmpty()) {
                            val cnt = IntArray(len)
                            for (t in ol) { val j = t / Project.TICKS; if (j >= 0 && j < len) cnt[j]++ }
                            val oa = arrayOfNulls<IntArray>(len)
                            for (j in 0 until len) oa[j] = IntArray(cnt[j])
                            val fill = IntArray(len)
                            for (t in ol) { val j = t / Project.TICKS; if (j >= 0 && j < len) oa[j]!![fill[j]++] = t % Project.TICKS }
                            tk.offs = oa.requireNoNulls()
                        }
                        tr.add(tk)
                    }
                    val ids: MutableList<IntArray> = ArrayList()
                    val vals: MutableList<FloatArray> = ArrayList()
                    for (e in P.motion.entries) {
                        // Java's split(":") drops trailing empty strings
                        val f = e.key.split(":").dropLastWhile { it.isEmpty() }
                        try { ids.add(intArrayOf(f[0].toInt(), f[1].toInt())); vals.add(e.value.copyOf()) } catch (ignored: Exception) {}
                    }
                    val lp = IntArray(ids.size); val lt = IntArray(ids.size)
                    for (q in 0 until lp.size) { lp[q] = ids[q][0]; lt[q] = ids[q][1] }
                    pats[pi] = Pat(len, tr.toTypedArray(), lp, lt, vals.toTypedArray())
                }
                val sl = p.songLen()
                val sn = Snapshot(p.bpm, p.swing, p.cur, sl, p.songMode && sl > 0, p.song.copyOf(), pads, pats.requireNoNulls(), if (live == null) Live() else live.copy())
                sn.squash = p.squash.toFloat(); sn.level = p.level.toFloat()
                return sn
            }
        }
    }

    /* ================= what the engine tells the UI ================= */

    class RecEvent(
        @JvmField val type: Int, @JvmField val pat: Int, @JvmField val key: String, @JvmField val step: Int, @JvmField val pitches: IntArray?, @JvmField val len: Int,
        /** Build 108: how hard the recorded hit was, 0..1, or below 0 for a plain tap. */
        @JvmField val vel: Float
    ) {
        /** Nov 22: which press made this hit (a CANCEL names the same one), 0 for none. */
        @JvmField var hit = 0
        constructor(type: Int, pat: Int, key: String, step: Int, pitches: IntArray?, len: Int) : this(type, pat, key, step, pitches, len, -1f)
        /** The step strength a hit of this velocity records as: ghost, normal or accent. */
        fun strength(): Int = if (vel < 0) 1 else if (vel <= .55f) 0 else if (vel >= .95f) 2 else 1

        companion object {
            const val STEP = 0
            const val NOTES = 1
            const val OFF = 2
            const val CANCEL = 3
        }
    }

    class Position internal constructor(@JvmField val playing: Boolean, @JvmField val pat: Int, @JvmField val step: Int, @JvmField val songPos: Int)

    /* ================= voices ================= */

    internal class Voice {
        @JvmField var active = false; @JvmField var preview = false; @JvmField var hold = false; @JvmField var releasing = false; @JvmField var choked = false; @JvmField var dry = false
        @JvmField var d: FloatArray? = null
        /** Phaser: four all-pass stages; Freeze: the window it rocks in (-1 when free) and which way it is going. */
        @JvmField var ap1 = 0f; @JvmField var ap2 = 0f; @JvmField var ap3 = 0f; @JvmField var ap4 = 0f; @JvmField var phPh = 0.0; @JvmField var frzA = -1.0; @JvmField var frzDir = 1
        @JvmField var a = 0; @JvmField var zEnd = 0; @JvmField var choke = 0; @JvmField var pad = 0
        @JvmField var pos = 0.0; @JvmField var len = 0.0; @JvmField var inc = 0.0; @JvmField var loopFrom = 0.0; @JvmField var glideMul = 0.0
        @JvmField var glideLeft = 0L
        @JvmField var loop = false
        @JvmField var delay = 0L; @JvmField var t = 0L; @JvmField var playLen = 0L; @JvmField var attF = 0L; @JvmField var fadeF = 0L; @JvmField var startAbs = 0L; @JvmField var chokeAt = 0L
        @JvmField var lvl = 0f; @JvmField var gl = 0f; @JvmField var gr = 0f; @JvmField var relFrom = 0f; @JvmField var chokeG = 0f
        @JvmField var relLeft = 0L; @JvmField var relLen = 0L
        @JvmField var filt = false
        @JvmField var b0 = 0f; @JvmField var b1 = 0f; @JvmField var b2 = 0f; @JvmField var a1 = 0f; @JvmField var a2 = 0f; @JvmField var x1 = 0f; @JvmField var x2 = 0f; @JvmField var y1 = 0f; @JvmField var y2 = 0f
        @JvmField var px1 = 0f; @JvmField var px2 = 0f; @JvmField var py1 = 0f; @JvmField var py2 = 0f
        @JvmField var fxRvb = 0f; @JvmField var fxEcho = 0f; @JvmField var fxDirt = 0f; @JvmField var fxCrush = 0f; @JvmField var fxChorus = 0f; @JvmField var fxWob = 0f; @JvmField var fxWobRate = 0f; @JvmField var crushVal = 0f; @JvmField var volMul = 1f
        @JvmField var wobPh = 0.0; @JvmField var chPh = 0.0; @JvmField var bendMul = 1.0; @JvmField var panX = 0.0
        @JvmField var chBuf: FloatArray? = null
        @JvmField var chW = 0; @JvmField var crushCnt = 0
        @JvmField var lastEnv = 0f
        // grains (SE-1, SE-2, SE-5): two read heads, each fading in and out over one grain, half a grain apart.
        // inc moves the heads (the pitch), tInc moves time through the sound (the length).
        @JvmField var grain = false; @JvmField var frozen = false; @JvmField var flow = false
        @JvmField var tPos = 0.0; @JvmField var tInc = 0.0; @JvmField var g0 = 0.0; @JvmField var g1 = 0.0; @JvmField var gp0 = 0.0; @JvmField var gp1 = 0.0; @JvmField var gStep = 0.0; @JvmField var sus = 0.0; @JvmField var srate = 0.0
        @JvmField var pulseAt = 0L; @JvmField var pulseLen = 0L
        // SE-11 synths: the synth voice, and when its gate closes (frames into the note, -1 while held)
        @JvmField var syn = false
        @JvmField val sv = Synth.Voice()
        @JvmField var synOff = 0L
    }

    private val pool: Array<Voice> = Array(96) { Voice() }
    @Volatile private var snap: Snapshot? = null
    private val cmds = ConcurrentQueue<Array<Any?>>()
    private val recs = ConcurrentQueue<RecEvent>()
    private val clickHi: Sample
    private val clickLo: Sample
    private val clickHiSlot: PadSlot
    private val clickLoSlot: PadSlot

    // clock state, audio thread only
    private var playing = false; private var paused = false; private var freeOn = false
    private var frame = 0L
    private var nextStepAt = 0.0; private var freeNextAt = 0.0
    private var step = 0; private var songPos = 0; private var arpI = 0
    private var fired = 0L; private var stopAfter = -1L
    private val held: MutableMap<Int, Held> = LinkedHashMap()
    private val lastP = DoubleArray(Project.SLOTS)
    @Volatile private var lastVoice: Voice? = null
    private val rnd = JavaRandom()

    // perform FX state (audio thread)
    /** True while the step clock fires under tape stop, so every hit slows like tape, whatever its pitch setting. */
    private var tapeNow = false
    /** True while padDown starts flow notes: they freeze and hold like Sustain, whatever the sound's own Hold choice. */
    private var sustainNow = false
    /** SE-7: the beat clock for latched beat pads while the transport is stopped. */
    private var beatOn = false
    private var beatNextAt = 0.0
    private var fxStut = false; private var fxTape = false; private var fxOct = false; private var fxRev = false; private var fxCrush = false; private var fxDirt = false
    private var fxRing = false; private var fxGate = false; private var fxEcho = false; private var xyOn = false; private var xyHP = false; private var filtSlow = false
    private var stutA = 0; private var stutB = 0; private var lastStep = 0
    private var tapeAt = 0.0
    private var wCrush = 0f; private var wDirt = 0f; private var wRing = 0f; private var wGate = 0f; private var wSend = 0f
    private var ringPh = 0.0; private var ringHz = 220.0; private var gatePh = 0.0; private var gateHz = 8.0
    private val dlyL: FloatArray
    private val dlyR: FloatArray
    private var dlyW = 0
    private var hpL = 0f; private var hpR = 0f; private var hpXL = 0f; private var hpXR = 0f
    private var filtFreq = 20000.0; private var filtTarget = 20000.0; private var filtQ = .7; private var qTarget = .7
    private var fzL = FloatArray(4); private var fzR = FloatArray(4)
    private var dryBuf = FloatArray(0)
    private var bypassMaster = false

    // live filter per pad (the strip under each piano): cutoff and resonance glide per block
    private val pfFreq = DoubleArray(Project.SLOTS); private val pfTarget = DoubleArray(Project.SLOTS); private val pfQ = DoubleArray(Project.SLOTS); private val pfQT = DoubleArray(Project.SLOTS)
    private val pfOn = BooleanArray(Project.SLOTS); private val pfSlow = BooleanArray(Project.SLOTS)
    private val pfC: Array<FloatArray> = Array(Project.SLOTS) { FloatArray(5) }

    private fun oct(): Double = if (fxOct) .5 else 1.0

    private fun fxSet(S: Snapshot?, n: String, on: Boolean) {
        when (n) {
            "stutter" -> {
                if (on && playing && !fxStut) { stutA = lastStep - (lastStep % 2); stutB = step }
                fxStut = on && playing
            }
            "tape" -> {
                if (on && playing && !fxTape) tapeAt = nextStepAt
                fxTape = on && playing
            }
            "oct" -> fxOct = on
            "rev" -> fxRev = on
            "crush" -> fxCrush = on
            "dirty" -> fxDirt = on
            "ring" -> { if (on && !fxRing) ringHz = 110 + rnd.nextDouble() * 440; fxRing = on }
            "gate" -> { fxGate = on; gateHz = 1.0 / (60.0 / maxOf(40, S!!.bpm) / 4) }
            "echo" -> fxEcho = on
        }
    }

    /** Which effects are held right now, for the UI and tests. */
    fun fxActive(): Boolean = fxStut || fxTape || fxOct || fxRev || fxCrush || fxDirt || fxRing || fxGate || fxEcho || xyOn

    // master bus
    private var compEnv = 0f
    private val compAtk: Float
    private val compRel: Float

    // for the UI's playhead
    @Volatile private var uiFrame = 0L
    @Volatile private var uiNanos = 0L
    @Volatile private var latencyFrames = 0
    @Volatile private var uiPlaying = false
    private val hFrame = LongArray(64)
    private val hPat = IntArray(64); private val hStep = IntArray(64); private val hSong = IntArray(64)
    @Volatile private var hHead = -1

    internal class Held {
        @JvmField var id = 0
        lateinit var pad: PadSlot
        lateinit var pitches: IntArray
        @JvmField var mel = false; @JvmField var holdV = false; @JvmField var latched = false; @JvmField var beat = false; @JvmField var flow = false
        @JvmField var beats = 0
        /** Where this press was recorded (Nov 22: a swipe takes its own hit back), -1 if it wasn't. */
        @JvmField var recPat = -1; @JvmField var recAt = -1; @JvmField var recId = 0; @JvmField var recFine = false
        @JvmField var at = 0.0
        @JvmField var voices: MutableList<Voice> = ArrayList()
    }

    private fun tone(key: String, f: Double): Sample {
        val n = (.06 * rate).toInt()
        val d = FloatArray(n)
        for (k in 0 until n) { val t = k / rate.toDouble(); d[k] = (sin(2 * PI * f * t) * .18 * (.001 / .18).pow(minOf(1.0, t / .05))).toFloat() }
        return Sample(key, d, rate)
    }

    fun rate(): Int = rate

    /* ================= talking to the engine (any thread) ================= */

    fun setSnapshot(s: Snapshot?) { snap = s }
    fun setLatencyFrames(f: Int) { latencyFrames = f }

    fun play() { cmds.add(arrayOf<Any?>("play")) }
    /** Nov 22 (Tyler: a count-in): from a stop, this many steps of click come first, then the loop starts at its top. */
    fun play(countSteps: Int) { cmds.add(arrayOf<Any?>("play", countSteps)) }
    fun pause() { cmds.add(arrayOf<Any?>("pause")) }
    fun stop() { cmds.add(arrayOf<Any?>("stop")) }
    fun panic() { cmds.add(arrayOf<Any?>("panic")) }

    /** A pad or key went down. mel = keys or the strip (pitched, may hold), pitches in semitones. */
    fun down(id: Int, pad: Int, pitches: IntArray, mel: Boolean) { down(id, pad, pitches, mel, false) }
    /** latch = keep sounding (and repeating) after the finger lifts, until unlatch(id). */
    fun down(id: Int, pad: Int, pitches: IntArray, mel: Boolean, latch: Boolean) { down(id, pad, pitches, mel, latch, -1f) }
    /** Build 108: vel 0..1 is how hard it was hit (a MIDI key, a 16 Levels pad); below 0 = a plain tap, played as before. */
    fun down(id: Int, pad: Int, pitches: IntArray, mel: Boolean, latch: Boolean, vel: Float) { cmds.add(arrayOf<Any?>("down", id, pad, pitches, mel, nanoTime(), latch, vel)) }

    fun unlatch(id: Int) { cmds.add(arrayOf<Any?>("unlatch", id)) }
    fun up(id: Int) { cmds.add(arrayOf<Any?>("up", id, nanoTime())) }
    /** Nov 22 (Tyler: a swipe cancels its own hit): the press under this id fades out at once and its recorded hit goes. */
    fun cancel(id: Int) { cmds.add(arrayOf<Any?>("cancel", id)) }

    /** Hear a sound with its doctor settings, outside the mess (browsing, editing, toggling a step). */
    fun preview(s: Sample?, e: SoundEdit?, semis: Int) { preview(s, e, semis, false) }
    /** hold = keep looping the trim until stopPreviews(), for the doctor's loop preview. */
    fun preview(s: Sample?, e: SoundEdit?, semis: Int, hold: Boolean) { if (s != null) cmds.add(arrayOf<Any?>("preview", s, if (e == null) SoundEdit.DEFAULT else e.copy(), semis, hold, nanoTime())) }

    /* ---------- perform FX, held from the UI ---------- */
    fun fx(name: String, on: Boolean) { cmds.add(arrayOf<Any?>("fx", name, on)) }
    /** The filter pad: x 0..1 is cutoff, y 0..1 is resonance (top = more). on = finger down. */
    fun xy(x: Float, y: Float, on: Boolean) { cmds.add(arrayOf<Any?>("xy", x, y, on)) }
    fun xyHighPass(hp: Boolean) { cmds.add(arrayOf<Any?>("xyhp", hp)) }
    /**
     * A live filter on one pad only (every voice of that pad, held notes, repeats, the steps).
     * cut 0..1 is cutoff (1 = open), res 0..1 is resonance. on = false glides it open again.
     */
    fun padFilter(pad: Int, cut: Float, res: Float, on: Boolean) { cmds.add(arrayOf<Any?>("pf", pad, cut, res, on)) }
    fun stopPreviews() { cmds.add(arrayOf<Any?>("stopprev")) }


    fun pollRec(): RecEvent? = recs.poll()

    fun isPlaying(): Boolean = uiPlaying

    /** Where the music you are hearing right now is. */
    fun heard(): Position {
        val h = hHead
        val pl = uiPlaying
        if (h < 0) return Position(pl, -1, -1, 0)
        val now = uiFrame + ((nanoTime() - uiNanos) * (rate / 1e9)).toLong() - latencyFrames
        for (k in 0 until hFrame.size) {
            val i = ((h - k) % hFrame.size + hFrame.size) % hFrame.size
            if (hFrame[i] <= now) return Position(pl, hPat[i], hStep[i], hSong[i])
        }
        return Position(pl, hPat[(h % hFrame.size + hFrame.size) % hFrame.size], -1, 0)
    }

    /* ================= the audio thread ================= */

    /** Fill out with frames of interleaved stereo. */
    fun render(out: FloatArray, frames: Int) {
        val nanos = nanoTime()
        val S = snap
        // the step length first: notes started by this block's commands fit loops to the tempo (SE-4)
        if (S != null) spbNow = spb(S)
        while (true) { val c = cmds.poll() ?: break; command(S, c, nanos) }
        out.fill(0f, 0, frames * 2)
        if (dryBuf.size < frames * 2) dryBuf = FloatArray(frames * 2)
        dryBuf.fill(0f, 0, frames * 2)
        if (rvbIn.size < frames * 2) { rvbIn = FloatArray(frames * 2); echoIn = FloatArray(frames * 2) }
        rvbIn.fill(0f, 0, frames * 2)
        echoIn.fill(0f, 0, frames * 2)
        lfoBlock(S)
        if (S !== lastSnap) { lastSnap = S; dropGoneLanes(S); restHomes(S) }
        filterBlock(S)
        padFilters(frames)
        padBlock(S!!, frames)
        var done = 0
        while (done < frames) {
            var chunk = frames - done
            if (playing) {
                val until = ceil(nextStepAt).toLong() - frame
                if (until <= 0) { fireStep(S); continue }
                chunk = minOf(chunk.toLong(), until).toInt()
            } else if (freeOn || beatOn) {
                val uf = if (freeOn) ceil(freeNextAt).toLong() - frame else Long.MAX_VALUE
                val ub = if (beatOn) ceil(beatNextAt).toLong() - frame else Long.MAX_VALUE
                if (uf <= 0) { fireFree(S); continue }
                if (ub <= 0) { fireBeat(S); continue }
                chunk = minOf(chunk.toLong(), minOf(uf, ub)).toInt()
            }
            mix(out, done, chunk)
            done += chunk
            frame += chunk
        }
        soundBuses(out, frames)
        effects(S, out, frames)
        for (k in 0 until frames * 2) out[k] += dryBuf[k]
        if (!bypassMaster) master(out, frames)
        uiFrame = frame - frames
        uiNanos = nanos
        uiPlaying = playing
    }

    private fun padFilters(frames: Int) {
        for (p in 0 until Project.SLOTS) {
            if (!lfoFilt[p] && !pfOn[p] && pfTarget[p] >= 19999 && pfQT[p] <= .71) continue
            val g = 1 - exp(-frames / ((if (pfSlow[p]) .12 else .025) * rate))
            pfFreq[p] += (pfTarget[p] - pfFreq[p]) * g
            pfQ[p] += (pfQT[p] - pfQ[p]) * g
            if (!lfoFilt[p] && pfFreq[p] >= 19900 && pfTarget[p] >= 19999) { pfFreq[p] = 20000.0; pfQ[p] = .7; pfOn[p] = false; continue }
            val f = minOf(pfFreq[p], rate * .45); val w = 2 * PI * f / rate; val cs = cos(w); val al = sin(w) / (2 * pfQ[p]); val a0 = 1 + al
            val c = pfC[p]
            val ty = pfType[p]
            if (ty == 2) { c[0] = ((1 + cs) / 2 / a0).toFloat(); c[1] = (-(1 + cs) / a0).toFloat(); c[2] = c[0] }
            else if (ty == 3) { c[0] = (al / a0).toFloat(); c[1] = 0f; c[2] = -c[0] }
            else { c[0] = ((1 - cs) / 2 / a0).toFloat(); c[1] = ((1 - cs) / a0).toFloat(); c[2] = c[0] }
            c[3] = (-2 * cs / a0).toFloat(); c[4] = ((1 - al) / a0).toFloat()
            pfOn[p] = true
        }
    }

    /* ---------- the LFO strip: one slow wave per pad (the build after 84) ---------- */

    private val lfoV = DoubleArray(Project.SLOTS); private val lfoRandV = DoubleArray(Project.SLOTS)
    private var lastSnap: Snapshot? = null

    /** A new snapshot (an edit, Undo, a cleared lane while stopped): lanes that no longer exist let go at once. */
    private fun dropGoneLanes(S: Snapshot?) {
        val pi = if (S != null) playingPat(S) else -1
        val P = if (S != null && pi >= 0 && pi < S.pats.size) S.pats[pi] else null
        val keep = arrayOfNulls<BooleanArray>(Project.SLOTS)
        if (P != null) for (q in 0 until P.lanePad.size) {
            val lp = P.lanePad[q]; val lt = P.laneT[q]
            if (lp < 0 || lp >= Project.SLOTS || lt < 0 || lt >= NT) continue
            if (keep[lp] == null) keep[lp] = BooleanArray(NT)
            keep[lp]!![lt] = true
        }
        for (lp in 0 until Project.SLOTS) for (lt in 0 until NT)
            if (laneHeld[lp][lt] && (keep[lp] == null || !keep[lp]!![lt])) { laneHeld[lp][lt] = false; combine(lp, lt) }
    }
    private val lfoRandCyc = LongArray(Project.SLOTS); private val padLast = LongArray(Project.SLOTS)
    private val lfoFilt = BooleanArray(Project.SLOTS)
    private val pfType = IntArray(Project.SLOTS)

    /** The LFO's wave right now on a pad, -1..1 (0 when it is off), for tests and the strip's light. */
    fun lfoValue(pad: Int): Double = if (pad >= 0 && pad < Project.SLOTS) lfoV[pad] else 0.0
    /** The pad's filter cutoff in Hz right now (20000 when open), for tests. */
    fun padCutoff(pad: Int): Double = if (pad >= 0 && pad < Project.SLOTS && (pfOn[pad] || lfoFilt[pad])) pfFreq[pad] else 20000.0

    /**
     * Each block: every pad with its LFO on gets its wave value, and the strip's filter (low, high or band pass) is set
     * straight from it, with no glide, so a 1/32 wub stays sharp. The wave runs with the loop (it counts from the top of
     * the loop while playing, from the clock while stopped), or restarts on every hit.
     */
    private fun lfoBlock(S: Snapshot?) {
        if (S == null) return
        val spbF = if (spbNow > 0) spbNow else spb(S)
        val pos = if (playing) step - countLeft - (nextStepAt - frame) / spbF else frame / spbF
        for (p in 0 until Project.SLOTS) {
            val ps = S.pads[p]
            val o = ps?.opts
            if (o == null || !o.lfoOn) { lfoV[p] = 0.0; continue }
            val per = maxOf(.125, o.lfoRate)
            val x = (if (o.lfoRetrig) (frame - padLast[p]) / spbF else pos) / per
            val cyc = floor(x).toLong()
            val v = lfoWave(o.lfoWave, x - cyc, cyc, p)
            lfoV[p] = v
        }
    }

    /** Is a pad's live filter closed at all right now (for the UI and tests). */
    fun padFiltered(pad: Int): Boolean = pad >= 0 && pad < Project.SLOTS && pfOn[pad]

    private fun spb(S: Snapshot): Double = rate * 60.0 / maxOf(40, S.bpm) / 4.0

    private fun playingPat(S: Snapshot): Int {
        if (S.songMode) return if (songPos < S.song.size) S.song[songPos] else -1
        return S.cur
    }

    private fun lenOf(S: Snapshot, pi: Int): Int = if (pi >= 0) S.pats[pi].len else 16

    private fun nowStep(S: Snapshot, nanos: Long, renderNanos: Long): Double {
        val heardFrame = frame - latencyFrames - (renderNanos - nanos) * (rate / 1e9)
        return step - countLeft - (nextStepAt - heardFrame) / spb(S)
    }

    private fun command(S: Snapshot?, c: Array<Any?>, renderNanos: Long) {
        // a Java "break" out of the switch is a return here (nothing follows the switch)
        when (c[0] as String) {
            "play" -> {
                if (!paused) { step = 0; songPos = 0; loops = 0; countTotal = if (c.size > 1) maxOf(0, c[1] as Int) else 0; countLeft = countTotal }
                countUi = if (countLeft > 0) (countLeft + 3) / 4 else 0
                paused = false; playing = true; freeOn = false
                nextStepAt = frame + .004 * rate
            }
            "pause" -> {
                if (!playing) return
                countUi = 0
                val heard = floor(nowStep(S!!, renderNanos, renderNanos)).toInt()
                val len = lenOf(S, playingPat(S))
                step = maxOf(0, minOf(len - 1, heard + 1))
                playing = false; paused = true
            }
            "stop" -> {
                countLeft = 0; countUi = 0
                if (!playing && !paused) releaseAll(.03f)
                fxStut = false; fxTape = false
                playing = false; paused = false; step = 0; songPos = 0; freeOn = !held.isEmpty() && S!!.live.nr
                hHead = -1
                dropLocks()
            }
            "panic" -> {
                for (v in pool) v.active = false
                for (m in pMod) m.fill(0f)
                for (m in pHome) m.fill(0f)
                for (m in laneHeld) m.fill(false)
                for (m in handOn) m.fill(false)
                for (m in lockOn) m.fill(false)
                for (m in tFx) m.fill(0f)
                restHomes(S)
                held.clear(); freeOn = false; beatOn = false
            }
            "down" -> { lastHitWaitNanos = renderNanos - (c[5] as Long); hits++; padDown(S!!, c[1] as Int, c[2] as Int, c[3] as IntArray, c[4] as Boolean, c[5] as Long, renderNanos, c.size > 6 && (c[6] as Boolean), if (c.size > 7) c[7] as Float else -1f) }
            "cancel" -> {
                val h = held.remove(c[1] as Int) ?: return
                // a true fast fade, even for a held synth or a sustained pad (their own release would linger)
                for (v in h.voices) { val hv = v.hold; v.hold = false; if (v.syn) { v.releasing = false }; startRelease(v, .006f); v.hold = hv }
                if (h.recPat >= 0) { val re = RecEvent(RecEvent.CANCEL, h.recPat, h.pad.key, h.recAt, null, if (h.recFine) 1 else 0); re.hit = h.recId; recs.add(re) }
            }
            "unlatch" -> {
                val h = held.remove(c[1] as Int)
                if (h != null) for (v in h.voices) startRelease(v, .03f)
                if (held.isEmpty()) { arpI = 0; freeOn = false }
            }
            "up" -> padUp(S!!, c[1] as Int, c[2] as Long, renderNanos)
            "preview" -> {
                val s = c[1] as Sample
                if (c.size > 5) { lastHitWaitNanos = renderNanos - (c[5] as Long); hits++ }
                for (v in pool) if (v.active && v.preview) v.active = false
                val hold = c.size > 4 && (c[4] as Boolean)
                var pe = c[2] as SoundEdit
                if (hold) { pe = pe.copy(); pe.loop = true }
                val v = trigger(PadSlot(-1, s.key, s, pe, true), 2.0.pow((c[3] as Int) / 12.0), 0.0, false, .85f, 0, hold, false)
                if (v != null) { v.preview = true; v.dry = true }
            }
            "stopprev" -> for (v in pool) if (v.active && v.preview) startRelease(v, .03f)
            "fx" -> fxSet(S, c[1] as String, c[2] as Boolean)
            "mod" -> {
                // a hand (finger, wheel, mouth, a knob) holds this control until it lets go (build 106)
                val pd = c[1] as Int; val t = c[2] as Int
                if (pd >= 0 && pd < Project.SLOTS && t >= 0 && t < NT) {
                    pHand[pd][t] = c[3] as Float
                    handOn[pd][t] = true
                    modTouch[pd][t] = frame
                    combine(pd, t)
                }
            }
            "rel" -> {
                val pd = c[1] as Int; val t = c[2] as Int
                if (pd >= 0 && pd < Project.SLOTS && t >= 0 && t < NT) { handOn[pd][t] = false; combine(pd, t) }
            }
            "home" -> {
                val pd = c[1] as Int; val t = c[2] as Int
                if (pd >= 0 && pd < Project.SLOTS && t >= 0 && t < NT) { pHome[pd][t] = c[3] as Float; combine(pd, t) }
            }
            "fxt" -> {
                val k = fxIndex(c[1] as String); val t = c[2] as Int
                if (k >= 0 && t >= 0 && t < tFx.size) tFx[t][k] = maxOf(0f, minOf(1f, c[3] as Float))
            }
            "famt" -> { val k = fxIndex(c[1] as String); if (k >= 0) mixAmt[k] = maxOf(0f, minOf(1f, c[2] as Float)) }
            "gatelen" -> { gateSteps = maxOf(1, c[1] as Int); gateHz = 1.0 / (60.0 / maxOf(40, S!!.bpm) / 4 * gateSteps) }
            "nmod" -> {
                val h = held[c[1] as Int]
                if (h != null) for (v in h.voices) { v.bendMul = 2.0.pow((c[2] as Float) / 12.0); v.volMul = maxOf(0f, c[3] as Float) }
            }
            "xy" -> {
                xyOn = c[3] as Boolean
                if (xyOn) {
                    val x = (c[1] as Float).toDouble(); val y = (c[2] as Float).toDouble()
                    filtTarget = if (xyHP) 20 * (6000.0 / 20).pow(x) else 120 * (18000.0 / 120).pow(x)
                    qTarget = .7 + (1 - y) * 14
                    filtSlow = false
                } else { filtTarget = if (xyHP) 10.0 else 20000.0; qTarget = .7; filtSlow = true }
            }
            "pf" -> {
                // a hand on the pad filter (XY, the Padiano strip, the dock filter): cut 1 = open. Let go and the
                // filter goes back to where it rests, not wide open (build 106)
                val pd = c[1] as Int
                if (pd < 0 || pd >= Project.SLOTS) return
                val on = c[4] as Boolean
                val cut = maxOf(0f, minOf(1f, c[2] as Float)); val res = maxOf(0f, minOf(1f, c[3] as Float))
                pHand[pd][T_FILTER] = 1 - cut; pHand[pd][T_RES] = res
                handOn[pd][T_FILTER] = on; handOn[pd][T_RES] = on
                combine(pd, T_FILTER); combine(pd, T_RES)
            }
            "xyhp" -> { xyHP = c[1] as Boolean; filtTarget = if (xyHP) 10.0 else 20000.0; filtFreq = filtTarget; qTarget = .7; filtQ = qTarget; fzL = FloatArray(4); fzR = FloatArray(4) }
        }
    }

    /** Start a voice semis away from its pitch and slide it home over sec seconds. */
    private fun glide(v: Voice, semis: Double, sec: Double) {
        if (abs(semis) < .01) return
        val n = maxOf(1L, jRound(sec * rate))
        val target = v.inc
        v.inc = target * 2.0.pow(semis / 12.0)
        v.glideMul = (target / v.inc).pow(1.0 / n)
        v.glideLeft = n
    }

    /** The playback rate of the newest voice, for tests. */
    fun lastInc(): Double { val v = lastVoice; return if (v == null) 0.0 else v.inc }

    private fun releaseAll(sec: Float) { for (v in pool) if (v.active && !v.preview) startRelease(v, sec) }

    private fun padDown(S: Snapshot, id: Int, pad: Int, pitches: IntArray, mel: Boolean, nanos: Long, renderNanos: Long, latch: Boolean, vel: Float) {
        val ps = (if (pad >= 0 && pad < S.pads.size) S.pads[pad] else null) ?: return
        val h = Held()
        h.id = id; h.pad = ps; h.pitches = pitches; h.mel = mel
        h.latched = latch
        // SE-5: Sustain holds under a finger too, not only under Hold
        h.holdV = (mel && ps.edit.holds()) || "sustain" == ps.edit.held || latch
        // SE-7: a latched beat pad plays each copy whole and starts it again on the beat; nothing to hold
        h.beat = latch && "beat" == ps.edit.held
        // SE-11: a synth pad sounds while it is held, like a key
        if (ps.synth != null && !h.beat) h.holdV = true
        if (h.beat) h.holdV = false
        // SE-6: flow plays each note once, held, and repeat pulses its volume (arpeggios keep cut or ring)
        val L0 = S.live
        h.flow = L0.nr && "flow" == L0.nrStyle && "rep" == L0.arp
        if (h.flow) h.holdV = true
        h.at = if (playing) nowStep(S, nanos, renderNanos) else 0.0
        val old = held.remove(id)
        if (old != null) for (v in old.voices) startRelease(v, .02f)
        // glide: slide in from the pad's last note (legato: only while another note of this pad is down)
        var from = Double.NaN
        val gT = if (pad >= 0 && pad < Project.SLOTS && pMod[pad][T_GLIDE] > .001) pMod[pad][T_GLIDE].toDouble() else if (ps.opts.glide) ps.opts.glideT else 0.0
        if (mel && pad >= 0 && pad < lastP.size && gT > .001 && !lastP[pad].isNaN()) {
            var overlap = false
            for (o in held.values) if (o.pad.index == pad) { overlap = true; break }
            if (!ps.opts.legato || overlap) from = lastP[pad]
        }
        if (mel && pad >= 0 && pad < lastP.size && pitches.size > 0) lastP[pad] = pitches[0].toDouble()
        held[id] = h
        val L = S.live
        val arpLive = L.nr && "rep" != L.arp
        if ((!(L.nr && playing) && !arpLive) || h.flow) {
            var g = if (mel) Theory.chordGain(pitches.size) else .85f
            // build 108: how hard it was hit; a plain tap stays where it always was
            if (vel >= 0) g *= velGain(vel)
            sustainNow = h.flow
            for (p in pitches) {
                val v = trigger(ps, oct() * 2.0.pow(p / 12.0), 0.0, false, g, 0, h.holdV) ?: continue
                if (h.flow) { v.flow = true; v.pulseAt = frame; v.pulseLen = jRound(maxOf(.25, L.nrRate) * spb(S)) }
                // Hold keeps a one-shot going: loop its trim, or freeze its tail
                if (!from.isNaN()) glide(v, from + (p - pitches[0]) - p, gT)
                val hm = ps.edit.held
                if (latch && !v.loop && !h.flow && "once" != hm && "sustain" != hm && "beat" != hm) {
                    v.loop = true
                    if ("tail" == ps.edit.held) v.loopFrom = maxOf(v.len * .7, v.len - .12 * ps.sample.rate)
                }
                h.voices.add(v)
            }
            sustainNow = false
        }
        if (h.beat && !playing && !beatOn) { beatOn = true; beatNextAt = frame + 4 * spb(S) }
        if (L.nr && !playing && !freeOn) {
            freeOn = true
            freeNextAt = frame + maxOf(.25, L.nrRate) * spb(S)
            // the press itself was repeat 0 (an arpeggio plays its first note now, as repeat 0)
            nrFreeI = if (arpLive) 0L else 1L
            if (arpLive) nrFree(S)
        }
        if (L.rec && playing && !L.nr && !mel && h.at >= -.5) {
            val pi = playingPat(S)
            if (pi >= 0) {
                val len = S.pats[pi].len
                if (L.recExact) {
                    // Rec set to exact (Nov 18): the hit lands on the finer grid where it was played
                    val tick = jRound(h.at * Project.TICKS).toInt().mod(len * Project.TICKS)
                    val re = RecEvent(RecEvent.OFF, pi, ps.key, tick, null, 1); h.recId = ++hitSerial; re.hit = h.recId; recs.add(re)
                    h.recPat = pi; h.recAt = tick; h.recFine = true
                } else {
                    val st = jRound(h.at).toInt().mod(len)
                    val re = RecEvent(RecEvent.STEP, pi, ps.key, st, null, 1, vel); h.recId = ++hitSerial; re.hit = h.recId; recs.add(re)
                    h.recPat = pi; h.recAt = st; h.recFine = false
                }
            }
        }
    }

    private fun padUp(S: Snapshot, id: Int, nanos: Long, renderNanos: Long) {
        val h0 = held[id]
        if (h0 == null || h0.latched) return
        val h = held.remove(id)!!
        if (h.holdV) for (v in h.voices) {
            if (v.syn && h.pad.synth != null && h.pad.synth!!.get("mode").toInt() != 0) {
                // mono: if another key of this pad is still down, slide back to it instead of stopping
                var back: Held? = null
                for (o in held.values) if (o.pad.index == h.pad.index && o.voices.contains(v)) back = o
                if (back != null && back.pitches.size > 0) {
                    v.sv.noteOn(h.pad.synth!!, 261.6256 * oct() * 2.0.pow(back.pitches[0] / 12.0) * 2.0.pow((h.pad.edit.tune + h.pad.edit.fine / 100.0) / 12.0), v.sv.velocity(), rate, true)
                    continue
                }
            }
            release(v)
        }
        val L = S.live
        if (L.rec && playing && h.mel && !L.nr) {
            val pi = playingPat(S)
            if (pi >= 0) {
                val len = S.pats[pi].len; val q = jRound(h.at).toInt().mod(len)
                val l = maxOf(1L, minOf((len - q).toLong(), jRound(nowStep(S, nanos, renderNanos) - h.at))).toInt()
                recs.add(RecEvent(RecEvent.NOTES, pi, h.pad.key, q, h.pitches, l))
            }
        }
        if (held.isEmpty()) { arpI = 0; freeOn = false }
    }

    /** Repeats played while stopped since the first note went down, for the pattern. */
    private var nrFreeI = 0L

    private fun fireFree(S: Snapshot) {
        if (!S.live.nr || held.isEmpty()) { freeOn = false; return }
        nrFree(S)
        freeNextAt += maxOf(.25, S.live.nrRate) * spb(S)
    }

    private fun nrFree(S: Snapshot) {
        val dur = maxOf(.25, S.live.nrRate) * spb(S) / rate * .9
        // the pattern's rests: nothing new starts (cut notes have already stopped, ring and flow notes play on)
        if (!S.live.patOn(nrFreeI++)) return
        if ("rep" != S.live.arp) {
            if (arpNext(S)) trigger(arpHeld!!.pad, oct() * 2.0.pow(arpPitch / 12.0), dur, true, .85f, 0, false)
            return
        }
        val ring = "ring" == S.live.nrStyle
        for (h in held.values) {
            if (h.flow) { pulse(h, 0, jRound(maxOf(.25, S.live.nrRate) * spb(S))); continue }
            val g = Theory.chordGain(h.pitches.size)
            for (p in h.pitches) trigger(h.pad, oct() * 2.0.pow(p / 12.0), if (ring) 0.0 else dur, !ring && (h.mel || p != 0), g, 0, false)
        }
    }

    /** SE-6 flow: one repeat hit is a fresh pulse of volume on the notes already playing. */
    private fun pulse(h: Held, delay: Long, len: Long) {
        for (v in h.voices) if (v.active && v.flow) { v.pulseAt = frame + delay; v.pulseLen = maxOf(1L, len) }
    }

    // the arpeggio's notes, sorted low to high in place (no memory made on the audio thread)
    private val arpH = arrayOfNulls<Held>(256)
    private val arpP = IntArray(256)
    private var arpHeld: Held? = null
    private var arpPitch = 0

    /** Picks the next arpeggio note into arpHeld / arpPitch; false when nothing is held. */
    private fun arpNext(S: Snapshot): Boolean {
        var n = 0
        for (h in held.values) for (p in h.pitches) {
            if (n >= arpP.size) break
            var j = n++
            while (j > 0 && arpP[j - 1] > p) { arpP[j] = arpP[j - 1]; arpH[j] = arpH[j - 1]; j-- }
            arpP[j] = p; arpH[j] = h
        }
        if (n == 0) return false
        var i: Int
        val m = S.live.arp
        if ("down" == m) i = n - 1 - arpI % n
        else if ("updown" == m) { val per = maxOf(1, 2 * n - 2); val x = arpI % per; i = if (x < n) x else per - x }
        else if ("rand" == m) i = rnd.nextInt(n)
        else i = arpI % n
        arpI++
        i = maxOf(0, minOf(n - 1, i))
        arpHeld = arpH[i]; arpPitch = arpP[i]
        arpH.fill(null, 0, n)
        return true
    }

    /** Build 6 of the overhaul: how many times the loop has come round since Play, for the 1:2 and 1:4 steps. */
    @JvmField var loops = 0
    private val dice = JavaRandom(11)

    /** Does a step with this chance play this time? */
    internal fun plays(c: Int): Boolean {
        return when (c) {
            101 -> loops % 2 == 0
            102 -> loops % 2 == 1
            103 -> loops % 4 == 0
            104 -> loops % 4 == 3
            else -> c >= 100 || dice.nextInt(100) < c
        }
    }

    private var countLeft = 0; private var countTotal = 0; private var hitSerial = 0
    /** Beats of count-in still to come (4, 3, 2, 1), 0 when the loop is running. Read by the screen. */
    @Volatile @JvmField var countUi = 0

    private fun fireStep(S: Snapshot) {
        if (countLeft > 0) {
            // the count-in: a click on every beat, the first one high, and the loop waits at its top
            val k = countTotal - countLeft
            val delay = maxOf(0L, jRound(nextStepAt - frame))
            if (k % 4 == 0) { val cv = trigger(if (k % 16 == 0) clickHiSlot else clickLoSlot, 1.0, 0.0, false, 1f, delay, false, false); if (cv != null) cv.dry = true; countUi = (countLeft + 3) / 4; beatTick(delay) }
            val LC = S.live
            if (LC.nr && !held.isEmpty()) {
                val every = maxOf(.25, LC.nrRate); val spbC = spb(S)
                val sc = k % 16
                var m = ceil(sc / every - 1e-9).toLong()
                while (m * every < sc + 1 - 1e-9) {
                    val off = m * every - sc
                    nrTick(S, delay + jRound(off * spbC), sc, -1, off > 1e-6, every, spbC, m)
                    m++
                }
            }
            countLeft--
            if (countLeft == 0) countUi = 0
            nextStepAt += spb(S)
            return
        }
        val pi = playingPat(S)
        val P = if (pi >= 0) S.pats[pi] else null
        val len = if (P != null) P.len else 16
        if (step >= len) step = 0
        if (stopAfter >= 0 && fired >= stopAfter) { playing = false; return }
        var spb = spb(S); var rateMul = oct()
        var mute = false
        if (fxTape) {
            val f = minOf(1.0, maxOf(0.0, (nextStepAt - tapeAt) / (1.1 * rate)))
            rateMul *= 1 - .82 * f
            spb = spb / maxOf(.18, 1 - .82 * f)
            mute = f >= .97
        }
        var s = step
        if (fxStut) { s = stutA + (step - stutB).mod(2); if (s >= len) s = 0 }
        lastStep = s
        val s0 = s
        val at = nextStepAt - frame + (if (s % 2 == 1) S.swing / 100.0 * spb * .5 else 0.0)
        val delay = maxOf(0L, jRound(at))
        tapeNow = fxTape
        if (P != null && !mute) {
            for (t in P.tracks) {
                if (!t.pad.audible) continue
                val ti = t.pad.index; var ts = s0
                if (ti >= 0 && ti < Project.SLOTS && padStut[ti] && !fxStut) { ts = pStutA[ti] + (step - pStutB[ti]).mod(2); if (ts >= len) ts = 0 }
                // step locks: the locked controls take this step's value, and let go again on a step without one
                val lockT = t.lockT
                if (lockT != null && ti >= 0 && ti < Project.SLOTS) for (q in 0 until lockT.size) {
                    val lockV = t.lockV!!
                    val lt = lockT[q]; val lv = if (ts < lockV[q].size) lockV[q][ts] else Float.NaN
                    val on = !lv.isNaN() && t.steps[ts]
                    if (on || lockOn[ti][lt]) { lockOn[ti][lt] = on; if (on) lockVal[ti][lt] = lv; combine(ti, lt) }
                }
                if (t.steps[ts] && (t.chance == null || plays(t.chance!![ts].toInt()))) trigger(t.pad, rateMul, t.gap[ts] * spb / rate, false, t.gain!![ts], delay, false)
                // hits between the sixteenths, at their tick inside this step
                val offs = t.offs
                if (offs != null && ts < offs.size) for (tk in offs[ts]) trigger(t.pad, rateMul, spb / rate, false, .85f, delay + jRound(tk * spb / Project.TICKS), false)
                val nt = t.notes[ts]
                var k = 0
                while (k < nt.size) { trigger(t.pad, rateMul * 2.0.pow(nt[k] / 12.0), (if (fxStut) minOf(nt[k + 1], 2) else nt[k + 1]) * spb / rate, true, .78f, delay, false); k += 2 }
            }
        }
        tapeNow = false
        // recorded XY moves play back, unless a finger is moving that control right now
        laneSerial++
        if (P != null) for (q in 0 until P.lanePad.size) {
            val lp = P.lanePad[q]; val lt = P.laneT[q]
            if (lp < 0 || lp >= Project.SLOTS || lt < 0 || lt >= NT) continue
            laneSeen[lp][lt] = laneSerial
            val v = P.laneV[q][s]
            if (v.isNaN()) continue  // between marks a lane holds its last value
            laneVal[lp][lt] = v
            laneHeld[lp][lt] = true
            combine(lp, lt)  // a hand on the same control still wins until it lets go
        }
        // build 105: where a lane stops (a cleared lane, a loop without it) the control goes back to where it rests,
        // instead of staying stuck at the lane's last value
        for (lp in 0 until Project.SLOTS) for (lt in 0 until NT) {
            if (!laneHeld[lp][lt] || laneSeen[lp][lt] == laneSerial) continue
            laneHeld[lp][lt] = false
            combine(lp, lt)
        }
        if (s % 4 == 0) beatTick(delay)
        val L = S.live
        if (L.click && s % 4 == 0) { val cv = trigger(if (s == 0) clickHiSlot else clickLoSlot, 1.0, 0.0, false, 1f, delay, false, false); if (cv != null) cv.dry = true }
        if (L.nr && !held.isEmpty()) {
            // any rate, straight, triplet or dotted: repeat m falls at m x rate steps from the top of the loop, so a
            // triplet lands between the sixteenths at its exact time (note #18). Straight rates fire as before.
            val every = maxOf(.25, L.nrRate)
            val m0 = ceil(s / every - 1e-9).toLong()
            var m = m0
            while (m * every < s + 1 - 1e-9) {
                val off = m * every - s
                nrTick(S, delay + jRound(off * spb), s, pi, off > 1e-6, every, spb, m)
                m++
            }
        }
        val h = hHead + 1
        val i = h % hFrame.size
        hFrame[i] = frame + delay; hPat[i] = pi; hStep[i] = s; hSong[i] = songPos
        hHead = h
        fired++
        nextStepAt += spb
        step++
        if (step >= len) {
            step = 0
            loops++
            if (S.songMode) songPos = (songPos + 1) % maxOf(1, S.songLen)
        }
    }

    /** SE-7: on each beat, latched beat pads start their sound again every beatN beats; the last copy rings on. */
    private fun beatTick(delay: Long): Boolean {
        var any = false
        for (h in held.values) {
            if (!h.beat) continue
            any = true
            h.beats++
            val n = maxOf(1, h.pad.edit.beatN)
            if (h.beats % n != 0) continue
            val g = if (h.mel) Theory.chordGain(h.pitches.size) else .85f
            for (p in h.pitches) trigger(h.pad, oct() * 2.0.pow(p / 12.0), 0.0, false, g, delay, false)
        }
        return any
    }

    private fun fireBeat(S: Snapshot) {
        if (!beatTick(0)) { beatOn = false; return }
        beatNextAt += 4 * spb(S)
    }

    private fun nrTick(S: Snapshot, delay: Long, s: Int, pi: Int, half: Boolean, every: Double, spb: Double, idx: Long) {
        // while playing the pattern counts from the start of the loop, so it sits on the beat the same way each time
        if (!S.live.patOn(idx)) return
        if ("rep" == S.live.arp) {
            for (h in held.values) {
                if (h.flow) { pulse(h, delay, jRound(spb * every)); continue }
                val g = Theory.chordGain(h.pitches.size)
                for (p in h.pitches) nrOne(S, h, p, delay, s, pi, half, g, every, spb)
            }
        } else {
            if (arpNext(S)) nrOne(S, arpHeld!!, arpPitch, delay, s, pi, half, .85f, every, spb)
        }
    }

    private fun nrOne(S: Snapshot, h: Held, p: Int, delay: Long, s: Int, pi: Int, half: Boolean, g: Float, every: Double, spb: Double) {
        val ring = "ring" == S.live.nrStyle
        trigger(h.pad, oct() * 2.0.pow(p / 12.0), if (ring) 0.0 else spb * .9 * (if (every == .5) 1.0 else every) / rate, !ring && (h.mel || p != 0), g, delay, false)
        if (S.live.rec && pi >= 0 && !half) {
            if (h.mel) recs.add(RecEvent(RecEvent.NOTES, pi, h.pad.key, s, intArrayOf(p), if (every >= 2) 2 else 1))
            else recs.add(RecEvent(RecEvent.STEP, pi, h.pad.key, s, null, 1))
        } else if (S.live.rec && pi >= 0 && !h.mel) {
            // a triplet or dotted repeat between the sixteenths lands on the finer grid (Nov 18)
            val tick = jRound((s + (delay - jRound(nextStepAt - frame)).toDouble() / spb) * Project.TICKS)
            recs.add(RecEvent(RecEvent.OFF, pi, h.pad.key, tick.toInt(), null, 1))
        }
    }

    /* ---------- starting one voice, voiceEd() from the site ---------- */

    private fun trigger(ps: PadSlot, rateMul: Double, durSec: Double, gate: Boolean, gain: Float, delay: Long, hold: Boolean): Voice? {
        val flip = fxRev || (ps.index >= 0 && ps.index < Project.SLOTS && padRev[ps.index])
        return trigger(ps, rateMul, durSec, gate, gain, delay, hold, flip)
    }

    private fun trigger(ps: PadSlot, rateMul: Double, durSec: Double, gate: Boolean, gain: Float, delay: Long, hold: Boolean, flip: Boolean): Voice? {
        val e = ps.edit
        val s = ps.sample
        val N = s.data.size
        if (N < 2) return null
        if (ps.synth != null) return synthTrigger(ps, rateMul, durSec, gate, gain, delay, hold)
        val rev = e.rev != flip
        var d = if (rev) s.reversedData() else s.data
        var a = e.st * N; var z = e.en * N
        val pm = if (ps.index >= 0 && ps.index < Project.SLOTS) pMod[ps.index] else null
        if (pm != null && pm[T_START] > 0) a = minOf(z - .005 * s.rate, a + (z - a) * minOf(.95, pm[T_START].toDouble()))
        if (rev) { val a2 = N - z; z = N - a; a = a2 }
        val len = maxOf(.005 * s.rate, z - a)
        val tuneR = 2.0.pow((e.tune + e.fine / 100.0) / 12.0)
        var r = maxOf(.05, rateMul * tuneR)
        val lvl = gain * e.vol * (if (e.norm) minOf(10.0, .95 / s.regionPeak(e.st, e.en)) else 1.0)
        // SE-1: Stretch (tape) moves time at the pitch's speed; Grain keeps time at 1; auto keeps the length of notes
        // played as keys, chords and roll notes, and lets the Doctor's own Tune stay tape. Tape stop is always tape.
        val sustain = hold && ("sustain" == e.held || sustainNow)
        var timeR: Double
        if (tapeNow || "stretch" == e.pitch) timeR = r
        else if ("grain" == e.pitch) timeR = 1.0
        else timeR = if (abs(rateMul - 1) > .0006) maxOf(.05, tuneR) else r
        // SE-4: a loop plays at the mess's tempo; Grain keeps its pitch (Stretch and tape stop change it, like tape)
        if (s.bpm > 0 && spbNow > 0) {
            val fit = rate * 15.0 / spbNow / s.bpm
            if (abs(fit - 1) > 1e-4) { if (tapeNow || "stretch" == e.pitch) { r *= fit; timeR *= fit } else timeR *= fit }
        }
        var grain = sustain || abs(timeR - r) > 1e-9
        // SE-3: a baked version already has the new pitch at the old length, so it plays like tape at the time rate
        var incR = r
        if (grain && !sustain && !rev) {
            val bk = s.bakedFor(r / timeR)
            if (bk != null && bk.size == N) { d = bk; incR = timeR; grain = false; bakedPlays++ }
        }
        if (grain && !sustain && ps.index >= 0 && ps.index < Project.SLOTS) grainUsed[ps.index] = true
        val natural = len / s.rate / timeR
        val g = "gate" == e.mode || gate
        val play = if (hold) 600.0 else if (e.loop) (if (durSec > 0) durSec else natural) else (if (g && durSec > 0) minOf(durSec, natural) else natural)
        val att = minOf(e.att + (if (pm != null) maxOf(0f, pm[T_ATT]) else 0f), play * .9)
        val fade = minOf(maxOf(e.rel + (if (pm != null) maxOf(0f, pm[T_REL]) else 0f), .004), maxOf(.002, play - att))

        val v = freeVoice()
        v.active = true; v.preview = false; v.dry = false; v.hold = hold; v.releasing = false; v.choked = false
        v.d = d; v.a = maxOf(0.0, floor(a)).toInt(); v.len = len; v.zEnd = minOf(N.toDouble(), ceil(a + len)).toInt()
        v.pos = 0.0; v.inc = incR * s.rate / rate; v.loop = e.loop; v.loopFrom = 0.0; v.glideLeft = 0; v.glideMul = 1.0
        v.grain = grain; v.frozen = false; v.flow = false; v.pulseAt = -1; v.srate = s.rate.toDouble(); v.syn = false
        if (grain) {
            v.tPos = 0.0; v.tInc = timeR * s.rate / rate
            v.gStep = 1.0 / maxOf(64.0, GRAIN_SEC * rate)
            // the first head starts at its loudest so the attack is not faded in; the second waits half a grain
            v.g0 = 0.0; v.gp0 = .5; v.g1 = 0.0; v.gp1 = 0.0
            // SE-5: where a held sustain freezes, partway in, after the attack
            v.sus = if (sustain) minOf(maxOf(minOf(.06 * s.rate, len * .45), len * .45), 1.5 * s.rate) else -1.0
        }
        lastVoice = v
        v.delay = delay; v.t = 0
        v.playLen = maxOf(1L, jRound(play * rate)); v.attF = jRound(att * rate); v.fadeF = maxOf(1L, jRound(fade * rate))
        v.lvl = lvl.toFloat()
        val x = (e.pan + 1) / 2
        v.gl = minOf(1.0, sqrt(2.0) * cos(x * PI / 2)).toFloat()
        v.gr = minOf(1.0, sqrt(2.0) * sin(x * PI / 2)).toFloat()
        v.pad = ps.index; v.choke = e.choke; v.startAbs = frame + delay; v.chokeAt = Long.MAX_VALUE; v.chokeG = 1f; v.lastEnv = 0f
        v.relLeft = 0; v.relLen = 1; v.relFrom = 0f
        v.py2 = 0f; v.py1 = v.py2; v.px2 = v.py1; v.px1 = v.px2
        v.fxRvb = e.rvb.toFloat(); v.fxEcho = e.echo.toFloat(); v.fxDirt = e.dirt.toFloat(); v.fxCrush = e.crush.toFloat()
        v.fxChorus = e.chorus.toFloat(); v.fxWob = e.wob.toFloat(); v.fxWobRate = e.wobRate.toFloat()
        v.wobPh = 0.0; v.chPh = 0.0; v.chW = 0; v.crushCnt = 0; v.crushVal = 0f; v.bendMul = 1.0; v.volMul = 1f; v.panX = e.pan
        v.chBuf?.fill(0f)
        if (e.rvb > 0 || (ps.index >= 0 && ps.index < Project.SLOTS && padRvb[ps.index] > 0)) rvbSizeT = e.rvbSize.toFloat()
        if (e.echo > 0 || (ps.index >= 0 && ps.index < Project.SLOTS && padEcho[ps.index] > 0)) sEchoDiv = e.echoT
        triggers++
        setFilter(v, e.tone)
        if (e.choke > 0) {
            for (o in pool) if (o !== v && o.active && !o.preview && o.choke == e.choke && o.startAbs < v.startAbs) o.chokeAt = minOf(o.chokeAt, v.startAbs)
        }
        return v
    }

    /**
     * SE-11: start a synth note. Pitch 0 is middle C. Mono and Acid keep one voice per pad: a note that arrives
     * while the last one's gate is open slides there instead of starting over.
     */
    private fun synthTrigger(ps: PadSlot, rateMul: Double, durSec: Double, gate: Boolean, gain: Float, delay: Long, hold: Boolean): Voice {
        val e = ps.edit
        val sp = ps.synth!!
        val hz = 261.6256 * rateMul * 2.0.pow((e.tune + e.fine / 100.0) / 12.0)
        val mode = sp.get("mode").toInt()
        val spbSec = spbNow / rate
        // how long the gate stays open: held notes until let go; Roll notes their length (acid a hair longer, so
        // touching notes slide); steps one step (acid half, for the bounce); a ring with no length, its own decay
        val gateSec: Double
        if (hold) gateSec = -1.0
        else if (durSec > 0 && gate) gateSec = if (mode == 2) durSec + .004 else durSec * .97
        else if (durSec > 0) gateSec = minOf(durSec, spbSec) * (if (mode == 2) .5 else .9)
        else gateSec = Synth.ringSec(sp)
        var v: Voice? = null
        var slide = false
        if (mode != 0) {
            for (o in pool) {
                if (o.active && o.syn && !o.preview && o.pad == ps.index && o.chokeAt == Long.MAX_VALUE) {
                    if (o.sv.gate && (o.synOff < 0 || o.t < o.synOff) && delay <= 0) { v = o; slide = true }
                    else if (v == null) { startRelease(o, .004f) }
                }
            }
        }
        if (v != null) {
            // the same voice slides to the new note; its gate now ends with this one
            v.sv.source(ps.grainSrc)
            v.sv.noteOn(sp, hz, gain, rate, true)
            v.hold = hold
            v.synOff = if (gateSec < 0) -1 else v.t + jRound(gateSec * rate)
            v.playLen = if (gateSec < 0) v.t + 600L * rate else v.synOff + jRound(v.sv.releaseSec() * rate)
            lastVoice = v
            triggers++
            return v
        }
        v = freeVoice()
        v.active = true; v.preview = false; v.dry = false; v.hold = hold; v.releasing = false; v.choked = false
        v.syn = true; v.grain = false; v.frozen = false; v.flow = false; v.pulseAt = -1; v.loop = false
        v.d = ps.sample.data; v.a = 0; v.len = 1.0; v.zEnd = v.d!!.size; v.pos = 0.0; v.loopFrom = 0.0
        v.inc = 1.0; v.glideLeft = 0; v.glideMul = 1.0; v.srate = rate.toDouble()
        v.sv.source(ps.grainSrc)
        v.sv.noteOn(sp, hz, gain, rate, false)
        v.synOff = if (gateSec < 0) -1 else jRound(gateSec * rate)
        v.playLen = if (gateSec < 0) 600L * rate else v.synOff + jRound(v.sv.releaseSec() * rate)
        v.attF = 0; v.fadeF = maxOf(1L, jRound(.003 * rate))
        v.lvl = e.vol.toFloat()
        lastVoice = v
        v.delay = delay; v.t = 0
        val x = (e.pan + 1) / 2
        v.gl = minOf(1.0, sqrt(2.0) * cos(x * PI / 2)).toFloat()
        v.gr = minOf(1.0, sqrt(2.0) * sin(x * PI / 2)).toFloat()
        v.pad = ps.index; v.choke = e.choke; v.startAbs = frame + delay; v.chokeAt = Long.MAX_VALUE; v.chokeG = 1f; v.lastEnv = 0f
        v.relLeft = 0; v.relLen = 1; v.relFrom = 0f
        v.py2 = 0f; v.py1 = v.py2; v.px2 = v.py1; v.px1 = v.px2
        v.fxRvb = e.rvb.toFloat(); v.fxEcho = e.echo.toFloat(); v.fxDirt = e.dirt.toFloat(); v.fxCrush = e.crush.toFloat()
        v.fxChorus = e.chorus.toFloat(); v.fxWob = e.wob.toFloat(); v.fxWobRate = e.wobRate.toFloat()
        v.wobPh = 0.0; v.chPh = 0.0; v.chW = 0; v.crushCnt = 0; v.crushVal = 0f; v.bendMul = 1.0; v.volMul = 1f; v.panX = e.pan
        v.chBuf?.fill(0f)
        if (e.rvb > 0 || (ps.index >= 0 && ps.index < Project.SLOTS && padRvb[ps.index] > 0)) rvbSizeT = e.rvbSize.toFloat()
        if (e.echo > 0 || (ps.index >= 0 && ps.index < Project.SLOTS && padEcho[ps.index] > 0)) sEchoDiv = e.echoT
        triggers++
        setFilter(v, e.tone)
        if (e.choke > 0) {
            for (o in pool) if (o !== v && o.active && !o.preview && o.choke == e.choke && o.startAbs < v.startAbs) o.chokeAt = minOf(o.chokeAt, v.startAbs)
        }
        return v
    }

    private fun setFilter(v: Voice, tone: Double) {
        v.y2 = 0f; v.y1 = v.y2; v.x2 = v.y1; v.x1 = v.x2
        if (tone == 0.0) { v.filt = false; return }
        val lp = tone < 0
        var f = if (lp) 18000 * (250.0 / 18000).pow(-tone) else 20 * (3000.0 / 20).pow(tone)
        f = minOf(f, rate * .45)
        val w = 2 * PI * f / rate; val cs = cos(w); val al = sin(w) / (2 * .7071)
        val b0: Double; val b1: Double; val b2: Double; val a0 = 1 + al; val a1 = -2 * cs; val a2 = 1 - al
        if (lp) { b0 = (1 - cs) / 2; b1 = 1 - cs; b2 = (1 - cs) / 2 }
        else { b0 = (1 + cs) / 2; b1 = -(1 + cs); b2 = (1 + cs) / 2 }
        v.b0 = (b0 / a0).toFloat(); v.b1 = (b1 / a0).toFloat(); v.b2 = (b2 / a0).toFloat(); v.a1 = (a1 / a0).toFloat(); v.a2 = (a2 / a0).toFloat()
        v.filt = true
    }

    @JvmField var stolen = 0

    private fun shedVoice() {
        var n = 0
        for (v in pool) if (v.active && v.chokeAt == Long.MAX_VALUE) n++
        if (n < VOICE_CAP) return
        var q: Voice? = null; var ql = Float.MAX_VALUE
        for (v in pool) {
            if (!v.active || v.preview || v.chokeAt != Long.MAX_VALUE || v.startAbs > frame) continue
            // fading voices go first, then the quietest of the rest
            val l = abs(v.lastEnv) * v.lvl * (if (v.releasing) .25f else 1f) * (if (v.hold) 4f else 1f)
            if (l < ql) { ql = l; q = v }
        }
        if (q != null) { q.chokeAt = frame; stolen++ }
    }

    private fun freeVoice(): Voice {
        shedVoice()
        var best: Voice? = null
        for (v in pool) if (!v.active) return v
        for (v in pool) if (!v.preview && (best == null || v.t > best.t)) best = v
        return best ?: pool[0]
    }

    private fun release(v: Voice) {
        if (!v.active) return
        startRelease(v, 0f)
    }

    private fun startRelease(v: Voice, minSec: Float) {
        if (!v.active || v.releasing) return
        if (v.delay > 0) { v.active = false; return }
        if (v.syn && minSec <= 0) {
            // a synth lets go through its own release; the engine's fade only tidies the very end
            v.sv.noteOff()
            v.synOff = -1
            v.playLen = minOf(v.playLen, v.t + jRound(v.sv.releaseSec() * rate))
            return
        }
        v.releasing = true
        v.relLen = maxOf(1L, jRound(maxOf(if (minSec > 0) minSec.toDouble() else .015, if (v.hold) relOf(v) else minSec.toDouble()) * rate))
        v.relLeft = v.relLen
        v.relFrom = v.lastEnv
    }

    private fun relOf(v: Voice): Double = maxOf(.015, v.fadeF / rate.toDouble())

    /* ---------- mixing ---------- */

    private fun mix(out: FloatArray, off: Int, n: Int) {
        val chokeK = exp(-1.0 / (.006 * rate)).toFloat()
        val gateDiv = maxOf(1.0, spbNow * gateSteps)
        val SN = snap
        for (v in pool) {
            if (!v.active) continue
            if (v.syn && SN != null && v.pad >= 0 && v.pad < SN.pads.size) {
                // knob turns reach notes already sounding
                val cp = SN.pads[v.pad]
                if (cp != null && cp.synth != null && cp.synth.kind === v.sv.patch?.kind) v.sv.read(cp.synth)
            }
            var k = 0
            if (v.delay > 0) {
                val skip = minOf(v.delay, n.toLong())
                v.delay -= skip
                k = skip.toInt()
                if (v.delay > 0) continue
            }
            val d = v.d!!
            val p = if (v.pad >= 0 && v.pad < Project.SLOTS) v.pad else -1
            // what this pad adds right now (XY, per-pad and per-bus effects)
            var dirt = v.fxDirt + (if (p >= 0) padDirt[p] else 0f); var crush = v.fxCrush + (if (p >= 0) padCrush[p] else 0f)
            var chorus = v.fxChorus + (if (p >= 0) padChorus[p] else 0f); var wob = v.fxWob + (if (p >= 0) padWob[p] else 0f)
            val gate = if (p >= 0) padGate[p] else 0f; val ring = if (p >= 0) padRing[p] else 0f
            val rvbS = minOf(1f, v.fxRvb + (if (p >= 0) padRvb[p] else 0f)); val echoS = minOf(1f, v.fxEcho + (if (p >= 0) padEcho[p] else 0f))
            val vol = (if (p >= 0) padVol[p] else 1f) * v.volMul
            var gl = v.gl; var gr = v.gr
            if (p >= 0 && padPan[p] != 0f) {
                val x = maxOf(0.0, minOf(1.0, (v.panX + padPan[p] + 1) / 2))
                gl = minOf(1.0, sqrt(2.0) * cos(x * PI / 2)).toFloat()
                gr = minOf(1.0, sqrt(2.0) * sin(x * PI / 2)).toFloat()
            }
            if (dirt > 1) dirt = 1f; if (crush > 1) crush = 1f; if (chorus > 1) chorus = 1f; if (wob > 1) wob = 1f
            val pump = if (p >= 0) padPump[p] else 0f; val warm = if (p >= 0) padWarm[p] else 0f; val phaser = if (p >= 0) padPhaser[p] else 0f
            val freeze = p >= 0 && padFreeze[p] > .5f
            if (!freeze) v.frzA = -1.0
            // where we are in the beat, for Pump: steps since the loop's top, a beat is four steps
            val spbV = if (spbNow > 0) spbNow else rate * 60.0 / 120 / 4
            val k0 = k
            // the phaser's sweep moves slowly, so once a block is enough
            var phC = 0f
            if (phaser > 0) { phC = (.55 + .4 * sin(v.phPh)).toFloat(); v.phPh += 2 * PI * .35 * (n - k) / rate }
            val beat0 = if (playing) (this.step - countLeft - (nextStepAt - (frame + k)) / spbV) else (frame + k) / spbV
            val drive = 1 + dirt * 9; val driveN = (1 / tanh(drive.toDouble())).toFloat()
            val hold = 1 + (crush * 11).toInt()
            val step = if (crush > 0) 2.0.pow((-(15 - crush * 12)).toDouble()).toFloat() else 0f
            if (chorus > 0 && v.chBuf == null) v.chBuf = FloatArray(2048)
            val wobInc = 2 * PI * (.3 + v.fxWobRate * 5.7) / rate; val chInc = 2 * PI * .8 / rate
            val dst = if (v.dry) dryBuf else out
            while (k < n) {
                // envelope
                var env: Float
                if (v.releasing) {
                    if (v.relLeft <= 0) { v.active = false; break }
                    env = v.relFrom * (v.relLeft / v.relLen.toFloat())
                    v.relLeft -= 1  // not v.relLeft--: Kotlin boxes the unused old value there
                } else {
                    if (v.t >= v.playLen) { v.active = false; break }
                    if (v.attF > 0 && v.t < v.attF) env = v.lvl * (v.t / v.attF.toFloat())
                    else if (v.t >= v.playLen - v.fadeF) env = v.lvl * ((v.playLen - v.t) / v.fadeF.toFloat())
                    else env = v.lvl
                    v.lastEnv = env
                }
                if (v.chokeAt != Long.MAX_VALUE && frame + k >= v.chokeAt) {
                    v.chokeG *= chokeK
                    if (v.chokeG < 1e-4f || frame + k - v.chokeAt > .05 * rate) { v.active = false; break }
                    env *= v.chokeG
                }
                // read the sound
                var smp: Float
                if (v.syn) {
                    if (v.t == v.synOff) v.sv.noteOff()
                    var sm = v.inc * v.bendMul
                    if (p >= 0) sm *= padRateFrom[p] + (padRateTo[p] - padRateFrom[p]) * ((frame + k - blockStart) / maxOf(1, blockLen).toDouble())
                    if (wob > 0) sm *= 1 + wob * .025 * sin(v.wobPh)
                    smp = v.sv.next(sm)
                    if (v.sv.done()) { v.active = false; break }
                } else if (v.grain) {
                    if (!v.loop && !v.frozen && v.tPos >= v.len) { v.active = false; break }
                    smp = HANN[(v.gp0 * 1024).toInt()] * gRead(v, d, v.g0) + HANN[(v.gp1 * 1024).toInt()] * gRead(v, d, v.g1)
                } else {
                    var pos = v.pos
                    if (v.loop) { val span = maxOf(1.0, v.len - v.loopFrom); while (pos >= v.len) pos -= span; v.pos = pos }
                    var i = v.a + pos.toInt()
                    if (i >= v.zEnd - 1 || i >= d.size - 1) {
                        if (!v.loop) { v.active = false; break }
                        i = minOf(v.zEnd, d.size) - 2
                    }
                    val fr = pos - floor(pos)
                    smp = (d[i] + (d[i + 1] - d[i]) * fr).toFloat()
                }
                // SE-6 flow: repeat pulses the volume of one long note instead of restarting it
                if (v.flow) {
                    val pf: Float
                    if (v.pulseAt < 0) pf = .12f
                    else {
                        // a 2 ms rise from the floor to full, then a fall back to the floor over the pulse
                        val dt = frame + k - v.pulseAt
                        if (dt < 0) pf = .12f
                        else if (dt < 96) pf = .12f + .88f * (dt / 96f)
                        else pf = .12f + .88f * maxOf(0.0, 1 - (dt - 96) / maxOf(1L, v.pulseLen).toDouble()).toFloat()
                    }
                    env *= pf
                }
                if (v.filt) {
                    val y = v.b0 * smp + v.b1 * v.x1 + v.b2 * v.x2 - v.a1 * v.y1 - v.a2 * v.y2
                    v.x2 = v.x1; v.x1 = smp; v.y2 = v.y1; v.y1 = y; smp = y
                }
                if (p >= 0) {
                    if (pfOn[p]) {
                        val c = pfC[p]
                        val y = c[0] * smp + c[1] * v.px1 + c[2] * v.px2 - c[3] * v.py1 - c[4] * v.py2
                        v.px2 = v.px1; v.px1 = smp; v.py2 = v.py1; v.py1 = y; smp = y
                    } else { v.py1 = smp; v.py2 = v.py1; v.px1 = v.py2; v.px2 = v.px1 }
                }
                // the doctor's effects and the pad's live ones
                if (dirt > 0) smp = tanh((smp * drive).toDouble()).toFloat() * driveN * (1 - dirt * .35f)
                if (crush > 0) {
                    if (--v.crushCnt <= 0) { v.crushCnt = hold; v.crushVal = jRound(smp / step) * step }
                    smp = v.crushVal
                }
                if (ring > 0) smp = smp * (1 - ring) + smp * sin(2 * PI * ringHz * (frame + k) / rate).toFloat() * ring
                if (gate > 0 && ((frame + k) % gateDiv.toLong()) >= gateDiv / 2) smp *= 1 - gate
                if (warm > 0) {
                    // a cheap tanh (x(27 + x^2) / (27 + 9x^2)) for the warmth
                    val xw = smp * (1 + 2 * warm); val x2 = xw * xw
                    val sat = if (abs(xw) > 3) sign(xw) else xw * (27 + x2) / (27 + 9 * x2)
                    smp = smp * (1 - warm * .4f) + sat * warm * .55f
                }
                if (phaser > 0) {
                    // four first-order all-passes swept by a slow sine (set once a block), mixed with the dry sound
                    val c = phC; val x0 = smp
                    val y1 = -c * x0 + v.ap1; v.ap1 = x0 + c * y1
                    val y2 = -c * y1 + v.ap2; v.ap2 = y1 + c * y2
                    val y3 = -c * y2 + v.ap3; v.ap3 = y2 + c * y3
                    val y4 = -c * y3 + v.ap4; v.ap4 = y3 + c * y4
                    smp = x0 * (1 - .5f * phaser) + y4 * .5f * phaser
                }
                if (pump > 0) {
                    // a duck on every beat that comes back up before the next: the sidechain sound
                    val bp = ((((beat0 + (k - k0) / spbV) % 4) + 4) % 4) / 4.0
                    val xr = (1 - bp).toFloat()
                    smp *= 1 - pump * xr * xr * xr
                }
                smp *= env * vol
                var sl = smp; var sr = smp
                if (chorus > 0) {
                    val cb = v.chBuf!!
                    cb[v.chW and 2047] = smp
                    val m = sin(v.chPh)
                    val dl = (.012 + .006 * m) * rate; val dr = (.012 - .006 * m) * rate
                    val tl = chRead(cb, v.chW - dl); val tr = chRead(cb, v.chW - dr)
                    v.chW++
                    v.chPh += chInc
                    sl = smp * (1 - .5f * chorus) + tl * .7f * chorus
                    sr = smp * (1 - .5f * chorus) + tr * .7f * chorus
                }
                val o = (off + k) * 2
                val ol = sl * gl; val or = sr * gr
                dst[o] += ol
                dst[o + 1] += or
                if (rvbS > 0) { rvbIn[o] += ol * rvbS; rvbIn[o + 1] += or * rvbS }
                if (echoS > 0) { echoIn[o] += ol * echoS; echoIn[o + 1] += or * echoS }
                var rm = v.bendMul
                if (p >= 0) rm *= padRateFrom[p] + (padRateTo[p] - padRateFrom[p]) * ((frame + k - blockStart) / maxOf(1, blockLen).toDouble())
                if (wob > 0) { rm *= 1 + wob * .025 * sin(v.wobPh); v.wobPh += wobInc }
                if (warm > 0) { rm *= 1 + warm * .004 * sin(v.wobPh * .15); if (wob <= 0) v.wobPh += wobInc }
                if (v.grain) {
                    val pi = v.inc * rm
                    v.g0 += pi; v.g1 += pi
                    v.gp0 += v.gStep; v.gp1 += v.gStep
                    if (!v.frozen && !freeze) {
                        v.tPos += v.tInc
                        if (v.loop && v.tPos >= v.len) v.tPos -= maxOf(1.0, v.len - v.loopFrom)
                        if (v.sus >= 0 && !v.releasing && v.tPos >= v.sus) { v.frozen = true; v.tPos = v.sus }
                    }
                    if (v.gp0 >= 1) { v.gp0 -= 1; v.g0 = grainStart(v, d, v.g1) }
                    if (v.gp1 >= 1) { v.gp1 -= 1; v.g1 = grainStart(v, d, v.g0) }
                } else if (!v.syn) {
                    if (freeze) {
                        // Freeze: rock back and forth in a 60 ms window where the sound was, so it holds without clicks
                        if (v.frzA < 0) v.frzA = v.pos
                        v.pos += v.inc * rm * v.frzDir
                        val win = .06 * (if (v.d != null) rate else rate)
                        if (v.pos > v.frzA + win) { v.pos = v.frzA + win; v.frzDir = -1 }
                        else if (v.pos < v.frzA) { v.pos = v.frzA; v.frzDir = 1 }
                    } else { v.frzDir = 1; v.pos += v.inc * rm }
                }
                if (v.glideLeft > 0) { v.inc *= v.glideMul; v.glideLeft-- }
                // a frozen note does not run out (its release still fades it)
                if (!freeze || v.releasing) v.t++
                k++
            }
        }
    }

    /** Scratch for grainStart, audio thread only. */
    private val gRef = FloatArray(32)

    /**
     * Where a head starts its next grain: where time is now (or around the frozen point, with a little drift so a
     * sustain never buzzes), nudged by up to 8 ms to where it best lines up with the grain fading out, so the two
     * don't cancel each other where they overlap (the warble of a plain grain shifter on tones).
     */
    private fun grainStart(v: Voice, d: FloatArray, other: Double): Double {
        val x = if (v.frozen) v.sus + (rnd.nextDouble() - .5) * .024 * v.srate else v.tPos
        // heads that read at time's own speed are already in line: nothing to search
        if (!v.frozen && abs(v.inc - v.tInc) < 1e-9) return x
        val r = minOf(.008 * v.srate, 400.0).toInt()
        val inc = maxOf(.25, v.inc) * 8
        // what the fading grain is about to play, read once
        for (n in 0 until 32) gRef[n] = gRead(v, d, other + n * inc)
        var best = x; var bestC = -1e30
        val span = 31 * inc
        // coarse pass every 4 samples, then the 3 either side of the winner
        for (pass in 0 until 2) {
            val c0 = best
            val lo = if (pass == 0) -r else -3; val hi = if (pass == 0) r else 3; val stp = if (pass == 0) 4 else 1
            var dl = lo
            while (dl <= hi) {
                val cand = (if (pass == 0) x else c0) + dl
                // a Java "continue" in a for loop: the step still happens
                if (cand >= 0) {
                    var c = 0.0
                    if (cand + span < v.len - 2 && v.a + cand + span < minOf(v.zEnd, d.size) - 2) {
                        // all inside the sound: plain reads, no wrapping or bounds to check
                        val at = v.a + cand
                        for (n in 0 until 32) c += gRef[n] * d[(at + n * inc).toInt()]
                    } else for (n in 0 until 32) c += gRef[n] * gRead(v, d, cand + n * inc)
                    if (c > bestC) { bestC = c; best = cand }
                }
                dl += stp
            }
        }
        return maxOf(0.0, best)
    }

    /* ---------- the sound effects' two shared spaces: a reverb and a tempo echo ---------- */

    private val combL: Array<FloatArray> = Array(4) { c -> FloatArray(maxOf(1, COMB[c] * rate / 44100)) }
    private val combR: Array<FloatArray> = Array(4) { c -> FloatArray(maxOf(1, (COMB[c] + 23) * rate / 44100)) }
    private val apL: Array<FloatArray> = Array(2) { a -> FloatArray(maxOf(1, ALLP[a] * rate / 44100)) }
    private val apR: Array<FloatArray> = Array(2) { a -> FloatArray(maxOf(1, (ALLP[a] + 23) * rate / 44100)) }
    private val combI = IntArray(4); private val apI = IntArray(2)
    private val combZL = FloatArray(4); private val combZR = FloatArray(4)
    private var rvbSize = .5f; private var rvbSizeT = .5f
    private val sEchoL: FloatArray
    private val sEchoR: FloatArray
    private var sEchoW = 0
    private var sEchoT = -1.0
    private var sEchoDiv = 1
    private var rvbIn = FloatArray(0); private var echoIn = FloatArray(0)
    private var busLevel = 0f

    private fun soundBuses(out: FloatArray, frames: Int) {
        var peak = 0f
        for (k in 0 until frames * 2) peak = maxOf(peak, maxOf(abs(rvbIn[k]), abs(echoIn[k])))
        if (peak < 1e-7f && busLevel < 1e-6f) return
        busLevel = 0f
        rvbSize += (rvbSizeT - rvbSize) * .1f
        val fb = .72f + rvbSize * .26f; val damp = .25f; val wet = .9f
        val div = doubleArrayOf(1.0, 2.0, 3.0, 4.0)[sEchoDiv]
        val dt = minOf((sEchoL.size - 1).toLong(), jRound(spbNow * div)).toInt()
        for (k in 0 until frames) {
            val inp = (rvbIn[k * 2] + rvbIn[k * 2 + 1]) * .03f
            var yl = 0f; var yr = 0f
            for (c in 0 until 4) {
                val bl = combL[c]; val br = combR[c]
                val i = combI[c]
                val ol = bl[i]; val or = br[i]
                combZL[c] = ol * (1 - damp) + combZL[c] * damp
                combZR[c] = or * (1 - damp) + combZR[c] * damp
                bl[i] = inp + combZL[c] * fb
                br[i] = inp + combZR[c] * fb
                combI[c] = (i + 1) % bl.size
                yl += ol; yr += or
            }
            for (a in 0 until 2) {
                val bl = apL[a]; val br = apR[a]
                val i = apI[a]
                val bo = bl[i]; val bro = br[i]
                bl[i] = yl + bo * .5f; br[i] = yr + bro * .5f
                yl = bo - yl; yr = bro - yr
                apI[a] = (i + 1) % bl.size
            }
            // the echo: tempo-timed, a little darker each repeat
            val r = (sEchoW - dt).mod(sEchoL.size)
            val el = sEchoL[r]; val er = sEchoR[r]
            sEchoL[sEchoW] = echoIn[k * 2] + er * .45f
            sEchoR[sEchoW] = echoIn[k * 2 + 1] + el * .45f
            sEchoW = (sEchoW + 1) % sEchoL.size
            val l = yl * wet + el; val rr = yr * wet + er
            out[k * 2] += l; out[k * 2 + 1] += rr
            busLevel = maxOf(busLevel, abs(l) + abs(rr))
        }
    }

    /** True when the reverb and echo have died away (for bouncing and baking). */
    fun busesQuiet(): Boolean = busLevel < 1e-5f

    /* ---------- per-pad live control: XY moves, routing, per-pad and per-bus effects ---------- */

    private val pMod: Array<FloatArray> = Array(Project.SLOTS) { FloatArray(NT) }
    private val tFx: Array<FloatArray> = Array(69) { FloatArray(9) }
    private val padDirt = FloatArray(Project.SLOTS); private val padCrush = FloatArray(Project.SLOTS); private val padChorus = FloatArray(Project.SLOTS); private val padWob = FloatArray(Project.SLOTS)
    private val padGate = FloatArray(Project.SLOTS); private val padRing = FloatArray(Project.SLOTS); private val padRvb = FloatArray(Project.SLOTS); private val padEcho = FloatArray(Project.SLOTS)
    private val padVol = FloatArray(Project.SLOTS); private val padPan = FloatArray(Project.SLOTS); private val tapeF = FloatArray(Project.SLOTS)
    private val padPump = FloatArray(Project.SLOTS); private val padWarm = FloatArray(Project.SLOTS); private val padPhaser = FloatArray(Project.SLOTS); private val padFreeze = FloatArray(Project.SLOTS)
    private val padRateFrom = DoubleArray(Project.SLOTS); private val padRateTo = DoubleArray(Project.SLOTS)
    private val padStut = BooleanArray(Project.SLOTS); private val padRev = BooleanArray(Project.SLOTS)
    private val pStutA = IntArray(Project.SLOTS); private val pStutB = IntArray(Project.SLOTS)
    private val rollNext = LongArray(Project.SLOTS)
    private var blockStart = 0L
    private var blockLen = 1
    private var spbNow = 1000.0
    private var gateSteps = 1
    private val mixAmt = floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f)
    @JvmField var triggers = 0
    /** SE-3: plays that used a baked version, and pads that needed live grain this session (for the Latency screen). */
    @JvmField var bakedPlays = 0
    @JvmField val grainUsed = BooleanArray(Project.SLOTS)

    /** Move one thing on one pad: semitones for pitch, -1..1 for volume and pan, seconds for attack, release and glide, 0..1 for the rest. */
    fun mod(pad: Int, what: Int, value: Float) { cmds.add(arrayOf<Any?>("mod", pad, what, value)) }
    /** A hand lets go of a control: it goes back to its lane or where it rests (build 106). */
    fun release(pad: Int, what: Int) { cmds.add(arrayOf<Any?>("rel", pad, what)) }
    /** Where a control rests (an FX strip fader): saved in the mess by the app. */
    fun home(pad: Int, what: Int, value: Float) { cmds.add(arrayOf<Any?>("home", pad, what, value)) }
    /** A perform effect on one pad (0..63) or one bus (65..68 = buses 1..4); amount 0 turns it off. */
    fun fxTo(name: String, target: Int, amount: Float) { cmds.add(arrayOf<Any?>("fxt", name, target, amount)) }
    /** Bend and level for one held note only (a circle drawn on a Padiano key). */
    fun noteMod(id: Int, semis: Float, vol: Float) { cmds.add(arrayOf<Any?>("nmod", id, semis, vol)) }
    /** How much a mix effect does when held, 0..1, and the gate's length in steps. */
    fun fxAmount(name: String, amount: Float) { cmds.add(arrayOf<Any?>("famt", name, amount)) }
    fun gateLength(steps: Int) { cmds.add(arrayOf<Any?>("gatelen", steps)) }

    private val modTouch: Array<LongArray> = Array(Project.SLOTS) { LongArray(NT) }
    /** Which controls a recorded lane is driving right now, and the step serial each lane was last heard on (build 105). */
    private val laneHeld: Array<BooleanArray> = Array(Project.SLOTS) { BooleanArray(NT) }
    private val laneSeen: Array<LongArray> = Array(Project.SLOTS) { LongArray(NT) }
    private var laneSerial = 0L

    /*
     * Build 106, the shared layer: every pad control has one value the sound reads (pMod), built the same way for all:
     * a hand on it (finger, wheel, mouth, knob) wins while it holds on; else a recorded lane where one is playing;
     * else where the control rests (Home: the FX strip's faders, saved in the mess). The LFO adds its wave on top
     * when the sound is made, so it moves from wherever the control is.
     */
    private val pHome: Array<FloatArray> = Array(Project.SLOTS) { FloatArray(NT) }
    private val pHand: Array<FloatArray> = Array(Project.SLOTS) { FloatArray(NT) }
    private val laneVal: Array<FloatArray> = Array(Project.SLOTS) { FloatArray(NT) }
    private val handOn: Array<BooleanArray> = Array(Project.SLOTS) { BooleanArray(NT) }

    /** Step locks: on for one step at a time, while the loop plays. */
    private val lockOn: Array<BooleanArray> = Array(Project.SLOTS) { BooleanArray(NT) }
    private val lockVal: Array<FloatArray> = Array(Project.SLOTS) { FloatArray(NT) }

    /** Who wins a control: a hand on it, then a step lock, then a recorded lane, then where it rests. */
    private fun combine(p: Int, t: Int) {
        pMod[p][t] = if (handOn[p][t]) pHand[p][t] else if (lockOn[p][t]) lockVal[p][t] else if (laneHeld[p][t]) laneVal[p][t] else pHome[p][t]
    }

    /** Let go of every step lock (stop, panic). */
    private fun dropLocks() {
        for (p in 0 until Project.SLOTS) for (t in 0 until NT) if (lockOn[p][t]) { lockOn[p][t] = false; combine(p, t) }
    }

    /** Every pad's controls rest where the mess says (each new snapshot, a bounce, panic). */
    private fun restHomes(S: Snapshot?) {
        if (S == null) return
        for (i in 0 until Project.SLOTS) {
            val ps = if (i < S.pads.size) S.pads[i] else null
            val m: Map<Int, Double> = if (ps != null) ps.opts.fxMap() else emptyMap()
            for (t in 0 until NT) {
                val d = m[t]
                val v = if (d == null) 0f else d.toFloat()
                if (pHome[i][t] != v) { pHome[i][t] = v; combine(i, t) }
            }
        }
    }

    /** What a control sounds like right now, before the LFO (for tests and the UI's ghost marks). */
    fun controlValue(pad: Int, t: Int): Float = if (pad >= 0 && pad < Project.SLOTS && t >= 0 && t < NT) pMod[pad][t] else 0f
    init { for (m in modTouch) m.fill(Long.MIN_VALUE / 2) }

    /**
     * Build 106: one pad filter for everything. Its value is the shared one (hand, lane or rest), its kind is the pad's
     * (low, high or band pass), and an LFO aimed at the filter closes it from there by up to its depth. With the LFO
     * moving it the filter follows each block with no glide, so a 1/32 wub stays sharp.
     */
    private fun filterBlock(S: Snapshot?) {
        for (p in 0 until Project.SLOTS) {
            val ps = if (S != null && p < S.pads.size) S.pads[p] else null
            var closed = maxOf(0f, minOf(1f, pMod[p][T_FILTER])).toDouble(); var res = maxOf(0f, minOf(1f, pMod[p][T_RES])).toDouble()
            val type = if (ps != null) ps.opts.ftype else 1
            val wave = ps != null && ps.opts.lfoOn && (ps.opts.lfoTarget == T_FILTER || ps.opts.lfoTarget == T_RES)
            if (wave) {
                val v = lfoV[p]; val dp = ps!!.opts.lfoDepth
                if (ps.opts.lfoTarget == T_FILTER) closed = maxOf(0.0, minOf(1.0, closed + dp * (1 - v) / 2))
                else res = maxOf(0.0, minOf(1.0, res + dp * (v + 1) / 2))
            }
            val on = closed > .001 || res > .001
            if (!on) {
                // a high or band pass that opens jumps straight to open: gliding it as a low pass would sweep the wrong way
                if (pfType[p] != 1) { pfType[p] = 1; pfFreq[p] = 20000.0 }
                pfTarget[p] = 20000.0; pfQT[p] = .7; pfSlow[p] = true; lfoFilt[p] = false
                continue
            }
            val f = if (type == 2) 20 * (16000.0 / 20).pow(closed) else if (type == 3) 90 * (16000.0 / 90).pow(1 - closed) else 90 * (20000.0 / 90).pow(1 - closed)
            pfType[p] = type
            pfTarget[p] = f
            pfQT[p] = .7 + res * 13
            pfSlow[p] = false
            if (wave) { pfFreq[p] = f; pfQ[p] = pfQT[p] }
            lfoFilt[p] = wave || type != 1
        }
    }

    /** Kept for the callers above; the filter is now worked out once per block in filterBlock. */
    @Suppress("UNUSED_PARAMETER") private fun filterFromMod(pd: Int) {}

    /** The rate a pad plays at right now (XY pitch, tape, octave), for tests. */
    fun padRate(pad: Int): Double = if (pad >= 0 && pad < Project.SLOTS) padRateTo[pad] else 1.0
    fun padLevel(pad: Int): Float = if (pad >= 0 && pad < Project.SLOTS) padVol[pad] else 1f

    private fun padBlock(S: Snapshot, frames: Int) {
        blockStart = frame
        blockLen = frames
        spbNow = spb(S)
        for (p in 0 until Project.SLOTS) {
            val ps = S.pads[p]
            val bus = if (ps != null) ps.opts.route else 0
            val a = tFx[p]; val b = if (bus > 0) tFx[Project.NPADS + bus] else ZERO9; val m = pMod[p]
            padDirt[p] = maxOf(a[5], b[5]) + m[T_DIRT]
            padCrush[p] = maxOf(a[2], b[2]) + m[T_CRUSH]
            padGate[p] = minOf(1f, maxOf(a[4], b[4]) + m[T_GATE])
            padRing[p] = minOf(1f, maxOf(a[6], b[6]) + m[T_RING])
            padEcho[p] = maxOf(a[1], b[1]) + m[T_ECHO]
            // Nov 21 (second video): a curve that eases out, so a half-full Verb vial is plainly reverb, the top the same as before
            run { val rv = maxOf(0f, minOf(1f, m[T_RVB])); padRvb[p] = rv * (2 - rv) }
            padChorus[p] = m[T_CHORUS]
            padWob[p] = m[T_WOB]
            padPump[p] = minOf(1f, m[T_PUMP]); padWarm[p] = minOf(1f, m[T_WARM]); padPhaser[p] = minOf(1f, m[T_PHASER]); padFreeze[p] = minOf(1f, m[T_FREEZE])
            val stut = (maxOf(a[0], b[0]) > 0 || m[T_STUT] > .5f) && playing
            if (stut && !padStut[p]) { pStutA[p] = lastStep - (lastStep % 2); pStutB[p] = step }
            padStut[p] = stut
            padRev[p] = maxOf(a[8], b[8]) > 0
            val oct = maxOf(a[7], b[7]) > 0
            // a held tape stop winds the pad down over about a second; XY tape sets it directly
            tapeF[p] = if (maxOf(a[3], b[3]) > 0) minOf(1f, tapeF[p] + frames / (1.1f * rate)) else 0f
            val tape = maxOf(tapeF[p], minOf(1f, m[T_TAPE]))
            padRateFrom[p] = padRateTo[p]
            padRateTo[p] = 2.0.pow(m[T_PITCH] / 12.0) * (if (oct) .5 else 1.0) * (1 - .82 * tape)
            padVol[p] = maxOf(0f, 1 + m[T_VOL]) * (if (tape >= .97f) 0 else 1)
            padPan[p] = maxOf(-1f, minOf(1f, m[T_PAN]))
            // the LFO strip moves one of these by its wave (filter and resonance are set in lfoBlock)
            if (ps != null && ps.opts.lfoOn) {
                val lv = lfoV[p]; val dp = ps.opts.lfoDepth
                val up = (dp * (lv + 1) / 2).toFloat()
                when (ps.opts.lfoTarget) {
                    T_PITCH -> padRateTo[p] *= 2.0.pow(dp * 12 * lv / 12.0)
                    T_VOL -> padVol[p] *= maxOf(0.0, 1 - dp * (1 - lv) / 2).toFloat()
                    T_PAN -> padPan[p] = maxOf(-1.0, minOf(1.0, padPan[p] + dp * lv)).toFloat()
                    T_RVB -> padRvb[p] += up
                    T_ECHO -> padEcho[p] += up
                    T_DIRT -> padDirt[p] += up
                    T_CRUSH -> padCrush[p] += up
                    T_CHORUS -> padChorus[p] += up
                    T_PUMP -> padPump[p] = minOf(1f, padPump[p] + up)
                    T_WARM -> padWarm[p] = minOf(1f, padWarm[p] + up)
                    T_PHASER -> padPhaser[p] = minOf(1f, padPhaser[p] + up)
                    else -> {}
                }
            }
            // roll: this pad's held notes retrigger, from every 2 steps up to every quarter step
            val roll = m[T_ROLL]
            if (roll > .05f && ps != null) {
                var any = false
                for (h in held.values) if (h.pad.index == p) { any = true; break }
                if (!any) { rollNext[p] = 0; continue }
                val every = 2.0.pow((1 - 3 * minOf(1f, roll)).toDouble()) * spbNow
                if (rollNext[p] < frame) rollNext[p] = frame
                while (rollNext[p] < frame + frames) {
                    val dl = rollNext[p] - frame
                    for (h in held.values) if (h.pad.index == p) for (q in h.pitches) trigger(h.pad, oct() * 2.0.pow(q / 12.0), every * .9 / rate, true, Theory.chordGain(h.pitches.size), dl, false)
                    rollNext[p] += maxOf(1.0, every).toLong()
                }
            } else rollNext[p] = 0
        }
    }

    /**
     * The live bus from the site, in its order: filter pad, dirt, ring mod, gate, crush, then an echo
     * send whose tail keeps ringing after you let go. Wet amounts glide over about 10 ms so nothing clicks.
     */
    private fun effects(S: Snapshot, out: FloatArray, frames: Int) {
        // filter pad: glide the cutoff once per block
        val glide = 1 - exp(-frames / ((if (filtSlow) .12 else .02) * rate))
        filtFreq += (filtTarget - filtFreq) * glide
        filtQ += (qTarget - filtQ) * glide
        val filt = if (xyHP) filtFreq > 11 else filtFreq < 19900
        var b0 = 0f; var b1 = 0f; var b2 = 0f; var a1 = 0f; var a2 = 0f
        if (filt) {
            val f = minOf(filtFreq, rate * .45); val w = 2 * PI * f / rate; val cs = cos(w); val al = sin(w) / (2 * filtQ); val a0 = 1 + al
            if (xyHP) { b0 = ((1 + cs) / 2 / a0).toFloat(); b1 = (-(1 + cs) / a0).toFloat() }
            else { b0 = ((1 - cs) / 2 / a0).toFloat(); b1 = ((1 - cs) / a0).toFloat() }
            b2 = b0; a1 = (-2 * cs / a0).toFloat(); a2 = ((1 - al) / a0).toFloat()
        }
        val k = (1 - exp(-1.0 / (.01 * rate))).toFloat()
        val spb = 60.0 / maxOf(40, S.bpm) / 4
        val dt = minOf((dlyL.size - 1).toLong(), jRound(minOf(1.9, spb * 3) * rate)).toInt()
        val hpK = exp(-2 * PI * 350 / rate).toFloat()
        val ringInc = 2 * PI * ringHz / rate; val gateInc = gateHz / rate
        val idle = !filt && !fxCrush && !fxDirt && !fxRing && !fxGate && !fxEcho && wCrush < 1e-4f && wDirt < 1e-4f && wRing < 1e-4f && wGate < 1e-4f && wSend < 1e-4f
        for (i in 0 until frames) {
            wCrush += ((if (fxCrush) mixAmt[2] else 0f) - wCrush) * k
            wDirt += ((if (fxDirt) mixAmt[5] else 0f) - wDirt) * k
            wRing += ((if (fxRing) mixAmt[6] else 0f) - wRing) * k
            wGate += ((if (fxGate) mixAmt[4] else 0f) - wGate) * k * 2
            wSend += ((if (fxEcho) mixAmt[1] else 0f) - wSend) * k * .5f
            var l = out[i * 2]; var r = out[i * 2 + 1]
            if (!idle) {
                if (filt) {
                    val yl = b0 * l + b1 * fzL[0] + b2 * fzL[1] - a1 * fzL[2] - a2 * fzL[3]
                    fzL[1] = fzL[0]; fzL[0] = l; fzL[3] = fzL[2]; fzL[2] = yl; l = yl
                    val yr = b0 * r + b1 * fzR[0] + b2 * fzR[1] - a1 * fzR[2] - a2 * fzR[3]
                    fzR[1] = fzR[0]; fzR[0] = r; fzR[3] = fzR[2]; fzR[2] = yr; r = yr
                }
                if (wDirt > 1e-4f) { l = l * (1 - wDirt) + drive(l) * .55f * wDirt; r = r * (1 - wDirt) + drive(r) * .55f * wDirt }
                if (wRing > 1e-4f) { val m = sin(ringPh).toFloat(); l = l * (1 - wRing) + l * m * wRing; r = r * (1 - wRing) + r * m * wRing }
                ringPh += ringInc; if (ringPh > 2 * PI) ringPh -= 2 * PI
                gatePh += gateInc; if (gatePh >= 1) gatePh -= 1
                if (wGate > 1e-4f) { val g = (1 - wGate) + wGate * (if (gatePh < .5) 1f else 0f); l *= g; r *= g }
                if (wCrush > 1e-4f) { l = l * (1 - wCrush) + crush(l) * .9f * wCrush; r = r * (1 - wCrush) + crush(r) * .9f * wCrush }
            }
            // echo: always running so tails finish after you let go
            var rd = dlyW - dt; if (rd < 0) rd += dlyL.size
            val el = dlyL[rd]; val er = dlyR[rd]
            val hl = hpK * (hpL + el - hpXL); val hr = hpK * (hpR + er - hpXR)
            hpXL = el; hpXR = er; hpL = hl; hpR = hr
            dlyL[dlyW] = l * wSend * .85f + hl * .55f
            dlyR[dlyW] = r * wSend * .85f + hr * .55f
            if (++dlyW >= dlyL.size) dlyW = 0
            out[i * 2] = l + hl
            out[i * 2 + 1] = r + hr
        }
    }

    /** A gentle compressor (about where the site's sits) and a soft clip, so stacked pads do not crack. */
    private fun master(out: FloatArray, frames: Int) {
        // squash .5 is where the bus always sat: -10 dB, 4 to 1, 1.25 makeup. 0 is no squash, 1 is -20 dB, 7 to 1
        val S0 = snap
        val sq = if (S0 == null) .5f else maxOf(0f, minOf(1f, S0.squash)); val lvl = if (S0 == null) 1f else S0.level
        val thr = 10.0.pow((-sq).toDouble()).toFloat(); val ratio = 1 + 6 * sq; val makeup = (1 + .5f * sq) * lvl
        for (k in 0 until frames) {
            val l = out[k * 2]; val r = out[k * 2 + 1]
            val x = maxOf(abs(l), abs(r))
            compEnv += (x - compEnv) * (if (x > compEnv) compAtk else compRel)
            var g = 1f
            if (compEnv > thr && ratio > 1.001f) g = (thr * (compEnv / thr).toDouble().pow((1 / ratio).toDouble()) / compEnv).toFloat()
            g *= makeup
            out[k * 2] = soft(l * g)
            out[k * 2 + 1] = soft(r * g)
        }
    }

    /** How long the last hit waited in the queue for the next block, and how many hits so far (Audio check, MM-1). */
    @Volatile @JvmField var lastHitWaitNanos = 0L
    @Volatile @JvmField var hits = 0

    fun activeVoices(): Int { var n = 0; for (v in pool) if (v.active) n++; return n }

    /** Voices running live grain right now: each costs about three plain voices. */
    fun grainVoices(): Int { var n = 0; for (v in pool) if (v.active && v.grain) n++; return n }

    init {
        clickHi = tone("click-hi", 1760.0)
        clickLo = tone("click-lo", 1100.0)
        clickHiSlot = PadSlot(-2, "click", clickHi, SoundEdit.DEFAULT, true)
        clickLoSlot = PadSlot(-2, "click", clickLo, SoundEdit.DEFAULT, true)
        compAtk = (1 - exp(-1.0 / (.005 * rate))).toFloat()
        compRel = (1 - exp(-1.0 / (.2 * rate))).toFloat()
        dlyL = FloatArray((2.0 * rate).toInt())
        dlyR = FloatArray((2.0 * rate).toInt())
        pfFreq.fill(20000.0); pfTarget.fill(20000.0)
        lastP.fill(Double.NaN)
        padRateFrom.fill(1.0); padRateTo.fill(1.0); padVol.fill(1f)
        sEchoL = FloatArray((2.2 * rate).toInt())
        sEchoR = FloatArray((2.2 * rate).toInt())
        pfQ.fill(.7); pfQT.fill(.7)
        snap = Snapshot.build(Project(), SampleSource { null }, null)
    }

    companion object {
        /** A step's level for ghost, normal and accent (normal is what every step played at before build 108). */
        @JvmField val STEP_GAIN = floatArrayOf(.42f, .85f, 1f)

        /** One wave value: ph 0..1 through the cycle. */
        @JvmStatic
        internal fun lfoWave(wave: Int, ph: Double, cyc: Long, pad: Int): Double {
            when (wave) {
                1 -> return 1 - 4 * abs(ph - .5)
                2 -> return 2 * ph - 1
                3 -> return 1 - 2 * ph
                4 -> return if (ph < .5) 1.0 else -1.0
                5 -> {
                    // the Java hex constants 0x9E3779B97F4A7C15L, 0xBF58476D1CE4E5B9L and 0x94D049BB133111EBL, written as the same bits
                    var h = (cyc * -0x61C8864680B583EBL) xor (pad * -0x40A7B892E31B1A47L); h = h xor (h ushr 31); h *= -0x6B2FB644ECCEEE15L; h = h xor (h ushr 29); return (h and 0xFFFFL) / 32767.5 - 1
                }
                else -> return sin(2 * PI * ph)
            }
        }

        /**
         * The soft voice limit (Tyler, Nov 18: the lag crept up in build 92 with all 96 voices sounding). Past it, the
         * quietest fading voice is choked: it fades out over a few milliseconds instead of stopping with a click.
         */
        const val VOICE_CAP = 48

        /** Grain length for Grain pitch and Sustain: long enough for low notes, short enough to keep attacks. */
        internal const val GRAIN_SEC = .05
        /** One grain's fade in and out: sin squared, so two heads half a grain apart always add up to 1. */
        private val HANN = FloatArray(1025)
        init { for (i in 0..1024) { val s = sin(PI * i / 1024.0); HANN[i] = (s * s).toFloat() } }

        /** A grain head's sample at position h (source samples from the trim start); silent outside the sound unless it loops. */
        private fun gRead(v: Voice, d: FloatArray, h0: Double): Float {
            var h = h0
            if (v.loop || v.frozen) { val span = maxOf(1.0, v.len - v.loopFrom); while (h >= v.len) h -= span }
            if (h < 0 || h >= v.len - 1) return 0f
            val i = v.a + h.toInt()
            if (i < 0 || i >= d.size - 1 || i >= v.zEnd - 1) return 0f
            val fr = h - floor(h)
            return (d[i] + (d[i + 1] - d[i]) * fr).toFloat()
        }

        private fun chRead(b: FloatArray, at: Double): Float {
            val i = floor(at).toInt()
            val f = (at - i).toFloat()
            val a = b[i and 2047]; val c = b[(i + 1) and 2047]
            return a + (c - a) * f
        }

        private val COMB = intArrayOf(1116, 1188, 1277, 1356)
        private val ALLP = intArrayOf(556, 441)

        /** What an XY direction (or any live control) can move on one pad. */
        const val T_PITCH = 0; const val T_FILTER = 1; const val T_RES = 2; const val T_VOL = 3; const val T_PAN = 4; const val T_ATT = 5; const val T_REL = 6; const val T_START = 7
        const val T_RVB = 8; const val T_ECHO = 9; const val T_DIRT = 10; const val T_CRUSH = 11; const val T_CHORUS = 12; const val T_WOB = 13; const val T_GLIDE = 14; const val T_ROLL = 15
        const val T_GATE = 16; const val T_RING = 17; const val T_STUT = 18; const val T_TAPE = 19
        // Nov 18: four more live effects for the FX strip (Pump ducks on the beat, Warm is tape wow and warmth,
        // Phaser sweeps, Freeze holds the sound where it is)
        const val T_PUMP = 20; const val T_WARM = 21; const val T_PHASER = 22; const val T_FREEZE = 23; const val NT = 24
        /** The perform effects, in the order the buttons show them. */
        @JvmField val FXN = arrayOf("stutter", "echo", "crush", "tape", "gate", "dirty", "ring", "oct", "rev")
        private val ZERO9 = FloatArray(9)

        /** Velocity 0..1 to a level: soft hits stay audible, a full hit is a little over a plain tap. */
        @JvmStatic
        internal fun velGain(v0: Float): Float { val v = maxOf(0f, minOf(1f, v0)); return (.3 + .85 * v).toFloat() }

        private fun fxIndex(n: String): Int { for (k in 0 until FXN.size) if (FXN[k] == n) return k; return -1 }

        private fun drive(x: Float): Float = (tanh((9 * x).toDouble()) / tanh(9.0)).toFloat()
        private fun crush(x: Float): Float { val c = maxOf(-1f, minOf(1f, x)); return jRound(c * 8f) / 8f }

        private fun soft(x: Float): Float {
            val a = abs(x)
            if (a <= .8f) return x
            val y = .8f + .2f * tanh(((a - .8f) / .2f).toDouble()).toFloat()
            return if (x < 0) -y else y
        }

        /* ================= bouncing to a file ================= */

        /** Render the mess offline. song = the whole song lane, otherwise the current loop, loops times. */
        @JvmStatic
        fun bounce(p: Project, src: SampleSource, rate: Int, song: Boolean, loops: Int): FloatArray = bounce(p, src, rate, song, loops, false)

        /** Render one sound with its doctor settings baked in, mono, no master bus. */
        @JvmStatic
        fun renderOne(key: String, s: Sample, e: SoundEdit): Sample {
            val en = Engine(s.rate)
            en.bypassMaster = true
            val v = en.trigger(PadSlot(-1, key, s, e, true), 1.0, 0.0, false, 1f, 0, false, false) ?: return Sample(key, FloatArray(1), s.rate)
            val block = 1024
            val buf = FloatArray(block * 2)
            val parts: MutableList<FloatArray> = ArrayList()
            var total = 0
            while ((en.activeVoices() > 0 || !en.busesQuiet()) && total < s.rate * 60) { en.render(buf, block); parts.add(buf.copyOf()); total += block }
            val lr = FloatArray(total * 2)
            var at = 0
            for (b in parts) { b.copyInto(lr, at, 0, b.size); at += b.size }
            // drop the silent end
            var last = total - 1
            while (last > 0 && abs(lr[last * 2]) < 1e-5f && abs(lr[last * 2 + 1]) < 1e-5f) last--
            val cut = lr.copyOf((last + 1) * 2)
            return Sample.fromStereo(key, cut, s.rate)
        }

        /**
         * Build 7 of the overhaul: stems. Each sound bounced alone (soloed, with its own effects and tails), all padded to
         * the same length so they line up from zero in a DAW. Keys are the sounds' keys in kit order; silent sounds are left out.
         */
        @JvmStatic
        fun bounceStems(p: Project, src: SampleSource, rate: Int, song: Boolean, loops: Int): MutableMap<String, FloatArray> {
            val out: MutableMap<String, FloatArray> = LinkedHashMap()
            val seen: MutableSet<String> = HashSet()
            for (k in p.kit) {
                if (k == null || k.isEmpty() || !seen.add(k) || !p.audible(k)) continue
                var used = false
                for (P in MidiOut.order(p, song, loops)) if (P.usedBy(k)) { used = true; break }
                if (!used) continue
                val q = p.copy()
                q.solos.clear(); q.solos.add(k); q.mutes.remove(k)
                val w = bounce(q, src, rate, song, loops)
                var any = false
                for (x in w) if (abs(x) > 1e-4f) { any = true; break }
                if (any) out[k] = w
            }
            var n = 0
            for (w in out.values) n = maxOf(n, w.size)
            for (e in out.entries) if (e.value.size < n) e.setValue(e.value.copyOf(n))
            return out
        }

        /** exact = stop right at the loop's end with no tail, for resampling a loop into a sound. */
        @JvmStatic
        fun bounce(p: Project, src: SampleSource, rate: Int, song: Boolean, loops: Int, exact: Boolean): FloatArray {
            val q = p.copy()
            q.songMode = song && q.songLen() > 0
            val S = Snapshot.build(q, src, Live())
            val e = Engine(rate)
            e.snap = S
            e.restHomes(S)
            var total = 0L
            if (q.songMode) for (k in 0 until q.songLen()) total += if (q.song[k] >= 0) q.pats[q.song[k]].len else 16
            else total = q.pats[q.cur].len.toLong() * maxOf(1, loops)
            e.stopAfter = total
            e.cmds.add(arrayOf<Any?>("play"))
            val block = 512
            val buf = FloatArray(block * 2)
            val stepFrames = rate * 60.0 / q.bpm / 4.0
            val maxFrames = (total * stepFrames + 6.0 * rate).toLong()
            val parts: MutableList<FloatArray> = ArrayList()
            var rendered = 0L; var quietSince = -1L
            while (rendered < maxFrames) {
                e.render(buf, block)
                parts.add(buf.copyOf())
                rendered += block
                if (exact && rendered >= total * stepFrames + .004 * rate) break
                if (!e.playing && e.fired >= total) {
                    if (e.activeVoices() == 0 && e.busesQuiet()) { if (quietSince < 0) quietSince = rendered; if (rendered - quietSince > .05 * rate) break }
                    if (rendered > total * stepFrames + 4.0 * rate) break
                }
            }
            val keep = if (exact) minOf(rendered, ceil(total * stepFrames + .004 * rate).toLong()) else maxOf(ceil(total * stepFrames).toLong(), rendered)
            val all = FloatArray((keep * 2).toInt())
            var at = 0
            for (b in parts) { val n = minOf(b.size, all.size - at); if (n <= 0) break; b.copyInto(all, at, 0, n); at += n }
            // short fade at the very end so the file never clicks
            val fade = minOf((.01 * rate).toInt(), keep.toInt())
            for (k in 0 until fade) { val f = keep.toInt() - 1 - k; val g = k / fade.toFloat(); all[f * 2] *= g; all[f * 2 + 1] *= g }
            return all
        }
    }
}
