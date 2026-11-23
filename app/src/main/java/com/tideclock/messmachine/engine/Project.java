package com.tideclock.messmachine.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One mess: 64 pads, 8 loops, a song lane, tempo, swing, mutes and every sound's doctor
 * settings. Saves to the same JSON the site's Mess Machine saves (v3), so a mess can move
 * between the site and the phone.
 */
public final class Project {
    public static final int NPADS = 64, BANK = 16, NSLOTS = 8, MAXSTEPS = 128, MAXSONG = 64;
    /** Nov 21: a slot past the 64 pads, in no bank, for the Synth tab's scratch synth; it plays and records like a pad. */
    public static final int SCRATCH = NPADS, SLOTS = NPADS + 1;
    /** The finer time grid: ticks in one sixteenth step (96 a quarter note), enough for every triplet and dotted rate. */
    public static final int TICKS = 24;
    public static final String LETTERS = "ABCDEFGH";
    public static final String[] DEFAULT_KIT = {"basement-kick", "wet-clap", "pot-lid-hat", "bw-snare", "tom-of-doom", "bubble-pop", "spring-doink", "tape-wobble-chord"};

    public static final class Note {
        public int s, p, l;
        public Note(int s, int p, int l) { this.s = s; this.p = p; this.l = l; }
        public Note copy() { return new Note(s, p, l); }
    }

    public static final class Pattern {
        public int len = 16;
        public final Map<String, boolean[]> steps = new LinkedHashMap<>();
        public final Map<String, List<Note>> notes = new LinkedHashMap<>();
        /** App only: recorded XY moves, "pad:target" to one value per step (NaN = nothing on that step). */
        public final Map<String, float[]> motion = new LinkedHashMap<>();
        /**
         * The finer time grid (Nov 18): hits between the sixteenths, per sound, in ticks from the top of the loop
         * (TICKS per step). Triplet and dotted repeats land here, and live hits when Rec is set to exact.
         */
        public final Map<String, List<Integer>> offs = new LinkedHashMap<>();
        /**
         * Build 108: how hard each step hits, per sound: 0 ghost (quiet), 1 normal, 2 accent (full). Missing = normal.
         * Saved as "acc": one character per step, "g", "." or "a".
         */
        public final Map<String, byte[]> acc = new LinkedHashMap<>();

        public int strength(String k, int j) { byte[] a = acc.get(k); return a == null || j < 0 || j >= a.length ? 1 : a[j]; }

        public void setStrength(String k, int j, int v) {
            byte[] a = acc.get(k);
            if (a == null) { if (v == 1) return; a = new byte[MAXSTEPS]; Arrays.fill(a, (byte) 1); acc.put(k, a); }
            if (j >= 0 && j < a.length) a[j] = (byte) Math.max(0, Math.min(2, v));
        }

        /**
         * Build 6 of the overhaul: whether each step plays, per sound. 0 to 100 is a percent chance; above that, which
         * time round the loop it plays on: 101 the 1st of 2, 102 the 2nd of 2, 103 the 1st of 4, 104 the 4th of 4.
         * Missing = 100 (always). Saved as "chance": per step, empty for always, else the number.
         */
        public final Map<String, byte[]> chance = new LinkedHashMap<>();
        public static final int[] CHANCES = {100, 75, 50, 25, 101, 102, 103, 104};

        public int chance(String k, int j) { byte[] a = chance.get(k); return a == null || j < 0 || j >= a.length ? 100 : a[j]; }

        public void setChance(String k, int j, int v) {
            byte[] a = chance.get(k);
            if (a == null) { if (v == 100) return; a = new byte[MAXSTEPS]; Arrays.fill(a, (byte) 100); chance.put(k, a); }
            if (j >= 0 && j < a.length) a[j] = (byte) Math.max(0, Math.min(104, v));
        }

        /**
         * Step locks (overhaul, last build): a value for one step only, per sound and control, that snaps back on the
         * next step. Key "sound@target" (Engine.T_FILTER and so on), one value per step, NaN for none. Saved as "locks".
         */
        public final Map<String, float[]> locks = new LinkedHashMap<>();

        public float lock(String k, int t, int j) { float[] a = locks.get(k + "@" + t); return a == null || j < 0 || j >= a.length ? Float.NaN : a[j]; }

