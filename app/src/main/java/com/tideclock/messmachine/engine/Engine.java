package com.tideclock.messmachine.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The Mess Machine's sound engine. Everything that makes noise happens inside render(), on the
 * audio thread, counted in frames: steps, swing, note repeat, the arp and every voice. That keeps
 * the timing exact no matter what the screen is doing. The UI talks to it through a command queue
 * and an immutable Snapshot of the mess, and hears back about recorded hits through a second queue.
 *
 * The rules for how a sound plays (trim, tune, gate, loop, attack, release, choke, tone, pan,
 * normalize) follow voiceEd() on the site.
 */
public final class Engine {

    /* ================= what the engine plays from ================= */

    public interface SampleSource { Sample get(String key); }

    /** Toggles that live on the transport, not in the saved mess. */
    public static final class Live {
        public boolean click, rec, nr;
        public double nrRate = 1;      // steps between repeats: 2 = 1/8, 1 = 1/16, 0.5 = 1/32
        public String arp = "rep";     // rep, up, down, updown, rand
        public Live copy() { Live l = new Live(); l.click = click; l.rec = rec; l.nr = nr; l.nrRate = nrRate; l.arp = arp; return l; }
    }

    public static final class PadSlot {
        public final int index;
        public final String key;
        public final Sample sample;
        public final SoundEdit edit;
        public final boolean audible;
        public PadSlot(int index, String key, Sample sample, SoundEdit edit, boolean audible) {
            this.index = index; this.key = key; this.sample = sample; this.edit = edit; this.audible = audible;
        }
    }

    static final class Track {
        final PadSlot pad;
        final boolean[] steps;
        final int[] gap;
        final int[][] notes; // per step: pitch, length, pitch, length...
        Track(PadSlot pad, boolean[] steps, int[] gap, int[][] notes) { this.pad = pad; this.steps = steps; this.gap = gap; this.notes = notes; }
    }

    static final class Pat {
        final int len;
        final Track[] tracks;
        Pat(int len, Track[] tracks) { this.len = len; this.tracks = tracks; }
    }

    public static final class Snapshot {
        final int bpm, swing, cur, songLen;
        final boolean songMode;
        final int[] song;
        final PadSlot[] pads;
        final Pat[] pats;
        final Live live;

        private Snapshot(int bpm, int swing, int cur, int songLen, boolean songMode, int[] song, PadSlot[] pads, Pat[] pats, Live live) {
            this.bpm = bpm; this.swing = swing; this.cur = cur; this.songLen = songLen; this.songMode = songMode;
            this.song = song; this.pads = pads; this.pats = pats; this.live = live;
        }

        public static Snapshot build(Project p, SampleSource src, Live live) {
            PadSlot[] pads = new PadSlot[Project.NPADS];
            for (int i = 0; i < Project.NPADS; i++) {
                String k = p.kit[i];
                if (k.isEmpty()) continue;
                Sample s = src.get(k);
                if (s == null) continue;
                pads[i] = new PadSlot(i, k, s, p.edit(k).copy(), p.audible(k));
            }
            Pat[] pats = new Pat[Project.NSLOTS];
            for (int pi = 0; pi < Project.NSLOTS; pi++) {
                Project.Pattern P = p.pats[pi];
                int len = P.len;
                List<Track> tr = new ArrayList<>();
                for (PadSlot ps : pads) {
                    if (ps == null || !P.usedBy(ps.key)) continue;
                    boolean[] st = new boolean[len];
                    for (int j = 0; j < len; j++) st[j] = P.step(ps.key, j);
                    int[] gap = new int[len];
                    for (int j = 0; j < len; j++) { int d = 1; while (d < len && !st[(j + d) % len]) d++; gap[j] = d; }
                    int[][] nt = new int[len][];
                    List<Project.Note> nl = P.notes.get(ps.key);
                    for (int j = 0; j < len; j++) {
                        int c = 0;
                        if (nl != null) for (Project.Note n : nl) if (n.s == j) c++;
                        int[] a = new int[c * 2];
                        int q = 0;
                        if (nl != null) for (Project.Note n : nl) if (n.s == j) { a[q++] = n.p; a[q++] = n.l; }
                        nt[j] = a;
                    }
                    tr.add(new Track(ps, st, gap, nt));
                }
                pats[pi] = new Pat(len, tr.toArray(new Track[0]));
            }
            int sl = p.songLen();
            return new Snapshot(p.bpm, p.swing, p.cur, sl, p.songMode && sl > 0, p.song.clone(), pads, pats, live == null ? new Live() : live.copy());
        }
    }

