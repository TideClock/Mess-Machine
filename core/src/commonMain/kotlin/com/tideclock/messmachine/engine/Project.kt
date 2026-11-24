package com.tideclock.messmachine.engine

import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic
import kotlin.jvm.Transient
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * One mess: 64 pads, 8 loops, a song lane, tempo, swing, mutes and every sound's doctor
 * settings. Saves to the same JSON the site's Mess Machine saves (v3), so a mess can move
 * between the site and the phone.
 */
class Project {
    companion object {
        const val NPADS = 64
        const val BANK = 16
        const val NSLOTS = 8
        const val MAXSTEPS = 128
        const val MAXSONG = 64
        /** Nov 21: a slot past the 64 pads, in no bank, for the Synth tab's scratch synth; it plays and records like a pad. */
        const val SCRATCH = NPADS
        const val SLOTS = NPADS + 1
        /** The finer time grid: ticks in one sixteenth step (96 a quarter note), enough for every triplet and dotted rate. */
        const val TICKS = 24
        const val LETTERS = "ABCDEFGH"
        @JvmField val DEFAULT_KIT: Array<String> = arrayOf("basement-kick", "wet-clap", "pot-lid-hat", "bw-snare", "tom-of-doom", "bubble-pop", "spring-doink", "tape-wobble-chord")

        /** The format version this app writes; a file with a higher "v" loads with Loaded.newer set. */
        const val V = 3
        @JvmField val TOP_KEYS: Set<String> = LinkedHashSet(listOf(
            "name", "bpm", "v", "kit", "sel", "steps", "pats", "song", "swing", "squash", "level", "mutes", "solos", "scale",
            "root", "fold", "edits", "synths", "padOpts", "at", "app", "mode", "cur"))
        @JvmField val PAT_KEYS: Set<String> = LinkedHashSet(listOf(
            "len", "steps", "notes", "inst", "motion", "offs", "acc", "chance", "locks"))

        /** A fresh mess with the site's default eight pads. */
        @JvmStatic
        fun starter(): Project {
            val p = Project()
            for (k in DEFAULT_KIT.indices) p.kit[k] = "samples/" + DEFAULT_KIT[k] + ".wav"
            return p
        }

        /** The first-launch mess: the default kit with a little beat on loop A so it makes noise right away. */
        @JvmStatic
        fun demo(): Project {
            val p = starter()
            p.name = "First mess"
            p.bpm = 100
            p.swing = 18
            val a = p.pats[0]
            val kick = intArrayOf(0, 6, 8, 11); val clap = intArrayOf(4, 12); val hat = intArrayOf(2, 6, 10, 14, 15)
            for (j in kick) a.setStep(p.kit[0], j, true)
            for (j in clap) a.setStep(p.kit[1], j, true)
            for (j in hat) a.setStep(p.kit[2], j, true)
            a.notes(p.kit[7]).add(Note(0, 0, 6))
            a.notes(p.kit[7]).add(Note(8, -4, 6))
            p.edits[p.kit[7]] = editWith { e -> e.mode = "gate"; e.rel = .08; e.vol = .7 }
            p.sel = 0
            return p
        }

        private fun editWith(f: EditFn): SoundEdit { val e = SoundEdit(); f.apply(e); return e }

        @JvmStatic
        fun isSynth(k: String?): Boolean = k != null && k.startsWith("synth:")

        @JvmStatic
        fun parseIntOr(s: String, d: Int): Int = try { s.toInt() } catch (e: NumberFormatException) { d }

        @JvmStatic
        fun fromJson(text: String, known: Known?): Loaded {
            val q = Json.map(Json.parse(text))
            val p = Project()
            p.name = Json.str(q["name"], "")
            for (e in q.entries) if (!TOP_KEYS.contains(e.key)) p.extra[e.key] = e.value
            val newer = Json.num(q["v"], V.toDouble()) > V
            for (e in Json.map(q["padOpts"]).entries) {
                try { val k = e.key.toInt(); if (k >= 0 && k < SLOTS) p.opts[k] = PadOpts.fromMap(Json.map(e.value)) } catch (ignored: NumberFormatException) {}
            }
            for (e in Json.map(q["synths"]).entries)
                if (isSynth(e.key)) p.synths[e.key] = Synth.Patch.fromMap(Json.map(e.value))
            val kl = Json.list(q["kit"])
            var missing = 0
            for (k in 0 until minOf(kl.size, SLOTS)) {
                val key = Json.str(kl[k], "")
                if (key.isEmpty()) continue
                // Nov 23: a sound this phone does not have stays on its pad (silent, shown as missing) instead of being
                // dropped, so opening and saving a mess from another platform never loses pads
                if (p.padOf(key) >= 0) continue
                if (isSynth(key) && !p.synths.containsKey(key)) { missing++; continue }  // a synth with no patch is nothing
                p.kit[k] = key
                if (known != null && !known.has(key) && !p.synths.containsKey(key)) missing++
            }
            val ed = Json.map(q["edits"])
            for (e in ed.entries) {
                if (p.padOf(e.key) < 0) continue
                val se = SoundEdit.fromMap(Json.map(e.value))
                if (!se.isDefault()) p.edits[e.key] = se
            }
            val pl = Json.list(q["pats"])
            if (!pl.isEmpty()) {
                for (i in 0 until minOf(NSLOTS, pl.size)) {
                    val x = Json.map(pl[i])
                    val P = p.pats[i]
                    for (e in x.entries) if (!PAT_KEYS.contains(e.key)) P.extra[e.key] = e.value
                    // 1, 2, 4 or 8 bars (the site plays 1 or 2; longer loops are app only)
                    val ln = Json.num(x["len"], 16.0).toInt()
                    P.len = if (ln >= 16 && ln <= 128 && ln % 16 == 0) ln else 16  // Nov 22: any 1 to 8 bars (+ and - in the loop strip)
                    decSteps(P, Json.map(x["steps"]))
                    for (e in Json.map(x["motion"]).entries) {
                        val a = FloatArray(MAXSTEPS)
                        a.fill(Float.NaN)
                        // Java's split(",", -1) keeps trailing empties, as Kotlin's split does
                        val f = Json.str(e.value, "").split(",")
                        for (j in 0 until minOf(f.size, a.size)) if (!f[j].isEmpty()) try { a[j] = f[j].toFloat() } catch (ignored: NumberFormatException) {}
                        P.motion[e.key] = a
                    }
                    for (e in Json.map(x["locks"]).entries) {
                        val k = e.key; val at = k.lastIndexOf('@')
                        if (at < 0) continue
                        val t = parseIntOr(k.substring(at + 1), -1)
                        if (t < 0) continue
                        val f = Json.str(e.value, "").split(",")
                        for (j in 0 until minOf(f.size, MAXSTEPS)) if (!f[j].isEmpty()) try { P.setLock(k.substring(0, at), t, j, f[j].toFloat()) } catch (ignored: NumberFormatException) {}
                    }
                    for (e in Json.map(x["chance"]).entries) {
                        val f = Json.str(e.value, "").split(",")
                        for (j in 0 until minOf(f.size, MAXSTEPS)) if (!f[j].isEmpty()) P.setChance(e.key, j, parseIntOr(jtrim(f[j]), 100))
                    }
                    for (e in Json.map(x["acc"]).entries) {
                        val f = Json.str(e.value, "")
                        for (j in 0 until minOf(f.length, MAXSTEPS)) { val c = f[j]; if (c == 'g') P.setStrength(e.key, j, 0) else if (c == 'a') P.setStrength(e.key, j, 2) }
                    }
                    for (e in Json.map(x["offs"]).entries) {
                        // Java's split(",") drops trailing empties
                        for (f in Json.str(e.value, "").split(",").dropLastWhile { it.isEmpty() }) if (!f.isEmpty()) try { P.addOff(e.key, jtrim(f).toInt()) } catch (ignored: NumberFormatException) {}
                    }
                    val nv = x["notes"]
                    if (nv is String) {
                        val inst = Json.str(x["inst"], "")
                        if (!inst.isEmpty()) decNotes(P, inst, nv)
                    } else for (e in Json.map(nv).entries) decNotes(P, e.key, Json.str(e.value, ""))
                }
                val sg = Json.str(q["song"], "")
                for (k in 0 until minOf(sg.length, MAXSONG)) p.song[k] = LETTERS.indexOf(sg[k])
            } else decSteps(p.pats[0], Json.map(q["steps"]))
            // drop anything that points at a sound that is not on a pad
            for (P in p.pats) {
                P.steps.keys.removeAll { k -> p.padOf(k) < 0 }
                P.notes.keys.removeAll { k -> p.padOf(k) < 0 }
            }
            p.swing = SoundEdit.clamp(Json.num(q["swing"], 0.0), 0.0, 100.0).toInt()
            p.squash = SoundEdit.clamp(Json.num(q["squash"], .5), 0.0, 1.0)
            p.level = SoundEdit.clamp(Json.num(q["level"], 1.0), 0.0, 1.6)
            for (m in Json.list(q["mutes"])) if (m is String) p.mutes.add(m)
            for (m in Json.list(q["solos"])) if (m is String) p.solos.add(m)
            val sc = Json.str(q["scale"], "Chromatic")
            p.scale = if (Theory.SCALES.containsKey(sc)) sc else "Chromatic"
            p.root = SoundEdit.clamp(Json.num(q["root"], 0.0), 0.0, 11.0).toInt()
            p.fold = Json.bool(q["fold"], false)
            p.bpm = SoundEdit.clamp(Json.num(q["bpm"], 112.0), 60.0, 180.0).toInt()
            var sel = Json.num(q["sel"], -1.0).toInt()
            if (sel < 0 || sel >= NPADS || p.kit[sel].isEmpty()) {
                sel = 0
                for (k in 0 until NPADS) if (!p.kit[k].isEmpty()) { sel = k; break }
            }
            p.sel = sel
            p.songMode = "song" == q["mode"] && p.songLen() > 0
            p.cur = SoundEdit.clamp(Json.num(q["cur"], 0.0), 0.0, (NSLOTS - 1).toDouble()).toInt()
            return Loaded(p, missing, newer)
        }

        private fun decSteps(P: Pattern, m: Map<String, Any?>) {
            for (e in m.entries) {
                val v = Json.str(e.value, "")
                val a = BooleanArray(MAXSTEPS)
                var any = false
                for (j in 0 until minOf(v.length, MAXSTEPS)) if (v[j] == '1') { a[j] = true; any = true }
                if (any) P.steps[e.key] = a
            }
        }

        private fun decNotes(P: Pattern, k: String, str: String) {
            if (str.isEmpty()) return
            val l = P.notes(k)
            // Java's split(",") and split("\\.") drop trailing empties
            for (t in str.split(",").dropLastWhile { it.isEmpty() }) {
                val f = t.split(".").dropLastWhile { it.isEmpty() }
                if (f.size < 3) continue
                try {
                    val s = jtrim(f[0]).toInt(); val pp = jtrim(f[1]).toInt(); val ll = jtrim(f[2]).toInt()
                    if (s >= 0 && s < P.len) l.add(Note(s, maxOf(Theory.ROLL_LO, minOf(Theory.ROLL_HI, pp)), maxOf(1, minOf(P.len - s, ll))))
                } catch (ignored: NumberFormatException) {}
            }
        }

        /** Java's String.trim(): strips every char up to ' ' at both ends (Kotlin's trim() strips Unicode whitespace instead). */
        private fun jtrim(s: String): String = s.trim { it <= ' ' }
    }