        public void setLock(String k, int t, int j, float v) {
            String key = k + "@" + t;
            float[] a = locks.get(key);
            if (a == null) { if (Float.isNaN(v)) return; a = new float[MAXSTEPS]; Arrays.fill(a, Float.NaN); locks.put(key, a); }
            if (j >= 0 && j < a.length) a[j] = v;
            boolean any = false;
            for (float x : a) if (!Float.isNaN(x)) { any = true; break; }
            if (!any) locks.remove(key);
        }

        /** Does this step have any lock on it? */
        public boolean hasLock(String k, int j) {
            for (Map.Entry<String, float[]> e : locks.entrySet()) if (e.getKey().startsWith(k + "@") && j >= 0 && j < e.getValue().length && !Float.isNaN(e.getValue()[j])) return true;
            return false;
        }

        /** What a chance says on screen: "75%", "1:2" and so on; "" for always. */
        public static String chanceLabel(int v) {
            switch (v) { case 101: return "1:2"; case 102: return "2:2"; case 103: return "1:4"; case 104: return "4:4"; case 100: return ""; default: return v + "%"; }
        }

        public void addOff(String k, int tick) {
            int t = Math.floorMod(tick, len * TICKS);
            if (t % TICKS == 0) { setStep(k, t / TICKS, true); return; }
            List<Integer> l = offs.get(k);
            if (l == null) { l = new ArrayList<>(); offs.put(k, l); }
            if (!l.contains(t)) { l.add(t); java.util.Collections.sort(l); }
        }

        /** Takes back one off-grid hit (a swipe cancelled it). */
        public void removeOff(String k, int tick) {
            int t = Math.floorMod(tick, len * TICKS);
            List<Integer> l = offs.get(k);
            if (l != null) l.remove((Integer) t);
        }

        /** Off-grid hits of a sound that fall inside step j. */
        public int offsIn(String k, int j) {
            List<Integer> l = offs.get(k);
            if (l == null) return 0;
            int n = 0;
            for (int t : l) if (t / TICKS == j) n++;
            return n;
        }

        public void setMotion(int pad, int target, int step, float v) {
            float[] a = motion.get(pad + ":" + target);
            if (a == null) { a = new float[MAXSTEPS]; Arrays.fill(a, Float.NaN); motion.put(pad + ":" + target, a); }
            if (step >= 0 && step < a.length) a[step] = v;
        }

        public boolean hasMotion() { return !motion.isEmpty(); }

        /* ---- the automation view (note #17): one lane is "pad:target", one value per step, NaN = no move ---- */

        /** A lane, made empty if it is not there yet. */
        public float[] lane(String key) {
            float[] a = motion.get(key);
            if (a == null) { a = new float[MAXSTEPS]; Arrays.fill(a, Float.NaN); motion.put(key, a); }
            return a;
        }

        /** Iron out a shaky take: each step moves toward the average of its neighbours, amt 0..1. Gaps stay gaps. */
        public void laneSmooth(String key, float amt) {
            float[] a = motion.get(key);
            if (a == null) return;
            float[] b = a.clone();
            for (int j = 0; j < len; j++) {
                if (Float.isNaN(a[j])) continue;
                float sum = 0; int n = 0;
                for (int d = -2; d <= 2; d++) { int k = Math.floorMod(j + d, len); if (!Float.isNaN(a[k])) { sum += a[k]; n++; } }
                b[j] = a[j] + (sum / n - a[j]) * Math.max(0, Math.min(1, amt));
            }
            System.arraycopy(b, 0, a, 0, len);
        }

        /**
         * Stamp a shape over the lane, repeating every span steps, between lo and hi, blended in by strength (0 keeps the
         * lane, 1 is the pure shape). Shapes: 0 hold, 1 ramp up, 2 ramp down, 3 triangle, 4 sine, 5 saw, 6 square.
         */
        public void laneStamp(String key, int shape, int span, float lo, float hi, float strength) {
            float[] a = lane(key);
            span = Math.max(1, span);
            float k = Math.max(0, Math.min(1, strength));
            for (int j = 0; j < len; j++) {
                double ph = (j % span) / (double) span, v;
                switch (shape) {
                    case 1: v = span == 1 ? 1 : (j % span) / (double) (span - 1); break;
                    case 2: v = span == 1 ? 0 : 1 - (j % span) / (double) (span - 1); break;
                    case 3: v = 1 - Math.abs(2 * ph - 1); break;
                    case 4: v = .5 + .5 * Math.sin(2 * Math.PI * ph); break;
                    case 5: v = ph; break;
                    case 6: v = ph < .5 ? 1 : 0; break;
                    default: v = Float.isNaN(a[j - j % span]) ? .5 : (a[j - j % span] - lo) / (double) Math.max(1e-6f, hi - lo); break;
                }
                float target = (float) (lo + (hi - lo) * v);
                a[j] = Float.isNaN(a[j]) ? target : a[j] + (target - a[j]) * k;
            }
        }

