package com.tideclock.messmachine.cloud

import com.tideclock.messmachine.engine.Json
import com.tideclock.messmachine.engine.PadOpts
import com.tideclock.messmachine.engine.Project
import com.tideclock.messmachine.engine.SoundEdit
import com.tideclock.messmachine.engine.Synth
import com.tideclock.messmachine.engine.Theory
import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Slice B, "two people in one mess": a change to a mess as small ops (docs/ACCOUNTS-API.md section 3 and 4.5).
 * Common Kotlin, one thread. Nothing here reads a clock, a file or the network.
 *
 * The shared part of a mess is: name, bpm, swing, squash, level, scale, root, fold, the song lane, and for
 * pads 0 to 63 the sound key, its doctor's table, its synth patch, the pad's options, and its row in every
 * loop (steps, accents, chances, off-grid hits, notes, step locks, motion lanes), plus each loop's length.
 * Everything else (sel, cur, mode, mutes, solos, slot 64, unknown keys) stays on the phone: carryLocal.
 */

/** One op: its whole JSON object, "t" included. Fields this app does not know stay in the map and are written back. */
class Op(@JvmField val m: MutableMap<String, Any?>) {
    val t: String get() = Json.str(m["t"], "")
    override fun toString(): String = Json.write(m)
}

/** One local change: the ops that make it, the ops that take it back (in the order to run them), and shownRev when it was made. */
class Change(@JvmField val ops: MutableList<Op>, @JvmField val undo: MutableList<Op>, @JvmField var seenRev: Long)

object Ops {
    const val V = 1
    const val MAX_OPS = 400
    const val MAX_BYTES = 49152
    const val ROW_PUT = 64

    private const val NP = Project.NPADS
    private const val NL = Project.NSLOTS
    private const val SCRATCH = Project.SCRATCH
    private const val NONE = Int.MIN_VALUE   // "no value" for a lock or lane cell, in thousandths

    @JvmField val EDIT_FIELDS: Array<String> = arrayOf("att", "rel", "rev", "loop", "mode", "tune", "fine", "vol", "pan", "tone", "norm", "choke",
        "held", "beatN", "pitch", "bake", "fx.rvb", "fx.rvbSize", "fx.echo", "fx.echoT", "fx.dirt", "fx.crush", "fx.chorus", "fx.wob", "fx.wobRate")
    @JvmField val OPT_FIELDS: Array<String> = arrayOf("glide", "glideT", "legato", "route", "ftype", "xy", "pack",
        "lfo.on", "lfo.wave", "lfo.rate", "lfo.target", "lfo.depth", "lfo.retrig")
    @JvmField val MESS_FIELDS: Array<String> = arrayOf("bpm", "swing", "squash", "level", "scale", "root", "fold", "name")

    /* ================= small readers and makers ================= */

    private fun int(m: Map<String, Any?>, k: String, d: Int): Int { val v = m[k]; return if (v is Number) { val x = v.toDouble(); if (x.isNaN()) d else x.toInt() } else d }
    private fun str(m: Map<String, Any?>, k: String): String = Json.str(m[k], "")
    private fun clampI(v: Int, a: Int, b: Int): Int = maxOf(a, minOf(b, v))
    private fun cl(v: Double, a: Double, b: Double): Double = SoundEdit.clamp(v, a, b)

    private fun mk(t: String): Op { val m: MutableMap<String, Any?> = LinkedHashMap(); m["t"] = t; return Op(m) }
    private fun copyOp(op: Op): Op = Op(LinkedHashMap(op.m))

    /** A value in thousandths, as locks, lanes and FX rest values are written. NONE for "nothing here". */
    private fun r3(v: Float): Int = if (v.isNaN()) NONE else (v * 1000).roundToInt()
    private fun r3d(v: Double): Int = if (v.isNaN()) NONE else (v * 1000).roundToInt()
    private fun un3(k: Int): Float = if (k == NONE) Float.NaN else k / 1000f
    private fun val3(k: Int): Any? = if (k == NONE) null else k / 1000.0

    private fun fmt3(b: StringBuilder, k: Int) {
        var n = k
        if (n < 0) { b.append('-'); n = -n }
        b.append(n / 1000)
        val f = n % 1000
        if (f != 0) {
            b.append('.').append(f / 100)
            val r = f % 100
            if (r != 0) { b.append(r / 10); if (r % 10 != 0) b.append(r % 10) }
        }
    }

    /* ---- hints the app can hand to SyncSession.hint (the session fills in the rest from the mess) ---- */

    /** setLen: loop l is going to len steps; fill true repeats what was there into the new bars. */
    @JvmStatic fun loopLen(l: Int, len: Int, fill: Boolean): Op { val o = mk("loop.len"); o.m["l"] = l.toDouble(); o.m["len"] = len.toDouble(); o.m["fill"] = fill; return o }
    /** copySlotTo. */
    @JvmStatic fun loopCopy(from: Int, to: Int): Op { val o = mk("loop.copy"); o.m["from"] = from.toDouble(); o.m["to"] = to.toDouble(); return o }
    /** clearSlotAt, clearSlot. */
    @JvmStatic fun loopClear(l: Int): Op { val o = mk("loop.clear"); o.m["l"] = l.toDouble(); return o }
    /** swapPads. ka and kb are filled in by Ops.change from what the pads held. */
    @JvmStatic fun padSwap(a: Int, b: Int): Op { val o = mk("pad.swap"); o.m["a"] = a.toDouble(); o.m["b"] = b.toDouble(); return o }
    /** putOnPad, clearPad, newSynth, scratchToPad: pad p is about to hold something else. Ops.change fills in what. */
    @JvmStatic fun padPut(p: Int): Op { val o = mk("pad.put"); o.m["p"] = p.toDouble(); return o }

    /* ================= what an op is ================= */

    private const val K_CELL = 1      // one cell of one pad's row in one loop
    private const val K_PAD = 2       // one field of a pad's sound, options or synth
    private const val K_GLOBAL = 3    // song.set, mess.set
    private const val K_STRUCT = 4
    private const val K_UNKNOWN = 0

    private fun kind(t: String): Int = when (t) {
        "step.set", "acc.set", "chance.set", "off.set", "note.set", "lock.set", "lane.set" -> K_CELL
        "edit.set", "edit.trim", "opt.set", "synth.set" -> K_PAD
        "song.set", "mess.set" -> K_GLOBAL
        "row.put", "loop.len", "loop.clear", "loop.copy", "loop.put", "pad.put", "pad.swap", "doc.reset" -> K_STRUCT
        else -> K_UNKNOWN
    }

    @JvmStatic fun known(op: Op): Boolean = kind(op.t) != K_UNKNOWN
    @JvmStatic fun structural(op: Op): Boolean = kind(op.t) == K_STRUCT

    /** The last-writer-wins key of section 3, with the pad number the op carries; "" for a structural or unknown op. */
    @JvmStatic fun key(op: Op): String {
        val m = op.m
        val l = int(m, "l", -1); val p = int(m, "p", -1)
        return when (op.t) {
            "step.set" -> "s/$l/$p/${int(m, "s", -1)}"
            "acc.set" -> "a/$l/$p/${int(m, "s", -1)}"
            "chance.set" -> "c/$l/$p/${int(m, "s", -1)}"
            "off.set" -> "o/$l/$p/${int(m, "tk", -1)}"
            "note.set" -> "n/$l/$p/${int(m, "s", -1)}/${int(m, "n", 0)}"
            "lock.set" -> "k/$l/$p/${int(m, "g", -1)}/${int(m, "s", -1)}"
            "lane.set" -> "m/$l/$p/${int(m, "g", -1)}/${int(m, "s", -1)}"
            "edit.set" -> "e/$p/${str(m, "f")}"
            "edit.trim" -> "e/$p/trim"
            "opt.set" -> "q/$p/${str(m, "f")}"
            "synth.set" -> "y/$p/${str(m, "id")}"
            "song.set" -> "g/${int(m, "b", -1)}"
            "mess.set" -> "t/${str(m, "f")}"
            else -> ""
        }
    }

    /** The R/, L/ and P/ scopes the op lies in or covers, with the pad numbers the op carries; "*" for doc.reset and unknown ops. */
    @JvmStatic fun scopes(op: Op): List<String> {
        val m = op.m
        val out = ArrayList<String>(3)
        val l = int(m, "l", -1); val p = int(m, "p", -1)
        when (op.t) {
            "step.set", "acc.set", "chance.set", "off.set", "note.set", "lock.set", "lane.set", "row.put" -> { out.add("R/$l/$p"); out.add("L/$l"); out.add("P/$p") }
            "edit.set", "edit.trim", "opt.set", "synth.set", "pad.put" -> out.add("P/$p")
            "song.set", "mess.set" -> {}
            "loop.len", "loop.clear", "loop.put" -> out.add("L/$l")
            "loop.copy" -> out.add("L/${int(m, "to", -1)}")
            "pad.swap" -> { out.add("P/${int(m, "a", -1)}"); out.add("P/${int(m, "b", -1)}") }
            else -> out.add("*")
        }
        return out
    }

    /** True only for the four ops that replace a whole scope: loop.clear, loop.put, loop.copy (its `to`) and pad.put. */
    @JvmStatic fun replaces(op: Op): Boolean = when (op.t) { "loop.clear", "loop.put", "loop.copy", "pad.put" -> true; else -> false }

    /**
     * Who an op is about, by sound rather than by slot: "K:<key>" when the op carries the key its sender saw, else
     * "#<pad>". Two ops for one sound keep one identity across a pad swap, which the slot keys of key() do not.
     */
    @JvmStatic fun sound(op: Op): String { val k = str(op.m, "k"); return if (k.isEmpty()) "#" + int(op.m, "p", -1) else "K:$k" }

    /** key() with the sound in place of the pad number. Used for squashing and for "someone else changed this". */
    @JvmStatic fun ident(op: Op): String {
        val m = op.m
        val l = int(m, "l", -1)
        return when (op.t) {
            "step.set" -> "s/$l/${int(m, "s", -1)}/${sound(op)}"
            "acc.set" -> "a/$l/${int(m, "s", -1)}/${sound(op)}"
            "chance.set" -> "c/$l/${int(m, "s", -1)}/${sound(op)}"
            "off.set" -> "o/$l/${int(m, "tk", -1)}/${sound(op)}"
            "note.set" -> "n/$l/${int(m, "s", -1)}/${int(m, "n", 0)}/${sound(op)}"
            "lock.set" -> "k/$l/${int(m, "g", -1)}/${int(m, "s", -1)}/${sound(op)}"
            "lane.set" -> "m/$l/${int(m, "g", -1)}/${int(m, "s", -1)}/${sound(op)}"
            "edit.set" -> "e/${str(m, "f")}/${sound(op)}"
            "edit.trim" -> "e/trim/${sound(op)}"
            "opt.set" -> "q/${str(m, "f")}/${sound(op)}"
            "synth.set" -> "y/${str(m, "id")}/${sound(op)}"
            "song.set" -> "g/${int(m, "b", -1)}"
            "mess.set" -> "t/${str(m, "f")}"
            else -> ""
        }
    }

