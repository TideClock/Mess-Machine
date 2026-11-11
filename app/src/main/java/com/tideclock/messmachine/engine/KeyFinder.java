package com.tideclock.messmachine.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Works out a sound's musical key, so a loop can say "A# minor" in the Sounds list and on its Sample sheet.
 * The app runs it on every sound a player makes (a resampled loop, a mic take, an import), and
 * tools/LoopKeys.java runs the same code over the built-in loops, so both give the same answers.
 *
 * How: the sound's notes are folded into the 12 pitch classes (tuning drift taken out first) and matched
 * against the usual major and minor key shapes. A key is only named when the match is strong and clearly
 * ahead of any key with different notes; drums, noise and unclear sounds get no key (an empty name).
 * A relative pair (A minor and C major) has the same notes, so either answer keeps a player in key.
 */
public final class KeyFinder {
    private KeyFinder() {}

    /** The app's own note names (sharps), as the root picker shows them. */
    public static final String[] NAMES = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
    private static final double[] MAJ = {6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88};
    private static final double[] MIN = {6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17};
    private static final int N = 8192, HOP = 2048;
    /** How sure a key has to be before it is named: the match, the lead over other note sets, and how tonal the sound is. */
    public static final double MATCH = .72, AHEAD = .08, TONAL = .30;
    /** Shorter sounds than this have too little in them to judge; longer ones are judged on their first part. */
    public static final double MIN_SECONDS = .4, MAX_SECONDS = 30;

    /** What one sound holds: weight on each pitch class (sums to 1) and how much of it is clear notes (0 noise, 1 pure). */
    public static final class Tone {
        public final double[] chroma = new double[12];
        public double tonal;
        public int frames;
    }

    /** One candidate key with how well it matches, and how far it leads the best key that has other notes. */
    public static final class Key {
        public final int root;
        public final boolean minor;
        public final double match;
        public double ahead;
        Key(int root, boolean minor, double match) { this.root = root; this.minor = minor; this.match = match; }
        public String name() { return NAMES[root] + (minor ? " minor" : " major"); }
        /** Keys with the same seven notes share this number (A minor and C major both give 0). */
        public int notes() { return minor ? (root + 3) % 12 : root; }
    }

    /** The key's name when it is sure, else "". */
    public static String find(float[] data, int rate) {
        if (data == null || rate <= 0 || data.length < rate * MIN_SECONDS) return "";
        Tone t = measure(data, rate);
        if (t.frames == 0) return "";
        Key k = ranked(t.chroma).get(0);
        return sure(k, t.tonal) ? k.name() : "";
    }

    /**
     * The other name for the same seven notes: "A minor" for "C major" and back. "" for anything that is not a key name.
     * A loop shows both, because either one keeps a player in key.
     */
    public static String relative(String name) {
        if (name == null) return "";
        int sp = name.indexOf(' ');
        if (sp < 0) return "";
        int root = Arrays.asList(NAMES).indexOf(name.substring(0, sp));
        String mode = name.substring(sp + 1);
        if (root < 0) return "";
        if (mode.equals("major")) return NAMES[(root + 9) % 12] + " minor";
        if (mode.equals("minor")) return NAMES[(root + 3) % 12] + " major";
        return "";
    }

    /** A key as a loop shows it: both names for its notes, like "C major / A minor". "" stays "". */
    public static String both(String name) {
        String r = relative(name);
        return r.isEmpty() ? (name == null ? "" : name) : name + " / " + r;
    }

    public static boolean sure(Key k, double tonal) { return k.match >= MATCH && k.ahead >= AHEAD && tonal >= TONAL; }

    /** Every major and minor key, best match first; the first one's ahead is filled in. */
    public static List<Key> ranked(double[] chroma) {
        List<Key> all = new ArrayList<>();
        for (int r = 0; r < 12; r++) {
            all.add(new Key(r, false, corr(MAJ, r, chroma)));
            all.add(new Key(r, true, corr(MIN, r, chroma)));
        }
        all.sort((a, b) -> Double.compare(b.match, a.match));
        Key best = all.get(0);
        for (Key k : all) if (k.notes() != best.notes()) { best.ahead = best.match - k.match; break; }
        return all;
    }

    /** How alike a key shape (turned to start on root) and the sound's pitch classes are: 1 the same, 0 unrelated. */
    private static double corr(double[] shape, int root, double[] c) {
        double ma = 0, mb = 0;
        for (int i = 0; i < 12; i++) { ma += shape[i]; mb += c[i]; }
        ma /= 12; mb /= 12;
        double num = 0, da = 0, db = 0;
        for (int i = 0; i < 12; i++) {
            double a = shape[((i - root) % 12 + 12) % 12] - ma, b = c[i] - mb;
            num += a * b; da += a * a; db += b * b;
        }
        return da <= 0 || db <= 0 ? 0 : num / Math.sqrt(da * db);
    }

