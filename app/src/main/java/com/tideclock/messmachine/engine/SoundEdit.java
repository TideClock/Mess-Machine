package com.tideclock.messmachine.engine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The doctor's table settings for one sound, non-destructive. Same fields and defaults as
 * ED_DEF on the site, so edits travel both ways in a saved mess.
 */
public final class SoundEdit {
    public double st = 0, en = 1;        // trim, as a fraction of the sound
    public double att = 0, rel = 0;      // seconds
    public boolean rev = false, loop = false, norm = false;
    public String mode = "one";          // "one" plays it all, "gate" stops when the note ends
    public double tune = 0, fine = 0;    // semitones, cents
    public double vol = 1, pan = 0, tone = 0; // tone < 0 darker (low pass), > 0 thinner (high pass)
    public int choke = 0;                // 0 = none, 1..4 groups
    /** App only: what a one-shot does while Hold keeps it: "loop" its trim, hold the "tail", or play "once". */
    public String held = "loop";

    public static final SoundEdit DEFAULT = new SoundEdit();

    public SoundEdit copy() {
        SoundEdit e = new SoundEdit();
        e.st = st; e.en = en; e.att = att; e.rel = rel; e.rev = rev; e.loop = loop; e.norm = norm;
        e.mode = mode; e.tune = tune; e.fine = fine; e.vol = vol; e.pan = pan; e.tone = tone; e.choke = choke;
        e.held = held;
        return e;
    }

    public boolean isDefault() {
        return st == 0 && en == 1 && att == 0 && rel == 0 && !rev && !loop && !norm && "one".equals(mode)
                && tune == 0 && fine == 0 && vol == 1 && pan == 0 && tone == 0 && choke == 0 && "loop".equals(held);
    }

    public boolean holds() { return !"one".equals(mode) || loop; }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("st", st); m.put("en", en); m.put("att", att); m.put("rel", rel); m.put("rev", rev); m.put("loop", loop);
        m.put("mode", mode); m.put("tune", tune); m.put("fine", fine); m.put("vol", vol); m.put("pan", pan);
        m.put("tone", tone); m.put("norm", norm); m.put("choke", (double) choke);
        if (!"loop".equals(held)) m.put("held", held);
        return m;
    }

    public static SoundEdit fromMap(Map<String, Object> m) {
        SoundEdit e = new SoundEdit();
        e.st = clamp(Json.num(m.get("st"), 0), 0, 1);
        e.en = clamp(Json.num(m.get("en"), 1), 0, 1);
        if (e.en <= e.st) { e.st = 0; e.en = 1; }
        e.att = clamp(Json.num(m.get("att"), 0), 0, 4);
        e.rel = clamp(Json.num(m.get("rel"), 0), 0, 4);
        e.rev = Json.bool(m.get("rev"), false);
        e.loop = Json.bool(m.get("loop"), false);
        e.norm = Json.bool(m.get("norm"), false);
        e.mode = "gate".equals(m.get("mode")) ? "gate" : "one";
        e.tune = clamp(Json.num(m.get("tune"), 0), -48, 48);
        e.fine = clamp(Json.num(m.get("fine"), 0), -100, 100);
        e.vol = clamp(Json.num(m.get("vol"), 1), 0, 4);
        e.pan = clamp(Json.num(m.get("pan"), 0), -1, 1);
        e.tone = clamp(Json.num(m.get("tone"), 0), -1, 1);
        e.choke = (int) clamp(Json.num(m.get("choke"), 0), 0, 8);
        Object h = m.get("held");
        e.held = "tail".equals(h) || "once".equals(h) ? (String) h : "loop";
        return e;
    }

    static double clamp(double v, double a, double b) { return Double.isNaN(v) ? a : Math.max(a, Math.min(b, v)); }
}