    /** The scopes an op lies in, by sound: "R/<l>/<sound>", "L/<l>", "P/<sound>", "P/#<slot>"; "*" for doc.reset and unknown. */
    @JvmStatic fun inScopes(op: Op): List<String> {
        val m = op.m
        val out = ArrayList<String>(4)
        val l = int(m, "l", -1)
        when (kind(op.t)) {
            K_CELL -> { val s = sound(op); out.add("R/$l/$s"); out.add("L/$l"); out.add("P/$s") }
            K_PAD -> out.add("P/" + sound(op))
            K_GLOBAL -> {}
            K_STRUCT -> when (op.t) {
                "row.put" -> { val s = sound(op); out.add("R/$l/$s"); out.add("L/$l"); out.add("P/$s") }
                "loop.len", "loop.clear", "loop.put" -> out.add("L/$l")
                "loop.copy" -> out.add("L/${int(m, "to", -1)}")
                "pad.put" -> {
                    out.add("P/" + sound(op)); out.add("P/#" + int(m, "p", -1))
                    val key = str(m, "key"); if (!key.isEmpty()) out.add("P/K:$key")
                }
                "pad.swap" -> { out.add("P/#" + int(m, "a", -1)); out.add("P/#" + int(m, "b", -1)) }
                else -> out.add("*")
            }
            else -> out.add("*")
        }
        return out
    }

    /** The scopes an op sets as a whole (so a field inside them was changed by it): a row, a loop, a pad's sound or a slot. */
    @JvmStatic fun coverScopes(op: Op): List<String> {
        val m = op.m
        return when (op.t) {
            "row.put" -> listOf("R/${int(m, "l", -1)}/${sound(op)}")
            "loop.len", "loop.clear", "loop.put", "loop.copy", "pad.put", "pad.swap" -> inScopes(op)
            "doc.reset" -> listOf("*")
            else -> if (kind(op.t) == K_UNKNOWN) listOf("*") else emptyList()
        }
    }

    /* ================= JSON ================= */

    @JvmStatic fun toJson(ops: List<Op>): String {
        val l = ArrayList<Any?>(ops.size)
        for (o in ops) l.add(o.m)
        return Json.write(l)
    }

    /** A parsed JSON array into ops. Each op keeps its whole object; anything that is not an object is dropped. */
    @JvmStatic fun fromList(l: List<Any?>): MutableList<Op> {
        val out = ArrayList<Op>(l.size)
        for (o in l) if (o is Map<*, *>) out.add(Op(Json.map(o)))
        return out
    }

    @JvmStatic fun fromJson(text: String): MutableList<Op> = fromList(Json.list(Json.parse(text)))

    private fun utf8(s: String): Int {
        var n = 0
        for (c in s) { val x = c.code; n += if (x < 0x80) 1 else if (x < 0x800) 2 else if (x in 0xD800..0xDFFF) 2 else 3 }
        return n
    }

    /** UTF-8 bytes of one op as it goes on the wire. */
    @JvmStatic fun bytes(op: Op): Int = utf8(Json.write(op.m))

    /** How many leading ops make the next batch: the longest run within 400 ops and 48 KB; one bigger op goes alone. */
    @JvmStatic fun fit(ops: List<Op>): Int {
        var bytes = 2
        var n = 0
        for (op in ops) {
            if (n >= MAX_OPS) break
            val add = bytes(op) + (if (n > 0) 1 else 0)
            if (n > 0 && bytes + add > MAX_BYTES) break
            bytes += add
            n++
        }
        return n
    }

    /* ================= rows: one pad's part of one loop, as the compact strings of MESS-FORMAT s6 ================= */

    private fun lockIs(lockKey: String, k: String): Boolean { val at = lockKey.lastIndexOf('@'); return at == k.length && lockKey.startsWith(k) }
    private fun lockTarget(lockKey: String): Int { val at = lockKey.lastIndexOf('@'); return if (at < 0) -1 else Project.parseIntOr(lockKey.substring(at + 1), -1) }
    private fun lanePad(laneKey: String): Int { val c = laneKey.indexOf(':'); return if (c <= 0) -1 else Project.parseIntOr(laneKey.substring(0, c), -1) }
    private fun laneTarget(laneKey: String): Int { val c = laneKey.indexOf(':'); return if (c <= 0) -1 else Project.parseIntOr(laneKey.substring(c + 1), -1) }

    private fun anyIn(a: FloatArray, len: Int): Boolean { for (j in 0 until minOf(len, a.size)) if (!a[j].isNaN()) return true; return false }

    private fun csv3(a: FloatArray, len: Int): String {
        val b = StringBuilder()
        for (j in 0 until len) { if (j > 0) b.append(','); val k = r3(a[j]); if (k != NONE) fmt3(b, k) }
        return b.toString()
    }

    /** A note list as the shared format sees it: steps inside the loop, pitch and length clamped, one note per step and pitch (the longest). */
    private fun noteMap(l: List<Project.Note>?, len: Int): LinkedHashMap<Int, Int> {
        val out = LinkedHashMap<Int, Int>()
        if (l == null) return out
        for (x in l) {
            if (x.s < 0 || x.s >= len) continue
            val n = clampI(x.p, Theory.ROLL_LO, Theory.ROLL_HI)
            val ln = clampI(x.l, 1, len - x.s)
            val id = x.s * 128 + (n - Theory.ROLL_LO)
            val was = out[id]
            if (was == null || was < ln) out[id] = ln
        }
        return out
    }
    private fun noteStep(id: Int): Int = id / 128
    private fun notePitch(id: Int): Int = id % 128 + Theory.ROLL_LO

    private fun offList(l: List<Int>?, len: Int): ArrayList<Int> {
        val out = ArrayList<Int>()
        if (l == null) return out
        for (t in l) if (t > 0 && t < len * Project.TICKS && t % Project.TICKS != 0 && !out.contains(t)) out.add(t)
        out.sort()
        return out
    }

    /** One pad's row in one loop as its row object; keys with nothing in them are left out, so an empty row is {}. */
    @JvmStatic fun row(p: Project, l: Int, pad: Int): MutableMap<String, Any?> {
        val m: MutableMap<String, Any?> = LinkedHashMap()
        if (l < 0 || l >= NL || pad < 0 || pad >= NP) return m
        val k = p.kit[pad]
        if (k.isEmpty()) return m
        val P = p.pats[l]
        val len = P.len
        val st = P.steps[k]
        if (st != null) {
            var any = false
            for (j in 0 until len) if (st[j]) { any = true; break }
            if (any) { val b = StringBuilder(len); for (j in 0 until len) b.append(if (st[j]) '1' else '0'); m["steps"] = b.toString() }
        }
        val ac = P.acc[k]
        if (ac != null) {
            var any = false
            for (j in 0 until len) if (ac[j].toInt() != 1) { any = true; break }
            if (any) { val b = StringBuilder(len); for (j in 0 until len) { val v = ac[j].toInt(); b.append(if (v == 0) 'g' else if (v == 2) 'a' else '.') }; m["acc"] = b.toString() }
        }
        val ch = P.chance[k]
        if (ch != null) {
            var any = false
            for (j in 0 until len) if (ch[j].toInt() != 100) { any = true; break }
            if (any) { val b = StringBuilder(); for (j in 0 until len) { if (j > 0) b.append(','); val v = ch[j].toInt(); if (v != 100) b.append(v) }; m["chance"] = b.toString() }
        }
        val of = offList(P.offs[k], len)
        if (!of.isEmpty()) { val b = StringBuilder(); for (t in of) { if (b.length > 0) b.append(','); b.append(t) }; m["offs"] = b.toString() }
        val nm = noteMap(P.notes[k], len)
        if (!nm.isEmpty()) {
            val ids = ArrayList(nm.keys); ids.sort()
            val b = StringBuilder()
            for (id in ids) { if (b.length > 0) b.append(','); b.append(noteStep(id)).append('.').append(notePitch(id)).append('.').append(nm[id]) }
            m["notes"] = b.toString()
        }
        var gs: ArrayList<Int>? = null
        for (e in P.locks.entries) if (lockIs(e.key, k) && anyIn(e.value, len)) { val g = lockTarget(e.key); if (g >= 0) { if (gs == null) gs = ArrayList(); if (!gs.contains(g)) gs.add(g) } }
        if (gs != null) {
            gs.sort()
            val lm: MutableMap<String, Any?> = LinkedHashMap()
            for (g in gs) { val a = P.locks["$k@$g"]; if (a != null) lm[g.toString()] = csv3(a, len) }
            if (!lm.isEmpty()) m["locks"] = lm
        }
        gs = null
        for (e in P.motion.entries) if (lanePad(e.key) == pad && anyIn(e.value, len)) { val g = laneTarget(e.key); if (g >= 0) { if (gs == null) gs = ArrayList(); if (!gs.contains(g)) gs.add(g) } }
        if (gs != null) {
            gs.sort()
            val mm: MutableMap<String, Any?> = LinkedHashMap()
            for (g in gs) { val a = P.motion["$pad:$g"]; if (a != null) mm[g.toString()] = csv3(a, len) }
            if (!mm.isEmpty()) m["motion"] = mm
        }
        return m
    }

    private fun forgetRows(P: Project.Pattern, k: String) {
        P.steps.remove(k); P.notes.remove(k); P.offs.remove(k); P.acc.remove(k); P.chance.remove(k)
        if (!P.locks.isEmpty()) P.locks.keys.removeAll { x -> lockIs(x, k) }
    }
    private fun forgetLanes(P: Project.Pattern, pad: Int) { if (!P.motion.isEmpty()) P.motion.keys.removeAll { x -> lanePad(x) == pad } }

    /** Writes a row object onto pad `pad` (holding key k) in loop P. The row must have been cleared first. */
    private fun putRow(P: Project.Pattern, k: String, pad: Int, r: Map<String, Any?>) {
        val len = P.len
        val st = Json.str(r["steps"], "")
        for (j in 0 until minOf(st.length, len)) if (st[j] == '1') P.setStep(k, j, true)
        val ac = Json.str(r["acc"], "")
        for (j in 0 until minOf(ac.length, len)) { val c = ac[j]; if (c == 'g') P.setStrength(k, j, 0) else if (c == 'a') P.setStrength(k, j, 2) }
        val ch = Json.str(r["chance"], "")
        if (!ch.isEmpty()) {
            val f = ch.split(",")
            for (j in 0 until minOf(f.size, len)) { val x = f[j].trim(); if (!x.isEmpty()) P.setChance(k, j, Project.parseIntOr(x, 100)) }
        }
        val of = Json.str(r["offs"], "")
        if (!of.isEmpty()) for (x in of.split(",")) { val t = Project.parseIntOr(x.trim(), -1); if (t > 0 && t < len * Project.TICKS) P.addOff(k, t) }
        val ns = Json.str(r["notes"], "")
        if (!ns.isEmpty()) {
            val tmp = ArrayList<Project.Note>()
            for (x in ns.split(",")) {
                val f = x.split(".")
                if (f.size < 3) continue
                val s = Project.parseIntOr(f[0].trim(), -1); val n = Project.parseIntOr(f[1].trim(), NONE); val ln = Project.parseIntOr(f[2].trim(), NONE)
                if (s < 0 || s >= len || n == NONE || ln == NONE) continue
                tmp.add(Project.Note(s, n, ln))
            }
            val nm = noteMap(tmp, len)
            if (!nm.isEmpty()) { val l = P.notes(k); for (e in nm.entries) l.add(Project.Note(noteStep(e.key), notePitch(e.key), e.value)) }
        }
        for (e in Json.map(r["locks"]).entries) {
            val g = Project.parseIntOr(e.key, -1)
            if (g < 0) continue
            val f = Json.str(e.value, "").split(",")
            for (j in 0 until minOf(f.size, len)) if (!f[j].isEmpty()) { val v = f[j].toDoubleOrNull(); if (v != null && !v.isNaN()) P.setLock(k, g, j, un3(r3d(v))) }
        }
        for (e in Json.map(r["motion"]).entries) {
            val g = Project.parseIntOr(e.key, -1)
            if (g < 0) continue
            val f = Json.str(e.value, "").split(",")
            for (j in 0 until minOf(f.size, len)) if (!f[j].isEmpty()) { val v = f[j].toDoubleOrNull(); if (v != null && !v.isNaN()) P.setMotion(pad, g, j, un3(r3d(v))) }
        }
    }