    class Note(@JvmField var s: Int, @JvmField var p: Int, @JvmField var l: Int) {
        fun copy(): Note = Note(s, p, l)
    }

    class Pattern {
        companion object {
            @JvmField val CHANCES: IntArray = intArrayOf(100, 75, 50, 25, 101, 102, 103, 104)

            /** What a chance says on screen: "75%", "1:2" and so on; "" for always. */
            @JvmStatic
            fun chanceLabel(v: Int): String {
                when (v) { 101 -> return "1:2"; 102 -> return "2:2"; 103 -> return "1:4"; 104 -> return "4:4"; 100 -> return ""; else -> return "$v%" }
            }
        }

        @JvmField var len = 16
        @JvmField val steps: MutableMap<String, BooleanArray> = LinkedHashMap()
        @JvmField val notes: MutableMap<String, MutableList<Note>> = LinkedHashMap()
        /** App only: recorded XY moves, "pad:target" to one value per step (NaN = nothing on that step). */
        @JvmField val motion: MutableMap<String, FloatArray> = LinkedHashMap()
        /**
         * The finer time grid (Nov 18): hits between the sixteenths, per sound, in ticks from the top of the loop
         * (TICKS per step). Triplet and dotted repeats land here, and live hits when Rec is set to exact.
         */
        @JvmField val offs: MutableMap<String, MutableList<Int>> = LinkedHashMap()
        /**
         * Build 108: how hard each step hits, per sound: 0 ghost (quiet), 1 normal, 2 accent (full). Missing = normal.
         * Saved as "acc": one character per step, "g", "." or "a".
         */
        @JvmField val acc: MutableMap<String, ByteArray> = LinkedHashMap()

