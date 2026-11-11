package com.tideclock.messmachine.engine;

/**
 * Bake (SE-3): renders a sound at another pitch with its length kept, ahead of time, so playing it costs what
 * Stretch costs. A phase vocoder stretches the sound in time by the pitch ratio, then a cubic resample plays the
 * stretch back at that ratio: same length, new pitch. Identity phase locking keeps tones clean (Laroche and
 * Dolson), and frames where a hit lands take their phases straight from the sound, so drums stay sharp.
 * Plain Java with no allocation per sample, run on a background thread, never on the audio thread.
 */
public final class Bake {
    private Bake() {}

    /** Semitones baked either side of the sound's own pitch (25 versions with the original). */
    public static final int RANGE = 12;
    /** Longest sound that bakes: 25 versions of 6 s is about 26 MB of floats at 44.1 kHz. */
    public static final double MAX_SECONDS = 6;

    private static final int N = 2048, HOP = N / 4;

    /** All the versions, index semis + RANGE; the centre slot stays null (the sound itself). */
    public static float[][] all(float[] x, java.util.function.BooleanSupplier stop) {
        float[][] out = new float[2 * RANGE + 1][];
        for (int s = -RANGE; s <= RANGE; s++) {
            if (s == 0) continue;
            if (stop != null && stop.getAsBoolean()) return null;
            out[s + RANGE] = shift(x, Math.pow(2, s / 12.0));
        }
        return out;
    }

    /** x at pitch ratio p, the same length as x. */
    public static float[] shift(float[] x, double p) {
        float[] st = stretch(x, p);
        // going up, the resample reads the stretch faster: cut what would fold back above the new Nyquist first
        if (p > 1.02) lowpass(st, .47 / p);
        float[] y = new float[x.length];
        for (int i = 0; i < y.length; i++) {
            double t = i * p;
            int k = (int) t;
            double f = t - k;
            float a = at(st, k - 1), b = at(st, k), c = at(st, k + 1), d = at(st, k + 2);
            // Catmull-Rom
            y[i] = (float) (b + .5 * f * (c - a + f * (2 * a - 5 * b + 4 * c - d + f * (3 * (b - c) + d - a))));
        }
        return y;
    }

    private static float at(float[] a, int i) { return i < 0 || i >= a.length ? 0 : a[i]; }

