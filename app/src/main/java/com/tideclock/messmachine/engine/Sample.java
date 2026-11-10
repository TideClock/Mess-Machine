package com.tideclock.messmachine.engine;

/** One decoded sound, mono floats at its own sample rate. */
public final class Sample {
    public final String key;
    public final float[] data;
    public final int rate;
    private float[] reversed;
    private double peakSt = -1, peakEn = -1, peak = 1;

    public Sample(String key, float[] data, int rate) {
        this.key = key;
        this.data = data;
        this.rate = rate;
    }

    public double seconds() { return data.length / (double) rate; }

    /** Loudest point inside a trim region, for normalize. Cached for the last region asked. */
    public synchronized double regionPeak(double st, double en) {
        if (st == peakSt && en == peakEn) return peak;
        int a = (int) Math.floor(st * data.length), z = (int) Math.ceil(en * data.length);
        double pk = 1e-4;
        for (int k = Math.max(0, a); k < Math.min(data.length, z); k++) { double v = Math.abs(data[k]); if (v > pk) pk = v; }
        peakSt = st; peakEn = en; peak = pk;
        return pk;
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