        fun strength(k: String, j: Int): Int { val a = acc[k]; return if (a == null || j < 0 || j >= a.size) 1 else a[j].toInt() }

        fun setStrength(k: String, j: Int, v: Int) {
            var a = acc[k]
            if (a == null) { if (v == 1) return; a = ByteArray(MAXSTEPS); a.fill(1.toByte()); acc[k] = a }
            if (j >= 0 && j < a.size) a[j] = maxOf(0, minOf(2, v)).toByte()
        }

        /**
         * Build 6 of the overhaul: whether each step plays, per sound. 0 to 100 is a percent chance; above that, which
         * time round the loop it plays on: 101 the 1st of 2, 102 the 2nd of 2, 103 the 1st of 4, 104 the 4th of 4.
         * Missing = 100 (always). Saved as "chance": per step, empty for always, else the number.
         */
        @JvmField val chance: MutableMap<String, ByteArray> = LinkedHashMap()

        fun chance(k: String, j: Int): Int { val a = chance[k]; return if (a == null || j < 0 || j >= a.size) 100 else a[j].toInt() }

        fun setChance(k: String, j: Int, v: Int) {
            var a = chance[k]
            if (a == null) { if (v == 100) return; a = ByteArray(MAXSTEPS); a.fill(100.toByte()); chance[k] = a }
            if (j >= 0 && j < a.size) a[j] = maxOf(0, minOf(104, v)).toByte()
        }

