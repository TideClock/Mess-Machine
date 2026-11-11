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
    /**
     * App only: what a one-shot does while Hold keeps it: "loop" its trim, hold the "tail", play "once",
     * "sustain" (freeze it partway and hold that moment as long as it is held, SE-5) or "beat" (play it
     * whole and start it again every beatN beats in time with the loop, each ringing into the next, SE-7).
     */
    public String held = "loop";
    public int beatN = 1;
    /**
     * App only (SE-1): how a pitch change plays. "stretch" is tape: higher is faster and shorter. "grain" keeps
     * the length. "auto": notes played as keys, chords and in the Roll keep their length; the Doctor's own Tune
     * stays tape, so a tuned-down kick still slows down.
     */
    public String pitch = "auto";
    /** App only (SE-3): keep baked versions at every semitone within an octave, so Grain costs what Stretch costs. */
    public boolean bake;
    /** App only: the doctor's effects, 0 = off. rvbSize 0..1, echoT picks 1/16, 1/8, 3/16, 1/4; wobRate 0..1. */
    public double rvb = 0, rvbSize = .5, echo = 0, dirt = 0, crush = 0, chorus = 0, wob = 0, wobRate = .3;
    public int echoT = 1;

    public static final SoundEdit DEFAULT = new SoundEdit();

    public SoundEdit copy() {
        SoundEdit e = new SoundEdit();
        e.st = st; e.en = en; e.att = att; e.rel = rel; e.rev = rev; e.loop = loop; e.norm = norm;
        e.mode = mode; e.tune = tune; e.fine = fine; e.vol = vol; e.pan = pan; e.tone = tone; e.choke = choke;
        e.held = held; e.beatN = beatN; e.pitch = pitch; e.bake = bake;
        e.rvb = rvb; e.rvbSize = rvbSize; e.echo = echo; e.echoT = echoT; e.dirt = dirt; e.crush = crush; e.chorus = chorus; e.wob = wob; e.wobRate = wobRate;
        return e;
    }

    public boolean isDefault() {
        return st == 0 && en == 1 && att == 0 && rel == 0 && !rev && !loop && !norm && "one".equals(mode)
                && tune == 0 && fine == 0 && vol == 1 && pan == 0 && tone == 0 && choke == 0 && "loop".equals(held) && beatN == 1 && "auto".equals(pitch) && !bake && !hasFx() && rvbSize == .5 && echoT == 1 && wobRate == .3;
    }

    public boolean hasFx() { return rvb > 0 || echo > 0 || dirt > 0 || crush > 0 || chorus > 0 || wob > 0; }

    public boolean holds() { return !"one".equals(mode) || loop || "sustain".equals(held); }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("st", st); m.put("en", en); m.put("att", att); m.put("rel", rel); m.put("rev", rev); m.put("loop", loop);
        m.put("mode", mode); m.put("tune", tune); m.put("fine", fine); m.put("vol", vol); m.put("pan", pan);
        m.put("tone", tone); m.put("norm", norm); m.put("choke", (double) choke);
        if (!"loop".equals(held)) m.put("held", held);
        if (beatN != 1) m.put("beatN", (double) beatN);
        if (!"auto".equals(pitch)) m.put("pitch", pitch);
        if (bake) m.put("bake", true);
        if (hasFx() || rvbSize != .5 || echoT != 1 || wobRate != .3) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("rvb", rvb); f.put("rvbSize", rvbSize); f.put("echo", echo); f.put("echoT", (double) echoT); f.put("dirt", dirt);
            f.put("crush", crush); f.put("chorus", chorus); f.put("wob", wob); f.put("wobRate", wobRate);
            m.put("fx", f);
        }
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
        e.held = "tail".equals(h) || "once".equals(h) || "sustain".equals(h) || "beat".equals(h) ? (String) h : "loop";
        int bn = (int) Math.round(Json.num(m.get("beatN"), 1));
        e.beatN = bn == 2 || bn == 4 ? bn : 1;
        Object pm = m.get("pitch");
        e.pitch = "stretch".equals(pm) || "grain".equals(pm) ? (String) pm : "auto";
        e.bake = Json.bool(m.get("bake"), false);
        Map<String, Object> f = Json.map(m.get("fx"));
        e.rvb = clamp(Json.num(f.get("rvb"), 0), 0, 1); e.rvbSize = clamp(Json.num(f.get("rvbSize"), .5), 0, 1);
        e.echo = clamp(Json.num(f.get("echo"), 0), 0, 1); e.echoT = (int) clamp(Json.num(f.get("echoT"), 1), 0, 3);
        e.dirt = clamp(Json.num(f.get("dirt"), 0), 0, 1); e.crush = clamp(Json.num(f.get("crush"), 0), 0, 1);
        e.chorus = clamp(Json.num(f.get("chorus"), 0), 0, 1); e.wob = clamp(Json.num(f.get("wob"), 0), 0, 1);
        e.wobRate = clamp(Json.num(f.get("wobRate"), .3), 0, 1);
        return e;
    }

    static double clamp(double v, double a, double b) { return Double.isNaN(v) ? a : Math.max(a, Math.min(b, v)); }
}
