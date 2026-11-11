package com.tideclock.messmachine.engine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Synths (SE-11 to SE-16): patches, their knobs, and the voices that play them. A synth sits on a pad like a
 * sound (key "synth:<id>"), so steps, Roll, keys, Hold and Repeat all play it; the engine's own envelope,
 * filters, effects and pan still apply on top.
 *
 * Each kind of synth is a list of knobs (Param) and a voice that reads them once per block, so turning a knob
 * changes notes already sounding. Nothing here allocates while it plays.
 *
 * Analog (SE-12): two band-limited oscillators that morph sine, triangle, saw and square, a sub, noise,
 * unison, a zero-delay ladder or state-variable filter with drive, two envelopes, an LFO. Three modes: Poly,
 * Mono (one voice, glides between held notes) and Acid (mono, a 3-pole squelchy filter, slides when notes
 * overlap, accent on fresh notes).
 */
public final class Synth {
    private Synth() {}

    /* ================= knobs ================= */

    public static final int LIN = 0, LOG = 1, INT = 2, CHOICE = 3;

    public static final class Param {
        public final String id, name, group, unit;
        public final int curve;
        public final double min, max, def;
        public final String[] choices;
        /** On the Simple view. */
        public final boolean simple;

        Param(String id, String name, String group, int curve, double min, double max, double def, String unit, boolean simple, String... choices) {
            this.id = id; this.name = name; this.group = group; this.curve = curve; this.min = min; this.max = max; this.def = def;
            this.unit = unit; this.simple = simple; this.choices = choices;
        }

        /** Knob position 0..1 to a value. */
        public double fromKnob(double k) {
            k = Math.max(0, Math.min(1, k));
            switch (curve) {
                case LOG: return min * Math.pow(max / min, k);
                case INT: case CHOICE: return Math.round(min + (max - min) * k);
                default: return min + (max - min) * k;
            }
        }

        /** A value to a knob position 0..1. */
        public double toKnob(double v) {
            if (max == min) return 0;
            if (curve == LOG) return Math.log(Math.max(min, Math.min(max, v)) / min) / Math.log(max / min);
            return Math.max(0, Math.min(1, (v - min) / (max - min)));
        }

        public double clamp(double v) {
            v = Math.max(Math.min(min, max), Math.min(Math.max(min, max), v));
            return curve == INT || curve == CHOICE ? Math.round(v) : v;
        }

        /** The value as words, for the knob's label. */
        public String show(double v) {
            if (curve == CHOICE) { int i = (int) Math.round(v - min); return i >= 0 && i < choices.length ? choices[i] : ""; }
            if (curve == INT) return (v > 0 && min < 0 ? "+" : "") + Math.round(v) + unit;
            if ("s".equals(unit)) return v < 1 ? Math.round(v * 1000) + " ms" : String.format(java.util.Locale.US, "%.1f s", v); // format-ok: number only
            if ("Hz".equals(unit)) return v < 1000 ? (v < 10 ? String.format(java.util.Locale.US, "%.1f Hz", v) : Math.round(v) + " Hz") : String.format(java.util.Locale.US, "%.1f kHz", v / 1000); // format-ok: number only
            if ("%".equals(unit)) return Math.round(v * 100) + "%";
            if ("c".equals(unit)) return (v > 0 ? "+" : "") + Math.round(v) + " cents";
            return (v > 0 && min < 0 ? "+" : "") + Math.round(v * 100) + "%";
        }
    }

    private static Param p(String id, String name, String group, int curve, double min, double max, double def, String unit, boolean simple, String... ch) {
        return new Param(id, name, group, curve, min, max, def, unit, simple, ch);
    }

    /** A kind of synth: its knobs, its presets and what it is called. */
    public static final class Kind {
        public final String id, name, blurb;
        public final Param[] params;
        public final Map<String, Integer> index = new LinkedHashMap<>();
        public final java.util.List<Patch> presets = new java.util.ArrayList<>();
        Kind(String id, String name, String blurb, Param... params) {
            this.id = id; this.name = name; this.blurb = blurb; this.params = params;
            for (int i = 0; i < params.length; i++) index.put(params[i].id, i);
        }
        public int at(String id) { Integer i = index.get(id); return i == null ? -1 : i; }
        Patch preset(String name, Object... kv) {
            Patch q = new Patch(this, name);
            for (int i = 0; i + 1 < kv.length; i += 2) q.set((String) kv[i], ((Number) kv[i + 1]).doubleValue());
            presets.add(q);
            return q;
        }
    }

