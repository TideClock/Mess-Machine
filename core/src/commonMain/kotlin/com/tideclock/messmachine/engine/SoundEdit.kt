package com.tideclock.messmachine.engine

import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic
import kotlin.math.roundToLong

/**
 * The doctor's table settings for one sound, non-destructive. Same fields and defaults as
 * ED_DEF on the site, so edits travel both ways in a saved mess.
 */
class SoundEdit {
    @JvmField var st = 0.0; @JvmField var en = 1.0        // trim, as a fraction of the sound
    @JvmField var att = 0.0; @JvmField var rel = 0.0      // seconds
    @JvmField var rev = false; @JvmField var loop = false; @JvmField var norm = false
    @JvmField var mode = "one"          // "one" plays it all, "gate" stops when the note ends
    @JvmField var tune = 0.0; @JvmField var fine = 0.0    // semitones, cents
    @JvmField var vol = 1.0; @JvmField var pan = 0.0; @JvmField var tone = 0.0 // tone < 0 darker (low pass), > 0 thinner (high pass)
    @JvmField var choke = 0                // 0 = none, 1..4 groups
    /**
     * App only: what a one-shot does while Hold keeps it: "loop" its trim, hold the "tail", play "once",
     * "sustain" (freeze it partway and hold that moment as long as it is held, SE-5) or "beat" (play it
     * whole and start it again every beatN beats in time with the loop, each ringing into the next, SE-7).
     */
    @JvmField var held = "loop"
    @JvmField var beatN = 1
    /**
     * App only (SE-1): how a pitch change plays. "stretch" is tape: higher is faster and shorter. "grain" keeps
     * the length. "auto": notes played as keys, chords and in the Roll keep their length; the Doctor's own Tune
     * stays tape, so a tuned-down kick still slows down.
     */
    @JvmField var pitch = "auto"
    /** App only (SE-3): keep baked versions at every semitone within an octave, so Grain costs what Stretch costs. */
    @JvmField var bake = false
    /** App only: the doctor's effects, 0 = off. rvbSize 0..1, echoT picks 1/16, 1/8, 3/16, 1/4; wobRate 0..1. */
    @JvmField var rvb = 0.0; @JvmField var rvbSize = .5; @JvmField var echo = 0.0; @JvmField var dirt = 0.0; @JvmField var crush = 0.0; @JvmField var chorus = 0.0; @JvmField var wob = 0.0; @JvmField var wobRate = .3
    @JvmField var echoT = 1
    /**
     * App only (Re-pitch, Nov 26): semitones with cents that Re-pitch added on top of tune and fine, and what it went by:
     * "note" (the sound's one note moved to C) or "key" (its key moved into the mess's key; keeps its length in auto
     * pitch mode and follows the mess's key). "" when the sound was not re-pitched.
     */
    @JvmField var ap = 0.0
    @JvmField var apKind = ""

    /** Every semitone the sound is moved by: tune, fine and Re-pitch. */
    fun semis(): Double { return tune + fine / 100.0 + ap }

    fun copy(): SoundEdit {
        val e = SoundEdit()
        e.st = st; e.en = en; e.att = att; e.rel = rel; e.rev = rev; e.loop = loop; e.norm = norm
        e.mode = mode; e.tune = tune; e.fine = fine; e.vol = vol; e.pan = pan; e.tone = tone; e.choke = choke
        e.held = held; e.beatN = beatN; e.pitch = pitch; e.bake = bake; e.ap = ap; e.apKind = apKind
        e.rvb = rvb; e.rvbSize = rvbSize; e.echo = echo; e.echoT = echoT; e.dirt = dirt; e.crush = crush; e.chorus = chorus; e.wob = wob; e.wobRate = wobRate
        return e
    }

    fun isDefault(): Boolean {
        return st == 0.0 && en == 1.0 && att == 0.0 && rel == 0.0 && !rev && !loop && !norm && "one" == mode
                && tune == 0.0 && fine == 0.0 && vol == 1.0 && pan == 0.0 && tone == 0.0 && choke == 0 && "loop" == held && beatN == 1 && "auto" == pitch && !bake && apKind.isEmpty() && !hasFx() && rvbSize == .5 && echoT == 1 && wobRate == .3
    }

    fun hasFx(): Boolean { return rvb > 0 || echo > 0 || dirt > 0 || crush > 0 || chorus > 0 || wob > 0 }

    fun holds(): Boolean { return "one" != mode || loop || "sustain" == held }

