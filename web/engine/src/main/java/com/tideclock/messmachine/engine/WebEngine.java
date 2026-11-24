package com.tideclock.messmachine.engine;

import java.util.HashMap;
import java.util.Map;
import org.teavm.jso.JSExport;
import org.teavm.jso.typedarrays.Float32Array;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * The web's door into the app's own engine. Compiled to JavaScript by TeaVM (web/engine/build.sh),
 * so the web plays the same Engine, Project, Synth and Theory code as the Android app.
 * Everything crosses as numbers, strings (mess JSON, CSV pitches) or Float32Arrays.
 * One engine per JS realm: the AudioWorklet runs one for sound, the page can run one for theory and files.
 */
public final class WebEngine {
    private WebEngine() {}

    static Engine en;
    static Project proj = new Project();
    static final Engine.Live live = new Engine.Live();
    static final Map<String, Sample> samples = new HashMap<>();
    static float[] buf = new float[256];
    static int errors;
    static String lastError = "";

    static final Engine.SampleSource SRC = k -> {
        if (k == null || k.isEmpty()) return null;
        Sample s = samples.get(k);
        if (s == null && Project.isSynth(k)) { s = new Sample(k, new float[64], 44100); samples.put(k, s); }
        return s;
    };

    static void fail(Throwable t) { errors++; lastError = String.valueOf(t); }

    static void rebuild() {
        if (en == null) return;
        try { en.setSnapshot(Engine.Snapshot.build(proj, SRC, live)); } catch (Throwable t) { fail(t); }
    }

    /* ---------- setup ---------- */

    @JSExport public static void init(int rate) { en = new Engine(rate); rebuild(); }
    @JSExport public static int rate() { return en == null ? 0 : en.rate(); }
    @JSExport public static void setLatency(int frames) { if (en != null) en.setLatencyFrames(frames); }

    /** Load a mess (the app's own file format). Returns how many pads name a sound this engine has not been given. */
    @JSExport public static int setMess(String json) {
        try {
            Project.Loaded l = Project.fromJson(json, k -> samples.containsKey(k) || Project.isSynth(k));
            proj = l.project;
            rebuild();
            return l.missing;
        } catch (Throwable t) { fail(t); return -1; }
    }

    /** The loaded mess written back out exactly as the app writes it. */
    @JSExport public static String messJson() { return proj.toJson(false); }

    /** Read a mess and write it back the app's way (fills defaults, drops what the app would drop). */
    @JSExport public static String normalize(String json) {
        try { return Project.fromJson(json, null).project.toJson(false); } catch (Throwable t) { fail(t); return ""; }
    }

    @JSExport public static String demoMess() { return Project.demo().toJson(false); }
    @JSExport public static String starterMess() { return Project.starter().toJson(false); }

    /** A decoded sound, mono. bpm and beats are a loop's own tempo (0 for one-shots). */
    @JSExport public static void addSample(String key, Float32Array data, int rate, double bpm, int beats) {
        int n = data.getLength();
        float[] d = new float[n];
        for (int k = 0; k < n; k++) d[k] = data.get(k);
        Sample s = new Sample(key, d, rate);
        if (bpm > 0 && beats > 0) { s.bpm = bpm; s.beats = beats; }
        samples.put(key, s);
    }
    /** A sound file's bytes (FLAC or WAV), decoded by the app's own decoders so every sample matches the phone. */
    @JSExport public static double addFile(String key, Int8Array bytes, double bpm, int beats) {
        try {
            int n = bytes.getLength();
            byte[] b = new byte[n];
            for (int k = 0; k < n; k++) b[k] = bytes.get(k);
            Sample s = n > 4 && b[0] == 'f' && b[1] == 'L' && b[2] == 'a' && b[3] == 'C'
                ? Flac.read(key, b) : Wav.read(key, new java.io.ByteArrayInputStream(b));
            if (s == null) return -1;
            if (bpm > 0 && beats > 0) { s.bpm = bpm; s.beats = beats; }
            samples.put(key, s);
            return s.seconds();
        } catch (Throwable t) { fail(t); return -1; }
    }
    @JSExport public static boolean hasSample(String key) { return samples.containsKey(key); }
    @JSExport public static void dropSample(String key) { samples.remove(key); }
    /** After a batch of addSample calls: rebuild the snapshot so the new sounds play. */
    @JSExport public static void refresh() { rebuild(); }