    /** x stretched in time by r (length about x.length * r), pitch kept. */
    public static float[] stretch(float[] x, double r) {
        int outLen = (int) Math.ceil(x.length * r) + N;
        float[] y = new float[outLen + N];
        double[] win = new double[N];
        for (int i = 0; i < N; i++) win[i] = .5 - .5 * Math.cos(2 * Math.PI * i / N);
        int H = N / 2 + 1;
        double[] re = new double[N], im = new double[N];
        double[] mag = new double[H], ph = new double[H], prevPh = new double[H], outPh = new double[H], prevMag = new double[H];
        boolean[] peak = new boolean[H];
        int[] owner = new int[H];
        double ha = HOP / r;                       // analysis hop
        double norm = 0;                           // window overlap sum for the synthesis hop
        for (int i = 0; i < N; i += HOP) norm += win[i] * win[i];
        norm = 1 / Math.max(1e-9, norm * 1.0);
        boolean first = true;
        double fluxAvg = 0;
        int frames = (int) Math.ceil((x.length + N / 2.0) / ha) + 1;
        for (int fi = 0; fi < frames; fi++) {
            double ap = fi * ha - N / 2.0;         // analysis position, fractional
            int a0 = (int) Math.floor(ap);
            double af = ap - a0;
            for (int i = 0; i < N; i++) {
                int j = a0 + i;
                double v = (1 - af) * at(x, j) + af * at(x, j + 1);
                re[i] = v * win[i];
                im[i] = 0;
            }
            fftshiftZero(re);
            fft(re, im, false);
            double flux = 0, tot = 0;
            for (int k = 0; k < H; k++) {
                mag[k] = Math.hypot(re[k], im[k]);
                ph[k] = Math.atan2(im[k], re[k]);
                double d = mag[k] - prevMag[k];
                if (d > 0) flux += d;
                tot += mag[k];
            }
            // a hit: energy rising much faster than usual; reset to the sound's own phases so the attack stays put
            boolean hit = !first && tot > 1e-6 && flux > .35 * tot && flux > 3 * fluxAvg;
            fluxAvg += (flux - fluxAvg) * .2;
            if (first || hit) {
                System.arraycopy(ph, 0, outPh, 0, H);
            } else {
                // peaks: local maxima; every other bin follows its nearest peak's phase rotation
                for (int k = 0; k < H; k++) peak[k] = k > 1 && k < H - 2 && mag[k] > mag[k - 1] && mag[k] >= mag[k + 1] && mag[k] > mag[k - 2] && mag[k] >= mag[k + 2];
                int last = -1;
                for (int k = 0; k < H; k++) {
                    if (peak[k]) {
                        double omega = 2 * Math.PI * k / N;
                        double dp = ph[k] - prevPh[k] - omega * ha;
                        dp -= 2 * Math.PI * Math.rint(dp / (2 * Math.PI));
                        double inst = omega + dp / ha;
                        outPh[k] += inst * HOP;
                        // fill the region since the last peak: bins up to the midpoint go to the last peak, the rest to this one
                        int from = last < 0 ? 0 : (last + k) / 2 + 1;
                        for (int q = from; q < k; q++) owner[q] = k;
                        if (last >= 0) for (int q = last + 1; q <= (last + k) / 2; q++) owner[q] = last;
                        owner[k] = k;
                        last = k;
                    }
                }
                if (last < 0) {
                    // no peaks at all (silence or noise): plain phase vocoder for every bin
                    for (int k = 0; k < H; k++) {
                        double omega = 2 * Math.PI * k / N;
                        double dp = ph[k] - prevPh[k] - omega * ha;
                        dp -= 2 * Math.PI * Math.rint(dp / (2 * Math.PI));
                        outPh[k] += (omega + dp / ha) * HOP;
                    }
                } else {
                    for (int q = last + 1; q < H; q++) owner[q] = last;
                    for (int k = 0; k < H; k++) {
                        int o = owner[k];
                        if (o != k) outPh[k] = outPh[o] + (ph[k] - ph[o]);
                    }
                }
            }
            first = false;
            System.arraycopy(ph, 0, prevPh, 0, H);
            System.arraycopy(mag, 0, prevMag, 0, H);
            for (int k = 0; k < H; k++) {
                outPh[k] = wrap(outPh[k]);
                re[k] = mag[k] * Math.cos(outPh[k]);
                im[k] = mag[k] * Math.sin(outPh[k]);
            }
            for (int k = H; k < N; k++) { re[k] = re[N - k]; im[k] = -im[N - k]; }
            fft(re, im, true);
            fftshiftZero(re);
            int sp = fi * HOP - N / 2;
            for (int i = 0; i < N; i++) {
                int j = sp + i;
                if (j >= 0 && j < y.length) y[j] += (float) (re[i] * win[i] * norm);
            }
        }
        int keep = (int) Math.round(x.length * r);
        return java.util.Arrays.copyOf(y, Math.min(y.length, keep));
    }

    private static double wrap(double a) { return a - 2 * Math.PI * Math.rint(a / (2 * Math.PI)); }

    /** Centre the frame on sample 0 so phases measure from the frame's middle (steadier peaks). */
    private static void fftshiftZero(double[] a) {
        int h = a.length / 2;
        for (int i = 0; i < h; i++) { double t = a[i]; a[i] = a[i + h]; a[i + h] = t; }
    }

    /** In-place radix-2 complex FFT; inverse scales by 1/n. */
    static void fft(double[] re, double[] im, boolean inv) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) { double t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = 2 * Math.PI / len * (inv ? 1 : -1);
            double wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int u = i + k, v = i + k + len / 2;
                    double xr = re[v] * cr - im[v] * ci, xi = re[v] * ci + im[v] * cr;
                    re[v] = re[u] - xr; im[v] = im[u] - xi;
                    re[u] += xr; im[u] += xi;
                    double t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t;
                }
            }
        }
        if (inv) for (int i = 0; i < n; i++) { re[i] /= n; im[i] /= n; }
    }

    /** A 63-tap windowed-sinc lowpass at fc (a fraction of the sample rate), in place. */
    private static void lowpass(float[] y, double fc) {
        int M = 63, h = M / 2;
        double[] c = new double[M];
        double s = 0;
        for (int i = 0; i < M; i++) {
            int n = i - h;
            double sinc = n == 0 ? 2 * fc : Math.sin(2 * Math.PI * fc * n) / (Math.PI * n);
            c[i] = sinc * (.42 - .5 * Math.cos(2 * Math.PI * i / (M - 1)) + .08 * Math.cos(4 * Math.PI * i / (M - 1)));
            s += c[i];
        }
        for (int i = 0; i < M; i++) c[i] /= s;
        float[] src = y.clone();
        for (int i = 0; i < y.length; i++) {
            double acc = 0;
            for (int k = 0; k < M; k++) acc += c[k] * at(src, i + k - h);
            y[i] = (float) acc;
        }
    }
}