    /* ================= the doctor's table, pad options, synths and mess settings, field by field ================= */

    private fun editGet(e: SoundEdit, f: String): Any? = when (f) {
        "att" -> cl(e.att, 0.0, 4.0); "rel" -> cl(e.rel, 0.0, 4.0)
        "rev" -> e.rev; "loop" -> e.loop; "norm" -> e.norm; "bake" -> e.bake
        "mode" -> if ("gate" == e.mode) "gate" else "one"
        "tune" -> cl(e.tune, -48.0, 48.0); "fine" -> cl(e.fine, -100.0, 100.0)
        "vol" -> cl(e.vol, 0.0, 4.0); "pan" -> cl(e.pan, -1.0, 1.0); "tone" -> cl(e.tone, -1.0, 1.0)
        "choke" -> clampI(e.choke, 0, 8).toDouble()
        "held" -> if ("tail" == e.held || "once" == e.held || "sustain" == e.held || "beat" == e.held) e.held else "loop"
        "beatN" -> (if (e.beatN == 2 || e.beatN == 4) e.beatN else 1).toDouble()
        "pitch" -> if ("stretch" == e.pitch || "grain" == e.pitch) e.pitch else "auto"
        "fx.rvb" -> cl(e.rvb, 0.0, 1.0); "fx.rvbSize" -> cl(e.rvbSize, 0.0, 1.0); "fx.echo" -> cl(e.echo, 0.0, 1.0)
        "fx.echoT" -> clampI(e.echoT, 0, 3).toDouble()
        "fx.dirt" -> cl(e.dirt, 0.0, 1.0); "fx.crush" -> cl(e.crush, 0.0, 1.0); "fx.chorus" -> cl(e.chorus, 0.0, 1.0)
        "fx.wob" -> cl(e.wob, 0.0, 1.0); "fx.wobRate" -> cl(e.wobRate, 0.0, 1.0)
        else -> null
    }

    /** false: not a field, or the value has the wrong type (the op is junk and does nothing). */
    private fun editSet(e: SoundEdit, f: String, v: Any?): Boolean {
        when (f) {
            "rev" -> { if (v !is Boolean) return false; e.rev = v }
            "loop" -> { if (v !is Boolean) return false; e.loop = v }
            "norm" -> { if (v !is Boolean) return false; e.norm = v }
            "bake" -> { if (v !is Boolean) return false; e.bake = v }
            "mode" -> { if (v !is String) return false; e.mode = if ("gate" == v) "gate" else "one" }
            "held" -> { if (v !is String) return false; e.held = if ("tail" == v || "once" == v || "sustain" == v || "beat" == v) v else "loop" }
            "pitch" -> { if (v !is String) return false; e.pitch = if ("stretch" == v || "grain" == v) v else "auto" }
            else -> {
                if (v !is Number) return false
                val d = v.toDouble()
                when (f) {
                    "att" -> e.att = cl(d, 0.0, 4.0); "rel" -> e.rel = cl(d, 0.0, 4.0)
                    "tune" -> e.tune = cl(d, -48.0, 48.0); "fine" -> e.fine = cl(d, -100.0, 100.0)
                    "vol" -> e.vol = cl(d, 0.0, 4.0); "pan" -> e.pan = cl(d, -1.0, 1.0); "tone" -> e.tone = cl(d, -1.0, 1.0)
                    "choke" -> e.choke = cl(d, 0.0, 8.0).toInt()
                    "beatN" -> { val b = cl(d, 0.0, 8.0).roundToInt(); e.beatN = if (b == 2 || b == 4) b else 1 }
                    "fx.rvb" -> e.rvb = cl(d, 0.0, 1.0); "fx.rvbSize" -> e.rvbSize = cl(d, 0.0, 1.0); "fx.echo" -> e.echo = cl(d, 0.0, 1.0)
                    "fx.echoT" -> e.echoT = cl(d, 0.0, 3.0).toInt()
                    "fx.dirt" -> e.dirt = cl(d, 0.0, 1.0); "fx.crush" -> e.crush = cl(d, 0.0, 1.0); "fx.chorus" -> e.chorus = cl(d, 0.0, 1.0)
                    "fx.wob" -> e.wob = cl(d, 0.0, 1.0); "fx.wobRate" -> e.wobRate = cl(d, 0.0, 1.0)
                    else -> return false
                }
            }
        }
        return true
    }

    private fun trimSt(e: SoundEdit): Double { val st = cl(e.st, 0.0, 1.0); val en = cl(e.en, 0.0, 1.0); return if (en <= st) 0.0 else st }
    private fun trimEn(e: SoundEdit): Double { val st = cl(e.st, 0.0, 1.0); val en = cl(e.en, 0.0, 1.0); return if (en <= st) 1.0 else en }

    /** A sound's settings as save then load would leave them. */
    private fun normEdit(e: SoundEdit): SoundEdit = SoundEdit.fromMap(e.toMap())

    private fun sameEditRaw(a: SoundEdit, b: SoundEdit): Boolean =
        a === b || (a.st == b.st && a.en == b.en && a.att == b.att && a.rel == b.rel && a.rev == b.rev && a.loop == b.loop && a.norm == b.norm
            && a.mode == b.mode && a.tune == b.tune && a.fine == b.fine && a.vol == b.vol && a.pan == b.pan && a.tone == b.tone && a.choke == b.choke
            && a.held == b.held && a.beatN == b.beatN && a.pitch == b.pitch && a.bake == b.bake && a.rvb == b.rvb && a.rvbSize == b.rvbSize
            && a.echo == b.echo && a.echoT == b.echoT && a.dirt == b.dirt && a.crush == b.crush && a.chorus == b.chorus && a.wob == b.wob && a.wobRate == b.wobRate)

    private fun optGet(o: PadOpts, f: String): Any? = when (f) {
        "glide" -> o.glide; "legato" -> o.legato
        "glideT" -> cl(o.glideT, 0.0, 1.0)
        "route" -> clampI(o.route, 0, 4).toDouble()
        "ftype" -> clampI(o.ftype, 1, 3).toDouble()
        "xy" -> o.xy; "pack" -> o.pack
        "lfo.on" -> o.lfoOn; "lfo.retrig" -> o.lfoRetrig
        "lfo.wave" -> o.lfoWave.toDouble(); "lfo.target" -> o.lfoTarget.toDouble()
        "lfo.rate" -> cl(o.lfoRate, .125, 64.0); "lfo.depth" -> cl(o.lfoDepth, 0.0, 1.0)
        else -> if (f.startsWith("fx.")) { val g = Project.parseIntOr(f.substring(3), -1); if (g < 0) null else r3d(o.fxHome(g)) / 1000.0 } else null
    }

    private fun optSet(o: PadOpts, f: String, v: Any?): Boolean {
        when (f) {
            "glide" -> { if (v !is Boolean) return false; o.glide = v }
            "legato" -> { if (v !is Boolean) return false; o.legato = v }
            "lfo.on" -> { if (v !is Boolean) return false; o.lfoOn = v }
            "lfo.retrig" -> { if (v !is Boolean) return false; o.lfoRetrig = v }
            "xy" -> { if (v !is String) return false; o.xy = v }
            "pack" -> { if (v !is String) return false; o.pack = v }
            else -> {
                if (v !is Number) return false
                val d = v.toDouble()
                when (f) {
                    "glideT" -> o.glideT = cl(d, 0.0, 1.0)
                    "route" -> o.route = cl(d, 0.0, 4.0).toInt()
                    "ftype" -> o.ftype = cl(d, 1.0, 3.0).toInt()
                    "lfo.wave" -> o.lfoWave = cl(d, -1000.0, 1000.0).toInt()
                    "lfo.target" -> o.lfoTarget = cl(d, -1000.0, 1000.0).toInt()
                    "lfo.rate" -> o.lfoRate = cl(d, .125, 64.0)
                    "lfo.depth" -> o.lfoDepth = cl(d, 0.0, 1.0)
                    else -> {
                        if (!f.startsWith("fx.")) return false
                        val g = Project.parseIntOr(f.substring(3), -1)
                        if (g < 0) return false
                        val h = o.fxMap()
                        val k = r3d(cl(d, 0.0, 1.0))
                        if (k <= 0) h.remove(g) else h[g] = k / 1000.0
                        o.fxSet(h)
                    }
                }
            }
        }
        return true
    }

    /** Equal in every synced option (the LFO's old filter fields are left out: they never sync). */
    private fun sameOptsRaw(a: PadOpts, b: PadOpts): Boolean =
        a === b || (a.glide == b.glide && a.glideT == b.glideT && a.legato == b.legato && a.route == b.route && a.xy == b.xy && a.pack == b.pack
            && a.fx == b.fx && a.ftype == b.ftype && a.lfoOn == b.lfoOn && a.lfoWave == b.lfoWave && a.lfoRate == b.lfoRate
            && a.lfoTarget == b.lfoTarget && a.lfoDepth == b.lfoDepth && a.lfoRetrig == b.lfoRetrig)

    private fun fxCanon(o: PadOpts): String {
        if (o.fx.isEmpty()) return ""
        val h = o.fxMap()
        val out: MutableMap<Int, Double> = LinkedHashMap()
        for (e in h.entries) { val k = r3d(e.value); if (k > 0) out[e.key] = k / 1000.0 }
        val t = PadOpts(); t.fxSet(out)
        return t.fx
    }

    /** A pad's options as they travel, or null when every synced option is at its default. */
    private fun optsMap(o: PadOpts?): MutableMap<String, Any?>? {
        if (o == null) return null
        val fx = fxCanon(o)
        val lfoDef = !o.lfoOn && o.lfoWave == 0 && cl(o.lfoRate, .125, 64.0) == 4.0 && o.lfoTarget == 1 && cl(o.lfoDepth, 0.0, 1.0) == .5 && !o.lfoRetrig
        if (!o.glide && cl(o.glideT, 0.0, 1.0) == .12 && !o.legato && clampI(o.route, 0, 4) == 0 && o.xy.isEmpty() && o.pack.isEmpty() && fx.isEmpty()
            && clampI(o.ftype, 1, 3) == 1 && lfoDef) return null
        val c = PadOpts()
        c.glide = o.glide; c.glideT = cl(o.glideT, 0.0, 1.0); c.legato = o.legato; c.route = clampI(o.route, 0, 4); c.xy = o.xy; c.pack = o.pack
        c.fx = fx; c.ftype = clampI(o.ftype, 1, 3)
        c.lfoOn = o.lfoOn; c.lfoWave = o.lfoWave; c.lfoRate = cl(o.lfoRate, .125, 64.0); c.lfoTarget = o.lfoTarget
        c.lfoDepth = cl(o.lfoDepth, 0.0, 1.0); c.lfoRetrig = o.lfoRetrig
        return c.toMap()
    }

