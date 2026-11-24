/*
 * Loads the shared mess fixtures with the Android engine's own Project.fromJson, saves them again with toJson, and
 * checks the result. Plain Java, no Android. From the repo root:
 *
 *   javac -d build/rt app/src/main/java/com/tideclock/messmachine/engine/*.java shared/fixtures/RoundTrip.java
 *   java -cp build/rt RoundTrip shared/fixtures app/src/main/assets/sounds/
 *
 * The second argument (the sounds folder) is optional. With it, keys are checked against the real library.json the
 * way the app does; without it every sound key counts as known.
 */
import com.tideclock.messmachine.engine.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class RoundTrip {
  static int failures = 0;
  static void check(boolean ok, String what) { System.out.println((ok ? "PASS " : "FAIL ") + what); if (!ok) failures++; }

  static String read(Path p) throws IOException { return new String(Files.readAllBytes(p), StandardCharsets.UTF_8); }

  /** The file as the writer would print it: parsed, "at" taken out, written back compact. */
  static String canonical(String text) {
    Map<String, Object> m = Json.map(Json.parse(text));
    m.remove("at");
    return Json.write(m);
  }

  static void firstDiff(String a, String b) {
    int i = 0;
    while (i < a.length() && i < b.length() && a.charAt(i) == b.charAt(i)) i++;
    System.out.println("  first difference at " + i + ":");
    System.out.println("  file:  ..." + a.substring(Math.max(0, i - 40), Math.min(a.length(), i + 60)));
    System.out.println("  saved: ..." + b.substring(Math.max(0, i - 40), Math.min(b.length(), i + 60)));
  }

  public static void main(String[] args) throws Exception {
    Path dir = Paths.get(args.length > 0 ? args[0] : "shared/fixtures");
    Project.Known known = null;
    if (args.length > 1) {
      String base = args[1].endsWith("/") ? args[1] : args[1] + "/";
      Library lib = new Library(p -> java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(p)), base);
      known = lib::has;
      System.out.println("library: " + lib.sounds.size() + " sounds");
    }

    // 1. the example is already in the writer's own form: load, save, and get the same file back
    String ex = read(dir.resolve("example.mess.json"));
    Project.Loaded L = Project.fromJson(ex, known);
    Project p = L.project;
    check(L.missing == 0, "example: every sound is known (missing " + L.missing + ")");
    String saved = p.toJson(false);
    String want = canonical(ex);
    boolean same = saved.equals(want);
    check(same, "example: re-saved file equals the fixture (ignoring \"at\" and whitespace)");
    if (!same) firstDiff(want, saved);
    String again = Project.fromJson(saved, known).project.toJson(false);
    check(again.equals(saved), "example: a second load and save changes nothing");

    // 2. spot checks: what the example means, read back from the model
    Project.Pattern A = p.pats[0], B = p.pats[1];
    String K = "samples/basement-kick.wav", C = "samples/wet-clap.wav", S = "synth:1", LP = "loop:ametrine-1";
    check(p.bpm == 96 && p.swing == 20 && Math.abs(p.squash - .6) < 1e-9 && p.level == 1, "example: tempo 96, swing 20, squash 0.6, level 1");
    check("Minor".equals(p.scale) && p.root == 9 && !p.fold, "example: A minor, no fold");
    check(p.songMode && p.songLen() == 4 && p.song[0] == 0 && p.song[1] == 1 && p.song[2] == -1 && p.song[3] == 1, "example: song A B rest B, song mode on");
    check(A.len == 16 && B.len == 32, "example: loop A is 1 bar, loop B is 2 bars");
    check(A.step(K, 0) && A.step(K, 6) && A.step(K, 8) && A.step(K, 11) && !A.step(K, 1), "example: kick steps 0 6 8 11");
    check(B.step(K, 28) && B.step(LP, 16), "example: loop B reaches step 28 and 16");
    List<Project.Note> ns = A.notes(S);
    check(ns.size() == 2 && ns.get(1).s == 8 && ns.get(1).p == -5 && ns.get(1).l == 4, "example: synth notes 0.0.4 and 8.-5.4");
    check(A.strength(K, 0) == 2 && A.strength(K, 6) == 0 && A.strength(K, 8) == 1, "example: accent on 0, ghost on 6, normal elsewhere");
    check(A.chance(C, 12) == 50 && A.chance(C, 4) == 100, "example: clap step 12 plays half the time");
    check(A.hasOff(C, 92) && A.hasOff(C, 284) && A.offsIn(C, 3) == 1, "example: clap off-grid hits at ticks 92 and 284");
    check(Math.abs(A.lock(K, Engine.T_FILTER, 0) - .7f) < 1e-6 && Math.abs(A.lock(K, Engine.T_FILTER, 8) - .3f) < 1e-6 && Float.isNaN(A.lock(K, Engine.T_FILTER, 1)), "example: kick filter locks on steps 0 and 8");
    float[] mo = A.motion.get("1:1");
    check(mo != null && Math.abs(mo[4] - .4f) < 1e-6 && Float.isNaN(mo[5]), "example: pad 1 filter lane");
    check(p.edit(C).mode.equals("gate") && p.edit(C).tune == -2 && p.edit(LP).held.equals("beat") && p.edit(LP).beatN == 2 && Math.abs(p.edit(LP).rvb - .3) < 1e-9, "example: edits");
    Synth.Patch sp = p.synth(S);
    check(sp != null && sp.kind == Synth.PLUCK && Math.abs(sp.get("decay") - 2.5) < 1e-9, "example: synth:1 is a pluck");
    check(p.opt(1).route == 1 && Math.abs(p.opt(1).fxHome(Engine.T_RVB) - .25) < 1e-9 && p.opt(2).glide && p.opt(2).lfoOn && p.opt(2).ftype == 2, "example: pad options");

    // 3. older files: they load, and what they mean survives
    Project v1 = Project.fromJson(read(dir.resolve("legacy-v1.mess.json")), known).project;
    check(v1.pats[0].step(K, 4) && v1.pats[0].step(C, 12) && v1.songLen() == 0 && "Chromatic".equals(v1.scale) && v1.swing == 10,
      "legacy v1: top-level steps become loop A; no scale means Chromatic");

    Project.Loaded v2L = Project.fromJson(read(dir.resolve("legacy-v2-inst.mess.json")), known);
    Project v2 = v2L.project;
    String hit = "qb:stwretch-hit";
    check(v2L.missing == 0 && v2.kit[1].equals(hit), "legacy v2: the old qb: key still loads (library alias) and keeps its spelling");
    check(v2.pats[0].len == 32 && v2.pats[0].notes(hit).size() == 2 && v2.pats[0].notes(hit).get(1).p == -3, "legacy v2: \"inst\" plus a notes string");
    check(Project.fromJson(v2.toJson(), known).project.pats[0].notes(hit).size() == 2, "legacy v2: re-saved as a notes map");

    Project.Loaded sL = Project.fromJson(read(dir.resolve("site-v3.mess.json")), known);
    Project st = sL.project;
    if (known != null) {
      // Nov 23: missing sounds keep their pads (silent, shown as Missing), so a save never wipes them
      check(sL.missing == 2 && st.kit[1].equals("a:uploaded1") && st.kit[2].equals("bn:lkj3x9512") && st.kit[3].equals(C), "site v3: the upload and the workbench sound are missing but keep their pads");
      String sv = st.toJson();
      check(st.pats[0].step("a:uploaded1", 2) && st.pats[0].notes("a:uploaded1").size() == 1 && sv.contains("\"a:uploaded1\":\"0010001000100010\""), "site v3: steps and notes of a missing sound stay, and are in the next save");
      check(st.sel == 1, "site v3: sel stays on the missing pad");
    }

    // 3b. keys from other platforms and missing sounds survive a load and save (Nov 23)
    Project.Loaded oL = Project.fromJson(read(dir.resolve("other-platform.mess.json")), known);
    String os = oL.project.toJson();
    Project ob = Project.fromJson(os, known).project;
    check(ob.kit[1].equals("ios:take-7") && ob.kit[3].equals("web:mic-3") && ob.pats[0].step("ios:take-7", 2) && ob.pats[0].notes("web:mic-3").size() == 1 && ob.edit("ios:take-7").tune == 3,
      "other platform: pads with sounds this phone lacks keep their sound, steps, notes and edits");
    check(os.contains("\"cloud\":{\"owner\":\"user-1\",\"rev\":12,\"shared\":true}") && os.contains("\"iosLane\":{\"points\":[0,0.5,1],\"note\":\"kept as is\"}"),
      "other platform: unknown top-level and per-loop keys are written back unchanged");
    if (known != null) check(oL.missing == 2, "other platform: two missing sounds counted: " + oL.missing);
    check("Dorian".equals(st.scale) && st.root == 2 && st.fold && st.edit(C).rev, "site v3: scale, root, fold and edits");
    check(!st.songMode && st.songLen() == 1, "site v3: no mode key means loop mode");

    PadOpts old = Project.fromJson(read(dir.resolve("android-pre106.mess.json")), known).project.opt(0);
    check(old.ftype == 2 && Math.abs(old.fxHome(Engine.T_FILTER) - .7) < 1e-9 && Math.abs(old.fxHome(Engine.T_RES) - .4) < 1e-9 && old.lfoType == 0,
      "pre-106: the LFO's own filter moves to the pad filter (ftype 2, filter rest 0.7, res 0.4)");

    // 4. things the reader tolerates
    Project empty = Project.fromJson("{}", known).project;
    check(empty.bpm == 112 && empty.loadedCount() == 0 && "Chromatic".equals(empty.scale), "{} loads as an empty mess");
    Project odd = Project.fromJson("{\"bpm\":999,\"swing\":-5,\"root\":40,\"cur\":12,\"kit\":[\"samples/basement-kick.wav\",\"samples/basement-kick.wav\"],\"pats\":[{\"len\":20}],\"scale\":\"Nope\"}", known).project;
    check(odd.bpm == 180 && odd.swing == 0 && odd.root == 11 && odd.cur == 7 && odd.pats[0].len == 16 && odd.kit[1].isEmpty() && "Chromatic".equals(odd.scale),
      "out-of-range values clamp; a bad len is 16; a repeated key keeps only its first pad");
    boolean threw = false;
    try { Project.fromJson("{\"name\":", known); } catch (RuntimeException e) { threw = true; }
    check(threw, "broken JSON throws (the app says it is not a Mess Machine file)");

    System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILED");
    System.exit(failures == 0 ? 0 : 1);
  }
}
