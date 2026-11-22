package com.tideclock.messmachine.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Scales, keys mode and chords, ported one to one from the Mess Machine on the site. */
public final class Theory {
    private Theory() {}

    /** The roll and the Padiano's keys: C1 to C6 (0 is C4). Build 84 widened it from C3 to C5 (note #18). */
    public static final int ROLL_HI = 24, ROLL_LO = -36;
    public static final String[] NN = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};

    public static final Map<String, int[]> SCALES = new LinkedHashMap<>();
    static {
        SCALES.put("Chromatic", new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11});
        SCALES.put("Major", new int[]{0, 2, 4, 5, 7, 9, 11});
        SCALES.put("Minor", new int[]{0, 2, 3, 5, 7, 8, 10});
        SCALES.put("Harmonic minor", new int[]{0, 2, 3, 5, 7, 8, 11});
        SCALES.put("Dorian", new int[]{0, 2, 3, 5, 7, 9, 10});
        SCALES.put("Phrygian", new int[]{0, 1, 3, 5, 7, 8, 10});
        SCALES.put("Mixolydian", new int[]{0, 2, 4, 5, 7, 9, 10});
        SCALES.put("Major pentatonic", new int[]{0, 2, 4, 7, 9});
        SCALES.put("Minor pentatonic", new int[]{0, 3, 5, 7, 10});
        SCALES.put("Blues", new int[]{0, 3, 5, 6, 7, 10});
    }

    public static final Map<String, int[]> QUAL = new LinkedHashMap<>();
    public static final Map<String, String> QSUF = new LinkedHashMap<>();
    private static void q(String k, String suf, int... iv) { QUAL.put(k, iv); QSUF.put(k, suf); }
    static {
        q("maj", "", 0, 4, 7); q("min", "m", 0, 3, 7); q("dim", "°", 0, 3, 6); q("aug", "+", 0, 4, 8);
        q("5", "5", 0, 7, 12); q("sus2", "sus2", 0, 2, 7); q("sus4", "sus4", 0, 5, 7); q("add9", "add9", 0, 4, 7, 14);
        q("7", "7", 0, 4, 7, 10); q("maj7", "maj7", 0, 4, 7, 11); q("m7", "m7", 0, 3, 7, 10); q("m7b5", "m7♭5", 0, 3, 6, 10);
        q("mMaj7", "m(maj7)", 0, 3, 7, 11); q("m9", "m9", 0, 3, 7, 10, 14); q("9", "9", 0, 4, 7, 10, 14);
        q("maj9", "maj9", 0, 4, 7, 11, 14); q("6", "6", 0, 4, 7, 9); q("m6", "m6", 0, 3, 7, 9); q("7sus4", "7sus4", 0, 5, 7, 10);
        q("dim7", "°7", 0, 3, 6, 9);
    }

    private static final Map<String, String> IVSUF = new LinkedHashMap<>();
    static {
        IVSUF.put("0,4,7", ""); IVSUF.put("0,3,7", "m"); IVSUF.put("0,3,6", "°"); IVSUF.put("0,4,8", "+"); IVSUF.put("0,7,12", "5");
        IVSUF.put("0,4,7,11", "maj7"); IVSUF.put("0,4,7,10", "7"); IVSUF.put("0,3,7,10", "m7"); IVSUF.put("0,3,6,10", "m7♭5");
        IVSUF.put("0,3,7,11", "m(maj7)"); IVSUF.put("0,3,6,9", "°7"); IVSUF.put("0,4,8,11", "+maj7"); IVSUF.put("0,2,7", "sus2"); IVSUF.put("0,5,7", "sus4"); IVSUF.put("0,12", " oct"); IVSUF.put("0,6,7", "sus♯4");
    }

    /**
     * A chord set (Nov 22, Tyler: twelve chords, all in key, smooth changes). Each chord is a root (semitones above
     * the mess's root, inside the set's home scale) and a quality that stays in that scale. The first seven are one
     * chord on each degree of the scale, in order, so a key in the scale plays the chord rooted on itself; the last
     * five are colour chords (sevenths, sus, add9) that the keys outside the scale play. Picking a set whose home is
     * not the mess's scale moves the scale to it, so every chord stays in key.
     */
    public static final class ChordSet {
        public final String name, home;
        public final int[] roots;
        public final String[] quals;
        ChordSet(String name, String home, int[] roots, String[] quals) { this.name = name; this.home = home; this.roots = roots; this.quals = quals; }
    }

    public static final Map<String, ChordSet> CHORDSETS = new LinkedHashMap<>();
    /** spec: twelve "root quality" pairs, e.g. "0 maj, 2 min, ..." */
    private static void cs(String n, String home, String spec) {
        String[] parts = spec.split(",");
        if (parts.length != 12) throw new IllegalArgumentException(n + " needs 12 chords");
        int[] r = new int[12]; String[] q = new String[12];
        for (int k = 0; k < 12; k++) { String[] rq = parts[k].trim().split(" "); r[k] = Integer.parseInt(rq[0]); q[k] = rq[1]; }
        CHORDSETS.put(n, new ChordSet(n, home, r, q));
    }
    static {
        //  degrees 1 to 7 of the home scale, then 5 colour chords
        cs("Plain Major", "Major", "0 maj, 2 min, 4 min, 5 maj, 7 maj, 9 min, 11 dim, 0 maj7, 2 m7, 5 maj7, 7 7, 9 m7");
        cs("Plain Minor", "Minor", "0 min, 2 dim, 3 maj, 5 min, 7 min, 8 maj, 10 maj, 0 m7, 3 maj7, 5 m7, 8 maj7, 10 7");
        cs("Sunday Pop", "Major", "0 maj, 2 min, 4 min, 5 maj, 7 maj, 9 min, 11 m7b5, 0 sus2, 5 add9, 7 sus4, 7 7, 9 m7");
        cs("Dreamy Sus", "Major", "0 sus2, 2 m7, 4 m7, 5 maj7, 7 sus4, 9 m7, 11 m7b5, 0 add9, 2 sus2, 5 sus2, 7 add9, 9 sus4");
        cs("Gospel Drip", "Major", "0 maj9, 2 m9, 4 m7, 5 maj7, 7 9, 9 m9, 11 m7b5, 0 6, 2 7sus4, 5 6, 7 7sus4, 0 add9");
        cs("Lo-fi Jazz", "Major", "0 maj7, 2 m7, 4 m7, 5 maj7, 7 7, 9 m7, 11 m7b5, 0 maj9, 2 m9, 5 maj9, 7 9, 9 m9");
        cs("Basement Minor", "Minor", "0 min, 2 dim, 3 maj, 5 min, 7 min, 8 maj, 10 maj, 0 sus2, 0 m7, 5 m7, 8 maj7, 10 sus2");
        cs("Neo Soul", "Dorian", "0 m9, 2 m7, 3 maj7, 5 9, 7 m7, 9 m7b5, 10 maj7, 0 m7, 3 maj9, 5 7, 7 m9, 10 maj9");
        cs("Cinematic", "Minor", "0 min, 2 dim, 3 maj, 5 min, 7 sus4, 8 maj, 10 maj, 0 sus2, 3 add9, 5 sus2, 8 maj7, 10 sus2");
        cs("Haunted", "Harmonic minor", "0 mMaj7, 2 m7b5, 3 aug, 5 m7, 7 7, 8 maj7, 11 dim7, 0 min, 2 dim, 5 min, 7 maj, 8 maj");
        cs("Trap Dread", "Phrygian", "0 min, 1 maj, 3 maj, 5 min, 7 dim, 8 maj, 10 min, 0 m7, 1 maj7, 5 m7, 8 maj7, 10 m7");
        cs("Power Stabs", "Minor", "0 5, 2 dim, 3 5, 5 5, 7 5, 8 5, 10 5, 0 sus4, 3 sus2, 5 sus2, 7 sus4, 10 sus2");
    }

    public static int pcOf(int p) { return ((p % 12) + 12) % 12; }

    public static String noteName(int p) { return NN[pcOf(p)] + (4 + Math.floorDiv(p, 12)); }

    public static int[] scale(String name) { int[] s = SCALES.get(name); return s != null ? s : SCALES.get("Chromatic"); }

    public static boolean contains(int[] a, int v) { for (int x : a) if (x == v) return true; return false; }

    public static boolean inScale(int p, String scale, int root) { return contains(scale(scale), pcOf(p - root)); }

    public static int foldP(int p) { while (p > ROLL_HI) p -= 12; while (p < ROLL_LO) p += 12; return p; }

    public static boolean setFits(ChordSet set, String scale) {
        int[] sc = scale(scale);
        for (int k = 0; k < set.roots.length; k++) for (int iv : QUAL.get(set.quals[k])) if (!contains(sc, pcOf(set.roots[k] + iv))) return false;
        return true;
    }

    static int[] harmScale(String scale) {
        if (scale.equals("Chromatic") || scale.equals("Major pentatonic")) return SCALES.get("Major");
        if (scale.equals("Minor pentatonic") || scale.equals("Blues")) return SCALES.get("Minor");
        return scale(scale);
    }

    static int[] stripScale(String scale) { return scale.equals("Chromatic") ? SCALES.get("Major") : scale(scale); }

    /** The keys strip: every in-scale note of the roll's range. */
    public static int[] stripNotes(String scale, int root) {
        int[] sc = stripScale(scale);
        List<Integer> out = new ArrayList<>();
        for (int p = ROLL_LO; p <= ROLL_HI; p++) if (contains(sc, pcOf(p - root))) out.add(p);
        int[] r = new int[out.size()];
        for (int k = 0; k < r.length; k++) r[k] = out.get(k);
        return r;
    }

    /** Keys mode on the 4x4 grid: d counts from the top left, the lowest note sits bottom left. */
    public static int keyNote(int d, String scale, int root) {
        int r = d / 4, c = d % 4, idx = (3 - r) * 4 + c;
        int[] sc = scale(scale);
        return root + sc[idx % sc.length] + 12 * (idx / sc.length) - (root > 5 ? 12 : 0);
    }

    public static float chordGain(int n) { return n > 1 ? (float) (.85 * Math.min(1, 1.15 / Math.sqrt(n))) : .85f; }

    /** The chord a key plays before folding into the roll. mode: off, triad, seventh, power, or set:Name */
    static int[] chordRaw(int p, int idx, String mode, String scale, int root) {
        if (mode.equals("power")) return new int[]{p, p + 7, p + 12};
        if (mode.equals("octave")) return new int[]{p, p + 12};
        if (mode.startsWith("set:")) {
            // a fixed slot: idx = slot + set size * octave. Slot N is always the same chord, moved only by the
            // mess's root and whole octaves, so a set never drifts across the keys (note #18)
            ChordSet set = CHORDSETS.get(mode.substring(4));
            if (set == null) return new int[]{p};
            int n = set.roots.length, j = Math.floorMod(idx, n), o = Math.floorDiv(idx, n);
            int base = rootBase(root) + set.roots[j] + 12 * o;
            int[] iv = QUAL.get(set.quals[j]), out = new int[iv.length];
            for (int k = 0; k < iv.length; k++) out[k] = base + iv[k];
            return out;
        }
        if (mode.equals("triad") || mode.equals("seventh") || mode.equals("sus")) {
            // harmonizing the scale: scale notes n, n+2, n+4 (n+6 for a seventh; n+3, n+4 for 1-4-5, the sus sound)
            int[] sc = harmScale(scale);
            int n = sc.length, rel = pcOf(p - root), deg = -1;
            for (int k = 0; k < n; k++) if (sc[k] == rel) deg = k;
            boolean sev = mode.equals("seventh"), sus = mode.equals("sus");
            if (deg < 0) {
                int[] iv = sev ? new int[]{0, 4, 7, 10} : sus ? new int[]{0, 5, 7} : new int[]{0, 4, 7}, out = new int[iv.length];
                for (int k = 0; k < iv.length; k++) out[k] = p + iv[k];
                return out;
            }
            int[] stack = sev ? new int[]{0, 2, 4, 6} : sus ? new int[]{0, 3, 4} : new int[]{0, 2, 4}, out = new int[stack.length];
            int base = p - rel;
            for (int k = 0; k < stack.length; k++) { int j = deg + stack[k]; out[k] = base + sc[j % n] + 12 * (j / n); }
            return out;
        }
        return new int[]{p};
    }

    /**
     * The chord a key plays: root on the bottom, never folded note by note (build 84, note #18). A chord that runs
     * past the top or bottom of the range moves by whole octaves, so it keeps its shape.
     */
    public static int[] chordOf(int p, int idx, String mode, String scale, int root) {
        if (mode.equals("off")) return new int[]{p};
        TreeSet<Integer> set = new TreeSet<>();
        for (int x : chordRaw(p, idx, mode, scale, root)) set.add(x);
        int shift = 0;
        while (set.last() + shift > ROLL_HI && set.first() + shift - 12 >= ROLL_LO) shift -= 12;
        while (set.first() + shift < ROLL_LO && set.last() + shift + 12 <= ROLL_HI) shift += 12;
        int[] r = new int[set.size()];
        int k = 0;
        for (int x : set) r[k++] = x + shift;
        return r;
    }

    /** Where the mess's root sits for chord sets: C to F on C4 and up, F# to B just under it. */
    public static int rootBase(int root) { return root > 5 ? root - 12 : root; }

    /** How many chords a set mode has (0 when it is not a set). */
    public static int setSize(String mode) {
        if (!mode.startsWith("set:")) return 0;
        ChordSet set = CHORDSETS.get(mode.substring(4));
        return set == null ? 0 : set.roots.length;
    }

    /**
     * A piano key's slot in a chord set (Nov 22): a key in the scale plays its degree's chord (rooted on the key), a
     * key outside the scale plays one of the colour chords. The same key in every octave plays the same chord, an
     * octave apart. Integer.MIN_VALUE when the set has no such slot.
     */
    public static int setIndexOfKey(int p, String mode, String scale, int root) {
        int n = setSize(mode);
        if (n == 0) return Integer.MIN_VALUE;
        // keys in the scale take the first slots in scale order (each plays the chord on its own degree); keys
        // outside the scale take the colour slots after them, low to high, so every one of the 12 keys plays
        int rel = pcOf(p - root);
        int[] sc = stripScale(scale);
        boolean[] in = new boolean[12];
        for (int iv : sc) in[pcOf(iv)] = true;
        int slot = 0;
        if (in[rel]) { for (int k = 0; k < rel; k++) if (in[k]) slot++; }
        else { for (int k = 0; k < 12; k++) if (in[k]) slot++; for (int k = 0; k < rel; k++) if (!in[k]) slot++; }
        if (slot >= n) return Integer.MIN_VALUE;
        return slot + n * Math.floorDiv(p - rootBase(root), 12);
    }

    /**
     * Smooth chord changes (Nov 22, the default): the inversion of a chord that moves the fewest semitones from the
     * last chord played, kept within a fifth of where the chord was asked for, so the keys' octaves still count.
     * With no last chord, the chord as asked. Chords of one or two notes are left alone.
     */
    public static int[] smooth(int[] chord, int[] prev) {
        int n = chord.length;
        if (n < 3) return chord;
        int[] c = chord.clone();
        Arrays.sort(c);
        if (prev == null || prev.length == 0) return c;
        double target = mean(c), bestCost = Double.MAX_VALUE;
        int[] best = c;
        for (int r = 0; r < n; r++) for (int sh = -2; sh <= 1; sh++) {
            int[] v = new int[n];
            for (int k = 0; k < n; k++) v[k] = c[k] + (k < r ? 12 : 0) + 12 * sh;
            Arrays.sort(v);
            if (v[0] < ROLL_LO || v[n - 1] > ROLL_HI) continue;
            double m = mean(v);
            if (Math.abs(m - target) > 7.5) continue;
            double cost = move(v, prev) + .25 * Math.abs(m - target);
            if (cost < bestCost - 1e-9) { bestCost = cost; best = v; }
        }
        return best;
    }

    private static double mean(int[] v) { double t = 0; for (int x : v) t += x; return t / v.length; }

    /** How far a hand moves from one chord to the next: each note to the nearest note of the other chord, both ways. */
    private static double move(int[] a, int[] b) {
        double d = 0;
        for (int x : a) { int m = Integer.MAX_VALUE; for (int y : b) m = Math.min(m, Math.abs(x - y)); d += m; }
        for (int y : b) { int m = Integer.MAX_VALUE; for (int x : a) m = Math.min(m, Math.abs(x - y)); d += m; }
        return d;
    }

    /** The name of slot j of a set, in the mess's root. */
    public static String slotName(String mode, int j, int root) {
        ChordSet set = CHORDSETS.get(mode.startsWith("set:") ? mode.substring(4) : mode);
        if (set == null) return "";
        int i = Math.floorMod(j, set.roots.length);
        return NN[pcOf(root + set.roots[i])] + QSUF.get(set.quals[i]);
    }

    public static String chordLabel(int p, int idx, String mode, String scale, int root) {
        if (mode.equals("off")) return noteName(p);
        if (mode.startsWith("set:")) {
            return CHORDSETS.get(mode.substring(4)) == null ? noteName(p) : slotName(mode, idx, root);
        }
        int[] t = chordRaw(p, idx, mode, scale, root);
        StringBuilder iv = new StringBuilder();
        for (int k = 0; k < t.length; k++) { if (k > 0) iv.append(','); iv.append(t[k] - t[0]); }
        String suf = IVSUF.get(iv.toString());
        return NN[pcOf(t[0])] + (suf != null ? suf : "");
    }

    public static String chordNotes(int[] ps) {
        StringBuilder b = new StringBuilder();
        for (int p : ps) { if (b.length() > 0) b.append(' '); b.append(noteName(p)); }
        return b.toString();
    }

    public static String describe(int[] a) { return Arrays.toString(a); }
}