        /**
         * Step locks (overhaul, last build): a value for one step only, per sound and control, that snaps back on the
         * next step. Key "sound@target" (Engine.T_FILTER and so on), one value per step, NaN for none. Saved as "locks".
         */
        @JvmField val locks: MutableMap<String, FloatArray> = LinkedHashMap()

        /**
         * Nov 23 (shared format): keys in this loop's saved object that this version does not know (another platform's,
         * or a newer version's). Kept exactly as they were and written back, never edited here.
         */
        @JvmField val extra: MutableMap<String, Any?> = LinkedHashMap()

        fun lock(k: String, t: Int, j: Int): Float { val a = locks[k + "@" + t]; return if (a == null || j < 0 || j >= a.size) Float.NaN else a[j] }

        fun setLock(k: String, t: Int, j: Int, v: Float) {
            val key = k + "@" + t
            var a = locks[key]
            if (a == null) { if (v.isNaN()) return; a = FloatArray(MAXSTEPS); a.fill(Float.NaN); locks[key] = a }
            if (j >= 0 && j < a.size) a[j] = v
            var any = false
            for (x in a) if (!x.isNaN()) { any = true; break }
            if (!any) locks.remove(key)
        }

        /** Does this step have any lock on it? */
        fun hasLock(k: String, j: Int): Boolean {
            for (e in locks.entries) if (e.key.startsWith(k + "@") && j >= 0 && j < e.value.size && !e.value[j].isNaN()) return true
            return false
        }

        fun addOff(k: String, tick: Int) {
            val t = tick.mod(len * TICKS)
            if (t % TICKS == 0) { setStep(k, t / TICKS, true); return }
            var l = offs[k]
            if (l == null) { l = ArrayList(); offs[k] = l }
            if (!l.contains(t)) { l.add(t); l.sort() }
        }

        /** Is this off-grid hit already there? */
        fun hasOff(k: String, tick: Int): Boolean {
            val l = offs[k]
            return l != null && l.contains(tick.mod(len * TICKS))
        }

        /**
         * Nov 22 (review): when a loop's length changes, the per-step extras (accents, chances, locks, off-grid hits)
         * past the new end go, and bars from..to start clean; with copy, the new bars take bar for bar what the first
         * ones had.
         */
        fun extrasFor(from: Int, to: Int, copy: Boolean) {
            for (a in acc.values) for (j in from until minOf(to, a.size)) a[j] = if (copy && from > 0) a[j % from] else 1.toByte()
            for (a in chance.values) for (j in from until minOf(to, a.size)) a[j] = if (copy && from > 0) a[j % from] else 100.toByte()
            for (a in locks.values) for (j in from until minOf(to, a.size)) a[j] = if (copy && from > 0) a[j % from] else Float.NaN
            for (l in offs.values) {
                l.removeAll { t -> t >= from * TICKS }
                if (copy && from > 0) {
                    val base: List<Int> = ArrayList(l)
                    var r = 1
                    while (r * from < to) { for (t in base) { val nt = t + r * from * TICKS; if (nt < to * TICKS && !l.contains(nt)) l.add(nt) }; r++ }
                    l.sort()
                }
            }
        }

        /** Takes back one off-grid hit (a swipe cancelled it). */
        fun removeOff(k: String, tick: Int) {
            val t = tick.mod(len * TICKS)
            val l = offs[k]
            if (l != null) l.remove(t)  // the value, not the index (Java's remove((Integer) t))
        }

        /** Off-grid hits of a sound that fall inside step j. */
        fun offsIn(k: String, j: Int): Int {
            val l = offs[k] ?: return 0
            var n = 0
            for (t in l) if (t / TICKS == j) n++
            return n
        }

        fun setMotion(pad: Int, target: Int, step: Int, v: Float) {
            var a = motion[pad.toString() + ":" + target]
            if (a == null) { a = FloatArray(MAXSTEPS); a.fill(Float.NaN); motion[pad.toString() + ":" + target] = a }
            if (step >= 0 && step < a.size) a[step] = v
        }

        fun hasMotion(): Boolean = !motion.isEmpty()

        /* ---- the automation view (note #17): one lane is "pad:target", one value per step, NaN = no move ---- */

        /** A lane, made empty if it is not there yet. */
        fun lane(key: String): FloatArray {
            var a = motion[key]
            if (a == null) { a = FloatArray(MAXSTEPS); a.fill(Float.NaN); motion[key] = a }
            return a
        }