    /* ================= what the engine tells the UI ================= */

    public static final class RecEvent {
        public static final int STEP = 0, NOTES = 1;
        public final int type, pat, step, len;
        public final String key;
        public final int[] pitches;
        RecEvent(int type, int pat, String key, int step, int[] pitches, int len) {
            this.type = type; this.pat = pat; this.key = key; this.step = step; this.pitches = pitches; this.len = len;
        }
    }

    public static final class Position {
        public final boolean playing;
        public final int pat, step, songPos;
        Position(boolean playing, int pat, int step, int songPos) { this.playing = playing; this.pat = pat; this.step = step; this.songPos = songPos; }
    }

    /* ================= voices ================= */

    static final class Voice {
        boolean active, preview, hold, releasing, choked;
        float[] d;
        int a, zEnd, choke, pad;
        double pos, len, inc;
        boolean loop;
        long delay, t, playLen, attF, fadeF, startAbs, chokeAt;
        float lvl, gl, gr, relFrom, chokeG;
        long relLeft, relLen;
        boolean filt;
        float b0, b1, b2, a1, a2, x1, x2, y1, y2;
        float lastEnv;
    }

    private final int rate;
    private final Voice[] pool = new Voice[96];
    private volatile Snapshot snap;
    private final ConcurrentLinkedQueue<Object[]> cmds = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<RecEvent> recs = new ConcurrentLinkedQueue<>();
    private final Sample clickHi, clickLo;

    // clock state, audio thread only
    private boolean playing, paused, freeOn;
    private long frame;
    private double nextStepAt, freeNextAt;
    private int step, songPos, arpI;
    private long fired, stopAfter = -1;
    private final Map<Integer, Held> held = new LinkedHashMap<>();
    private final java.util.Random rnd = new java.util.Random();

    // master bus
    private float compEnv;
    private final float compAtk, compRel;

    // for the UI's playhead
    private volatile long uiFrame, uiNanos;
    private volatile int latencyFrames;
    private volatile boolean uiPlaying;
    private final long[] hFrame = new long[64];
    private final int[] hPat = new int[64], hStep = new int[64], hSong = new int[64];
    private volatile int hHead = -1;

    static final class Held {
        int id;
        PadSlot pad;
        int[] pitches;
        boolean mel, holdV;
        double at;
        List<Voice> voices = new ArrayList<>();
    }

    public Engine(int rate) {
        this.rate = rate;
        for (int k = 0; k < pool.length; k++) pool[k] = new Voice();
        clickHi = tone("click-hi", 1760);
        clickLo = tone("click-lo", 1100);
        compAtk = (float) (1 - Math.exp(-1.0 / (.005 * rate)));
        compRel = (float) (1 - Math.exp(-1.0 / (.2 * rate)));
        snap = Snapshot.build(new Project(), k -> null, null);
    }

    private Sample tone(String key, double f) {
        int n = (int) (.06 * rate);
        float[] d = new float[n];
        for (int k = 0; k < n; k++) { double t = k / (double) rate; d[k] = (float) (Math.sin(2 * Math.PI * f * t) * .18 * Math.pow(.001 / .18, Math.min(1, t / .05))); }
        return new Sample(key, d, rate);
    }

    public int rate() { return rate; }

    /* ================= talking to the engine (any thread) ================= */

    public void setSnapshot(Snapshot s) { snap = s; }
    public void setLatencyFrames(int f) { latencyFrames = f; }

    public void play() { cmds.add(new Object[]{"play"}); }
    public void pause() { cmds.add(new Object[]{"pause"}); }
    public void stop() { cmds.add(new Object[]{"stop"}); }
    public void panic() { cmds.add(new Object[]{"panic"}); }

    /** A pad or key went down. mel = keys or the strip (pitched, may hold), pitches in semitones. */
    public void down(int id, int pad, int[] pitches, boolean mel) { cmds.add(new Object[]{"down", id, pad, pitches, mel, System.nanoTime()}); }
    public void up(int id) { cmds.add(new Object[]{"up", id, System.nanoTime()}); }

