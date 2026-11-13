package com.tideclock.messmachine.engine;

/**
 * Mouth control (Tyler: "make wubby noises and convert it to automation"): reads the mic and turns your mouth into
 * two moving numbers, like a finger on an XY pad.
 *
 * <ul>
 *   <li><b>vowel</b>, 0 to 1: how bright the sound is. "Ooo" is dark (near 0), "aah" sits in the middle, "eee" is
 *   bright (near 1). Measured as the spectral centroid between 150 Hz and 4 kHz (pre-emphasized, as speech
 *   analysis does), on a log scale from 600 Hz to 1.8 kHz. A "wow wow" sweeps it up and down, which is the wub.</li>
 *   <li><b>level</b>, 0 to 1: how loud, from about -50 dB to -10 dB.</li>
 *   <li><b>on</b>: you're making a sound (over the gate, with a little hysteresis so it doesn't flicker).</li>
 * </ul>
 *
 * Feed it mono samples at any rate as they arrive; it analyses 32 ms windows every 8 ms. No memory is made after
 * it is built, so it can run on the mic thread without hiccups.
 */
public final class MouthTracker {
    public final int rate;
    private final int n, hop;
    private final float[] ring;
    private int at, sinceHop, filled;
    private final double[] win, re, im;

    /** Smoothed outputs, read them any time. */
    public volatile float vowel, level;
    public volatile boolean on;
    /** The raw numbers of the last window, for the meter and for tests. */
    public volatile float centroidHz, db = -120;
    /** The gate in dB: under it you're quiet. */
    public float gateDb = -46;
    private int quietHops;

    public MouthTracker(int rate) {
        this.rate = rate;
        int nn = 256;
        while (nn < rate * .032) nn *= 2;
        n = nn;
        hop = Math.max(32, (int) Math.round(rate * .008));
        ring = new float[n];
        win = new double[n];
        for (int i = 0; i < n; i++) win[i] = .5 - .5 * Math.cos(2 * Math.PI * i / (n - 1));
        re = new double[n];
        im = new double[n];
    }

    /** Add samples; true when at least one new window was measured. */
    public boolean feed(float[] x, int off, int len) {
        boolean any = false;
        for (int i = 0; i < len; i++) {
            ring[at] = x[off + i];
            at = (at + 1) % n;
            if (filled < n) filled++;
            if (++sinceHop >= hop && filled >= n) { sinceHop = 0; measure(); any = true; }
        }
        return any;
    }

    private void measure() {
        double sq = 0;
        for (int i = 0; i < n; i++) {
            double v = ring[(at + i) % n];
            sq += v * v;
            re[i] = v * win[i];
            im[i] = 0;
        }
        double rms = Math.sqrt(sq / n);
        float d = (float) (20 * Math.log10(Math.max(1e-6, rms)));
        db = d;
        Bake.fft(re, im, false);
        double lo = 150, hi = 4000, num = 0, den = 0;
        int a = (int) Math.ceil(lo * n / rate), z = Math.min(n / 2 - 1, (int) Math.floor(hi * n / rate));
        for (int k = a; k <= z; k++) {
            // pre-emphasis (+6 dB an octave, as speech analysis does) so the upper formant that makes "eee"
            // bright counts as much as the strong low one
            double f = k * (double) rate / n;
            double m = Math.sqrt(re[k] * re[k] + im[k] * im[k]) * f;
            num += m * f;
            den += m;
        }
        double c = den > 0 ? num / den : 0;
        centroidHz = (float) c;
        // the gate: on above it, off after 80 ms under it (3 dB lower), so a word's gaps don't flicker it
        if (d > gateDb) { on = true; quietHops = 0; }
        else if (d < gateDb - 3 && ++quietHops * hop > rate * .08) on = false;
        float lvl = (float) Math.max(0, Math.min(1, (d + 50) / 40.0));
        level += (lvl - level) * (lvl > level ? .6f : .2f);
        if (on && c > 0) {
            float v = (float) Math.max(0, Math.min(1, Math.log(c / 600.0) / Math.log(1800.0 / 600.0)));
            vowel += (v - vowel) * .45f;
        }
    }

    /** Start over (when the mic opens again). */
    public void reset() {
        java.util.Arrays.fill(ring, 0f);
        at = 0; sinceHop = 0; filled = 0; quietHops = 0;
        vowel = 0; level = 0; on = false; db = -120; centroidHz = 0;
    }
}