    public static final Kind ANALOG = new Kind("analog", "Analog", "Two oscillators, a warm filter, envelopes and an LFO. Poly for chords, Mono for leads, Acid for squelch.",
        p("mode", "Mode", "Voice", CHOICE, 0, 2, 0, "", true, "Poly", "Mono", "Acid"),
        p("w1", "Wave", "Oscillator 1", LIN, 0, 3, 2, "", true),
        p("pw1", "Width", "Oscillator 1", LIN, .05, .95, .5, "", false),
        p("w2", "Wave", "Oscillator 2", LIN, 0, 3, 2, "", false),
        p("semi2", "Semis", "Oscillator 2", INT, -24, 24, 0, "", false),
        p("fine2", "Fine", "Oscillator 2", LIN, -50, 50, 7, "c", false),
        p("mix2", "Level", "Oscillator 2", LIN, 0, 1, .5, "%", false),
        p("sync", "Sync", "Oscillator 2", CHOICE, 0, 1, 0, "", false, "Off", "On"),
        p("sub", "Sub", "Mixer", LIN, 0, 1, 0, "%", false),
        p("noise", "Noise", "Mixer", LIN, 0, 1, 0, "%", false),
        p("uni", "Unison", "Mixer", INT, 1, 5, 1, "", false),
        p("spread", "Spread", "Mixer", LIN, 0, 1, .3, "%", false),
        p("ftype", "Filter", "Filter", CHOICE, 0, 3, 0, "", false, "Low 24", "Low 12", "Band", "High"),
        p("cut", "Cutoff", "Filter", LOG, 30, 18000, 2400, "Hz", true),
        p("res", "Resonance", "Filter", LIN, 0, 1, .2, "%", true),
        p("fenv", "Env amount", "Filter", LIN, -1, 1, .35, "", true),
        p("track", "Key track", "Filter", LIN, 0, 1, .5, "%", false),
        p("drive", "Drive", "Filter", LIN, 0, 1, .1, "%", true),
        p("fa", "Attack", "Filter envelope", LOG, .001, 5, .005, "s", false),
        p("fd", "Decay", "Filter envelope", LOG, .005, 5, .4, "s", false),
        p("fs", "Sustain", "Filter envelope", LIN, 0, 1, .2, "%", false),
        p("fr", "Release", "Filter envelope", LOG, .005, 8, .3, "s", false),
        p("aa", "Attack", "Amp envelope", LOG, .001, 5, .004, "s", true),
        p("ad", "Decay", "Amp envelope", LOG, .005, 5, .5, "s", false),
        p("as", "Sustain", "Amp envelope", LIN, 0, 1, .8, "%", false),
        p("ar", "Release", "Amp envelope", LOG, .005, 8, .25, "s", true),
        p("lrate", "Rate", "LFO", LOG, .05, 20, 4, "Hz", false),
        p("lshape", "Shape", "LFO", CHOICE, 0, 4, 0, "", false, "Sine", "Triangle", "Saw", "Square", "Random"),
        p("lpitch", "To pitch", "LFO", LIN, 0, 1, 0, "%", false),
        p("lcut", "To filter", "LFO", LIN, 0, 1, 0, "%", false),
        p("lamp", "To volume", "LFO", LIN, 0, 1, 0, "%", false),
        p("lpw", "To width", "LFO", LIN, 0, 1, 0, "%", false),
        p("glide", "Glide", "Voice", LOG, .001, 1.5, .001, "s", false),
        p("accent", "Accent", "Voice", LIN, 0, 1, .5, "%", false),
        p("oct", "Octave", "Voice", INT, -3, 2, 0, "", false),
        p("level", "Level", "Voice", LIN, 0, 1, .7, "%", false)
    );