    private fun copyOpts(o: PadOpts): PadOpts {
        val c = PadOpts()
        c.glide = o.glide; c.glideT = o.glideT; c.legato = o.legato; c.route = o.route; c.xy = o.xy; c.pack = o.pack; c.fx = o.fx; c.ftype = o.ftype
        c.lfoOn = o.lfoOn; c.lfoWave = o.lfoWave; c.lfoRate = o.lfoRate; c.lfoTarget = o.lfoTarget; c.lfoDepth = o.lfoDepth
        c.lfoType = o.lfoType; c.lfoCut = o.lfoCut; c.lfoRes = o.lfoRes; c.lfoRetrig = o.lfoRetrig
        return c
    }

    private fun messName(n: String?): String = if (n == null || n.isEmpty()) "Untitled mess" else if (n.length > 48) n.substring(0, 48) else n
    private fun messSquash(v: Double): Double { val c = cl(v, 0.0, 1.0); return if (abs(c - .5) > 1e-6) c else .5 }
    private fun messLevel(v: Double): Double { val c = cl(v, 0.0, 1.6); return if (abs(c - 1) > 1e-6) c else 1.0 }
    private fun messScale(s: String): String = if (Theory.SCALES.containsKey(s)) s else "Chromatic"

    private fun messGet(p: Project, f: String): Any? = when (f) {
        "bpm" -> clampI(p.bpm, 60, 180).toDouble()
        "swing" -> clampI(p.swing, 0, 100).toDouble()
        "squash" -> messSquash(p.squash)
        "level" -> messLevel(p.level)
        "scale" -> messScale(p.scale)
        "root" -> clampI(p.root, 0, 11).toDouble()
        "fold" -> p.fold
        "name" -> messName(p.name)
        else -> null
    }

    private fun messSet(p: Project, f: String, v: Any?) {
        when (f) {
            "bpm" -> if (v is Number) p.bpm = cl(v.toDouble(), 60.0, 180.0).toInt()
            "swing" -> if (v is Number) p.swing = cl(v.toDouble(), 0.0, 100.0).toInt()
            "squash" -> if (v is Number) p.squash = messSquash(v.toDouble())
            "level" -> if (v is Number) p.level = messLevel(v.toDouble())
            "scale" -> if (v is String) p.scale = messScale(v)
            "root" -> if (v is Number) p.root = cl(v.toDouble(), 0.0, 11.0).toInt()
            "fold" -> if (v is Boolean) p.fold = v
            "name" -> if (v is String) p.name = messName(v)
        }
    }

    /* ================= apply ================= */

    /** Which pad an op means: pad p if it holds k, else the pad that now holds k, else p. -1 when p is no pad. */
    private fun resolve(p: Project, pi: Int, k: String): Int {
        if (pi < 0 || pi >= NP) return -1
        if (k.isEmpty() || p.kit[pi] == k) return pi
        val q = p.padOf(k)
        return if (q >= 0) q else pi
    }

    /** The pad a field op lands on, or -1 when it names no pad or the pad is empty (an op for an empty pad does nothing). */
    private fun target(p: Project, m: Map<String, Any?>): Int {
        val t = resolve(p, int(m, "p", -1), str(m, "k"))
        return if (t < 0 || p.kit[t].isEmpty()) -1 else t
    }

    private fun loopOf(p: Project, m: Map<String, Any?>): Project.Pattern? { val l = int(m, "l", -1); return if (l < 0 || l >= NL) null else p.pats[l] }

    private fun purgeKey(p: Project, k: String) {
        for (P in p.pats) forgetRows(P, k)
        p.edits.remove(k); p.synths.remove(k); p.mutes.remove(k); p.solos.remove(k)
    }

    /** A pad with nothing on it and nothing left behind: its sound's rows, settings and patch go, and the pad's lanes and options. */
    private fun emptyPad(p: Project, t: Int) {
        p.opts[t] = null
        for (P in p.pats) forgetLanes(P, t)
        val w = p.kit[t]
        if (w.isEmpty()) return
        p.kit[t] = ""
        purgeKey(p, w)
    }

    private fun renameKey(p: Project, old: String, nk: String) {
        for (P in p.pats) {
            val s = P.steps.remove(old); if (s != null) P.steps[nk] = s
            val n = P.notes.remove(old); if (n != null) P.notes[nk] = n
            val o = P.offs.remove(old); if (o != null) P.offs[nk] = o
            val a = P.acc.remove(old); if (a != null) P.acc[nk] = a
            val c = P.chance.remove(old); if (c != null) P.chance[nk] = c
            if (!P.locks.isEmpty()) {
                var move: ArrayList<String>? = null
                for (lk in P.locks.keys) if (lockIs(lk, old)) { if (move == null) move = ArrayList(); move.add(lk) }
                if (move != null) for (lk in move) { val a2 = P.locks.remove(lk); if (a2 != null) P.locks[nk + lk.substring(old.length)] = a2 }
            }
        }
        val e = p.edits.remove(old); if (e != null) p.edits[nk] = e
        val sp = p.synths.remove(old); if (sp != null) p.synths[nk] = sp
        if (p.mutes.remove(old)) p.mutes.add(nk)
        if (p.solos.remove(old)) p.solos.add(nk)
    }

    private fun onAnySlot(p: Project, k: String): Boolean { for (x in p.kit) if (x == k) return true; return false }

    /** The scratch synth (slot 64, this phone only) gives up its key when a pad needs it. */
    private fun moveScratch(p: Project) {
        val old = p.kit[SCRATCH]
        if (old.isEmpty()) return
        var n = 1
        while (p.synths.containsKey("synth:$n") || onAnySlot(p, "synth:$n")) n++
        val nk = "synth:$n"
        p.kit[SCRATCH] = nk
        renameKey(p, old, nk)
    }

    /** The lowest synth:<n> on none of the 64 pads. */
    private fun freeSynthKey(p: Project): String { var n = 1; while (p.padOf("synth:$n") >= 0) n++; return "synth:$n" }

    /** The key a pad.put ends up under on pad t: its own, or the lowest free synth key when it is new and its key is taken. */
    private fun putKey(p: Project, m: Map<String, Any?>, t: Int): String {
        val k = str(m, "key")
        if (k.isEmpty() || m["new"] != true || !Project.isSynth(k)) return k
        val q = p.padOf(k)
        return if (q >= 0 && q != t) freeSynthKey(p) else k
    }

    private fun shrinkTo(P: Project.Pattern, n: Int) {
        val old = P.len
        if (n >= old) return
        for (a in P.steps.values) for (j in n until a.size) a[j] = false
        for (l in P.notes.values) { l.removeAll { x -> x.s >= n }; for (x in l) x.l = minOf(x.l, n - x.s) }
        for (a in P.motion.values) for (j in n until a.size) a[j] = Float.NaN
        P.extrasFor(n, old, false)
        P.len = n
    }

    /** loop.len: what AppState.setLen does, bar for bar. */
    private fun setLen(P: Project.Pattern, n: Int, fill: Boolean) {
        val old = P.len
        if (n == old || n < 16 || n > Project.MAXSTEPS || n % 16 != 0) return
        if (n < old) { shrinkTo(P, n); return }
        if (fill) {
            for (a in P.steps.values) for (j in old until n) a[j] = a[j % old]
            for (l in P.notes.values) {
                val base = ArrayList<Project.Note>()
                for (x in l) if (x.s < old) base.add(x)
                for (rep in 1 until n / old) for (x in base) l.add(Project.Note(x.s + rep * old, x.p, x.l))
            }
            for (a in P.motion.values) for (j in old until n) a[j] = a[j % old]
        } else {
            for (a in P.steps.values) for (j in old until n) a[j] = false
            for (l in P.notes.values) l.removeAll { x -> x.s >= old }
            for (a in P.motion.values) for (j in old until n) a[j] = Float.NaN
        }
        P.extrasFor(old, n, fill)
        P.len = n
    }

    /** Empties the shared part of a loop: every pad's row and lanes. Slot 64's stay (they belong to this phone). */
    private fun clearShared(p: Project, P: Project.Pattern) {
        val sk = p.kit[SCRATCH]
        P.steps.keys.removeAll { x -> x != sk }
        P.notes.keys.removeAll { x -> x != sk }
        P.offs.keys.removeAll { x -> x != sk }
        P.acc.keys.removeAll { x -> x != sk }
        P.chance.keys.removeAll { x -> x != sk }
        P.locks.keys.removeAll { x -> sk.isEmpty() || !lockIs(x, sk) }
        P.motion.keys.removeAll { x -> lanePad(x) != SCRATCH }
    }

    private fun copyShared(p: Project, F: Project.Pattern, T: Project.Pattern) {
        val sk = p.kit[SCRATCH]
        for (e in F.steps.entries) if (e.key != sk) T.steps[e.key] = e.value.copyOf()
        for (e in F.notes.entries) if (e.key != sk) { val l = ArrayList<Project.Note>(e.value.size); for (n in e.value) l.add(n.copy()); T.notes[e.key] = l }
        for (e in F.offs.entries) if (e.key != sk) T.offs[e.key] = ArrayList(e.value)
        for (e in F.acc.entries) if (e.key != sk) T.acc[e.key] = e.value.copyOf()
        for (e in F.chance.entries) if (e.key != sk) T.chance[e.key] = e.value.copyOf()
        for (e in F.locks.entries) if (sk.isEmpty() || !lockIs(e.key, sk)) T.locks[e.key] = e.value.copyOf()
        for (e in F.motion.entries) if (lanePad(e.key) != SCRATCH) T.motion[e.key] = e.value.copyOf()
    }

    private fun applyPadPut(p: Project, m: Map<String, Any?>) {
        val t = resolve(p, int(m, "p", -1), str(m, "k"))
        if (t < 0) return
        val key = str(m, "key")
        if (key.isEmpty()) { emptyPad(p, t); return }
        val sm = m["synth"]
        if (Project.isSynth(key) && sm !is Map<*, *>) return   // a synth with no patch is nothing
        val k = putKey(p, m, t)
        if (k == key) { val q = p.padOf(k); if (q >= 0 && q != t) emptyPad(p, q) }   // a sound lives on one pad: this is a move
        if (p.kit[SCRATCH] == k) moveScratch(p)
        emptyPad(p, t)
        purgeKey(p, k)
        p.kit[t] = k
        if (Project.isSynth(k)) p.synths[k] = Synth.Patch.fromMap(Json.map(sm))
        val em = m["edit"]
        if (em is Map<*, *>) { val se = SoundEdit.fromMap(Json.map(em)); if (!se.isDefault()) p.edits[k] = se }
        val om = m["opts"]
        if (om is Map<*, *>) { val po = PadOpts.fromMap(Json.map(om)); if (!po.isDefault()) p.opts[t] = po }
        for (e in Json.map(m["loops"]).entries) {
            val l = Project.parseIntOr(e.key, -1)
            if (l >= 0 && l < NL && e.value is Map<*, *>) putRow(p.pats[l], k, t, Json.map(e.value))
        }
    }