    /** Hear a sound with its doctor settings, outside the mess (browsing, editing, toggling a step). */
    public void preview(Sample s, SoundEdit e, int semis) { if (s != null) cmds.add(new Object[]{"preview", s, e == null ? SoundEdit.DEFAULT : e.copy(), semis}); }
    public void stopPreviews() { cmds.add(new Object[]{"stopprev"}); }

    public RecEvent pollRec() { return recs.poll(); }

    public boolean isPlaying() { return uiPlaying; }

    /** Where the music you are hearing right now is. */
    public Position heard() {
        int h = hHead;
        boolean pl = uiPlaying;
        if (h < 0) return new Position(pl, -1, -1, 0);
        long now = uiFrame + (long) ((System.nanoTime() - uiNanos) * (rate / 1e9)) - latencyFrames;
        for (int k = 0; k < hFrame.length; k++) {
            int i = ((h - k) % hFrame.length + hFrame.length) % hFrame.length;
            if (hFrame[i] <= now) return new Position(pl, hPat[i], hStep[i], hSong[i]);
        }
        return new Position(pl, hPat[(h % hFrame.length + hFrame.length) % hFrame.length], -1, 0);
    }

    /* ================= the audio thread ================= */

    /** Fill out with frames of interleaved stereo. */
    public void render(float[] out, int frames) {
        long nanos = System.nanoTime();
        Snapshot S = snap;
        Object[] c;
        while ((c = cmds.poll()) != null) command(S, c, nanos);
        java.util.Arrays.fill(out, 0, frames * 2, 0f);
        int done = 0;
        while (done < frames) {
            int chunk = frames - done;
            if (playing) {
                long until = (long) Math.ceil(nextStepAt) - frame;
                if (until <= 0) { fireStep(S); continue; }
                chunk = (int) Math.min(chunk, until);
            } else if (freeOn) {
                long until = (long) Math.ceil(freeNextAt) - frame;
                if (until <= 0) { fireFree(S); continue; }
                chunk = (int) Math.min(chunk, until);
            }
            mix(out, done, chunk);
            done += chunk;
            frame += chunk;
        }
        master(out, frames);
        uiFrame = frame - frames;
        uiNanos = nanos;
        uiPlaying = playing;
    }

    private double spb(Snapshot S) { return rate * 60.0 / Math.max(40, S.bpm) / 4.0; }

    private int playingPat(Snapshot S) {
        if (S.songMode) return songPos < S.song.length ? S.song[songPos] : -1;
        return S.cur;
    }

    private int lenOf(Snapshot S, int pi) { return pi >= 0 ? S.pats[pi].len : 16; }

    private double nowStep(Snapshot S, long nanos, long renderNanos) {
        double heardFrame = frame - latencyFrames - (renderNanos - nanos) * (rate / 1e9);
        return step - (nextStepAt - heardFrame) / spb(S);
    }

    private void command(Snapshot S, Object[] c, long renderNanos) {
        switch ((String) c[0]) {
            case "play":
                if (!paused) { step = 0; songPos = 0; }
                paused = false; playing = true; freeOn = false;
                nextStepAt = frame + .004 * rate;
                break;
            case "pause":
                if (!playing) break;
                int heard = (int) Math.floor(nowStep(S, renderNanos, renderNanos));
                int len = lenOf(S, playingPat(S));
                step = Math.max(0, Math.min(len - 1, heard + 1));
                playing = false; paused = true;
                break;
            case "stop":
                if (!playing && !paused) releaseAll(.03f);
                playing = false; paused = false; step = 0; songPos = 0; freeOn = !held.isEmpty() && S.live.nr;
                hHead = -1;
                break;
            case "panic":
                for (Voice v : pool) v.active = false;
                held.clear(); freeOn = false;
                break;
            case "down": padDown(S, (Integer) c[1], (Integer) c[2], (int[]) c[3], (Boolean) c[4], (Long) c[5], renderNanos); break;
            case "up": padUp(S, (Integer) c[1], (Long) c[2], renderNanos); break;
            case "preview": {
                Sample s = (Sample) c[1];
                PadSlot ps = new PadSlot(-1, s.key, s, (SoundEdit) c[2], true);
                for (Voice v : pool) if (v.active && v.preview) v.active = false;
                Voice v = trigger(ps, Math.pow(2, (Integer) c[3] / 12.0), 0, false, .85f, 0, false);
                if (v != null) v.preview = true;
                break;
            }
            case "stopprev": for (Voice v : pool) if (v.active && v.preview) startRelease(v, .03f); break;
        }
    }