    fun toMap(): MutableMap<String, Any?> {
        val m: MutableMap<String, Any?> = LinkedHashMap()
        m["st"] = st; m["en"] = en; m["att"] = att; m["rel"] = rel; m["rev"] = rev; m["loop"] = loop
        m["mode"] = mode; m["tune"] = tune; m["fine"] = fine; m["vol"] = vol; m["pan"] = pan
        m["tone"] = tone; m["norm"] = norm; m["choke"] = choke.toDouble()
        if ("loop" != held) m["held"] = held
        if (beatN != 1) m["beatN"] = beatN.toDouble()
        if ("auto" != pitch) m["pitch"] = pitch
        if (bake) m["bake"] = true
        if (apKind.isNotEmpty()) { m["ap"] = ap; m["apKind"] = apKind }
        if (hasFx() || rvbSize != .5 || echoT != 1 || wobRate != .3) {
            val f: MutableMap<String, Any?> = LinkedHashMap()
            f["rvb"] = rvb; f["rvbSize"] = rvbSize; f["echo"] = echo; f["echoT"] = echoT.toDouble(); f["dirt"] = dirt
            f["crush"] = crush; f["chorus"] = chorus; f["wob"] = wob; f["wobRate"] = wobRate
            m["fx"] = f
        }
        return m
    }

    companion object {
        @JvmField val DEFAULT = SoundEdit()

        @JvmStatic
        fun fromMap(m: Map<String, Any?>): SoundEdit {
            val e = SoundEdit()
            e.st = clamp(Json.num(m["st"], 0.0), 0.0, 1.0)
            e.en = clamp(Json.num(m["en"], 1.0), 0.0, 1.0)
            if (e.en <= e.st) { e.st = 0.0; e.en = 1.0 }
            e.att = clamp(Json.num(m["att"], 0.0), 0.0, 4.0)
            e.rel = clamp(Json.num(m["rel"], 0.0), 0.0, 4.0)
            e.rev = Json.bool(m["rev"], false)
            e.loop = Json.bool(m["loop"], false)
            e.norm = Json.bool(m["norm"], false)
            e.mode = if ("gate" == m["mode"]) "gate" else "one"
            e.tune = clamp(Json.num(m["tune"], 0.0), -48.0, 48.0)
            e.fine = clamp(Json.num(m["fine"], 0.0), -100.0, 100.0)
            e.vol = clamp(Json.num(m["vol"], 1.0), 0.0, 4.0)
            e.pan = clamp(Json.num(m["pan"], 0.0), -1.0, 1.0)
            e.tone = clamp(Json.num(m["tone"], 0.0), -1.0, 1.0)
            e.choke = clamp(Json.num(m["choke"], 0.0), 0.0, 8.0).toInt()
            val h = m["held"]
            e.held = if ("tail" == h || "once" == h || "sustain" == h || "beat" == h) h as String else "loop"
            val bn = javaRound(Json.num(m["beatN"], 1.0)).toInt()
            e.beatN = if (bn == 2 || bn == 4) bn else 1
            val pm = m["pitch"]
            e.pitch = if ("stretch" == pm || "grain" == pm) pm as String else "auto"
            e.bake = Json.bool(m["bake"], false)
            val ak = m["apKind"]
            if ("note" == ak || "key" == ak) { e.apKind = ak as String; e.ap = clamp(Json.num(m["ap"], 0.0), -12.0, 12.0) }
            val f = Json.map(m["fx"])
            e.rvb = clamp(Json.num(f["rvb"], 0.0), 0.0, 1.0); e.rvbSize = clamp(Json.num(f["rvbSize"], .5), 0.0, 1.0)
            e.echo = clamp(Json.num(f["echo"], 0.0), 0.0, 1.0); e.echoT = clamp(Json.num(f["echoT"], 1.0), 0.0, 3.0).toInt()
            e.dirt = clamp(Json.num(f["dirt"], 0.0), 0.0, 1.0); e.crush = clamp(Json.num(f["crush"], 0.0), 0.0, 1.0)
            e.chorus = clamp(Json.num(f["chorus"], 0.0), 0.0, 1.0); e.wob = clamp(Json.num(f["wob"], 0.0), 0.0, 1.0)
            e.wobRate = clamp(Json.num(f["wobRate"], .3), 0.0, 1.0)
            return e
        }

        @JvmStatic
        fun clamp(v: Double, a: Double, b: Double): Double { return if (v.isNaN()) a else maxOf(a, minOf(b, v)) }

        // Kotlin note: Java's round of a double gives 0 for NaN where roundToLong throws; otherwise the same
        private fun javaRound(d: Double): Long = if (d.isNaN()) 0L else d.roundToLong()
    }
}