    private fun applyLoopPut(p: Project, m: Map<String, Any?>) {
        val P = loopOf(p, m) ?: return
        val len = int(m, "len", -1)
        if (len < 16 || len > Project.MAXSTEPS || len % 16 != 0) return
        shrinkTo(P, len)
        clearShared(p, P)
        P.len = len
        val keys = Json.map(m["keys"])
        for (e in Json.map(m["pads"]).entries) {
            val pn = Project.parseIntOr(e.key, -1)
            val t = resolve(p, pn, Json.str(keys[e.key], ""))
            if (t < 0 || p.kit[t].isEmpty() || e.value !is Map<*, *>) continue
            forgetRows(P, p.kit[t]); forgetLanes(P, t)
            putRow(P, p.kit[t], t, Json.map(e.value))
        }
    }

    /** One op, in place. false: this app does not know the op (it is skipped). A known op with bad fields does nothing. */
    private fun applyOne(p: Project, op: Op): Boolean {
        val m = op.m
        when (op.t) {
            "step.set" -> {
                val P = loopOf(p, m); val t = target(p, m); val s = int(m, "s", -1); val on = m["on"]
                if (P != null && t >= 0 && s >= 0 && s < P.len && on is Boolean) P.setStep(p.kit[t], s, on)
            }
            "acc.set" -> {
                val P = loopOf(p, m); val t = target(p, m); val s = int(m, "s", -1); val v = m["v"]
                if (P != null && t >= 0 && s >= 0 && s < P.len && v is Number) P.setStrength(p.kit[t], s, int(m, "v", 1))
            }
            "chance.set" -> {
                val P = loopOf(p, m); val t = target(p, m); val s = int(m, "s", -1); val v = m["v"]
                if (P != null && t >= 0 && s >= 0 && s < P.len && v is Number) P.setChance(p.kit[t], s, int(m, "v", 100))
            }
            "off.set" -> {
                val P = loopOf(p, m); val t = target(p, m); val tk = int(m, "tk", -1); val on = m["on"]
                if (P != null && t >= 0 && tk > 0 && tk < P.len * Project.TICKS && tk % Project.TICKS != 0 && on is Boolean) {
                    if (on) P.addOff(p.kit[t], tk) else { val l = P.offs[p.kit[t]]; if (l != null) { l.remove(tk); if (l.isEmpty()) P.offs.remove(p.kit[t]) } }
                }
            }
            "note.set" -> {
                val P = loopOf(p, m); val t = target(p, m); val s = int(m, "s", -1)
                if (P != null && t >= 0 && s >= 0 && s < P.len && m["n"] is Number && m["len"] is Number) {
                    val n = clampI(int(m, "n", 0), Theory.ROLL_LO, Theory.ROLL_HI); val ln = int(m, "len", 0)
                    val k = p.kit[t]
                    val l = P.notes[k]
                    if (l != null) l.removeAll { x -> x.s == s && clampI(x.p, Theory.ROLL_LO, Theory.ROLL_HI) == n }
                    if (ln > 0) P.notes(k).add(Project.Note(s, n, clampI(ln, 1, P.len - s)))
                    else if (l != null && l.isEmpty()) P.notes.remove(k)
                }
            }
            "lock.set" -> {
                val P = loopOf(p, m); val t = target(p, m); val s = int(m, "s", -1); val g = int(m, "g", -1); val v = m["v"]
                if (P != null && t >= 0 && g >= 0 && s >= 0 && s < P.len && (v == null || v is Number))
                    P.setLock(p.kit[t], g, s, if (v is Number) un3(r3d(v.toDouble())) else Float.NaN)
            }
            "lane.set" -> {
                val P = loopOf(p, m); val t = target(p, m); val s = int(m, "s", -1); val g = int(m, "g", -1); val v = m["v"]
                if (P != null && t >= 0 && g >= 0 && s >= 0 && s < P.len && (v == null || v is Number)) {
                    val x = if (v is Number) un3(r3d(v.toDouble())) else Float.NaN
                    if (!x.isNaN()) P.setMotion(t, g, s, x)
                    else { val lk = "$t:$g"; val a = P.motion[lk]; if (a != null) { a[s] = Float.NaN; if (!anyIn(a, a.size)) P.motion.remove(lk) } }
                }
            }
            "edit.set" -> {
                val t = target(p, m)
                if (t >= 0) {
                    val k = p.kit[t]
                    val had = p.edits[k]
                    val e = had ?: SoundEdit()
                    if (editSet(e, str(m, "f"), m["v"])) { if (e.isDefault()) p.edits.remove(k) else if (had == null) p.edits[k] = e }
                }
            }
            "edit.trim" -> {
                val t = target(p, m); val a = m["st"]; val b = m["en"]
                if (t >= 0 && a is Number && b is Number) {
                    val k = p.kit[t]
                    val had = p.edits[k]
                    val e = had ?: SoundEdit()
                    e.st = cl(a.toDouble(), 0.0, 1.0); e.en = cl(b.toDouble(), 0.0, 1.0)
                    if (e.en <= e.st) { e.st = 0.0; e.en = 1.0 }
                    if (e.isDefault()) p.edits.remove(k) else if (had == null) p.edits[k] = e
                }
            }
            "opt.set" -> {
                val t = target(p, m)
                if (t >= 0) {
                    val had = p.opts[t]
                    val o = had ?: PadOpts()
                    if (optSet(o, str(m, "f"), m["v"])) p.opts[t] = if (o.isDefault()) null else o
                }
            }
            "synth.set" -> {
                val t = target(p, m)
                val sp = if (t >= 0) p.synths[p.kit[t]] else null
                if (sp != null) {
                    val id = str(m, "id"); val v = m["v"]
                    if (id == "@name") { if (v is String) sp.name = v }
                    else if (id == "@src") { if (v is String) sp.src = v }
                    else if (v is Number && !v.toDouble().isNaN()) sp.set(id, v.toDouble())
                }
            }
            "song.set" -> { val b = int(m, "b", -1); if (b >= 0 && b < Project.MAXSONG && m["v"] is Number) p.song[b] = clampI(int(m, "v", -1), -1, NL - 1) }
            "mess.set" -> messSet(p, str(m, "f"), m["v"])
            "row.put" -> {
                val P = loopOf(p, m); val t = target(p, m); val r = m["row"]
                if (P != null && t >= 0 && r is Map<*, *>) { forgetRows(P, p.kit[t]); forgetLanes(P, t); putRow(P, p.kit[t], t, Json.map(r)) }
            }
            "loop.len" -> { val P = loopOf(p, m); if (P != null) setLen(P, int(m, "len", -1), m["fill"] == true) }
            "loop.clear" -> { val P = loopOf(p, m); if (P != null) { clearShared(p, P); P.extra.clear() } }
            "loop.copy" -> {
                val f = int(m, "from", -1); val to = int(m, "to", -1)
                if (f >= 0 && f < NL && to >= 0 && to < NL && f != to) {
                    val F = p.pats[f]; val T = p.pats[to]
                    shrinkTo(T, F.len)
                    clearShared(p, T)
                    T.len = F.len
                    copyShared(p, F, T)
                }
            }
            "loop.put" -> applyLoopPut(p, m)
            "pad.put" -> applyPadPut(p, m)
            "pad.swap" -> {
                val a = int(m, "a", -1); val b = int(m, "b", -1)
                if (a >= 0 && a < NP && b >= 0 && b < NP && a != b && p.kit[a] == str(m, "ka") && p.kit[b] == str(m, "kb")) p.swapPads(a, b)
            }
            "doc.reset" -> {}
            else -> return false
        }
        return true
    }

    /** Applies in order, in place, by the rules of section 3. Returns how many ops it skipped as unknown. */
    @JvmStatic fun apply(p: Project, ops: List<Op>): Int {
        var skipped = 0
        for (op in ops) if (!applyOne(p, op)) skipped++
        return skipped
    }

    /* ================= building the structural ops from a mess ================= */

    /** Everything pad `pad` of p holds, written into a pad.put's map: key, edit, synth, opts, loops. */
    private fun padInto(p: Project, pad: Int, m: MutableMap<String, Any?>) {
        val k = p.kit[pad]
        m["key"] = k
        if (k.isEmpty()) return
        val e = p.edits[k]
        if (e != null) { val n = normEdit(e); if (!n.isDefault()) m["edit"] = n.toMap() }
        val sp = p.synths[k]
        if (sp != null && Project.isSynth(k)) m["synth"] = sp.toMap()
        val om = optsMap(p.opts[pad])
        if (om != null) m["opts"] = om
        var lm: MutableMap<String, Any?>? = null
        for (l in 0 until NL) { val r = row(p, l, pad); if (!r.isEmpty()) { if (lm == null) lm = LinkedHashMap(); lm[l.toString()] = r } }
        if (lm != null) m["loops"] = lm
    }

    /** The pad.put that makes pad `at` (seen holding `seen`) hold what pad `pad` of `from` holds. */
    private fun padPutOf(from: Project, pad: Int, at: Int, seen: String): Op {
        val o = mk("pad.put")
        o.m["p"] = at.toDouble(); o.m["k"] = seen
        padInto(from, pad, o.m)
        return o
    }

    /** The loop.put that makes loop l what it is in p. `keys` names each pad's sound so a later swap cannot misplace a row. */
    private fun loopPutOf(p: Project, l: Int): Op {
        val o = mk("loop.put")
        o.m["l"] = l.toDouble(); o.m["len"] = p.pats[l].len.toDouble()
        val pads: MutableMap<String, Any?> = LinkedHashMap()
        val keys: MutableMap<String, Any?> = LinkedHashMap()
        for (pad in 0 until NP) { val r = row(p, l, pad); if (!r.isEmpty()) { pads[pad.toString()] = r; keys[pad.toString()] = p.kit[pad] } }
        o.m["pads"] = pads; o.m["keys"] = keys
        return o
    }

    private fun rowPutOf(p: Project, l: Int, pad: Int): Op {
        val o = mk("row.put")
        o.m["l"] = l.toDouble(); o.m["p"] = pad.toDouble(); o.m["k"] = p.kit[pad]; o.m["row"] = row(p, l, pad)
        return o
    }

    private fun loopHasShared(p: Project, l: Int): Boolean { for (pad in 0 until NP) if (!row(p, l, pad).isEmpty()) return true; return false }

    /* ================= inverse ================= */

    private fun noteLen(P: Project.Pattern, k: String, s: Int, n: Int): Int {
        val l = P.notes[k] ?: return 0
        var best = 0
        for (x in l) if (x.s == s && clampI(x.p, Theory.ROLL_LO, Theory.ROLL_HI) == n) best = maxOf(best, clampI(x.l, 1, maxOf(1, P.len - s)))
        return best
    }

