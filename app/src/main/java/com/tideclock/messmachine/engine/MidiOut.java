package com.tideclock.messmachine.engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Build 7 of the overhaul: the loop or the song as a standard MIDI file, one track per sound, named after it, so a
 * DAW can put each sound on its own instrument. Steps land on C3 (note 60, or 60 plus the step's pitch), at their
 * strength's velocity; hits between the sixteenths keep their place. Chance and loop conditions are written as if
 * every step plays, since a MIDI file has no dice.
 */
public final class MidiOut {
    public static final int PPQ = 96;
    /** MIDI ticks per sequencer step (a sixteenth). */
    public static final int STEP = PPQ / 4;
    static final int[] VEL = {48, 100, 127};

    private MidiOut() {}

    /** The patterns played, in order: the song lane, or the current loop loops times. */
    static List<Project.Pattern> order(Project p, boolean song, int loops) {
        List<Project.Pattern> l = new ArrayList<>();
        if (song && p.songLen() > 0) { for (int k = 0; k < p.songLen(); k++) if (p.song[k] >= 0) l.add(p.pats[p.song[k]]); }
        else for (int i = 0; i < Math.max(1, loops); i++) l.add(p.pats[p.cur]);
        return l;
    }

    /** One note: when (MIDI ticks), how long, which note, how hard. */
    static final class Ev { final long at, len; final int note, vel; Ev(long at, long len, int note, int vel) { this.at = at; this.len = len; this.note = note; this.vel = vel; } }

    /** Every sound's notes, by sound key, in the order the kit lists them. */
    static Map<String, List<Ev>> events(Project p, boolean song, int loops) {
        Map<String, List<Ev>> out = new LinkedHashMap<>();
        long base = 0;
        for (Project.Pattern P : order(p, song, loops)) {
            for (String k : p.kit) {
                if (k == null || k.isEmpty() || !P.usedBy(k)) continue;
                List<Ev> l = out.get(k);
                if (l == null) { l = new ArrayList<>(); out.put(k, l); }
                for (int j = 0; j < P.len; j++) {
                    int v = VEL[Math.max(0, Math.min(2, P.strength(k, j)))];
                    if (P.step(k, j) && !P.hasNoteAt(k, j)) l.add(new Ev(base + (long) j * STEP, STEP, 60, v));
                }
                List<Project.Note> nl = P.notes.get(k);
                if (nl != null) for (Project.Note n : nl) {
                    if (n.s < 0 || n.s >= P.len) continue;
                    l.add(new Ev(base + (long) n.s * STEP, (long) Math.max(1, n.l) * STEP, Math.max(0, Math.min(127, 60 + n.p)), VEL[Math.max(0, Math.min(2, P.strength(k, n.s)))]));
                }
                List<Integer> ol = P.offs.get(k);
                if (ol != null) for (int t : ol) l.add(new Ev(base + (long) t * STEP / Project.TICKS, STEP / 2, 60, 100));
            }
            base += (long) P.len * STEP;
        }
        for (List<Ev> l : out.values()) l.sort((a, b) -> Long.compare(a.at, b.at));
        return out;
    }

    /** Write the file. names gives each sound's track name (the sound's title); null uses the key. */
    public static void write(Project p, boolean song, int loops, Map<String, String> names, OutputStream os) throws IOException {
        Map<String, List<Ev>> ev = events(p, song, loops);
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        f.write("MThd".getBytes(StandardCharsets.US_ASCII));
        int32(f, 6); int16(f, 1); int16(f, ev.size() + 1); int16(f, PPQ);
        // track 0: the tempo and the name of the mess
        ByteArrayOutputStream t0 = new ByteArrayOutputStream();
        vlq(t0, 0); t0.write(new byte[]{(byte) 0xFF, 0x51, 3});
        int us = (int) Math.round(60_000_000.0 / Math.max(20, p.bpm));
        t0.write((us >> 16) & 0xFF); t0.write((us >> 8) & 0xFF); t0.write(us & 0xFF);
        meta(t0, 0x03, p.name == null || p.name.isEmpty() ? "Mess Machine" : p.name);
        vlq(t0, 0); t0.write(new byte[]{(byte) 0xFF, 0x2F, 0});
        chunk(f, t0);
        int ch = 0;
        for (Map.Entry<String, List<Ev>> e : ev.entrySet()) {
            ByteArrayOutputStream t = new ByteArrayOutputStream();
            String nm = names != null && names.get(e.getKey()) != null ? names.get(e.getKey()) : e.getKey();
            meta(t, 0x03, nm);
            // on and off events, sorted by time, offs first at the same time so repeated notes retrigger
            List<long[]> on = new ArrayList<>();
            for (Ev x : e.getValue()) { on.add(new long[]{x.at, 1, x.note, x.vel}); on.add(new long[]{x.at + x.len, 0, x.note, 0}); }
            on.sort((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
            int c = ch == 9 ? 10 : ch;  // channel 10 is drums in most DAWs: keep every sound on its own melodic channel
            long last = 0;
            for (long[] x : on) {
                vlq(t, x[0] - last); last = x[0];
                t.write((x[1] == 1 ? 0x90 : 0x80) | (c & 0x0F)); t.write((int) x[2]); t.write((int) (x[1] == 1 ? x[3] : 0));
            }
            vlq(t, 0); t.write(new byte[]{(byte) 0xFF, 0x2F, 0});
            chunk(f, t);
            ch = (ch + 1) % 16;
        }
        f.writeTo(os);
    }

    static void meta(ByteArrayOutputStream t, int type, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        vlq(t, 0); t.write(0xFF); t.write(type); vlq(t, b.length); t.write(b);
    }
    static void chunk(ByteArrayOutputStream f, ByteArrayOutputStream t) throws IOException {
        f.write("MTrk".getBytes(StandardCharsets.US_ASCII)); int32(f, t.size()); t.writeTo(f);
    }
    static void vlq(ByteArrayOutputStream o, long v) {
        long buf = v & 0x7F;
        while ((v >>= 7) > 0) { buf <<= 8; buf |= ((v & 0x7F) | 0x80); }
        while (true) { o.write((int) (buf & 0xFF)); if ((buf & 0x80) != 0) buf >>= 8; else break; }
    }
    static void int32(ByteArrayOutputStream o, int v) { o.write(v >>> 24); o.write((v >> 16) & 0xFF); o.write((v >> 8) & 0xFF); o.write(v & 0xFF); }
    static void int16(ByteArrayOutputStream o, int v) { o.write((v >> 8) & 0xFF); o.write(v & 0xFF); }
}