        /** Iron out a shaky take: each step moves toward the average of its neighbours, amt 0..1. Gaps stay gaps. */
        fun laneSmooth(key: String, amt: Float) {
            val a = motion[key] ?: return
            val b = a.copyOf()
            for (j in 0 until len) {
                if (a[j].isNaN()) continue
                var sum = 0f; var n = 0
                for (d in -2..2) { val k = (j + d).mod(len); if (!a[k].isNaN()) { sum += a[k]; n++ } }
                b[j] = a[j] + (sum / n - a[j]) * maxOf(0f, minOf(1f, amt))
            }
            b.copyInto(a, 0, 0, len)
        }

        /**
         * Stamp a shape over the lane, repeating every span steps, between lo and hi, blended in by strength (0 keeps the
         * lane, 1 is the pure shape). Shapes: 0 hold, 1 ramp up, 2 ramp down, 3 triangle, 4 sine, 5 saw, 6 square.
         */
        fun laneStamp(key: String, shape: Int, span: Int, lo: Float, hi: Float, strength: Float) {
            val a = lane(key)
            @Suppress("NAME_SHADOWING") val span = maxOf(1, span)
            val k = maxOf(0f, minOf(1f, strength))
            for (j in 0 until len) {
                val ph = (j % span) / span.toDouble()
                val v: Double
                when (shape) {
                    1 -> v = if (span == 1) 1.0 else (j % span) / (span - 1).toDouble()
                    2 -> v = if (span == 1) 0.0 else 1 - (j % span) / (span - 1).toDouble()
                    3 -> v = 1 - abs(2 * ph - 1)
                    4 -> v = .5 + .5 * sin(2 * PI * ph)
                    5 -> v = ph
                    6 -> v = if (ph < .5) 1.0 else 0.0
                    else -> v = if (a[j - j % span].isNaN()) .5 else (a[j - j % span] - lo) / maxOf(1e-6f, hi - lo).toDouble()
                }
                val target = (lo + (hi - lo) * v).toFloat()
                a[j] = if (a[j].isNaN()) target else a[j] + (target - a[j]) * k
            }
        }

        /** One lane off. */
        fun laneClear(key: String) { motion.remove(key) }

        /** Build 106: every lane of one pad, gone (the pad was cleared). */
        fun lanesForget(pad: Int) { motion.keys.removeAll { k -> k.startsWith(pad.toString() + ":") } }

        /** Build 106: lanes follow their pad when two pads swap. */
        fun lanesSwap(a: Int, b: Int) {
            val out: MutableMap<String, FloatArray> = LinkedHashMap()
            for (e in motion.entries) {
                val k = e.key
                val c = k.indexOf(':')
                val pd = if (c > 0) parseIntOr(k.substring(0, c), -1) else -1
                val nk = if (pd == a) b.toString() + k.substring(c) else if (pd == b) a.toString() + k.substring(c) else k
                out[nk] = e.value
            }
            motion.clear(); motion.putAll(out)
        }

        fun step(k: String, j: Int): Boolean { val a = steps[k]; return a != null && j >= 0 && j < a.size && a[j] }

        fun setStep(k: String, j: Int, v: Boolean) {
            var a = steps[k]
            if (a == null) { if (!v) return; a = BooleanArray(MAXSTEPS); steps[k] = a }
            if (j >= 0 && j < a.size) a[j] = v
        }

        fun notes(k: String): MutableList<Note> {
            var n = notes[k]
            if (n == null) { n = ArrayList(); notes[k] = n }
            return n
        }

        /* ---- row tools (Steps, note #16 and the redraft): one sound's row in this loop ---- */

        /** A hit on every n-th step, nothing else (the row's pitches go). */
        fun rowEvery(k: String, n: Int) {
            rowClear(k)
            var j = 0
            while (j < len) { setStep(k, j, true); j += maxOf(1, n) }
        }

        /** The first n steps, with their pitches, copied across the whole row. */
        fun rowRepeat(k: String, n: Int) {
            if (n <= 0 || n >= len) return
            for (j in n until len) setStep(k, j, step(k, j % n))
            val ns = notes(k)
            val first: MutableList<Note> = ArrayList()
            for (x in ns) if (x.s < n) first.add(x.copy())
            ns.clear()
            var m = 0
            while (m * n < len) {
                for (x in first) {
                    val s = x.s + m * n
                    if (s < len) ns.add(Note(s, x.p, maxOf(1, minOf(x.l, len - s))))
                }
                m++
            }
        }