        /** One lane off. */
        public void laneClear(String key) { motion.remove(key); }

        /** Build 106: every lane of one pad, gone (the pad was cleared). */
        public void lanesForget(int pad) { motion.keySet().removeIf(k -> k.startsWith(pad + ":")); }

        /** Build 106: lanes follow their pad when two pads swap. */
        public void lanesSwap(int a, int b) {
            Map<String, float[]> out = new LinkedHashMap<>();
            for (Map.Entry<String, float[]> e : motion.entrySet()) {
                String k = e.getKey();
                int c = k.indexOf(':');
                int pd = c > 0 ? parseIntOr(k.substring(0, c), -1) : -1;
                String nk = pd == a ? b + k.substring(c) : pd == b ? a + k.substring(c) : k;
                out.put(nk, e.getValue());
            }
            motion.clear(); motion.putAll(out);
        }

        public boolean step(String k, int j) { boolean[] a = steps.get(k); return a != null && j >= 0 && j < a.length && a[j]; }

        public void setStep(String k, int j, boolean v) {
            boolean[] a = steps.get(k);
            if (a == null) { if (!v) return; a = new boolean[MAXSTEPS]; steps.put(k, a); }
            if (j >= 0 && j < a.length) a[j] = v;
        }

        public List<Note> notes(String k) {
            List<Note> n = notes.get(k);
            if (n == null) { n = new ArrayList<>(); notes.put(k, n); }
            return n;
        }

        /* ---- row tools (Steps, note #16 and the redraft): one sound's row in this loop ---- */

        /** A hit on every n-th step, nothing else (the row's pitches go). */
        public void rowEvery(String k, int n) {
            rowClear(k);
            for (int j = 0; j < len; j += Math.max(1, n)) setStep(k, j, true);
        }

        /** The first n steps, with their pitches, copied across the whole row. */
        public void rowRepeat(String k, int n) {
            if (n <= 0 || n >= len) return;
            for (int j = n; j < len; j++) setStep(k, j, step(k, j % n));
            List<Note> ns = notes(k);
            List<Note> first = new ArrayList<>();
            for (Note x : ns) if (x.s < n) first.add(x.copy());
            ns.clear();
            for (int m = 0; m * n < len; m++) for (Note x : first) {
                int s = x.s + m * n;
                if (s < len) ns.add(new Note(s, x.p, Math.max(1, Math.min(x.l, len - s))));
            }
        }

        /** The whole row moved by steps (wrapping round the loop), pitches too. */
        public void rowNudge(String k, int by) {
            boolean[] a = new boolean[len];
            for (int j = 0; j < len; j++) a[Math.floorMod(j + by, len)] = step(k, j);
            for (int j = 0; j < len; j++) setStep(k, j, a[j]);
            for (Note x : notes(k)) { x.s = Math.floorMod(x.s + by, len); x.l = Math.max(1, Math.min(x.l, len - x.s)); }
        }

        /** hits spread as evenly as the loop allows (Euclid): 3 over 8 is the tresillo. */
        public void rowSpread(String k, int hits) {
            rowClear(k);
            hits = Math.max(0, Math.min(len, hits));
            for (int j = 0; j < len; j++) if ((j * hits) % len < hits) setStep(k, j, true);
        }

        /** Every step and pitch of the row off. */
        public void rowClear(String k) {
            boolean[] a = steps.get(k);
            if (a != null) Arrays.fill(a, false);
            List<Note> n = notes.get(k);
            if (n != null) n.clear();
            offs.remove(k);
        }

        public boolean hasNoteAt(String k, int s) {
            List<Note> n = notes.get(k);
            if (n != null) for (Note x : n) if (x.s == s) return true;
            return false;
        }

        public boolean used() {
            for (List<Note> n : notes.values()) if (!n.isEmpty()) return true;
            for (boolean[] a : steps.values()) for (int j = 0; j < len; j++) if (a[j]) return true;
            for (List<Integer> l : offs.values()) if (!l.isEmpty()) return true;
            return false;
        }

        public boolean usedBy(String k) {
            List<Note> n = notes.get(k);
            if (n != null && !n.isEmpty()) return true;
            boolean[] a = steps.get(k);
            if (a != null) for (int j = 0; j < len; j++) if (a[j]) return true;
            List<Integer> o = offs.get(k);
            return o != null && !o.isEmpty();
        }