    private void releaseAll(float sec) { for (Voice v : pool) if (v.active && !v.preview) startRelease(v, sec); }

    private void padDown(Snapshot S, int id, int pad, int[] pitches, boolean mel, long nanos, long renderNanos) {
        PadSlot ps = pad >= 0 && pad < S.pads.length ? S.pads[pad] : null;
        if (ps == null) return;
        Held h = new Held();
        h.id = id; h.pad = ps; h.pitches = pitches; h.mel = mel;
        h.holdV = mel && ps.edit.holds();
        h.at = playing ? nowStep(S, nanos, renderNanos) : 0;
        Held old = held.remove(id);
        if (old != null) for (Voice v : old.voices) startRelease(v, .02f);
        held.put(id, h);
        Live L = S.live;
        boolean arpLive = L.nr && !"rep".equals(L.arp);
        if (!(L.nr && playing) && !arpLive) {
            float g = mel ? Theory.chordGain(pitches.length) : .85f;
            for (int p : pitches) { Voice v = trigger(ps, Math.pow(2, p / 12.0), 0, false, g, 0, h.holdV); if (v != null) h.voices.add(v); }
        }
        if (L.nr && !playing && !freeOn) {
            freeOn = true;
            freeNextAt = frame + Math.max(.5, L.nrRate) * spb(S);
            if (arpLive) nrFree(S);
        }
        if (L.rec && playing && !L.nr && !mel) {
            int pi = playingPat(S);
            if (pi >= 0) {
                int len = S.pats[pi].len, q = Math.floorMod((int) Math.round(h.at), len);
                recs.add(new RecEvent(RecEvent.STEP, pi, ps.key, q, null, 1));
            }
        }
    }

    private void padUp(Snapshot S, int id, long nanos, long renderNanos) {
        Held h = held.remove(id);
        if (h == null) return;
        if (h.holdV) for (Voice v : h.voices) release(v);
        Live L = S.live;
        if (L.rec && playing && h.mel && !L.nr) {
            int pi = playingPat(S);
            if (pi >= 0) {
                int len = S.pats[pi].len, q = Math.floorMod((int) Math.round(h.at), len);
                int l = (int) Math.max(1, Math.min(len - q, Math.round(nowStep(S, nanos, renderNanos) - h.at)));
                recs.add(new RecEvent(RecEvent.NOTES, pi, h.pad.key, q, h.pitches, l));
            }
        }
        if (held.isEmpty()) { arpI = 0; freeOn = false; }
    }

    private void fireFree(Snapshot S) {
        if (!S.live.nr || held.isEmpty()) { freeOn = false; return; }
        nrFree(S);
        freeNextAt += Math.max(.5, S.live.nrRate) * spb(S);
    }

    private void nrFree(Snapshot S) {
        double dur = Math.max(.5, S.live.nrRate) * spb(S) / rate * .9;
        if (!"rep".equals(S.live.arp)) {
            Object[] n = arpNext(S);
            if (n != null) trigger(((Held) n[0]).pad, Math.pow(2, (Integer) n[1] / 12.0), dur, true, .85f, 0, false);
            return;
        }
        for (Held h : held.values()) {
            float g = Theory.chordGain(h.pitches.length);
            for (int p : h.pitches) trigger(h.pad, Math.pow(2, p / 12.0), dur, h.mel || p != 0, g, 0, false);
        }
    }

    private Object[] arpNext(Snapshot S) {
        List<Object[]> L = new ArrayList<>();
        for (Held h : held.values()) for (int p : h.pitches) L.add(new Object[]{h, p});
        if (L.isEmpty()) return null;
        L.sort((x, y) -> Integer.compare((Integer) x[1], (Integer) y[1]));
        int n = L.size(), i;
        String m = S.live.arp;
        if ("down".equals(m)) i = n - 1 - arpI % n;
        else if ("updown".equals(m)) { int per = Math.max(1, 2 * n - 2), x = arpI % per; i = x < n ? x : per - x; }
        else if ("rand".equals(m)) i = rnd.nextInt(n);
        else i = arpI % n;
        arpI++;
        return L.get(Math.max(0, Math.min(n - 1, i)));
    }

