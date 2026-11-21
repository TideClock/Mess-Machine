package com.tideclock.messmachine.engine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Settings that belong to a pad rather than its sound: glide, where it is routed, its XY setup.
 * App only: saved under "padOpts" in the mess, which the site ignores.
 */
public final class PadOpts {
    public boolean glide = false;
    public double glideT = .12;     // seconds
    public boolean legato = false;  // glide only when the notes overlap
    public int route = 0;           // 0 = the mix, 1..4 = FX buses
    public String xy = "";          // the pad's XY setup, encoded (see Xy)
    /** The FannyPack: the strips packed on this pad's sound, in order ("keys,fx,lfo,xy"). Empty = nothing packed. */
    public String pack = "";

    /*
     * The LFO strip (the build after 84): a slow wave that moves one thing on this pad, and the strip's own filter.
     * Rate is steps per full wave (32 = 2 bars, 16 = 1 bar, 4 = 1/4, 0.5 = 1/32; x 2/3 triplet, x 1.5 dotted).
     */
    public boolean lfoOn = false;
    public int lfoWave = 0;            // 0 sine, 1 triangle, 2 saw up, 3 saw down, 4 square, 5 random steps
    public double lfoRate = 4;
    public int lfoTarget = 1;          // an Engine.T_ target: 1 is the strip's filter cutoff
    public double lfoDepth = .5;
    public int lfoType = 0;            // before build 106 the strip's own filter (0 off, 1 low, 2 high, 3 band); now only read to move an old mess over
    public double lfoCut = .55, lfoRes = .25;
    public boolean lfoRetrig = false;  // restart the wave on every hit, or run it with the loop

    /**
     * Where the FX strip's faders rest on this pad (build 105), "target=value;target=value", values 0..1.
     * Saved so what you hear is what reloads and what a bounce renders. Absent targets rest at 0.
     */
    public String fx = "";

    /** Build 106: the pad filter's kind, 1 low pass, 2 high pass, 3 band pass. Everything that moves the filter moves this one. */
    public int ftype = 1;

    /** The resting value of one FX target, or 0. */
    public double fxHome(int target) {
        Double v = fxMap().get(target);
        return v == null ? 0 : v;
    }

    public Map<Integer, Double> fxMap() {
        Map<Integer, Double> m = new LinkedHashMap<>();
        if (fx == null || fx.isEmpty()) return m;
        for (String part : fx.split(";")) {
            int e = part.indexOf('=');
            if (e <= 0) continue;
            try {
                int t = Integer.parseInt(part.substring(0, e));
                double v = SoundEdit.clamp(Double.parseDouble(part.substring(e + 1)), 0, 1);
                if (v > 0) m.put(t, v);
            } catch (NumberFormatException ignored) {}
        }
        return m;
    }

    /** Sets the resting values; zeros are left out. */
    public void fxSet(Map<Integer, Double> m) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<Integer, Double> e : new java.util.TreeMap<>(m).entrySet()) {
            double v = SoundEdit.clamp(e.getValue(), 0, 1);
            if (v <= 0) continue;
            if (b.length() > 0) b.append(';');
            b.append(e.getKey()).append('=').append(Math.round(v * 1000) / 1000.0);
        }
        fx = b.toString();
    }

    public String lfoCode() {
        return (lfoOn ? 1 : 0) + "," + lfoWave + "," + lfoRate + "," + lfoTarget + "," + lfoDepth + "," + lfoType + "," + lfoCut + "," + lfoRes + "," + (lfoRetrig ? 1 : 0);
    }

    private static final String LFO_DEFAULT = new PadOpts().lfoCode();

    public void lfoParse(String c) {
        if (c == null || c.isEmpty()) return;
        try {
            String[] f = c.split(",");
            lfoOn = f[0].equals("1"); lfoWave = Integer.parseInt(f[1]); lfoRate = SoundEdit.clamp(Double.parseDouble(f[2]), .125, 64);
            lfoTarget = Integer.parseInt(f[3]); lfoDepth = SoundEdit.clamp(Double.parseDouble(f[4]), 0, 1); lfoType = (int) SoundEdit.clamp(Integer.parseInt(f[5]), 0, 3);
            lfoCut = SoundEdit.clamp(Double.parseDouble(f[6]), 0, 1); lfoRes = SoundEdit.clamp(Double.parseDouble(f[7]), 0, 1); lfoRetrig = f[8].equals("1");
        } catch (RuntimeException ignored) {}
    }

    public static final PadOpts DEFAULT = new PadOpts();

    public PadOpts copy() {
        PadOpts o = new PadOpts();
        o.glide = glide; o.glideT = glideT; o.legato = legato; o.route = route; o.xy = xy; o.pack = pack; o.fx = fx; o.ftype = ftype;
        o.lfoParse(lfoCode());
        return o;
    }

    public boolean isDefault() { return !glide && glideT == .12 && !legato && route == 0 && xy.isEmpty() && pack.isEmpty() && fx.isEmpty() && ftype == 1 && lfoCode().equals(LFO_DEFAULT); }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("glide", glide); m.put("glideT", glideT); m.put("legato", legato); m.put("route", (double) route);
        if (!xy.isEmpty()) m.put("xy", xy);
        if (!pack.isEmpty()) m.put("pack", pack);
        if (!fx.isEmpty()) m.put("fx", fx);
        m.put("ftype", (double) ftype);  // its presence also marks a mess saved by build 106 or later
        if (!lfoCode().equals(LFO_DEFAULT)) m.put("lfo", lfoCode());
        return m;
    }

    public static PadOpts fromMap(Map<String, Object> m) {
        PadOpts o = new PadOpts();
        o.glide = Json.bool(m.get("glide"), false);
        o.glideT = SoundEdit.clamp(Json.num(m.get("glideT"), .12), 0, 1);
        o.legato = Json.bool(m.get("legato"), false);
        o.route = (int) SoundEdit.clamp(Json.num(m.get("route"), 0), 0, 4);
        o.xy = Json.str(m.get("xy"), "");
        o.pack = Json.str(m.get("pack"), "");
        o.fx = Json.str(m.get("fx"), "");
        o.lfoParse(Json.str(m.get("lfo"), ""));
        o.ftype = (int) SoundEdit.clamp(Json.num(m.get("ftype"), 1), 1, 3);
        // build 106: the LFO no longer has a filter of its own. A mess saved before that heard the LFO's filter
        // (its kind, cutoff and resonance) whenever the LFO was on: those become the pad filter's resting place.
        if (!m.containsKey("ftype") && o.lfoType > 0) {
            if (o.lfoOn) {
                Map<Integer, Double> h = o.fxMap();
                h.put(1, SoundEdit.clamp(1 - o.lfoCut, 0, 1));   // Engine.T_FILTER: how far closed
                h.put(2, SoundEdit.clamp(o.lfoRes, 0, 1));       // Engine.T_RES
                o.fxSet(h);
                o.ftype = o.lfoType;
            }
            o.lfoType = 0;
        }
        return o;
    }
}