        public Pattern copy() {
            Pattern p = new Pattern();
            p.len = len;
            for (Map.Entry<String, boolean[]> e : steps.entrySet()) p.steps.put(e.getKey(), e.getValue().clone());
            for (Map.Entry<String, List<Note>> e : notes.entrySet()) {
                List<Note> l = new ArrayList<>();
                for (Note n : e.getValue()) l.add(n.copy());
                p.notes.put(e.getKey(), l);
            }
            for (Map.Entry<String, float[]> e : motion.entrySet()) p.motion.put(e.getKey(), e.getValue().clone());
            for (Map.Entry<String, List<Integer>> e : offs.entrySet()) p.offs.put(e.getKey(), new ArrayList<>(e.getValue()));
            for (Map.Entry<String, byte[]> e : acc.entrySet()) p.acc.put(e.getKey(), e.getValue().clone());
            for (Map.Entry<String, byte[]> e : chance.entrySet()) p.chance.put(e.getKey(), e.getValue().clone());
            for (Map.Entry<String, float[]> e : locks.entrySet()) p.locks.put(e.getKey(), e.getValue().clone());
            return p;
        }

        public void clear() { steps.clear(); notes.clear(); motion.clear(); offs.clear(); acc.clear(); chance.clear(); locks.clear(); }

        /** Pull a sound out of the loop entirely (when its pad is cleared). */
        public void forget(String k) { steps.remove(k); notes.remove(k); offs.remove(k); acc.remove(k); chance.remove(k); locks.keySet().removeIf(x -> x.startsWith(k + "@")); }
    }

    public String name = "";
    public final String[] kit = new String[SLOTS];
    /** Per-pad settings (glide, routing, XY); null = the defaults. */
    public final PadOpts[] opts = new PadOpts[SLOTS];

    public PadOpts opt(int pad) { return pad >= 0 && pad < SLOTS && opts[pad] != null ? opts[pad] : PadOpts.DEFAULT; }
    public void setOpt(int pad, PadOpts o) { if (pad >= 0 && pad < SLOTS) opts[pad] = o == null || o.isDefault() ? null : o.copy(); }
    public final Pattern[] pats = new Pattern[NSLOTS];
    public final int[] song = new int[MAXSONG];
    public int bpm = 112, swing = 0, sel = 7, root = 0, cur = 0;
    /**
     * The master bus (overhaul, last build): squash is how hard the mix is pressed together (0 off, .5 where it always
     * sat, 1 hard), level is the mix's volume after it (1 = as before). Saved only when changed.
     */
    public double squash = .5, level = 1;
    public boolean fold = false, songMode = false;
    public String scale = "Minor"; // new messes start in C minor (Tyler, 2026-11-10); files without a scale still load as Chromatic
    public final Set<String> mutes = new LinkedHashSet<>(), solos = new LinkedHashSet<>();
    public final Map<String, SoundEdit> edits = new LinkedHashMap<>();
    /** SE-11: the mess's synths, by key ("synth:<id>"); a synth sits on a pad like a sound. */
    public final Map<String, Synth.Patch> synths = new LinkedHashMap<>();

    public Project() {
        Arrays.fill(kit, "");
        Arrays.fill(song, -1);
        for (int k = 0; k < NSLOTS; k++) pats[k] = new Pattern();
    }

    /** A fresh mess with the site's default eight pads. */
    public static Project starter() {
        Project p = new Project();
        for (int k = 0; k < DEFAULT_KIT.length; k++) p.kit[k] = "samples/" + DEFAULT_KIT[k] + ".wav";
        return p;
    }

    /** The first-launch mess: the default kit with a little beat on loop A so it makes noise right away. */
    public static Project demo() {
        Project p = starter();
        p.name = "First mess";
        p.bpm = 100;
        p.swing = 18;
        Pattern a = p.pats[0];
        int[] kick = {0, 6, 8, 11}, clap = {4, 12}, hat = {2, 6, 10, 14, 15};
        for (int j : kick) a.setStep(p.kit[0], j, true);
        for (int j : clap) a.setStep(p.kit[1], j, true);
        for (int j : hat) a.setStep(p.kit[2], j, true);
        a.notes(p.kit[7]).add(new Note(0, 0, 6));
        a.notes(p.kit[7]).add(new Note(8, -4, 6));
        p.edits.put(p.kit[7], editWith(e -> { e.mode = "gate"; e.rel = .08; e.vol = .7; }));
        p.sel = 0;
        return p;
    }