        /** The whole row moved by steps (wrapping round the loop), pitches too. */
        fun rowNudge(k: String, by: Int) {
            val a = BooleanArray(len)
            for (j in 0 until len) a[(j + by).mod(len)] = step(k, j)
            for (j in 0 until len) setStep(k, j, a[j])
            for (x in notes(k)) { x.s = (x.s + by).mod(len); x.l = maxOf(1, minOf(x.l, len - x.s)) }
        }

        /** hits spread as evenly as the loop allows (Euclid): 3 over 8 is the tresillo. */
        fun rowSpread(k: String, hits: Int) {
            rowClear(k)
            @Suppress("NAME_SHADOWING") val hits = maxOf(0, minOf(len, hits))
            for (j in 0 until len) if ((j * hits) % len < hits) setStep(k, j, true)
        }

        /** Every step and pitch of the row off. */
        fun rowClear(k: String) {
            val a = steps[k]
            if (a != null) a.fill(false)
            val n = notes[k]
            if (n != null) n.clear()
            offs.remove(k)
        }

        fun hasNoteAt(k: String, s: Int): Boolean {
            val n = notes[k]
            if (n != null) for (x in n) if (x.s == s) return true
            return false
        }

        fun used(): Boolean {
            for (n in notes.values) if (!n.isEmpty()) return true
            for (a in steps.values) for (j in 0 until len) if (a[j]) return true
            for (l in offs.values) if (!l.isEmpty()) return true
            return false
        }

        fun usedBy(k: String): Boolean {
            val n = notes[k]
            if (n != null && !n.isEmpty()) return true
            val a = steps[k]
            if (a != null) for (j in 0 until len) if (a[j]) return true
            val o = offs[k]
            return o != null && !o.isEmpty()
        }

        fun copy(): Pattern {
            val p = Pattern()
            p.len = len
            for (e in steps.entries) p.steps[e.key] = e.value.copyOf()
            for (e in notes.entries) {
                val l: MutableList<Note> = ArrayList()
                for (n in e.value) l.add(n.copy())
                p.notes[e.key] = l
            }
            for (e in motion.entries) p.motion[e.key] = e.value.copyOf()
            for (e in offs.entries) p.offs[e.key] = ArrayList(e.value)
            for (e in acc.entries) p.acc[e.key] = e.value.copyOf()
            for (e in chance.entries) p.chance[e.key] = e.value.copyOf()
            for (e in locks.entries) p.locks[e.key] = e.value.copyOf()
            p.extra.putAll(extra)
            return p
        }

        fun clear() { steps.clear(); notes.clear(); motion.clear(); offs.clear(); acc.clear(); chance.clear(); locks.clear(); extra.clear() }

        /** Pull a sound out of the loop entirely (when its pad is cleared). */
        fun forget(k: String) { steps.remove(k); notes.remove(k); offs.remove(k); acc.remove(k); chance.remove(k); locks.keys.removeAll { x -> x.startsWith(k + "@") } }
    }

    @JvmField var name: String = ""
    /** Nov 23 (shared format): top-level keys this version does not know, kept as they were and written back. */
    @JvmField val extra: MutableMap<String, Any?> = LinkedHashMap()
    @JvmField val kit: Array<String> = Array(SLOTS) { "" }
    /** Per-pad settings (glide, routing, XY); null = the defaults. */
    @JvmField val opts: Array<PadOpts?> = arrayOfNulls(SLOTS)

    fun opt(pad: Int): PadOpts { val o = if (pad >= 0 && pad < SLOTS) opts[pad] else null; return o ?: PadOpts.DEFAULT }
    fun setOpt(pad: Int, o: PadOpts?) { if (pad >= 0 && pad < SLOTS) opts[pad] = if (o == null || o.isDefault()) null else o.copy() }
    @JvmField val pats: Array<Pattern> = Array(NSLOTS) { Pattern() }
    @JvmField val song: IntArray = IntArray(MAXSONG)
    @JvmField var bpm = 112
    @JvmField var swing = 0
    @JvmField var sel = 7
    @JvmField var root = 0
    @JvmField var cur = 0
    /**
     * The master bus (overhaul, last build): squash is how hard the mix is pressed together (0 off, .5 where it always
     * sat, 1 hard), level is the mix's volume after it (1 = as before). Saved only when changed.
     */
    @JvmField var squash = .5
    @JvmField var level = 1.0
    @JvmField var fold = false
    @JvmField var songMode = false
    @JvmField var scale = "Minor" // new messes start in C minor (Tyler, 2026-11-10); files without a scale still load as Chromatic
    @JvmField val mutes: MutableSet<String> = LinkedHashSet()
    @JvmField val solos: MutableSet<String> = LinkedHashSet()
    @JvmField val edits: MutableMap<String, SoundEdit> = LinkedHashMap()
    /** SE-11: the mess's synths, by key ("synth:<id>"); a synth sits on a pad like a sound. */
    @JvmField val synths: MutableMap<String, Synth.Patch> = LinkedHashMap()

