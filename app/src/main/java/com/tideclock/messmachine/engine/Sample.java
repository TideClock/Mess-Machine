package com.tideclock.messmachine.engine;

/** One decoded sound, mono floats at its own sample rate. */
public final class Sample {
    public final String key;
    public final float[] data;
    public final int rate;
    private float[] reversed;
    private final java.util.HashMap<Long, Double> peaks = new java.util.HashMap<>();

    public Sample(String key, float[] data, int rate) {
        this.key = key;
        this.data = data;
        this.rate = rate;
    }

    public double seconds() { return data.length / (double) rate; }

    /** Loudest point inside a trim region, for normalize. Cached for the last region asked. */
    public synchronized double regionPeak(double st, double en) {
        long id = Math.round(st * 1e6) * 2_000_003L + Math.round(en * 1e6);
        Double c = peaks.get(id);
        if (c != null) return c;
        int a = (int) Math.floor(st * data.length), z = (int) Math.ceil(en * data.length);
        double pk = 1e-4;
        for (int k = Math.max(0, a); k < Math.min(data.length, z); k++) { double v = Math.abs(data[k]); if (v > pk) pk = v; }
        if (peaks.size() > 256) peaks.clear();
        peaks.put(id, pk);
        return pk;
    }

    /** Min/max pairs for a window of the sound (fractions a..z), for a zoomed waveform. */
    public float[] overview(double a, double z, int cols) {
        float[] o = new float[cols * 2];
        int n = data.length;
        long from = (long) (a * n), span = Math.max(1, (long) ((z - a) * n));
        for (int c = 0; c < cols; c++) {
            int x0 = (int) (from + span * c / cols), x1 = (int) Math.max(x0 + 1, from + span * (c + 1) / cols);
            float lo = 0, hi = 0;
            for (int k = Math.max(0, x0); k < Math.min(n, x1); k++) { float v = data[k]; if (v < lo) lo = v; if (v > hi) hi = v; }
            o[c * 2] = lo; o[c * 2 + 1] = hi;
        }
        return o;
    }

    /** The nearest zero crossing to f (a fraction) within 4 ms, for clean trims. */
    public double snapZero(double f) {
        int i0 = (int) Math.round(f * data.length), R = (int) Math.round(rate * .004);
        for (int r = 0; r < R; r++) {
            for (int i : new int[]{i0 + r, i0 - r}) {
                if (i > 0 && i < data.length && (data[i - 1] <= 0) != (data[i] <= 0)) return i / (double) data.length;
            }
        }
        return f;
    }

    /** Where the hits are, as fractions, for auto chop. Same method as the site: energy flux over a threshold. */
    public double[] onsets(double st, double en, double sens) {
        int hop = 512, a = (int) Math.floor(st * data.length), z = (int) Math.floor(en * data.length);
        java.util.ArrayList<Double> e = new java.util.ArrayList<>();
        for (int i = a; i + hop < z; i += hop) { double s = 0; for (int j = 0; j < hop; j++) s += data[i + j] * data[i + j]; e.add(Math.sqrt(s / hop)); }
        int n = e.size();
        if (n < 3) return new double[0];
        double[] fl = new double[n];
        double mean = 0;
        for (int i = 0; i < n; i++) { fl[i] = Math.max(0, e.get(i) - (i > 0 ? e.get(i - 1) : e.get(i))); mean += fl[i]; }
        mean /= n;
        double sd = 0;
        for (double v : fl) sd += (v - mean) * (v - mean);
        sd = Math.sqrt(sd / n);
        double th = mean + (2.6 - sens * 2) * sd;
        int gap = (int) Math.round(.08 * rate / hop);
        java.util.ArrayList<int[]> out = new java.util.ArrayList<>();
        int last = -1000;
        for (int i = 2; i < n; i++) if (fl[i] > th && i - last >= gap) { out.add(new int[]{i}); last = i; }
        out.sort((x, y) -> Double.compare(fl[y[0]], fl[x[0]]));
        int keep = Math.min(15, out.size());
        double[] r = new double[keep];
        for (int k = 0; k < keep; k++) r[k] = (a + out.get(k)[0] * (double) hop) / data.length;
        java.util.Arrays.sort(r);
        return r;
    }

    /** Mix interleaved stereo down to one sound. */
    public static Sample fromStereo(String key, float[] lr, int rate) {
        float[] d = new float[lr.length / 2];
        for (int k = 0; k < d.length; k++) d[k] = (lr[k * 2] + lr[k * 2 + 1]) * .5f;
        return new Sample(key, d, rate);
    }

    public synchronized float[] reversedData() {
        if (reversed == null) {
            reversed = new float[data.length];
            for (int k = 0, n = data.length; k < n; k++) reversed[k] = data[n - 1 - k];
        }
        return reversed;
    }

    /** Min/max pairs for drawing a waveform with the given number of columns. */
    public float[] overview(int cols) {
        float[] o = new float[cols * 2];
        int n = data.length;
        for (int c = 0; c < cols; c++) {
            int a = (int) ((long) c * n / cols), z = (int) Math.max(a + 1, (long) (c + 1) * n / cols);
            float lo = 0, hi = 0;
            for (int k = a; k < Math.min(n, z); k++) { float v = data[k]; if (v < lo) lo = v; if (v > hi) hi = v; }
            o[c * 2] = lo; o[c * 2 + 1] = hi;
        }
        return o;
    }

    /** A slice of a longer sprite file, start and length in seconds. */
    public static Sample slice(String key, Sample sprite, double start, double dur) {
        int a = (int) Math.max(0, Math.round(start * sprite.rate));
        int z = (int) Math.min(sprite.data.length, Math.round((start + dur) * sprite.rate));
        float[] d = new float[Math.max(1, z - a)];
        System.arraycopy(sprite.data, a, d, 0, Math.max(0, z - a));
        return new Sample(key, d, sprite.rate);
    }
}