    /** Transport toggles that are not saved in the mess. */
    @JSExport public static void setLive(boolean click, boolean rec, boolean recExact, boolean nr, double nrRate, String arp, String nrStyle, String nrPat) {
        live.click = click; live.rec = rec; live.recExact = recExact; live.nr = nr; live.nrRate = nrRate;
        live.arp = arp == null ? "rep" : arp; live.nrStyle = nrStyle == null ? "cut" : nrStyle; live.nrPat = nrPat == null ? "" : nrPat;
        rebuild();
    }

    /* ---------- the audio block ---------- */

    @JSExport public static void render(Float32Array left, Float32Array right) {
        int n = left.getLength();
        if (en == null) { for (int k = 0; k < n; k++) { left.set(k, 0f); right.set(k, 0f); } return; }
        if (buf.length < n * 2) buf = new float[n * 2];
        try { en.render(buf, n); } catch (Throwable t) { fail(t); java.util.Arrays.fill(buf, 0f); }
        for (int k = 0; k < n; k++) { left.set(k, buf[2 * k]); right.set(k, buf[2 * k + 1]); }
    }

    /* ---------- transport and playing ---------- */

    @JSExport public static void play(int countSteps) { if (en != null) { if (countSteps > 0) en.play(countSteps); else en.play(); } }
    @JSExport public static void pause() { if (en != null) en.pause(); }
    @JSExport public static void stop() { if (en != null) en.stop(); }
    @JSExport public static void panic() { if (en != null) en.panic(); }
    @JSExport public static boolean playing() { return en != null && en.isPlaying(); }
    @JSExport public static int countIn() { return en == null ? 0 : en.countUi; }

    static int[] csv(String s) {
        if (s == null || s.isEmpty()) return new int[]{0};
        String[] f = s.split(",");
        int[] a = new int[f.length];
        for (int k = 0; k < f.length; k++) a[k] = Project.parseIntOr(f[k].trim(), 0);
        return a;
    }
    static String csvOf(int[] a) {
        StringBuilder b = new StringBuilder();
        if (a != null) for (int k = 0; k < a.length; k++) { if (k > 0) b.append(','); b.append(a[k]); }
        return b.toString();
    }

    /** A press: id ties it to its up(); pitches as "0" for a drum hit or "60,64,67" for a chord; vel below 0 is a plain tap. */
    @JSExport public static void down(int id, int pad, String pitches, boolean mel, boolean latch, float vel) { if (en != null) en.down(id, pad, csv(pitches), mel, latch, vel); }
    @JSExport public static void up(int id) { if (en != null) en.up(id); }
    @JSExport public static void cancel(int id) { if (en != null) en.cancel(id); }
    @JSExport public static void unlatch(int id) { if (en != null) en.unlatch(id); }
    @JSExport public static void preview(String key, int semis, boolean hold) {
        if (en == null) return;
        Sample s = SRC.get(key);
        if (s != null) en.preview(s, proj.edit(key), semis, hold);
    }
    @JSExport public static void stopPreviews() { if (en != null) en.stopPreviews(); }

    /* ---------- perform ---------- */