    static {
        Kind k = ANALOG;
        k.preset("Warm pad", "w1", 2, "w2", 2, "fine2", 9, "mix2", .6, "uni", 3, "spread", .35, "cut", 1400, "res", .15, "fenv", .2, "fa", .8, "fd", 1.5, "fs", .5, "aa", .6, "ad", 1, "as", .9, "ar", 1.6, "lrate", .3, "lcut", .15);
        k.preset("Soft keys", "w1", 1.3, "w2", 2, "semi2", 12, "mix2", .25, "cut", 2200, "res", .1, "fenv", .3, "fd", .6, "fs", .1, "ad", 1.2, "as", .4, "ar", .4);
        k.preset("Brass", "w1", 2, "w2", 2, "fine2", 12, "mix2", .7, "cut", 900, "res", .25, "fenv", .55, "fa", .06, "fd", .5, "fs", .4, "aa", .03, "as", .85, "ar", .2, "drive", .3);
        k.preset("Chord stab", "w1", 3, "pw1", .35, "w2", 2, "fine2", 10, "cut", 1600, "res", .3, "fenv", .5, "fd", .18, "fs", 0, "ad", .3, "as", 0, "ar", .15);
        k.preset("Mono bass", "mode", 1, "w1", 2, "w2", 3, "semi2", -12, "mix2", .5, "sub", .5, "cut", 380, "res", .3, "fenv", .45, "fd", .25, "fs", .1, "as", .9, "ar", .08, "oct", -2, "glide", .05, "drive", .35);
        k.preset("Acid line", "mode", 2, "w1", 2, "mix2", 0, "cut", 420, "res", .78, "fenv", .7, "fd", .22, "fs", 0, "aa", .002, "ad", .4, "as", .7, "ar", .05, "oct", -1, "glide", .07, "accent", .7, "drive", .45);
        k.preset("Square acid", "mode", 2, "w1", 3, "mix2", 0, "cut", 350, "res", .72, "fenv", .65, "fd", .3, "fs", 0, "ar", .05, "oct", -1, "glide", .08, "accent", .6, "drive", .55);
        k.preset("Sync lead", "mode", 1, "w1", 2, "w2", 2, "semi2", 7, "sync", 1, "mix2", .8, "cut", 4000, "res", .2, "fenv", .3, "lrate", 5.5, "lpitch", .08, "glide", .08, "as", .9);
        k.preset("Wobble", "mode", 1, "w1", 2, "w2", 3, "semi2", -12, "sub", .4, "cut", 300, "res", .5, "fenv", .1, "lrate", 3, "lcut", .65, "oct", -2, "drive", .5);
        k.preset("Glass bell", "w1", 0, "w2", 1, "semi2", 19, "mix2", .45, "cut", 8000, "res", 0, "fenv", 0, "ad", 2.2, "as", 0, "ar", 2.2, "lrate", 6, "lamp", .08);
        k.preset("Noise sweep", "w1", 0, "mix2", 0, "noise", 1, "ftype", 2, "cut", 300, "res", .6, "fenv", .9, "fa", 2, "fd", 2, "fs", .3, "aa", .4, "ar", 1.5);
    }

    /** Every kind, in the order the Synth tab shows them. More arrive in later pieces. */
    public static final Kind[] KINDS = {ANALOG};

    public static Kind kind(String id) { for (Kind k : KINDS) if (k.id.equals(id)) return k; return ANALOG; }

    /* ================= patches ================= */

    /** A synth's settings: its kind, its name and one value per knob. */
    public static final class Patch {
        public final Kind kind;
        public String name;
        public final double[] v;
        public Patch(Kind kind, String name) {
            this.kind = kind; this.name = name;
            v = new double[kind.params.length];
            for (int i = 0; i < v.length; i++) v[i] = kind.params[i].def;
        }
        public double get(String id) { int i = kind.at(id); return i < 0 ? 0 : v[i]; }
        public void set(String id, double x) { int i = kind.at(id); if (i >= 0) v[i] = kind.params[i].clamp(x); }
        public Patch copy() { Patch q = new Patch(kind, name); System.arraycopy(v, 0, q.v, 0, v.length); return q; }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", kind.id);
            m.put("name", name);
            Map<String, Object> vals = new LinkedHashMap<>();
            for (int i = 0; i < v.length; i++) vals.put(kind.params[i].id, v[i]);
            m.put("v", vals);
            return m;
        }

