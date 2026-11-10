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

    public static final PadOpts DEFAULT = new PadOpts();

    public PadOpts copy() {
        PadOpts o = new PadOpts();
        o.glide = glide; o.glideT = glideT; o.legato = legato; o.route = route; o.xy = xy;
        return o;
    }

    public boolean isDefault() { return !glide && glideT == .12 && !legato && route == 0 && xy.isEmpty(); }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("glide", glide); m.put("glideT", glideT); m.put("legato", legato); m.put("route", (double) route);
        if (!xy.isEmpty()) m.put("xy", xy);
        return m;
    }

    public static PadOpts fromMap(Map<String, Object> m) {
        PadOpts o = new PadOpts();
        o.glide = Json.bool(m.get("glide"), false);
        o.glideT = SoundEdit.clamp(Json.num(m.get("glideT"), .12), 0, 1);
        o.legato = Json.bool(m.get("legato"), false);
        o.route = (int) SoundEdit.clamp(Json.num(m.get("route"), 0), 0, 4);
        o.xy = Json.str(m.get("xy"), "");
        return o;
    }
}