    /**
     * The ops that take `op` back, in the order to run them, worked out against the mess as it stands BEFORE op is
     * applied. Empty when op would do nothing here, or cannot be taken back (doc.reset, an unknown op).
     */
    @JvmStatic fun inverse(p: Project, op: Op): MutableList<Op> {
        val out = ArrayList<Op>(2)
        val m = op.m
        when (kind(op.t)) {
            K_CELL -> {
                val P = loopOf(p, m) ?: return out
                val t = target(p, m)
                if (t < 0) return out
                val k = p.kit[t]
                val i = copyOp(op); i.m["p"] = t.toDouble(); i.m["k"] = k
                val s = int(m, "s", -1)
                when (op.t) {
                    "step.set" -> { if (s < 0 || s >= P.len) return out; i.m["on"] = P.step(k, s) }
                    "acc.set" -> { if (s < 0 || s >= P.len) return out; i.m["v"] = P.strength(k, s).toDouble() }
                    "chance.set" -> { if (s < 0 || s >= P.len) return out; i.m["v"] = P.chance(k, s).toDouble() }
                    "off.set" -> { val tk = int(m, "tk", -1); if (tk <= 0 || tk >= P.len * Project.TICKS || tk % Project.TICKS == 0) return out; val l = P.offs[k]; i.m["on"] = l != null && l.contains(tk) }
                    "note.set" -> { if (s < 0 || s >= P.len) return out; val n = clampI(int(m, "n", 0), Theory.ROLL_LO, Theory.ROLL_HI); i.m["n"] = n.toDouble(); i.m["len"] = noteLen(P, k, s, n).toDouble() }
                    "lock.set" -> { if (s < 0 || s >= P.len) return out; i.m["v"] = val3(r3(P.lock(k, int(m, "g", -1), s))) }
                    "lane.set" -> { if (s < 0 || s >= P.len) return out; val a = P.motion["$t:${int(m, "g", -1)}"]; i.m["v"] = val3(if (a == null) NONE else r3(a[s])) }
                }
                out.add(i)
            }
            K_PAD -> {
                val t = target(p, m)
                if (t < 0) return out
                val k = p.kit[t]
                val i = copyOp(op); i.m["p"] = t.toDouble(); i.m["k"] = k
                when (op.t) {
                    "edit.set" -> { val v = editGet(p.edits[k] ?: SoundEdit.DEFAULT, str(m, "f")) ?: return out; i.m["v"] = v }
                    "edit.trim" -> { val e = p.edits[k] ?: SoundEdit.DEFAULT; i.m["st"] = trimSt(e); i.m["en"] = trimEn(e) }
                    "opt.set" -> { val v = optGet(p.opts[t] ?: PadOpts.DEFAULT, str(m, "f")) ?: return out; i.m["v"] = v }
                    "synth.set" -> {
                        val sp = p.synths[k] ?: return out
                        val id = str(m, "id")
                        if (id == "@name") i.m["v"] = sp.name else if (id == "@src") i.m["v"] = sp.src
                        else { if (sp.kind.at(id) < 0) return out; i.m["v"] = sp.get(id) }
                    }
                }
                out.add(i)
            }
            K_GLOBAL -> {
                val i = copyOp(op)
                if (op.t == "song.set") { val b = int(m, "b", -1); if (b < 0 || b >= Project.MAXSONG) return out; i.m["v"] = p.song[b].toDouble() }
                else { val v = messGet(p, str(m, "f")) ?: return out; i.m["v"] = v }
                out.add(i)
            }
            K_STRUCT -> when (op.t) {
                "row.put" -> { val l = int(m, "l", -1); val t = target(p, m); if (l >= 0 && l < NL && t >= 0) out.add(rowPutOf(p, l, t)) }
                "loop.len", "loop.clear", "loop.put" -> { val l = int(m, "l", -1); if (l >= 0 && l < NL) out.add(loopPutOf(p, l)) }
                "loop.copy" -> { val f = int(m, "from", -1); val to = int(m, "to", -1); if (f >= 0 && f < NL && to >= 0 && to < NL && f != to) out.add(loopPutOf(p, to)) }
                "pad.swap" -> {
                    val a = int(m, "a", -1); val b = int(m, "b", -1)
                    if (a >= 0 && a < NP && b >= 0 && b < NP && a != b && p.kit[a] == str(m, "ka") && p.kit[b] == str(m, "kb") && !(p.kit[a].isEmpty() && p.kit[b].isEmpty())) {
                        val i = mk("pad.swap"); i.m["a"] = a.toDouble(); i.m["b"] = b.toDouble(); i.m["ka"] = p.kit[b]; i.m["kb"] = p.kit[a]
                        out.add(i)
                    }
                }
                "pad.put" -> {
                    val t = resolve(p, int(m, "p", -1), str(m, "k"))
                    if (t < 0) return out
                    val key = str(m, "key")
                    if (!key.isEmpty() && Project.isSynth(key) && m["synth"] !is Map<*, *>) return out
                    val nk = putKey(p, m, t)
                    val q = if (nk.isEmpty() || nk != key) -1 else p.padOf(nk)
                    // first the pad itself: whatever holds the new sound then goes back to what this pad held
                    val back = padPutOf(p, t, t, nk)
                    if (Project.isSynth(p.kit[t]) && p.kit[t] != nk) back.m["new"] = true
                    out.add(back)
                    // then, when the put was a move, the sound goes back to the pad it came from
                    if (q >= 0 && q != t) { val home = padPutOf(p, q, q, ""); if (Project.isSynth(nk)) home.m["new"] = true; out.add(home) }
                }
            }
        }
        return out
    }

    /* ================= change: the ops between the shadow and the mess, found in place ================= */

    private fun sameBools(a: BooleanArray?, b: BooleanArray?, len: Int): Boolean {
        if (a === b) return true
        if (a == null || b == null) { val x = a ?: b!!; for (j in 0 until len) if (x[j]) return false; return true }
        for (j in 0 until len) if (a[j] != b[j]) return false
        return true
    }
    private fun sameBytes(a: ByteArray?, b: ByteArray?, len: Int, def: Int): Boolean {
        if (a === b) return true
        if (a == null || b == null) { val x = a ?: b!!; for (j in 0 until len) if (x[j].toInt() != def) return false; return true }
        for (j in 0 until len) if (a[j] != b[j]) return false
        return true
    }
    private fun sameFloats(a: FloatArray?, b: FloatArray?, len: Int): Boolean {
        if (a === b) return true
        if (a == null || b == null) { val x = a ?: b!!; for (j in 0 until len) if (!x[j].isNaN()) return false; return true }
        for (j in 0 until len) { val x = a[j]; val y = b[j]; if (x != y && !(x.isNaN() && y.isNaN()) && r3(x) != r3(y)) return false }
        return true
    }
    private fun sameOffs(a: List<Int>?, b: List<Int>?): Boolean {
        val an = a?.size ?: 0; val bn = b?.size ?: 0
        if (an != bn) return false
        for (j in 0 until an) if (a!![j] != b!![j]) return false
        return true
    }
    private fun sameNotes(a: List<Project.Note>?, b: List<Project.Note>?): Boolean {
        val an = a?.size ?: 0; val bn = b?.size ?: 0
        if (an != bn) return false
        for (j in 0 until an) { val x = a!![j]; val y = b!![j]; if (x.s != y.s || x.p != y.p || x.l != y.l) return false }
        return true
    }

    private class Differ(@JvmField val sh: Project, @JvmField val pr: Project, @JvmField val cellsOnly: Boolean) {
        @JvmField val ops = ArrayList<Op>()
        /** Each op's inverse, appended as the ops are made; an inverse of two ops goes in back to front, and the whole list is turned round at the end. */
        @JvmField val inv = ArrayList<Op>()
        private var dirty: BooleanArray? = null
        private var anyDirty = false

        fun emit(op: Op) {
            val back = inverse(sh, op)
            for (i in back.indices.reversed()) inv.add(back[i])
            applyOne(sh, op)
            ops.add(op)
        }

        private fun field(t: String, pad: Int, k: String): Op { val o = mk(t); o.m["p"] = pad.toDouble(); o.m["k"] = k; return o }

        /* ---- hints ---- */

        fun hint(h: Op) {
            val m = h.m
            when (h.t) {
                "loop.len" -> {
                    val l = int(m, "l", -1); val n = int(m, "len", -1)
                    if (l < 0 || l >= NL || n < 16 || n > Project.MAXSTEPS || n % 16 != 0 || sh.pats[l].len == n) return
                    emit(loopLen(l, n, m["fill"] == true))
                }
                "loop.clear" -> { val l = int(m, "l", -1); if (l >= 0 && l < NL && loopHasShared(sh, l)) emit(loopClear(l)) }
                "loop.copy" -> { val f = int(m, "from", -1); val to = int(m, "to", -1); if (f >= 0 && f < NL && to >= 0 && to < NL && f != to) emit(loopCopy(f, to)) }
                "pad.swap" -> {
                    val a = int(m, "a", -1); val b = int(m, "b", -1)
                    if (a < 0 || a >= NP || b < 0 || b >= NP || a == b || (sh.kit[a].isEmpty() && sh.kit[b].isEmpty())) return
                    val o = padSwap(a, b); o.m["ka"] = sh.kit[a]; o.m["kb"] = sh.kit[b]
                    emit(o)
                }
                "pad.put" -> { val pad = int(m, "p", -1); if (pad >= 0 && pad < NP && sh.kit[pad] != pr.kit[pad]) putPad(pad, m["sound"]) }
            }
        }

        /** Pad `pad` holds another sound now: one pad.put with all the pad holds in the mess. */
        fun putPad(pad: Int, sound: Any?) {
            val o = padPutOf(pr, pad, pad, sh.kit[pad])
            val k = pr.kit[pad]
            if (!k.isEmpty() && sh.padOf(k) < 0) o.m["new"] = true
            if (sound is Map<*, *>) o.m["sound"] = sound
            emit(o)
        }

        /* ---- the pass ---- */

        fun run() {
            // mess settings
            for (f in MESS_FIELDS) {
                val a = messGet(pr, f); val b = messGet(sh, f)
                if (a != b) { val o = mk("mess.set"); o.m["f"] = f; o.m["v"] = a; emit(o) }
            }
            // the song lane
            for (b in 0 until Project.MAXSONG) {
                val v = clampI(pr.song[b], -1, NL - 1)
                if (v != sh.song[b]) { val o = mk("song.set"); o.m["b"] = b.toDouble(); o.m["v"] = v.toDouble(); emit(o) }
            }
            // which sound is on which pad (no hint named it), and a synth that changed kind
            for (pad in 0 until NP) {
                val k = pr.kit[pad]
                if (k != sh.kit[pad]) { putPad(pad, null); continue }
                if (k.isEmpty() || !Project.isSynth(k)) continue
                val a = pr.synths[k]; val b = sh.synths[k]
                if ((a == null) != (b == null) || (a != null && b != null && a.kind !== b.kind)) putPad(pad, null)
            }
            // loop lengths
            for (l in 0 until NL) {
                val n = pr.pats[l].len
                if (n != sh.pats[l].len && n >= 16 && n <= Project.MAXSTEPS && n % 16 == 0) emit(loopLen(l, n, false))
            }
            // each pad's sound, options and synth
            for (pad in 0 until NP) {
                val k = pr.kit[pad]
                if (k.isEmpty() || k != sh.kit[pad]) continue
                val pe = pr.edits[k] ?: SoundEdit.DEFAULT; val se = sh.edits[k] ?: SoundEdit.DEFAULT
                if (!sameEditRaw(pe, se)) edit(pad, k, pe, se)
                val po = pr.opts[pad] ?: PadOpts.DEFAULT; val so = sh.opts[pad] ?: PadOpts.DEFAULT
                if (!sameOptsRaw(po, so)) opts(pad, k, po, so)
                val a = pr.synths[k]
                if (a != null) { val b = sh.synths[k]; if (b != null && a !== b) synth(pad, k, a, b) }
            }
            // rows
            for (l in 0 until NL) loop(l)
        }