        public static Patch fromMap(Map<String, Object> m) {
            Kind k = kind(Json.str(m.get("kind"), "analog"));
            Patch q = new Patch(k, Json.str(m.get("name"), k.name));
            Map<String, Object> vals = Json.map(m.get("v"));
            for (Map.Entry<String, Object> e : vals.entrySet()) if (e.getValue() instanceof Number) q.set(e.getKey(), ((Number) e.getValue()).doubleValue());
            return q;
        }
    }

    /* ================= shared pieces ================= */

    private static final float[] SINE = new float[2049];
    static { for (int i = 0; i <= 2048; i++) SINE[i] = (float) Math.sin(2 * Math.PI * i / 2048); }

    /** sin(2 pi ph) for ph in [0, 1), from a table. */
    static float sin1(double ph) {
        double x = (ph - Math.floor(ph)) * 2048;
        int i = (int) x;
        float f = (float) (x - i);
        return SINE[i] + (SINE[i + 1] - SINE[i]) * f;
    }

    /** The PolyBLEP step correction for a phase t in [0, 1) with increment dt. */
    static double blep(double t, double dt) {
        if (t < dt) { t /= dt; return t + t - t * t - 1; }
        if (t > 1 - dt) { t = (t - 1) / dt; return t * t + t + t + 1; }
        return 0;
    }

    /** A soft clip that costs a few multiplies instead of tanh. */
    static double sat(double x) {
        if (x > 3) return 1;
        if (x < -3) return -1;
        double x2 = x * x;
        return x * (27 + x2) / (27 + 9 * x2);
    }

    /** One oscillator sample: wave 0 sine, 1 triangle, 2 saw, 3 square (pw), and the shapes between. */
    static double osc(double ph, double dt, double wave, double pw) {
        int a = (int) Math.floor(wave);
        if (a >= 3) return shape(3, ph, dt, pw);
        double f = wave - a;
        double x = shape(a, ph, dt, pw);
        return f < 1e-4 ? x : x + (shape(a + 1, ph, dt, pw) - x) * f;
    }

    private static double shape(int s, double ph, double dt, double pw) {
        switch (s) {
            case 0: return sin1(ph);
            case 1: return ph < .5 ? 4 * ph - 1 : 3 - 4 * ph;
            case 2: return 2 * ph - 1 - blep(ph, dt);
            default: {
                double v = ph < pw ? 1 : -1;
                v += blep(ph, dt);
                double t2 = ph - pw; if (t2 < 0) t2 += 1;
                v -= blep(t2, dt);
                return v;
            }
        }
    }

    /** ADSR with exponential decay and release; levels 0..1. */
    static final class Env {
        int stage; // 0 idle, 1 attack, 2 decay/sustain, 3 release
        double v, aInc, dK, rK, s;
        void set(double a, double d, double sus, double r, int rate) {
            aInc = 1.0 / Math.max(1, a * rate);
            dK = Math.exp(-4.6 / Math.max(1, d * rate));   // about 99% of the way in d seconds
            rK = Math.exp(-4.6 / Math.max(1, r * rate));
            s = sus;
        }
        void on(boolean fromZero) { if (fromZero) v = 0; stage = 1; }
        void off() { if (stage != 0) stage = 3; }
        double next() {
            switch (stage) {
                case 1: v += aInc; if (v >= 1) { v = 1; stage = 2; } break;
                case 2: v = s + (v - s) * dK; break;
                case 3: v *= rK; if (v < 1e-5) { v = 0; stage = 0; } break;
                default: v = 0;
            }
            return v;
        }
        /** Advance n samples at once (control rate), for the filter envelope. */
        double skip(int n) {
            switch (stage) {
                case 1: v += aInc * n; if (v >= 1) { v = 1; stage = 2; } break;
                case 2: v = s + (v - s) * Math.pow(dK, n); break;
                case 3: v *= Math.pow(rK, n); if (v < 1e-5) { v = 0; stage = 0; } break;
                default: v = 0;
            }
            return v;
        }
    }

    /* ================= a voice ================= */

    /** Everything one playing note needs. One per engine voice, made once, reused. */
    public static final class Voice {
        Patch patch;
        int rate;
        public boolean on, gate;
        double hz, hzTarget, glideK = 1;
        float vel;
        boolean fresh;
        // oscillators: unison copies of osc 1 and osc 2, and the sub
        final double[] ph1 = new double[5], ph2 = new double[5];
        double phSub;
        int rng = 0x9E3779B9;
        // filter (ladder stages or state-variable states)
        double s1, s2, s3, s4, ic1, ic2;
        double g, k, G;
        final Env amp = new Env(), fe = new Env();
        double lph, lsh;
        int ctl;
        // knob values read once per block
        int mode, uni, ftype, lshape;
        double w1, pw1, w2, semi2r, mix2, sub, noise, spread, cut, res, fenv, track, drive, lrate, lpitch, lcut, lamp, lpw, glide, accent, oct, level;
        boolean sync;
        double fNow = 1000, lfoV, lfoPitchMul = 1;
        final double[] det = new double[5];

        /** Start a note (or slide an already sounding mono note there when slide is true). */
        public void noteOn(Patch p, double hz, float vel, int rate, boolean slide) {
            this.rate = rate;
            this.patch = p;
            read(p);
            hzTarget = hz;
            if (!slide || !on) {
                this.hz = hz;
                for (int i = 0; i < 5; i++) { ph1[i] = (i * .2137) % 1; ph2[i] = (i * .3971 + .5) % 1; }
                phSub = 0; s1 = s2 = s3 = s4 = ic1 = ic2 = 0;
                lph = 0;
                amp.on(true); fe.on(true);
                fresh = true;
            } else {
                // a slide: no new envelopes, the pitch glides there
                fresh = false;
            }
            // slides glide there: acid always takes a moment, mono as long as Glide says
            double gt = mode == 2 ? Math.max(glide, .04) : glide;
            glideK = slide && gt > .002 ? Math.exp(-4.6 / Math.max(1, gt * rate)) : 0;
            this.vel = vel;
            on = true; gate = true; ctl = 0;
        }

        public void noteOff() { gate = false; amp.off(); fe.off(); }

        public boolean done() { return on && !gate && amp.stage == 0; }

        public float velocity() { return vel; }

        /** Seconds the release lasts at most, so the engine knows when the note is over. */
        public double releaseSec() { return Math.min(8, patch == null ? .3 : patch.get("ar")) * 1.3 + .02; }

        /** Read the knobs; called at the note's start and once per block, so knob turns reach held notes. */
        public void read(Patch p) {
            patch = p;
            double[] v = p.v;
            Kind K = p.kind;
            mode = (int) v[K.at("mode")];
            w1 = v[K.at("w1")]; pw1 = v[K.at("pw1")]; w2 = v[K.at("w2")];
            semi2r = Math.pow(2, (v[K.at("semi2")] + v[K.at("fine2")] / 100) / 12);
            mix2 = v[K.at("mix2")]; sync = v[K.at("sync")] > .5;
            sub = v[K.at("sub")]; noise = v[K.at("noise")];
            uni = (int) v[K.at("uni")]; spread = v[K.at("spread")];
            ftype = (int) v[K.at("ftype")]; cut = v[K.at("cut")]; res = v[K.at("res")]; fenv = v[K.at("fenv")];
            track = v[K.at("track")]; drive = v[K.at("drive")];
            lrate = v[K.at("lrate")]; lshape = (int) v[K.at("lshape")];
            lpitch = v[K.at("lpitch")]; lcut = v[K.at("lcut")]; lamp = v[K.at("lamp")]; lpw = v[K.at("lpw")];
            glide = v[K.at("glide")]; accent = v[K.at("accent")]; oct = Math.pow(2, v[K.at("oct")]); level = v[K.at("level")];
            if (rate > 0) {
                fe.set(v[K.at("fa")], v[K.at("fd")], v[K.at("fs")], v[K.at("fr")], rate);
                // acid: the amp holds while the gate is open, the filter does the shaping
                amp.set(v[K.at("aa")], v[K.at("ad")], v[K.at("as")], v[K.at("ar")], rate);
            }
        }

        /** One sample. pitchMul carries bends, glides and wobble from the engine. */
        public float next(double pitchMul) {
            // control rate, every 16 samples: LFO, filter envelope and cutoff
            if (ctl-- <= 0) {
                ctl = 15;
                double dl = lrate * 16 / rate;
                double prev = lph;
                lph += dl; if (lph >= 1) lph -= 1;
                switch (lshape) {
                    case 1: lfoV = lph < .5 ? 4 * lph - 1 : 3 - 4 * lph; break;
                    case 2: lfoV = 1 - 2 * lph; break;
                    case 3: lfoV = lph < .5 ? 1 : -1; break;
                    case 4: if (lph < prev) { rng ^= rng << 13; rng ^= rng >>> 17; rng ^= rng << 5; lsh = (rng & 0xFFFF) / 32767.5 - 1; } lfoV = lsh; break;
                    default: lfoV = sin1(lph);
                }
                lfoPitchMul = lpitch > 0 ? Math.pow(2, lpitch * lfoV) : 1;
                int nu = Math.max(1, uni);
                for (int u = 0; u < nu; u++) det[u] = nu == 1 ? 1 : Math.pow(2, spread * (u / (double) (nu - 1) - .5) / 12);
                double fv = fe.skip(16);
                double acc = mode == 2 && fresh ? 1 + accent * .8 : 1;
                double oct4 = fenv * fv * 6 * acc + lcut * lfoV * 3;
                double base = cut * Math.pow(hz * oct / 261.63, track);
                fNow = Math.max(20, Math.min(rate * .45, base * Math.pow(2, oct4)));
                g = Math.tan(Math.PI * fNow / rate);
                if (ftype == 0 || mode == 2) {
                    k = mode == 2 ? res * 3.6 : res * 4.0;  // ladder feedback; acid a touch below self-oscillation
                    G = g / (1 + g);
                } else {
                    k = 2 - 2 * Math.min(.98, res);      // SVF damping
                }
            }
            hz = glideK > 0 ? hzTarget + (hz - hzTarget) * glideK : hzTarget;
            double f = hz * oct * pitchMul * lfoPitchMul;
            double dt1 = f / rate, dt2 = dt1 * semi2r;
            double pw = Math.max(.05, Math.min(.95, pw1 + lpw * lfoV * .4));
            // oscillators, with unison spread around the note
            double o = 0;
            int n = Math.max(1, uni);
            for (int u = 0; u < n; u++) {
                double d1 = dt1 * det[u], d2 = dt2 * det[u];
                double a = osc(ph1[u], d1, w1, pw);
                double b = mix2 > 0 ? osc(ph2[u], d2, w2, pw) : 0;
                o += a * (1 - mix2 * .5) + b * mix2;
                ph1[u] += d1;
                if (ph1[u] >= 1) { ph1[u] -= 1; if (sync) ph2[u] = ph1[u] * (d2 / d1); }
                ph2[u] += d2; if (ph2[u] >= 1) ph2[u] -= 1;
            }
            o /= Math.sqrt(n);
            if (sub > 0) {
                double ds = dt1 * .5;
                double sq = phSub < .5 ? 1 : -1;
                sq += blep(phSub, ds);
                double t2 = phSub - .5; if (t2 < 0) t2 += 1;
                sq -= blep(t2, ds);
                o += sq * sub * .7;
                phSub += ds; if (phSub >= 1) phSub -= 1;
            }
            if (noise > 0) { rng ^= rng << 13; rng ^= rng >>> 17; rng ^= rng << 5; o += noise * ((rng & 0xFFFF) / 32767.5 - 1); }
            // filter
            double x = o * (1 + drive * 3);
            double y;
            if (ftype == 0 || mode == 2) {
                // zero-delay ladder (TPT one-poles), the feedback solved each sample; saturation in the loop
                // what the four stages' states add to the output when the input is 0: y4 = G^4 u + S
                double gg = G, S = (gg * gg * gg * s1 + gg * gg * s2 + gg * s3 + s4) * (1 - gg);
                double u = (x - k * S) / (1 + k * gg * gg * gg * gg);
                u = sat(u);
                double v1 = (u - s1) * gg; double y1 = v1 + s1; s1 = y1 + v1;
                double v2 = (y1 - s2) * gg; double y2 = v2 + s2; s2 = y2 + v2;
                double v3 = (y2 - s3) * gg; double y3 = v3 + s3; s3 = y3 + v3;
                double v4 = (y3 - s4) * gg; double y4 = v4 + s4; s4 = y4 + v4;
                y = mode == 2 ? y3 : y4;
                // keep the passband up as resonance rises, like the real thing loses less than the math does
                y *= 1 + k * .25;
            } else {
                // TPT state-variable filter: low 12, band, high
                double a1 = 1 / (1 + g * (g + k)), a2 = g * a1, a3 = g * a2;
                double v3 = x - ic2, v1 = a1 * ic1 + a2 * v3, v2 = ic2 + a2 * ic1 + a3 * v3;
                ic1 = 2 * v1 - ic1; ic2 = 2 * v2 - ic2;
                y = ftype == 1 ? v2 : ftype == 2 ? v1 * k : x - k * v1 - v2;
                y = sat(y);
            }
            if (drive > 0) y = sat(y * (1 + drive)) / (1 + drive * .3);
            double e = amp.next();
            if (lamp > 0) e *= 1 - lamp * .5 * (1 + lfoV);
            double acc = mode == 2 && fresh ? 1 + accent * .35 : 1;
            return (float) (y * e * level * vel * acc * .7);
        }
    }
}