    private interface EditFn { void apply(SoundEdit e); }
    private static SoundEdit editWith(EditFn f) { SoundEdit e = new SoundEdit(); f.apply(e); return e; }

    public Pattern pat() { return pats[cur]; }

    public SoundEdit edit(String k) { SoundEdit e = edits.get(k); return e != null ? e : SoundEdit.DEFAULT; }

    public void setEdit(String k, SoundEdit e) { if (e == null || e.isDefault()) edits.remove(k); else edits.put(k, e.copy()); }

    public Synth.Patch synth(String k) { return k != null && k.startsWith("synth:") ? synths.get(k) : null; }

    public static boolean isSynth(String k) { return k != null && k.startsWith("synth:"); }

    /** A new synth key no other synth in this mess uses. */
    public String newSynthKey() { int n = 1; while (synths.containsKey("synth:" + n)) n++; return "synth:" + n; }

    /** Drop (Nov 21): while held, only these sounds are heard; null the rest of the time. Never saved. */
    public transient java.util.Set<String> dropKeep;
    public boolean audible(String k) { if (dropKeep != null) return dropKeep.contains(k); return solos.isEmpty() ? !mutes.contains(k) : solos.contains(k); }

    public int songLen() { int n = 0; for (int k = 0; k < MAXSONG; k++) if (song[k] >= 0) n = k + 1; return n; }

    public int padOf(String k) { for (int k2 = 0; k2 < NPADS; k2++) if (kit[k2].equals(k)) return k2; return -1; }

    public int loadedCount() { int n = 0; for (int k = 0; k < NPADS; k++) if (!kit[k].isEmpty()) n++; return n; }

    /** Put a sound on a pad. A sound lives on one pad at a time, like the site. */
    public void assign(int pad, String key) {
        int old = padOf(key);
        if (old >= 0 && old != pad) kit[old] = "";
        String was = kit[pad];
        kit[pad] = key;
        if (!was.isEmpty() && !was.equals(key) && padOf(was) < 0) for (Pattern p : pats) p.forget(was);
        sel = pad;
    }

    public void clearPad(int pad) {
        String k = kit[pad];
        if (k.isEmpty()) return;
        kit[pad] = "";
        for (Pattern p : pats) { p.forget(k); p.lanesForget(pad); }
        // build 106: the pad's own settings go with its sound, so the next sound starts clean
        opts[pad] = null;
        mutes.remove(k);
        solos.remove(k);
    }

    /** Build 106: two pads trade everything: sound, steps (they follow the sound), lanes and pad settings. */
    public void swapPads(int a, int b) {
        if (a == b || a < 0 || b < 0 || a >= NPADS || b >= NPADS) return;
        String t = kit[a]; kit[a] = kit[b]; kit[b] = t;
        PadOpts o = opts[a]; opts[a] = opts[b]; opts[b] = o;
        for (Pattern p : pats) p.lanesSwap(a, b);
    }