        private fun edit(pad: Int, k: String, pe: SoundEdit, se: SoundEdit) {
            for (f in EDIT_FIELDS) {
                val a = editGet(pe, f)
                if (a != editGet(se, f)) { val o = field("edit.set", pad, k); o.m["f"] = f; o.m["v"] = a; emit(o) }
            }
            val st = trimSt(pe); val en = trimEn(pe)
            if (st != trimSt(se) || en != trimEn(se)) { val o = field("edit.trim", pad, k); o.m["st"] = st; o.m["en"] = en; emit(o) }
        }

        private fun opts(pad: Int, k: String, po: PadOpts, so: PadOpts) {
            for (f in OPT_FIELDS) {
                val a = optGet(po, f)
                if (a != optGet(so, f)) { val o = field("opt.set", pad, k); o.m["f"] = f; o.m["v"] = a; emit(o) }
            }
            if (po.fx != so.fx) {
                val a = po.fxMap(); val b = so.fxMap()
                var gs: ArrayList<Int>? = null
                for (g in a.keys) if (r3d(a[g] ?: 0.0) != r3d(b[g] ?: 0.0)) { if (gs == null) gs = ArrayList(); gs.add(g) }
                for (g in b.keys) if (!a.containsKey(g) && r3d(b[g] ?: 0.0) > 0) { if (gs == null) gs = ArrayList(); gs.add(g) }
                if (gs != null) { gs.sort(); for (g in gs) { val o = field("opt.set", pad, k); o.m["f"] = "fx.$g"; o.m["v"] = maxOf(0, r3d(a[g] ?: 0.0)) / 1000.0; emit(o) } }
                // the same values written another way: take the mess's spelling, so the next pass sees equal strings
                val now = sh.opts[pad]
                if (now != null && now.fx != po.fx && fxCanon(now) == fxCanon(po)) now.fx = po.fx
            }
        }

        private fun synth(pad: Int, k: String, a: Synth.Patch, b: Synth.Patch) {
            if (a.name != b.name) { val o = field("synth.set", pad, k); o.m["id"] = "@name"; o.m["v"] = a.name; emit(o) }
            if (a.src != b.src) { val o = field("synth.set", pad, k); o.m["id"] = "@src"; o.m["v"] = a.src; emit(o) }
            val n = minOf(a.v.size, b.v.size)
            for (i in 0 until n) if (a.v[i] != b.v[i]) {
                val v = a.kind.params[i].clamp(a.v[i])
                if (v != b.v[i]) { val o = field("synth.set", pad, k); o.m["id"] = a.kind.params[i].id; o.m["v"] = v; emit(o) }
            }
        }

        private fun mark(pad: Int, k: String) {
            if (k.isEmpty() || pad < 0 || pad >= NP || pr.kit[pad] != k || sh.kit[pad] != k) return
            var d = dirty
            if (d == null) { d = BooleanArray(NP); dirty = d }
            d[pad] = true; anyDirty = true
        }
        private fun markKey(k: String) { val pad = pr.padOf(k); if (pad >= 0) mark(pad, k) }

        /** One loop: find the rows that differ without making anything, then work those rows out cell by cell. */
        private fun loop(l: Int) {
            val P = pr.pats[l]; val S = sh.pats[l]
            val len = minOf(P.len, S.len)
            anyDirty = false
            for (e in P.steps.entries) if (!sameBools(e.value, S.steps[e.key], len)) markKey(e.key)
            for (e in S.steps.entries) if (!P.steps.containsKey(e.key) && !sameBools(null, e.value, len)) markKey(e.key)
            for (e in P.acc.entries) if (!sameBytes(e.value, S.acc[e.key], len, 1)) markKey(e.key)
            for (e in S.acc.entries) if (!P.acc.containsKey(e.key) && !sameBytes(null, e.value, len, 1)) markKey(e.key)
            for (e in P.chance.entries) if (!sameBytes(e.value, S.chance[e.key], len, 100)) markKey(e.key)
            for (e in S.chance.entries) if (!P.chance.containsKey(e.key) && !sameBytes(null, e.value, len, 100)) markKey(e.key)
            for (e in P.offs.entries) if (!sameOffs(e.value, S.offs[e.key])) markKey(e.key)
            for (e in S.offs.entries) if (!P.offs.containsKey(e.key) && !e.value.isEmpty()) markKey(e.key)
            for (e in P.notes.entries) if (!sameNotes(e.value, S.notes[e.key])) markKey(e.key)
            for (e in S.notes.entries) if (!P.notes.containsKey(e.key) && !e.value.isEmpty()) markKey(e.key)
            for (e in P.locks.entries) if (!sameFloats(e.value, S.locks[e.key], len)) { val at = e.key.lastIndexOf('@'); if (at > 0) markKey(e.key.substring(0, at)) }
            for (e in S.locks.entries) if (!P.locks.containsKey(e.key) && !sameFloats(null, e.value, len)) { val at = e.key.lastIndexOf('@'); if (at > 0) markKey(e.key.substring(0, at)) }
            for (e in P.motion.entries) if (!sameFloats(e.value, S.motion[e.key], len)) { val pad = lanePad(e.key); if (pad >= 0 && pad < NP) mark(pad, pr.kit[pad]) }
            for (e in S.motion.entries) if (!P.motion.containsKey(e.key) && !sameFloats(null, e.value, len)) { val pad = lanePad(e.key); if (pad >= 0 && pad < NP) mark(pad, pr.kit[pad]) }
            if (!anyDirty) return
            val d = dirty!!
            for (pad in 0 until NP) if (d[pad]) { d[pad] = false; row(l, pad) }
        }

        private fun cell(t: String, l: Int, pad: Int, k: String): Op { val o = mk(t); o.m["l"] = l.toDouble(); o.m["p"] = pad.toDouble(); o.m["k"] = k; return o }

        private fun targets(P: Project.Pattern, S: Project.Pattern, k: String, pad: Int, lanes: Boolean): ArrayList<Int> {
            val gs = ArrayList<Int>()
            if (lanes) {
                for (key in P.motion.keys) if (lanePad(key) == pad) { val g = laneTarget(key); if (g >= 0 && !gs.contains(g)) gs.add(g) }
                for (key in S.motion.keys) if (lanePad(key) == pad) { val g = laneTarget(key); if (g >= 0 && !gs.contains(g)) gs.add(g) }
            } else {
                for (key in P.locks.keys) if (lockIs(key, k)) { val g = lockTarget(key); if (g >= 0 && !gs.contains(g)) gs.add(g) }
                for (key in S.locks.keys) if (lockIs(key, k)) { val g = lockTarget(key); if (g >= 0 && !gs.contains(g)) gs.add(g) }
            }
            gs.sort()
            return gs
        }

        /** One pad's row in one loop, cell by cell; more than 64 changed cells go as one row.put. */
        private fun row(l: Int, pad: Int) {
            val k = pr.kit[pad]
            val P = pr.pats[l]; val S = sh.pats[l]
            val len = minOf(P.len, S.len)
            val out = ArrayList<Op>()
            val a = P.steps[k]; val b = S.steps[k]
            for (j in 0 until len) { val x = a != null && a[j]; if (x != (b != null && b[j])) { val o = cell("step.set", l, pad, k); o.m["s"] = j.toDouble(); o.m["on"] = x; out.add(o) } }
            if (P.acc[k] != null || S.acc[k] != null) for (j in 0 until len) { val x = clampI(P.strength(k, j), 0, 2); if (x != S.strength(k, j)) { val o = cell("acc.set", l, pad, k); o.m["s"] = j.toDouble(); o.m["v"] = x.toDouble(); out.add(o) } }
            if (P.chance[k] != null || S.chance[k] != null) for (j in 0 until len) { val x = clampI(P.chance(k, j), 0, 104); if (x != S.chance(k, j)) { val o = cell("chance.set", l, pad, k); o.m["s"] = j.toDouble(); o.m["v"] = x.toDouble(); out.add(o) } }
            if (!sameOffs(P.offs[k], S.offs[k])) {
                val x = offList(P.offs[k], len); val y = offList(S.offs[k], len)
                for (t in x) if (!y.contains(t)) { val o = cell("off.set", l, pad, k); o.m["tk"] = t.toDouble(); o.m["on"] = true; out.add(o) }
                for (t in y) if (!x.contains(t)) { val o = cell("off.set", l, pad, k); o.m["tk"] = t.toDouble(); o.m["on"] = false; out.add(o) }
            }
            var notesMoved = false
            if (!sameNotes(P.notes[k], S.notes[k])) {
                notesMoved = true
                val x = noteMap(P.notes[k], len); val y = noteMap(S.notes[k], len)
                for (e in x.entries) if (y[e.key] != e.value) { val o = cell("note.set", l, pad, k); o.m["s"] = noteStep(e.key).toDouble(); o.m["n"] = notePitch(e.key).toDouble(); o.m["len"] = e.value.toDouble(); out.add(o) }
                for (e in y.entries) if (!x.containsKey(e.key)) { val o = cell("note.set", l, pad, k); o.m["s"] = noteStep(e.key).toDouble(); o.m["n"] = notePitch(e.key).toDouble(); o.m["len"] = 0.0; out.add(o) }
            }
            for (g in targets(P, S, k, pad, false)) {
                val x = P.locks["$k@$g"]; val y = S.locks["$k@$g"]
                for (j in 0 until len) { val u = if (x == null) NONE else r3(x[j]); if (u != (if (y == null) NONE else r3(y[j]))) { val o = cell("lock.set", l, pad, k); o.m["g"] = g.toDouble(); o.m["s"] = j.toDouble(); o.m["v"] = val3(u); out.add(o) } }
            }
            for (g in targets(P, S, k, pad, true)) {
                val x = P.motion["$pad:$g"]; val y = S.motion["$pad:$g"]
                for (j in 0 until len) { val u = if (x == null) NONE else r3(x[j]); if (u != (if (y == null) NONE else r3(y[j]))) { val o = cell("lane.set", l, pad, k); o.m["g"] = g.toDouble(); o.m["s"] = j.toDouble(); o.m["v"] = val3(u); out.add(o) } }
            }
            if (out.size > ROW_PUT && !cellsOnly) emit(rowPutOf(pr, l, pad))
            else for (o in out) emit(o)
            if (notesMoved) {
                // the shadow's notes in the mess's own order, so the next pass can compare the two lists as they lie
                val nm = noteMap(P.notes[k], len)
                if (nm.isEmpty()) S.notes.remove(k)
                else { val sl = S.notes(k); sl.clear(); for (e in nm.entries) sl.add(Project.Note(noteStep(e.key), notePitch(e.key), e.value)) }
            }
            if (!sameOffs(P.offs[k], S.offs[k]) && offList(P.offs[k], len) == offList(S.offs[k], len)) {
                val pl = P.offs[k]
                if (pl == null) S.offs.remove(k) else S.offs[k] = ArrayList(pl)
            }
        }
    }

    /**
     * Applies hints to shadow, then compares shadow and project in place (no copies), writing old values into undo in
     * the same pass, and patches shadow until its shared part equals project's. The hints may be bare (Ops.padPut(3),
     * Ops.padSwap(1, 2)): what the pads hold is read from the two messes.
     */
    @JvmStatic fun change(shadow: Project, project: Project, hints: List<Op>): Change = change(shadow, project, hints, false)

