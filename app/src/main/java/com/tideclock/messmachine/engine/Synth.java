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

    /* ---------- FM (SE-13) ---------- */

    /** Operator frequency ratios: whole numbers for tones, 1.41 and 3.5 for bells and metal. */
    public static final double[] RATIOS = {.5, 1, 1.41, 1.5, 2, 2.5, 3, 3.5, 4, 5, 6, 7, 8, 9, 11, 14};
    private static final String[] RATIO_NAMES = {"0.5", "1", "1.41", "1.5", "2", "2.5", "3", "3.5", "4", "5", "6", "7", "8", "9", "11", "14"};
    static int ri(double r) { for (int i = 0; i < RATIOS.length; i++) if (Math.abs(RATIOS[i] - r) < .01) return i; return 1; }

    /** Who modulates whom: per algorithm, per operator, a bit for each operator feeding it; and which ones you hear. */
    static final int[][] MODS = {
        {0b0010, 0b0100, 0b1000, 0}, {0b0110, 0, 0b1000, 0}, {0b1110, 0, 0, 0}, {0b0010, 0, 0b1000, 0},
        {0b1000, 0b1000, 0b1000, 0}, {0b0010, 0b0100, 0, 0}, {0b0010, 0, 0, 0}, {0, 0, 0, 0},
    };
    static final int[] CARRIERS = {0b0001, 0b0001, 0b0001, 0b0101, 0b0111, 0b1001, 0b1101, 0b1111};

    private static Param[] fmParams() {
        java.util.List<Param> l = new java.util.ArrayList<>();
        l.add(p("mode", "Mode", "Voice", CHOICE, 0, 1, 0, "", true, "Poly", "Mono"));
        l.add(p("algo", "Algorithm", "Operators", CHOICE, 0, 7, 3, "", true, "Stack", "Branch", "Three into one", "Two stacks", "One drives three", "Stack + sine", "Pair + two sines", "Organ"));
        l.add(p("bright", "Brightness", "Operators", LIN, 0, 2, 1, "", true));
        l.add(p("fb", "Feedback", "Operators", LIN, 0, 1, 0, "%", true));
        l.add(p("det", "Detune", "Operators", LIN, 0, 20, 0, "c", false));
        double[] lv = {1, .5, 1, .3};
        for (int i = 1; i <= 4; i++) {
            String g = "Operator " + i;
            l.add(p("r" + i, "Ratio", g, CHOICE, 0, RATIOS.length - 1, 1, "", i == 2, RATIO_NAMES));
            l.add(p("l" + i, "Level", g, LIN, 0, 1, lv[i - 1], "%", false));
            l.add(p("a" + i, "Attack", g, LOG, .001, 5, .002, "s", i == 1));
            l.add(p("d" + i, "Decay", g, LOG, .005, 8, .8, "s", i == 2));
            l.add(p("s" + i, "Sustain", g, LIN, 0, 1, .6, "%", false));
            l.add(p("rl" + i, "Release", g, LOG, .005, 8, .3, "s", i == 1));
        }
        l.add(p("lrate", "Rate", "LFO", LOG, .05, 20, 5, "Hz", false));
        l.add(p("lpitch", "Vibrato", "LFO", LIN, 0, 1, 0, "%", false));
        l.add(p("lamp", "Tremolo", "LFO", LIN, 0, 1, 0, "%", false));
        l.add(p("glide", "Glide", "Voice", LOG, .001, 1.5, .001, "s", false));
        l.add(p("oct", "Octave", "Voice", INT, -3, 2, 0, "", false));
        l.add(p("level", "Level", "Voice", LIN, 0, 1, .7, "%", false));
        return l.toArray(new Param[0]);
    }

    public static final Kind FM = new Kind("fm", "FM", "Four sine operators that bend each other into bells, electric pianos, metal and growl. Brightness is how hard they bend.", fmParams());

    static {
        Kind k = FM;
        k.preset("E piano", "algo", 3, "r2", ri(1), "l2", .5, "d2", 1.5, "s2", .1, "r4", ri(14), "l4", .25, "d4", .3, "s4", 0, "d1", 2.5, "s1", .2, "rl1", .6, "l3", .5, "d3", 1.2, "s3", 0, "rl3", .4, "det", 3);
        k.preset("Bell", "algo", 3, "r2", ri(3.5), "l2", .45, "d2", 3, "s2", 0, "r4", ri(1.41), "l4", .4, "d4", 2, "s4", 0, "d1", 4, "s1", 0, "rl1", 2.5, "l3", .6, "d3", 3, "s3", 0, "rl3", 2);
        k.preset("FM bass", "mode", 1, "algo", 0, "r1", ri(.5), "r2", ri(1), "l2", .55, "d2", .35, "s2", .15, "l3", 0, "l4", 0, "fb", .2, "d1", 1, "s1", .7, "rl1", .1, "oct", -1, "glide", .03);
        k.preset("Brass", "algo", 1, "r2", ri(1), "l2", .6, "a2", .08, "d2", .6, "s2", .5, "l3", .3, "a3", .05, "l4", 0, "a1", .04, "s1", .9, "rl1", .2, "fb", .1, "lpitch", .03);
        k.preset("Marimba", "algo", 3, "r2", ri(4), "l2", .4, "d2", .08, "s2", 0, "r4", ri(9), "l4", .2, "d4", .05, "s4", 0, "d1", .6, "s1", 0, "rl1", .3, "l3", .5, "d3", .4, "s3", 0);
        k.preset("Organ", "algo", 7, "r1", ri(.5), "r2", ri(1), "r3", ri(2), "r4", ri(3), "l1", .8, "l2", .7, "l3", .5, "l4", .4, "s1", 1, "s2", 1, "s3", 1, "s4", 1, "rl1", .08, "rl2", .08, "rl3", .08, "rl4", .08, "lamp", .1, "lrate", 6);
        k.preset("Metal", "algo", 2, "r2", ri(1.41), "r3", ri(3.5), "r4", ri(7), "l2", .5, "l3", .4, "l4", .35, "d2", 2, "d3", 1.5, "d4", 1, "s2", 0, "s3", 0, "s4", 0, "d1", 3, "s1", 0, "rl1", 1.5);
        k.preset("Growl", "mode", 1, "algo", 0, "fb", .85, "r2", ri(1), "l2", .7, "r3", ri(2), "l3", .4, "l4", .3, "s1", .9, "s2", .8, "oct", -1);
        k.preset("Glass pad", "algo", 3, "a1", .8, "a3", .9, "r2", ri(2), "l2", .3, "r4", ri(5), "l4", .2, "s1", .8, "s2", .8, "s3", .8, "s4", .8, "d1", 2, "rl1", 1.5, "rl3", 1.5, "det", 6, "l3", .7);
    }

    /* ---------- Wavetable (SE-14) ---------- */

    public static final Kind WAVE = new Kind("wave", "Wavetable", "One oscillator that sweeps through a table of shapes. Position picks the shape; the envelope and LFO can move it while the note plays.",
        p("mode", "Mode", "Voice", CHOICE, 0, 1, 0, "", true, "Poly", "Mono"),
        p("table", "Table", "Oscillator", CHOICE, 0, 5, 0, "", true, "Basic", "Pulse", "Sweep", "Vowels", "Hollow", "Grit"),
        p("pos", "Position", "Oscillator", LIN, 0, 1, .3, "%", true),
        p("penv", "Env to position", "Oscillator", LIN, -1, 1, 0, "", true),
        p("plfo", "LFO to position", "Oscillator", LIN, 0, 1, 0, "%", false),
        p("warp", "Warp", "Oscillator", LIN, 0, 1, 0, "%", false),
        p("uni", "Unison", "Oscillator", INT, 1, 5, 1, "", false),
        p("spread", "Spread", "Oscillator", LIN, 0, 1, .3, "%", false),
        p("ftype", "Filter", "Filter", CHOICE, 0, 3, 0, "", false, "Low 24", "Low 12", "Band", "High"),
        p("cut", "Cutoff", "Filter", LOG, 30, 18000, 6000, "Hz", true),
        p("res", "Resonance", "Filter", LIN, 0, 1, .1, "%", false),
        p("fenv", "Env amount", "Filter", LIN, -1, 1, .2, "", false),
        p("track", "Key track", "Filter", LIN, 0, 1, .5, "%", false),
        p("drive", "Drive", "Filter", LIN, 0, 1, 0, "%", false),
        p("fa", "Attack", "Envelope 2 (filter, position)", LOG, .001, 5, .005, "s", false),
        p("fd", "Decay", "Envelope 2 (filter, position)", LOG, .005, 5, .6, "s", false),
        p("fs", "Sustain", "Envelope 2 (filter, position)", LIN, 0, 1, .3, "%", false),
        p("fr", "Release", "Envelope 2 (filter, position)", LOG, .005, 8, .4, "s", false),
        p("aa", "Attack", "Amp envelope", LOG, .001, 5, .005, "s", true),
        p("ad", "Decay", "Amp envelope", LOG, .005, 5, .6, "s", false),
        p("as", "Sustain", "Amp envelope", LIN, 0, 1, .8, "%", false),
        p("ar", "Release", "Amp envelope", LOG, .005, 8, .4, "s", true),
        p("lrate", "Rate", "LFO", LOG, .05, 20, 1, "Hz", false),
        p("lshape", "Shape", "LFO", CHOICE, 0, 4, 0, "", false, "Sine", "Triangle", "Saw", "Square", "Random"),
        p("lpitch", "To pitch", "LFO", LIN, 0, 1, 0, "%", false),
        p("lcut", "To filter", "LFO", LIN, 0, 1, 0, "%", false),
        p("lamp", "To volume", "LFO", LIN, 0, 1, 0, "%", false),
        p("glide", "Glide", "Voice", LOG, .001, 1.5, .001, "s", false),
        p("oct", "Octave", "Voice", INT, -3, 2, 0, "", false),
        p("level", "Level", "Voice", LIN, 0, 1, .7, "%", false)
    );

    static {
        Kind k = WAVE;
        k.preset("Vowel pad", "table", 3, "pos", .3, "plfo", .5, "lrate", .2, "aa", .5, "ar", 1.5, "uni", 3, "spread", .3, "cut", 5000);
        k.preset("Sweep lead", "mode", 1, "table", 2, "pos", .1, "penv", .8, "fd", .6, "fs", .2, "glide", .06, "cut", 9000, "lpitch", .04, "lrate", 5);
        k.preset("PWM strings", "table", 1, "pos", .3, "plfo", .6, "lrate", .5, "uni", 3, "aa", .4, "ar", 1.2, "cut", 3500, "res", .1);
        k.preset("Hollow keys", "table", 4, "pos", .6, "ad", .8, "as", .3, "ar", .5, "cut", 7000, "fenv", .3, "fd", .4, "fs", 0);
        k.preset("Grit bass", "mode", 1, "table", 5, "pos", .4, "oct", -2, "cut", 600, "fenv", .4, "fd", .3, "fs", .1, "drive", .4, "res", .25);
        k.preset("Morph pluck", "table", 0, "pos", 1, "penv", -.9, "fd", .25, "fs", 0, "ad", .5, "as", 0, "ar", .3, "cut", 8000);
        k.preset("Vowel talk", "mode", 1, "table", 3, "pos", 0, "plfo", 1, "lrate", 2.5, "lshape", 1, "oct", -1, "cut", 4000);
    }

    /* ---------- Granular (SE-15) ---------- */

    public static final Kind GRAIN = new Kind("grain", "Granular", "Plays a sound from your mess as a cloud of tiny grains, at any pitch, frozen or crawling through it. Position picks where; Spray scatters it.",
        p("mode", "Mode", "Voice", CHOICE, 0, 1, 0, "", true, "Poly", "Mono"),
        p("pos", "Position", "Grains", LIN, 0, 1, .3, "%", true),
        p("spray", "Spray", "Grains", LIN, 0, 1, .1, "%", true),
        p("size", "Size", "Grains", LOG, .01, .5, .08, "s", true),
        p("dens", "Density", "Grains", LOG, 2, 120, 30, "Hz", true),
        p("scan", "Scan", "Grains", LIN, -1, 1, 0, "", false),
        p("pspray", "Pitch spray", "Grains", LIN, 0, 12, 0, "", false),
        p("rev", "Reverse", "Grains", LIN, 0, 1, 0, "%", false),
        p("gshape", "Shape", "Grains", CHOICE, 0, 1, 0, "", false, "Smooth", "Sharp"),
        p("ftype", "Filter", "Filter", CHOICE, 0, 3, 1, "", false, "Low 24", "Low 12", "Band", "High"),
        p("cut", "Cutoff", "Filter", LOG, 30, 18000, 12000, "Hz", false),
        p("res", "Resonance", "Filter", LIN, 0, 1, 0, "%", false),
        p("aa", "Attack", "Amp envelope", LOG, .001, 5, .05, "s", true),
        p("ad", "Decay", "Amp envelope", LOG, .005, 5, .5, "s", false),
        p("as", "Sustain", "Amp envelope", LIN, 0, 1, 1, "%", false),
        p("ar", "Release", "Amp envelope", LOG, .005, 8, .8, "s", true),
        p("lrate", "Rate", "LFO", LOG, .05, 20, .3, "Hz", false),
        p("lpos", "To position", "LFO", LIN, 0, 1, 0, "%", false),
        p("lpitch", "To pitch", "LFO", LIN, 0, 1, 0, "%", false),
        p("glide", "Glide", "Voice", LOG, .001, 1.5, .001, "s", false),
        p("oct", "Octave", "Voice", INT, -3, 2, 0, "", false),
        p("level", "Level", "Voice", LIN, 0, 1, .8, "%", false)
    );

    static {
        Kind k = GRAIN;
        k.preset("Cloud", "pos", .3, "spray", .15, "size", .12, "dens", 40, "aa", .4, "ar", 1.5);
        k.preset("Freeze", "pos", .5, "spray", .02, "size", .2, "dens", 25, "aa", .2, "ar", 1.2);
        k.preset("Crawl", "pos", 0, "scan", .25, "spray", .05, "size", .1, "dens", 30, "aa", .1, "ar", 1);
        k.preset("Shimmer", "pos", .4, "spray", .2, "size", .06, "dens", 70, "pspray", 12, "aa", .5, "ar", 2, "cut", 9000);
        k.preset("Stutter", "pos", .2, "spray", 0, "size", .03, "dens", 12, "gshape", 1, "aa", .002, "ar", .2);
        k.preset("Backwards wash", "pos", .6, "spray", .3, "size", .25, "dens", 20, "rev", 1, "aa", .8, "ar", 2.5);
        k.preset("Glitch", "mode", 1, "pos", .5, "spray", .6, "size", .02, "dens", 90, "pspray", 7, "rev", .5, "gshape", 1, "aa", .002, "ar", .1);
    }

    /* ---------- Drums (SE-16) ---------- */

    public static final Kind DRUM = new Kind("drum", "Drums", "Drum sounds made from scratch: pick a model, then Tune, Decay, Tone and Snap. Played as keys they go up and down in pitch.",
        p("model", "Model", "Drum", CHOICE, 0, 7, 0, "", true, "Kick", "Snare", "Hat", "Open hat", "Clap", "Tom", "Cowbell", "Zap"),
        p("tune", "Tune", "Drum", INT, -24, 24, 0, "", true),
        p("decay", "Decay", "Drum", LOG, .02, 3, .5, "s", true),
        p("tone", "Tone", "Drum", LIN, 0, 1, .5, "%", true),
        p("snap", "Snap", "Drum", LIN, 0, 1, .4, "%", true),
        p("sweep", "Sweep", "Drum", LIN, 0, 1, .5, "%", false),
        p("stime", "Sweep time", "Drum", LOG, .005, .5, .04, "s", false),
        p("noise", "Noise", "Drum", LIN, 0, 1, .5, "%", false),
        p("drive", "Drive", "Drum", LIN, 0, 1, .2, "%", false),
        p("level", "Level", "Drum", LIN, 0, 1, .8, "%", false)
    );

    static {
        Kind k = DRUM;
        k.preset("Deep kick", "model", 0, "decay", .6, "sweep", .55, "stime", .045, "snap", .35, "drive", .25);
        k.preset("Punch kick", "model", 0, "tune", 3, "decay", .3, "sweep", .7, "stime", .025, "snap", .7, "drive", .5);
        k.preset("Boom 808", "model", 0, "tune", -3, "decay", 1.8, "sweep", .35, "stime", .06, "snap", .15, "drive", .35);
        k.preset("Snare", "model", 1, "decay", .25, "tone", .55, "snap", .6, "noise", .6);
        k.preset("Rim snare", "model", 1, "tune", 7, "decay", .12, "tone", .8, "snap", .9, "noise", .3);
        k.preset("Closed hat", "model", 2, "decay", .06, "tone", .7);
        k.preset("Open hat", "model", 3, "decay", .5, "tone", .65);
        k.preset("Clap", "model", 4, "decay", .3, "tone", .5);
        k.preset("Tom", "model", 5, "decay", .45, "sweep", .3, "stime", .08, "noise", .2);
        k.preset("Cowbell", "model", 6, "decay", .3, "tone", .6);
        k.preset("Zap", "model", 7, "decay", .25, "sweep", .9, "stime", .03);
    }

    /* ---------- Plucks (SE-16) ---------- */

    public static final Kind PLUCK = new Kind("pluck", "Pluck", "A plucked string: a burst of noise ringing through a tuned delay. Bright is the pick, Damp the string, Body the wood around it.",
        p("mode", "Mode", "Voice", CHOICE, 0, 1, 0, "", true, "Poly", "Mono"),
        p("decay", "Decay", "String", LOG, .1, 12, 2.5, "s", true),
        p("bright", "Bright", "String", LIN, 0, 1, .7, "%", true),
        p("damp", "Damp", "String", LIN, 0, 1, .3, "%", true),
        p("pick", "Pick position", "String", LIN, 0, 1, .2, "%", false),
        p("body", "Body", "String", LIN, 0, 1, .3, "%", true),
        p("mute", "Let go", "String", LOG, .01, 3, .6, "s", false),
        p("drive", "Drive", "String", LIN, 0, 1, 0, "%", false),
        p("lrate", "Rate", "LFO", LOG, .05, 20, 5, "Hz", false),
        p("lpitch", "Vibrato", "LFO", LIN, 0, 1, 0, "%", false),
        p("glide", "Glide", "Voice", LOG, .001, 1.5, .001, "s", false),
        p("oct", "Octave", "Voice", INT, -3, 2, 0, "", false),
        p("level", "Level", "Voice", LIN, 0, 1, .8, "%", false)
    );

    static {
        Kind k = PLUCK;
        k.preset("Nylon", "decay", 2.5, "bright", .55, "damp", .45, "pick", .25, "body", .45);
        k.preset("Steel", "decay", 4, "bright", .85, "damp", .15, "pick", .12, "body", .3);
        k.preset("Harp", "decay", 5, "bright", .5, "damp", .35, "pick", .5, "body", .2, "mute", 2);
        k.preset("Koto", "decay", 2, "bright", .9, "damp", .2, "pick", .08, "body", .1, "lpitch", .02);
        k.preset("Pluck bass", "mode", 1, "decay", 1.2, "bright", .45, "damp", .6, "body", .5, "oct", -2, "mute", .15);
        k.preset("Muted", "decay", .35, "bright", .6, "damp", .7, "pick", .3, "body", .3, "mute", .05);
        k.preset("Bell string", "decay", 8, "bright", 1, "damp", 0, "pick", .4, "body", 0);
    }

    /** Every kind, in the order the Synth tab shows them. */
    public static final Kind[] KINDS = {ANALOG, FM, WAVE, GRAIN, DRUM, PLUCK};

    /** How long a note with no length rings: its attack and decay (FM: the slowest carrier's). */
    public static double ringSec(Patch p) {
        if (p.kind == FM) {
            double m = 0; int cm = CARRIERS[(int) p.get("algo")];
            for (int i = 0; i < 4; i++) if ((cm >> i & 1) != 0) m = Math.max(m, p.v[p.kind.at(FM_A[i])] + p.v[p.kind.at(FM_D[i])]);
            return Math.min(4, m);
        }
        if (p.kind == DRUM) return Math.min(6, p.get("decay") * ((int) p.get("model") == 3 ? 2 : 1.2));
        if (p.kind == PLUCK) return Math.min(12, p.get("decay"));
        return Math.min(2, p.get("aa") + p.get("ad"));
    }

    static final String[] FM_R = {"r1", "r2", "r3", "r4"}, FM_L = {"l1", "l2", "l3", "l4"}, FM_A = {"a1", "a2", "a3", "a4"},
        FM_D = {"d1", "d2", "d3", "d4"}, FM_S = {"s1", "s2", "s3", "s4"}, FM_RL = {"rl1", "rl2", "rl3", "rl4"};

    /**
     * The wavetables, made once on first use (about 2 MB): 6 tables of 16 shapes, each at 6 levels of detail so
     * high notes never fold harmonics back down (the right level is picked by pitch).
     */
    static final class WT {
        static final int N = 1024, FR = 16, MIPS = 6;
        static final int[] HARM = {256, 128, 64, 32, 16, 8};
        static final float[][][][] T = new float[6][MIPS][FR][];
        static {
            double[] a = new double[257];
            double[] re = new double[N], im = new double[N];
            java.util.Random rnd = new java.util.Random(505);
            double[][] grit = new double[FR][257];
            for (int f = 0; f < FR; f++) for (int h = 1; h <= 256; h++) grit[f][h] = rnd.nextDouble();
            for (int t = 0; t < 6; t++) for (int f = 0; f < FR; f++) {
                double x = f / (double) (FR - 1);
                java.util.Arrays.fill(a, 0);
                for (int h = 1; h <= 256; h++) a[h] = spectrum(t, x, h, grit[f][h]);
                double norm = 0;
                for (int m = 0; m < MIPS; m++) {
                    java.util.Arrays.fill(re, 0); java.util.Arrays.fill(im, 0);
                    for (int h = 1; h <= HARM[m]; h++) {
                        // fade the top quarter of each level's harmonics so levels blend instead of stepping
                        double fade = h > HARM[m] * .75 ? (HARM[m] - h) / (HARM[m] * .25) : 1;
                        im[h] = -N * a[h] * fade / 2; im[N - h] = N * a[h] * fade / 2;
                    }
                    Bake.fft(re, im, true);
                    if (m == 0) { for (int i = 0; i < N; i++) norm = Math.max(norm, Math.abs(re[i])); norm = Math.max(norm, 1e-9); }
                    float[] w = new float[N + 1];
                    for (int i = 0; i < N; i++) w[i] = (float) (re[i] / norm);
                    w[N] = w[0];
                    T[t][m][f] = w;
                }
            }
        }

        /** Harmonic h's level in table t at position x (0..1). */
        private static double spectrum(int t, double x, int h, double r) {
            switch (t) {
                case 0: { // sine, triangle, saw, square
                    double s = x * 3; int i = Math.min(2, (int) s); double fr = s - i;
                    return basic(i, h) * (1 - fr) + basic(i + 1, h) * fr;
                }
                case 1: { double w = .5 - x * .47; return Math.abs(2 / (h * Math.PI) * Math.sin(Math.PI * h * w)); }
                case 2: { double n = Math.pow(2, x * 6); return h <= n ? 1.0 / h : (h < n + 1 ? (n + 1 - h) / h : 0); }
                case 3: { // vowels a, e, i, o, u by formants at harmonic numbers (for a voice near 110 Hz)
                    double[][] V = {{6.6, 9.9}, {4.8, 16.7}, {2.5, 20.8}, {5.2, 7.6}, {2.7, 7.9}};
                    double s = x * 4; int i = Math.min(3, (int) s); double fr = s - i;
                    double f1 = V[i][0] + (V[i + 1][0] - V[i][0]) * fr, f2 = V[i][1] + (V[i + 1][1] - V[i][1]) * fr;
                    double g = Math.exp(-Math.pow((h - f1) / 1.5, 2)) + .7 * Math.exp(-Math.pow((h - f2) / 2.5, 2)) + .04;
                    return g / Math.pow(h, .7);
                }
                case 4: return h == 1 ? 1 : (h % 2 == 1 ? 1 - x : x) / h;
                default: return h == 1 ? 1 : Math.pow(r, 3 - 2 * x) / Math.pow(h, 1.4 - x * .7);
            }
        }

        private static double basic(int s, int h) {
            switch (s) {
                case 0: return h == 1 ? 1 : 0;
                case 1: return h % 2 == 1 ? 8 / (Math.PI * Math.PI * h * h) : 0;
                case 2: return 2 / (Math.PI * h);
                default: return h % 2 == 1 ? 4 / (Math.PI * h) : 0;
            }
        }
    }

    /** Make the wavetables now (off the audio thread), so the first wavetable note does not wait for them. */
    public static void warm() { if (WT.T[0][0][0] == null) throw new IllegalStateException(); }

    public static Kind kind(String id) { for (Kind k : KINDS) if (k.id.equals(id)) return k; return ANALOG; }

    /* ================= patches ================= */

    /** A synth's settings: its kind, its name and one value per knob. */
    public static final class Patch {
        public final Kind kind;
        public String name;
        public final double[] v;
        /** Granular (SE-15): the sound its grains come from, a library key; empty for other kinds. */
        public String src = "";
        public Patch(Kind kind, String name) {
            this.kind = kind; this.name = name;
            v = new double[kind.params.length];
            for (int i = 0; i < v.length; i++) v[i] = kind.params[i].def;
        }
        public double get(String id) { int i = kind.at(id); return i < 0 ? 0 : v[i]; }
        public void set(String id, double x) { int i = kind.at(id); if (i >= 0) v[i] = kind.params[i].clamp(x); }
        public Patch copy() { Patch q = new Patch(kind, name); System.arraycopy(v, 0, q.v, 0, v.length); q.src = src; return q; }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", kind.id);
            m.put("name", name);
            Map<String, Object> vals = new LinkedHashMap<>();
            for (int i = 0; i < v.length; i++) vals.put(kind.params[i].id, v[i]);
            m.put("v", vals);
            if (!src.isEmpty()) m.put("src", src);
            return m;
        }

        public static Patch fromMap(Map<String, Object> m) {
            Kind k = kind(Json.str(m.get("kind"), "analog"));
            Patch q = new Patch(k, Json.str(m.get("name"), k.name));
            Map<String, Object> vals = Json.map(m.get("v"));
            for (Map.Entry<String, Object> e : vals.entrySet()) if (e.getValue() instanceof Number) q.set(e.getKey(), ((Number) e.getValue()).doubleValue());
            q.src = Json.str(m.get("src"), "");
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
        /** 0 analog, 1 FM, 2 wavetable. */
        int kindNo;
        // FM: four operators
        final double[] fph = new double[4], fr = new double[4], fl = new double[4], fdet = new double[4], mo = new double[4], co = new double[4];
        final Env[] fe4 = {new Env(), new Env(), new Env(), new Env()};
        int algo;
        double fbAmt, bright, fb1, fb2;
        // wavetable
        int table;
        double pos, penv, plfo, warp, wposNow;
        // granular: up to 24 grains at once, each a read head with its own speed, age and length
        static final int NG = 24;
        float[] src;
        int srcRate = 44100;
        final double[] gPos = new double[NG], gInc = new double[NG];
        final int[] gAge = new int[NG], gLen = new int[NG];
        final boolean[] gOn = new boolean[NG];
        double spray, gsize, dens, scan, pspray, grev, lpos, head;
        int gshape;
        long nextGrain;

        // drums
        int model;
        double dtune, ddecay, dtone, dsnap, dsweep, dstime, dnoise, dt0;
        long dT;
        double dph1, dph2;
        final double[] mph = new double[6];
        // plucks: the string is a delay line
        final float[] ks = new float[4096];
        int ksW;
        double ksLp, kdecay, kbright, kdamp, kpick, kbody, kmute, relEnv = 1, ksAvg;
        long ksT;

        /** The sound a granular voice reads (null for silence). Set before noteOn. */
        public void source(Sample s) { src = s == null ? null : s.data; srcRate = s == null ? 44100 : s.rate; }

        /** Start a note (or slide an already sounding mono note there when slide is true). */
        public void noteOn(Patch p, double hz, float vel, int rate, boolean slide) {
            this.rate = rate;
            this.patch = p;
            read(p);
            hzTarget = hz;
            if (!slide || !on) {
                this.hz = hz;
                for (int i = 0; i < 4; i++) { fph[i] = 0; fe4[i].on(true); }
                fb1 = fb2 = 0;
                for (int i = 0; i < NG; i++) gOn[i] = false;
                head = 0; nextGrain = 0;
                dT = 0; dph1 = dph2 = 0; for (int i = 0; i < 6; i++) mph[i] = i * .17;
                if (kindNo == 5) pluckOn(hz * oct);
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

        public void noteOff() { gate = false; amp.off(); fe.off(); for (Env e : fe4) e.off(); }

        public boolean done() {
            if (on && kindNo == 4) return dT > ringSec(patch) * rate + rate / 20;
            if (on && kindNo == 5 && ksT > rate / 10 && ksAvg < 2e-5) return true;
            if (!on || gate) return false;
            if (kindNo == 5) return relEnv < 1e-4;
            if (kindNo == 1) {
                int cm = CARRIERS[algo];
                for (int i = 0; i < 4; i++) if ((cm >> i & 1) != 0 && fe4[i].stage != 0) return false;
                return true;
            }
            return amp.stage == 0;
        }

        public float velocity() { return vel; }

        /** Seconds the release lasts at most, so the engine knows when the note is over. */
        public double releaseSec() {
            if (patch == null) return .3;
            if (patch.kind == FM) {
                double m = 0; int cm = CARRIERS[(int) patch.get("algo")];
                for (int i = 0; i < 4; i++) if ((cm >> i & 1) != 0) m = Math.max(m, patch.v[patch.kind.at(FM_RL[i])]);
                return Math.min(8, m) * 1.3 + .02;
            }
            if (patch.kind == DRUM) return ringSec(patch) + .05;
            if (patch.kind == PLUCK) return Math.min(3, patch.get("mute")) * 1.5 + .02;
            return Math.min(8, patch.get("ar")) * 1.3 + .02;
        }

        private static double g(double[] v, Kind K, String id, double def) { int i = K.at(id); return i < 0 ? def : v[i]; }

        /** Read the knobs; called at the note's start and once per block, so knob turns reach held notes. */
        public void read(Patch p) {
            patch = p;
            double[] v = p.v;
            Kind K = p.kind;
            kindNo = K == FM ? 1 : K == WAVE ? 2 : K == GRAIN ? 3 : K == DRUM ? 4 : K == PLUCK ? 5 : 0;
            mode = (int) g(v, K, "mode", 0);
            w1 = g(v, K, "w1", 2); pw1 = g(v, K, "pw1", .5); w2 = g(v, K, "w2", 2);
            semi2r = Math.pow(2, (g(v, K, "semi2", 0) + g(v, K, "fine2", 0) / 100) / 12);
            mix2 = g(v, K, "mix2", 0); sync = g(v, K, "sync", 0) > .5;
            sub = g(v, K, "sub", 0); noise = g(v, K, "noise", 0);
            uni = (int) g(v, K, "uni", 1); spread = g(v, K, "spread", .3);
            ftype = (int) g(v, K, "ftype", 0); cut = g(v, K, "cut", 18000); res = g(v, K, "res", 0); fenv = g(v, K, "fenv", 0);
            track = g(v, K, "track", .5); drive = g(v, K, "drive", 0);
            lrate = g(v, K, "lrate", 1); lshape = (int) g(v, K, "lshape", 0);
            lpitch = g(v, K, "lpitch", 0); lcut = g(v, K, "lcut", 0); lamp = g(v, K, "lamp", 0); lpw = g(v, K, "lpw", 0);
            glide = g(v, K, "glide", .001); accent = g(v, K, "accent", .5); oct = Math.pow(2, g(v, K, "oct", 0)); level = g(v, K, "level", .7);
            if (kindNo == 1) {
                algo = (int) g(v, K, "algo", 0); fbAmt = g(v, K, "fb", 0); bright = g(v, K, "bright", 1);
                double dc = g(v, K, "det", 0);
                for (int i = 0; i < 4; i++) {
                    fr[i] = RATIOS[(int) v[K.at(FM_R[i])]];
                    fl[i] = v[K.at(FM_L[i])];
                    fdet[i] = i == 1 ? Math.pow(2, dc / 1200) : i == 3 ? Math.pow(2, -dc / 1200) : 1;
                    if (rate > 0) fe4[i].set(v[K.at(FM_A[i])], v[K.at(FM_D[i])], v[K.at(FM_S[i])], v[K.at(FM_RL[i])], rate);
                }
            } else if (kindNo == 2) {
                table = (int) g(v, K, "table", 0); pos = g(v, K, "pos", 0); penv = g(v, K, "penv", 0); plfo = g(v, K, "plfo", 0); warp = g(v, K, "warp", 0);
            } else if (kindNo == 4) {
                model = (int) g(v, K, "model", 0); dtune = Math.pow(2, g(v, K, "tune", 0) / 12); ddecay = g(v, K, "decay", .5); dtone = g(v, K, "tone", .5);
                dsnap = g(v, K, "snap", .4); dsweep = g(v, K, "sweep", .5); dstime = g(v, K, "stime", .04); dnoise = g(v, K, "noise", .5);
            } else if (kindNo == 5) {
                kdecay = g(v, K, "decay", 2.5); kbright = g(v, K, "bright", .7); kdamp = g(v, K, "damp", .3); kpick = g(v, K, "pick", .2);
                kbody = g(v, K, "body", .3); kmute = g(v, K, "mute", .6);
            } else if (kindNo == 3) {
                pos = g(v, K, "pos", 0); spray = g(v, K, "spray", 0); gsize = g(v, K, "size", .08); dens = g(v, K, "dens", 30);
                scan = g(v, K, "scan", 0); pspray = g(v, K, "pspray", 0); grev = g(v, K, "rev", 0); gshape = (int) g(v, K, "gshape", 0); lpos = g(v, K, "lpos", 0);
            }
            if (rate > 0) {
                fe.set(g(v, K, "fa", .005), g(v, K, "fd", .4), g(v, K, "fs", .2), g(v, K, "fr", .3), rate);
                amp.set(g(v, K, "aa", .004), g(v, K, "ad", .5), g(v, K, "as", .8), g(v, K, "ar", .25), rate);
            }
        }

        /** The six square oscillators of a hat, at the classic metallic ratios. */
        private static final double[] HAT = {1, 1.4471, 1.617, 1.9265, 2.5028, 2.6637};

        private double nz() { rng ^= rng << 13; rng ^= rng >>> 17; rng ^= rng << 5; return (rng & 0xFFFF) / 32767.5 - 1; }

        /** A one-shot state-variable filter step on the shared states: 0 low, 1 band, 2 high. */
        private double svf(double x, double fc, double q, int out) {
            double gg = Math.tan(Math.PI * Math.min(fc, rate * .45) / rate), kk = 1 / q;
            double a1 = 1 / (1 + gg * (gg + kk)), a2 = gg * a1, a3 = gg * a2;
            double v3 = x - ic2, v1 = a1 * ic1 + a2 * v3, v2 = ic2 + a2 * ic1 + a3 * v3;
            ic1 = 2 * v1 - ic1; ic2 = 2 * v2 - ic2;
            return out == 0 ? v2 : out == 1 ? v1 : x - kk * v1 - v2;
        }

        /** Drums: each model built from sines, squares and noise, with its own fall. */
        private float drumNext(double f) {
            double t = dT++ / (double) rate;
            double r = f / 261.6256 * dtune;  // the note moves the drum's pitch; pitch 0 is the drum's own
            double tau = Math.max(.005, ddecay) / 4.6;
            double env = Math.exp(-t / tau);
            double click = dsnap * Math.exp(-t / .0015) * nz();
            double o;
            switch (model) {
                case 0: case 5: case 7: { // kick, tom, zap: a sine swept down from above
                    double base = (model == 0 ? 50 : model == 5 ? 110 : 180) * r;
                    double depth = model == 7 ? dsweep * 48 : model == 5 ? dsweep * 18 : dsweep * 30;
                    double fNow = base * Math.pow(2, depth / 12 * Math.exp(-t / Math.max(.002, dstime)));
                    dph1 += fNow / rate; if (dph1 >= 1) dph1 -= 1;
                    o = sin1(dph1) * env + click * .6;
                    if (model == 5) o += dnoise * .25 * nz() * Math.exp(-t / (tau * .4));
                    break;
                }
                case 1: { // snare: two tones and filtered noise
                    double base = 180 * r;
                    dph1 += base / rate; if (dph1 >= 1) dph1 -= 1;
                    dph2 += base * 1.83 / rate; if (dph2 >= 1) dph2 -= 1;
                    double body = (sin1(dph1) + .5 * sin1(dph2)) * Math.exp(-t / (tau * .45));
                    double nzv = svf(nz(), 1500 + dtone * 6000, .7, 2) * env;
                    o = body * (1 - dnoise * .5) + nzv * (.4 + dnoise * .9) + click * .5;
                    break;
                }
                case 2: case 3: { // hats: six metallic squares through a high filter
                    double s = 0;
                    for (int i = 0; i < 6; i++) { mph[i] += 400 * r * HAT[i] / rate; if (mph[i] >= 1) mph[i] -= 1; s += mph[i] < .5 ? 1 : -1; }
                    s = s / 6 + nz() * .3;
                    o = svf(s, 5000 + dtone * 6000, .8, 2) * env * 1.6 + click * .2;
                    break;
                }
                case 4: { // clap: three quick bursts of band noise, then the tail
                    double burst = 0;
                    for (int i = 0; i < 3; i++) { double tt = t - i * .011; if (tt >= 0) burst = Math.max(burst, Math.exp(-tt / .006)); }
                    double tail = t > .022 ? Math.exp(-(t - .022) / tau) : 0;
                    o = svf(nz(), (900 + dtone * 1800) * r, 1.6, 1) * Math.max(burst, tail * .8) * 2.2;
                    break;
                }
                default: { // cowbell: two squares through a band filter
                    dph1 += 540 * r / rate; if (dph1 >= 1) dph1 -= 1;
                    dph2 += 800 * r / rate; if (dph2 >= 1) dph2 -= 1;
                    double s = ((dph1 < .5 ? 1 : -1) + (dph2 < .5 ? 1 : -1)) * .5;
                    double fall = Math.exp(-t / .01) * .5 + .5;
                    o = svf(s, 2000 + dtone * 2000, 2, 1) * env * fall * 1.5;
                }
            }
            if (drive > 0) o = sat(o * (1 + drive * 4)) / (1 + drive);
            return (float) (o * level * vel * .9);
        }

        /** Plucks: fill the string (a delay one period long) with a shaped burst. */
        private void pluckOn(double f0) {
            int L = (int) Math.max(2, Math.min(ks.length - 2, rate / Math.max(12, f0)));
            java.util.Arrays.fill(ks, 0f);
            double lp = 0, a = .05 + kbright * .95;
            int pk = (int) Math.round(kpick * .5 * L);
            for (int i = 0; i < L; i++) { lp += (nz() - lp) * a; ks[i] = (float) lp; }
            if (pk > 0) for (int i = L - 1; i >= pk; i--) ks[i] -= ks[i - pk];   // a pick away from the end loses the notes that have a node there
            // take out the burst's average and bring it to a steady level, so every pluck starts as loud
            double mean = 0, peak = 1e-9;
            for (int i = 0; i < L; i++) mean += ks[i];
            mean /= L;
            for (int i = 0; i < L; i++) { ks[i] -= (float) mean; peak = Math.max(peak, Math.abs(ks[i])); }
            for (int i = 0; i < L; i++) ks[i] = (float) (ks[i] * .6 / peak);
            ksW = L; ksLp = 0; ksT = 0; ksAvg = .1; relEnv = 1; ic1 = ic2 = 0;
        }

        private float pluckNext(double f) {
            // the damping filter in the loop delays the string a little; take that off so high notes stay in tune
            double d0 = .02 + kdamp * .9, c0 = 1 - d0 * .5;
            double L = Math.max(2, Math.min(ks.length - 4, rate / Math.max(12, f) - (1 - c0) / c0 * c0));
            int M = ks.length;
            double rp = ksW - L;
            if (rp < 0) rp += M;
            int i = (int) rp; double fr = rp - i;
            float a = ks[i % M], b = ks[(i + 1) % M];
            double y = a + (b - a) * fr;
            // loss per trip round the string, so the whole string dies away in Decay seconds
            double gfb = Math.exp(-4.6 * L / (Math.max(.05, kdecay) * rate));
            double d = .02 + kdamp * .9;
            ksLp += (y - ksLp) * (1 - d * .5);
            double w = (ksLp * (1 - d * .5) + y * d * .5) * gfb;
            ks[ksW] = (float) w;
            if (++ksW >= M) ksW = 0;
            ksT++;
            ksAvg += (Math.abs(y) - ksAvg) * .001;
            if (!gate) relEnv *= Math.exp(-4.6 / Math.max(1, kmute * rate));
            double o = y;
            if (kbody > 0) o += svf(y, 220, 1.5, 1) * kbody * 1.5;
            if (drive > 0) o = sat(o * (1 + drive * 4)) / (1 + drive);
            return (float) (o * relEnv * level * vel);
        }

        private double rnd01() { rng ^= rng << 13; rng ^= rng >>> 17; rng ^= rng << 5; return (rng & 0xFFFFFF) / (double) 0x1000000; }

        /**
         * Granular: a new grain every 1/density seconds (a little early or late, so it never buzzes), each reading the
         * sound around the position at the note's pitch (the sound counts as middle C), faded in and out.
         */
        private float grainNext(double f) {
            float[] d = src;
            if (d == null || d.length < 64) { amp.next(); return 0; }
            int N = d.length;
            if (--nextGrain <= 0) {
                nextGrain = Math.max(1, Math.round(rate / dens * (.7 + .6 * rnd01())));
                for (int i = 0; i < NG; i++) if (!gOn[i]) {
                    double at = pos + head / N + (rnd01() - .5) * spray * .5 + (lpos > 0 ? lpos * lfoV * .25 : 0);
                    at -= Math.floor(at);
                    double ratio = f / 261.6256 * srcRate / rate;
                    if (pspray > 0) ratio *= Math.pow(2, Math.round((rnd01() * 2 - 1) * pspray) / 12.0);
                    boolean back = grev > 0 && rnd01() < grev;
                    gPos[i] = at * N; gInc[i] = back ? -ratio : ratio;
                    gLen[i] = (int) Math.max(32, gsize * rate); gAge[i] = 0; gOn[i] = true;
                    break;
                }
            }
            head += scan * srcRate / rate;
            if (head > N || head < -N) head -= Math.floor(head / N) * N;
            double o = 0; int live = 0;
            for (int i = 0; i < NG; i++) {
                if (!gOn[i]) continue;
                double x = gAge[i] / (double) gLen[i];
                double w;
                if (gshape == 1) w = x < .1 ? x * 10 : x > .9 ? (1 - x) * 10 : 1;
                else { double s = sin1(x * .5); w = s * s; }
                double p = gPos[i];
                p -= Math.floor(p / N) * N;
                int j = (int) p; double fr2 = p - j; int j1 = j + 1 >= N ? 0 : j + 1;
                o += (d[j] + (d[j1] - d[j]) * fr2) * w;
                gPos[i] += gInc[i];
                if (++gAge[i] >= gLen[i]) gOn[i] = false;
                live++;
            }
            // overlapping grains add up; keep the cloud near one sound's level
            o /= Math.sqrt(Math.max(1, dens * gsize * .5));
            // the shared filter (no envelope on it here)
            double y;
            if (ftype == 0) {
                double gg = G, S = (gg * gg * gg * s1 + gg * gg * s2 + gg * s3 + s4) * (1 - gg);
                double u = (o - k * S) / (1 + k * gg * gg * gg * gg);
                double v1 = (u - s1) * gg; double y1 = v1 + s1; s1 = y1 + v1;
                double v2 = (y1 - s2) * gg; double y2 = v2 + s2; s2 = y2 + v2;
                double v3 = (y2 - s3) * gg; double y3 = v3 + s3; s3 = y3 + v3;
                double v4 = (y3 - s4) * gg; double y4 = v4 + s4; s4 = y4 + v4;
                y = y4;
            } else {
                double a1 = 1 / (1 + g * (g + k)), a2 = g * a1, a3 = g * a2;
                double v3 = o - ic2, v1 = a1 * ic1 + a2 * v3, v2 = ic2 + a2 * ic1 + a3 * v3;
                ic1 = 2 * v1 - ic1; ic2 = 2 * v2 - ic2;
                y = ftype == 1 ? v2 : ftype == 2 ? v1 * k : o - k * v1 - v2;
            }
            double e = amp.next();
            return (float) (y * e * level * vel * .9);
        }

        /** FM: operators 4 down to 1, each bent by the ones the algorithm feeds into it; carriers are what you hear. */
        private float fmNext(double f) {
            int[] mods = MODS[algo];
            for (int i = 3; i >= 0; i--) {
                double m = 0;
                int mask = mods[i];
                for (int j = i + 1; j < 4; j++) if ((mask >> j & 1) != 0) m += mo[j];
                if (i == 3) m += fbAmt * (fb1 + fb2) * .25;
                double e = fe4[i].next();
                double s = sin1(fph[i] + m);
                fph[i] += f * fr[i] * fdet[i] / rate;
                if (fph[i] >= 1) fph[i] -= Math.floor(fph[i]);
                double lv = fl[i];
                mo[i] = s * lv * lv * e * 2 * bright;   // as a modulator: phase in cycles
                co[i] = s * lv * e;                     // as a carrier
                if (i == 3) { fb2 = fb1; fb1 = s * e; }
            }
            int cm = CARRIERS[algo];
            double out = 0; int nc = 0;
            for (int i = 0; i < 4; i++) if ((cm >> i & 1) != 0) { out += co[i]; nc++; }
            out /= Math.sqrt(Math.max(1, nc));
            if (lamp > 0) out *= 1 - lamp * .5 * (1 + lfoV);
            return (float) (out * level * vel * .7);
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
                if (kindNo == 2) wposNow = Math.max(0, Math.min(1, pos + penv * fv + plfo * lfoV * .5));
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
            if (kindNo == 1) return fmNext(f);
            if (kindNo == 3) return grainNext(f);
            if (kindNo == 4) return drumNext(f);
            if (kindNo == 5) return pluckNext(f);
            double dt1 = f / rate, dt2 = dt1 * semi2r;
            double pw = Math.max(.05, Math.min(.95, pw1 + lpw * lfoV * .4));
            // oscillators, with unison spread around the note
            double o = 0;
            int n = Math.max(1, uni);
            if (kindNo == 2) {
                // wavetable: the right level of detail for this pitch, two shapes either side of the position, mixed
                int m = 0;
                double nh = .45 / Math.max(1e-9, dt1);
                while (m < WT.MIPS - 1 && WT.HARM[m] > nh) m++;
                float[][] tb = WT.T[table][m];
                double fp = wposNow * (WT.FR - 1);
                int f0 = Math.min(WT.FR - 2, (int) fp);
                double ff = fp - f0;
                float[] A = tb[f0], B = tb[f0 + 1];
                for (int u = 0; u < n; u++) {
                    double d1 = dt1 * det[u];
                    double ph = ph1[u];
                    if (warp > 0) { ph *= 1 + warp * 3; ph -= Math.floor(ph); }
                    double xi = ph * WT.N;
                    int i = (int) xi;
                    double fr2 = xi - i;
                    double av = A[i] + (A[i + 1] - A[i]) * fr2, bv = B[i] + (B[i + 1] - B[i]) * fr2;
                    o += av + (bv - av) * ff;
                    ph1[u] += d1; if (ph1[u] >= 1) ph1[u] -= 1;
                }
            } else for (int u = 0; u < n; u++) {
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
