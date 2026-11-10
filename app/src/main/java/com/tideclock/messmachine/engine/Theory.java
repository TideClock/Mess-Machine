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

    public static final int ROLL_HI = 12, ROLL_LO = -12;
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
    }

    private static final Map<String, String> IVSUF = new LinkedHashMap<>();
    static {
        IVSUF.put("0,4,7", ""); IVSUF.put("0,3,7", "m"); IVSUF.put("0,3,6", "°"); IVSUF.put("0,4,8", "+"); IVSUF.put("0,7,12", "5");
        IVSUF.put("0,4,7,11", "maj7"); IVSUF.put("0,4,7,10", "7"); IVSUF.put("0,3,7,10", "m7"); IVSUF.put("0,3,6,10", "m7♭5");
        IVSUF.put("0,3,7,11", "m(maj7)"); IVSUF.put("0,3,6,9", "°7"); IVSUF.put("0,4,8,11", "+maj7"); IVSUF.put("0,2,7", "sus2"); IVSUF.put("0,5,7", "sus4");
    }

    /** A chord set: eight chords, roots in semitones above the scale root, written in a home scale. */
    public static final class ChordSet {
        public final String name, home;
        public final int[] roots;
        public final String[] quals;
        ChordSet(String name, String home, Object... rq) {
            this.name = name; this.home = home;
            roots = new int[rq.length / 2]; quals = new String[rq.length / 2];
            for (int k = 0; k < roots.length; k++) { roots[k] = (Integer) rq[k * 2]; quals[k] = (String) rq[k * 2 + 1]; }
        }
    }

    public static final Map<String, ChordSet> CHORDSETS = new LinkedHashMap<>();
    private static void cs(String n, String home, Object... rq) { CHORDSETS.put(n, new ChordSet(n, home, rq)); }
    static {
        cs("Sunday Pop", "Major", 0, "maj", 7, "maj", 9, "min", 5, "maj", 2, "min", 4, "min", 5, "maj7", 7, "7");
        cs("Dreamy Sus", "Major", 0, "sus2", 5, "add9", 9, "m7", 7, "sus4", 0, "maj7", 4, "m7", 5, "maj7", 7, "sus2");
        cs("Gospel Drip", "Major", 0, "add9", 4, "m7", 9, "m9", 2, "m9", 7, "9", 5, "maj7", 11, "m7b5", 0, "maj7");
        cs("Basement Minor", "Minor", 0, "min", 8, "maj", 3, "maj", 10, "maj", 5, "min", 7, "min", 0, "m7", 8, "maj7");
        cs("Neo Soul", "Dorian", 0, "m9", 5, "9", 10, "maj7", 3, "maj7", 7, "m7", 2, "m7", 9, "m7b5", 0, "m7");
        cs("Haunted", "Harmonic minor", 0, "min", 7, "7", 8, "maj", 5, "min", 11, "dim", 2, "dim", 3, "aug", 0, "mMaj7");
        cs("Trap Dread", "Phrygian", 0, "min", 1, "maj", 8, "maj", 5, "min", 3, "maj", 10, "min", 0, "m7", 1, "maj7");
        cs("Power Stabs", "Minor", 0, "5", 3, "5", 5, "5", 7, "5", 8, "5", 10, "5", 12, "5", -2, "5");
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

    /** The keys strip: every in-scale note of the roll's two octaves. */
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
        if (mode.startsWith("set:")) {
            ChordSet set = CHORDSETS.get(mode.substring(4));
            if (set == null) return new int[]{p};
            int j = ((idx % 8) + 8) % 8;
            int base = (root > 5 ? root - 12 : root) + set.roots[j] + (idx >= 8 ? 12 : 0);
            int[] iv = QUAL.get(set.quals[j]), out = new int[iv.length];
            for (int k = 0; k < iv.length; k++) out[k] = base + iv[k];
            return out;
        }
        if (mode.equals("triad") || mode.equals("seventh")) {
            int[] sc = harmScale(scale);
            int n = sc.length, rel = pcOf(p - root), deg = -1;
            for (int k = 0; k < n; k++) if (sc[k] == rel) deg = k;
            boolean sev = mode.equals("seventh");
            if (deg < 0) {
                int[] iv = sev ? new int[]{0, 4, 7, 10} : new int[]{0, 4, 7}, out = new int[iv.length];
                for (int k = 0; k < iv.length; k++) out[k] = p + iv[k];
                return out;
            }
            int[] stack = sev ? new int[]{0, 2, 4, 6} : new int[]{0, 2, 4}, out = new int[stack.length];
            int base = p - rel;
            for (int k = 0; k < stack.length; k++) { int j = deg + stack[k]; out[k] = base + sc[j % n] + 12 * (j / n); }
            return out;
        }
        return new int[]{p};
    }

    public static int[] chordOf(int p, int idx, String mode, String scale, int root) {
        if (mode.equals("off")) return new int[]{p};
        TreeSet<Integer> set = new TreeSet<>();
        for (int x : chordRaw(p, idx, mode, scale, root)) set.add(foldP(x));
        int[] r = new int[set.size()];
        int k = 0;
        for (int x : set) r[k++] = x;
        return r;
    }

    public static String chordLabel(int p, int idx, String mode, String scale, int root) {
        if (mode.equals("off")) return noteName(p);
        if (mode.startsWith("set:")) {
            ChordSet set = CHORDSETS.get(mode.substring(4));
            if (set == null) return noteName(p);
            int j = ((idx % 8) + 8) % 8;
            return NN[pcOf(root + set.roots[j])] + QSUF.get(set.quals[j]);
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