    /** cellsOnly (a phone that saw newer ops, S7): a busy row goes as its cells, never as a row.put built from this phone's copy. */
    @JvmStatic fun change(shadow: Project, project: Project, hints: List<Op>, cellsOnly: Boolean): Change {
        val d = Differ(shadow, project, cellsOnly)
        for (h in hints) d.hint(h)
        d.run()
        d.inv.reverse()
        return Change(d.ops, d.inv, 0L)
    }

    /** No hints: change(clone(a), b, empty).ops. Used on reopen from the cache. */
    @JvmStatic fun diff(a: Project, b: Project): MutableList<Op> = change(clone(a), b, emptyList(), false).ops

    /* ================= squash ================= */

    /**
     * Drops a field op when a later op in the list sets the same field of the same sound, or replaces a scope it lies
     * in (loop.clear, loop.put, loop.copy's `to`, pad.put). Order is kept. An op is never dropped across a later op
     * that reads what it wrote: loop.copy reads its `from`, loop.len reads its loop.
     */
    @JvmStatic fun squash(ops: List<Op>): MutableList<Op> {
        val n = ops.size
        val out = ArrayList<Op>(n)
        if (n < 2) { out.addAll(ops); return out }
        val keep = BooleanArray(n)
        val deadCell = arrayOfNulls<HashSet<String>>(NL)
        val deadLoop = BooleanArray(NL)
        val deadField = HashSet<String>()
        val deadSound = HashSet<String>()
        for (i in n - 1 downTo 0) {
            val op = ops[i]
            keep[i] = true
            when (kind(op.t)) {
                K_CELL -> {
                    val l = int(op.m, "l", -1)
                    if (l < 0 || l >= NL) continue
                    val id = ident(op)
                    var set = deadCell[l]
                    if (deadLoop[l] || deadSound.contains(sound(op)) || (set != null && set.contains(id))) keep[i] = false
                    else { if (set == null) { set = HashSet(); deadCell[l] = set }; set.add(id) }
                }
                K_PAD, K_GLOBAL -> {
                    val id = ident(op)
                    if ((kind(op.t) == K_PAD && deadSound.contains(sound(op))) || deadField.contains(id)) keep[i] = false else deadField.add(id)
                }
                K_STRUCT -> when (op.t) {
                    "loop.clear", "loop.put" -> { val l = int(op.m, "l", -1); if (l >= 0 && l < NL) deadLoop[l] = true }
                    "loop.copy" -> {
                        val to = int(op.m, "to", -1); val f = int(op.m, "from", -1)
                        if (to >= 0 && to < NL) deadLoop[to] = true
                        if (f >= 0 && f < NL) { deadLoop[f] = false; deadCell[f] = null }
                    }
                    "loop.len" -> { val l = int(op.m, "l", -1); if (l >= 0 && l < NL) { deadLoop[l] = false; deadCell[l] = null } }
                    "pad.put" -> {
                        if (!str(op.m, "k").isEmpty()) deadSound.add(sound(op))
                        val key = str(op.m, "key"); if (!key.isEmpty()) deadSound.add("K:$key")
                    }
                    "row.put", "pad.swap" -> {}
                    else -> { for (l in 0 until NL) { deadLoop[l] = false; deadCell[l] = null }; deadField.clear(); deadSound.clear() }
                }
                else -> { for (l in 0 until NL) { deadLoop[l] = false; deadCell[l] = null }; deadField.clear(); deadSound.clear() }
            }
        }
        for (i in 0 until n) if (keep[i]) out.add(ops[i])
        return out
    }

    /* ================= canon, clone, the local part ================= */

    private fun canonMap(p: Project): MutableMap<String, Any?> {
        val o: MutableMap<String, Any?> = LinkedHashMap()
        for (f in MESS_FIELDS) o[f] = messGet(p, f)
        val sg = StringBuilder(Project.MAXSONG)
        for (b in 0 until Project.MAXSONG) { val v = p.song[b]; sg.append(if (v >= 0 && v < NL) Project.LETTERS[v] else '-') }
        o["song"] = sg.toString()
        val lens = ArrayList<Any?>(NL)
        for (l in 0 until NL) lens.add(p.pats[l].len.toDouble())
        o["lens"] = lens
        val pads: MutableMap<String, Any?> = LinkedHashMap()
        for (pad in 0 until NP) {
            if (p.kit[pad].isEmpty()) continue
            val m: MutableMap<String, Any?> = LinkedHashMap()
            padInto(p, pad, m)
            pads[pad.toString()] = m
        }
        o["pads"] = pads
        return o
    }

    /**
     * The shared part as text in one fixed order, for comparing two phones. What it leaves out, because save and load
     * or the engine treat it as nothing: options and lanes left on an empty pad, rows and settings of a sound that is
     * on no pad, lanes and locks with no value inside the loop, anything at or past a loop's length, the LFO's old
     * filter fields (type, cut, res), a sound's settings or a pad's options that are all at their defaults, and
     * every never-synced field (sel, cur, mode, mutes, solos, slot 64, unknown keys). Locks, lanes and FX rest values
     * are compared to 3 decimals; notes by step then pitch, one per step and pitch.
     */
    @JvmStatic fun canon(p: Project): String = Json.write(canonMap(p))

    /** Field by field, no JSON round trip. Unknown keys are carried as they are (they are never edited). */
    @JvmStatic fun clone(p: Project): Project {
        val q = Project()
        q.name = p.name
        q.extra.putAll(p.extra)
        for (i in 0 until Project.SLOTS) { q.kit[i] = p.kit[i]; val o = p.opts[i]; q.opts[i] = if (o == null) null else copyOpts(o) }
        for (l in 0 until NL) q.pats[l] = p.pats[l].copy()
        p.song.copyInto(q.song)
        q.bpm = p.bpm; q.swing = p.swing; q.sel = p.sel; q.root = p.root; q.cur = p.cur
        q.squash = p.squash; q.level = p.level; q.fold = p.fold; q.songMode = p.songMode; q.scale = p.scale
        q.mutes.addAll(p.mutes); q.solos.addAll(p.solos)
        for (e in p.edits.entries) q.edits[e.key] = e.value.copy()
        for (e in p.synths.entries) q.synths[e.key] = e.value.copy()
        val dk = p.dropKeep
        q.dropKeep = if (dk == null) null else LinkedHashSet(dk)
        return q
    }

    private fun dropScratch(p: Project) {
        val k = p.kit[SCRATCH]
        p.kit[SCRATCH] = ""
        p.opts[SCRATCH] = null
        for (P in p.pats) forgetLanes(P, SCRATCH)
        if (!k.isEmpty() && p.padOf(k) < 0) purgeKey(p, k)
    }

    /**
     * Copies what never syncs from one mess to another: sel, cur, mode, mutes, solos, unknown keys, dropKeep, and slot
     * 64 (the scratch synth, its patch, options, rows and lanes), which takes a new key when `to` has its key on a pad.
     */
    @JvmStatic fun carryLocal(from: Project, to: Project) {
        if (from === to) return
        to.sel = from.sel; to.cur = from.cur
        to.extra.clear(); to.extra.putAll(from.extra)
        val dk = from.dropKeep
        to.dropKeep = if (dk == null) null else LinkedHashSet(dk)
        for (l in 0 until NL) { to.pats[l].extra.clear(); to.pats[l].extra.putAll(from.pats[l].extra) }
        dropScratch(to)
        val fk = from.kit[SCRATCH]
        var nk = fk
        if (!fk.isEmpty()) {
            if (to.padOf(fk) >= 0) { var n = 1; while (to.synths.containsKey("synth:$n") || onAnySlot(to, "synth:$n") || from.synths.containsKey("synth:$n")) n++; nk = "synth:$n" }
            else purgeKey(to, nk)
        }
        to.mutes.clear(); to.mutes.addAll(from.mutes)
        to.solos.clear(); to.solos.addAll(from.solos)
        if (!fk.isEmpty()) {
            to.kit[SCRATCH] = nk
            val sp = from.synths[fk]; if (sp != null) to.synths[nk] = sp.copy()
            val e = from.edits[fk]; if (e != null) to.edits[nk] = e.copy()
            val o = from.opts[SCRATCH]; to.opts[SCRATCH] = if (o == null) null else copyOpts(o)
            if (from.mutes.contains(fk) && nk != fk) { to.mutes.remove(fk); to.mutes.add(nk) }
            if (from.solos.contains(fk) && nk != fk) { to.solos.remove(fk); to.solos.add(nk) }
            for (l in 0 until NL) {
                val F = from.pats[l]; val T = to.pats[l]
                val len = T.len
                val st = F.steps[fk]; if (st != null) { val a = BooleanArray(Project.MAXSTEPS); for (j in 0 until len) a[j] = st[j]; T.steps[nk] = a }
                val ac = F.acc[fk]; if (ac != null) { val a = ByteArray(Project.MAXSTEPS); a.fill(1.toByte()); for (j in 0 until len) a[j] = ac[j]; T.acc[nk] = a }
                val ch = F.chance[fk]; if (ch != null) { val a = ByteArray(Project.MAXSTEPS); a.fill(100.toByte()); for (j in 0 until len) a[j] = ch[j]; T.chance[nk] = a }
                val of = F.offs[fk]; if (of != null) { val a = ArrayList<Int>(); for (t in of) if (t < len * Project.TICKS) a.add(t); if (!a.isEmpty()) T.offs[nk] = a }
                val ns = F.notes[fk]; if (ns != null) { val a = ArrayList<Project.Note>(); for (x in ns) if (x.s < len) a.add(Project.Note(x.s, x.p, maxOf(1, minOf(x.l, len - x.s)))); if (!a.isEmpty()) T.notes[nk] = a }
                for (e2 in F.locks.entries) if (lockIs(e2.key, fk)) { val a = FloatArray(Project.MAXSTEPS); a.fill(Float.NaN); for (j in 0 until len) a[j] = e2.value[j]; if (anyIn(a, len)) T.locks[nk + e2.key.substring(fk.length)] = a }
                for (e2 in F.motion.entries) if (lanePad(e2.key) == SCRATCH) { val a = FloatArray(Project.MAXSTEPS); a.fill(Float.NaN); for (j in 0 until len) a[j] = e2.value[j]; T.motion[e2.key] = a }
            }
        }
        to.songMode = from.songMode && to.songLen() > 0
    }

    /** What changes when one of the never-synced fields does: a cheap text to compare (the solo snapshot rule, M4). */
    @JvmStatic fun localMark(p: Project): String {
        val b = StringBuilder()
        b.append(p.sel).append('/').append(p.cur).append('/').append(if (p.songMode) 's' else 'l').append('/').append(p.kit[SCRATCH])
        for (m in p.mutes) b.append("/m").append(m)
        for (s in p.solos) b.append("/s").append(s)
        return b.toString()
    }

    /** The JSON to upload: toJson as it is when solo; with the never-synced fields blanked when the mess is shared. */
    @JvmStatic fun uploadDoc(p: Project, shared: Boolean): String {
        if (!shared) return p.toJson()
        val q = clone(p)
        q.mutes.clear(); q.solos.clear(); q.sel = 0; q.cur = 0; q.songMode = false; q.dropKeep = null
        q.extra.clear()
        for (P in q.pats) P.extra.clear()
        dropScratch(q)
        return q.toJson()
    }
}
