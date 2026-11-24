package com.tideclock.messmachine.engine

import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic
import kotlin.math.roundToLong

/**
 * Settings that belong to a pad rather than its sound: glide, where it is routed, its XY setup.
 * App only: saved under "padOpts" in the mess, which the site ignores.
 */
class PadOpts {
    @JvmField var glide = false
    @JvmField var glideT = .12     // seconds
    @JvmField var legato = false  // glide only when the notes overlap
    @JvmField var route = 0           // 0 = the mix, 1..4 = FX buses
    @JvmField var xy = ""          // the pad's XY setup, encoded (see Xy)
    /** The FannyPack: the strips packed on this pad's sound, in order ("keys,fx,lfo,xy"). Empty = nothing packed. */
    @JvmField var pack = ""

    /*
     * The LFO strip (the build after 84): a slow wave that moves one thing on this pad, and the strip's own filter.
     * Rate is steps per full wave (32 = 2 bars, 16 = 1 bar, 4 = 1/4, 0.5 = 1/32; x 2/3 triplet, x 1.5 dotted).
     */
    @JvmField var lfoOn = false
    @JvmField var lfoWave = 0            // 0 sine, 1 triangle, 2 saw up, 3 saw down, 4 square, 5 random steps
    @JvmField var lfoRate = 4.0
    @JvmField var lfoTarget = 1          // an Engine.T_ target: 1 is the strip's filter cutoff
    @JvmField var lfoDepth = .5
    @JvmField var lfoType = 0            // before build 106 the strip's own filter (0 off, 1 low, 2 high, 3 band); now only read to move an old mess over
    @JvmField var lfoCut = .55; @JvmField var lfoRes = .25
    @JvmField var lfoRetrig = false  // restart the wave on every hit, or run it with the loop

    /**
     * Where the FX strip's faders rest on this pad (build 105), "target=value;target=value", values 0..1.
     * Saved so what you hear is what reloads and what a bounce renders. Absent targets rest at 0.
     */
    @JvmField var fx = ""

    /** Build 106: the pad filter's kind, 1 low pass, 2 high pass, 3 band pass. Everything that moves the filter moves this one. */
    @JvmField var ftype = 1

    /** The resting value of one FX target, or 0. */
    fun fxHome(target: Int): Double {
        val v = fxMap()[target]
        return if (v == null) 0.0 else v
    }

    fun fxMap(): MutableMap<Int, Double> {
        val m: MutableMap<Int, Double> = LinkedHashMap()
        val fx: String? = this.fx  // Kotlin note: Java code can still leave the field null
        if (fx == null || fx.isEmpty()) return m
        for (part in fx.split(";").dropLastWhile { it.isEmpty() }) {
            val e = part.indexOf('=')
            if (e <= 0) continue
            try {
                val t = part.substring(0, e).toInt()
                val v = SoundEdit.clamp(part.substring(e + 1).toDouble(), 0.0, 1.0)
                if (v > 0) m[t] = v
            } catch (ignored: NumberFormatException) {}
        }
        return m
    }

    /** Sets the resting values; zeros are left out. */
    fun fxSet(m: Map<Int, Double>) {
        val b = StringBuilder()
        // Kotlin note: Java walked a TreeMap copy; this walks the entries sorted by target the same way
        for (e in m.entries.sortedBy { it.key }) {
            val v = SoundEdit.clamp(e.value, 0.0, 1.0)
            if (v <= 0) continue
            if (b.length > 0) b.append(';')
            b.append(e.key).append('=').append(jround(v * 1000) / 1000.0)
        }
        fx = b.toString()
    }

    fun lfoCode(): String {
        return (if (lfoOn) 1 else 0).toString() + "," + lfoWave + "," + lfoRate + "," + lfoTarget + "," + lfoDepth + "," + lfoType + "," + lfoCut + "," + lfoRes + "," + (if (lfoRetrig) 1 else 0)
    }

    fun lfoParse(c: String?) {
        if (c == null || c.isEmpty()) return
        try {
            val f = c.split(",").dropLastWhile { it.isEmpty() }
            lfoOn = f[0] == "1"; lfoWave = f[1].toInt(); lfoRate = SoundEdit.clamp(f[2].toDouble(), .125, 64.0)
            lfoTarget = f[3].toInt(); lfoDepth = SoundEdit.clamp(f[4].toDouble(), 0.0, 1.0); lfoType = SoundEdit.clamp(f[5].toInt().toDouble(), 0.0, 3.0).toInt()
            lfoCut = SoundEdit.clamp(f[6].toDouble(), 0.0, 1.0); lfoRes = SoundEdit.clamp(f[7].toDouble(), 0.0, 1.0); lfoRetrig = f[8] == "1"
        } catch (ignored: RuntimeException) {}
    }

    fun copy(): PadOpts {
        val o = PadOpts()
        o.glide = glide; o.glideT = glideT; o.legato = legato; o.route = route; o.xy = xy; o.pack = pack; o.fx = fx; o.ftype = ftype
        o.lfoParse(lfoCode())
        return o
    }

    fun isDefault(): Boolean { return !glide && glideT == .12 && !legato && route == 0 && xy.isEmpty() && pack.isEmpty() && fx.isEmpty() && ftype == 1 && lfoCode() == LFO_DEFAULT }

    fun toMap(): MutableMap<String, Any?> {
        val m: MutableMap<String, Any?> = LinkedHashMap()
        m["glide"] = glide; m["glideT"] = glideT; m["legato"] = legato; m["route"] = route.toDouble()
        if (!xy.isEmpty()) m["xy"] = xy
        if (!pack.isEmpty()) m["pack"] = pack
        if (!fx.isEmpty()) m["fx"] = fx
        m["ftype"] = ftype.toDouble()  // its presence also marks a mess saved by build 106 or later
        if (lfoCode() != LFO_DEFAULT) m["lfo"] = lfoCode()
        return m
    }

    companion object {
        private val LFO_DEFAULT = PadOpts().lfoCode()

        @JvmField val DEFAULT = PadOpts()

        @JvmStatic
        fun fromMap(m: Map<String, Any?>): PadOpts {
            val o = PadOpts()
            o.glide = Json.bool(m["glide"], false)
            o.glideT = SoundEdit.clamp(Json.num(m["glideT"], .12), 0.0, 1.0)
            o.legato = Json.bool(m["legato"], false)
            o.route = SoundEdit.clamp(Json.num(m["route"], 0.0), 0.0, 4.0).toInt()
            o.xy = Json.str(m["xy"], "")
            o.pack = Json.str(m["pack"], "")
            o.fx = Json.str(m["fx"], "")
            o.lfoParse(Json.str(m["lfo"], ""))
            o.ftype = SoundEdit.clamp(Json.num(m["ftype"], 1.0), 1.0, 3.0).toInt()
            // build 106: the LFO no longer has a filter of its own. A mess saved before that heard the LFO's filter
            // (its kind, cutoff and resonance) whenever the LFO was on: those become the pad filter's resting place.
            if (!m.containsKey("ftype") && o.lfoType > 0) {
                if (o.lfoOn) {
                    val h = o.fxMap()
                    h[1] = SoundEdit.clamp(1 - o.lfoCut, 0.0, 1.0)   // Engine.T_FILTER: how far closed
                    h[2] = SoundEdit.clamp(o.lfoRes, 0.0, 1.0)       // Engine.T_RES
                    o.fxSet(h)
                    o.ftype = o.lfoType
                }
                o.lfoType = 0
            }
            return o
        }
    }
}
