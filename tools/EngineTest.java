/* Engine tests: run with plain javac/java, see .github/workflows/build.yml. Args: <sounds dir> <output dir> */
import com.tideclock.messmachine.engine.*;
import java.io.*;
import java.util.*;

public class EngineTest {
  static void check(boolean ok, String what){ System.out.println((ok ? "PASS " : "FAIL ") + what); if (!ok) failures++; }
  static int failures = 0;
  static String fmt(double[] x) { return String.format("%.2f s at %.0f Hz", x[0], x[1]); }
  public static void main(String[] a) throws Exception {
    String base = a[0];
    Library lib = new Library(p -> new FileInputStream(p), base);
    check(lib.sounds.size() == 433, "library has 433 sounds: " + lib.sounds.size());
    check(lib.sounds.stream().allMatch(x -> !x.type.isEmpty()), "every sound has an instrument type");
    check("kick".equals(lib.sound("samples/basement-kick.wav").type) && "bass".equals(lib.sound("qb:block-of-meat-bass").type), "named sounds sort by name: " + lib.sound("qb:block-of-meat-bass").type);
    // MM-8: the bundled sounds are FLAC; the kick must decode to exactly the WAV it came from (length and a checksum)
    Sample kick = lib.load("samples/basement-kick.wav");
    long sumAbs = 0; for (float v : kick.data) sumAbs += Math.abs(Math.round(v * 32768f));
    check(kick.data.length == 26460 && kick.rate == 44100 && sumAbs == 92183135L, "FLAC kick is sample-exact: " + kick.data.length + " frames, sum " + sumAbs);
    Project p = Project.demo();
    for (String k : p.kit) if (!k.isEmpty()) check(lib.load(k) != null, "load " + k);
    Sample sl = lib.load("qb:stwretch-hit"); check(sl != null && Math.abs(sl.seconds() - .35) < .01, "qbldy slice length " + (sl == null ? -1 : sl.seconds()));
    // JSON round trip
    String j = p.toJson();
    Project q = Project.fromJson(j, lib::has).project;
    check(q.toJson().replaceAll("\"at\":\"[^\"]*\"", "").equals(j.replaceAll("\"at\":\"[^\"]*\"", "")), "json round trip");
    // a mess in the site's own format
    String site = "{\"name\":\"Site mess\",\"bpm\":120,\"v\":3,\"kit\":[\"samples/basement-kick.wav\",\"samples/wet-clap.wav\",\"a:uploaded1\",\"\",\"qb:fat-glide-pad\"],\"sel\":4,"
      + "\"pats\":[{\"len\":32,\"steps\":{\"samples/basement-kick.wav\":\"10001000100010001000100010001000\",\"samples/wet-clap.wav\":\"00001000000010000000100000001000\"},\"notes\":{\"qb:fat-glide-pad\":\"0.0.8,16.-5.8\"}},{\"len\":16,\"steps\":{},\"notes\":{}}],"
      + "\"song\":\"AAB-------------\",\"swing\":30,\"mutes\":[],\"solos\":[],\"scale\":\"Minor\",\"root\":9,\"fold\":false,\"edits\":{\"qb:fat-glide-pad\":{\"st\":0,\"en\":1,\"att\":0.1,\"rel\":0.3,\"rev\":false,\"loop\":false,\"mode\":\"gate\",\"tune\":-12,\"fine\":0,\"vol\":0.8,\"pan\":0,\"tone\":-0.3,\"norm\":false,\"choke\":0}}}";
    Project.Loaded L = Project.fromJson(site, lib::has);
    check(L.missing == 1, "missing uploaded sound counted: " + L.missing);
    check(L.project.pats[0].len == 32 && L.project.pats[0].step("samples/basement-kick.wav", 28), "32-step loop decoded");
    check(L.project.pats[0].notes("qb:fat-glide-pad").size() == 2 && L.project.pats[0].notes("qb:fat-glide-pad").get(1).p == -5, "roll notes decoded");
    check(L.project.songLen() == 3 && L.project.song[2] == 1, "song lane decoded");
    check(L.project.edit("qb:fat-glide-pad").tune == -12, "edits decoded");
    // theory
    check(Theory.keyNote(12, "Major", 0) == 0 && Theory.keyNote(13, "Major", 0) == 2 && Theory.keyNote(8, "Major", 0) == 7, "keys mode note layout");
    check(Theory.chordLabel(0, 0, "triad", "Major", 0).equals("C") && Theory.chordLabel(2, 1, "triad", "Major", 0).equals("Dm"), "triad labels");
    check(Theory.chordLabel(0, 2, "set:Sunday Pop", "Major", 0).equals("Am"), "chord set label");
    check(Arrays.equals(Theory.chordOf(0, 0, "seventh", "Major", 0), new int[]{0, 4, 7, 11}), "seventh chord notes");
    // bounce the demo loop twice and check where the kicks land
    int rate = 48000;
    for (String k : L.project.kit) if (!k.isEmpty()) lib.load(k);
    float[] w = Engine.bounce(p, lib, rate, false, 2);
    double spb = rate * 60.0 / p.bpm / 4;
    int frames = w.length / 2;
    check(frames >= 32 * spb, "bounce covers two loops: " + frames + " frames vs " + (int)(32*spb));
    double pk = 0; for (float v : w) pk = Math.max(pk, Math.abs(v));
    check(pk > .2 && pk <= 1.0, "bounce peak sane: " + pk);
    // onset at step 0 must be within 1ms of frame 0 + 4ms start offset
    int first = -1; for (int i = 0; i < frames; i++) if (Math.abs(w[i*2]) > .02) { first = i; break; }
    check(first >= 0 && Math.abs(first - .004*rate) < .002*rate, "first hit lands on time: frame " + first);
    // swing: step 6 kick at 6*spb (even), step 11 kick should be late by swing
    try (OutputStream o = new FileOutputStream(a[1] + "/demo_bounce.wav")) { Wav.writeStereo16(o, w, rate); }
    float[] w2 = Engine.bounce(L.project, lib, rate, true, 1);
    double spb2 = rate * 60.0 / 120 / 4;
    check(w2.length / 2 >= (32 + 32 + 16) * spb2, "song bounce covers A A B: " + w2.length / 2 + " vs " + (int)((32+32+16)*spb2));
    try (OutputStream o = new FileOutputStream(a[1] + "/site_song_bounce.wav")) { Wav.writeStereo16(o, w2, rate); }
    // realtime: live pad with rec on while playing should produce a STEP event
    Engine e = new Engine(rate);
    Engine.Live live = new Engine.Live(); live.rec = true;
    e.setSnapshot(Engine.Snapshot.build(p, lib, live));
    float[] buf = new float[256 * 2];
    e.play();
    for (int i = 0; i < 40; i++) e.render(buf, 256);
    e.down(1, 1, new int[]{0}, false);
    e.render(buf, 256);
    e.up(1);
    e.render(buf, 256);
    Engine.RecEvent ev = e.pollRec();
    check(ev != null && ev.type == Engine.RecEvent.STEP && ev.key.equals(p.kit[1]), "recording a pad hit gives a step event" + (ev == null ? "" : " at step " + ev.step));
    Engine.Position pos = e.heard();
    check(pos.playing && pos.pat == 0 && pos.step >= 0, "playhead position " + pos.step);
    // keys with note repeat + arp stopped: free clock fires
    Engine e2 = new Engine(rate);
    Engine.Live l2 = new Engine.Live(); l2.nr = true; l2.arp = "up";
    e2.setSnapshot(Engine.Snapshot.build(p, lib, l2));
    e2.down(5, 7, new int[]{0, 4, 7}, true);
    int maxv = 0; for (int i = 0; i < 400; i++) { e2.render(buf, 256); maxv = Math.max(maxv, e2.activeVoices()); }
    check(maxv >= 1, "arp runs with the transport stopped, voices " + maxv);
    // the arpeggio walks a held chord low to high (sorted on the audio thread without making new lists)
    Engine e2b = new Engine(rate);
    e2b.setSnapshot(Engine.Snapshot.build(p, lib, l2));
    e2b.down(6, 7, new int[]{7, 0, 4}, true);
    java.util.List<Double> incs = new java.util.ArrayList<>();
    double lastSeen = 0;
    for (int i = 0; i < 2000 && incs.size() < 4; i++) { e2b.render(buf, 256); double li = e2b.lastInc(); if (li != lastSeen && li > 0) { incs.add(li); lastSeen = li; } }
    check(incs.size() >= 4 && incs.get(0) < incs.get(1) && incs.get(1) < incs.get(2) && incs.get(3) < incs.get(2), "arp up goes 0, 4, 7, then back to 0: " + incs);
    // timing benchmark: 1 second of the demo at 48k
    Engine e3 = new Engine(rate); e3.setSnapshot(Engine.Snapshot.build(p, lib, new Engine.Live())); e3.play();
    long t0 = System.nanoTime(); for (int i = 0; i < rate / 256 * 10; i++) e3.render(buf, 256); long t1 = System.nanoTime();
    System.out.printf("render cost: %.2f ms per second of audio%n", (t1 - t0) / 1e6 / 10);
    // exact timing with an impulse: steps 0, 6, 8, 11 at 100 BPM with 18% swing
    float[] imp = new float[400]; imp[0] = 1f; imp[1] = 1f;
    Sample is = new Sample("imp", imp, rate);
    Project tp = new Project(); tp.kit[0] = "imp"; tp.bpm = 100; tp.swing = 18;
    for (int st : new int[]{0, 6, 8, 11}) tp.pats[0].setStep("imp", st, true);
    float[] tw = Engine.bounce(tp, k -> k.equals("imp") ? is : null, rate, false, 1);
    List<Double> got = new ArrayList<>();
    for (int i = 1; i < tw.length / 2; i++) if (Math.abs(tw[i * 2]) > .01 && Math.abs(tw[(i - 1) * 2]) < .01) got.add((i - .004 * rate) / spb);
    System.out.println("impulse onsets in steps: " + got);
    double sw = .18 * .5;
    check(got.size() == 4 && Math.abs(got.get(0)) < .01 && Math.abs(got.get(1) - 6) < .01 && Math.abs(got.get(2) - 8) < .01 && Math.abs(got.get(3) - (11 + sw)) < .01, "steps and swing land on the exact frame");
    // ---- SE-1, SE-2, SE-5, SE-6, SE-7: Stretch or Grain, Sustain, repeat styles, the beat retrigger
    {
      int sr = 44100;
      float[] sine = new float[sr];
      for (int i = 0; i < sr; i++) sine[i] = (float) (0.5 * Math.sin(2 * Math.PI * 220 * i / sr) * Math.min(1, Math.min(i, sr - i) / 220.0));
      Sample sn = new Sample("sine", sine, sr);
      Engine.SampleSource src = k -> k.equals("sine") ? sn : null;
      // plays pad 0 as a key at +12 (one octave up), holds 2 s worth of blocks, returns [sounding seconds, Hz]
      java.util.function.BiFunction<String, String, double[]> octaveUp = (pitchMode, heldMode) -> {
        Project gp = new Project(); gp.kit[0] = "sine";
        SoundEdit se = new SoundEdit(); se.pitch = pitchMode; se.held = heldMode; gp.setEdit("sine", se);
        Engine ge = new Engine(rate); ge.setSnapshot(Engine.Snapshot.build(gp, src, new Engine.Live()));
        ge.down(1, 0, new int[]{12}, true);
        float[] b = new float[256 * 2]; float[] all = new float[rate * 3];
        for (int i = 0; i < rate * 3 / 256; i++) { ge.render(b, 256); for (int jj = 0; jj < 256; jj++) all[i * 256 + jj] = b[jj * 2]; }
        int last = 0; for (int i = 0; i < all.length; i++) if (Math.abs(all[i]) > .02) last = i;
        int zc = 0; int a0 = rate / 10, a1 = Math.min(last, rate * 4 / 10);
        for (int i = a0 + 1; i < a1; i++) if (all[i - 1] < 0 && all[i] >= 0) zc++;
        return new double[]{last / (double) rate, zc / ((a1 - a0) / (double) rate)};
      };
      double[] st = octaveUp.apply("stretch", "loop"), gr = octaveUp.apply("grain", "loop"), au = octaveUp.apply("auto", "loop");
      // ---- SE-11, SE-12: synths on pads
      {
        Engine.SampleSource ss = k -> new Sample(k, new float[64], 44100);
        java.util.function.Function<Synth.Patch, Engine> eng = sp -> { Project qz = new Project(); qz.kit[0] = "synth:1"; qz.synths.put("synth:1", sp); Engine x = new Engine(rate); x.setSnapshot(Engine.Snapshot.build(qz, ss, new Engine.Live())); return x; };
        java.util.function.BiFunction<Engine, Double, float[]> run = (x, sec) -> { int nn = (int) (sec * rate) / 256 * 256; float[] bb = new float[512], o = new float[nn]; for (int i = 0; i < nn / 256; i++) { x.render(bb, 256); for (int qz = 0; qz < 256; qz++) o[i * 256 + qz] = bb[qz * 2]; } return o; };
        java.util.function.Function<float[], Double> hz = o -> { int z = 0; for (int i = 1; i < o.length; i++) if (o[i - 1] < 0 && o[i] >= 0) z++; return z / (o.length / (double) rate); };
        Synth.Patch sinP = new Synth.Patch(Synth.ANALOG, "t"); sinP.set("w1", 0); sinP.set("mix2", 0); sinP.set("cut", 18000); sinP.set("fenv", 0); sinP.set("res", 0); sinP.set("drive", 0);
        Engine se1 = eng.apply(sinP); se1.down(1, 0, new int[]{0}, true); run.apply(se1, .1); double h0 = hz.apply(run.apply(se1, .5));
        se1.down(2, 0, new int[]{12}, true); run.apply(se1, .1); double h12 = hz.apply(run.apply(se1, .5));
        check(Math.abs(h0 - 261.6) < 3 && Math.abs(h12 - 523.3) < 5 && se1.activeVoices() == 2, String.format("Synth: pitch 0 is middle C (%.1f Hz), +12 an octave up (%.1f Hz), poly plays both", h0, h12));
        se1.up(1); se1.up(2); run.apply(se1, 1.5);
        check(se1.activeVoices() == 0, "Synth: let go, the release rings out and the voices end");
        Synth.Patch mono = sinP.copy(); mono.set("mode", 1); mono.set("glide", .05);
        Engine se2 = eng.apply(mono); se2.down(1, 0, new int[]{0}, true); run.apply(se2, .3); se2.down(2, 0, new int[]{7}, true); run.apply(se2, .2);
        double hg = hz.apply(run.apply(se2, .3)); int mv = se2.activeVoices();
        se2.up(2); run.apply(se2, .2); double hb = hz.apply(run.apply(se2, .3));
        check(mv == 1 && Math.abs(hg - 392) < 5 && Math.abs(hb - 261.6) < 5, String.format("Synth: Mono glides to a second key on one voice (%.0f Hz, %d voice) and back when it lifts (%.0f Hz)", hg, mv, hb));
        // knob turns reach a held note: close the filter on a saw and the top end falls
        Synth.Patch saw = new Synth.Patch(Synth.ANALOG, "s"); saw.set("mix2", 0); saw.set("fenv", 0); saw.set("cut", 18000);
        Project qz = new Project(); qz.kit[0] = "synth:1"; qz.synths.put("synth:1", saw);
        Engine se3 = new Engine(rate); se3.setSnapshot(Engine.Snapshot.build(qz, ss, new Engine.Live())); se3.down(1, 0, new int[]{12}, true);
        java.util.function.Function<float[], Double> bright = o -> { double ee = 0; for (float f : o) ee += f * f; return Math.sqrt(ee / o.length); };
        double b0 = bright.apply(run.apply(se3, .3));
        qz.synths.get("synth:1").set("cut", 200); se3.setSnapshot(Engine.Snapshot.build(qz, ss, new Engine.Live()));
        run.apply(se3, .05); double b1 = bright.apply(run.apply(se3, .3));
        check(b1 < b0 * .3, String.format("Synth: turning Cutoff to 200 Hz under a held 523 Hz saw takes it down (level %.3f to %.3f)", b0, b1));
        // steps play a synth for one step, then it rings out
        Project stp = new Project(); stp.kit[0] = "synth:1"; stp.synths.put("synth:1", sinP.copy()); stp.pats[0].setStep("synth:1", 0, true);
        float[] bw = Engine.bounce(stp, ss, rate, false, 1);
        int lastS = 0; for (int i = 0; i < bw.length / 2; i++) if (Math.abs(bw[i * 2]) > .01) lastS = i;
        double stepSec = 60.0 / stp.bpm / 4;
        check(lastS > 0 && lastS / (double) rate < stepSec + .6, String.format("Synth: a step plays one step and its release (%.2f s, a step is %.2f s)", lastS / (double) rate, stepSec));
        // every mode, every filter, resonance and drive full, low to high: no blowups
        boolean bad = false; double peak = 0;
        for (int ft = 0; ft < 4; ft++) for (int md = 0; md < 3; md++) for (int nt : new int[]{-36, 0, 36}) {
          Synth.Patch x = new Synth.Patch(Synth.ANALOG, "x"); x.set("ftype", ft); x.set("mode", md); x.set("res", 1); x.set("drive", 1); x.set("uni", 5); x.set("fenv", 1); x.set("noise", 1); x.set("sub", 1);
          Engine xe = eng.apply(x); xe.down(1, 0, new int[]{nt}, true); for (float f : run.apply(xe, .3)) { if (Float.isNaN(f) || Float.isInfinite(f)) bad = true; peak = Math.max(peak, Math.abs(f)); }
        }
        check(!bad && peak < 1.5, String.format("Synth: full resonance and drive in every mode and filter stay finite (peak %.2f)", peak));
        // SE-13 FM and SE-14 wavetable: in tune, they end after their release, and every preset plays without blowing up
        Synth.Patch fm1 = new Synth.Patch(Synth.FM, "t"); fm1.set("algo", 6); fm1.set("l2", 0); fm1.set("l3", 0); fm1.set("l4", 0); fm1.set("s1", 1);
        Synth.Patch wv1 = new Synth.Patch(Synth.WAVE, "t"); wv1.set("pos", 0); wv1.set("fenv", 0); wv1.set("cut", 18000);
        for (Synth.Patch kp : new Synth.Patch[]{fm1, wv1}) {
          Engine ke = eng.apply(kp); ke.down(1, 0, new int[]{0}, true); run.apply(ke, .1); double kh = hz.apply(run.apply(ke, .5));
          ke.up(1); run.apply(ke, 2.5);
          check(Math.abs(kh - 261.6) < 3 && ke.activeVoices() == 0, String.format("Synth %s: middle C at %.1f Hz, and the note ends after its release", kp.kind.name, kh));
        }
        boolean kBad = false; double kPeak = 0;
        for (Synth.Kind kk : Synth.KINDS) for (Synth.Patch pr : kk.presets) { Engine ke = eng.apply(pr.copy()); ke.down(1, 0, new int[]{0, 4, 7}, true); for (float f : run.apply(ke, .6)) { if (Float.isNaN(f) || Float.isInfinite(f)) kBad = true; kPeak = Math.max(kPeak, Math.abs(f)); } }
        check(!kBad && kPeak < 1.5, String.format("Synth: all %d presets play a chord cleanly (peak %.2f)", java.util.Arrays.stream(Synth.KINDS).mapToInt(kk -> kk.presets.size()).sum(), kPeak));
        Project sv = new Project(); sv.kit[3] = "synth:2"; sv.synths.put("synth:2", Synth.ANALOG.presets.get(4).copy());
        Project back = Project.fromJson(sv.toJson(), k -> false).project;
        check("synth:2".equals(back.kit[3]) && back.synth("synth:2") != null && back.synth("synth:2").get("mode") == 1 && "Mono bass".equals(back.synth("synth:2").name), "Synth: saved and loaded with the mess, even where the library does not know the key");
      }
      // ---- SE-3: bake. The same octave up played from the baked version: same length and pitch, no live grain
      {
        float[] up = Bake.shift(sine, 2);
        int zc = 0; for (int i = sr / 10 + 1; i < sr * 9 / 10; i++) if (up[i - 1] < 0 && up[i] >= 0) zc++;
        double e0 = 0, e1 = 0; for (int i = 0; i < sr; i++) { e0 += sine[i] * sine[i]; e1 += up[i] * up[i]; }
        check(up.length == sine.length && Math.abs(zc / .8 - 440) < 3 && Math.abs(Math.sqrt(e1 / e0) - 1) < .1,
            String.format("Bake: an octave up keeps the length (%d of %d) at %.1f Hz, level %.2f", up.length, sine.length, zc / .8, Math.sqrt(e1 / e0)));
        float[] dn = Bake.shift(sine, Math.pow(2, -7 / 12.0));
        zc = 0; for (int i = sr / 10 + 1; i < sr * 9 / 10; i++) if (dn[i - 1] < 0 && dn[i] >= 0) zc++;
        check(Math.abs(zc / .8 - 146.8) < 3, String.format("Bake: 7 semitones down plays at %.1f Hz (146.8 wanted)", zc / .8));
        sn.baked = Bake.all(sine, null);
        check(sn.baked != null && sn.baked[Bake.RANGE] == null && sn.baked[0] != null && sn.baked[2 * Bake.RANGE] != null, "Bake: every semitone an octave either way, the sound itself left alone");
        Project gp = new Project(); gp.kit[0] = "sine"; SoundEdit se = new SoundEdit(); se.pitch = "grain"; se.bake = true; gp.setEdit("sine", se);
        Engine ge = new Engine(rate); ge.setSnapshot(Engine.Snapshot.build(gp, src, new Engine.Live()));
        ge.down(1, 0, new int[]{12}, true);
        float[] b = new float[256 * 2]; float[] all = new float[rate * 2];
        int gv = 0;
        for (int i = 0; i < rate * 2 / 256; i++) { ge.render(b, 256); gv = Math.max(gv, ge.grainVoices()); for (int jj = 0; jj < 256; jj++) all[i * 256 + jj] = b[jj * 2]; }
        int last = 0; for (int i = 0; i < all.length; i++) if (Math.abs(all[i]) > .02) last = i;
        zc = 0; for (int i = rate / 10 + 1; i < rate * 4 / 10; i++) if (all[i - 1] < 0 && all[i] >= 0) zc++;
        check(ge.bakedPlays == 1 && gv == 0 && Math.abs(last / (double) rate - 1) < .08 && Math.abs(zc / .3 - 440) < 7,
            String.format("Bake: the engine plays the baked octave as tape (%d baked plays, %d grain voices, %.2f s at %.0f Hz)", ge.bakedPlays, gv, last / (double) rate, zc / .3));
        // 13 semitones is past the bake: live grain
        Engine g2 = new Engine(rate); g2.setSnapshot(Engine.Snapshot.build(gp, src, new Engine.Live()));
        g2.down(1, 0, new int[]{13}, true); g2.render(b, 256);
        check(g2.bakedPlays == 0 && g2.grainVoices() == 1 && g2.grainUsed[0], "Bake: past an octave it plays live grain, and the pad is marked as needing it");
        // Sustain stays live (it freezes grains), reversed plays stay live (the bake is forward)
        SoundEdit ss = se.copy(); ss.held = "sustain"; gp.setEdit("sine", ss);
        Engine g3 = new Engine(rate); g3.setSnapshot(Engine.Snapshot.build(gp, src, new Engine.Live()));
        g3.down(1, 0, new int[]{12}, true); g3.render(b, 256);
        check(g3.bakedPlays == 0 && g3.grainVoices() == 1, "Bake: Sustain still plays live grain");
        check(SoundEdit.fromMap(se.toMap()).bake && !SoundEdit.fromMap(new SoundEdit().toMap()).bake, "Bake: saved and loaded with the sound's settings");
        sn.baked = null;
      }
      check(Math.abs(st[0] - .5) < .06 && Math.abs(st[1] - 440) < 6, "Stretch: an octave up plays twice as fast, half as long: " + fmt(st));
      check(Math.abs(gr[0] - 1.0) < .08 && Math.abs(gr[1] - 440) < 6, "Grain: an octave up keeps its length: " + fmt(gr));
      check(Math.abs(au[0] - 1.0) < .08 && Math.abs(au[1] - 440) < 6, "auto: a key played an octave up keeps its length: " + fmt(au));
      // the Doctor's own tune stays tape in auto: tune +12 at the key's own pitch halves the length
      {
        Project gp = new Project(); gp.kit[0] = "sine"; SoundEdit se = new SoundEdit(); se.tune = 12; gp.setEdit("sine", se);
        gp.pats[0].setStep("sine", 0, true); float[] gw = Engine.bounce(gp, src, rate, false, 1);
        int last = 0; for (int i = 0; i < gw.length / 2; i++) if (Math.abs(gw[i * 2]) > .02) last = i;
        check(Math.abs((last - .004 * rate) / rate - .5) < .06, "auto: the Doctor's Tune +12 still plays as tape: " + String.format("%.2f s", last / (double) rate));
      }
      // grain at pitch 1 is the sound itself (two heads reading the same moment add up to it)
      {
        Project gp = new Project(); gp.kit[0] = "sine"; SoundEdit se = new SoundEdit(); se.pitch = "grain"; gp.setEdit("sine", se);
        Engine ge = new Engine(rate); ge.setSnapshot(Engine.Snapshot.build(gp, src, new Engine.Live()));
        ge.down(1, 0, new int[]{0}, false);
        float[] b = new float[4800 * 2]; ge.render(b, 4800);
        Engine pe = new Engine(rate); SoundEdit pl = new SoundEdit(); pl.pitch = "stretch"; gp.setEdit("sine", pl); pe.setSnapshot(Engine.Snapshot.build(gp, src, new Engine.Live()));
        pe.down(1, 0, new int[]{0}, false); float[] c = new float[4800 * 2]; pe.render(c, 4800);
        double diff = 0; for (int i = 0; i < 9600; i++) diff = Math.max(diff, Math.abs(b[i] - c[i]));
        check(diff < 1e-3, "Grain at the sound's own pitch matches the sound: max difference " + diff);
      }
      // SE-5 sustain: held past its natural end it keeps sounding; let go and it fades
      double[] sus = octaveUp.apply("grain", "sustain");
      check(sus[0] > 2.8, "Sustain keeps a held note sounding past its end: still sounding at " + String.format("%.2f s", sus[0]));
      {
        Project gp = new Project(); gp.kit[0] = "sine"; SoundEdit se = new SoundEdit(); se.held = "sustain"; gp.setEdit("sine", se);
        Engine ge = new Engine(rate); ge.setSnapshot(Engine.Snapshot.build(gp, src, new Engine.Live()));
        ge.down(1, 0, new int[]{0}, false);
        float[] b = new float[256 * 2];
        for (int i = 0; i < rate * 2 / 256; i++) ge.render(b, 256);
        double during = 0; for (float v : b) during = Math.max(during, Math.abs(v));
        ge.up(1);
        for (int i = 0; i < rate / 256; i++) ge.render(b, 256);
        double after = 0; for (float v : b) after = Math.max(after, Math.abs(v));
        check(during > .2 && after < .01, "Sustain under a finger holds a drum-pad press, and stops when let go: " + String.format("%.2f then %.3f", during, after));
      }
      // SE-6: repeat styles on a held chord while playing. cut restarts every hit, flow starts once and pulses
      for (String style : new String[]{"cut", "ring", "flow"}) {
        Project gp = new Project(); gp.kit[0] = "sine"; gp.bpm = 120;
        Engine.Live lv = new Engine.Live(); lv.nr = true; lv.nrRate = 2; lv.nrStyle = style;
        Engine ge = new Engine(rate); ge.setSnapshot(Engine.Snapshot.build(gp, src, lv)); ge.play();
        float[] b = new float[256 * 2];
        for (int i = 0; i < 10; i++) ge.render(b, 256);
        int tr0 = ge.triggers;
        ge.down(1, 0, new int[]{0, 4, 7}, true);
        int maxV = 0;
        float[] env = new float[rate];
        for (int i = 0; i < rate / 256; i++) { ge.render(b, 256); maxV = Math.max(maxV, ge.activeVoices()); for (int jj = 0; jj < 256; jj++) env[i * 256 + jj] = Math.abs(b[jj * 2]); }
        int starts = ge.triggers - tr0;
        if ("flow".equals(style)) {
          // the volume rises and falls with each 1/8 (0.25 s at 120 BPM) while only three notes ever start
          double hi = 0, lo = 9; for (int w0 = rate / 2; w0 < rate - 2400; w0 += 2400) { double m = 0; for (int jj = w0; jj < w0 + 2400; jj++) m = Math.max(m, env[jj]); hi = Math.max(hi, m); lo = Math.min(lo, m); }
          check(starts == 3 && hi > 2.5 * lo, "flow: a held chord starts once (" + starts + " starts) and repeat pulses it (loud " + String.format("%.2f, quiet %.2f", hi, lo) + ")");
        } else check(starts >= 9, style + ": repeat starts the chord again on every 1/8: " + starts + " starts, up to " + maxV + " voices");
        if ("ring".equals(style)) check(maxV > 3, "ring: hits overlap instead of cutting each other: up to " + maxV + " voices");
        if ("cut".equals(style)) check(maxV <= 6, "cut: each hit stops before the next: up to " + maxV + " voices");
      }
      // SE-7: Hold's beat retrigger, transport stopped: a latched pad starts again every beat (0.5 s at 120 BPM)
      {
        Project gp = new Project(); gp.kit[0] = "sine"; gp.bpm = 120; SoundEdit se = new SoundEdit(); se.held = "beat"; gp.setEdit("sine", se);
        Engine ge = new Engine(rate); ge.setSnapshot(Engine.Snapshot.build(gp, src, new Engine.Live()));
        int tr0 = ge.triggers;
        ge.down(1, 0, new int[]{0}, false, true);
        float[] b = new float[256 * 2];
        for (int i = 0; i < (int) (rate * 2.1 / 256); i++) ge.render(b, 256);
        int starts = ge.triggers - tr0;
        check(starts == 5, "beat retrigger: latched for 2 s at 120 BPM starts 5 times (now and on 4 beats): " + starts);
        ge.unlatch(1);
        int tr1 = ge.triggers; for (int i = 0; i < rate / 256; i++) ge.render(b, 256);
        check(ge.triggers == tr1, "beat retrigger stops when unlatched");
      }
      // sound edits keep the new settings through a save
      SoundEdit se = new SoundEdit(); se.pitch = "grain"; se.held = "beat"; se.beatN = 4;
      SoundEdit back = SoundEdit.fromMap(Json.map(Json.parse(Json.write(se.toMap()))));
      check("grain".equals(back.pitch) && "beat".equals(back.held) && back.beatN == 4 && !back.isDefault(), "pitch, held and beatN survive a save");
    }
    // ---- perform FX
    {
      // stutter: hold it while playing; steps loop on a pair instead of moving on
      Engine fe = new Engine(rate); fe.setSnapshot(Engine.Snapshot.build(tp, k -> k.equals("imp") ? is : null, new Engine.Live()));
      fe.play(); float[] b = new float[256 * 2];
      for (int i = 0; i < 30; i++) fe.render(b, 256);
      fe.fx("stutter", true);
      java.util.Set<Integer> seen = new java.util.TreeSet<>();
      for (int i = 0; i < 400; i++) { fe.render(b, 256); Engine.Position ps = fe.heard(); if (ps.step >= 0) seen.add(ps.step); }
      check(seen.size() <= 3, "stutter holds the loop on two steps: " + seen);
      fe.fx("stutter", false);
      // tape stop: the clock slows and then stops making sound
      Engine te = new Engine(rate); te.setSnapshot(Engine.Snapshot.build(p, lib, new Engine.Live())); te.play();
      for (int i = 0; i < 20; i++) te.render(b, 256);
      te.fx("tape", true);
      double late = 0; for (int i = 0; i < 1500; i++) { te.render(b, 256); if (i > 1200) for (float v : b) late = Math.max(late, Math.abs(v)); }
      check(late < .05, "tape stop goes quiet after a second or so: " + late);
      // crush: output sits on 1/8 steps (before the master bus) — check through renderOne-like raw path
      Engine ce = new Engine(rate); ce.setSnapshot(Engine.Snapshot.build(p, lib, new Engine.Live()));
      ce.fx("crush", true); ce.preview(lib.get(p.kit[7]), null, 0); // previews skip FX, so use a pad hit instead
      ce.down(9, 7, new int[]{0}, false);
      float[] big = new float[4096 * 2]; for (int i = 0; i < 4; i++) ce.render(big, 4096);
      check(ce.fxActive(), "crush is held");
      // echo: hit once with echo held, release, and the tail is still there half a second later
      Engine ee = new Engine(rate); ee.setSnapshot(Engine.Snapshot.build(p, lib, new Engine.Live()));
      ee.fx("echo", true); ee.down(1, 0, new int[]{0}, false); ee.up(1);
      for (int i = 0; i < 20; i++) ee.render(b, 256);
      ee.fx("echo", false);
      double tail = 0; for (int i = 0; i < 400; i++) { ee.render(b, 256); if (i > 150) for (float v : b) tail = Math.max(tail, Math.abs(v)); }
      check(tail > .01, "echo tail keeps ringing after you let go: " + tail);
      // filter pad: closing it right down takes the highs out
      Engine xe = new Engine(rate); xe.setSnapshot(Engine.Snapshot.build(p, lib, new Engine.Live()));
      xe.xy(0f, 1f, true); for (int i = 0; i < 10; i++) xe.render(b, 256);
      xe.down(2, 2, new int[]{0}, false);
      double hf = 0, prev = 0; for (int i = 0; i < 40; i++) { xe.render(b, 256); for (int u = 0; u < 256; u++) { hf += Math.abs(b[u * 2] - prev); prev = b[u * 2]; } }
      Engine ne = new Engine(rate); ne.setSnapshot(Engine.Snapshot.build(p, lib, new Engine.Live()));
      for (int i = 0; i < 10; i++) ne.render(b, 256);
      ne.down(2, 2, new int[]{0}, false);
      double hf2 = 0; prev = 0; for (int i = 0; i < 40; i++) { ne.render(b, 256); for (int u = 0; u < 256; u++) { hf2 += Math.abs(b[u * 2] - prev); prev = b[u * 2]; } }
      check(hf < hf2 * .5, "filter pad at the bottom darkens the hat: " + (int) hf + " vs " + (int) hf2);
    }
    // ---- pad filter: closes on one pad only
    {
      float[] b = new float[256 * 2];
      java.util.function.BiFunction<Integer, Boolean, Double> hiss = (pad, closed) -> {
        Engine pe = new Engine(rate); pe.setSnapshot(Engine.Snapshot.build(p, lib, new Engine.Live()));
        if (closed) pe.padFilter(2, 0f, .3f, true);
        for (int i = 0; i < 20; i++) pe.render(b, 256);
        pe.down(7, pad, new int[]{0}, false);
        double hf = 0, prev = 0; for (int i = 0; i < 40; i++) { pe.render(b, 256); for (int u = 0; u < 256; u++) { hf += Math.abs(b[u * 2] - prev); prev = b[u * 2]; } }
        return hf;
      };
      double hatShut = hiss.apply(2, true), hatOpen = hiss.apply(2, false), kickShut = hiss.apply(0, true), kickOpen = hiss.apply(0, false);
      check(hatShut < hatOpen * .5, "pad filter darkens its own pad: " + (int) hatShut + " vs " + (int) hatOpen);
      check(Math.abs(kickShut - kickOpen) < kickOpen * .01 + 1e-6, "pad filter leaves other pads alone: " + (int) kickShut + " vs " + (int) kickOpen);
      Engine oe = new Engine(rate); oe.setSnapshot(Engine.Snapshot.build(p, lib, new Engine.Live()));
      oe.padFilter(2, 0f, 1f, true); for (int i = 0; i < 20; i++) oe.render(b, 256);
      boolean wasOn = oe.padFiltered(2);
      oe.padFilter(2, 0f, 0f, false); for (int i = 0; i < 200; i++) oe.render(b, 256);
      check(wasOn && !oe.padFiltered(2), "letting go of the pad filter opens it again");
    }
    // ---- hold (latch): a looping sound keeps going after the finger lifts, until it is pressed again
    {
      Project lp = Project.starter();
      SoundEdit le = new SoundEdit(); le.loop = true; lp.setEdit(lp.kit[7], le);
      Engine he = new Engine(rate); he.setSnapshot(Engine.Snapshot.build(lp, lib, new Engine.Live()));
      float[] b = new float[256 * 2];
      he.down(1500, 7, new int[]{0}, true, true); he.up(1500);
      for (int i = 0; i < 400; i++) he.render(b, 256);
      check(he.activeVoices() >= 1, "a held note keeps sounding after lifting: " + he.activeVoices());
      he.unlatch(1500);
      for (int i = 0; i < 40; i++) he.render(b, 256);
      check(he.activeVoices() == 0, "pressing it again lets it go: " + he.activeVoices());
      he.down(1501, 7, new int[]{0}, true, false); he.up(1501);
      for (int i = 0; i < 40; i++) he.render(b, 256);
      check(he.activeVoices() == 0, "a normal note on a looping sound stops on lift: " + he.activeVoices());
    }
    // ---- hold keeps a one-shot sounding (note #4); "once" lets it end
    {
      Project op = Project.starter();
      float[] b = new float[256 * 2];
      Engine oe = new Engine(rate); oe.setSnapshot(Engine.Snapshot.build(op, lib, new Engine.Live()));
      oe.down(1600, 0, new int[]{0}, true, true); oe.up(1600);
      double kickSec = lib.get(op.kit[0]).seconds();
      int blocks = (int) Math.ceil((kickSec + 1.0) * rate / 256);
      for (int i = 0; i < blocks; i++) oe.render(b, 256);
      check(oe.activeVoices() >= 1, "a held one-shot keeps sounding past its end (" + String.format("%.2f", kickSec) + " s kick)");
      oe.unlatch(1600); for (int i = 0; i < 40; i++) oe.render(b, 256);
      check(oe.activeVoices() == 0, "letting go of a held one-shot stops it");
      SoundEdit once = new SoundEdit(); once.held = "once"; op.setEdit(op.kit[0], once);
      oe.setSnapshot(Engine.Snapshot.build(op, lib, new Engine.Live()));
      oe.down(1601, 0, new int[]{0}, true, true); oe.up(1601);
      for (int i = 0; i < blocks; i++) oe.render(b, 256);
      check(oe.activeVoices() == 0, "When held: play once lets the one-shot end");
      SoundEdit tail = new SoundEdit(); tail.held = "tail"; op.setEdit(op.kit[0], tail);
      check(SoundEdit.fromMap(tail.toMap()).held.equals("tail") && SoundEdit.fromMap(new SoundEdit().toMap()).held.equals("loop"), "When held saves with the mess");
    }
    // ---- glide: a new note slides in from the pad's last note
    {
      Project gp = Project.starter();
      PadOpts go = new PadOpts(); go.glide = true; go.glideT = .3; gp.setOpt(7, go);
      check(Project.fromJson(gp.toJson(), null).project.opt(7).glide, "glide saves with the mess");
      // loops up to 8 bars: a step in bar 7 saves, loads and plays
      Project lp = new Project();
      lp.kit[0] = p.kit[0];
      lp.pat().len = 128;
      lp.pat().setStep(lp.kit[0], 100, true);
      Project lq = Project.fromJson(lp.toJson(), null).project;
      check(lq.pat().len == 128 && lq.pat().step(lq.kit[0], 100), "an 8 bar loop keeps step 101 through save and load");
      Engine el = new Engine(rate);
      el.setSnapshot(Engine.Snapshot.build(lq, lib, new Engine.Live()));
      el.play();
      int hits = 0;
      double spbFrames = rate * 60.0 / lq.bpm / 4.0;
      for (int i = 0; i < (int) (spbFrames * 104 / 256); i++) { int before = el.triggers; el.render(buf, 256); hits += el.triggers - before; }
      check(hits >= 1, "the 8 bar loop reaches step 101 and plays it (" + hits + " hits)");
      Engine ge = new Engine(rate); ge.setSnapshot(Engine.Snapshot.build(gp, lib, new Engine.Live()));
      float[] b = new float[256 * 2];
      ge.down(1, 7, new int[]{0}, true); for (int i = 0; i < 4; i++) ge.render(b, 256);
      double gBase = ge.lastInc();
      ge.down(2, 7, new int[]{12}, true); ge.render(b, 256);
      double mid = ge.lastInc();
      for (int i = 0; i < (int) (.5 * rate / 256); i++) ge.render(b, 256);
      double end = ge.lastInc();
      check(mid < gBase * 1.25 && Math.abs(end / gBase - 2) < .01, String.format("glide slides up an octave over 0.3 s (%.2f, %.2f, %.2f)", gBase, mid, end));
      PadOpts lo = go.copy(); lo.legato = true; gp.setOpt(7, lo);
      Engine le2 = new Engine(rate); le2.setSnapshot(Engine.Snapshot.build(gp, lib, new Engine.Live()));
      le2.down(1, 7, new int[]{0}, true); le2.up(1); for (int i = 0; i < 4; i++) le2.render(b, 256);
      double b2 = le2.lastInc();
      le2.down(2, 7, new int[]{12}, true); le2.render(b, 256);
      check(Math.abs(le2.lastInc() / b2 - 2) < .01, "legato only: separate notes jump straight to pitch");
    }
    // ---- build C: doctor effects, per-pad and per-bus effects, live control
    {
      float[] b = new float[256 * 2];
      java.util.function.Function<Engine, double[]> run = (en) -> { double sum = 0, tail = 0; for (int i = 0; i < 400; i++) { en.render(b, 256); for (int u = 0; u < 512; u++) { double x = Math.abs(b[u]); sum += x; if (i > 120) tail += x; } } return new double[]{sum, tail}; };
      Project fp = Project.starter();
      Engine dry = new Engine(rate); dry.setSnapshot(Engine.Snapshot.build(fp, lib, new Engine.Live()));
      dry.down(1, 0, new int[]{0}, false); double[] d0 = run.apply(dry);
      SoundEdit wet = new SoundEdit(); wet.rvb = .7; wet.rvbSize = .8; fp.setEdit(fp.kit[0], wet);
      Engine rv = new Engine(rate); rv.setSnapshot(Engine.Snapshot.build(fp, lib, new Engine.Live()));
      rv.down(1, 0, new int[]{0}, false); double[] r0 = run.apply(rv);
      check(r0[1] > d0[1] * 20 + 1e-3, String.format("reverb leaves a tail after the kick (%.3f vs %.3f)", r0[1], d0[1]));
      SoundEdit ec = new SoundEdit(); ec.echo = .6; fp.setEdit(fp.kit[0], ec);
      Engine ee = new Engine(rate); ee.setSnapshot(Engine.Snapshot.build(fp, lib, new Engine.Live()));
      ee.down(1, 0, new int[]{0}, false); double[] e0 = run.apply(ee);
      check(e0[1] > d0[1] * 20 + 1e-3, String.format("echo repeats after the kick (%.3f)", e0[1]));
      check(Engine.renderOne("k", lib.get(fp.kit[0]), wet).seconds() > lib.get(fp.kit[0]).seconds() + .3, "save as new bakes the reverb tail in");
      SoundEdit dt = new SoundEdit(); dt.dirt = 1; dt.crush = .8; dt.chorus = .6; dt.wob = .5;
      check(SoundEdit.fromMap(dt.toMap()).crush == .8 && SoundEdit.fromMap(dt.toMap()).chorus == .6, "doctor effects save with the mess");
      Project fp2 = Project.starter(); fp2.setEdit(fp2.kit[2], dt);
      Engine de = new Engine(rate); de.setSnapshot(Engine.Snapshot.build(fp2, lib, new Engine.Live()));
      de.down(1, 2, new int[]{0}, false); double[] dd = run.apply(de);
      Engine dn = new Engine(rate); dn.setSnapshot(Engine.Snapshot.build(Project.starter(), lib, new Engine.Live()));
      dn.down(1, 2, new int[]{0}, false); double[] dn0 = run.apply(dn);
      check(Math.abs(dd[0] - dn0[0]) > dn0[0] * .1, String.format("dirty, crush, chorus and wobble change the hat (%.2f vs %.2f)", dd[0], dn0[0]));
      // a perform effect aimed at one pad leaves the others alone; aimed at a bus it reaches only that bus's pads
      Project rp = Project.starter();
      PadOpts bus1 = new PadOpts(); bus1.route = 1; rp.setOpt(2, bus1);
      java.util.function.BiFunction<Integer, Integer, Double> hit = (pad, target) -> {
        Engine en = new Engine(rate); en.setSnapshot(Engine.Snapshot.build(rp, lib, new Engine.Live()));
        if (target >= 0) en.fxTo("crush", target, 1f);
        en.render(b, 256); en.down(1, pad, new int[]{0}, false);
        return run.apply(en)[0];
      };
      double k0 = hit.apply(0, -1), k1 = hit.apply(0, 2), k2 = hit.apply(0, 65), h0 = hit.apply(2, -1), h1 = hit.apply(2, 65);
      check(Math.abs(k0 - k1) < 1e-6 && Math.abs(k0 - k2) < 1e-6, "crush on pad 3 or bus 1 leaves the kick alone");
      check(Math.abs(h0 - h1) > h0 * .02, String.format("crush on bus 1 reaches the hat routed there (%.2f vs %.2f)", h1, h0));
      // live control: pitch and volume move a pad as it plays
      Engine me = new Engine(rate); me.setSnapshot(Engine.Snapshot.build(Project.starter(), lib, new Engine.Live()));
      me.mod(7, Engine.T_PITCH, 12f); me.mod(7, Engine.T_VOL, -1f); me.render(b, 256);
      check(Math.abs(me.padRate(7) - 2) < 1e-6 && me.padLevel(7) == 0f && me.padRate(0) == 1, "XY pitch and volume move just that pad");
      me.fxTo("tape", 7, 1f); for (int i = 0; i < (int) (1.3 * rate / 256); i++) me.render(b, 256);
      check(me.padRate(7) < .5, "tape stop on one pad winds it down: " + me.padRate(7));
      // roll: a held note retriggers faster as the roll grows
      Engine re = new Engine(rate); re.setSnapshot(Engine.Snapshot.build(Project.starter(), lib, new Engine.Live()));
      re.mod(7, Engine.T_ROLL, 1f); re.down(1, 7, new int[]{0}, true);
      int rt0 = re.triggers; for (int i = 0; i < (int) (rate / 256); i++) re.render(b, 256);
      check(re.triggers - rt0 >= 12, "a full roll retriggers a held note many times a second: " + (re.triggers - rt0));
    }
    // ---- motion recording: recorded XY moves play back with the loop
    {
      Project mp = Project.starter();
      mp.pats[0].setMotion(7, Engine.T_PITCH, 4, 12f);
      mp.pats[0].setMotion(7, Engine.T_PITCH, 8, 0f);
      Project back = Project.fromJson(mp.toJson(), null).project;
      check(back.pats[0].hasMotion() && back.pats[0].motion.get("7:0")[4] == 12f && Float.isNaN(back.pats[0].motion.get("7:0")[5]), "recorded moves save with the mess");
      Engine me2 = new Engine(rate); me2.setSnapshot(Engine.Snapshot.build(mp, lib, new Engine.Live()));
      float[] b = new float[256 * 2];
      me2.play();
      double spbF = rate * 60.0 / mp.bpm / 4.0;
      for (int i = 0; i < (int) (6.5 * spbF / 256); i++) me2.render(b, 256);
      double during = me2.padRate(7);
      for (int i = 0; i < (int) (4 * spbF / 256); i++) me2.render(b, 256);
      check(Math.abs(during - 2) < 1e-6 && Math.abs(me2.padRate(7) - 1) < 1e-6, "a recorded bend plays back on its step and lets go on the next mark: " + during + ", " + me2.padRate(7));
    }
    // ---- workbench tools
    Sample hat = lib.get(p.kit[2]);
    SoundEdit tr = new SoundEdit(); tr.st = .1; tr.en = .6; tr.tune = 12;
    Sample one = Engine.renderOne("take", hat, tr);
    double expect = hat.seconds() * .5 / 2;
    check(Math.abs(one.seconds() - expect) < .02, "save as new sound bakes trim and tune: " + one.seconds() + " vs " + expect);
    float[] rs = Engine.bounce(p, lib, 44100, false, 1, true);
    double loopSec = 16 * 60.0 / p.bpm / 4;
    check(Math.abs(rs.length / 2 / 44100.0 - loopSec) < .01, "resample is exactly one loop long: " + rs.length / 2 / 44100.0 + " vs " + loopSec);
    Sample demo = Sample.fromStereo("d", w, rate);
    double[] cuts = demo.onsets(0, 1, .5);
    check(cuts.length >= 6, "auto chop finds the hits in the demo loop: " + cuts.length);
    double z = hat.snapZero(.3);
    int zi = (int) Math.round(z * hat.data.length);
    check(zi > 0 && (hat.data[zi - 1] <= 0) != (hat.data[zi] <= 0), "snap lands on a zero crossing");
    System.out.println(failures == 0 ? "ALL GOOD" : failures + " FAILED");
    if (failures > 0) System.exit(1);
  }
}