    private void fireStep(Snapshot S) {
        int pi = playingPat(S);
        Pat P = pi >= 0 ? S.pats[pi] : null;
        int len = P != null ? P.len : 16;
        if (step >= len) step = 0;
        if (stopAfter >= 0 && fired >= stopAfter) { playing = false; return; }
        double spb = spb(S);
        int s = step;
        double at = nextStepAt - frame + (s % 2 == 1 ? S.swing / 100.0 * spb * .5 : 0);
        long delay = Math.max(0, Math.round(at));
        if (P != null) {
            for (Track t : P.tracks) {
                if (!t.pad.audible) continue;
                if (t.steps[s]) trigger(t.pad, 1, t.gap[s] * spb / rate, false, .85f, delay, false);
                int[] nt = t.notes[s];
                for (int k = 0; k < nt.length; k += 2) trigger(t.pad, Math.pow(2, nt[k] / 12.0), nt[k + 1] * spb / rate, true, .78f, delay, false);
            }
        }
        Live L = S.live;
        if (L.click && s % 4 == 0) trigger(new PadSlot(-2, "click", s == 0 ? clickHi : clickLo, SoundEdit.DEFAULT, true), 1, 0, false, 1f, delay, false);
        if (L.nr && !held.isEmpty()) {
            double every = L.nrRate;
            if (every >= 1 ? s % (int) every == 0 : true) {
                nrTick(S, delay, s, pi, false, every, spb);
                if (every < 1) nrTick(S, delay + Math.round(spb / 2), s, pi, true, every, spb);
            }
        }
        int h = hHead + 1;
        int i = h % hFrame.length;
        hFrame[i] = frame + delay; hPat[i] = pi; hStep[i] = s; hSong[i] = songPos;
        hHead = h;
        fired++;
        nextStepAt += spb;
        step++;
        if (step >= len) {
            step = 0;
            if (S.songMode) songPos = (songPos + 1) % Math.max(1, S.songLen);
        }
    }

    private void nrTick(Snapshot S, long delay, int s, int pi, boolean half, double every, double spb) {
        if ("rep".equals(S.live.arp)) {
            for (Held h : held.values()) {
                float g = Theory.chordGain(h.pitches.length);
                for (int p : h.pitches) nrOne(S, h, p, delay, s, pi, half, g, every, spb);
            }
        } else {
            Object[] n = arpNext(S);
            if (n != null) nrOne(S, (Held) n[0], (Integer) n[1], delay, s, pi, half, .85f, every, spb);
        }
    }

    private void nrOne(Snapshot S, Held h, int p, long delay, int s, int pi, boolean half, float g, double every, double spb) {
        trigger(h.pad, Math.pow(2, p / 12.0), spb * .9 * Math.max(1, every >= 1 ? every : 1) / rate, h.mel || p != 0, g, delay, false);
        if (S.live.rec && pi >= 0 && !half) {
            if (h.mel) recs.add(new RecEvent(RecEvent.NOTES, pi, h.pad.key, s, new int[]{p}, every >= 2 ? 2 : 1));
            else recs.add(new RecEvent(RecEvent.STEP, pi, h.pad.key, s, null, 1));
        }
    }

    /* ---------- starting one voice, voiceEd() from the site ---------- */

    private Voice trigger(PadSlot ps, double rateMul, double durSec, boolean gate, float gain, long delay, boolean hold) {
        SoundEdit e = ps.edit;
        Sample s = ps.sample;
        int N = s.data.length;
        if (N < 2) return null;
        boolean rev = e.rev;
        float[] d = rev ? s.reversedData() : s.data;
        double a = e.st * N, z = e.en * N;
        if (rev) { double a2 = N - z; z = N - a; a = a2; }
        double len = Math.max(.005 * s.rate, z - a);
        double r = Math.max(.05, rateMul * Math.pow(2, (e.tune + e.fine / 100.0) / 12.0));
        double lvl = gain * e.vol * (e.norm ? Math.min(10, .95 / s.regionPeak(e.st, e.en)) : 1);
        double natural = len / s.rate / r;
        boolean g = "gate".equals(e.mode) || gate;
        double play = hold ? 600 : e.loop ? (durSec > 0 ? durSec : natural) : (g && durSec > 0 ? Math.min(durSec, natural) : natural);
        double att = Math.min(e.att, play * .9);
        double fade = Math.min(Math.max(e.rel, .004), Math.max(.002, play - att));

        Voice v = freeVoice();
        v.active = true; v.preview = false; v.hold = hold; v.releasing = false; v.choked = false;
        v.d = d; v.a = (int) Math.max(0, Math.floor(a)); v.len = len; v.zEnd = (int) Math.min(N, Math.ceil(a + len));
        v.pos = 0; v.inc = r * s.rate / rate; v.loop = e.loop;
        v.delay = delay; v.t = 0;
        v.playLen = Math.max(1, Math.round(play * rate)); v.attF = Math.round(att * rate); v.fadeF = Math.max(1, Math.round(fade * rate));
        v.lvl = (float) lvl;
        double x = (e.pan + 1) / 2;
        v.gl = (float) Math.min(1, Math.sqrt(2) * Math.cos(x * Math.PI / 2));
        v.gr = (float) Math.min(1, Math.sqrt(2) * Math.sin(x * Math.PI / 2));
        v.pad = ps.index; v.choke = e.choke; v.startAbs = frame + delay; v.chokeAt = Long.MAX_VALUE; v.chokeG = 1; v.lastEnv = 0;
        v.relLeft = 0; v.relLen = 1; v.relFrom = 0;
        setFilter(v, e.tone);
        if (e.choke > 0) {
            for (Voice o : pool) if (o != v && o.active && !o.preview && o.choke == e.choke && o.startAbs < v.startAbs) o.chokeAt = Math.min(o.chokeAt, v.startAbs);
        }
        return v;
    }