    public static Tone measure(float[] data, int rate) {
        Tone out = new Tone();
        // half the rate is plenty for notes up to 2 kHz and halves the work
        int step = rate >= 32000 ? 2 : 1;
        double sr = rate / (double) step;
        int len = (int) Math.min(data.length / step, (long) (sr * MAX_SECONDS));
        if (len < N / 4) return out;
        float[] x = new float[Math.max(len, N)];
        if (step == 2) for (int i = 0; i < len; i++) x[i] = (data[2 * i] + data[2 * i + 1]) * .5f;
        else System.arraycopy(data, 0, x, 0, len);
        int total = x.length;
        // the bins between 60 Hz and 2100 Hz, with one spare each side for peak picking
        int lo = Math.max(1, (int) Math.ceil(60.0 * N / sr)), hi = Math.min(N / 2 - 2, (int) Math.floor(2100.0 * N / sr));
        int nb = hi - lo + 1;
        if (nb < 24) return out;
        double[] midi = new double[nb];
        for (int k = 0; k < nb; k++) midi[k] = 69 + 12 * Math.log((lo + k) * sr / N / 440.0) / Math.log(2);
        double[] win = new double[N];
        for (int i = 0; i < N; i++) win[i] = .5 - .5 * Math.cos(2 * Math.PI * i / (N - 1));
        int frames = (total - N) / HOP + 1;
        float[][] mag = new float[frames][nb];
        double[] re = new double[N], im = new double[N];
        for (int f = 0; f < frames; f++) {
            int at = f * HOP;
            for (int i = 0; i < N; i++) { re[i] = x[at + i] * win[i]; im[i] = 0; }
            fft(re, im);
            for (int k = 0; k < nb; k++) mag[f][k] = (float) Math.hypot(re[lo + k], im[lo + k]);
        }
        // tuning: where do the strongest peaks sit between two semitones?
        double[] devs = new double[frames * 12];
        int nd = 0;
        boolean[] used = new boolean[nb];
        for (int f = 0; f < frames; f++) {
            Arrays.fill(used, false);
            float[] m = mag[f];
            for (int n = 0; n < 12; n++) {
                int top = -1;
                for (int k = 0; k < nb; k++) if (!used[k] && (top < 0 || m[k] > m[top])) top = k;
                if (top < 0) break;
                used[top] = true;
                float left = top > 0 ? m[top - 1] : 0, right = top < nb - 1 ? m[top + 1] : 0;
                if (m[top] > left && m[top] > right && m[top] > 0) devs[nd++] = midi[top] - Math.rint(midi[top]);
            }
        }
        double tune = 0;
        if (nd > 0) {
            double[] d = Arrays.copyOf(devs, nd);
            Arrays.sort(d);
            tune = nd % 2 == 1 ? d[nd / 2] : (d[nd / 2 - 1] + d[nd / 2]) / 2;
        }
        // only energy close to a real note counts toward its pitch class
        int[] pc = new int[nb];
        double[] w = new double[nb];
        for (int k = 0; k < nb; k++) {
            double m2 = midi[k] - tune, near = Math.abs(m2 - Math.rint(m2));
            pc[k] = (((int) Math.rint(m2)) % 12 + 12) % 12;
            w[k] = Math.sqrt(Math.max(0, Math.min(1, 1 - near / .35)));
        }
        double[] c = out.chroma;
        double tonal = 0;
        int counted = 0;
        float[] p = new float[nb];
        for (int f = 0; f < frames; f++) {
            float[] m = mag[f];
            double sum = 0;
            for (int k = 0; k < nb; k++) { c[pc[k]] += m[k] * w[k]; p[k] = m[k] * m[k]; sum += p[k]; }
            if (sum <= 1e-12) continue;
            // how much of the frame's energy sits in its 24 strongest bins: notes are a few peaks, noise is spread
            float[] s = Arrays.copyOf(p, nb);
            Arrays.sort(s);
            double peaks = 0;
            for (int k = nb - 24; k < nb; k++) peaks += s[k];
            tonal += peaks / sum;
            counted++;
        }
        double all = 0;
        for (double v : c) all += v;
        if (all <= 1e-12 || counted == 0) return out;
        for (int i = 0; i < 12; i++) c[i] /= all;
        out.tonal = tonal / counted;
        out.frames = counted;
        return out;
    }

    /** A plain in-place FFT for a power of two length. */
    private static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) { double t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
        }
        for (int size = 2; size <= n; size <<= 1) {
            double ang = -2 * Math.PI / size, wr = Math.cos(ang), wi = Math.sin(ang);
            for (int at = 0; at < n; at += size) {
                double cr = 1, ci = 0;
                for (int k = 0; k < size / 2; k++) {
                    int a = at + k, b = a + size / 2;
                    double xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi;
                    re[a] += xr; im[a] += xi;
                    double nr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr; cr = nr;
                }
            }
        }
    }
}
