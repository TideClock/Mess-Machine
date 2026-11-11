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
        /** SE-6: how repeat plays each hit. "cut" stops it before the next, "ring" lets it play out, "flow" plays it once and pulses its volume. */
        public String nrStyle = "cut";
        public Live copy() { Live l = new Live(); l.click = click; l.rec = rec; l.nr = nr; l.nrRate = nrRate; l.arp = arp; l.nrStyle = nrStyle; return l; }
    }

    public static final class PadSlot {
        public final int index;
        public final String key;
        public final Sample sample;
        public final SoundEdit edit;
        public final boolean audible;
        public final PadOpts opts;
        public PadSlot(int index, String key, Sample sample, SoundEdit edit, boolean audible) { this(index, key, sample, edit, audible, PadOpts.DEFAULT); }
        public PadSlot(int index, String key, Sample sample, SoundEdit edit, boolean audible, PadOpts opts) {
            this.index = index; this.key = key; this.sample = sample; this.edit = edit; this.audible = audible; this.opts = opts;
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
        final int[] lanePad, laneT;
        final float[][] laneV;
        Pat(int len, Track[] tracks, int[] lanePad, int[] laneT, float[][] laneV) { this.len = len; this.tracks = tracks; this.lanePad = lanePad; this.laneT = laneT; this.laneV = laneV; }
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
                pads[i] = new PadSlot(i, k, s, p.edit(k).copy(), p.audible(k), p.opt(i).copy());
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
                List<int[]> ids = new ArrayList<>();
                List<float[]> vals = new ArrayList<>();
                for (Map.Entry<String, float[]> e : P.motion.entrySet()) {
                    String[] f = e.getKey().split(":");
                    try { ids.add(new int[]{Integer.parseInt(f[0]), Integer.parseInt(f[1])}); vals.add(e.getValue().clone()); } catch (Exception ignored) {}
                }
                int[] lp = new int[ids.size()], lt = new int[ids.size()];
                for (int q = 0; q < lp.length; q++) { lp[q] = ids.get(q)[0]; lt[q] = ids.get(q)[1]; }
                pats[pi] = new Pat(len, tr.toArray(new Track[0]), lp, lt, vals.toArray(new float[0][]));
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
        boolean active, preview, hold, releasing, choked, dry;
        float[] d;
        int a, zEnd, choke, pad;
        double pos, len, inc, loopFrom, glideMul;
        long glideLeft;
        boolean loop;
        long delay, t, playLen, attF, fadeF, startAbs, chokeAt;
        float lvl, gl, gr, relFrom, chokeG;
        long relLeft, relLen;
        boolean filt;
        float b0, b1, b2, a1, a2, x1, x2, y1, y2;
        float px1, px2, py1, py2;
        float fxRvb, fxEcho, fxDirt, fxCrush, fxChorus, fxWob, fxWobRate, crushVal, volMul = 1;
        double wobPh, chPh, bendMul = 1, panX;
        float[] chBuf;
        int chW, crushCnt;
        float lastEnv;
        // grains (SE-1, SE-2, SE-5): two read heads, each fading in and out over one grain, half a grain apart.
        // inc moves the heads (the pitch), tInc moves time through the sound (the length).
        boolean grain, frozen, flow;
        double tPos, tInc, g0, g1, gp0, gp1, gStep, sus, srate;
        long pulseAt, pulseLen;
    }

    private final int rate;
    private final Voice[] pool = new Voice[96];
    private volatile Snapshot snap;
    private final ConcurrentLinkedQueue<Object[]> cmds = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<RecEvent> recs = new ConcurrentLinkedQueue<>();
    private final Sample clickHi, clickLo;
    private final PadSlot clickHiSlot, clickLoSlot;

    // clock state, audio thread only
    private boolean playing, paused, freeOn;
    private long frame;
    private double nextStepAt, freeNextAt;
    private int step, songPos, arpI;
    private long fired, stopAfter = -1;
    private final Map<Integer, Held> held = new LinkedHashMap<>();
    private final double[] lastP = new double[64];
    private volatile Voice lastVoice;
    private final java.util.Random rnd = new java.util.Random();

    // perform FX state (audio thread)
    /** True while the step clock fires under tape stop, so every hit slows like tape, whatever its pitch setting. */
    private boolean tapeNow;
    /** True while padDown starts flow notes: they freeze and hold like Sustain, whatever the sound's own Hold choice. */
    private boolean sustainNow;
    /** SE-7: the beat clock for latched beat pads while the transport is stopped. */
    private boolean beatOn;
    private double beatNextAt;
    private boolean fxStut, fxTape, fxOct, fxRev, fxCrush, fxDirt, fxRing, fxGate, fxEcho, xyOn, xyHP, filtSlow;
    private int stutA, stutB, lastStep;
    private double tapeAt;
    private float wCrush, wDirt, wRing, wGate, wSend;
    private double ringPh, ringHz = 220, gatePh, gateHz = 8;
    private final float[] dlyL, dlyR;
    private int dlyW;
    private float hpL, hpR, hpXL, hpXR;
    private double filtFreq = 20000, filtTarget = 20000, filtQ = .7, qTarget = .7;
    private float[] fzL = new float[4], fzR = new float[4];
    private float[] dryBuf = new float[0];
    private boolean bypassMaster;

    // live filter per pad (the strip under each piano): cutoff and resonance glide per block
    private final double[] pfFreq = new double[64], pfTarget = new double[64], pfQ = new double[64], pfQT = new double[64];
    private final boolean[] pfOn = new boolean[64], pfSlow = new boolean[64];
    private final float[][] pfC = new float[64][5];

    private double oct() { return fxOct ? .5 : 1; }

    private void fxSet(Snapshot S, String n, boolean on) {
        switch (n) {
            case "stutter":
                if (on && playing && !fxStut) { stutA = lastStep - (lastStep % 2); stutB = step; }
                fxStut = on && playing;
                break;
            case "tape":
                if (on && playing && !fxTape) tapeAt = nextStepAt;
                fxTape = on && playing;
                break;
            case "oct": fxOct = on; break;
            case "rev": fxRev = on; break;
            case "crush": fxCrush = on; break;
            case "dirty": fxDirt = on; break;
            case "ring": if (on && !fxRing) ringHz = 110 + rnd.nextDouble() * 440; fxRing = on; break;
            case "gate": fxGate = on; gateHz = 1.0 / (60.0 / Math.max(40, S.bpm) / 4); break;
            case "echo": fxEcho = on; break;
        }
    }

    /** Which effects are held right now, for the UI and tests. */
    public boolean fxActive() { return fxStut || fxTape || fxOct || fxRev || fxCrush || fxDirt || fxRing || fxGate || fxEcho || xyOn; }

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
        boolean mel, holdV, latched, beat, flow;
        int beats;
        double at;
        List<Voice> voices = new ArrayList<>();
    }

    public Engine(int rate) {
        this.rate = rate;
        for (int k = 0; k < pool.length; k++) pool[k] = new Voice();
        clickHi = tone("click-hi", 1760);
        clickLo = tone("click-lo", 1100);
        clickHiSlot = new PadSlot(-2, "click", clickHi, SoundEdit.DEFAULT, true);
        clickLoSlot = new PadSlot(-2, "click", clickLo, SoundEdit.DEFAULT, true);
        compAtk = (float) (1 - Math.exp(-1.0 / (.005 * rate)));
        compRel = (float) (1 - Math.exp(-1.0 / (.2 * rate)));
        dlyL = new float[(int) (2.0 * rate)];
        dlyR = new float[(int) (2.0 * rate)];
        java.util.Arrays.fill(pfFreq, 20000); java.util.Arrays.fill(pfTarget, 20000);
        java.util.Arrays.fill(lastP, Double.NaN);
        java.util.Arrays.fill(padRateFrom, 1); java.util.Arrays.fill(padRateTo, 1); java.util.Arrays.fill(padVol, 1);
        for (int c = 0; c < 4; c++) {
            combL[c] = new float[Math.max(1, COMB[c] * rate / 44100)];
            combR[c] = new float[Math.max(1, (COMB[c] + 23) * rate / 44100)];
        }
        for (int a = 0; a < 2; a++) {
            apL[a] = new float[Math.max(1, ALLP[a] * rate / 44100)];
            apR[a] = new float[Math.max(1, (ALLP[a] + 23) * rate / 44100)];
        }
        sEchoL = new float[(int) (2.2 * rate)];
        sEchoR = new float[(int) (2.2 * rate)];
        java.util.Arrays.fill(pfQ, .7); java.util.Arrays.fill(pfQT, .7);
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
    public void down(int id, int pad, int[] pitches, boolean mel) { down(id, pad, pitches, mel, false); }
    /** latch = keep sounding (and repeating) after the finger lifts, until unlatch(id). */
    public void down(int id, int pad, int[] pitches, boolean mel, boolean latch) { cmds.add(new Object[]{"down", id, pad, pitches, mel, System.nanoTime(), latch}); }
    public void unlatch(int id) { cmds.add(new Object[]{"unlatch", id}); }
    public void up(int id) { cmds.add(new Object[]{"up", id, System.nanoTime()}); }

    /** Hear a sound with its doctor settings, outside the mess (browsing, editing, toggling a step). */
    public void preview(Sample s, SoundEdit e, int semis) { preview(s, e, semis, false); }
    /** hold = keep looping the trim until stopPreviews(), for the doctor's loop preview. */
    public void preview(Sample s, SoundEdit e, int semis, boolean hold) { if (s != null) cmds.add(new Object[]{"preview", s, e == null ? SoundEdit.DEFAULT : e.copy(), semis, hold, System.nanoTime()}); }

    /* ---------- perform FX, held from the UI ---------- */
    public void fx(String name, boolean on) { cmds.add(new Object[]{"fx", name, on}); }
    /** The filter pad: x 0..1 is cutoff, y 0..1 is resonance (top = more). on = finger down. */
    public void xy(float x, float y, boolean on) { cmds.add(new Object[]{"xy", x, y, on}); }
    public void xyHighPass(boolean hp) { cmds.add(new Object[]{"xyhp", hp}); }
    /**
     * A live filter on one pad only (every voice of that pad, held notes, repeats, the steps).
     * cut 0..1 is cutoff (1 = open), res 0..1 is resonance. on = false glides it open again.
     */
    public void padFilter(int pad, float cut, float res, boolean on) { cmds.add(new Object[]{"pf", pad, cut, res, on}); }
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
        if (dryBuf.length < frames * 2) dryBuf = new float[frames * 2];
        java.util.Arrays.fill(dryBuf, 0, frames * 2, 0f);
        if (rvbIn.length < frames * 2) { rvbIn = new float[frames * 2]; echoIn = new float[frames * 2]; }
        java.util.Arrays.fill(rvbIn, 0, frames * 2, 0f);
        java.util.Arrays.fill(echoIn, 0, frames * 2, 0f);
        padFilters(frames);
        padBlock(S, frames);
        int done = 0;
        while (done < frames) {
            int chunk = frames - done;
            if (playing) {
                long until = (long) Math.ceil(nextStepAt) - frame;
                if (until <= 0) { fireStep(S); continue; }
                chunk = (int) Math.min(chunk, until);
            } else if (freeOn || beatOn) {
                long uf = freeOn ? (long) Math.ceil(freeNextAt) - frame : Long.MAX_VALUE;
                long ub = beatOn ? (long) Math.ceil(beatNextAt) - frame : Long.MAX_VALUE;
                if (uf <= 0) { fireFree(S); continue; }
                if (ub <= 0) { fireBeat(S); continue; }
                chunk = (int) Math.min(chunk, Math.min(uf, ub));
            }
            mix(out, done, chunk);
            done += chunk;
            frame += chunk;
        }
        soundBuses(out, frames);
        effects(S, out, frames);
        for (int k = 0; k < frames * 2; k++) out[k] += dryBuf[k];
        if (!bypassMaster) master(out, frames);
        uiFrame = frame - frames;
        uiNanos = nanos;
        uiPlaying = playing;
    }

    private void padFilters(int frames) {
        for (int p = 0; p < 64; p++) {
            if (!pfOn[p] && pfTarget[p] >= 19999 && pfQT[p] <= .71) continue;
            double g = 1 - Math.exp(-frames / ((pfSlow[p] ? .12 : .025) * rate));
            pfFreq[p] += (pfTarget[p] - pfFreq[p]) * g;
            pfQ[p] += (pfQT[p] - pfQ[p]) * g;
            if (pfFreq[p] >= 19900 && pfTarget[p] >= 19999) { pfFreq[p] = 20000; pfQ[p] = .7; pfOn[p] = false; continue; }
            double f = Math.min(pfFreq[p], rate * .45), w = 2 * Math.PI * f / rate, cs = Math.cos(w), al = Math.sin(w) / (2 * pfQ[p]), a0 = 1 + al;
            float[] c = pfC[p];
            c[0] = (float) ((1 - cs) / 2 / a0); c[1] = (float) ((1 - cs) / a0); c[2] = c[0];
            c[3] = (float) (-2 * cs / a0); c[4] = (float) ((1 - al) / a0);
            pfOn[p] = true;
        }
    }

    /** Is a pad's live filter closed at all right now (for the UI and tests). */
    public boolean padFiltered(int pad) { return pad >= 0 && pad < 64 && pfOn[pad]; }

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
                fxStut = false; fxTape = false;
                playing = false; paused = false; step = 0; songPos = 0; freeOn = !held.isEmpty() && S.live.nr;
                hHead = -1;
                break;
            case "panic":
                for (Voice v : pool) v.active = false;
                for (float[] m : pMod) java.util.Arrays.fill(m, 0f);
                for (float[] m : tFx) java.util.Arrays.fill(m, 0f);
                held.clear(); freeOn = false; beatOn = false;
                break;
            case "down": lastHitWaitNanos = renderNanos - (Long) c[5]; hits++; padDown(S, (Integer) c[1], (Integer) c[2], (int[]) c[3], (Boolean) c[4], (Long) c[5], renderNanos, c.length > 6 && (Boolean) c[6]); break;
            case "unlatch": {
                Held h = held.remove((Integer) c[1]);
                if (h != null) for (Voice v : h.voices) startRelease(v, .03f);
                if (held.isEmpty()) { arpI = 0; freeOn = false; }
                break;
            }
            case "up": padUp(S, (Integer) c[1], (Long) c[2], renderNanos); break;
            case "preview": {
                Sample s = (Sample) c[1];
                if (c.length > 5) { lastHitWaitNanos = renderNanos - (Long) c[5]; hits++; }
                for (Voice v : pool) if (v.active && v.preview) v.active = false;
                boolean hold = c.length > 4 && (Boolean) c[4];
                SoundEdit pe = (SoundEdit) c[2];
                if (hold) { pe = pe.copy(); pe.loop = true; }
                Voice v = trigger(new PadSlot(-1, s.key, s, pe, true), Math.pow(2, (Integer) c[3] / 12.0), 0, false, .85f, 0, hold, false);
                if (v != null) { v.preview = true; v.dry = true; }
                break;
            }
            case "stopprev": for (Voice v : pool) if (v.active && v.preview) startRelease(v, .03f); break;
            case "fx": fxSet(S, (String) c[1], (Boolean) c[2]); break;
            case "mod": {
                int pd = (Integer) c[1], t = (Integer) c[2];
                if (pd >= 0 && pd < 64 && t >= 0 && t < NT) {
                    pMod[pd][t] = (Float) c[3];
                    modTouch[pd][t] = frame;
                    if (t == T_FILTER || t == T_RES) filterFromMod(pd);
                }
                break;
            }
            case "fxt": {
                int k = fxIndex((String) c[1]), t = (Integer) c[2];
                if (k >= 0 && t >= 0 && t < tFx.length) tFx[t][k] = Math.max(0, Math.min(1, (Float) c[3]));
                break;
            }
            case "famt": { int k = fxIndex((String) c[1]); if (k >= 0) mixAmt[k] = Math.max(0, Math.min(1, (Float) c[2])); break; }
            case "gatelen": gateSteps = Math.max(1, (Integer) c[1]); gateHz = 1.0 / (60.0 / Math.max(40, S.bpm) / 4 * gateSteps); break;
            case "nmod": {
                Held h = held.get((Integer) c[1]);
                if (h != null) for (Voice v : h.voices) { v.bendMul = Math.pow(2, (Float) c[2] / 12.0); v.volMul = Math.max(0, (Float) c[3]); }
                break;
            }
            case "xy":
                xyOn = (Boolean) c[3];
                if (xyOn) {
                    double x = (Float) c[1], y = (Float) c[2];
                    filtTarget = xyHP ? 20 * Math.pow(6000.0 / 20, x) : 120 * Math.pow(18000.0 / 120, x);
                    qTarget = .7 + (1 - y) * 14;
                    filtSlow = false;
                } else { filtTarget = xyHP ? 10 : 20000; qTarget = .7; filtSlow = true; }
                break;
            case "pf": {
                int pd = (Integer) c[1];
                if (pd < 0 || pd >= 64) break;
                boolean on = (Boolean) c[4];
                double cut = Math.max(0, Math.min(1, (Float) c[2])), res = Math.max(0, Math.min(1, (Float) c[3]));
                pfTarget[pd] = on ? 90 * Math.pow(20000.0 / 90, cut) : 20000;
                pfQT[pd] = on ? .7 + res * 13 : .7;
                pfSlow[pd] = !on;
                break;
            }
            case "xyhp": xyHP = (Boolean) c[1]; filtFreq = filtTarget = xyHP ? 10 : 20000; filtQ = qTarget = .7; fzL = new float[4]; fzR = new float[4]; break;
        }
    }

    /** Start a voice semis away from its pitch and slide it home over sec seconds. */
    private void glide(Voice v, double semis, double sec) {
        if (Math.abs(semis) < .01) return;
        long n = Math.max(1, Math.round(sec * rate));
        double target = v.inc;
        v.inc = target * Math.pow(2, semis / 12.0);
        v.glideMul = Math.pow(target / v.inc, 1.0 / n);
        v.glideLeft = n;
    }

    /** The playback rate of the newest voice, for tests. */
    public double lastInc() { Voice v = lastVoice; return v == null ? 0 : v.inc; }

    private void releaseAll(float sec) { for (Voice v : pool) if (v.active && !v.preview) startRelease(v, sec); }

    private void padDown(Snapshot S, int id, int pad, int[] pitches, boolean mel, long nanos, long renderNanos, boolean latch) {
        PadSlot ps = pad >= 0 && pad < S.pads.length ? S.pads[pad] : null;
        if (ps == null) return;
        Held h = new Held();
        h.id = id; h.pad = ps; h.pitches = pitches; h.mel = mel;
        h.latched = latch;
        // SE-5: Sustain holds under a finger too, not only under Hold
        h.holdV = (mel && ps.edit.holds()) || "sustain".equals(ps.edit.held) || latch;
        // SE-7: a latched beat pad plays each copy whole and starts it again on the beat; nothing to hold
        h.beat = latch && "beat".equals(ps.edit.held);
        if (h.beat) h.holdV = false;
        // SE-6: flow plays each note once, held, and repeat pulses its volume (arpeggios keep cut or ring)
        Live L0 = S.live;
        h.flow = L0.nr && "flow".equals(L0.nrStyle) && "rep".equals(L0.arp);
        if (h.flow) h.holdV = true;
        h.at = playing ? nowStep(S, nanos, renderNanos) : 0;
        Held old = held.remove(id);
        if (old != null) for (Voice v : old.voices) startRelease(v, .02f);
        // glide: slide in from the pad's last note (legato: only while another note of this pad is down)
        double from = Double.NaN;
        double gT = pad >= 0 && pad < 64 && pMod[pad][T_GLIDE] > .001 ? pMod[pad][T_GLIDE] : ps.opts.glide ? ps.opts.glideT : 0;
        if (mel && pad >= 0 && pad < lastP.length && gT > .001 && !Double.isNaN(lastP[pad])) {
            boolean overlap = false;
            for (Held o : held.values()) if (o.pad.index == pad) { overlap = true; break; }
            if (!ps.opts.legato || overlap) from = lastP[pad];
        }
        if (mel && pad >= 0 && pad < lastP.length && pitches.length > 0) lastP[pad] = pitches[0];
        held.put(id, h);
        Live L = S.live;
        boolean arpLive = L.nr && !"rep".equals(L.arp);
        if ((!(L.nr && playing) && !arpLive) || h.flow) {
            float g = mel ? Theory.chordGain(pitches.length) : .85f;
            sustainNow = h.flow;
            for (int p : pitches) {
                Voice v = trigger(ps, oct() * Math.pow(2, p / 12.0), 0, false, g, 0, h.holdV);
                if (v == null) continue;
                if (h.flow) { v.flow = true; v.pulseAt = frame; v.pulseLen = Math.round(Math.max(.5, L.nrRate) * spb(S)); }
                // Hold keeps a one-shot going: loop its trim, or freeze its tail
                if (!Double.isNaN(from)) glide(v, from + (p - pitches[0]) - p, gT);
                String hm = ps.edit.held;
                if (latch && !v.loop && !h.flow && !"once".equals(hm) && !"sustain".equals(hm) && !"beat".equals(hm)) {
                    v.loop = true;
                    if ("tail".equals(ps.edit.held)) v.loopFrom = Math.max(v.len * .7, v.len - .12 * ps.sample.rate);
                }
                h.voices.add(v);
            }
            sustainNow = false;
        }
        if (h.beat && !playing && !beatOn) { beatOn = true; beatNextAt = frame + 4 * spb(S); }
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
        Held h0 = held.get(id);
        if (h0 == null || h0.latched) return;
        Held h = held.remove(id);
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
            if (arpNext(S)) trigger(arpHeld.pad, oct() * Math.pow(2, arpPitch / 12.0), dur, true, .85f, 0, false);
            return;
        }
        boolean ring = "ring".equals(S.live.nrStyle);
        for (Held h : held.values()) {
            if (h.flow) { pulse(h, 0, Math.round(Math.max(.5, S.live.nrRate) * spb(S))); continue; }
            float g = Theory.chordGain(h.pitches.length);
            for (int p : h.pitches) trigger(h.pad, oct() * Math.pow(2, p / 12.0), ring ? 0 : dur, !ring && (h.mel || p != 0), g, 0, false);
        }
    }

    /** SE-6 flow: one repeat hit is a fresh pulse of volume on the notes already playing. */
    private void pulse(Held h, long delay, long len) {
        for (Voice v : h.voices) if (v.active && v.flow) { v.pulseAt = frame + delay; v.pulseLen = Math.max(1, len); }
    }

    // the arpeggio's notes, sorted low to high in place (no memory made on the audio thread)
    private final Held[] arpH = new Held[256];
    private final int[] arpP = new int[256];
    private Held arpHeld;
    private int arpPitch;

    /** Picks the next arpeggio note into arpHeld / arpPitch; false when nothing is held. */
    private boolean arpNext(Snapshot S) {
        int n = 0;
        for (Held h : held.values()) for (int p : h.pitches) {
            if (n >= arpP.length) break;
            int j = n++;
            while (j > 0 && arpP[j - 1] > p) { arpP[j] = arpP[j - 1]; arpH[j] = arpH[j - 1]; j--; }
            arpP[j] = p; arpH[j] = h;
        }
        if (n == 0) return false;
        int i;
        String m = S.live.arp;
        if ("down".equals(m)) i = n - 1 - arpI % n;
        else if ("updown".equals(m)) { int per = Math.max(1, 2 * n - 2), x = arpI % per; i = x < n ? x : per - x; }
        else if ("rand".equals(m)) i = rnd.nextInt(n);
        else i = arpI % n;
        arpI++;
        i = Math.max(0, Math.min(n - 1, i));
        arpHeld = arpH[i]; arpPitch = arpP[i];
        java.util.Arrays.fill(arpH, 0, n, null);
        return true;
    }

    private void fireStep(Snapshot S) {
        int pi = playingPat(S);
        Pat P = pi >= 0 ? S.pats[pi] : null;
        int len = P != null ? P.len : 16;
        if (step >= len) step = 0;
        if (stopAfter >= 0 && fired >= stopAfter) { playing = false; return; }
        double spb = spb(S), rateMul = oct();
        boolean mute = false;
        if (fxTape) {
            double f = Math.min(1, Math.max(0, (nextStepAt - tapeAt) / (1.1 * rate)));
            rateMul *= 1 - .82 * f;
            spb = spb / Math.max(.18, 1 - .82 * f);
            mute = f >= .97;
        }
        int s = step;
        if (fxStut) { s = stutA + Math.floorMod(step - stutB, 2); if (s >= len) s = 0; }
        lastStep = s;
        final int s0 = s;
        double at = nextStepAt - frame + (s % 2 == 1 ? S.swing / 100.0 * spb * .5 : 0);
        long delay = Math.max(0, Math.round(at));
        tapeNow = fxTape;
        if (P != null && !mute) {
            for (Track t : P.tracks) {
                if (!t.pad.audible) continue;
                int ti = t.pad.index, ts = s0;
                if (ti >= 0 && ti < 64 && padStut[ti] && !fxStut) { ts = pStutA[ti] + Math.floorMod(step - pStutB[ti], 2); if (ts >= len) ts = 0; }
                if (t.steps[ts]) trigger(t.pad, rateMul, t.gap[ts] * spb / rate, false, .85f, delay, false);
                int[] nt = t.notes[ts];
                for (int k = 0; k < nt.length; k += 2) trigger(t.pad, rateMul * Math.pow(2, nt[k] / 12.0), (fxStut ? Math.min(nt[k + 1], 2) : nt[k + 1]) * spb / rate, true, .78f, delay, false);
            }
        }
        tapeNow = false;
        // recorded XY moves play back, unless a finger is moving that control right now
        if (P != null) for (int q = 0; q < P.lanePad.length; q++) {
            int lp = P.lanePad[q], lt = P.laneT[q];
            if (lp < 0 || lp >= 64 || lt < 0 || lt >= NT) continue;
            float v = P.laneV[q][s];
            if (Float.isNaN(v) || frame - modTouch[lp][lt] < .25 * rate) continue;
            pMod[lp][lt] = v;
            if (lt == T_FILTER || lt == T_RES) filterFromMod(lp);
        }
        if (s % 4 == 0) beatTick(delay);
        Live L = S.live;
        if (L.click && s % 4 == 0) { Voice cv = trigger(s == 0 ? clickHiSlot : clickLoSlot, 1, 0, false, 1f, delay, false, false); if (cv != null) cv.dry = true; }
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

    /** SE-7: on each beat, latched beat pads start their sound again every beatN beats; the last copy rings on. */
    private boolean beatTick(long delay) {
        boolean any = false;
        for (Held h : held.values()) {
            if (!h.beat) continue;
            any = true;
            h.beats++;
            int n = Math.max(1, h.pad.edit.beatN);
            if (h.beats % n != 0) continue;
            float g = h.mel ? Theory.chordGain(h.pitches.length) : .85f;
            for (int p : h.pitches) trigger(h.pad, oct() * Math.pow(2, p / 12.0), 0, false, g, delay, false);
        }
        return any;
    }

    private void fireBeat(Snapshot S) {
        if (!beatTick(0)) { beatOn = false; return; }
        beatNextAt += 4 * spb(S);
    }

    private void nrTick(Snapshot S, long delay, int s, int pi, boolean half, double every, double spb) {
        if ("rep".equals(S.live.arp)) {
            for (Held h : held.values()) {
                if (h.flow) { pulse(h, delay, Math.round(spb * (every < 1 ? .5 : every))); continue; }
                float g = Theory.chordGain(h.pitches.length);
                for (int p : h.pitches) nrOne(S, h, p, delay, s, pi, half, g, every, spb);
            }
        } else {
            if (arpNext(S)) nrOne(S, arpHeld, arpPitch, delay, s, pi, half, .85f, every, spb);
        }
    }

    private void nrOne(Snapshot S, Held h, int p, long delay, int s, int pi, boolean half, float g, double every, double spb) {
        boolean ring = "ring".equals(S.live.nrStyle);
        trigger(h.pad, oct() * Math.pow(2, p / 12.0), ring ? 0 : spb * .9 * Math.max(1, every >= 1 ? every : 1) / rate, !ring && (h.mel || p != 0), g, delay, false);
        if (S.live.rec && pi >= 0 && !half) {
            if (h.mel) recs.add(new RecEvent(RecEvent.NOTES, pi, h.pad.key, s, new int[]{p}, every >= 2 ? 2 : 1));
            else recs.add(new RecEvent(RecEvent.STEP, pi, h.pad.key, s, null, 1));
        }
    }

    /* ---------- starting one voice, voiceEd() from the site ---------- */

    private Voice trigger(PadSlot ps, double rateMul, double durSec, boolean gate, float gain, long delay, boolean hold) {
        boolean flip = fxRev || (ps.index >= 0 && ps.index < 64 && padRev[ps.index]);
        return trigger(ps, rateMul, durSec, gate, gain, delay, hold, flip);
    }

    private Voice trigger(PadSlot ps, double rateMul, double durSec, boolean gate, float gain, long delay, boolean hold, boolean flip) {
        SoundEdit e = ps.edit;
        Sample s = ps.sample;
        int N = s.data.length;
        if (N < 2) return null;
        boolean rev = e.rev != flip;
        float[] d = rev ? s.reversedData() : s.data;
        double a = e.st * N, z = e.en * N;
        float[] pm = ps.index >= 0 && ps.index < 64 ? pMod[ps.index] : null;
        if (pm != null && pm[T_START] > 0) a = Math.min(z - .005 * s.rate, a + (z - a) * Math.min(.95, pm[T_START]));
        if (rev) { double a2 = N - z; z = N - a; a = a2; }
        double len = Math.max(.005 * s.rate, z - a);
        double tuneR = Math.pow(2, (e.tune + e.fine / 100.0) / 12.0);
        double r = Math.max(.05, rateMul * tuneR);
        double lvl = gain * e.vol * (e.norm ? Math.min(10, .95 / s.regionPeak(e.st, e.en)) : 1);
        // SE-1: Stretch (tape) moves time at the pitch's speed; Grain keeps time at 1; auto keeps the length of notes
        // played as keys, chords and roll notes, and lets the Doctor's own Tune stay tape. Tape stop is always tape.
        boolean sustain = hold && ("sustain".equals(e.held) || sustainNow);
        double timeR;
        if (tapeNow || "stretch".equals(e.pitch)) timeR = r;
        else if ("grain".equals(e.pitch)) timeR = 1;
        else timeR = Math.abs(rateMul - 1) > .0006 ? Math.max(.05, tuneR) : r;
        boolean grain = sustain || Math.abs(timeR - r) > 1e-9;
        // SE-3: a baked version already has the new pitch at the old length, so it plays like tape at the time rate
        double incR = r;
        if (grain && !sustain && !rev) {
            float[] bk = s.bakedFor(r / timeR);
            if (bk != null && bk.length == N) { d = bk; incR = timeR; grain = false; bakedPlays++; }
        }
        if (grain && !sustain && ps.index >= 0 && ps.index < 64) grainUsed[ps.index] = true;
        double natural = len / s.rate / timeR;
        boolean g = "gate".equals(e.mode) || gate;
        double play = hold ? 600 : e.loop ? (durSec > 0 ? durSec : natural) : (g && durSec > 0 ? Math.min(durSec, natural) : natural);
        double att = Math.min(e.att + (pm != null ? Math.max(0, pm[T_ATT]) : 0), play * .9);
        double fade = Math.min(Math.max(e.rel + (pm != null ? Math.max(0, pm[T_REL]) : 0), .004), Math.max(.002, play - att));

        Voice v = freeVoice();
        v.active = true; v.preview = false; v.dry = false; v.hold = hold; v.releasing = false; v.choked = false;
        v.d = d; v.a = (int) Math.max(0, Math.floor(a)); v.len = len; v.zEnd = (int) Math.min(N, Math.ceil(a + len));
        v.pos = 0; v.inc = incR * s.rate / rate; v.loop = e.loop; v.loopFrom = 0; v.glideLeft = 0; v.glideMul = 1;
        v.grain = grain; v.frozen = false; v.flow = false; v.pulseAt = -1; v.srate = s.rate;
        if (grain) {
            v.tPos = 0; v.tInc = timeR * s.rate / rate;
            v.gStep = 1.0 / Math.max(64, GRAIN_SEC * rate);
            // the first head starts at its loudest so the attack is not faded in; the second waits half a grain
            v.g0 = 0; v.gp0 = .5; v.g1 = 0; v.gp1 = 0;
            // SE-5: where a held sustain freezes, partway in, after the attack
            v.sus = sustain ? Math.min(Math.max(Math.min(.06 * s.rate, len * .45), len * .45), 1.5 * s.rate) : -1;
        }
        lastVoice = v;
        v.delay = delay; v.t = 0;
        v.playLen = Math.max(1, Math.round(play * rate)); v.attF = Math.round(att * rate); v.fadeF = Math.max(1, Math.round(fade * rate));
        v.lvl = (float) lvl;
        double x = (e.pan + 1) / 2;
        v.gl = (float) Math.min(1, Math.sqrt(2) * Math.cos(x * Math.PI / 2));
        v.gr = (float) Math.min(1, Math.sqrt(2) * Math.sin(x * Math.PI / 2));
        v.pad = ps.index; v.choke = e.choke; v.startAbs = frame + delay; v.chokeAt = Long.MAX_VALUE; v.chokeG = 1; v.lastEnv = 0;
        v.relLeft = 0; v.relLen = 1; v.relFrom = 0;
        v.px1 = v.px2 = v.py1 = v.py2 = 0;
        v.fxRvb = (float) e.rvb; v.fxEcho = (float) e.echo; v.fxDirt = (float) e.dirt; v.fxCrush = (float) e.crush;
        v.fxChorus = (float) e.chorus; v.fxWob = (float) e.wob; v.fxWobRate = (float) e.wobRate;
        v.wobPh = 0; v.chPh = 0; v.chW = 0; v.crushCnt = 0; v.crushVal = 0; v.bendMul = 1; v.volMul = 1; v.panX = e.pan;
        if (v.chBuf != null) java.util.Arrays.fill(v.chBuf, 0f);
        if (e.rvb > 0) rvbSizeT = (float) e.rvbSize;
        if (e.echo > 0) sEchoDiv = e.echoT;
        triggers++;
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
        double gateDiv = Math.max(1, spbNow * gateSteps);
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
            int p = v.pad >= 0 && v.pad < 64 ? v.pad : -1;
            // what this pad adds right now (XY, per-pad and per-bus effects)
            float dirt = v.fxDirt + (p >= 0 ? padDirt[p] : 0), crush = v.fxCrush + (p >= 0 ? padCrush[p] : 0);
            float chorus = v.fxChorus + (p >= 0 ? padChorus[p] : 0), wob = v.fxWob + (p >= 0 ? padWob[p] : 0);
            float gate = p >= 0 ? padGate[p] : 0, ring = p >= 0 ? padRing[p] : 0;
            float rvbS = Math.min(1, v.fxRvb + (p >= 0 ? padRvb[p] : 0)), echoS = Math.min(1, v.fxEcho + (p >= 0 ? padEcho[p] : 0));
            float vol = (p >= 0 ? padVol[p] : 1) * v.volMul;
            float gl = v.gl, gr = v.gr;
            if (p >= 0 && padPan[p] != 0) {
                double x = Math.max(0, Math.min(1, (v.panX + padPan[p] + 1) / 2));
                gl = (float) Math.min(1, Math.sqrt(2) * Math.cos(x * Math.PI / 2));
                gr = (float) Math.min(1, Math.sqrt(2) * Math.sin(x * Math.PI / 2));
            }
            if (dirt > 1) dirt = 1; if (crush > 1) crush = 1; if (chorus > 1) chorus = 1; if (wob > 1) wob = 1;
            float drive = 1 + dirt * 9, driveN = (float) (1 / Math.tanh(drive));
            int hold = 1 + (int) (crush * 11);
            float step = crush > 0 ? (float) Math.pow(2, -(15 - crush * 12)) : 0;
            if (chorus > 0 && v.chBuf == null) v.chBuf = new float[2048];
            double wobInc = 2 * Math.PI * (.3 + v.fxWobRate * 5.7) / rate, chInc = 2 * Math.PI * .8 / rate;
            float[] dst = v.dry ? dryBuf : out;
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
                float smp;
                if (v.grain) {
                    if (!v.loop && !v.frozen && v.tPos >= v.len) { v.active = false; break; }
                    smp = HANN[(int) (v.gp0 * 1024)] * gRead(v, d, v.g0) + HANN[(int) (v.gp1 * 1024)] * gRead(v, d, v.g1);
                } else {
                    double pos = v.pos;
                    if (v.loop) { double span = Math.max(1, v.len - v.loopFrom); while (pos >= v.len) pos -= span; v.pos = pos; }
                    int i = v.a + (int) pos;
                    if (i >= v.zEnd - 1 || i >= d.length - 1) {
                        if (!v.loop) { v.active = false; break; }
                        i = Math.min(v.zEnd, d.length) - 2;
                    }
                    double fr = pos - Math.floor(pos);
                    smp = (float) (d[i] + (d[i + 1] - d[i]) * fr);
                }
                // SE-6 flow: repeat pulses the volume of one long note instead of restarting it
                if (v.flow) {
                    float pf;
                    if (v.pulseAt < 0) pf = .12f;
                    else {
                        // a 2 ms rise from the floor to full, then a fall back to the floor over the pulse
                        long dt = frame + k - v.pulseAt;
                        if (dt < 0) pf = .12f;
                        else if (dt < 96) pf = .12f + .88f * (dt / 96f);
                        else pf = .12f + .88f * (float) Math.max(0, 1 - (dt - 96) / (double) Math.max(1, v.pulseLen));
                    }
                    env *= pf;
                }
                if (v.filt) {
                    float y = v.b0 * smp + v.b1 * v.x1 + v.b2 * v.x2 - v.a1 * v.y1 - v.a2 * v.y2;
                    v.x2 = v.x1; v.x1 = smp; v.y2 = v.y1; v.y1 = y; smp = y;
                }
                if (p >= 0) {
                    if (pfOn[p]) {
                        float[] c = pfC[p];
                        float y = c[0] * smp + c[1] * v.px1 + c[2] * v.px2 - c[3] * v.py1 - c[4] * v.py2;
                        v.px2 = v.px1; v.px1 = smp; v.py2 = v.py1; v.py1 = y; smp = y;
                    } else { v.px2 = v.px1 = v.py2 = v.py1 = smp; }
                }
                // the doctor's effects and the pad's live ones
                if (dirt > 0) smp = (float) Math.tanh(smp * drive) * driveN * (1 - dirt * .35f);
                if (crush > 0) {
                    if (--v.crushCnt <= 0) { v.crushCnt = hold; v.crushVal = Math.round(smp / step) * step; }
                    smp = v.crushVal;
                }
                if (ring > 0) smp = smp * (1 - ring) + smp * (float) Math.sin(2 * Math.PI * ringHz * (frame + k) / rate) * ring;
                if (gate > 0 && ((frame + k) % (long) gateDiv) >= gateDiv / 2) smp *= 1 - gate;
                smp *= env * vol;
                float sl = smp, sr = smp;
                if (chorus > 0) {
                    float[] cb = v.chBuf;
                    cb[v.chW & 2047] = smp;
                    double m = Math.sin(v.chPh);
                    double dl = (.012 + .006 * m) * rate, dr = (.012 - .006 * m) * rate;
                    float tl = chRead(cb, v.chW - dl), tr = chRead(cb, v.chW - dr);
                    v.chW++;
                    v.chPh += chInc;
                    sl = smp * (1 - .5f * chorus) + tl * .7f * chorus;
                    sr = smp * (1 - .5f * chorus) + tr * .7f * chorus;
                }
                int o = (off + k) * 2;
                float ol = sl * gl, or = sr * gr;
                dst[o] += ol;
                dst[o + 1] += or;
                if (rvbS > 0) { rvbIn[o] += ol * rvbS; rvbIn[o + 1] += or * rvbS; }
                if (echoS > 0) { echoIn[o] += ol * echoS; echoIn[o + 1] += or * echoS; }
                double rm = v.bendMul;
                if (p >= 0) rm *= padRateFrom[p] + (padRateTo[p] - padRateFrom[p]) * ((frame + k - blockStart) / (double) Math.max(1, blockLen));
                if (wob > 0) { rm *= 1 + wob * .025 * Math.sin(v.wobPh); v.wobPh += wobInc; }
                if (v.grain) {
                    double pi = v.inc * rm;
                    v.g0 += pi; v.g1 += pi;
                    v.gp0 += v.gStep; v.gp1 += v.gStep;
                    if (!v.frozen) {
                        v.tPos += v.tInc;
                        if (v.loop && v.tPos >= v.len) v.tPos -= Math.max(1, v.len - v.loopFrom);
                        if (v.sus >= 0 && !v.releasing && v.tPos >= v.sus) { v.frozen = true; v.tPos = v.sus; }
                    }
                    if (v.gp0 >= 1) { v.gp0 -= 1; v.g0 = grainStart(v, d, v.g1); }
                    if (v.gp1 >= 1) { v.gp1 -= 1; v.g1 = grainStart(v, d, v.g0); }
                } else v.pos += v.inc * rm;
                if (v.glideLeft > 0) { v.inc *= v.glideMul; v.glideLeft--; }
                v.t++;
            }
        }
    }

    /** Grain length for Grain pitch and Sustain: long enough for low notes, short enough to keep attacks. */
    static final double GRAIN_SEC = .05;
    /** One grain's fade in and out: sin squared, so two heads half a grain apart always add up to 1. */
    private static final float[] HANN = new float[1025];
    /** Scratch for grainStart, audio thread only. */
    private final float[] gRef = new float[32];
    static { for (int i = 0; i <= 1024; i++) { double s = Math.sin(Math.PI * i / 1024.0); HANN[i] = (float) (s * s); } }

    /** A grain head's sample at position h (source samples from the trim start); silent outside the sound unless it loops. */
    private static float gRead(Voice v, float[] d, double h) {
        if (v.loop || v.frozen) { double span = Math.max(1, v.len - v.loopFrom); while (h >= v.len) h -= span; }
        if (h < 0 || h >= v.len - 1) return 0f;
        int i = v.a + (int) h;
        if (i < 0 || i >= d.length - 1 || i >= v.zEnd - 1) return 0f;
        double fr = h - Math.floor(h);
        return (float) (d[i] + (d[i + 1] - d[i]) * fr);
    }

    /**
     * Where a head starts its next grain: where time is now (or around the frozen point, with a little drift so a
     * sustain never buzzes), nudged by up to 8 ms to where it best lines up with the grain fading out, so the two
     * don't cancel each other where they overlap (the warble of a plain grain shifter on tones).
     */
    private double grainStart(Voice v, float[] d, double other) {
        double x = v.frozen ? v.sus + (rnd.nextDouble() - .5) * .024 * v.srate : v.tPos;
        // heads that read at time's own speed are already in line: nothing to search
        if (!v.frozen && Math.abs(v.inc - v.tInc) < 1e-9) return x;
        int r = (int) Math.min(.008 * v.srate, 400);
        double inc = Math.max(.25, v.inc) * 8;
        // what the fading grain is about to play, read once
        for (int n = 0; n < 32; n++) gRef[n] = gRead(v, d, other + n * inc);
        double best = x, bestC = -1e30;
        double span = 31 * inc;
        // coarse pass every 4 samples, then the 3 either side of the winner
        for (int pass = 0; pass < 2; pass++) {
            double c0 = best;
            int lo = pass == 0 ? -r : -3, hi = pass == 0 ? r : 3, stp = pass == 0 ? 4 : 1;
            for (int dl = lo; dl <= hi; dl += stp) {
                double cand = (pass == 0 ? x : c0) + dl;
                if (cand < 0) continue;
                double c = 0;
                if (cand + span < v.len - 2 && v.a + cand + span < Math.min(v.zEnd, d.length) - 2) {
                    // all inside the sound: plain reads, no wrapping or bounds to check
                    double at = v.a + cand;
                    for (int n = 0; n < 32; n++) c += gRef[n] * d[(int) (at + n * inc)];
                } else for (int n = 0; n < 32; n++) c += gRef[n] * gRead(v, d, cand + n * inc);
                if (c > bestC) { bestC = c; best = cand; }
            }
        }
        return Math.max(0, best);
    }

    private static float chRead(float[] b, double at) {
        int i = (int) Math.floor(at);
        float f = (float) (at - i);
        float a = b[i & 2047], c = b[(i + 1) & 2047];
        return a + (c - a) * f;
    }

    /* ---------- the sound effects' two shared spaces: a reverb and a tempo echo ---------- */

    private static final int[] COMB = {1116, 1188, 1277, 1356}, ALLP = {556, 441};
    private final float[][] combL = new float[4][], combR = new float[4][], apL = new float[2][], apR = new float[2][];
    private final int[] combI = new int[4], apI = new int[2];
    private final float[] combZL = new float[4], combZR = new float[4];
    private float rvbSize = .5f, rvbSizeT = .5f;
    private final float[] sEchoL, sEchoR;
    private int sEchoW;
    private double sEchoT = -1;
    private int sEchoDiv = 1;
    private float[] rvbIn = new float[0], echoIn = new float[0];
    private float busLevel;

    private void soundBuses(float[] out, int frames) {
        float peak = 0;
        for (int k = 0; k < frames * 2; k++) peak = Math.max(peak, Math.max(Math.abs(rvbIn[k]), Math.abs(echoIn[k])));
        if (peak < 1e-7f && busLevel < 1e-6f) return;
        busLevel = 0;
        rvbSize += (rvbSizeT - rvbSize) * .1f;
        float fb = .72f + rvbSize * .26f, damp = .25f, wet = .9f;
        double div = new double[]{1, 2, 3, 4}[sEchoDiv];
        int dt = (int) Math.min(sEchoL.length - 1, Math.round(spbNow * div));
        for (int k = 0; k < frames; k++) {
            float in = (rvbIn[k * 2] + rvbIn[k * 2 + 1]) * .03f;
            float yl = 0, yr = 0;
            for (int c = 0; c < 4; c++) {
                float[] bl = combL[c], br = combR[c];
                int i = combI[c];
                float ol = bl[i], or = br[i];
                combZL[c] = ol * (1 - damp) + combZL[c] * damp;
                combZR[c] = or * (1 - damp) + combZR[c] * damp;
                bl[i] = in + combZL[c] * fb;
                br[i] = in + combZR[c] * fb;
                combI[c] = (i + 1) % bl.length;
                yl += ol; yr += or;
            }
            for (int a = 0; a < 2; a++) {
                float[] bl = apL[a], br = apR[a];
                int i = apI[a];
                float bo = bl[i], bro = br[i];
                bl[i] = yl + bo * .5f; br[i] = yr + bro * .5f;
                yl = bo - yl; yr = bro - yr;
                apI[a] = (i + 1) % bl.length;
            }
            // the echo: tempo-timed, a little darker each repeat
            int r = Math.floorMod(sEchoW - dt, sEchoL.length);
            float el = sEchoL[r], er = sEchoR[r];
            sEchoL[sEchoW] = echoIn[k * 2] + er * .45f;
            sEchoR[sEchoW] = echoIn[k * 2 + 1] + el * .45f;
            sEchoW = (sEchoW + 1) % sEchoL.length;
            float l = yl * wet + el, rr = yr * wet + er;
            out[k * 2] += l; out[k * 2 + 1] += rr;
            busLevel = Math.max(busLevel, Math.abs(l) + Math.abs(rr));
        }
    }

    /** True when the reverb and echo have died away (for bouncing and baking). */
    public boolean busesQuiet() { return busLevel < 1e-5f; }

    /* ---------- per-pad live control: XY moves, routing, per-pad and per-bus effects ---------- */

    /** What an XY direction (or any live control) can move on one pad. */
    public static final int T_PITCH = 0, T_FILTER = 1, T_RES = 2, T_VOL = 3, T_PAN = 4, T_ATT = 5, T_REL = 6, T_START = 7,
        T_RVB = 8, T_ECHO = 9, T_DIRT = 10, T_CRUSH = 11, T_CHORUS = 12, T_WOB = 13, T_GLIDE = 14, T_ROLL = 15,
        T_GATE = 16, T_RING = 17, T_STUT = 18, T_TAPE = 19, NT = 20;
    /** The perform effects, in the order the buttons show them. */
    public static final String[] FXN = {"stutter", "echo", "crush", "tape", "gate", "dirty", "ring", "oct", "rev"};
    private static final float[] ZERO9 = new float[9];
    private final float[][] pMod = new float[64][NT];
    private final float[][] tFx = new float[69][9];
    private final float[] padDirt = new float[64], padCrush = new float[64], padChorus = new float[64], padWob = new float[64],
        padGate = new float[64], padRing = new float[64], padRvb = new float[64], padEcho = new float[64],
        padVol = new float[64], padPan = new float[64], tapeF = new float[64];
    private final double[] padRateFrom = new double[64], padRateTo = new double[64];
    private final boolean[] padStut = new boolean[64], padRev = new boolean[64];
    private final int[] pStutA = new int[64], pStutB = new int[64];
    private final long[] rollNext = new long[64];
    private long blockStart;
    private int blockLen = 1;
    private double spbNow = 1000;
    private int gateSteps = 1;
    private final float[] mixAmt = {1, 1, 1, 1, 1, 1, 1, 1, 1};
    public int triggers;
    /** SE-3: plays that used a baked version, and pads that needed live grain this session (for the Latency screen). */
    public int bakedPlays;
    public final boolean[] grainUsed = new boolean[64];

    /** Move one thing on one pad: semitones for pitch, -1..1 for volume and pan, seconds for attack, release and glide, 0..1 for the rest. */
    public void mod(int pad, int what, float value) { cmds.add(new Object[]{"mod", pad, what, value}); }
    /** A perform effect on one pad (0..63) or one bus (65..68 = buses 1..4); amount 0 turns it off. */
    public void fxTo(String name, int target, float amount) { cmds.add(new Object[]{"fxt", name, target, amount}); }
    /** Bend and level for one held note only (a circle drawn on a Padiano key). */
    public void noteMod(int id, float semis, float vol) { cmds.add(new Object[]{"nmod", id, semis, vol}); }
    /** How much a mix effect does when held, 0..1, and the gate's length in steps. */
    public void fxAmount(String name, float amount) { cmds.add(new Object[]{"famt", name, amount}); }
    public void gateLength(int steps) { cmds.add(new Object[]{"gatelen", steps}); }

    private final long[][] modTouch = new long[64][NT];
    { for (long[] m : modTouch) java.util.Arrays.fill(m, Long.MIN_VALUE / 2); }

    /** Filter and resonance moved through mod() or recorded moves: set that pad's live filter. */
    private void filterFromMod(int pd) {
        double cut = 1 - Math.max(0, Math.min(1, pMod[pd][T_FILTER])), res = Math.max(0, Math.min(1, pMod[pd][T_RES]));
        boolean on = cut < .999 || res > .001;
        pfTarget[pd] = on ? 90 * Math.pow(20000.0 / 90, cut) : 20000;
        pfQT[pd] = on ? .7 + res * 13 : .7;
        pfSlow[pd] = !on;
    }

    private static int fxIndex(String n) { for (int k = 0; k < FXN.length; k++) if (FXN[k].equals(n)) return k; return -1; }

    /** The rate a pad plays at right now (XY pitch, tape, octave), for tests. */
    public double padRate(int pad) { return pad >= 0 && pad < 64 ? padRateTo[pad] : 1; }
    public float padLevel(int pad) { return pad >= 0 && pad < 64 ? padVol[pad] : 1; }

    private void padBlock(Snapshot S, int frames) {
        blockStart = frame;
        blockLen = frames;
        spbNow = spb(S);
        for (int p = 0; p < 64; p++) {
            PadSlot ps = S.pads[p];
            int bus = ps != null ? ps.opts.route : 0;
            float[] a = tFx[p], b = bus > 0 ? tFx[64 + bus] : ZERO9, m = pMod[p];
            padDirt[p] = Math.max(a[5], b[5]) + m[T_DIRT];
            padCrush[p] = Math.max(a[2], b[2]) + m[T_CRUSH];
            padGate[p] = Math.min(1, Math.max(a[4], b[4]) + m[T_GATE]);
            padRing[p] = Math.min(1, Math.max(a[6], b[6]) + m[T_RING]);
            padEcho[p] = Math.max(a[1], b[1]) + m[T_ECHO];
            padRvb[p] = m[T_RVB];
            padChorus[p] = m[T_CHORUS];
            padWob[p] = m[T_WOB];
            boolean stut = (Math.max(a[0], b[0]) > 0 || m[T_STUT] > .5f) && playing;
            if (stut && !padStut[p]) { pStutA[p] = lastStep - (lastStep % 2); pStutB[p] = step; }
            padStut[p] = stut;
            padRev[p] = Math.max(a[8], b[8]) > 0;
            boolean oct = Math.max(a[7], b[7]) > 0;
            // a held tape stop winds the pad down over about a second; XY tape sets it directly
            tapeF[p] = Math.max(a[3], b[3]) > 0 ? Math.min(1, tapeF[p] + frames / (1.1f * rate)) : 0;
            float tape = Math.max(tapeF[p], Math.min(1, m[T_TAPE]));
            padRateFrom[p] = padRateTo[p];
            padRateTo[p] = Math.pow(2, m[T_PITCH] / 12.0) * (oct ? .5 : 1) * (1 - .82 * tape);
            padVol[p] = Math.max(0, 1 + m[T_VOL]) * (tape >= .97f ? 0 : 1);
            padPan[p] = Math.max(-1, Math.min(1, m[T_PAN]));
            // roll: this pad's held notes retrigger, from every 2 steps up to every quarter step
            float roll = m[T_ROLL];
            if (roll > .05f && ps != null) {
                boolean any = false;
                for (Held h : held.values()) if (h.pad.index == p) { any = true; break; }
                if (!any) { rollNext[p] = 0; continue; }
                double every = Math.pow(2, 1 - 3 * Math.min(1, roll)) * spbNow;
                if (rollNext[p] < frame) rollNext[p] = frame;
                while (rollNext[p] < frame + frames) {
                    long dl = rollNext[p] - frame;
                    for (Held h : held.values()) if (h.pad.index == p) for (int q : h.pitches) trigger(h.pad, oct() * Math.pow(2, q / 12.0), every * .9 / rate, true, Theory.chordGain(h.pitches.length), dl, false);
                    rollNext[p] += (long) Math.max(1, every);
                }
            } else rollNext[p] = 0;
        }
    }

    /**
     * The live bus from the site, in its order: filter pad, dirt, ring mod, gate, crush, then an echo
     * send whose tail keeps ringing after you let go. Wet amounts glide over about 10 ms so nothing clicks.
     */
    private void effects(Snapshot S, float[] out, int frames) {
        // filter pad: glide the cutoff once per block
        double glide = 1 - Math.exp(-frames / ((filtSlow ? .12 : .02) * rate));
        filtFreq += (filtTarget - filtFreq) * glide;
        filtQ += (qTarget - filtQ) * glide;
        boolean filt = xyHP ? filtFreq > 11 : filtFreq < 19900;
        float b0 = 0, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
        if (filt) {
            double f = Math.min(filtFreq, rate * .45), w = 2 * Math.PI * f / rate, cs = Math.cos(w), al = Math.sin(w) / (2 * filtQ), a0 = 1 + al;
            if (xyHP) { b0 = (float) ((1 + cs) / 2 / a0); b1 = (float) (-(1 + cs) / a0); }
            else { b0 = (float) ((1 - cs) / 2 / a0); b1 = (float) ((1 - cs) / a0); }
            b2 = b0; a1 = (float) (-2 * cs / a0); a2 = (float) ((1 - al) / a0);
        }
        float k = (float) (1 - Math.exp(-1.0 / (.01 * rate)));
        double spb = 60.0 / Math.max(40, S.bpm) / 4;
        int dt = (int) Math.min(dlyL.length - 1, Math.round(Math.min(1.9, spb * 3) * rate));
        float hpK = (float) Math.exp(-2 * Math.PI * 350 / rate);
        double ringInc = 2 * Math.PI * ringHz / rate, gateInc = gateHz / rate;
        boolean idle = !filt && !fxCrush && !fxDirt && !fxRing && !fxGate && !fxEcho && wCrush < 1e-4f && wDirt < 1e-4f && wRing < 1e-4f && wGate < 1e-4f && wSend < 1e-4f;
        for (int i = 0; i < frames; i++) {
            wCrush += ((fxCrush ? mixAmt[2] : 0) - wCrush) * k;
            wDirt += ((fxDirt ? mixAmt[5] : 0) - wDirt) * k;
            wRing += ((fxRing ? mixAmt[6] : 0) - wRing) * k;
            wGate += ((fxGate ? mixAmt[4] : 0) - wGate) * k * 2;
            wSend += ((fxEcho ? mixAmt[1] : 0) - wSend) * k * .5f;
            float l = out[i * 2], r = out[i * 2 + 1];
            if (!idle) {
                if (filt) {
                    float yl = b0 * l + b1 * fzL[0] + b2 * fzL[1] - a1 * fzL[2] - a2 * fzL[3];
                    fzL[1] = fzL[0]; fzL[0] = l; fzL[3] = fzL[2]; fzL[2] = yl; l = yl;
                    float yr = b0 * r + b1 * fzR[0] + b2 * fzR[1] - a1 * fzR[2] - a2 * fzR[3];
                    fzR[1] = fzR[0]; fzR[0] = r; fzR[3] = fzR[2]; fzR[2] = yr; r = yr;
                }
                if (wDirt > 1e-4f) { l = l * (1 - wDirt) + drive(l) * .55f * wDirt; r = r * (1 - wDirt) + drive(r) * .55f * wDirt; }
                if (wRing > 1e-4f) { float m = (float) Math.sin(ringPh); l = l * (1 - wRing) + l * m * wRing; r = r * (1 - wRing) + r * m * wRing; }
                ringPh += ringInc; if (ringPh > 2 * Math.PI) ringPh -= 2 * Math.PI;
                gatePh += gateInc; if (gatePh >= 1) gatePh -= 1;
                if (wGate > 1e-4f) { float g = (1 - wGate) + wGate * (gatePh < .5 ? 1f : 0f); l *= g; r *= g; }
                if (wCrush > 1e-4f) { l = l * (1 - wCrush) + crush(l) * .9f * wCrush; r = r * (1 - wCrush) + crush(r) * .9f * wCrush; }
            }
            // echo: always running so tails finish after you let go
            int rd = dlyW - dt; if (rd < 0) rd += dlyL.length;
            float el = dlyL[rd], er = dlyR[rd];
            float hl = hpK * (hpL + el - hpXL), hr = hpK * (hpR + er - hpXR);
            hpXL = el; hpXR = er; hpL = hl; hpR = hr;
            dlyL[dlyW] = l * wSend * .85f + hl * .55f;
            dlyR[dlyW] = r * wSend * .85f + hr * .55f;
            if (++dlyW >= dlyL.length) dlyW = 0;
            out[i * 2] = l + hl;
            out[i * 2 + 1] = r + hr;
        }
    }

    private static float drive(float x) { return (float) (Math.tanh(9 * x) / Math.tanh(9)); }
    private static float crush(float x) { float c = Math.max(-1f, Math.min(1f, x)); return Math.round(c * 8f) / 8f; }

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

    /** How long the last hit waited in the queue for the next block, and how many hits so far (Audio check, MM-1). */
    public volatile long lastHitWaitNanos;
    public volatile int hits;

    public int activeVoices() { int n = 0; for (Voice v : pool) if (v.active) n++; return n; }

    /** Voices running live grain right now: each costs about three plain voices. */
    public int grainVoices() { int n = 0; for (Voice v : pool) if (v.active && v.grain) n++; return n; }

    /* ================= bouncing to a file ================= */

    /** Render the mess offline. song = the whole song lane, otherwise the current loop, loops times. */
    public static float[] bounce(Project p, SampleSource src, int rate, boolean song, int loops) { return bounce(p, src, rate, song, loops, false); }

    /** Render one sound with its doctor settings baked in, mono, no master bus. */
    public static Sample renderOne(String key, Sample s, SoundEdit e) {
        Engine en = new Engine(s.rate);
        en.bypassMaster = true;
        Voice v = en.trigger(new PadSlot(-1, key, s, e, true), 1, 0, false, 1f, 0, false, false);
        if (v == null) return new Sample(key, new float[1], s.rate);
        int block = 1024;
        float[] buf = new float[block * 2];
        List<float[]> parts = new ArrayList<>();
        int total = 0;
        while ((en.activeVoices() > 0 || !en.busesQuiet()) && total < s.rate * 60) { en.render(buf, block); parts.add(buf.clone()); total += block; }
        float[] lr = new float[total * 2];
        int at = 0;
        for (float[] b : parts) { System.arraycopy(b, 0, lr, at, b.length); at += b.length; }
        // drop the silent end
        int last = total - 1;
        while (last > 0 && Math.abs(lr[last * 2]) < 1e-5f && Math.abs(lr[last * 2 + 1]) < 1e-5f) last--;
        float[] cut = java.util.Arrays.copyOf(lr, (last + 1) * 2);
        return Sample.fromStereo(key, cut, s.rate);
    }

    /** exact = stop right at the loop's end with no tail, for resampling a loop into a sound. */
    public static float[] bounce(Project p, SampleSource src, int rate, boolean song, int loops, boolean exact) {
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
            if (exact && rendered >= total * stepFrames + .004 * rate) break;
            if (!e.playing && e.fired >= total) {
                if (e.activeVoices() == 0 && e.busesQuiet()) { if (quietSince < 0) quietSince = rendered; if (rendered - quietSince > .05 * rate) break; }
                if (rendered > total * stepFrames + 4.0 * rate) break;
            }
        }
        long keep = exact ? Math.min(rendered, (long) Math.ceil(total * stepFrames + .004 * rate)) : Math.max((long) Math.ceil(total * stepFrames), rendered);
        float[] all = new float[(int) (keep * 2)];
        int at = 0;
        for (float[] b : parts) { int n = Math.min(b.length, all.length - at); if (n <= 0) break; System.arraycopy(b, 0, all, at, n); at += n; }
        // short fade at the very end so the file never clicks
        int fade = Math.min((int) (.01 * rate), (int) keep);
        for (int k = 0; k < fade; k++) { int f = (int) keep - 1 - k; float g = k / (float) fade; all[f * 2] *= g; all[f * 2 + 1] *= g; }
        return all;
    }
}