    private void setFilter(Voice v, double tone) {
        v.x1 = v.x2 = v.y1 = v.y2 = 0;
        if (tone == 0) { v.filt = false; return; }
        boolean lp = tone < 0;
        double f = lp ? 18000 * Math.pow(250.0 / 18000, -tone) : 20 * Math.pow(3000.0 / 20, tone);
        f = Math.min(f, rate * .45);
        double w = 2 * Math.PI * f / rate, cs = Math.cos(w), al = Math.sin(w) / (2 * .7071);
        double b0, b1, b2, a0 = 1 + al, a1 = -2 * cs, a2 = 1 - al;
        if (lp) { b0 = (1 - cs) / 2; b1 = 1 - cs; b2 = (1 - cs) / 2; }
        else { b0 = (1 + cs) / 2; b1 = -(1 + cs); b2 = (1 + cs) / 2; }
        v.b0 = (float) (b0 / a0); v.b1 = (float) (b1 / a0); v.b2 = (float) (b2 / a0); v.a1 = (float) (a1 / a0); v.a2 = (float) (a2 / a0);
        v.filt = true;
    }

    private Voice freeVoice() {
        Voice best = null;
        for (Voice v : pool) if (!v.active) return v;
        for (Voice v : pool) if (!v.preview && (best == null || v.t > best.t)) best = v;
        return best != null ? best : pool[0];
    }

    private void release(Voice v) {
        if (!v.active) return;
        startRelease(v, 0);
    }

    private void startRelease(Voice v, float minSec) {
        if (!v.active || v.releasing) return;
        if (v.delay > 0) { v.active = false; return; }
        v.releasing = true;
        v.relLen = Math.max(1, Math.round(Math.max(minSec > 0 ? minSec : .015, v.hold ? relOf(v) : minSec) * rate));
        v.relLeft = v.relLen;
        v.relFrom = v.lastEnv;
    }

    private double relOf(Voice v) { return Math.max(.015, v.fadeF / (double) rate); }

    /* ---------- mixing ---------- */

