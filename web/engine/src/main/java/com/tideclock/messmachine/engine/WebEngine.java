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
            // Every key is kept: a pad whose sound has not arrived yet is simply skipped by the snapshot until refresh().
            Project.Loaded l = Project.fromJson(json, null);
            proj = l.project;
            proj.dropKeep = dropKeep;
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
    /** Min/max pairs for drawing a sound's waveform (fractions a..z of it), or an empty array if this engine lacks it. */
    @JSExport public static Float32Array overview(String key, double a, double z, int cols) {
        Sample s = samples.get(key);
        if (s == null) return Float32Array.create(0);
        float[] o = s.overview(a, z, cols);
        Float32Array r = Float32Array.create(o.length);
        for (int k = 0; k < o.length; k++) r.set(k, o[k]);
        return r;
    }
    @JSExport public static double seconds(String key) { Sample s = samples.get(key); return s == null ? 0 : s.seconds(); }
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


    /* ---------- editing the mess (the page's own engine holds the working copy) ----------
       Each one is a line-for-line port of the matching AppState function in the Android app, so an edit on the
       web changes the mess exactly as the same tap on the phone does. The page sends messJson() to the worklet after. */

    static Project.Pattern pat() { return proj.pats[proj.cur]; }

    /** AppState.toggleStep: a dotted step clears; a lit step cycles normal, accent, ghost, off. Returns the new strength, -1 for off. */
    @JSExport public static int toggleStep(String key, int j) {
        Project.Pattern P = pat();
        if (j < 0 || j >= P.len) return -1;
        if (P.hasNoteAt(key, j)) { P.notes(key).removeIf(n -> n.s == j); P.setStep(key, j, false); return -1; }
        if (P.step(key, j)) {
            int st = P.strength(key, j);
            if (st == 1) { P.setStrength(key, j, 2); return 2; }
            if (st == 2) { P.setStrength(key, j, 0); return 0; }
            P.setStep(key, j, false); P.setStrength(key, j, 1); return -1;
        }
        P.setStep(key, j, true); P.setStrength(key, j, 1); return 1;
    }

    /** Drag paint: every step the finger crosses takes the first step's state. */
    @JSExport public static void paintStep(String key, int j, boolean on) {
        Project.Pattern P = pat();
        if (j < 0 || j >= P.len || P.step(key, j) == on) return;
        P.setStep(key, j, on);
        if (on) P.setStrength(key, j, 1);
    }

    /** AppState.setLen: longer with fill copies what is there; + adds an empty bar; shorter drops bars past the end. */
    @JSExport public static boolean setLen(int n, boolean fill) {
        Project.Pattern P = pat();
        int old = P.len;
        if (n == old || n < 16 || n > 128 || n % 16 != 0) return false;
        if (n > old && !fill) {
            // an empty bar at the end
        } else if (n > old) {
            for (boolean[] a : P.steps.values()) for (int j = old; j < n; j++) a[j] = a[j % old];
            for (java.util.List<Project.Note> l : P.notes.values()) {
                java.util.List<Project.Note> base = new java.util.ArrayList<>();
                for (Project.Note x : l) if (x.s < old) base.add(x);
                for (int rep = 1; rep < n / old; rep++) for (Project.Note x : base) l.add(new Project.Note(x.s + rep * old, x.p, x.l));
            }
            for (float[] a : P.motion.values()) for (int j = old; j < n; j++) a[j] = a[j % old];
        } else {
            for (boolean[] a : P.steps.values()) for (int j = n; j < a.length; j++) a[j] = false;
            for (java.util.List<Project.Note> l : P.notes.values()) { l.removeIf(x -> x.s >= n); for (Project.Note x : l) x.l = Math.min(x.l, n - x.s); }
            for (float[] a : P.motion.values()) for (int j = n; j < a.length; j++) a[j] = Float.NaN;
        }
        P.extrasFor(Math.min(old, n), Math.max(old, n), n > old && fill);
        P.len = n;
        return true;
    }

    @JSExport public static void clearLoop() { pat().clear(); }
    @JSExport public static void copyLoopTo(int to) { if (to >= 0 && to < Project.NSLOTS && to != proj.cur) proj.pats[to] = proj.pats[proj.cur].copy(); }
    @JSExport public static void clearSoundInLoop(int pad) { if (pad >= 0 && pad < Project.SLOTS && !proj.kit[pad].isEmpty()) pat().forget(proj.kit[pad]); }
    @JSExport public static void clearNotes() { for (java.util.List<Project.Note> l : pat().notes.values()) l.clear(); }

    /** AppState.randomDrums: fresh steps for every loaded pad in this bank, busier on the first. */
    @JSExport public static boolean randomDrums(int bank) {
        Project.Pattern P = pat();
        int n = 0;
        for (int i = bank * Project.BANK; i < (bank + 1) * Project.BANK && i < Project.NPADS; i++) {
            String k = proj.kit[i];
            if (k.isEmpty()) continue;
            for (int j = 0; j < P.len; j++) P.setStep(k, j, Math.random() < (n == 0 ? .3 : .14));
            n++;
        }
        return n > 0;
    }

    @JSExport public static void setCur(int i) { if (i >= 0 && i < Project.NSLOTS) proj.cur = i; }
    @JSExport public static void setSel(int pad) { if (pad >= 0 && pad < Project.SLOTS) proj.sel = pad; }
    @JSExport public static void setBpm(int bpm) { proj.bpm = Math.max(60, Math.min(180, bpm)); }
    @JSExport public static void setSwing(int sw) { proj.swing = Math.max(0, Math.min(100, sw)); }
    @JSExport public static void setName(String n) { proj.name = n == null ? "" : n; }
    @JSExport public static void setKey(String scale, int root) { proj.scale = Theory.SCALES.containsKey(scale) ? scale : "Chromatic"; proj.root = Math.max(0, Math.min(11, root)); }
    @JSExport public static void setSongMode(boolean on) { proj.songMode = on && proj.songLen() > 0; }
    @JSExport public static void setSongBar(int i, int slot) { if (i >= 0 && i < Project.MAXSONG) proj.song[i] = slot < 0 || slot >= Project.NSLOTS ? -1 : slot; }
    @JSExport public static void insertSongBar(int i) { for (int k = Project.MAXSONG - 1; k > i; k--) proj.song[k] = proj.song[k - 1]; if (i >= 0 && i < Project.MAXSONG) proj.song[i] = -1; }
    @JSExport public static void deleteSongBar(int i) { for (int k = i; k < Project.MAXSONG - 1; k++) proj.song[k] = proj.song[k + 1]; proj.song[Project.MAXSONG - 1] = -1; }
    @JSExport public static void setMaster(double squash, double level) { proj.squash = Math.max(0, Math.min(1, squash)); proj.level = Math.max(0, Math.min(1.6, level)); }

    @JSExport public static void assign(int pad, String key) { if (pad >= 0 && pad < Project.SLOTS && key != null && !key.isEmpty()) proj.assign(pad, key); }
    @JSExport public static void clearPad(int pad) { if (pad >= 0 && pad < Project.SLOTS) proj.clearPad(pad); }
    @JSExport public static void swapPads(int a, int b) { proj.swapPads(a, b); }
    @JSExport public static void toggleMute(String key) { if (!proj.mutes.remove(key)) proj.mutes.add(key); }
    @JSExport public static void toggleSolo(String key) { if (!proj.solos.remove(key)) proj.solos.add(key); }
    @JSExport public static void setMutes(String csvKeys) { proj.mutes.clear(); if (csvKeys != null && !csvKeys.isEmpty()) for (String k : csvKeys.split("\n")) proj.mutes.add(k); }

    /** The pad's sound edit (the doctor's table) as JSON, and setting it back. */
    @JSExport public static String editJson(String key) { return Json.write(proj.edit(key).toMap()); }
    @JSExport public static void setEditJson(String key, String json) {
        try { proj.setEdit(key, SoundEdit.fromMap(Json.map(Json.parse(json)))); } catch (Throwable t) { fail(t); }
    }

    /* ---------- a recorded hit coming back from the worklet (AppState's drain loop) ---------- */

    static final java.util.LinkedHashMap<Integer, Boolean> hitNew = new java.util.LinkedHashMap<>();

    /** Applies one RecEvent as pollRec() wrote it (type, pat, key, step, len, hit, strength, pitches). */
    @JSExport public static void applyRec(int type, int pi, String key, int step, int len, int hit, int strength, String pitches) {
        if (pi < 0 || pi >= Project.NSLOTS) return;
        Project.Pattern P = proj.pats[pi];
        if (type == Engine.RecEvent.STEP) {
            if (hit != 0) hitNew.put(hit, !P.step(key, step));
            P.setStep(key, step, true); P.setStrength(key, step, strength);
        } else if (type == Engine.RecEvent.OFF) {
            int t = Math.floorMod(step, P.len * Project.TICKS);
            if (hit != 0) hitNew.put(hit, !(t % Project.TICKS == 0 ? P.step(key, t / Project.TICKS) : P.hasOff(key, step)));
            P.addOff(key, step);
        } else if (type == Engine.RecEvent.CANCEL) {
            Boolean was = hitNew.remove(hit);
            if (was != null && was) {
                if (len == 1) { int t = Math.floorMod(step, P.len * Project.TICKS); if (t % Project.TICKS == 0) P.setStep(key, t / Project.TICKS, false); else P.removeOff(key, step); }
                else P.setStep(key, step, false);
            }
        } else {
            java.util.List<Project.Note> notes = P.notes(key);
            for (int q : csv(pitches)) {
                notes.removeIf(n -> n.s == step && n.p == q);
                notes.add(new Project.Note(step, q, len));
            }
        }
        if (hitNew.size() > 64) { java.util.Iterator<Integer> it = hitNew.keySet().iterator(); for (int k = 0; k < 32 && it.hasNext(); k++) { it.next(); it.remove(); } }
    }


    /* ---------- Drop (held: only the pads you touch keep sounding; never saved) ---------- */

    static java.util.Set<String> dropKeep;
    /** keys separated by newlines; null or "-" ends Drop. */
    @JSExport public static void setDrop(String keys) {
        if (keys == null || keys.equals("-")) dropKeep = null;
        else { dropKeep = new java.util.HashSet<>(); if (!keys.isEmpty()) for (String k : keys.split("\n")) dropKeep.add(k); }
        proj.dropKeep = dropKeep;
        rebuild();
    }

    /* ---------- per-pad settings (padOpts: glide, LFO, FX strip resting places, XY, pack) ---------- */

    @JSExport public static String padOptsJson(int pad) { return Json.write(proj.opt(pad).toMap()); }
    @JSExport public static void setPadOptsJson(int pad, String json) {
        try { proj.setOpt(pad, PadOpts.fromMap(Json.map(Json.parse(json)))); } catch (Throwable t) { fail(t); }
    }

    /* ---------- the step sheet: strength, chance, locks ---------- */

    @JSExport public static String stepJson(String key, int j) {
        Project.Pattern P = pat();
        StringBuilder b = new StringBuilder("{\"on\":").append(P.step(key, j)).append(",\"strength\":").append(P.strength(key, j))
            .append(",\"chance\":").append(P.chance(key, j)).append(",\"label\":").append(Json.write(Project.Pattern.chanceLabel(P.chance(key, j))))
            .append(",\"locks\":{");
        boolean first = true;
        for (int t = 0; t < Engine.NT; t++) { float v = P.lock(key, t, j); if (Float.isNaN(v)) continue; if (!first) b.append(','); first = false; b.append('"').append(t).append("\":").append(v); }
        return b.append("}}").toString();
    }
    @JSExport public static void setStrength(String key, int j, int v) { pat().setStrength(key, j, v); }
    @JSExport public static void setChance(String key, int j, int v) { pat().setChance(key, j, v); }
    @JSExport public static void setLock(String key, int t, int j, float v) { pat().setLock(key, t, j, v); }
    @JSExport public static void rowTool(String key, String tool, int n) {
        Project.Pattern P = pat();
        if (tool.equals("every")) P.rowEvery(key, n);
        else if (tool.equals("repeat")) P.rowRepeat(key, n);
        else if (tool.equals("nudge")) P.rowNudge(key, n);
        else if (tool.equals("spread")) P.rowSpread(key, n);
        else if (tool.equals("clear")) P.rowClear(key);
    }

    /* ---------- the Roll: notes on the current loop ---------- */

    /** "s.p.l" triples of one sound's notes in the current loop, comma separated. */
    @JSExport public static String notesOf(String key) {
        StringBuilder b = new StringBuilder();
        java.util.List<Project.Note> l = pat().notes.get(key);
        if (l != null) for (Project.Note n : l) { if (b.length() > 0) b.append(','); b.append(n.s).append('.').append(n.p).append('.').append(n.l); }
        return b.toString();
    }
    /** A note (or a chord as pitches "p,p,p") at step s, length l: replaces notes at the same step and pitch. */
    @JSExport public static void addNotes(String key, int s, String pitches, int l) {
        Project.Pattern P = pat();
        if (s < 0 || s >= P.len) return;
        java.util.List<Project.Note> notes = P.notes(key);
        for (int q : csv(pitches)) { notes.removeIf(n -> n.s == s && n.p == q); notes.add(new Project.Note(s, q, Math.max(1, Math.min(l, P.len - s)))); }
    }
    @JSExport public static void removeNote(String key, int s, int p) { java.util.List<Project.Note> l = pat().notes.get(key); if (l != null) l.removeIf(n -> n.s == s && n.p == p); }
    @JSExport public static void setFold(boolean on) { proj.fold = on; }

    /* ---------- synths (SE-11) ---------- */

    /** Every synth kind with its knobs and preset names, for the Synth page. */
    @JSExport public static String synthKinds() {
        java.util.List<Object> out = new java.util.ArrayList<>();
        for (Synth.Kind k : Synth.KINDS) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", k.id); m.put("name", k.name); m.put("blurb", k.blurb);
            java.util.List<Object> ps = new java.util.ArrayList<>();
            for (Synth.Param q : k.params) {
                Map<String, Object> pm = new java.util.LinkedHashMap<>();
                pm.put("id", q.id); pm.put("name", q.name); pm.put("group", q.group); pm.put("curve", (double) q.curve);
                pm.put("min", q.min); pm.put("max", q.max); pm.put("def", q.def); pm.put("unit", q.unit); pm.put("simple", q.simple);
                java.util.List<Object> ch = new java.util.ArrayList<>(); for (String c : q.choices) ch.add(c); pm.put("choices", ch);
                ps.add(pm);
            }
            m.put("params", ps);
            java.util.List<Object> pr = new java.util.ArrayList<>(); for (Synth.Patch x : k.presets) pr.add(x.name); m.put("presets", pr);
            out.add(m);
        }
        return Json.write(out);
    }
    @JSExport public static String paramShow(String kind, String id, double v) { Synth.Kind k = Synth.kind(kind); int i = k.at(id); return i < 0 ? "" : k.params[i].show(v); }
    @JSExport public static double paramFromKnob(String kind, String id, double knob) { Synth.Kind k = Synth.kind(kind); int i = k.at(id); return i < 0 ? 0 : k.params[i].fromKnob(knob); }
    @JSExport public static double paramToKnob(String kind, String id, double v) { Synth.Kind k = Synth.kind(kind); int i = k.at(id); return i < 0 ? 0 : k.params[i].toKnob(v); }

    @JSExport public static String synthJson(int pad) { Synth.Patch sp = proj.synth(pad >= 0 && pad < Project.SLOTS ? proj.kit[pad] : null); return sp == null ? "" : Json.write(sp.toMap()); }

    /** AppState.ensureScratch: the scratch slot always holds a synth. */
    @JSExport public static boolean ensureScratch() {
        String k = proj.kit[Project.SCRATCH];
        if (!k.isEmpty() && Project.isSynth(k) && proj.synths.containsKey(k)) return false;
        String key = proj.newSynthKey();
        proj.synths.put(key, Synth.ANALOG.presets.get(0).copy());
        proj.kit[Project.SCRATCH] = key;
        return true;
    }

    static String defaultGrainSource(int pad) {
        String here = pad >= 0 && pad < Project.SLOTS ? proj.kit[pad] : "";
        if (!here.isEmpty() && !Project.isSynth(here)) return here;
        for (String k : proj.kit) if (!k.isEmpty() && !Project.isSynth(k)) return k;
        return "samples/glass-harp.wav";
    }

    /** AppState.newSynth: a new synth from a preset on a pad (replacing what was there). */
    @JSExport public static String newSynth(int pad, String kind, int preset) {
        if (pad < 0 || pad >= Project.SLOTS) return "";
        Synth.Kind k = Synth.kind(kind);
        Synth.Patch made = (preset >= 0 && preset < k.presets.size() ? k.presets.get(preset) : new Synth.Patch(k, k.name)).copy();
        if (k == Synth.GRAIN && made.src.isEmpty()) made.src = defaultGrainSource(pad);
        String key = proj.newSynthKey();
        String old = proj.kit[pad];
        proj.synths.put(key, made);
        proj.kit[pad] = key;
        if (!old.isEmpty() && Project.isSynth(old) && proj.padOf(old) < 0 && !old.equals(proj.kit[Project.SCRATCH])) proj.synths.remove(old);
        if (pad < Project.NPADS) proj.sel = pad;
        return key;
    }

    /** AppState.loadSynthPreset: a preset's knobs into a pad's synth (the key stays, so steps and notes stay). */
    @JSExport public static void loadSynthPreset(int pad, String kind, int preset) {
        String k = proj.kit[pad];
        if (!Project.isSynth(k)) return;
        Synth.Kind kk = Synth.kind(kind);
        if (preset < 0 || preset >= kk.presets.size()) return;
        Synth.Patch old = proj.synths.get(k);
        String keep = old == null ? "" : old.src;
        Synth.Patch made = kk.presets.get(preset).copy();
        if (kk == Synth.GRAIN && made.src.isEmpty()) made.src = keep.isEmpty() ? defaultGrainSource(pad) : keep;
        proj.synths.put(k, made);
    }
    /** A new kind on a pad's synth, keeping its key: the kind's first preset. */
    @JSExport public static void setSynthKind(int pad, String kind) { loadSynthPreset(pad, kind, 0); }
    @JSExport public static void setSynth(int pad, String id, double v) { Synth.Patch sp = proj.synth(proj.kit[pad]); if (sp != null) sp.set(id, v); }
    @JSExport public static void setGrainSource(int pad, String key) { Synth.Patch sp = proj.synth(proj.kit[pad]); if (sp != null) sp.src = key == null ? "" : key; }
    @JSExport public static void setSynthName(int pad, String name) { Synth.Patch sp = proj.synth(proj.kit[pad]); if (sp != null && name != null && !name.isEmpty()) sp.name = name; }

    /** AppState.scratchToPad: the scratch synth's key moves to the pad (its notes go with it); a copy stays. */
    @JSExport public static boolean scratchToPad(int pad) {
        if (pad < 0 || pad >= Project.NPADS) return false;
        String key = proj.kit[Project.SCRATCH];
        Synth.Patch sp = proj.synths.get(key);
        if (sp == null) return false;
        String old = proj.kit[pad];
        proj.kit[pad] = key;
        proj.opts[pad] = proj.opts[Project.SCRATCH] == null ? null : proj.opts[Project.SCRATCH].copy();
        boolean still = false; for (String k2 : proj.kit) if (k2.equals(old)) still = true;
        if (!old.isEmpty() && Project.isSynth(old) && !still) proj.synths.remove(old);
        String nk = proj.newSynthKey();
        proj.synths.put(nk, sp.copy());
        proj.kit[Project.SCRATCH] = nk;
        proj.sel = pad;
        return true;
    }

    /* ---------- chords ---------- */

    @JSExport public static String chordSetsJson() {
        java.util.List<Object> out = new java.util.ArrayList<>();
        for (Theory.ChordSet c : Theory.CHORDSETS.values()) { Map<String, Object> m = new java.util.LinkedHashMap<>(); m.put("name", c.name); m.put("home", c.home); out.add(m); }
        return Json.write(out);
    }
    /** AppState.chordFor: the notes a key plays in a chord mode ("off", "triad", "seventh", "sus", "set:<name>"), as CSV. */
    @JSExport public static String chordFor(int p, String mode, String scale, int root) {
        if (mode == null || mode.equals("off")) return String.valueOf(p);
        int idx = mode.startsWith("set:") ? Theory.setIndexOfKey(p, mode, scale, root) : 0;
        if (idx == Integer.MIN_VALUE) return String.valueOf(p);
        return csvOf(Theory.chordOf(p, idx, mode, scale, root));
    }
    @JSExport public static String chordName(int p, String mode, String scale, int root) {
        if (mode == null || mode.equals("off")) return Theory.noteName(p);
        int idx = mode.startsWith("set:") ? Theory.setIndexOfKey(p, mode, scale, root) : 0;
        if (idx == Integer.MIN_VALUE) return Theory.noteName(p);
        return Theory.chordLabel(p, idx, mode, scale, root);
    }
    @JSExport public static String smooth(String chord, String prev) { return csvOf(Theory.smooth(csv(chord), prev == null || prev.isEmpty() ? null : csv(prev))); }
    @JSExport public static String chordNotes(String chord) { return Theory.chordNotes(csv(chord)); }
    @JSExport public static int foldP(int p) { return Theory.foldP(p); }

    /* ---------- exports: the app's own bounce, stems, WAV and MIDI writers ---------- */

    static Int8Array bytesOf(byte[] b) { Int8Array o = Int8Array.create(b.length); for (int k = 0; k < b.length; k++) o.set(k, b[k]); return o; }
    static int exportRate() { return en == null ? 44100 : en.rate(); }

    /** The mess as a stereo 16-bit WAV: the loop played `loops` times, or the song. */
    @JSExport public static Int8Array wavOf(boolean song, int loops) {
        try {
            float[] a = Engine.bounce(proj, SRC, exportRate(), song, loops);
            java.io.ByteArrayOutputStream os = new java.io.ByteArrayOutputStream();
            Wav.writeStereo16(os, a, exportRate());
            return bytesOf(os.toByteArray());
        } catch (Throwable t) { fail(t); return Int8Array.create(0); }
    }
    static java.util.List<String> stemKeys = new java.util.ArrayList<>();
    static java.util.List<float[]> stemData = new java.util.ArrayList<>();
    /** Renders every sound on its own (all the same length); returns the keys, newline separated. */
    @JSExport public static String stems(boolean song, int loops) {
        stemKeys.clear(); stemData.clear();
        try {
            for (Map.Entry<String, float[]> e : Engine.bounceStems(proj, SRC, exportRate(), song, loops).entrySet()) { stemKeys.add(e.getKey()); stemData.add(e.getValue()); }
        } catch (Throwable t) { fail(t); }
        return String.join("\n", stemKeys);
    }
    @JSExport public static Int8Array stemWav(int i) {
        try {
            java.io.ByteArrayOutputStream os = new java.io.ByteArrayOutputStream();
            Wav.writeStereo16(os, stemData.get(i), exportRate());
            return bytesOf(os.toByteArray());
        } catch (Throwable t) { fail(t); return Int8Array.create(0); }
    }
    /** A format 1 MIDI file (PPQ 96). names: JSON object key to track name. */
    @JSExport public static Int8Array midiOf(boolean song, int loops, String names) {
        try {
            Map<String, String> nm = new java.util.HashMap<>();
            for (Map.Entry<String, Object> e : Json.map(Json.parse(names)).entrySet()) nm.put(e.getKey(), String.valueOf(e.getValue()));
            java.io.ByteArrayOutputStream os = new java.io.ByteArrayOutputStream();
            MidiOut.write(proj, song, loops, nm, os);
            return bytesOf(os.toByteArray());
        } catch (Throwable t) { fail(t); return Int8Array.create(0); }
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