    init {
        kit.fill("")
        song.fill(-1)
        // pats: each slot already holds a new Pattern (made where the field is declared)
    }

    private fun interface EditFn { fun apply(e: SoundEdit) }

    fun pat(): Pattern = pats[cur]

    fun edit(k: String): SoundEdit { val e = edits[k]; return e ?: SoundEdit.DEFAULT }

    fun setEdit(k: String, e: SoundEdit?) { if (e == null || e.isDefault()) edits.remove(k) else edits[k] = e.copy() }

    fun synth(k: String?): Synth.Patch? = if (k != null && k.startsWith("synth:")) synths[k] else null

    /** A new synth key no other synth in this mess uses. */
    fun newSynthKey(): String { var n = 1; while (synths.containsKey("synth:" + n)) n++; return "synth:" + n }

    /** Drop (Nov 21): while held, only these sounds are heard; null the rest of the time. Never saved. */
    @Transient @JvmField var dropKeep: MutableSet<String>? = null
    fun audible(k: String): Boolean { val dk = dropKeep; if (dk != null) return dk.contains(k); return if (solos.isEmpty()) !mutes.contains(k) else solos.contains(k) }

    fun songLen(): Int { var n = 0; for (k in 0 until MAXSONG) if (song[k] >= 0) n = k + 1; return n }

    fun padOf(k: String): Int { for (k2 in 0 until NPADS) if (kit[k2] == k) return k2; return -1 }

    fun loadedCount(): Int { var n = 0; for (k in 0 until NPADS) if (!kit[k].isEmpty()) n++; return n }

    /** Put a sound on a pad. A sound lives on one pad at a time, like the site. */
    fun assign(pad: Int, key: String) {
        val old = padOf(key)
        if (old >= 0 && old != pad) kit[old] = ""
        val was = kit[pad]
        kit[pad] = key
        if (!was.isEmpty() && was != key && padOf(was) < 0) for (p in pats) p.forget(was)
        sel = pad
    }

    fun clearPad(pad: Int) {
        val k = kit[pad]
        if (k.isEmpty()) return
        kit[pad] = ""
        for (p in pats) { p.forget(k); p.lanesForget(pad) }
        // build 106: the pad's own settings go with its sound, so the next sound starts clean
        opts[pad] = null
        mutes.remove(k)
        solos.remove(k)
    }

    /** Build 106: two pads trade everything: sound, steps (they follow the sound), lanes and pad settings. */
    fun swapPads(a: Int, b: Int) {
        if (a == b || a < 0 || b < 0 || a >= NPADS || b >= NPADS) return
        val t = kit[a]; kit[a] = kit[b]; kit[b] = t
        val o = opts[a]; opts[a] = opts[b]; opts[b] = o
        for (p in pats) p.lanesSwap(a, b)
    }

    fun copy(): Project = fromJson(toJson(), null).project

    /* ---------- the site's saved-mess format, v3 ---------- */

    fun toJson(): String = toJson(true)