    static int parseIntOr(String s, int d) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return d; } }

    public Project copy() { return fromJson(toJson(), null).project; }

    /* ---------- the site's saved-mess format, v3 ---------- */

    public String toJson() { return toJson(true); }

    /** withTime = false leaves out the save time, for comparing two states (undo). */
    public String toJson(boolean withTime) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("name", (name == null || name.isEmpty() ? "Untitled mess" : name.length() > 48 ? name.substring(0, 48) : name));
        o.put("bpm", (double) bpm);
        o.put("v", 3.0);
        o.put("kit", new ArrayList<Object>(Arrays.asList((Object[]) kit)));
        o.put("sel", (double) sel);
        o.put("steps", encSteps(pats[0]));
        List<Object> pl = new ArrayList<>();
        for (Pattern p : pats) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("len", (double) p.len);
            x.put("steps", encSteps(p));
            Map<String, Object> nm = new LinkedHashMap<>();
            for (Map.Entry<String, List<Note>> e : p.notes.entrySet()) {
                if (e.getValue().isEmpty() || padOf(e.getKey()) < 0) continue;
                StringBuilder b = new StringBuilder();
                for (Note n : e.getValue()) { if (b.length() > 0) b.append(','); b.append(n.s).append('.').append(n.p).append('.').append(n.l); }
                nm.put(e.getKey(), b.toString());
            }
            x.put("notes", nm);
            if (!p.motion.isEmpty()) {
                Map<String, Object> mm = new LinkedHashMap<>();
                for (Map.Entry<String, float[]> e : p.motion.entrySet()) {
                    StringBuilder b = new StringBuilder();
                    float[] a = e.getValue();
                    for (int j = 0; j < p.len; j++) { if (j > 0) b.append(','); if (!Float.isNaN(a[j])) b.append(Math.round(a[j] * 1000) / 1000f); }
                    mm.put(e.getKey(), b.toString());
                }
                x.put("motion", mm);
            }
            if (!p.offs.isEmpty()) {
                Map<String, Object> om = new LinkedHashMap<>();
                for (Map.Entry<String, List<Integer>> e : p.offs.entrySet()) {
                    if (e.getValue().isEmpty() || padOf(e.getKey()) < 0) continue;
                    StringBuilder b = new StringBuilder();
                    for (int t : e.getValue()) { if (b.length() > 0) b.append(','); b.append(t); }
                    om.put(e.getKey(), b.toString());
                }
                if (!om.isEmpty()) x.put("offs", om);
            }
            if (!p.acc.isEmpty()) {
                Map<String, Object> am = new LinkedHashMap<>();
                for (Map.Entry<String, byte[]> e : p.acc.entrySet()) {
                    if (padOf(e.getKey()) < 0) continue;
                    StringBuilder b = new StringBuilder();
                    boolean any = false;
                    for (int j = 0; j < p.len; j++) { byte v = e.getValue()[j]; b.append(v == 0 ? 'g' : v == 2 ? 'a' : '.'); if (v != 1) any = true; }
                    if (any) am.put(e.getKey(), b.toString());
                }
                if (!am.isEmpty()) x.put("acc", am);
            }
            if (!p.chance.isEmpty()) {
                Map<String, Object> cm = new LinkedHashMap<>();
                for (Map.Entry<String, byte[]> e : p.chance.entrySet()) {
                    if (padOf(e.getKey()) < 0) continue;
                    StringBuilder b = new StringBuilder();
                    boolean any = false;
                    for (int j = 0; j < p.len; j++) { if (j > 0) b.append(','); byte v = e.getValue()[j]; if (v != 100) { b.append(v); any = true; } }
                    if (any) cm.put(e.getKey(), b.toString());
                }
                if (!cm.isEmpty()) x.put("chance", cm);
            }
            if (!p.locks.isEmpty()) {
                Map<String, Object> lm = new LinkedHashMap<>();
                for (Map.Entry<String, float[]> e : p.locks.entrySet()) {
                    String k = e.getKey(); int at = k.lastIndexOf('@');
                    if (at < 0 || padOf(k.substring(0, at)) < 0) continue;
                    StringBuilder b = new StringBuilder();
                    for (int j = 0; j < p.len; j++) { if (j > 0) b.append(','); float v = e.getValue()[j]; if (!Float.isNaN(v)) b.append(Math.round(v * 1000) / 1000f); }
                    lm.put(k, b.toString());
                }
                if (!lm.isEmpty()) x.put("locks", lm);
            }
            pl.add(x);
        }
        o.put("pats", pl);
        StringBuilder sg = new StringBuilder();
        int sl = Math.max(16, songLen());
        for (int k = 0; k < sl; k++) sg.append(song[k] >= 0 ? LETTERS.charAt(song[k]) : '-');
        o.put("song", sg.toString());
        o.put("swing", (double) swing);
        if (Math.abs(squash - .5) > 1e-6) o.put("squash", squash);
        if (Math.abs(level - 1) > 1e-6) o.put("level", level);
        o.put("mutes", new ArrayList<Object>(mutes));
        o.put("solos", new ArrayList<Object>(solos));
        o.put("scale", scale);
        o.put("root", (double) root);
        o.put("fold", fold);
        Map<String, Object> em = new LinkedHashMap<>();
        for (String k : kit) if (!k.isEmpty() && edits.containsKey(k)) em.put(k, edits.get(k).toMap());
        o.put("edits", em);
        Map<String, Object> sy = new LinkedHashMap<>();
        for (String k : kit) if (synths.containsKey(k)) sy.put(k, synths.get(k).toMap());
        if (!sy.isEmpty()) o.put("synths", sy);
        Map<String, Object> po = new LinkedHashMap<>();
        for (int k = 0; k < SLOTS; k++) if (opts[k] != null && !opts[k].isDefault()) po.put(String.valueOf(k), opts[k].toMap());
        if (!po.isEmpty()) o.put("padOpts", po);
        if (withTime) o.put("at", isoNow());
        o.put("app", "android");
        o.put("mode", songMode ? "song" : "loop");
        o.put("cur", (double) cur);
        return Json.write(o);
    }

    private Map<String, Object> encSteps(Pattern p) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : kit) {
            if (k.isEmpty()) continue;
            StringBuilder b = new StringBuilder();
            for (int j = 0; j < p.len; j++) b.append(p.step(k, j) ? '1' : '0');
            m.put(k, b.toString());
        }
        return m;
    }

    static String isoNow() {
        try { return java.time.Instant.now().toString(); } catch (Throwable t) { return ""; }
    }

    /** What loading a mess found. missing counts pads whose sound this app does not have. */
    public static final class Loaded {
        public final Project project;
        public final int missing;
        Loaded(Project p, int m) { project = p; missing = m; }
    }

    public interface Known { boolean has(String key); }

    public static Loaded fromJson(String text, Known known) {
        Map<String, Object> q = Json.map(Json.parse(text));
        Project p = new Project();
        p.name = Json.str(q.get("name"), "");
        for (Map.Entry<String, Object> e : Json.map(q.get("padOpts")).entrySet()) {
            try { int k = Integer.parseInt(e.getKey()); if (k >= 0 && k < SLOTS) p.opts[k] = PadOpts.fromMap(Json.map(e.getValue())); } catch (NumberFormatException ignored) {}
        }
        for (Map.Entry<String, Object> e : Json.map(q.get("synths")).entrySet())
            if (isSynth(e.getKey())) p.synths.put(e.getKey(), Synth.Patch.fromMap(Json.map(e.getValue())));
        List<Object> kl = Json.list(q.get("kit"));
        int missing = 0;
        for (int k = 0; k < kl.size() && k < SLOTS; k++) {
            String key = Json.str(kl.get(k), "");
            if (key.isEmpty()) continue;
            if (known == null || known.has(key) || p.synths.containsKey(key)) {
                if (p.padOf(key) < 0) p.kit[k] = key;
            } else missing++;
        }
        Map<String, Object> ed = Json.map(q.get("edits"));
        for (Map.Entry<String, Object> e : ed.entrySet()) {
            if (p.padOf(e.getKey()) < 0) continue;
            SoundEdit se = SoundEdit.fromMap(Json.map(e.getValue()));
            if (!se.isDefault()) p.edits.put(e.getKey(), se);
        }
        List<Object> pl = Json.list(q.get("pats"));
        if (!pl.isEmpty()) {
            for (int i = 0; i < NSLOTS && i < pl.size(); i++) {
                Map<String, Object> x = Json.map(pl.get(i));
                Pattern P = p.pats[i];
                // 1, 2, 4 or 8 bars (the site plays 1 or 2; longer loops are app only)
                int ln = (int) Json.num(x.get("len"), 16);
                P.len = ln >= 16 && ln <= 128 && ln % 16 == 0 ? ln : 16;  // Nov 22: any 1 to 8 bars (+ and - in the loop strip)
                decSteps(P, Json.map(x.get("steps")));
                for (Map.Entry<String, Object> e : Json.map(x.get("motion")).entrySet()) {
                    float[] a = new float[MAXSTEPS];
                    Arrays.fill(a, Float.NaN);
                    String[] f = Json.str(e.getValue(), "").split(",", -1);
                    for (int j = 0; j < f.length && j < a.length; j++) if (!f[j].isEmpty()) try { a[j] = Float.parseFloat(f[j]); } catch (NumberFormatException ignored) {}
                    P.motion.put(e.getKey(), a);
                }
                for (Map.Entry<String, Object> e : Json.map(x.get("locks")).entrySet()) {
                    String k = e.getKey(); int at = k.lastIndexOf('@');
                    if (at < 0) continue;
                    int t = parseIntOr(k.substring(at + 1), -1);
                    if (t < 0) continue;
                    String[] f = Json.str(e.getValue(), "").split(",", -1);
                    for (int j = 0; j < f.length && j < MAXSTEPS; j++) if (!f[j].isEmpty()) try { P.setLock(k.substring(0, at), t, j, Float.parseFloat(f[j])); } catch (NumberFormatException ignored) {}
                }
                for (Map.Entry<String, Object> e : Json.map(x.get("chance")).entrySet()) {
                    String[] f = Json.str(e.getValue(), "").split(",", -1);
                    for (int j = 0; j < f.length && j < MAXSTEPS; j++) if (!f[j].isEmpty()) P.setChance(e.getKey(), j, parseIntOr(f[j].trim(), 100));
                }
                for (Map.Entry<String, Object> e : Json.map(x.get("acc")).entrySet()) {
                    String f = Json.str(e.getValue(), "");
                    for (int j = 0; j < f.length() && j < MAXSTEPS; j++) { char c = f.charAt(j); if (c == 'g') P.setStrength(e.getKey(), j, 0); else if (c == 'a') P.setStrength(e.getKey(), j, 2); }
                }
                for (Map.Entry<String, Object> e : Json.map(x.get("offs")).entrySet()) {
                    for (String f : Json.str(e.getValue(), "").split(",")) if (!f.isEmpty()) try { P.addOff(e.getKey(), Integer.parseInt(f.trim())); } catch (NumberFormatException ignored) {}
                }
                Object nv = x.get("notes");
                if (nv instanceof String) {
                    String inst = Json.str(x.get("inst"), "");
                    if (!inst.isEmpty()) decNotes(P, inst, (String) nv);
                } else for (Map.Entry<String, Object> e : Json.map(nv).entrySet()) decNotes(P, e.getKey(), Json.str(e.getValue(), ""));
            }
            String sg = Json.str(q.get("song"), "");
            for (int k = 0; k < sg.length() && k < MAXSONG; k++) p.song[k] = LETTERS.indexOf(sg.charAt(k));
        } else decSteps(p.pats[0], Json.map(q.get("steps")));
        // drop anything that points at a sound that is not on a pad
        for (Pattern P : p.pats) {
            P.steps.keySet().removeIf(k -> p.padOf(k) < 0);
            P.notes.keySet().removeIf(k -> p.padOf(k) < 0);
        }
        p.swing = (int) SoundEdit.clamp(Json.num(q.get("swing"), 0), 0, 100);
        p.squash = SoundEdit.clamp(Json.num(q.get("squash"), .5), 0, 1);
        p.level = SoundEdit.clamp(Json.num(q.get("level"), 1), 0, 1.6);
        for (Object m : Json.list(q.get("mutes"))) if (m instanceof String) p.mutes.add((String) m);
        for (Object m : Json.list(q.get("solos"))) if (m instanceof String) p.solos.add((String) m);
        String sc = Json.str(q.get("scale"), "Chromatic");
        p.scale = Theory.SCALES.containsKey(sc) ? sc : "Chromatic";
        p.root = (int) SoundEdit.clamp(Json.num(q.get("root"), 0), 0, 11);
        p.fold = Json.bool(q.get("fold"), false);
        p.bpm = (int) SoundEdit.clamp(Json.num(q.get("bpm"), 112), 60, 180);
        int sel = (int) Json.num(q.get("sel"), -1);
        if (sel < 0 || sel >= NPADS || p.kit[sel].isEmpty()) {
            sel = 0;
            for (int k = 0; k < NPADS; k++) if (!p.kit[k].isEmpty()) { sel = k; break; }
        }
        p.sel = sel;
        p.songMode = "song".equals(q.get("mode")) && p.songLen() > 0;
        p.cur = (int) SoundEdit.clamp(Json.num(q.get("cur"), 0), 0, NSLOTS - 1);
        return new Loaded(p, missing);
    }

    private static void decSteps(Pattern P, Map<String, Object> m) {
        for (Map.Entry<String, Object> e : m.entrySet()) {
            String v = Json.str(e.getValue(), "");
            boolean[] a = new boolean[MAXSTEPS];
            boolean any = false;
            for (int j = 0; j < Math.min(v.length(), MAXSTEPS); j++) if (v.charAt(j) == '1') { a[j] = true; any = true; }
            if (any) P.steps.put(e.getKey(), a);
        }
    }

    private static void decNotes(Pattern P, String k, String str) {
        if (str.isEmpty()) return;
        List<Note> l = P.notes(k);
        for (String t : str.split(",")) {
            String[] f = t.split("\\.");
            if (f.length < 3) continue;
            try {
                int s = Integer.parseInt(f[0].trim()), pp = Integer.parseInt(f[1].trim()), ll = Integer.parseInt(f[2].trim());
                if (s >= 0 && s < P.len) l.add(new Note(s, Math.max(Theory.ROLL_LO, Math.min(Theory.ROLL_HI, pp)), Math.max(1, Math.min(P.len - s, ll))));
            } catch (NumberFormatException ignored) {}
        }
    }
}
