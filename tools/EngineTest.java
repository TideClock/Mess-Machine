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
    Library lib = new Library(p -> java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(p)), base);
    // piece 10: 43 named sounds, 90 loops and 180 hits (the 390 0.35 s chops are gone, their keys alias to hits)
    check(lib.sounds.size() == 313, "library has 313 sounds: " + lib.sounds.size());
    check(lib.sounds.stream().allMatch(x -> !x.type.isEmpty()), "every sound has an instrument type");
    check("kick".equals(lib.sound("samples/basement-kick.wav").type) && "chop".equals(lib.sound("loop:ametrine-1").type), "named sounds sort by name, loops are chops: " + lib.sound("loop:ametrine-1").type);
    // MM-8: the bundled sounds are FLAC; the kick must decode to exactly the WAV it came from (length and a checksum)
    Sample kick = lib.load("samples/basement-kick.wav");
    long sumAbs = 0; for (float v : kick.data) sumAbs += Math.abs(Math.round(v * 32768f));
    check(kick.data.length == 26460 && kick.rate == 44100 && sumAbs == 92183135L, "FLAC kick is sample-exact: " + kick.data.length + " frames, sum " + sumAbs);
    Project p = Project.demo();
    for (String k : p.kit) if (!k.isEmpty()) check(lib.load(k) != null, "load " + k);
    Sample sl = lib.load("qb:stwretch-hit"); check(sl != null && sl.seconds() > .05 && sl.seconds() < .7, "an old Qbldy key plays a hit cut at the music: " + lib.titleOf("qb:stwretch-hit") + ", " + (sl == null ? -1 : sl.seconds()) + " s");
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
    // Nov 23 (shared format): a sound this phone does not have keeps its pad, its steps and its edits through a save,
    // and keys from other platforms or newer versions come back out exactly as they went in
    {
      String other = "{\"name\":\"From iOS\",\"bpm\":100,\"v\":4,\"kit\":[\"ios:take-7\",\"samples/basement-kick.wav\",\"synth:9\"],"
        + "\"pats\":[{\"len\":16,\"steps\":{\"ios:take-7\":\"1000100010001000\"},\"notes\":{},\"chance\":{\"ios:take-7\":\"50\"},\"iosLane\":{\"a\":[1,2,{\"b\":null}]}}],"
        + "\"edits\":{\"ios:take-7\":{\"tune\":5}},\"cloud\":{\"owner\":\"u1\",\"rev\":12},\"webTheme\":\"dark\"}";
      Project.Loaded lo = Project.fromJson(other, lib::has);
      check(lo.missing == 2 && "ios:take-7".equals(lo.project.kit[0]) && lo.project.kit[2].isEmpty(),
          "a sound this phone lacks stays on its pad (a synth with no patch does not): missing " + lo.missing);
      check(lo.newer, "a file from a newer format version is flagged");
      check("Missing".equals(lib.titleOf("ios:take-7")) && lib.titleOf("").isEmpty(), "a missing pad reads Missing, an empty one nothing");
      Project.Loaded again = Project.fromJson(lo.project.toJson(), lib::has);
      Project ag = again.project;
      check("ios:take-7".equals(ag.kit[0]) && ag.pats[0].step("ios:take-7", 4) && ag.pats[0].chance("ios:take-7", 0) == 50 && ag.edit("ios:take-7").tune == 5,
          "saving keeps a missing pad's sound, steps, chance and edits");
      String saved = ag.toJson();
      check(saved.contains("\"cloud\":{\"owner\":\"u1\",\"rev\":12}") && saved.contains("\"webTheme\":\"dark\"")
          && saved.contains("\"iosLane\":{\"a\":[1,2,{\"b\":null}]}"), "unknown top-level and per-loop keys are written back");
      check(saved.contains("\"v\":3,") && Project.fromJson(saved, lib::has).project.copy().toJson().replaceAll("\"at\":\"[^\"]*\"", "").equals(saved.replaceAll("\"at\":\"[^\"]*\"", "")),
          "the carried keys survive copy and a second save unchanged");
      Project cl = Project.fromJson(saved, lib::has).project; cl.pats[0].clear();
      check(!cl.toJson().contains("iosLane"), "clearing a loop clears what it carried");
    }
    // theory
    check(Theory.keyNote(12, "Major", 0) == 0 && Theory.keyNote(13, "Major", 0) == 2 && Theory.keyNote(8, "Major", 0) == 7, "keys mode note layout");
    check(Theory.chordLabel(0, 0, "triad", "Major", 0).equals("C") && Theory.chordLabel(2, 1, "triad", "Major", 0).equals("Dm"), "triad labels");
    check(Theory.chordLabel(9, 5, "set:Sunday Pop", "Major", 0).equals("Am"), "chord set label");
    check(Arrays.equals(Theory.chordOf(0, 0, "seventh", "Major", 0), new int[]{0, 4, 7, 11}), "seventh chord notes");
    {
        // Steps row tools (build 85): every n, repeat across, nudge, spread
        Project.Pattern rp = new Project.Pattern();
        rp.len = 16;
        rp.rowEvery("k", 4);
        check(rp.step("k", 0) && rp.step("k", 4) && rp.step("k", 12) && !rp.step("k", 2), "row tools: every 4");
        rp.rowClear("k"); rp.setStep("k", 1, true); rp.notes("k").add(new Project.Note(1, 7, 1));
        rp.rowRepeat("k", 4);
        check(rp.step("k", 5) && rp.step("k", 13) && !rp.step("k", 4) && rp.notes("k").size() == 4, "row tools: the first 4 across, pitches too");
        rp.rowNudge("k", 1);
        check(rp.step("k", 2) && rp.step("k", 14) && !rp.step("k", 1) && rp.notes("k").get(0).s == 2, "row tools: nudge right");
        rp.rowNudge("k", -3);
        check(rp.step("k", 15) && rp.step("k", 3), "row tools: nudge left wraps");
        rp.len = 8; rp.rowSpread("k", 3);
        check(rp.step("k", 0) && rp.step("k", 3) && rp.step("k", 6) && !rp.step("k", 1), "row tools: 3 over 8 is the tresillo");
    }
    {
        // the automation view: stamp a ramp over each beat, smooth it, clear it
        Project.Pattern mp = new Project.Pattern();
        mp.len = 16;
        mp.laneStamp("0:1", 1, 4, 0f, 1f, 1f);
        float[] ln = mp.lane("0:1");
        check(ln[0] == 0f && Math.abs(ln[3] - 1f) < 1e-6 && ln[4] == 0f, "moves: a ramp stamped every beat goes 0 to 1 and starts again");
        float jump = Math.abs(ln[4] - ln[3]);
        mp.laneSmooth("0:1", 1f);
        check(Math.abs(mp.lane("0:1")[4] - mp.lane("0:1")[3]) < jump, "moves: smoothing narrows the jump at the top of each beat");
        mp.laneStamp("0:1", 6, 8, 0f, 1f, .5f);
        check(mp.lane("0:1")[0] > .4f && mp.lane("0:1")[0] < 1f, "moves: half strength blends the square with what was there");
        mp.laneClear("0:1");
        check(!mp.hasMotion(), "moves: clear takes the lane away");
    }
    // build 84 (note #18): chords keep the root on the bottom, and chord sets are fixed slots that never drift
    check(Arrays.equals(Theory.chordOf(7, 0, "triad", "Major", 0), new int[]{7, 11, 14}), "G4 triad is G B D, root on the bottom (was D4 G4 B4)");
    check(Arrays.equals(Theory.chordOf(11, 0, "seventh", "Major", 0), new int[]{11, 14, 17, 21}), "B4 seventh keeps B lowest");
    check(Arrays.equals(Theory.chordOf(24, 0, "triad", "Major", 0), new int[]{12, 16, 19}), "a chord past the top moves down whole");
    check(Arrays.equals(Theory.chordOf(0, 0, "sus", "Major", 0), new int[]{0, 5, 7}) && Theory.chordLabel(0, 0, "sus", "Major", 0).equals("Csus4"), "1-4-5 is the sus sound");
    check(Arrays.equals(Theory.chordOf(0, 0, "octave", "Major", 0), new int[]{0, 12}), "octave type");
    {
        String sp = "set:Sunday Pop";
        int c4 = Theory.setIndexOfKey(0, sp, "Major", 0), c5 = Theory.setIndexOfKey(12, sp, "Major", 0), c3 = Theory.setIndexOfKey(-12, sp, "Major", 0);
        check(Arrays.equals(Theory.chordOf(0, c4, sp, "Major", 0), new int[]{0, 4, 7}), "set: low C plays C");
        check(Arrays.equals(Theory.chordOf(12, c5, sp, "Major", 0), new int[]{12, 16, 19}) && Theory.chordLabel(12, c5, sp, "Major", 0).equals("C"), "set: the next C up plays C again, an octave up (was G7)");
        check(Arrays.equals(Theory.chordOf(-12, c3, sp, "Major", 0), new int[]{-12, -8, -5}), "set: C3 plays C an octave down");
        check(Theory.setIndexOfKey(9, sp, "Major", 0) == 5 && Theory.slotName(sp, 5, 0).equals("Am"), "set: A, in the scale, plays its own degree's chord (Am)");
        check(Theory.setIndexOfKey(1, sp, "Major", 0) == 7 && Theory.slotName(sp, 7, 0).equals("Csus2"), "set: C#, outside the scale, plays the first colour chord (Csus2)");
        check(Theory.slotName(sp, 4, 0).equals("G") && Theory.setSize(sp) == 12, "set slot names");
        // Nov 22 (Tyler: in key): every set has 12 chords, all in its home scale; in its home scale every key of
        // every root plays a slot, and keys in the scale play the chord rooted on themselves
        boolean ok = true; String bad = "";
        for (Theory.ChordSet set : Theory.CHORDSETS.values()) {
            String m = "set:" + set.name;
            if (Theory.setSize(m) != 12 || !Theory.setFits(set, set.home)) { ok = false; bad = set.name + " size or key"; break; }
            int[] sc = Theory.scale(set.home);
            for (int root = 0; root < 12 && ok; root++) for (int k = -24; k <= 12; k++) {
                int ix = Theory.setIndexOfKey(k, m, set.home, root);
                if (ix == Integer.MIN_VALUE) { ok = false; bad = set.name + " root " + root + " key " + k + " plays nothing"; break; }
                boolean inScale = false; for (int iv : sc) if (Theory.pcOf(iv) == Theory.pcOf(k - root)) inScale = true;
                int[] ch = Theory.chordOf(k, ix, m, set.home, root);
                if (inScale && Theory.pcOf(ch[0]) != Theory.pcOf(k)) { ok = false; bad = set.name + " root " + root + " key " + k + " not rooted"; break; }
            }
        }
        check(ok, "12-chord sets: all in key, every key plays, in-scale keys rooted on themselves " + bad);
        // smooth chord changes: C then F moves to C F A (one hand barely moves), not up to F A C
        int[] cMaj = {0, 4, 7}, fMaj = {5, 9, 12};
        check(Arrays.equals(Theory.smooth(fMaj, cMaj), new int[]{0, 5, 9}), "smooth: C to F keeps the C (" + Arrays.toString(Theory.smooth(fMaj, cMaj)) + ")");
        check(Arrays.equals(Theory.smooth(cMaj, null), cMaj) && Arrays.equals(Theory.smooth(new int[]{0, 7}, cMaj), new int[]{0, 7}), "smooth: first chord and two-note chords stay as asked");
        int[] g7 = Theory.smooth(new int[]{7, 11, 14, 17}, cMaj);
        double gm = 0; for (int x : g7) gm += x; gm /= g7.length;
        check(Math.abs(gm - 12.25) <= 7.5 && g7[0] >= Theory.ROLL_LO, "smooth: stays within a fifth of where it was asked (" + Arrays.toString(g7) + ")");
        for (int k = -36; k <= 12; k++) {
            int ka = Theory.setIndexOfKey(k, sp, "Major", 0), kb = Theory.setIndexOfKey(k + 12, sp, "Major", 0);
            if (ka == Integer.MIN_VALUE) continue;
            if (!Theory.chordLabel(k, ka, sp, "Major", 0).equals(Theory.chordLabel(k + 12, kb, sp, "Major", 0))) { check(false, "set: key " + k + " and an octave up play different chords"); break; }
        }
    }
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
    try (OutputStream o = new FileOutputStream(a[1] + "/demo_bounce.wav")) { o.write(Wav.writeStereo16(w, rate)); }
    float[] w2 = Engine.bounce(L.project, lib, rate, true, 1);
    double spb2 = rate * 60.0 / 120 / 4;
    check(w2.length / 2 >= (32 + 32 + 16) * spb2, "song bounce covers A A B: " + w2.length / 2 + " vs " + (int)((32+32+16)*spb2));
    try (OutputStream o = new FileOutputStream(a[1] + "/site_song_bounce.wav")) { o.write(Wav.writeStereo16(w2, rate)); }
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
    // Nov 22 (Tyler: a swipe cancels its own hit): a hit cancelled while the finger is still down fades out and its
    // recorded step is taken back (a CANCEL event for the same step)
    {
        Engine ce = new Engine(rate);
        Engine.Live cl = new Engine.Live(); cl.rec = true;
        ce.setSnapshot(Engine.Snapshot.build(p, lib, cl));
        ce.play();
        for (int i = 0; i < 40; i++) ce.render(buf, 256);
        ce.down(7, 1, new int[]{0}, false);
        ce.render(buf, 256);
        int before = ce.activeVoices();
        ce.cancel(7);
        for (int i = 0; i < 4; i++) ce.render(buf, 256);
        int after = ce.activeVoices();
        Engine.RecEvent r1 = ce.pollRec(), r2 = ce.pollRec();
        check(r1 != null && r1.type == Engine.RecEvent.STEP && r2 != null && r2.type == Engine.RecEvent.CANCEL && r2.step == r1.step && r2.key.equals(r1.key),
            "swipe cancel: the recorded hit is taken back (" + (r1 == null ? "none" : "step " + r1.step) + ", " + (r2 == null ? "no cancel" : "cancel at " + r2.step) + ")");
        check(before >= 1 && after < before, "swipe cancel: the hit fades out at once (" + before + " voices, then " + after + ")");
        // cancelling after the finger lifted does nothing
        ce.down(8, 1, new int[]{0}, false); ce.render(buf, 256); ce.up(8); ce.render(buf, 256);
        while (ce.pollRec() != null) { }
        ce.cancel(8); ce.render(buf, 256);
        check(ce.pollRec() == null, "swipe cancel: a lifted hit stays recorded");
    }
    // Nov 22 (Tyler: a count-in): with a count-in the loop waits a bar of clicks at its top, then starts
    {
        Engine ke = new Engine(rate);
        Engine.Live kl = new Engine.Live(); kl.click = true;
        ke.setSnapshot(Engine.Snapshot.build(p, lib, kl));
        double spbK = rate * 60.0 / p.bpm / 4;
        ke.play(16);
        int halfBar = (int) (spbK * 8 / 256);
        for (int i = 0; i < halfBar; i++) ke.render(buf, 256);
        int countMid = ke.countUi;
        Engine.Position kp = ke.heard();
        for (int i = 0; i < halfBar + (int) (spbK * 3 / 256); i++) ke.render(buf, 256);
        Engine.Position kp2 = ke.heard();
        check(countMid >= 2 && countMid <= 3 && ke.countUi == 0, "count-in: counts down through the bar (" + countMid + " beats left halfway), then clears");
        check(kp2.playing && kp2.step >= 0 && kp2.step <= 4, "count-in: the loop starts at its top after one bar (step " + kp2.step + ")");
        Engine pe2 = new Engine(rate); pe2.setSnapshot(Engine.Snapshot.build(p, lib, kl));
        pe2.play(16); for (int i = 0; i < 4; i++) pe2.render(buf, 256); pe2.pause(); for (int i = 0; i < 4; i++) pe2.render(buf, 256);
        pe2.stop(); pe2.render(buf, 256);
        check(pe2.countUi == 0, "count-in: Stop clears it");
        // review of build 153: hits played during the count-in are not recorded, except right on the downbeat
        Engine re = new Engine(rate); Engine.Live rl = new Engine.Live(); rl.rec = true;
        re.setSnapshot(Engine.Snapshot.build(p, lib, rl));
        re.play(16);
        for (int i = 0; i < (int) (spbK * 6 / 256); i++) re.render(buf, 256);
        re.down(3, 1, new int[]{0}, false); re.render(buf, 256); re.up(3); re.render(buf, 256);
        check(re.pollRec() == null, "count-in: a hit in the middle of the count is not recorded");
        for (int i = 0; i < (int) (spbK * 9.8 / 256) - 2; i++) re.render(buf, 256);
        re.down(4, 1, new int[]{0}, false); re.render(buf, 256); re.up(4); re.render(buf, 256);
        Engine.RecEvent late = re.pollRec();
        check(late != null && late.step == 0, "count-in: a hit just before the downbeat lands on step 0 (" + (late == null ? "none" : "step " + late.step) + ")");
        // pause in the count-in keeps the rest of the count for Play
        Engine pc = new Engine(rate); pc.setSnapshot(Engine.Snapshot.build(p, lib, kl));
        pc.play(16); for (int i = 0; i < (int) (spbK * 6 / 256); i++) pc.render(buf, 256);
        pc.pause(); pc.render(buf, 256);
        int whilePaused = pc.countUi;
        pc.play(16); for (int i = 0; i < 3; i++) pc.render(buf, 256);
        check(whilePaused == 0 && pc.countUi > 0, "count-in: paused it shows nothing, Play carries on counting (" + pc.countUi + " left)");
    }
    // Nov 22: loops of any 1 to 8 bars load back (+ and - in the loop strip)
    {
        Project lp = new Project(); lp.kit[0] = "imp"; lp.pats[0].len = 48; lp.pats[0].setStep("imp", 40, true);
        Project lb = Project.fromJson(lp.toJson(), k -> true).project;
        check(lb.pats[0].len == 48 && lb.pats[0].step("imp", 40), "a 3-bar loop saves and loads as 3 bars");
        // review of build 153: shortening drops the extras past the end; doubling copies them
        Project.Pattern xp = new Project().pats[0]; xp.len = 32;
        xp.addOff("imp", 20 * Project.TICKS + 8); xp.setStrength("imp", 20, 2); xp.setStep("imp", 4, true); xp.setStrength("imp", 4, 2);
        xp.extrasFor(16, 32, false); xp.len = 16;
        xp.extrasFor(16, 32, false); xp.len = 32;
        check(xp.offsIn("imp", 20) == 0 && xp.strength("imp", 20) == 1, "minus then plus: the new bar starts clean");
        xp.extrasFor(32, 64, true); xp.len = 64;
        check(xp.strength("imp", 36) == 2, "2x copies accents into the new bars");
    }
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
      // ---- MM-K: the key finder names a key only when it is sure
      {
        final int ksr = 44100;
        java.util.function.BiFunction<int[], Double, float[]> tune = (notes, cents) -> {
          float[] x = new float[ksr * 4];
          int each = x.length / notes.length;
          for (int n = 0; n < notes.length; n++) {
            double f = 261.6256 * Math.pow(2, (notes[n] + cents / 100.0) / 12.0);
            for (int i = 0; i < each; i++) {
              double t = i / (double) ksr, env = Math.min(1, i / 400.0) * Math.exp(-t * 2);
              x[n * each + i] += (float) (env * (.5 * Math.sin(2 * Math.PI * f * t) + .25 * Math.sin(4 * Math.PI * f * t) + .12 * Math.sin(6 * Math.PI * f * t)));
            }
          }
          return x;
        };
        int[] major = {0, 4, 7, 12, 2, 4, 5, 7, 9, 11, 12, 7, 4, 0, 7, 0}, minor = {-3, 0, 4, 9, -3, -1, 0, 2, 4, 5, 7, 9, 4, 0, -3, -3};
        String cMaj = KeyFinder.find(tune.apply(major, 0.0), ksr);
        String aMin = KeyFinder.find(tune.apply(minor, 0.0), ksr);
        int[] up6 = major.clone();
        for (int i = 0; i < up6.length; i++) up6[i] += 6;
        String fsMaj = KeyFinder.find(tune.apply(up6, 35.0), ksr);
        check(cMaj.equals("C major") || cMaj.equals("A minor"), "Key: a C major tune reads as " + cMaj);
        check(aMin.equals("A minor") || aMin.equals("C major"), "Key: an A minor tune reads as " + aMin + " (the same notes as C major)");
        check(fsMaj.equals("F# major") || fsMaj.equals("D# minor"), "Key: an F# major tune a third of a semitone sharp still reads as " + fsMaj);
        java.util.Random kr = new java.util.Random(7);
        float[] hiss = new float[ksr * 2];
        for (int i = 0; i < hiss.length; i++) hiss[i] = (float) (kr.nextGaussian() * .2);
        check(KeyFinder.find(hiss, ksr).isEmpty(), "Key: noise gets no key");
        check(KeyFinder.find(java.util.Arrays.copyOf(tune.apply(major, 0.0), ksr / 5), ksr).isEmpty(), "Key: a sound shorter than 0.4 s gets no key");
        check(KeyFinder.find(new float[ksr * 2], ksr).isEmpty(), "Key: silence gets no key");
      }
      // ---- SE-4, SE-9, SE-10: loops at the mess's tempo, hits, and old chop keys still playing
      {
        Library ll = new Library(pth -> java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(pth)), a[0]);
        // loop keys (tools/LoopKeys.java): only the sure ones are written, each a note name plus major or minor, never on a hit
        java.util.List<String> noteNames = java.util.Arrays.asList(KeyFinder.NAMES);
        long keyed = ll.sounds.stream().filter(x -> x.cab.equals("loops") && !x.mkey.isEmpty()).count();
        boolean keysOk = ll.sounds.stream().filter(x -> !x.mkey.isEmpty()).allMatch(x -> {
          String[] kw = x.mkey.split(" ");
          return x.cab.equals("loops") && kw.length == 2 && noteNames.contains(kw[0]) && (kw[1].equals("major") || kw[1].equals("minor"));
        });
        check(keyed >= 20 && keysOk, "Loops: " + keyed + " loops carry a measured key, each a note name plus major or minor");
        Library.Sound keyedLoop = ll.sounds.stream().filter(x -> !x.mkey.isEmpty()).findFirst().get();
        Sample keyedSmp = ll.load(keyedLoop.key);
        KeyFinder.Key again = KeyFinder.ranked(KeyFinder.measure(keyedSmp.data, keyedSmp.rate).chroma).get(0);
        check(again.match > .5, String.format("Loops: %s says %s, and measured alone it reads %s (match %.2f)", keyedLoop.title, keyedLoop.mkey, again.name(), again.match));
        long nLoops = ll.sounds.stream().filter(x -> x.cab.equals("loops")).count(), nHits = ll.sounds.stream().filter(x -> x.cab.equals("hits")).count();
        long nOld = ll.sounds.stream().filter(x -> x.cab.equals("heap") || x.cab.equals("qbldy")).count();
        check(nLoops >= 80 && nHits >= 150 && nOld == 0, "Loops: " + nLoops + " loops and " + nHits + " hits on the shelves, no 0.35 s chops left");
        Library.Sound lp = ll.sounds.stream().filter(x -> x.cab.equals("loops")).findFirst().get();
        Sample ls = ll.load(lp.key);
        double barSec = lp.beats * 60.0 / lp.bpm;
        check(Math.abs(ls.data.length / (double) ls.rate - barSec) < 1.0 / ls.rate, String.format("Loops: %s is exactly %d beats at %.0f bpm (%.4f s)", lp.title, lp.beats, lp.bpm, ls.data.length / (double) ls.rate));
        Project lq = new Project(); lq.kit[0] = lp.key; lq.bpm = (int) Math.round(lp.bpm * 1.25); lq.pats[0].setStep(lp.key, 0, true);
        float[] lb = Engine.bounce(lq, k -> k.equals(lp.key) ? ls : null, rate, false, 1);
        int lastL = 0; for (int i = 0; i < lb.length / 2; i++) if (Math.abs(lb[i * 2]) > .003) lastL = i;
        double want = lp.beats * 60.0 / lq.bpm;
        check(Math.abs(lastL / (double) rate - want) < .04, String.format("Loops: at %d bpm the loop lasts one bar of the mess (%.3f s, %.3f wanted)", lq.bpm, lastL / (double) rate, want));
        check(ll.has("qb:bass-whimsy-01") && ll.load("qb:bass-whimsy-01") != null && ll.titleOf("qb:bass-whimsy-01").contains("hit"), "Loops: an old Heap key still loads (now " + ll.titleOf("qb:bass-whimsy-01") + "), so old messes keep their pads");
      }
      // ---- Hold on grid pads: a latched pad (not a key) keeps sounding after the finger lifts, until let go
      {
        Sample kk = lib.load("samples/basement-kick.wav");
        Project hq = new Project(); hq.kit[0] = "samples/basement-kick.wav";
        Engine he = new Engine(rate); he.setSnapshot(Engine.Snapshot.build(hq, k -> k.equals(hq.kit[0]) ? kk : null, new Engine.Live()));
        he.down(1000, 0, new int[]{0}, false, true); he.up(1000);
        float[] hb = new float[512]; double late = 0;
        for (int i = 0; i < rate * 2 / 256; i++) { he.render(hb, 256); if (i > rate * 3 / 2 / 256) for (int qq = 0; qq < 512; qq++) late = Math.max(late, Math.abs(hb[qq])); }
        check(late > .01 && he.activeVoices() > 0, String.format("Hold: a latched grid pad is still sounding 1.5 s after the finger lifted (peak %.3f)", late));
        he.unlatch(1000); for (int i = 0; i < rate / 256; i++) he.render(hb, 256);
        check(he.activeVoices() == 0, "Hold: letting it go stops it");
      }
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
        // Nov 21 video 2 (S3): the Synth tab's own synth is a 65th pad in no bank; it plays, saves and comes back
        {
          Project qs = new Project(); qs.kit[Project.SCRATCH] = "synth:1"; qs.synths.put("synth:1", sinP.copy());
          Engine sc = new Engine(rate); sc.setSnapshot(Engine.Snapshot.build(qs, ss, new Engine.Live()));
          sc.down(7, Project.SCRATCH, new int[]{0}, true); run.apply(sc, .1); double hs = hz.apply(run.apply(sc, .5));
          check(Math.abs(hs - 261.6) < 3 && sc.activeVoices() == 1, String.format("Scratch synth: the 65th pad plays middle C (%.1f Hz)", hs));
          sc.down(8, Project.SCRATCH, new int[]{7}, true); run.apply(sc, .1);
          check(sc.activeVoices() == 2, "Scratch synth: two fingers, two notes");
          sc.up(7); sc.up(8); run.apply(sc, 1.5);
          check(sc.activeVoices() == 0, "Scratch synth: let go, the notes end");
          Project qb = Project.fromJson(qs.toJson(), k -> false).project;
          check("synth:1".equals(qb.kit[Project.SCRATCH]) && qb.synths.containsKey("synth:1") && qb.loadedCount() == 0,
              "Scratch synth: saved with the mess, back after a load, and not counted as a loaded pad");
        }
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
        // SE-15 granular: grains of a 440 Hz tone played at pitch 0 (the source counts as middle C) come out at 440,
        // an octave up at 880; with no source it is silent and nothing breaks
        {
          float[] tone = new float[44100]; for (int i = 0; i < tone.length; i++) tone[i] = (float) (.5 * Math.sin(2 * Math.PI * 440 * i / 44100.0));
          Sample ts = new Sample("tone", tone, 44100);
          Synth.Patch gp = new Synth.Patch(Synth.GRAIN, "g"); gp.src = "tone"; gp.set("spray", 0); gp.set("size", .1); gp.set("dens", 40); gp.set("aa", .002);
          Project gq = new Project(); gq.kit[0] = "synth:1"; gq.synths.put("synth:1", gp);
          Engine ge = new Engine(rate); ge.setSnapshot(Engine.Snapshot.build(gq, k -> k.equals("tone") ? ts : new Sample(k, new float[64], 44100), new Engine.Live()));
          ge.down(1, 0, new int[]{0}, true); run.apply(ge, .2); double g0 = hz.apply(run.apply(ge, .5));
          ge.down(2, 0, new int[]{12}, true); ge.up(1); run.apply(ge, 1.0); double g12 = hz.apply(run.apply(ge, .5));
          check(Math.abs(g0 - 440) < 12 && Math.abs(g12 - 880) < 20, String.format("Synth Granular: grains of a 440 Hz tone play at %.0f Hz, an octave up at %.0f Hz", g0, g12));
          Synth.Patch none = gp.copy(); none.src = ""; Engine ne = eng.apply(none); ne.down(1, 0, new int[]{0}, true);
          double nl = 0; for (float f : run.apply(ne, .3)) nl = Math.max(nl, Math.abs(f));
          check(nl == 0, "Synth Granular: no source, no sound, no crash");
        }
        // SE-16 drums are one-shots even under a held finger; plucks are in tune and die away on their own
        {
          Engine de = eng.apply(Synth.DRUM.presets.get(0).copy()); de.down(1, 0, new int[]{0}, true); run.apply(de, 1.5);
          check(de.activeVoices() == 0, "Synth Drums: a kick ends by itself while the pad is still held");
          Synth.Patch pl = new Synth.Patch(Synth.PLUCK, "p"); pl.set("decay", 6);
          Engine pe2 = eng.apply(pl); pe2.down(1, 0, new int[]{12}, true); run.apply(pe2, .1); float[] py = run.apply(pe2, .3);
          int best = 0; double bc = -1; for (int lag = 40; lag < 200; lag++) { double c = 0; for (int i = 0; i < 8000; i++) c += py[i] * py[i + lag]; if (c > bc) { bc = c; best = lag; } }
          double ph = rate / (double) best;
          check(Math.abs(1200 * Math.log(ph / 523.25) / Math.log(2)) < 40, String.format("Synth Pluck: an octave up rings at %.0f Hz (523 wanted, whole-sample lag)", ph));
          Synth.Patch pm = new Synth.Patch(Synth.PLUCK, "p"); pm.set("decay", .3); Engine pe3 = eng.apply(pm); pe3.down(1, 0, new int[]{0}, true); run.apply(pe3, 2.0);
          check(pe3.activeVoices() == 0, "Synth Pluck: a short string dies away while still held");
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
      // Nov 24 (Tyler's video): the stutter holds the length it is set to and never speeds up on its own
      Project fullP = new Project(); fullP.kit[0] = "imp"; fullP.bpm = 100; for (int st = 0; st < 16; st++) fullP.pats[0].setStep("imp", st, true);
      for (float sL : new float[]{8f, 4f, 1f}) {
        Engine le = new Engine(rate); le.setSnapshot(Engine.Snapshot.build(tp, k -> k.equals("imp") ? is : null, new Engine.Live()));
        le.play(); for (int i = 0; i < 30; i++) le.render(b, 256);
        le.stutterLength(sL); le.fx("stutter", true);
        java.util.Set<Integer> s2 = new java.util.TreeSet<>();
        for (int i = 0; i < 1200; i++) { le.render(b, 256); Engine.Position ps = le.heard(); if (i > 40 && ps.step >= 0) s2.add(ps.step); }
        check(s2.size() <= (int) sL + 1 && s2.size() >= Math.min((int) sL, 2), "stutter at " + sL + " sixteenths loops that many steps: " + s2);
      }
      {
        // 32nd notes: twice the hits of a one-step stutter over the same time
        int[] hits = new int[2]; float[] lens = {1f, .5f};
        for (int sq = 0; sq < 2; sq++) {
          Engine le = new Engine(rate); le.setSnapshot(Engine.Snapshot.build(fullP, k -> k.equals("imp") ? is : null, new Engine.Live()));
          le.play(); for (int i = 0; i < 30; i++) le.render(b, 256);
          le.stutterLength(lens[sq]); le.fx("stutter", true);
          int st0 = le.triggers; for (int i = 0; i < 1200; i++) le.render(b, 256); hits[sq] = le.triggers - st0;
        }
        check(hits[1] > hits[0] * 1.6, "32nd-note stutter plays about twice as often as sixteenths: " + hits[0] + " vs " + hits[1]);
      }
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
    // ---- build 105: FX faders rest where you leave them; a cleared lane lets go
    {
      PadOpts po = new PadOpts();
      java.util.Map<Integer, Double> fm = new java.util.HashMap<>(); fm.put(Engine.T_RVB, .9); fm.put(Engine.T_ECHO, 0.0);
      po.fxSet(fm);
      Project fp = Project.starter(); fp.setOpt(0, po);
      Project fb = Project.fromJson(fp.toJson(), null).project;
      check(Math.abs(fb.opt(0).fxHome(Engine.T_RVB) - .9) < 1e-9 && fb.opt(0).fxHome(Engine.T_ECHO) == 0 && fb.opt(0).fx.indexOf(Engine.T_ECHO + "=") < 0,
        "the FX strip's fader positions save with the mess: " + fb.opt(0).fx);
      // a bounce starts with the faders where they rest, so the reverb is in the file
      Project dry = Project.starter(), wet = Project.starter(); wet.setOpt(0, po);
      for (Project q5 : new Project[]{dry, wet}) { for (int j5 = 0; j5 < q5.pats[q5.cur].len; j5++) for (String k5 : q5.kit) if (!k5.isEmpty()) q5.pats[q5.cur].setStep(k5, j5, false); q5.pats[q5.cur].setStep(q5.kit[0], 0, true); }
      float[] bd = Engine.bounce(dry, lib, rate, false, 1), bw = Engine.bounce(wet, lib, rate, false, 1);
      double td5 = 0, tw5 = 0; int from = (int) (rate * .5) * 2;
      for (int i = from; i < bd.length; i++) td5 += bd[i] * bd[i];
      for (int i = from; i < bw.length; i++) tw5 += bw[i] * bw[i];
      check(tw5 > td5 * 2, String.format("a bounce keeps a pad's resting reverb: tail energy %.4f with it, %.4f without", tw5, td5));
      // a lane on pitch, then the lane is cleared while playing: the pad goes back to its own pitch
      Project lp = Project.starter(); lp.pats[0].setMotion(7, Engine.T_PITCH, 0, 12f);
      Engine le = new Engine(rate); le.setSnapshot(Engine.Snapshot.build(lp, lib, new Engine.Live()));
      float[] lb = new float[256 * 2]; double spb5 = rate * 60.0 / lp.bpm / 4.0;
      le.play(); for (int i = 0; i < (int) (2.5 * spb5 / 256); i++) le.render(lb, 256);
      double heldR = le.padRate(7);
      lp.pats[0].motion.clear(); le.setSnapshot(Engine.Snapshot.build(lp, lib, new Engine.Live()));
      for (int i = 0; i < (int) (2 * spb5 / 256); i++) le.render(lb, 256);
      check(Math.abs(heldR - 2) < 1e-6 && Math.abs(le.padRate(7) - 1) < 1e-6, "a cleared lane lets the pad go back to its own pitch: " + heldR + " then " + le.padRate(7));
      // the synth's Wave knob says the shape, not 200%
      Synth.Param wv = null; for (Synth.Param pm : Synth.ANALOG.params) if (pm.id.equals("w1")) wv = pm;
      check(wv != null && wv.show(2).equals("saw") && wv.show(1.3).equals("tri to saw 30%") && wv.show(3).equals("square"), "the Wave knob names the shape: " + (wv == null ? "?" : wv.show(2) + ", " + wv.show(1.3)));
    }
    // ---- build 106: one shared layer. Hand beats lane beats rest; letting go goes back, not to zero
    {
      float[] sb = new float[256 * 2];
      Project sp6 = Project.starter();
      PadOpts so = new PadOpts(); java.util.Map<Integer, Double> sh = new java.util.HashMap<>(); sh.put(Engine.T_RVB, .6); sh.put(Engine.T_FILTER, .5); so.fxSet(sh);
      sp6.setOpt(3, so);
      Engine se = new Engine(rate); se.setSnapshot(Engine.Snapshot.build(sp6, lib, new Engine.Live())); se.render(sb, 256);
      check(Math.abs(se.controlValue(3, Engine.T_RVB) - .6f) < 1e-6, "a pad's controls start where the mess says they rest: verb " + se.controlValue(3, Engine.T_RVB));
      se.mod(3, Engine.T_RVB, 1f); se.render(sb, 256); float held = se.controlValue(3, Engine.T_RVB);
      se.release(3, Engine.T_RVB); se.render(sb, 256);
      check(held == 1f && Math.abs(se.controlValue(3, Engine.T_RVB) - .6f) < 1e-6, "a hand lets go and the control goes back to rest, not to zero: " + held + " then " + se.controlValue(3, Engine.T_RVB));
      se.padFilter(3, 1f, 0f, true); se.render(sb, 256); float open = se.controlValue(3, Engine.T_FILTER);
      se.padFilter(3, 1f, 0f, false); se.render(sb, 256);
      check(open == 0f && Math.abs(se.controlValue(3, Engine.T_FILTER) - .5f) < 1e-6, "an XY opens the filter, and letting go puts it back where it rests: " + open + " then " + se.controlValue(3, Engine.T_FILTER));
      // a lane beats the rest, a hand beats the lane, and letting go hands it back to the lane
      Project lp6 = Project.starter(); lp6.setOpt(3, so);
      for (int j6 = 0; j6 < 16; j6++) lp6.pats[0].setMotion(3, Engine.T_RVB, j6, .2f);
      Engine le6 = new Engine(rate); le6.setSnapshot(Engine.Snapshot.build(lp6, lib, new Engine.Live())); le6.play();
      double spb6 = rate * 60.0 / lp6.bpm / 4.0;
      for (int i = 0; i < (int) (1.5 * spb6 / 256); i++) le6.render(sb, 256);
      float laneNow = le6.controlValue(3, Engine.T_RVB);
      le6.mod(3, Engine.T_RVB, .9f); for (int i = 0; i < (int) (2 * spb6 / 256); i++) le6.render(sb, 256);
      float handNow = le6.controlValue(3, Engine.T_RVB);
      le6.release(3, Engine.T_RVB); for (int i = 0; i < (int) (1.5 * spb6 / 256); i++) le6.render(sb, 256);
      check(Math.abs(laneNow - .2f) < 1e-6 && Math.abs(handNow - .9f) < 1e-6 && Math.abs(le6.controlValue(3, Engine.T_RVB) - .2f) < 1e-6,
        "lane beats rest, hand beats lane, letting go gives it back to the lane: " + laneNow + ", " + handNow + ", " + le6.controlValue(3, Engine.T_RVB));
      // an LFO on the filter closes an open pad filter from where it rests (the old LFO filter is gone)
      Project wp = Project.starter(); PadOpts wo = new PadOpts(); wo.lfoOn = true; wo.lfoTarget = Engine.T_FILTER; wo.lfoDepth = 1; wo.lfoRate = 4; wp.setOpt(3, wo);
      Engine we = new Engine(rate); we.setSnapshot(Engine.Snapshot.build(wp, lib, new Engine.Live()));
      double wlo = 1e9, whi = 0;
      for (int i = 0; i < 200; i++) { we.render(sb, 256); wlo = Math.min(wlo, we.padCutoff(3)); whi = Math.max(whi, we.padCutoff(3)); }
      check(wlo < 300 && whi > 5000, String.format("an LFO on the filter wubs an open pad (%.0f to %.0f Hz)", wlo, whi));
      // old messes: the LFO's own filter becomes the pad filter's resting place
      String old = "{\"name\":\"old\",\"bpm\":100,\"v\":3,\"kit\":[\"samples/basement-kick.wav\"],\"pats\":[{\"len\":16,\"steps\":{}}],\"padOpts\":{\"0\":{\"glide\":false,\"glideT\":0.12,\"legato\":false,\"route\":0.0,\"lfo\":\"1,0,4.0,1,0.5,2,0.3,0.4,0\"}}}";
      Project op = Project.fromJson(old, null).project;
      PadOpts oo = op.opt(0);
      check(oo.ftype == 2 && Math.abs(oo.fxHome(Engine.T_FILTER) - .7) < 1e-9 && Math.abs(oo.fxHome(Engine.T_RES) - .4) < 1e-9 && oo.lfoType == 0,
        "an old mess's LFO filter becomes the pad filter: kind " + oo.ftype + ", rest " + oo.fxHome(Engine.T_FILTER) + ", res " + oo.fxHome(Engine.T_RES));
      PadOpts again = Project.fromJson(op.toJson(), null).project.opt(0);
      check(again.ftype == 2 && Math.abs(again.fxHome(Engine.T_FILTER) - .7) < 1e-9, "and saving it again keeps it the same");
      // pads trade lanes and settings when swapped; a cleared pad drops them
      Project wq = Project.starter(); wq.setOpt(1, so); wq.pats[0].setMotion(1, Engine.T_RVB, 0, .3f);
      String k1 = wq.kit[1], k2 = wq.kit[2];
      wq.swapPads(1, 2);
      check(wq.kit[2].equals(k1) && wq.kit[1].equals(k2) && wq.opt(2).fxHome(Engine.T_RVB) > .5 && wq.opt(1).fx.isEmpty() && wq.pats[0].motion.containsKey("2:" + Engine.T_RVB) && !wq.pats[0].motion.containsKey("1:" + Engine.T_RVB),
        "swapping pads moves their lanes and settings with them");
      wq.clearPad(2);
      check(wq.opt(2).fx.isEmpty() && !wq.pats[0].motion.containsKey("2:" + Engine.T_RVB), "clearing a pad drops its lanes and settings");
    }
    // ---- build 108: velocity. Steps hit at their strength, hits at their velocity
    {
      double[] pk8 = new double[3];
      for (int sv = 0; sv < 3; sv++) {
        Project vp = Project.starter();
        for (int j8 = 0; j8 < vp.pats[vp.cur].len; j8++) for (String k8 : vp.kit) if (!k8.isEmpty()) vp.pats[vp.cur].setStep(k8, j8, false);
        vp.pats[vp.cur].setStep(vp.kit[0], 0, true); vp.pats[vp.cur].setStrength(vp.kit[0], 0, sv);
        float[] w8 = Engine.bounce(vp, lib, rate, false, 1);
        for (int i = 0; i < Math.min(w8.length, rate / 2 * 2); i++) pk8[sv] = Math.max(pk8[sv], Math.abs(w8[i]));
      }
      check(pk8[0] < pk8[1] * .7 && pk8[2] > pk8[1] * 1.08, String.format("step strength: ghost %.3f < normal %.3f < accent %.3f", pk8[0], pk8[1], pk8[2]));
      Project ap = Project.starter(); ap.pats[0].setStep(ap.kit[1], 3, true); ap.pats[0].setStrength(ap.kit[1], 3, 2); ap.pats[0].setStrength(ap.kit[1], 5, 0);
      Project ab = Project.fromJson(ap.toJson(), null).project;
      check(ab.pats[0].strength(ab.kit[1], 3) == 2 && ab.pats[0].strength(ab.kit[1], 5) == 0 && ab.pats[0].strength(ab.kit[1], 4) == 1, "step strengths save with the mess");
      double[] vk = new double[2]; float[] vb = new float[256 * 2];
      for (int q8 = 0; q8 < 2; q8++) {
        Engine ve = new Engine(rate); ve.setSnapshot(Engine.Snapshot.build(Project.starter(), lib, new Engine.Live()));
        ve.down(1, 0, new int[]{0}, false, false, q8 == 0 ? .15f : 1f);
        for (int i = 0; i < 40; i++) { ve.render(vb, 256); for (float x : vb) vk[q8] = Math.max(vk[q8], Math.abs(x)); }
      }
      check(vk[0] < vk[1] * .5, String.format("a soft hit is quieter than a hard one: %.3f vs %.3f", vk[0], vk[1]));
      // a soft hit recorded while playing comes back as a ghost step, a hard one as an accent
      Project rp = Project.starter(); rp.pats[0].clear();
      Engine.Live rl = new Engine.Live(); rl.rec = true;
      Engine re8 = new Engine(rate); re8.setSnapshot(Engine.Snapshot.build(rp, lib, rl)); re8.play();
      for (int i = 0; i < 4; i++) re8.render(vb, 256);
      re8.down(1, 0, new int[]{0}, false, false, .3f); re8.render(vb, 256);
      Engine.RecEvent ev8 = re8.pollRec();
      check(ev8 != null && ev8.strength() == 0, "a soft recorded hit lands as a ghost step: " + (ev8 == null ? "none" : ev8.strength()));
      check(new Engine.RecEvent(Engine.RecEvent.STEP, 0, "k", 0, null, 1, 1f).strength() == 2 && new Engine.RecEvent(Engine.RecEvent.STEP, 0, "k", 0, null, 1).strength() == 1, "a full hit records as an accent, a plain tap as normal");
    }
    // ---- build 6 of the overhaul: step chance and loop conditions
    {
      int[] heard = new int[3]; int[] cond = {101, 50, 0};
      boolean[] firstOfTwo = new boolean[8];
      for (int ci = 0; ci < 3; ci++) {
        Project cp = Project.starter(); Project.Pattern pp = cp.pats[cp.cur];
        for (int j9 = 0; j9 < pp.len; j9++) for (String k9 : cp.kit) if (!k9.isEmpty()) pp.setStep(k9, j9, false);
        pp.setStep(cp.kit[0], 0, true); pp.setChance(cp.kit[0], 0, cond[ci]);
        float[] w9 = Engine.bounce(cp, lib, rate, false, 8);
        int loopLen = w9.length / 2 / 8;
        for (int lq = 0; lq < 8; lq++) {
          double pk9 = 0;
          for (int i = lq * loopLen; i < lq * loopLen + Math.min(loopLen, rate / 4); i++) pk9 = Math.max(pk9, Math.abs(w9[i * 2]));
          if (pk9 > .05) { heard[ci]++; if (ci == 0) firstOfTwo[lq] = true; }
        }
      }
      check(heard[0] == 4 && firstOfTwo[0] && !firstOfTwo[1] && firstOfTwo[2] && !firstOfTwo[3], "a 1:2 step plays on the 1st time round of every 2: heard " + heard[0] + " of 8, " + java.util.Arrays.toString(firstOfTwo));
      check(heard[1] > 0 && heard[1] < 8, "a 50% step plays some loops and not others: " + heard[1] + " of 8");
      check(heard[2] == 0, "a 0% step never plays: " + heard[2]);
      Project cq = Project.starter(); cq.pats[0].setStep(cq.kit[1], 2, true); cq.pats[0].setChance(cq.kit[1], 2, 75); cq.pats[0].setChance(cq.kit[1], 6, 103);
      Project cb = Project.fromJson(cq.toJson(), null).project;
      check(cb.pats[0].chance(cb.kit[1], 2) == 75 && cb.pats[0].chance(cb.kit[1], 6) == 103 && cb.pats[0].chance(cb.kit[1], 3) == 100, "step chances save with the mess");
      check(Project.Pattern.chanceLabel(102).equals("2:2") && Project.Pattern.chanceLabel(25).equals("25%") && Project.Pattern.chanceLabel(100).isEmpty(), "chance labels read plainly");
    }
    // ---- build 7 of the overhaul: stems and a MIDI file
    {
      Project sp = p;
      int sounds = 0;
      for (String k : sp.kit) if (!k.isEmpty() && sp.pats[sp.cur].usedBy(k)) sounds++;
      java.util.Map<String, float[]> stems = Engine.bounceStems(sp, lib, rate, false, 1);
      int len0 = -1; boolean same = true;
      for (float[] w0 : stems.values()) { if (len0 < 0) len0 = w0.length; else if (w0.length != len0) same = false; }
      check(sounds > 1 && stems.size() == sounds && same, "one stem per sound in the loop, all the same length: " + stems.size() + " of " + sounds);
      float[] mix = Engine.bounce(sp, lib, rate, false, 1);
      double em = 0, es = 0;
      for (int i = 0; i < Math.min(mix.length, len0); i++) { double sum = 0; for (float[] w0 : stems.values()) sum += w0[i]; em += mix[i] * mix[i]; es += sum * sum; }
      check(es > em * .4 && es < em * 2.5, String.format("the stems add up to about the mix: %.2f of its energy", es / Math.max(1e-9, em)));
      Project mp = Project.starter(); Project.Pattern mq = mp.pats[mp.cur];
      for (int j9 = 0; j9 < mq.len; j9++) for (String k9 : mp.kit) if (!k9.isEmpty()) mq.setStep(k9, j9, false);
      mq.setStep(mp.kit[0], 0, true); mq.setStep(mp.kit[0], 8, true); mq.setStrength(mp.kit[0], 8, 2);
      mq.setStep(mp.kit[1], 4, true);
      java.io.ByteArrayOutputStream mo = new java.io.ByteArrayOutputStream();
      mo.writeBytes(MidiOut.write(mp, false, 2, null));
      byte[] mb = mo.toByteArray();
      int tracks = ((mb[10] & 0xFF) << 8) | (mb[11] & 0xFF);
      int ons = 0, loud = 0;
      for (int i = 14; i + 2 < mb.length; i++) if ((mb[i] & 0xF0) == 0x90 && mb[i + 1] == 60) { ons++; if ((mb[i + 2] & 0xFF) == 127) loud++; }
      check(new String(mb, 0, 4).equals("MThd") && tracks == 3, "the MIDI file has a tempo track and one per sound: " + tracks);
      check(ons == 6 && loud == 2, "two loops of three hits, the accents at full velocity: " + ons + " notes, " + loud + " loud");
    }
    // ---- the last build of the overhaul: step locks and the master bus
    {
      // a hat on steps 1 and 5; step 1 locks the filter nearly shut, step 5 should be bright again
      double[] hfA = new double[2], hfB = new double[2];
      for (int lk = 0; lk < 2; lk++) {
        Project lp = Project.starter(); Project.Pattern lq = lp.pats[lp.cur];
        for (int j9 = 0; j9 < lq.len; j9++) for (String k9 : lp.kit) if (!k9.isEmpty()) lq.setStep(k9, j9, false);
        String hk = lp.kit[2];
        lq.setStep(hk, 0, true); lq.setStep(hk, 4, true);
        if (lk == 1) lq.setLock(hk, Engine.T_FILTER, 0, .92f);
        float[] wl = Engine.bounce(lp, lib, rate, false, 1);
        int stepF = (int) Math.round(rate * 60.0 / lp.bpm / 4);
        double[] hf = lk == 0 ? hfA : hfB;
        for (int h = 0; h < 2; h++) {
          int from = h * 4 * stepF, to = Math.min(from + stepF, wl.length / 2 - 1);
          for (int i = from + 1; i < to; i++) { double d = wl[i * 2] - wl[(i - 1) * 2]; hf[h] += d * d; }
        }
      }
      check(hfB[0] < hfA[0] * .5, String.format("a filter lock on step 1 darkens that hit: %.4f vs %.4f unlocked", hfB[0], hfA[0]));
      check(hfB[1] > hfA[1] * .7, String.format("and step 5 is bright again (it snaps back): %.4f vs %.4f", hfB[1], hfA[1]));
      Project lp2 = Project.starter(); lp2.pats[0].setStep(lp2.kit[2], 3, true); lp2.pats[0].setLock(lp2.kit[2], Engine.T_RVB, 3, .6f);
      Project lb = Project.fromJson(lp2.toJson(), null).project;
      check(Math.abs(lb.pats[0].lock(lb.kit[2], Engine.T_RVB, 3) - .6f) < 1e-3 && Float.isNaN(lb.pats[0].lock(lb.kit[2], Engine.T_RVB, 2)) && lb.pats[0].hasLock(lb.kit[2], 3),
        "step locks save with the mess");
      lb.pats[0].setLock(lb.kit[2], Engine.T_RVB, 3, Float.NaN);
      check(lb.pats[0].locks.isEmpty(), "taking the last lock off leaves nothing behind");
      // the master bus: squash .5 and level 1 sound exactly like before; level halves; squash evens out peaks
      float[] m5 = Engine.bounce(p, lib, rate, false, 1);
      Project pq = p.copy(); pq.level = .5;
      float[] mh = Engine.bounce(pq, lib, rate, false, 1);
      double r5 = 0, rh = 0; for (int i = 0; i < Math.min(m5.length, mh.length); i++) { r5 += m5[i] * m5[i]; rh += mh[i] * mh[i]; }
      check(rh < r5 * .4 && rh > r5 * .15, String.format("master level .5 is about a quarter of the power: %.2f", rh / r5));
      // squash evens the loop out: the spread between its loud and quiet moments (50 ms windows) shrinks
      double[] spread = new double[2];
      for (int c9 = 0; c9 < 2; c9++) {
        Project pc = p.copy(); pc.squash = c9 == 0 ? 0 : 1;
        float[] wc = Engine.bounce(pc, lib, rate, false, 1, true);
        int win = rate / 20, nw = wc.length / 2 / win;
        double[] rw = new double[nw];
        for (int w9 = 0; w9 < nw; w9++) { double e9 = 0; for (int i = w9 * win; i < (w9 + 1) * win; i++) e9 += wc[i * 2] * wc[i * 2]; rw[w9] = Math.sqrt(e9 / win) + 1e-6; }
        java.util.Arrays.sort(rw);
        spread[c9] = rw[(int) (nw * .9)] / rw[(int) (nw * .3)];
      }
      check(spread[1] < spread[0] * .8, String.format("more squash evens out the loop: loud to quiet %.2f (squash 1) vs %.2f (off)", spread[1], spread[0]));
      Project ps9 = p.copy(); ps9.squash = .8; ps9.level = 1.2;
      Project pb9 = Project.fromJson(ps9.toJson(), null).project;
      check(Math.abs(pb9.squash - .8) < 1e-9 && Math.abs(pb9.level - 1.2) < 1e-9 && !p.toJson().contains("squash"), "the master bus saves with the mess, and only when changed");
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
    repeatPatternTests(rate);
    mouthTests();
    if (failures > 0) System.exit(1);
  }

  static void repeatPatternTests(int rate) {
    // ---- repeat patterns (Tyler: "trigger every two notes, then skip one"): x plays, . rests
    {
      float[] tone = new float[4410]; for (int i = 0; i < tone.length; i++) tone[i] = (float) (.5 * Math.sin(2 * Math.PI * 440 * i / 44100.0));
      Engine.SampleSource ts = k -> new Sample(k, tone, 44100);
      Project rp = new Project(); rp.kit[0] = "tone"; rp.bpm = 120;
      double spbR = rate * 60.0 / rp.bpm / 4.0;
      float[] rb = new float[512];
      for (String pat : new String[]{"", "xx.", "x.", "x..x..x."}) {
        Engine.Live rl = new Engine.Live(); rl.nr = true; rl.nrRate = 1; rl.nrPat = pat;
        Engine re = new Engine(rate); re.setSnapshot(Engine.Snapshot.build(rp, ts, rl));
        re.down(1, 0, new int[]{0}, false);
        // 24 repeats while stopped: the press is repeat 0, then one every sixteenth
        int rt0 = re.triggers;
        for (int i = 0; i < (int) (24 * spbR / 256) - 1; i++) re.render(rb, 256);
        int rgot = re.triggers - rt0;
        int rwant = 0; for (int i = 0; i < 24; i++) if (rl.patOn(i)) rwant++;
        // playing: the pattern counts from the top of the loop, one letter per sixteenth
        Engine rq = new Engine(rate); rq.setSnapshot(Engine.Snapshot.build(rp, ts, rl));
        rq.down(1, 0, new int[]{0}, false); rq.play();
        int rt1 = rq.triggers;
        for (int i = 0; i < (int) (16 * spbR / 256) - 1; i++) rq.render(rb, 256);
        int rgotP = rq.triggers - rt1;
        int rwantP = 0; for (int i = 0; i < 16; i++) if (rl.patOn(i)) rwantP++;
        check(Math.abs(rgot - rwant) <= 1 && Math.abs(rgotP - rwantP) <= 1, String.format("Repeat pattern \"%s\": %d of 24 repeats play while stopped (%d wanted), %d of 16 while playing (%d wanted)", pat.isEmpty() ? "every one" : pat, rgot, rwant, rgotP, rwantP));
      }
      // straight, triplet and dotted rates while playing (note #18): one bar of 16 steps, a held pad
      for (double[] rw : new double[][]{{4, 4}, {4.0 / 3, 12}, {6, 3}, {2.0 / 3, 24}, {3, 6}, {1.5, 11}}) {
        Engine.Live tl = new Engine.Live(); tl.nr = true; tl.nrRate = rw[0];
        Engine te = new Engine(rate); te.setSnapshot(Engine.Snapshot.build(rp, ts, tl));
        te.down(1, 0, new int[]{0}, false); te.play();
        int tt0 = te.triggers;
        for (int i = 0; i < (int) (16 * spbR / 256); i++) te.render(rb, 256);
        int got = te.triggers - tt0 - 1; // less the press itself
        check(got == (int) rw[1], String.format("Repeat every %.3f steps while playing: %d in a bar (%d wanted)", rw[0], got, (int) rw[1]));
      }
      // the LFO strip: a sine of one quarter note runs with the loop; its filter follows the wave; Hi and Band work
      {
        PadOpts lo = new PadOpts(); lo.lfoOn = true; lo.lfoWave = 0; lo.lfoRate = 4; lo.lfoTarget = Engine.T_FILTER; lo.lfoDepth = 1; lo.lfoType = 1; lo.lfoCut = .5; lo.lfoRes = .2;
        Project lp = new Project(); lp.kit[0] = "tone"; lp.bpm = 120; lp.setOpt(0, lo);
        Engine le = new Engine(rate); le.setSnapshot(Engine.Snapshot.build(lp, ts, new Engine.Live()));
        le.play();
        double vmin = 9, vmax = -9, cmin = 1e9, cmax = 0; int peakBlock = -1;
        int blocks = (int) (4 * spbR / 256);
        for (int i = 0; i < blocks; i++) {
          le.render(rb, 256);
          double v = le.lfoValue(0);
          if (v > vmax) { vmax = v; peakBlock = i; }
          vmin = Math.min(vmin, v);
          cmin = Math.min(cmin, le.padCutoff(0)); cmax = Math.max(cmax, le.padCutoff(0));
        }
        check(vmax > .95 && vmin < -.95 && Math.abs(peakBlock - blocks / 4) <= 2, String.format("LFO: a 1/4 sine peaks a quarter of the way through the beat (%.2f to %.2f, peak at block %d of %d)", vmin, vmax, peakBlock, blocks));
        check(cmin < 300 && cmax > 5000, String.format("LFO: the strip's filter sweeps with the wave (%.0f to %.0f Hz)", cmin, cmax));
        // high pass and band pass: a low tone through a high pass set high comes out much quieter than through low pass open
        float[] low = new float[44100]; for (int i = 0; i < low.length; i++) low[i] = (float) (.5 * Math.sin(2 * Math.PI * 110 * i / 44100.0));
        Engine.SampleSource lsrc = k -> new Sample(k, low, 44100);
        double[] peaks = new double[3];
        for (int ty = 1; ty <= 3; ty++) {
          // build 106: the pad filter's kind and where it rests, half closed (the LFO has no filter of its own now)
          PadOpts fo = new PadOpts(); fo.ftype = ty; java.util.Map<Integer, Double> fh = new java.util.HashMap<>(); fh.put(Engine.T_FILTER, .5); fo.fxSet(fh);
          Project fp = new Project(); fp.kit[0] = "low"; fp.bpm = 120; fp.setOpt(0, fo);
          Engine fe = new Engine(rate); fe.setSnapshot(Engine.Snapshot.build(fp, lsrc, new Engine.Live()));
          fe.down(1, 0, new int[]{0}, false);
          float[] ob = new float[1024]; double pk = 0;
          for (int i = 0; i < 40; i++) { fe.render(ob, 512); if (i > 10) for (float x : ob) pk = Math.max(pk, Math.abs(x)); }
          peaks[ty - 1] = pk;
        }
        check(peaks[1] < peaks[0] * .2 && peaks[2] < peaks[0] * .5, String.format("pad filter kinds, half closed: a 110 Hz tone through low %.3f, high %.3f, band %.3f", peaks[0], peaks[1], peaks[2]));
      }
      // the finer time grid (Nov 18): 1/16 triplet repeats record, the off-grid ones as ticks, and play back on time
      {
        Engine.Live gl = new Engine.Live(); gl.nr = true; gl.nrRate = 2.0 / 3; gl.rec = true;
        Engine ge = new Engine(rate); ge.setSnapshot(Engine.Snapshot.build(rp, ts, gl));
        ge.down(1, 0, new int[]{0}, false); ge.play();
        for (int i = 0; i < (int) (16 * spbR / 256); i++) ge.render(rb, 256);
        int stepsN = 0, offsN = 0; boolean ticksOk = true; Engine.RecEvent ev;
        Project gp = rp.copy();
        while ((ev = ge.pollRec()) != null) {
          if (ev.type == Engine.RecEvent.STEP) stepsN++;
          else if (ev.type == Engine.RecEvent.OFF) { offsN++; if (ev.step % 8 != 0) ticksOk = false; gp.pats[0].addOff(ev.key, ev.step); }
        }
        check(stepsN >= 7 && offsN >= 15 && ticksOk, String.format("finer grid: a bar of 1/16 triplets records %d hits on the sixteenths and %d between them, all on thirds of a step", stepsN, offsN));
        String rk = rp.kit[0];
        check(gp.pats[0].offs.get(rk) != null && gp.pats[0].offsIn(rk, 0) == 1, "finer grid: the triplet a third of the way into step 1 is stored there");
        Project lp2 = Project.fromJson(gp.toJson(), k -> true).project;
        check(lp2.pats[0].offs.get(rk) != null && lp2.pats[0].offs.get(rk).equals(gp.pats[0].offs.get(rk)), "finer grid: off-grid hits survive saving");
        // playback: a hit stored a third into step 1 sounds a third of a step after one stored on the step itself
        double spbP = rate * 60.0 / 120 / 4;
        int[] onset = new int[2];
        for (int c = 0; c < 2; c++) {
          Project one = new Project(); one.bpm = 120; one.kit[0] = rk; one.pats[0].addOff(rk, c == 0 ? 0 : 8);
          Engine pe = new Engine(rate); pe.setSnapshot(Engine.Snapshot.build(one, ts, new Engine.Live())); pe.play();
          float[] ob = new float[128]; onset[c] = -1;
          for (int f = 0; f < 4 * spbP && onset[c] < 0; f += 64) { pe.render(ob, 64); for (int q = 0; q < 64; q++) if (Math.abs(ob[q * 2]) > 1e-4) { onset[c] = f + q; break; } }
        }
        check(onset[0] >= 0 && Math.abs(onset[1] - onset[0] - spbP / 3) < 64, String.format("finer grid: the stored triplet sounds a third of a step late (%d frames after the on-step hit, %.0f wanted)", onset[1] - onset[0], spbP / 3));
      }
      // the new effects (Nov 18): Pump ducks the start of each beat, Freeze holds a note past its end
      {
        Project fp2 = new Project(); fp2.bpm = 120; fp2.kit[0] = "long0";
        float[] lt = new float[44100 * 4]; for (int i = 0; i < lt.length; i++) lt[i] = (float) (.3 * Math.sin(2 * Math.PI * 220 * i / 44100.0));
        float[] st = new float[4410]; for (int i = 0; i < st.length; i++) st[i] = (float) (.3 * Math.sin(2 * Math.PI * 220 * i / 44100.0));
        Engine.SampleSource src2 = k -> new Sample(k, k.equals("long0") ? lt : st, 44100);
        Engine pu = new Engine(rate); pu.setSnapshot(Engine.Snapshot.build(fp2, src2, new Engine.Live())); pu.play();
        pu.mod(0, Engine.T_PUMP, 1f);
        pu.down(7000, 0, new int[]{0}, false, true);
        double spbE = rate * 60.0 / 120 / 4; float[] ob2 = new float[512];
        double early = 0, late = 0; int f2 = 0;
        for (int i = 0; i < (int) (8 * spbE / 256); i++) {
          pu.render(ob2, 256);
          double pk = 0; for (int q = 0; q < 256; q++) pk = Math.max(pk, Math.abs(ob2[q * 2]));
          double posBeat = ((f2 / spbE) % 4) / 4.0;
          if (f2 > 4 * spbE) { if (posBeat > .03 && posBeat < .1) early = Math.max(early, pk); else if (posBeat > .7) late = Math.max(late, pk); }
          f2 += 256;
        }
        check(late > 0 && early < late * .7, String.format("Pump: the start of the beat is ducked (%.3f) under the end of it (%.3f)", early, late));
        Project fz = new Project(); fz.bpm = 120; fz.kit[0] = "short";
        Engine fe2 = new Engine(rate); fe2.setSnapshot(Engine.Snapshot.build(fz, src2, new Engine.Live()));
        fe2.mod(0, Engine.T_FREEZE, 1f);
        fe2.down(7001, 0, new int[]{0}, false);
        for (int i = 0; i < 40; i++) fe2.render(ob2, 256); // over 0.2 s: the 0.1 s sound would be over
        double pz = 0; for (int q = 0; q < 256; q++) pz = Math.max(pz, Math.abs(ob2[q * 2]));
        check(pz > .05, String.format("Freeze: a 0.1 s sound still sounds after 0.2 s (%.3f)", pz));
      }
      // the lag creep (Nov 18): a pile-up of long notes stays under the soft voice limit, and a heavy scene renders in time
      {
        float[] longT = new float[44100 * 4]; for (int i = 0; i < longT.length; i++) longT[i] = (float) (.2 * Math.sin(2 * Math.PI * 220 * i / 44100.0));
        Engine.SampleSource lsrc = k -> new Sample(k, longT, 44100);
        Project hp = new Project(); hp.bpm = 120;
        for (int pd = 0; pd < 16; pd++) {
          hp.kit[pd] = "long" + pd;
          PadOpts ho = new PadOpts(); ho.lfoOn = true; ho.lfoWave = pd % 6; ho.lfoRate = 2; ho.lfoTarget = Engine.T_FILTER; ho.lfoDepth = .8; ho.lfoType = 1 + pd % 3; ho.lfoCut = .6; ho.lfoRes = .3;
          hp.setOpt(pd, ho);
        }
        Engine he = new Engine(rate); he.setSnapshot(Engine.Snapshot.build(hp, lsrc, new Engine.Live()));
        he.play();
        // and the four new effects on every pad (Nov 18): the full house has to stay in time with them too
        for (int pd = 0; pd < 16; pd++) { he.mod(pd, Engine.T_PUMP, .6f); he.mod(pd, Engine.T_WARM, .5f); he.mod(pd, Engine.T_PHASER, .7f); if (pd % 4 == 0) he.mod(pd, Engine.T_FREEZE, 1f); }
        float[] hb = new float[192 * 2]; int most = 0; long worst = 0, total = 0; int blocks = 0;
        java.util.List<Long> times = new java.util.ArrayList<>();
        for (int i = 0; i < 120; i++) {
          he.down(5000 + i, i % 16, new int[]{(i * 5) % 24, (i * 5 + 4) % 24, (i * 5 + 7) % 24}, false);
          for (int b = 0; b < 3; b++) {
            long t0 = System.nanoTime(); he.render(hb, 192); long took = System.nanoTime() - t0;
            if (i > 20) { worst = Math.max(worst, took); total += took; blocks++; times.add(took); }
            most = Math.max(most, he.activeVoices());
          }
        }
        double avgMs = total / 1e6 / blocks, budgetMs = 192 * 1000.0 / rate;
        check(most <= Engine.VOICE_CAP + 24 && he.stolen > 0, String.format("voice limit: 360 long notes on 16 pads peak at %d voices (soft limit %d, %d faded out)", most, Engine.VOICE_CAP, he.stolen));
        // the median, not the mean (Nov 21): a shared CI machine pauses now and then (a 15 ms stall in one block), which
        // says nothing about the engine; the median still catches a real slowdown, since it moves every block
        java.util.Collections.sort(times);
        double medMs = times.get(times.size() / 2) / 1e6;
        check(medMs < budgetMs * .5, String.format("speed: a full house with 16 LFOs and filters renders a %.1f ms block in %.2f ms (median; average %.2f, worst %.2f ms)", budgetMs, medMs, avgMs, worst / 1e6));
      }
      // Hold plus a pattern: a latched note keeps the pattern after the finger lifts
      Engine.Live hl = new Engine.Live(); hl.nr = true; hl.nrPat = "xx.";
      Engine he2 = new Engine(rate); he2.setSnapshot(Engine.Snapshot.build(rp, ts, hl));
      he2.down(1000, 0, new int[]{0}, false, true); he2.up(1000);
      int rh0 = he2.triggers;
      for (int i = 0; i < (int) (12 * spbR / 256) - 1; i++) he2.render(rb, 256);
      check(Math.abs((he2.triggers - rh0) - 8) <= 1, "Repeat pattern with Hold: a held pad goes hit, hit, skip after the finger lifts (" + (he2.triggers - rh0) + " of 12, 8 wanted)");
    }
  }

  static void mouthTests() {
    // ---- mouth control: vowels into a number (dark "ooo" low, bright "eee" high), loudness, a gate
    {
      int mr = 16000;
      // a voice at 120 Hz through two formants, like a mouth shaping a vowel
      java.util.function.BiFunction<double[], Double, float[]> vowelOf = (fm, sec) -> {
        float[] o = new float[(int) (sec * mr)];
        for (int k = 1; k * 120 < mr / 2; k++) {
          double f = k * 120, amp = 0;
          for (int j = 0; j < 2; j++) amp += 1 / (1 + Math.pow((f - fm[j]) / 90, 2));
          amp /= k * .6 + .4;
          for (int i = 0; i < o.length; i++) o[i] += (float) (.12 * amp * Math.sin(2 * Math.PI * f * i / mr));
        }
        return o;
      };
      double[][] fms = {{300, 870}, {730, 1090}, {270, 2290}};
      String[] names = {"ooo", "aah", "eee"};
      float[] vs = new float[3];
      for (int j = 0; j < 3; j++) { MouthTracker mt = new MouthTracker(mr); float[] x = vowelOf.apply(fms[j], .4); mt.feed(x, 0, x.length); vs[j] = mt.on ? mt.vowel : -1; }
      check(vs[0] >= 0 && vs[0] < vs[1] && vs[1] < vs[2] && vs[2] - vs[0] > .45, String.format("Mouth: vowels come out in order, ooo %.2f < aah %.2f < eee %.2f", vs[0], vs[1], vs[2]));
      MouthTracker mq = new MouthTracker(mr); float[] hush = new float[mr / 2]; java.util.Random rnd = new java.util.Random(4);
      for (int i = 0; i < hush.length; i++) hush[i] = (float) (rnd.nextGaussian() * .0005);
      mq.feed(hush, 0, hush.length);
      check(!mq.on && mq.level < .05, String.format("Mouth: a quiet room is off (%.0f dB, level %.2f)", mq.db, mq.level));
      // how fast it follows: ooo then eee, time until the number is most of the way there
      MouthTracker mf = new MouthTracker(mr); float[] a1 = vowelOf.apply(fms[0], .3), a2 = vowelOf.apply(fms[2], .3);
      mf.feed(a1, 0, a1.length); float from = mf.vowel; int ms = -1;
      for (int i = 0; i < a2.length; i += 16) { mf.feed(a2, i, Math.min(16, a2.length - i)); if (ms < 0 && mf.vowel > from + (vs[2] - from) * .8) ms = i * 1000 / mr; }
      check(ms > 0 && ms < 70, "Mouth: from ooo to eee it gets 80% of the way in " + ms + " ms (under 70 wanted)");
      mf.feed(hush, 0, hush.length);
      check(!mf.on, "Mouth: stop making the sound and it goes off");
    }
  }
}