    /** withTime = false leaves out the save time, for comparing two states (undo). */
    @Suppress("SENSELESS_COMPARISON")
    fun toJson(withTime: Boolean): String {
        val o: MutableMap<String, Any?> = LinkedHashMap()
        // name == null: a Java caller can still set the field to null
        o["name"] = (if (name == null || name.isEmpty()) "Untitled mess" else if (name.length > 48) name.substring(0, 48) else name)
        o["bpm"] = bpm.toDouble()
        o["v"] = V.toDouble()
        o["kit"] = ArrayList<Any?>(kit.asList())
        o["sel"] = sel.toDouble()
        o["steps"] = encSteps(pats[0])
        val pl: MutableList<Any?> = ArrayList()
        for (p in pats) {
            val x: MutableMap<String, Any?> = LinkedHashMap()
            x["len"] = p.len.toDouble()
            x["steps"] = encSteps(p)
            val nm: MutableMap<String, Any?> = LinkedHashMap()
            for (e in p.notes.entries) {
                if (e.value.isEmpty() || padOf(e.key) < 0) continue
                val b = StringBuilder()
                for (n in e.value) { if (b.length > 0) b.append(','); b.append(n.s).append('.').append(n.p).append('.').append(n.l) }
                nm[e.key] = b.toString()
            }
            x["notes"] = nm
            if (!p.motion.isEmpty()) {
                val mm: MutableMap<String, Any?> = LinkedHashMap()
                for (e in p.motion.entries) {
                    val b = StringBuilder()
                    val a = e.value
                    for (j in 0 until p.len) { if (j > 0) b.append(','); if (!a[j].isNaN()) b.append((a[j] * 1000).roundToInt() / 1000f) }
                    mm[e.key] = b.toString()
                }
                x["motion"] = mm
            }
            if (!p.offs.isEmpty()) {
                val om: MutableMap<String, Any?> = LinkedHashMap()
                for (e in p.offs.entries) {
                    if (e.value.isEmpty() || padOf(e.key) < 0) continue
                    val b = StringBuilder()
                    for (t in e.value) { if (b.length > 0) b.append(','); b.append(t) }
                    om[e.key] = b.toString()
                }
                if (!om.isEmpty()) x["offs"] = om
            }
            if (!p.acc.isEmpty()) {
                val am: MutableMap<String, Any?> = LinkedHashMap()
                for (e in p.acc.entries) {
                    if (padOf(e.key) < 0) continue
                    val b = StringBuilder()
                    var any = false
                    for (j in 0 until p.len) { val v = e.value[j].toInt(); b.append(if (v == 0) 'g' else if (v == 2) 'a' else '.'); if (v != 1) any = true }
                    if (any) am[e.key] = b.toString()
                }
                if (!am.isEmpty()) x["acc"] = am
            }
            if (!p.chance.isEmpty()) {
                val cm: MutableMap<String, Any?> = LinkedHashMap()
                for (e in p.chance.entries) {
                    if (padOf(e.key) < 0) continue
                    val b = StringBuilder()
                    var any = false
                    for (j in 0 until p.len) { if (j > 0) b.append(','); val v = e.value[j].toInt(); if (v != 100) { b.append(v); any = true } }
                    if (any) cm[e.key] = b.toString()
                }
                if (!cm.isEmpty()) x["chance"] = cm
            }
            if (!p.locks.isEmpty()) {
                val lm: MutableMap<String, Any?> = LinkedHashMap()
                for (e in p.locks.entries) {
                    val k = e.key; val at = k.lastIndexOf('@')
                    if (at < 0 || padOf(k.substring(0, at)) < 0) continue
                    val b = StringBuilder()
                    for (j in 0 until p.len) { if (j > 0) b.append(','); val v = e.value[j]; if (!v.isNaN()) b.append((v * 1000).roundToInt() / 1000f) }
                    lm[k] = b.toString()
                }
                if (!lm.isEmpty()) x["locks"] = lm
            }
            for (e in p.extra.entries) if (!x.containsKey(e.key)) x[e.key] = e.value
            pl.add(x)
        }
        o["pats"] = pl
        val sg = StringBuilder()
        val sl = maxOf(16, songLen())
        for (k in 0 until sl) sg.append(if (song[k] >= 0) LETTERS[song[k]] else '-')
        o["song"] = sg.toString()
        o["swing"] = swing.toDouble()
        if (abs(squash - .5) > 1e-6) o["squash"] = squash
        if (abs(level - 1) > 1e-6) o["level"] = level
        o["mutes"] = ArrayList<Any?>(mutes)
        o["solos"] = ArrayList<Any?>(solos)
        o["scale"] = scale
        o["root"] = root.toDouble()
        o["fold"] = fold
        val em: MutableMap<String, Any?> = LinkedHashMap()
        for (k in kit) if (!k.isEmpty() && edits.containsKey(k)) em[k] = edits[k]!!.toMap()
        o["edits"] = em
        val sy: MutableMap<String, Any?> = LinkedHashMap()
        for (k in kit) if (synths.containsKey(k)) sy[k] = synths[k]!!.toMap()
        if (!sy.isEmpty()) o["synths"] = sy
        val po: MutableMap<String, Any?> = LinkedHashMap()
        for (k in 0 until SLOTS) { val ok = opts[k]; if (ok != null && !ok.isDefault()) po[k.toString()] = ok.toMap() }
        if (!po.isEmpty()) o["padOpts"] = po
        if (withTime) o["at"] = isoNow()  // isoNow() is Plat.kt's now (was a private Java helper on Instant.now())
        o["app"] = "android"
        o["mode"] = if (songMode) "song" else "loop"
        o["cur"] = cur.toDouble()
        for (e in extra.entries) if (!o.containsKey(e.key)) o[e.key] = e.value
        return Json.write(o)
    }

    private fun encSteps(p: Pattern): MutableMap<String, Any?> {
        val m: MutableMap<String, Any?> = LinkedHashMap()
        for (k in kit) {
            if (k.isEmpty()) continue
            val b = StringBuilder()
            for (j in 0 until p.len) b.append(if (p.step(k, j)) '1' else '0')
            m[k] = b.toString()
        }
        return m
    }

    /**
     * What loading a mess found. missing counts pads whose sound this app does not have: since Nov 23 they keep their
     * key (silent, shown as missing) so saving does not wipe them. newer: the file says a later format than this app's.
     */
    class Loaded internal constructor(@JvmField val project: Project, @JvmField val missing: Int, @JvmField val newer: Boolean)

    fun interface Known { fun has(key: String): Boolean }
}
