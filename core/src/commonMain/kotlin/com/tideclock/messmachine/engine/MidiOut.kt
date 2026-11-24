package com.tideclock.messmachine.engine

import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic
import kotlin.math.roundToLong

/**
 * Build 7 of the overhaul: the loop or the song as a standard MIDI file, one track per sound, named after it, so a
 * DAW can put each sound on its own instrument. Steps land on C3 (note 60, or 60 plus the step's pitch), at their
 * strength's velocity; hits between the sixteenths keep their place. Chance and loop conditions are written as if
 * every step plays, since a MIDI file has no dice.
 */
object MidiOut {
    const val PPQ = 96
    /** MIDI ticks per sequencer step (a sixteenth). */
    const val STEP = PPQ / 4
    @JvmField internal val VEL = intArrayOf(48, 100, 127)

    /** The patterns played, in order: the song lane, or the current loop loops times. */
    // public (was package-private): Engine uses it
    @JvmStatic
    fun order(p: Project, song: Boolean, loops: Int): List<Project.Pattern> {
        val l: MutableList<Project.Pattern> = ArrayList()
        if (song && p.songLen() > 0) { for (k in 0 until p.songLen()) if (p.song[k] >= 0) l.add(p.pats[p.song[k]]) }
        else for (i in 0 until maxOf(1, loops)) l.add(p.pats[p.cur])
        return l
    }

    /** One note: when (MIDI ticks), how long, which note, how hard. */
    internal class Ev(@JvmField val at: Long, @JvmField val len: Long, @JvmField val note: Int, @JvmField val vel: Int)

    /** Every sound's notes, by sound key, in the order the kit lists them. */
    internal fun events(p: Project, song: Boolean, loops: Int): Map<String, MutableList<Ev>> {
        val out: MutableMap<String, MutableList<Ev>> = LinkedHashMap()
        var base = 0L
        for (P in order(p, song, loops)) {
            for (k in p.kit) {
                if (k == null || k.isEmpty() || !P.usedBy(k)) continue
                var l = out[k]
                if (l == null) { l = ArrayList(); out[k] = l }
                for (j in 0 until P.len) {
                    val v = VEL[maxOf(0, minOf(2, P.strength(k, j)))]
                    if (P.step(k, j) && !P.hasNoteAt(k, j)) l.add(Ev(base + j.toLong() * STEP, STEP.toLong(), 60, v))
                }
                val nl = P.notes[k]
                if (nl != null) for (n in nl) {
                    if (n.s < 0 || n.s >= P.len) continue
                    l.add(Ev(base + n.s.toLong() * STEP, maxOf(1, n.l).toLong() * STEP, maxOf(0, minOf(127, 60 + n.p)), VEL[maxOf(0, minOf(2, P.strength(k, n.s)))]))
                }
                val ol = P.offs[k]
                if (ol != null) for (t in ol) l.add(Ev(base + t.toLong() * STEP / Project.TICKS, (STEP / 2).toLong(), 60, 100))
            }
            base += P.len.toLong() * STEP
        }
        for (l in out.values) l.sortWith { a, b -> a.at.compareTo(b.at) }
        return out
    }

    /**
     * Write the file. names gives each sound's track name (the sound's title); null uses the key.
     * Returns the file's bytes (was: wrote them to an OutputStream).
     */
    @JvmStatic
    fun write(p: Project, song: Boolean, loops: Int, names: Map<String, String>?): ByteArray {
        val ev = events(p, song, loops)
        val f = Out()
        f.write("MThd".encodeToByteArray())
        int32(f, 6); int16(f, 1); int16(f, ev.size + 1); int16(f, PPQ)
        // track 0: the tempo and the name of the mess
        val t0 = Out()
        vlq(t0, 0); t0.write(byteArrayOf(0xFF.toByte(), 0x51, 3))
        val us = jround(60_000_000.0 / maxOf(20, p.bpm)).toInt()
        t0.write((us shr 16) and 0xFF); t0.write((us shr 8) and 0xFF); t0.write(us and 0xFF)
        val pn = p.name
        meta(t0, 0x03, if (pn == null || pn.isEmpty()) "Mess Machine" else pn)
        vlq(t0, 0); t0.write(byteArrayOf(0xFF.toByte(), 0x2F, 0))
        chunk(f, t0)
        var ch = 0
        for (e in ev.entries) {
            val t = Out()
            val nm = if (names != null && names[e.key] != null) names[e.key]!! else e.key
            meta(t, 0x03, nm)
            // on and off events, sorted by time, offs first at the same time so repeated notes retrigger
            val on: MutableList<LongArray> = ArrayList()
            for (x in e.value) { on.add(longArrayOf(x.at, 1, x.note.toLong(), x.vel.toLong())); on.add(longArrayOf(x.at + x.len, 0, x.note.toLong(), 0)) }
            on.sortWith { a, b -> if (a[0] != b[0]) a[0].compareTo(b[0]) else a[1].compareTo(b[1]) }
            val c = if (ch == 9) 10 else ch  // channel 10 is drums in most DAWs: keep every sound on its own melodic channel
            var last = 0L
            for (x in on) {
                vlq(t, x[0] - last); last = x[0]
                t.write((if (x[1] == 1L) 0x90 else 0x80) or (c and 0x0F)); t.write(x[2].toInt()); t.write((if (x[1] == 1L) x[3] else 0L).toInt())
            }
            vlq(t, 0); t.write(byteArrayOf(0xFF.toByte(), 0x2F, 0))
            chunk(f, t)
            ch = (ch + 1) % 16
        }
        return f.toByteArray()
    }

    internal fun meta(t: Out, type: Int, s: String) {
        val b = s.encodeToByteArray()
        vlq(t, 0); t.write(0xFF); t.write(type); vlq(t, b.size.toLong()); t.write(b)
    }
    internal fun chunk(f: Out, t: Out) {
        f.write("MTrk".encodeToByteArray()); int32(f, t.size()); t.writeTo(f)
    }
    internal fun vlq(o: Out, v0: Long) {
        var v = v0
        var buf = v and 0x7F
        while (true) { v = v shr 7; if (v <= 0) break; buf = buf shl 8; buf = buf or ((v and 0x7F) or 0x80) }
        while (true) { o.write((buf and 0xFF).toInt()); if ((buf and 0x80) != 0L) buf = buf shr 8 else break }
    }
    internal fun int32(o: Out, v: Int) { o.write(v ushr 24); o.write((v shr 16) and 0xFF); o.write((v shr 8) and 0xFF); o.write(v and 0xFF) }
    internal fun int16(o: Out, v: Int) { o.write((v shr 8) and 0xFF); o.write(v and 0xFF) }

    /** Rounds like the Java engine did (half up): roundToLong, but NaN gives 0 instead of throwing. */
    private fun jround(d: Double): Long = if (d.isNaN()) 0L else d.roundToLong()

    /** A growing byte buffer, in place of the JVM ByteArrayOutputStream. write(int) keeps the low 8 bits, like it did. */
    internal class Out {
        private var a = ByteArray(256)
        private var n = 0
        private fun room(more: Int) { if (n + more > a.size) a = a.copyOf(maxOf(a.size * 2, n + more)) }
        fun write(b: Int) { room(1); a[n++] = b.toByte() }
        fun write(b: ByteArray) { room(b.size); b.copyInto(a, n); n += b.size }
        fun size(): Int = n
        fun writeTo(o: Out) { o.write(toByteArray()) }
        fun toByteArray(): ByteArray = a.copyOf(n)
    }
}