    private void mix(float[] out, int off, int n) {
        float chokeK = (float) Math.exp(-1.0 / (.006 * rate));
        for (Voice v : pool) {
            if (!v.active) continue;
            int k = 0;
            if (v.delay > 0) {
                long skip = Math.min(v.delay, n);
                v.delay -= skip;
                k = (int) skip;
                if (v.delay > 0) continue;
            }
            float[] d = v.d;
            for (; k < n; k++) {
                // envelope
                float env;
                if (v.releasing) {
                    if (v.relLeft <= 0) { v.active = false; break; }
                    env = v.relFrom * (v.relLeft / (float) v.relLen);
                    v.relLeft--;
                } else {
                    if (v.t >= v.playLen) { v.active = false; break; }
                    if (v.attF > 0 && v.t < v.attF) env = v.lvl * (v.t / (float) v.attF);
                    else if (v.t >= v.playLen - v.fadeF) env = v.lvl * ((v.playLen - v.t) / (float) v.fadeF);
                    else env = v.lvl;
                    v.lastEnv = env;
                }
                if (v.chokeAt != Long.MAX_VALUE && frame + k >= v.chokeAt) {
                    v.chokeG *= chokeK;
                    if (v.chokeG < 1e-4f || frame + k - v.chokeAt > .05 * rate) { v.active = false; break; }
                    env *= v.chokeG;
                }
                // read the sound
                double pos = v.pos;
                if (v.loop) { while (pos >= v.len) pos -= v.len; v.pos = pos; }
                int i = v.a + (int) pos;
                if (i >= v.zEnd - 1 || i >= d.length - 1) {
                    if (!v.loop) { v.active = false; break; }
                    i = Math.min(v.zEnd, d.length) - 2;
                }
                double fr = pos - Math.floor(pos);
                float smp = (float) (d[i] + (d[i + 1] - d[i]) * fr);
                if (v.filt) {
                    float y = v.b0 * smp + v.b1 * v.x1 + v.b2 * v.x2 - v.a1 * v.y1 - v.a2 * v.y2;
                    v.x2 = v.x1; v.x1 = smp; v.y2 = v.y1; v.y1 = y; smp = y;
                }
                smp *= env;
                int o = (off + k) * 2;
                out[o] += smp * v.gl;
                out[o + 1] += smp * v.gr;
                v.pos += v.inc;
                v.t++;
            }
        }
    }

    /** A gentle compressor (about where the site's sits) and a soft clip, so stacked pads do not crack. */
    private void master(float[] out, int frames) {
        final float thr = .316f, ratio = 4f;
        for (int k = 0; k < frames; k++) {
            float l = out[k * 2], r = out[k * 2 + 1];
            float x = Math.max(Math.abs(l), Math.abs(r));
            compEnv += (x - compEnv) * (x > compEnv ? compAtk : compRel);
            float g = 1f;
            if (compEnv > thr) g = (float) (thr * Math.pow(compEnv / thr, 1 / ratio) / compEnv);
            g *= 1.25f;
            out[k * 2] = soft(l * g);
            out[k * 2 + 1] = soft(r * g);
        }
    }

    private static float soft(float x) {
        float a = Math.abs(x);
        if (a <= .8f) return x;
        float y = .8f + .2f * (float) Math.tanh((a - .8f) / .2f);
        return x < 0 ? -y : y;
    }

    public int activeVoices() { int n = 0; for (Voice v : pool) if (v.active) n++; return n; }

    /* ================= bouncing to a file ================= */

    /** Render the mess offline. song = the whole song lane, otherwise the current loop, loops times. */
    public static float[] bounce(Project p, SampleSource src, int rate, boolean song, int loops) {
        Project q = p.copy();
        q.songMode = song && q.songLen() > 0;
        Snapshot S = Snapshot.build(q, src, new Live());
        Engine e = new Engine(rate);
        e.snap = S;
        long total = 0;
        if (q.songMode) for (int k = 0; k < q.songLen(); k++) total += q.song[k] >= 0 ? q.pats[q.song[k]].len : 16;
        else total = (long) q.pats[q.cur].len * Math.max(1, loops);
        e.stopAfter = total;
        e.cmds.add(new Object[]{"play"});
        int block = 512;
        float[] buf = new float[block * 2];
        double stepFrames = rate * 60.0 / q.bpm / 4.0;
        long maxFrames = (long) (total * stepFrames + 6.0 * rate);
        List<float[]> parts = new ArrayList<>();
        long rendered = 0, quietSince = -1;
        while (rendered < maxFrames) {
            e.render(buf, block);
            parts.add(buf.clone());
            rendered += block;
            if (!e.playing && e.fired >= total) {
                if (e.activeVoices() == 0) { if (quietSince < 0) quietSince = rendered; if (rendered - quietSince > .05 * rate) break; }
                if (rendered > total * stepFrames + 4.0 * rate) break;
            }
        }
        long keep = Math.max((long) Math.ceil(total * stepFrames), rendered);
        float[] all = new float[(int) (keep * 2)];
        int at = 0;
        for (float[] b : parts) { int n = Math.min(b.length, all.length - at); if (n <= 0) break; System.arraycopy(b, 0, all, at, n); at += n; }
        // short fade at the very end so the file never clicks
        int fade = Math.min((int) (.01 * rate), (int) keep);
        for (int k = 0; k < fade; k++) { int f = (int) keep - 1 - k; float g = k / (float) fade; all[f * 2] *= g; all[f * 2 + 1] *= g; }
        return all;
    }
}
