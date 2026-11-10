/* Engine tests: run with plain javac/java, see .github/workflows/build.yml. Args: <sounds dir> <output dir> */
import com.tideclock.messmachine.engine.*;
import java.io.*;
import java.util.*;

public class EngineTest {
  static void check(boolean ok, String what){ System.out.println((ok ? "PASS " : "FAIL ") + what); if (!ok) failures++; }
  static int failures = 0;
  public static void main(String[] a) throws Exception {
    String base = a[0];
    Library lib = new Library(p -> new FileInputStream(p), base);
    check(lib.sounds.size() == 433, "library has 433 sounds: " + lib.sounds.size());
    check(lib.sounds.stream().allMatch(x -> !x.type.isEmpty()), "every sound has an instrument type");
    check("kick".equals(lib.sound("samples/basement-kick.wav").type) && "bass".equals(lib.sound("qb:block-of-meat-bass").type), "named sounds sort by name: " + lib.sound("qb:block-of-meat-bass").type);
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