    @JSExport public static void fx(String name, boolean on) { if (en != null) en.fx(name, on); }
    @JSExport public static void fxAmount(String name, float amount) { if (en != null) en.fxAmount(name, amount); }
    @JSExport public static void fxTo(String name, int target, float amount) { if (en != null) en.fxTo(name, target, amount); }
    @JSExport public static boolean fxActive() { return en != null && en.fxActive(); }
    @JSExport public static void xy(float x, float y, boolean on) { if (en != null) en.xy(x, y, on); }
    @JSExport public static void xyHighPass(boolean hp) { if (en != null) en.xyHighPass(hp); }
    @JSExport public static void padFilter(int pad, float cut, float res, boolean on) { if (en != null) en.padFilter(pad, cut, res, on); }
    @JSExport public static void mod(int pad, int what, float value) { if (en != null) en.mod(pad, what, value); }
    @JSExport public static void release(int pad, int what) { if (en != null) en.release(pad, what); }
    @JSExport public static void home(int pad, int what, float value) { if (en != null) en.home(pad, what, value); }
    @JSExport public static void noteMod(int id, float semis, float vol) { if (en != null) en.noteMod(id, semis, vol); }
    @JSExport public static void gateLength(int steps) { if (en != null) en.gateLength(steps); }

    /* ---------- what the engine tells the page ---------- */

    /** "playing,pat,step,songPos" for the playhead, already late by the latency given. */
    @JSExport public static String heard() {
        if (en == null) return "0,-1,-1,0";
        Engine.Position p = en.heard();
        return (p.playing ? 1 : 0) + "," + p.pat + "," + p.step + "," + p.songPos;
    }

    /** The next recorded hit as JSON, or "" when none is waiting. */
    @JSExport public static String pollRec() {
        if (en == null) return "";
        Engine.RecEvent r = en.pollRec();
        if (r == null) return "";
        StringBuilder b = new StringBuilder("{\"type\":").append(r.type).append(",\"pat\":").append(r.pat)
            .append(",\"step\":").append(r.step).append(",\"len\":").append(r.len).append(",\"hit\":").append(r.hit)
            .append(",\"vel\":").append(r.vel).append(",\"strength\":").append(r.strength()).append(",\"key\":");
        b.append(Json.write(r.key == null ? "" : r.key)).append(",\"pitches\":[").append(csvOf(r.pitches)).append("]}");
        return b.toString();
    }

    @JSExport public static int voices() { return en == null ? 0 : en.activeVoices(); }
    @JSExport public static double lfoValue(int pad) { return en == null ? 0 : en.lfoValue(pad); }
    @JSExport public static double padCutoff(int pad) { return en == null ? 20000 : en.padCutoff(pad); }
    @JSExport public static int errors() { return errors; }
    @JSExport public static String lastError() { return lastError; }

    /* ---------- files ---------- */

    /** The loaded mess played out offline, interleaved stereo at the engine's rate. */
    @JSExport public static Float32Array bounce(boolean song, int loops) {
        try {
            float[] a = Engine.bounce(proj, SRC, en == null ? 44100 : en.rate(), song, loops);
            Float32Array o = Float32Array.create(a.length);
            for (int k = 0; k < a.length; k++) o.set(k, a[k]);
            return o;
        } catch (Throwable t) { fail(t); return Float32Array.create(0); }
    }

    /* ---------- theory, shared with the page so Keys and Chords match the app ---------- */

    @JSExport public static String scales() { return Json.write(new java.util.ArrayList<Object>(Theory.SCALES.keySet())); }
    @JSExport public static String chordSets() { return Json.write(new java.util.ArrayList<Object>(Theory.CHORDSETS.keySet())); }
    @JSExport public static String chordOf(int p, int idx, String mode, String scale, int root) { return csvOf(Theory.chordOf(p, idx, mode, scale, root)); }
    @JSExport public static String chordLabel(int p, int idx, String mode, String scale, int root) { return Theory.chordLabel(p, idx, mode, scale, root); }
    @JSExport public static String stripNotes(String scale, int root) { return csvOf(Theory.stripNotes(scale, root)); }
    @JSExport public static int keyNote(int d, String scale, int root) { return Theory.keyNote(d, scale, root); }
    @JSExport public static boolean inScale(int p, String scale, int root) { return Theory.inScale(p, scale, root); }
    @JSExport public static String noteName(int p) { return Theory.noteName(p); }
    @JSExport public static int setSize(String mode) { return Theory.setSize(mode); }
    @JSExport public static String slotName(String mode, int j, int root) { return Theory.slotName(mode, j, root); }
}
