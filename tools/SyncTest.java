/* Slice B sync tests: Ops, SyncSession and FakeCloudB, with plain javac/java (tools/sync-check.sh).
 * Args: <sounds dir> [fuzz seeds]. Prints FAIL lines and a final SYNC GOOD or "N FAILED"; exits 1 on a failure. */
import com.tideclock.messmachine.engine.*;
import com.tideclock.messmachine.cloud.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.*;

public class SyncTest {
  static int failures = 0, checks = 0;
  static void check(boolean ok, String what) { checks++; if (!ok) { failures++; System.out.println("FAIL " + what); } }
  static Library lib;
  static List<String> keys = new ArrayList<>(), free = new ArrayList<>();
  static long opsRun = 0;

  /* ================= small worlds ================= */

  static class Clk implements Clock { long t = 1_800_000_000_000L; public long nowMs() { return t; } }

  static class Mem implements CloudCache {
    final Map<String, String> m = new HashMap<>(); boolean frozen; int writes;
    public String load(String n) { return m.get(n); }
    public void save(String n, String j) { if (frozen) return; writes++; if (j == null) m.remove(n); else m.put(n, j); }
  }

  /** One app process on a phone: its calls wait in order here, and once it is killed its answers are never heard. */
  static class Proc implements InvocationHandler {
    final Phone ph; boolean dead; final ArrayDeque<Call> fifo = new ArrayDeque<>(); final CloudApi api;
    Proc(Phone ph) { this.ph = ph; api = (CloudApi) Proxy.newProxyInstance(CloudApi.class.getClassLoader(), new Class<?>[]{CloudApi.class}, this); }
    @SuppressWarnings("unchecked")
    public Object invoke(Object proxy, Method m, Object[] a) throws Throwable {
      if (m.getDeclaringClass() == Object.class) return m.invoke(this, a);
      int di = -1;
      if (a != null) for (int i = 0; i < a.length; i++) if (a[i] instanceof Done) di = i;
      if (di < 0) return m.invoke(ph.raw, a);
      if (dead) return null;
      final Done<Object> cb = (Done<Object>) a[di];
      final Object[] b = a.clone();
      b[di] = (Done<Object>) (v, e) -> { if (!dead) cb.done(v, e); };
      final String name = m.getName();
      fifo.add(new Call(name, () -> { ph.w.trace(ph.name + " " + name); try { m.invoke(ph.raw, b); } catch (Exception ex) { throw new RuntimeException(ex); } }));
      return null;
    }
  }

  static class Call { final String name; final Runnable run; Call(String n, Runnable r) { name = n; run = r; } }

  static class Phone implements SyncHost {
    final World w; final String email, name; final CloudApi raw; String me;
    Mem cache = new Mem(); Proc proc; SyncSession s; Project p; String file;
    final List<int[]> notices = new ArrayList<>(); final List<Integer> kept = new ArrayList<>();
    int helds, replaces, patches; boolean playing; long armed = -1; int state = -1;
    Phone(World w, String email, String name, CloudApi raw, Project start) { this.w = w; this.email = email; this.name = name; this.raw = raw; p = start; file = p.toJson(); }
    void start() { proc = new Proc(this); s = new SyncSession(proc.api, cache, w.clk, this, w.mess, me); s.open(); if (playing) s.setPlaying(true); }
    public Project project() { return p; }
    public void replace(Project np) { p = np; replaces++; file = p.toJson(); }
    public void patched() { patches++; file = p.toJson(); }
    public void onState(int st) { state = st; }
    public void onNotice(int kind, int n, String who) { notices.add(new int[]{kind, n}); }
    public void onSound(Sound snd) {}
    public void onHeld() { helds++; arm(); }
    public void keepCopy(int why) { kept.add(why); }
    public void onPeers(List<Peer> peers) {}
    /** The app's changed(): the mess file is written, then the session hears of it, then arm(). */
    void changed(boolean commit) { file = p.toJson(); s.localChanged(commit); arm(); }
    void arm() { if (s == null) return; Project q = s.preview(); if (q != null) armed = s.previewRev; }
    void wrap() { if (s != null && armed >= 0) { long r = armed; armed = -1; s.loopTop(r); } }
    void play(boolean on) { playing = on; armed = -1; if (s != null) s.setPlaying(on); }
    boolean alive() { return s != null; }
    void kill() { if (proc != null) { proc.dead = true; proc.fifo.clear(); } s = null; armed = -1; }
    void reopen() { p = Project.fromJson(file, null).project; start(); }
    boolean noticed(int kind) { for (int[] n : notices) if (n[0] == kind) return true; return false; }
    int applyCalls() { int n = 0; for (String c : w.fake.calls(raw)) if (c.equals("applyOps")) n++; return n; }
    int snapCalls() { int n = 0; for (String c : w.fake.calls(raw)) if (c.equals("saveSnapshot")) n++; return n; }
  }

  static class World {
    final Clk clk = new Clk(); FakeCloudB fake; final Random r; String mess; final List<Phone> phones = new ArrayList<>();
    final ArrayDeque<String> tr = new ArrayDeque<>();
    World(long seed) { this(seed, null); }
    World(long seed, Function<Clock, FakeCloudB> make) { r = new Random(seed); fake = make == null ? new FakeCloudB(clk) : make.apply(clk); }
    void trace(String s) { tr.add(s); if (tr.size() > 60) tr.poll(); }
    String lastTrace() { return String.join(" | ", tr); }

    @SuppressWarnings("unchecked")
    <T> T await(Consumer<Done<T>> f) {
      Object[] out = new Object[2];
      f.accept((v, e) -> { out[0] = v; out[1] = e; });
      fake.deliver();
      if (out[1] != null) throw new RuntimeException("call failed: " + ((CloudErr) out[1]).code);
      return (T) out[0];
    }
    CloudApi raw(String email) {
      CloudApi c = fake.client(email);
      c.setSession(fake.newSession(email));
      String tv = fake.termsVersion;
      this.<Boolean>await(d -> c.acceptTerms(tv, d));
      return c;
    }
    Phone add(String email, String name, CloudApi raw, Project start) {
      Phone ph = new Phone(this, email, name, raw, start); ph.me = fake.userId(email); phones.add(ph); return ph;
    }
    /** Runs one waiting call of one phone (random when ph is null). false: nothing waits. */
    boolean step(Phone ph) {
      if (ph == null) {
        List<Phone> ready = new ArrayList<>();
        for (Phone x : phones) if (x.proc != null && !x.proc.dead && !x.proc.fifo.isEmpty()) ready.add(x);
        if (ready.isEmpty()) return false;
        ph = ready.get(r.nextInt(ready.size()));
      }
      if (ph.proc == null || ph.proc.fifo.isEmpty()) return false;
      ph.proc.fifo.poll().run.run();
      fake.deliver();
      return true;
    }
    void pumpAll() { int n = 0; while (step(null)) if (++n > 200000) throw new IllegalStateException("pump never ends"); }
    void pumpSome(int max) { for (int i = 0; i < max; i++) if (!step(null)) break; }
    void tick(long ms) { for (Phone x : phones) if (x.alive()) x.s.tick(); clk.t += ms; }
    void tickPump() { tick(100); pumpAll(); }
    boolean quiet() {
      long rev = fake.messRev(mess);
      for (Phone x : phones) {
        if (!x.alive()) continue;
        if (x.s.pending() > 0 || x.s.held() > 0 || x.s.serverRev != rev || !x.proc.fifo.isEmpty() || x.s.state == SyncSession.OPENING || x.s.state == SyncSession.OFFLINE) return false;
      }
      return true;
    }
    boolean settle() {
      for (Phone x : phones) { if (x.alive() && x.playing) x.play(false); }
      for (int i = 0; i < 2000; i++) { tickPump(); if (quiet()) { for (int k = 0; k < 25; k++) tickPump(); if (quiet()) return true; } }
      return false;
    }
    boolean same() { String c = null; for (Phone x : phones) if (x.alive()) { String d = Ops.canon(x.p); if (c == null) c = d; else if (!c.equals(d)) return false; } return true; }
    String diffs() {
      StringBuilder b = new StringBuilder();
      String c0 = null; String n0 = "";
      for (Phone x : phones) if (x.alive()) { String d = Ops.canon(x.p); if (c0 == null) { c0 = d; n0 = x.name; } else if (!c0.equals(d)) {
        int i = 0; while (i < c0.length() && i < d.length() && c0.charAt(i) == d.charAt(i)) i++;
        b.append(n0).append(" vs ").append(x.name).append(" at ").append(i).append(": ").append(c0, Math.max(0, i - 60), Math.min(c0.length(), i + 60))
          .append(" <> ").append(d, Math.max(0, i - 60), Math.min(d.length(), i + 60)).append("; ");
      } }
      return b.toString();
    }
  }

  /** n phones on one mess: p0 owns it and invites the rest. emails may repeat (two phones of one account). */
  static World world(long seed, Project start, String... emails) { return world(seed, null, start, emails); }
  static World world(long seed, Function<Clock, FakeCloudB> make, Project start, String... emails) {
    World w = new World(seed, make);
    Map<String, CloudApi> first = new HashMap<>();
    List<CloudApi> raws = new ArrayList<>();
    for (String e : emails) { CloudApi c = w.raw(e); raws.add(c); first.putIfAbsent(e, c); }
    CloudApi a = raws.get(0);
    String mess = a.newId(); w.mess = mess;
    String doc = start.toJson();
    w.<Rev>await(d -> a.createMess(mess, "Jam", doc, d));
    Set<String> in = new HashSet<>(); in.add(emails[0]);
    for (int i = 1; i < emails.length; i++) {
      if (!in.add(emails[i])) continue;
      Invite inv = w.<Invite>await(d -> a.makeInvite(mess, 7, d));
      CloudApi b = first.get(emails[i]);
      w.<Joined>await(d -> b.redeemInvite(inv.token, d));
    }
    for (int i = 0; i < emails.length; i++) { Phone ph = w.add(emails[i], "P" + i, raws.get(i), Project.fromJson(doc, null).project); ph.start(); }
    w.settle();
    return w;
  }

  /* ================= the app's edits, through the same Project mutations AppState makes ================= */

  static void setLen(Phone ph, int l, int n, boolean fill) {
    Project.Pattern P = ph.p.pats[l];
    int old = P.len;
    if (n == old || n < 16 || n > 128 || n % 16 != 0) return;
    ph.s.hint(Ops.loopLen(l, n, fill));
    setLenRaw(P, n, fill);
    ph.changed(true);
  }
  /** AppState.setLen, line for line. */
  static void setLenRaw(Project.Pattern P, int n, boolean fill) {
    int old = P.len;
    if (n > old && !fill) {
    } else if (n > old) {
      for (boolean[] a : P.steps.values()) for (int j = old; j < n; j++) a[j] = a[j % old];
      for (List<Project.Note> l : P.notes.values()) {
        List<Project.Note> base = new ArrayList<>(); for (Project.Note x : l) if (x.s < old) base.add(x);
        for (int rep = 1; rep < n / old; rep++) for (Project.Note x : base) l.add(new Project.Note(x.s + rep * old, x.p, x.l));
      }
      for (float[] a : P.motion.values()) for (int j = old; j < n; j++) a[j] = a[j % old];
    } else {
      for (boolean[] a : P.steps.values()) for (int j = n; j < a.length; j++) a[j] = false;
      for (List<Project.Note> l : P.notes.values()) { l.removeIf(x -> x.s >= n); for (Project.Note x : l) x.l = Math.min(x.l, n - x.s); }
      for (float[] a : P.motion.values()) for (int j = n; j < a.length; j++) a[j] = Float.NaN;
    }
    P.extrasFor(Math.min(old, n), Math.max(old, n), n > old && fill);
    P.len = n;
  }
  static void copySlotTo(Phone ph, int from, int to) { if (from == to) return; ph.s.hint(Ops.loopCopy(from, to)); ph.p.pats[to] = ph.p.pats[from].copy(); ph.changed(true); }
  static void clearSlotAt(Phone ph, int i) { ph.s.hint(Ops.loopClear(i)); ph.p.pats[i].clear(); ph.changed(true); }
  static void swapPads(Phone ph, int a, int b) { if (a == b) return; ph.s.hint(Ops.padSwap(a, b)); ph.p.swapPads(a, b); ph.changed(true); }
  static void putOnPad(Phone ph, int pad, String key) { ph.s.hint(Ops.padPut(pad)); ph.p.assign(pad, key); ph.changed(true); }
  static void clearPad(Phone ph, int pad) { ph.s.hint(Ops.padPut(pad)); ph.p.clearPad(pad); ph.changed(true); }
  /** AppState.newSynth. */
  static void newSynth(Phone ph, int pad, Synth.Patch preset) {
    Project p = ph.p; String key = p.newSynthKey(); String old = p.kit[pad];
    ph.s.hint(Ops.padPut(pad));
    p.synths.put(key, preset.copy()); p.kit[pad] = key;
    if (!old.isEmpty() && Project.isSynth(old) && !Arrays.asList(p.kit).contains(old)) p.synths.remove(old);
    p.sel = pad;
    ph.changed(true);
  }
  /** AppState.ensureScratch then scratchToPad. */
  static void ensureScratch(Project p) {
    String k = p.kit[Project.SCRATCH];
    if (!k.isEmpty() && Project.isSynth(k) && p.synths.containsKey(k)) return;
    String key = p.newSynthKey(); p.synths.put(key, Synth.ANALOG.presets.get(0).copy()); p.kit[Project.SCRATCH] = key;
  }
  static void scratchToPad(Phone ph, int pad) {
    Project p = ph.p; ensureScratch(p);
    String key = p.kit[Project.SCRATCH]; Synth.Patch sp = p.synths.get(key); String old = p.kit[pad];
    ph.s.hint(Ops.padPut(pad));
    p.kit[pad] = key;
    PadOpts so = p.opts[Project.SCRATCH]; p.opts[pad] = so == null ? null : so.copy();
    if (!old.isEmpty() && Project.isSynth(old) && !Arrays.asList(p.kit).contains(old)) p.synths.remove(old);
    String nk = p.newSynthKey(); p.synths.put(nk, sp.copy()); p.kit[Project.SCRATCH] = nk; p.sel = pad;
    ph.changed(true);
  }

  /** One random edit of the kinds the app makes. own: the pads and loops this phone may touch (null: any). */
  static String randomEdit(Phone ph, Random r, int[] own, Project ref) {
    Project p = ph.p;
    int lo = own == null ? 0 : own[0], hi = own == null ? 64 : own[1];
    int l = own == null ? r.nextInt(8) : own[2] + r.nextInt(2);
    List<Integer> loaded = new ArrayList<>();
    for (int i = lo; i < hi; i++) if (!p.kit[i].isEmpty()) loaded.add(i);
    int pad = loaded.isEmpty() ? lo + r.nextInt(hi - lo) : loaded.get(r.nextInt(loaded.size()));
    String k = p.kit[pad];
    Project.Pattern P = p.pats[l];
    int roll = r.nextInt(own == null ? 34 : 30);
    final int fl = l, fpad = pad; final String fk = k;
    Consumer<Project> mut = null; String what; boolean commit = r.nextInt(4) != 0;
    if (k.isEmpty() && roll < 22) roll = 22;
    switch (roll) {
      case 0: case 1: case 2: case 3: { int j = r.nextInt(P.len); boolean v = !P.step(k, j); mut = q -> q.pats[fl].setStep(fk, j, v); what = "step " + l + "/" + pad + "/" + j; break; }
      case 4: { int j = r.nextInt(P.len), v = r.nextInt(3); mut = q -> q.pats[fl].setStrength(fk, j, v); what = "acc"; break; }
      case 5: { int j = r.nextInt(P.len), v = Project.Pattern.CHANCES[r.nextInt(8)]; mut = q -> q.pats[fl].setChance(fk, j, v); what = "chance"; break; }
      case 6: { int t = r.nextInt(P.len) * 24 + 1 + r.nextInt(23); boolean add = r.nextBoolean(); mut = q -> { if (add) q.pats[fl].addOff(fk, t); else q.pats[fl].removeOff(fk, t); }; what = "off"; break; }
      case 7: case 8: { int s = r.nextInt(P.len), pi = r.nextInt(25) - 12, ln = 1 + r.nextInt(4); boolean add = r.nextInt(3) != 0;
        mut = q -> { List<Project.Note> nl = q.pats[fl].notes(fk); if (add) nl.add(new Project.Note(s, pi, Math.min(ln, q.pats[fl].len - s))); else if (!nl.isEmpty()) nl.remove(0); }; what = "note"; break; }
      case 9: { int g = r.nextInt(24), j = r.nextInt(P.len); float v = r.nextInt(3) == 0 ? Float.NaN : r.nextFloat(); mut = q -> q.pats[fl].setLock(fk, g, j, v); what = "lock"; break; }
      case 10: { int g = r.nextInt(24), j = r.nextInt(P.len); float v = r.nextFloat(); mut = q -> q.pats[fl].setMotion(fpad, g, j, v); what = "lane"; break; }
      case 11: { int g = r.nextInt(24), sh = r.nextInt(7), span = 1 + r.nextInt(16); float a = r.nextFloat(), b = r.nextFloat(), st = r.nextFloat();
        mut = q -> q.pats[fl].laneStamp(fpad + ":" + g, sh, span, a, b, st); what = "stamp"; break; }
      case 12: { int t = r.nextInt(5), n = 1 + r.nextInt(6);
        mut = q -> { Project.Pattern X = q.pats[fl]; if (t == 0) X.rowEvery(fk, n); else if (t == 1) X.rowNudge(fk, n - 3); else if (t == 2) X.rowSpread(fk, n); else if (t == 3) X.rowRepeat(fk, n); else X.rowClear(fk); };
        what = "row tool " + t; break; }
      case 13: case 14: { double v = Math.round(r.nextDouble() * 200) / 100.0; int f = r.nextInt(6);
        mut = q -> { SoundEdit e = q.edit(fk).copy(); if (f == 0) e.vol = v; else if (f == 1) e.pan = v - 1; else if (f == 2) e.tune = Math.round(v * 6); else if (f == 3) e.rvb = v / 2; else if (f == 4) e.mode = v > 1 ? "gate" : "one"; else e.held = v > 1 ? "tail" : "loop"; q.setEdit(fk, e); };
        what = "edit " + f; break; }
      case 15: { double a = r.nextDouble() * .6, b = .4 + r.nextDouble() * .6; mut = q -> { SoundEdit e = q.edit(fk).copy(); e.st = a; e.en = b; q.setEdit(fk, e); }; what = "trim"; break; }
      case 16: { int f = r.nextInt(5); double v = r.nextDouble();
        mut = q -> { PadOpts o = q.opt(fpad).copy(); if (f == 0) o.glide = !o.glide; else if (f == 1) o.route = (int) (v * 4); else if (f == 2) o.lfoOn = !o.lfoOn; else if (f == 3) o.lfoRate = .5 + v * 8; else { Map<Integer, Double> h = o.fxMap(); h.put(1 + (int) (v * 20), v); o.fxSet(h); } q.setOpt(fpad, o); };
        what = "opt " + f; break; }
      case 17: { Synth.Patch sp = p.synth(k); if (sp == null) { int j = r.nextInt(P.len); mut = q -> q.pats[fl].setStep(fk, j, true); what = "step*"; break; }
        int i = r.nextInt(sp.kind.params.length); String id = sp.kind.params[i].id; double v = sp.kind.params[i].min + r.nextDouble() * (sp.kind.params[i].max - sp.kind.params[i].min);
        mut = q -> { Synth.Patch x = q.synth(fk); if (x != null) x.set(id, v); }; what = "knob"; break; }
      case 18: case 19: case 20: case 21: { int j = r.nextInt(P.len); mut = q -> q.pats[fl].setStep(fk, j, true); what = "step+"; break; }
      case 22: { if (own != null) { what = "noop"; break; } int b = r.nextInt(16), v = r.nextInt(9) - 1; mut = q -> q.song[b] = v; what = "song"; break; }
      case 23: { if (own != null) { what = "noop"; break; } int f = r.nextInt(6); int v = r.nextInt(100);
        String sc = new ArrayList<>(Theory.SCALES.keySet()).get(r.nextInt(Theory.SCALES.size()));
        mut = q -> { if (f == 0) q.bpm = 60 + v; else if (f == 1) q.swing = v; else if (f == 2) q.scale = sc; else if (f == 3) q.root = v % 12; else if (f == 4) q.fold = !q.fold; else q.level = v / 70.0; };
        what = "mess " + f; break; }
      case 24: { int n = 16 * (1 + r.nextInt(8)); boolean fill = r.nextBoolean(); if (ref != null) setLenRaw(ref.pats[l], n, fill); setLen(ph, l, n, fill); return "len " + l + " " + n; }
      case 25: { int to = own == null ? r.nextInt(8) : own[2] + r.nextInt(2); if (to == l) return "noop"; if (ref != null) ref.pats[to] = ref.pats[l].copy(); copySlotTo(ph, l, to); return "copy " + l + ">" + to; }
      case 26: { if (r.nextInt(3) != 0) return "noop"; if (ref != null) ref.pats[l].clear(); clearSlotAt(ph, l); return "clear " + l; }
      case 27: { int b = lo + r.nextInt(hi - lo); if (b == pad) return "noop"; if (ref != null) ref.swapPads(pad, b); swapPads(ph, pad, b); return "swap " + pad + " " + b; }
      case 28: { String key = own == null ? keys.get(r.nextInt(keys.size())) : free.get(own[3] + r.nextInt(8)); int to = lo + r.nextInt(hi - lo);
        if (ref != null) ref.assign(to, key); putOnPad(ph, to, key); return "put " + to + " " + key; }
      case 29: { if (r.nextInt(3) != 0) return "noop"; if (ref != null) ref.clearPad(pad); clearPad(ph, pad); return "clearpad " + pad; }
      case 30: { Synth.Kind kd = Synth.KINDS[r.nextInt(Synth.KINDS.length)]; newSynth(ph, pad, kd.presets.get(r.nextInt(kd.presets.size()))); return "synth " + pad; }
      case 31: { scratchToPad(ph, pad); return "scratch>" + pad; }
      case 32: { int f = r.nextInt(4); if (f == 0) { if (!p.mutes.remove(k)) p.mutes.add(k); } else if (f == 1) p.cur = r.nextInt(8); else if (f == 2) p.sel = pad; else p.songMode = !p.songMode;
        ph.file = p.toJson(); ph.s.localChanged(false); return "local " + f; }
      default: { if (ph.name.equals("P0")) { String nm = "Jam " + r.nextInt(50); p.name = nm; ph.changed(true); return "name"; } return "noop"; }
    }
    if (mut == null) return "noop";
    mut.accept(p);
    if (ref != null) mut.accept(ref);
    ph.changed(commit);
    return what;
  }

  /* ================= Ops on their own ================= */

  static Project rich() {
    Project p = Project.demo();
    for (int i = 8; i < 14; i++) p.kit[i] = keys.get(10 + i);
    p.kit[20] = "synth:1"; p.synths.put("synth:1", Synth.ANALOG.presets.get(0).copy());
    p.kit[21] = "synth:2"; p.synths.put("synth:2", Synth.FM.presets.get(0).copy());
    Project.Pattern a = p.pats[0], b = p.pats[1];
    b.len = 32;
    for (int j = 0; j < 32; j += 3) b.setStep(p.kit[0], j, true);
    a.setStrength(p.kit[0], 4, 2); a.setChance(p.kit[1], 4, 50); a.addOff(p.kit[2], 30);
    a.setLock(p.kit[0], 1, 0, .25f); a.setMotion(0, 3, 2, .5f); b.notes(p.kit[8]).add(new Project.Note(3, 2, 4));
    SoundEdit e = new SoundEdit(); e.vol = .6; e.st = .1; e.en = .8; p.setEdit(p.kit[1], e);
    PadOpts o = new PadOpts(); o.glide = true; Map<Integer, Double> h = new HashMap<>(); h.put(3, .4); o.fxSet(h); p.setOpt(1, o);
    p.song[0] = 0; p.song[1] = 1; p.bpm = 120;
    return p;
  }

  static Op op(String json) { return Ops.fromJson("[" + json + "]").get(0); }
  static List<String> types(List<Op> ops) { List<String> t = new ArrayList<>(); for (Op o : ops) t.add(o.getT()); return t; }

  static void opsTests() {
    Project base = rich();
    String k0 = base.kit[0], k1 = base.kit[1], k8 = base.kit[8];
    String[] all = {
      "{\"t\":\"step.set\",\"l\":0,\"p\":0,\"k\":\"" + k0 + "\",\"s\":5,\"on\":true}",
      "{\"t\":\"acc.set\",\"l\":0,\"p\":0,\"k\":\"" + k0 + "\",\"s\":1,\"v\":0}",
      "{\"t\":\"chance.set\",\"l\":0,\"p\":1,\"k\":\"" + k1 + "\",\"s\":2,\"v\":101}",
      "{\"t\":\"off.set\",\"l\":0,\"p\":2,\"k\":\"" + base.kit[2] + "\",\"tk\":53,\"on\":true}",
      "{\"t\":\"note.set\",\"l\":1,\"p\":8,\"k\":\"" + k8 + "\",\"s\":7,\"n\":-5,\"len\":3}",
      "{\"t\":\"note.set\",\"l\":1,\"p\":8,\"k\":\"" + k8 + "\",\"s\":3,\"n\":2,\"len\":0}",
      "{\"t\":\"lock.set\",\"l\":0,\"p\":0,\"k\":\"" + k0 + "\",\"g\":1,\"s\":0,\"v\":null}",
      "{\"t\":\"lock.set\",\"l\":0,\"p\":0,\"k\":\"" + k0 + "\",\"g\":5,\"s\":3,\"v\":0.123}",
      "{\"t\":\"lane.set\",\"l\":0,\"p\":0,\"k\":\"" + k0 + "\",\"g\":3,\"s\":9,\"v\":0.75}",
      "{\"t\":\"edit.set\",\"p\":1,\"k\":\"" + k1 + "\",\"f\":\"vol\",\"v\":1.5}",
      "{\"t\":\"edit.set\",\"p\":0,\"k\":\"" + k0 + "\",\"f\":\"mode\",\"v\":\"gate\"}",
      "{\"t\":\"edit.set\",\"p\":0,\"k\":\"" + k0 + "\",\"f\":\"fx.echoT\",\"v\":2}",
      "{\"t\":\"edit.trim\",\"p\":1,\"k\":\"" + k1 + "\",\"st\":0.2,\"en\":0.5}",
      "{\"t\":\"opt.set\",\"p\":1,\"k\":\"" + k1 + "\",\"f\":\"glide\",\"v\":false}",
      "{\"t\":\"opt.set\",\"p\":0,\"k\":\"" + k0 + "\",\"f\":\"fx.7\",\"v\":0.5}",
      "{\"t\":\"opt.set\",\"p\":0,\"k\":\"" + k0 + "\",\"f\":\"xy\",\"v\":\"a=1\"}",
      "{\"t\":\"synth.set\",\"p\":20,\"k\":\"synth:1\",\"id\":\"" + Synth.ANALOG.params[0].id + "\",\"v\":0.2}",
      "{\"t\":\"synth.set\",\"p\":20,\"k\":\"synth:1\",\"id\":\"@name\",\"v\":\"Buzz\"}",
      "{\"t\":\"song.set\",\"b\":5,\"v\":3}",
      "{\"t\":\"mess.set\",\"f\":\"bpm\",\"v\":90}",
      "{\"t\":\"mess.set\",\"f\":\"scale\",\"v\":\"Major\"}",
      "{\"t\":\"mess.set\",\"f\":\"name\",\"v\":\"Basement jam\"}",
      "{\"t\":\"row.put\",\"l\":0,\"p\":0,\"k\":\"" + k0 + "\",\"row\":{\"steps\":\"1111000011110000\",\"acc\":\"a...............\",\"notes\":\"0.3.2\"}}",
      "{\"t\":\"loop.len\",\"l\":0,\"len\":48,\"fill\":true}",
      "{\"t\":\"loop.len\",\"l\":1,\"len\":16,\"fill\":false}",
      "{\"t\":\"loop.clear\",\"l\":0}",
      "{\"t\":\"loop.copy\",\"from\":0,\"to\":2}",
      "{\"t\":\"loop.put\",\"l\":3,\"len\":32,\"pads\":{\"0\":{\"steps\":\"10000000000000001000000000000000\"}},\"keys\":{\"0\":\"" + k0 + "\"}}",
      "{\"t\":\"pad.put\",\"p\":30,\"k\":\"\",\"key\":\"" + keys.get(40) + "\",\"new\":true,\"edit\":{\"vol\":0.5},\"loops\":{\"0\":{\"steps\":\"1000100010001000\"}}}",
      "{\"t\":\"pad.put\",\"p\":31,\"k\":\"\",\"key\":\"" + k1 + "\"}",
      "{\"t\":\"pad.put\",\"p\":0,\"k\":\"" + k0 + "\",\"key\":\"\"}",
      "{\"t\":\"pad.put\",\"p\":22,\"k\":\"\",\"key\":\"synth:1\",\"new\":true,\"synth\":" + Json.write(Synth.PLUCK.presets.get(0).toMap()) + "}",
      "{\"t\":\"pad.swap\",\"a\":0,\"b\":1,\"ka\":\"" + k0 + "\",\"kb\":\"" + k1 + "\"}",
      "{\"t\":\"pad.swap\",\"a\":0,\"b\":1,\"ka\":\"wrong\",\"kb\":\"" + k1 + "\"}",
      "{\"t\":\"doc.reset\"}",
    };
    Set<String> seen = new TreeSet<>();
    for (String j : all) {
      Op o = op(j);
      seen.add(o.getT());
      String w = Ops.toJson(Collections.singletonList(o));
      check(Ops.toJson(Ops.fromJson(w)).equals(w), "op JSON round trip: " + j);
      Project before = Ops.clone(base);
      String c0 = Ops.canon(before);
      List<Op> inv = Ops.inverse(before, o);
      Project after = Ops.clone(base);
      check(Ops.apply(after, Collections.singletonList(o)) == 0, "a known op is not skipped: " + o.getT());
      Ops.apply(after, inv);
      check(Ops.canon(after).equals(c0), "inverse restores the exact canon: " + j + " inv " + Ops.toJson(inv));
      Project a1 = Ops.clone(base), a2 = Project.fromJson(base.toJson(), null).project;
      Ops.apply(a1, Collections.singletonList(o)); Ops.apply(a2, Collections.singletonList(o));
      check(Ops.canon(a1).equals(Ops.canon(Project.fromJson(a1.toJson(), null).project)), "after an op the mess equals save then load: " + o.getT());
      check(Ops.canon(a1).equals(Ops.canon(a2)), "apply does not depend on how the mess was made: " + o.getT());
    }
    check(seen.size() == 21, "every op type of contract s3 was tried: " + seen.size() + " " + seen);
    // the pad op follows its sound after a swap (S4)
    {
      Project p = Ops.clone(base);
      p.swapPads(0, 1);
      Ops.apply(p, Collections.singletonList(op("{\"t\":\"step.set\",\"l\":0,\"p\":0,\"k\":\"" + k0 + "\",\"s\":9,\"on\":true}")));
      check(p.pats[0].step(k0, 9) && !p.pats[0].step(k1, 9), "a pad op lands on the pad that holds its key now");
      Ops.apply(p, Collections.singletonList(op("{\"t\":\"step.set\",\"l\":0,\"p\":40,\"k\":\"gone:key\",\"s\":9,\"on\":true}")));
      check(Ops.canon(p).equals(Ops.canon(p)), "an op for an empty pad does nothing");
      Project q = Ops.clone(base); q.kit[22] = "synth:9"; q.synths.put("synth:9", Synth.ANALOG.presets.get(0).copy());
      Ops.apply(q, Collections.singletonList(op("{\"t\":\"pad.put\",\"p\":23,\"k\":\"\",\"key\":\"synth:9\",\"new\":true,\"synth\":" + Json.write(Synth.FM.presets.get(0).toMap()) + "}")));
      check("synth:9".equals(q.kit[22]) && "synth:3".equals(q.kit[23]), "two new synths under one key get two keys: " + q.kit[23]);
    }
    // unknown ops are kept whole and skipped
    {
      Op u = op("{\"t\":\"warp.set\",\"l\":0,\"z\":[1,{\"q\":null}],\"p\":3}");
      Project p = Ops.clone(base); String c = Ops.canon(p);
      check(Ops.apply(p, Collections.singletonList(u)) == 1 && Ops.canon(p).equals(c), "an unknown op is skipped and changes nothing");
      check(Ops.toJson(Collections.singletonList(u)).equals("[{\"t\":\"warp.set\",\"l\":0,\"z\":[1,{\"q\":null}],\"p\":3}]"), "an unknown op is written back whole");
      check(!Ops.known(u) && Ops.scopes(u).contains("*") && Ops.key(u).isEmpty(), "unknown op: not known, scope *, no key");
      check(Ops.replaces(op("{\"t\":\"loop.clear\",\"l\":1}")) && Ops.replaces(op("{\"t\":\"pad.put\",\"p\":1,\"key\":\"\"}")) && Ops.replaces(op("{\"t\":\"loop.put\",\"l\":1,\"len\":16,\"pads\":{}}"))
        && Ops.replaces(op("{\"t\":\"loop.copy\",\"from\":1,\"to\":2}")) && !Ops.replaces(op("{\"t\":\"pad.swap\",\"a\":1,\"b\":2}")) && !Ops.replaces(op("{\"t\":\"loop.len\",\"l\":1,\"len\":32}"))
        && !Ops.replaces(op("{\"t\":\"row.put\",\"l\":1,\"p\":1,\"row\":{}}")), "replaces: only loop.clear, loop.put, loop.copy and pad.put");
      check(Ops.key(op("{\"t\":\"note.set\",\"l\":1,\"p\":8,\"s\":7,\"n\":-5,\"len\":3}")).equals("n/1/8/7/-5") && Ops.key(op("{\"t\":\"off.set\",\"l\":2,\"p\":3,\"tk\":50,\"on\":true}")).equals("o/2/3/50")
        && Ops.scopes(op("{\"t\":\"step.set\",\"l\":2,\"p\":3,\"s\":1,\"on\":true}")).equals(Arrays.asList("R/2/3", "L/2", "P/3")), "keys and scopes as contract s3 writes them");
    }
    // Ops.change finds exactly the ops of each kind of edit, and none when nothing changed
    Object[][] cases = {
      {"step toggle", (Consumer<Project>) p -> p.pats[0].setStep(k0, 3, true), new Op[0], "step.set"},
      {"fader", (Consumer<Project>) p -> { SoundEdit e = p.edit(k1).copy(); e.vol = .33; p.setEdit(k1, e); }, new Op[0], "edit.set"},
      {"trim", (Consumer<Project>) p -> { SoundEdit e = p.edit(k1).copy(); e.st = .3; e.en = .9; p.setEdit(k1, e); }, new Op[0], "edit.trim"},
      {"pad option", (Consumer<Project>) p -> { PadOpts o = p.opt(1).copy(); o.legato = true; p.setOpt(1, o); }, new Op[0], "opt.set"},
      {"fx rest", (Consumer<Project>) p -> { PadOpts o = p.opt(1).copy(); Map<Integer, Double> h = o.fxMap(); h.put(9, .25); o.fxSet(h); p.setOpt(1, o); }, new Op[0], "opt.set"},
      {"synth knob", (Consumer<Project>) p -> p.synth("synth:1").set(Synth.ANALOG.params[1].id, Synth.ANALOG.params[1].max), new Op[0], "synth.set"},
      {"song bar", (Consumer<Project>) p -> p.song[7] = 2, new Op[0], "song.set"},
      {"tempo", (Consumer<Project>) p -> p.bpm = 77, new Op[0], "mess.set"},
      {"note", (Consumer<Project>) p -> p.pats[1].notes(k8).add(new Project.Note(10, 4, 2)), new Op[0], "note.set"},
      {"lane", (Consumer<Project>) p -> p.pats[0].setMotion(1, 4, 5, .9f), new Op[0], "lane.set"},
      {"lock", (Consumer<Project>) p -> p.pats[0].setLock(k1, 2, 6, .4f), new Op[0], "lock.set"},
      {"busy row tool", (Consumer<Project>) p -> { setLenRaw(p.pats[2], 128, false); p.pats[2].len = 128; p.pats[2].rowEvery(k0, 1); }, new Op[]{Ops.loopLen(2, 128, false)}, "loop.len,row.put"},
      {"setLen x2 busy, with hint", (Consumer<Project>) p -> setLenRaw(p.pats[1], 64, true), new Op[]{Ops.loopLen(1, 64, true)}, "loop.len"},
      {"setLen without hint", (Consumer<Project>) p -> setLenRaw(p.pats[0], 32, false), new Op[0], "loop.len"},
      {"copySlotTo", (Consumer<Project>) p -> p.pats[4] = p.pats[1].copy(), new Op[]{Ops.loopCopy(1, 4)}, "loop.copy"},
      {"clearSlotAt", (Consumer<Project>) p -> p.pats[0].clear(), new Op[]{Ops.loopClear(0)}, "loop.clear"},
      {"swapPads", (Consumer<Project>) p -> p.swapPads(0, 1), new Op[]{Ops.padSwap(0, 1)}, "pad.swap"},
      {"swapPads without hint", (Consumer<Project>) p -> p.swapPads(0, 1), new Op[0], "pad.put,pad.put"},
      {"putOnPad", (Consumer<Project>) p -> p.assign(40, keys.get(41)), new Op[]{Ops.padPut(40)}, "pad.put"},
      {"putOnPad (a move)", (Consumer<Project>) p -> p.assign(40, k0), new Op[]{Ops.padPut(40)}, "pad.put"},
      {"clearPad", (Consumer<Project>) p -> p.clearPad(1), new Op[]{Ops.padPut(1)}, "pad.put"},
      {"nothing", (Consumer<Project>) p -> {}, new Op[0], ""},
      {"local only", (Consumer<Project>) p -> { p.mutes.add(k0); p.cur = 3; p.sel = 2; p.songMode = true; }, new Op[0], ""},
    };
    for (Object[] c : cases) {
      @SuppressWarnings("unchecked") Consumer<Project> f = (Consumer<Project>) c[1];
      Project before = Ops.clone(base), shadow = Ops.clone(base), after = Ops.clone(base);
      f.accept(after);
      Change ch = Ops.change(shadow, after, Arrays.asList((Op[]) c[2]));
      String got = String.join(",", types(ch.ops));
      check(got.equals(c[3]), "Ops.change for " + c[0] + ": wanted [" + c[3] + "] got [" + got + "]");
      Project replay = Ops.clone(before); Ops.apply(replay, ch.ops);
      check(Ops.canon(replay).equals(Ops.canon(after)), "the ops of " + c[0] + " rebuild the edit on another phone");
      check(Ops.canon(shadow).equals(Ops.canon(after)), "the shadow is patched to the mess: " + c[0]);
      check(Ops.change(shadow, after, Collections.emptyList()).ops.isEmpty(), "a second change finds nothing: " + c[0]);
      Project back = Ops.clone(after); Ops.apply(back, ch.undo);
      check(Ops.canon(back).equals(Ops.canon(before)), "the undo of " + c[0] + " restores the mess");
    }
    {
      // newSynth and scratchToPad as the app makes them
      Project before = Ops.clone(base), sh = Ops.clone(base), p = Ops.clone(base);
      ensureScratch(p); ensureScratch(sh); ensureScratch(before);
      String key = p.newSynthKey(); p.synths.put(key, Synth.DRUM.presets.get(0).copy()); p.kit[44] = key;
      Change ch = Ops.change(sh, p, Collections.singletonList(Ops.padPut(44)));
      check(String.join(",", types(ch.ops)).equals("pad.put") && Boolean.TRUE.equals(ch.ops.get(0).m.get("new")), "newSynth goes as one pad.put with new: true");
      String sk = p.kit[Project.SCRATCH]; Synth.Patch sp = p.synths.get(sk);
      p.kit[45] = sk; String nk = p.newSynthKey(); p.synths.put(nk, sp.copy()); p.kit[Project.SCRATCH] = nk;
      Change c2 = Ops.change(sh, p, Collections.singletonList(Ops.padPut(45)));
      Project rp = Ops.clone(before); Ops.apply(rp, ch.ops); Ops.apply(rp, c2.ops);
      check(String.join(",", types(c2.ops)).equals("pad.put") && Ops.canon(rp).equals(Ops.canon(p)), "scratchToPad goes as one pad.put and rebuilds the pad");
    }
    // the law and order: random messes, every phone applying the same list gets the same mess
    {
      Random r = new Random(7);
      int lawFails = 0, orderFails = 0;
      World w = new World(1); Phone ph = w.add("x@t", "X", null, Ops.clone(base));
      for (int t = 0; t < 300; t++) {
        Project a = Ops.clone(base);
        ph.p = a; ph.s = null;
        Project b = Ops.clone(a);
        ph.p = b;
        for (int e = 0; e < 1 + r.nextInt(12); e++) randomEditNoSession(ph, r);
        List<Op> d = Ops.diff(a, b);
        Project x = Ops.clone(a); Ops.apply(x, d);
        if (!Ops.canon(x).equals(Ops.canon(b))) lawFails++;
        Project y = Project.fromJson(a.toJson(), null).project; Ops.apply(y, d);
        if (!Ops.canon(y).equals(Ops.canon(x))) orderFails++;
        opsRun += d.size();
      }
      check(lawFails == 0, "law canon(apply(clone(a), diff(a, b))) == canon(b) on 300 random pairs: " + lawFails + " failed");
      check(orderFails == 0, "the same ops in the same order give the same mess on every phone: " + orderFails + " failed");
    }
    // squash
    {
      String kk = k0;
      Op f1 = op("{\"t\":\"edit.set\",\"p\":0,\"k\":\"" + kk + "\",\"f\":\"vol\",\"v\":0.1}"), f2 = op("{\"t\":\"edit.set\",\"p\":0,\"k\":\"" + kk + "\",\"f\":\"vol\",\"v\":0.2}");
      Op sw = op("{\"t\":\"pad.swap\",\"a\":0,\"b\":1,\"ka\":\"" + kk + "\",\"kb\":\"" + k1 + "\"}");
      Op s1 = op("{\"t\":\"step.set\",\"l\":0,\"p\":0,\"k\":\"" + kk + "\",\"s\":1,\"on\":true}");
      check(Ops.squash(Arrays.asList(f1, f2)).size() == 1 && Ops.squash(Arrays.asList(f1, f2)).get(0) == f2, "squash keeps the later of two sets of one field");
      check(Ops.squash(Arrays.asList(f1, sw)).size() == 2, "squash keeps a fader move before a swap (S6)");
      check(Ops.squash(Arrays.asList(s1, op("{\"t\":\"loop.clear\",\"l\":0}"))).size() == 1, "squash drops a cell before a loop.clear of its loop");
      check(Ops.squash(Arrays.asList(s1, op("{\"t\":\"loop.copy\",\"from\":0,\"to\":1}"))).size() == 2, "squash keeps a cell a later loop.copy reads");
      check(Ops.squash(Arrays.asList(f1, op("{\"t\":\"pad.put\",\"p\":0,\"k\":\"" + kk + "\",\"key\":\"\"}"))).size() == 1, "squash drops a field op before a pad.put that replaces its pad");
    }
    // fit: 400 ops and 48 KB; a big op alone
    {
      List<Op> many = new ArrayList<>(); for (int i = 0; i < 450; i++) many.add(op("{\"t\":\"song.set\",\"b\":" + (i % 64) + ",\"v\":1}"));
      check(Ops.fit(many) == 400, "fit: at most 400 ops: " + Ops.fit(many));
      StringBuilder big = new StringBuilder(); for (int i = 0; i < 60000; i++) big.append('1');
      Op huge = op("{\"t\":\"mess.set\",\"f\":\"name\",\"v\":\"" + big + "\"}");
      check(Ops.fit(Arrays.asList(huge, many.get(0))) == 1 && Ops.fit(Arrays.asList(many.get(0), huge)) == 1, "fit: one op over 48 KB goes alone");
      List<Op> mid = new ArrayList<>(); String b2 = big.substring(0, 1000); for (int i = 0; i < 100; i++) mid.add(op("{\"t\":\"mess.set\",\"f\":\"name\",\"v\":\"" + b2 + "\"}"));
      int n = Ops.fit(mid); check(n > 40 && n < 50 && Ops.toJson(mid.subList(0, n)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= Ops.MAX_BYTES, "fit: the longest run within 48 KB: " + n);
    }
    // canon: the junk cases (B S9)
    {
      Project a = Ops.clone(base), b = Ops.clone(base);
      b.opts[50] = new PadOpts(); b.opts[50].glide = true;                         // options left on an empty pad
      b.pats[0].lane("50:3")[2] = .5f;                                              // a lane on an empty pad
      b.pats[0].lane("0:9");                                                        // an all-empty lane
      PadOpts o = b.opt(1).copy(); o.lfoCut = .9; o.lfoRes = .1; b.setOpt(1, o);   // the LFO's old filter fields
      b.pats[0].setStep(k0, 100, true);                                             // past the loop's length
      b.edits.put("nowhere", new SoundEdit());                                      // settings of a sound on no pad
      b.mutes.add(k0); b.cur = 4; b.sel = 3; b.extra.put("ios", 1.0);               // never synced
      b.setEdit(k0, new SoundEdit());                                               // all defaults
      check(Ops.canon(a).equals(Ops.canon(b)), "canon ignores the junk cases and the never-synced fields");
      b.pats[0].setStep(k0, 1, !b.pats[0].step(k0, 1));
      check(!Ops.canon(a).equals(Ops.canon(b)), "canon sees a real change");
      Project c = Ops.clone(base);
      check(Ops.canon(c).equals(Ops.canon(base)) && c != base && c.pats[0] != base.pats[0] && c.edits.get(k1) != base.edits.get(k1), "clone copies field by field");
    }
  }

  /** randomEdit without a session: the same mutations, with hints thrown away (for the law test). */
  static void randomEditNoSession(Phone ph, Random r) {
    SyncSession was = ph.s;
    ph.s = null;
    try {
      Project p = ph.p;
      int pad = r.nextInt(30), l = r.nextInt(8);
      String k = p.kit[pad];
      switch (r.nextInt(10)) {
        case 0: if (!k.isEmpty()) p.pats[l].setStep(k, r.nextInt(p.pats[l].len), true); break;
        case 1: if (!k.isEmpty()) { SoundEdit e = p.edit(k).copy(); e.vol = r.nextDouble(); p.setEdit(k, e); } break;
        case 2: setLenRaw(p.pats[l], 16 * (1 + r.nextInt(8)), r.nextBoolean()); break;
        case 3: p.pats[(l + 1) % 8] = p.pats[l].copy(); break;
        case 4: p.pats[l].clear(); break;
        case 5: p.swapPads(pad, r.nextInt(30)); break;
        case 6: p.assign(pad, keys.get(r.nextInt(keys.size()))); break;
        case 7: p.clearPad(pad); break;
        case 8: if (!k.isEmpty()) p.pats[l].rowEvery(k, 1 + r.nextInt(3)); break;
        default: if (!k.isEmpty()) p.pats[l].setMotion(pad, r.nextInt(24), r.nextInt(p.pats[l].len), r.nextFloat()); break;
      }
    } finally { ph.s = was; }
  }

  /* ================= phones on one fake server ================= */

  static int countKind(Phone ph, int kind) { int n = 0; for (int[] x : ph.notices) if (x[0] == kind) n++; return n; }

  static void sessionTests() {
    Project start = rich();
    String k0 = start.kit[0], k1 = start.kit[1];
    // edits to different fields all survive, on 2, 3 and 4 phones
    for (int n = 2; n <= 4; n++) {
      String[] em = new String[n]; for (int i = 0; i < n; i++) em[i] = "u" + i + "@t";
      World w = world(n, Ops.clone(start), em);
      for (int i = 0; i < n; i++) { Phone ph = w.phones.get(i); ph.p.pats[0].setStep(ph.p.kit[i], 15, true); ph.changed(true); }
      w.phones.get(0).p.bpm = 99; w.phones.get(0).changed(true);
      check(w.settle(), n + " phones settle");
      boolean ok = w.same();
      for (Phone ph : w.phones) for (int i = 0; i < n; i++) ok &= ph.p.pats[0].step(ph.p.kit[i], 15);
      check(ok && w.phones.get(n - 1).p.bpm == 99, n + " phones: edits to different fields all survive " + w.diffs());
    }
    // same field: the server's later one wins everywhere; no flicker while mine is unconfirmed
    {
      World w = world(11, Ops.clone(start), "a@t", "b@t");
      Phone a = w.phones.get(0), b = w.phones.get(1);
      w.clk.t += 3000;
      SoundEdit e = a.p.edit(k1).copy(); e.vol = .2; a.p.setEdit(k1, e); a.changed(true);
      e = b.p.edit(k1).copy(); e.vol = .9; b.p.setEdit(k1, e); b.changed(true);
      b.s.tick(); while (w.step(b)) {}
      a.s.tick();                                    // a's poll goes first, then its send
      boolean flick = false;
      while (w.step(a)) flick |= Math.abs(a.p.edit(k1).vol - .2) > 1e-9;
      check(!flick && Math.abs(a.p.edit(k1).vol - .2) < 1e-9, "no flicker: my unconfirmed value stays on screen while theirs comes in");
      w.settle();
      check(w.same() && Math.abs(b.p.edit(k1).vol - .2) < 1e-9, "same field: the server's later one wins on every phone");
    }
    // Review B M1 run 1: swap, close, reopen: nothing is sent again and the swap does not undo itself
    {
      World w = world(12, Ops.clone(start), "solo@t");
      Phone a = w.phones.get(0);
      swapPads(a, 0, 1); w.settle();
      int sends = a.applyCalls();
      a.s.close(); a.kill(); a.reopen(); w.settle();
      check(a.applyCalls() == sends && k0.equals(a.p.kit[1]) && k1.equals(a.p.kit[0]), "M1 run 1 (close): swap then reopen sends nothing and stays swapped");
      swapPads(a, 2, 3); w.settle(); sends = a.applyCalls(); String k2 = a.p.kit[3];
      a.kill(); a.reopen(); w.settle();
      check(a.applyCalls() == sends && k2.equals(a.p.kit[3]), "M1 run 1 (kill): swap then reopen sends nothing and stays swapped");
    }
    // Review B M1 run 2: A plays with B's batch held, clears loop B; B records 4 hits there; A is killed and reopens
    {
      World w = world(13, Ops.clone(start), "a@t", "b@t");
      Phone a = w.phones.get(0), b = w.phones.get(1);
      a.play(true);
      b.p.bpm = 101; b.changed(true);
      for (int i = 0; i < 30; i++) w.tickPump();
      check(a.s.held() == 1 && a.p.bpm != 101 && a.helds > 0, "while playing their batch is held, not on screen, and onHeld fired");
      clearSlotAt(a, 1);
      for (int i = 0; i < 10; i++) w.tickPump();
      String kb = b.p.kit[3];
      for (int j = 0; j < 4; j++) b.p.pats[1].setStep(kb, j * 4, true);
      b.changed(true);
      for (int i = 0; i < 10; i++) { b.s.tick(); w.clk.t += 100; while (w.step(b)) {} }
      a.kill(); a.play(false); a.reopen(); w.settle();
      int hits = 0; for (int j = 0; j < 16; j++) if (a.p.pats[1].step(kb, j)) hits++;
      check(hits == 4 && w.same(), "M1 run 2: the friend's take survives my kill and reopen (" + hits + " hits) " + w.diffs());
    }
    // kill at every point of a send, then reopen: no loss and no double apply
    for (int point = 0; point < 3; point++) {
      World w = world(20 + point, Ops.clone(start), "a@t", "b@t");
      Phone a = w.phones.get(0);
      int before = w.fake.batchCount(w.mess);
      a.p.pats[2].setStep(k0, 7, true); a.changed(true);
      w.clk.t += 1000; a.s.tick();
      boolean has = false;
      for (Call x : a.proc.fifo) has |= x.name.equals("applyOps");
      check(has, "kill point " + point + ": a send is waiting");
      if (point == 0) a.kill();
      else if (point == 1) {
        while (!a.proc.fifo.isEmpty() && !a.proc.fifo.peek().name.equals("applyOps")) w.step(a);
        a.proc.fifo.poll().run.run(); a.kill(); w.fake.deliver();
      } else {
        while (!a.proc.fifo.isEmpty() && !a.proc.fifo.peek().name.equals("applyOps")) w.step(a);
        a.cache.frozen = true; w.step(a); a.kill(); a.cache.frozen = false;
      }
      a.reopen(); w.settle();
      int stored = w.fake.batchCount(w.mess) - before;
      check(stored == 1 && w.same() && a.p.pats[2].step(k0, 7), "kill point " + point + " (" + new String[]{"before the call", "after the call, before the answer", "after the answer, before the cache write"}[point] + "): one batch, no loss: " + stored);
    }
    // a phone that changed nothing between close and open sends zero ops
    {
      World w = world(23, Ops.clone(start), "a@t", "b@t");
      Phone a = w.phones.get(0), b = w.phones.get(1);
      a.p.bpm = 130; a.changed(true); w.settle();
      int sends = a.applyCalls();
      a.s.close(); a.kill();
      b.p.swing = 40; b.changed(true); w.settle();
      a.reopen(); w.settle();
      check(a.applyCalls() == sends && a.p.swing == 40 && w.same(), "changed nothing between close and open: zero ops sent");
    }
    // held batches: not on screen while playing, released at loopTop(previewRev) and only up to previewRev; stopped means at once
    {
      World w = world(24, Ops.clone(start), "a@t", "b@t");
      Phone a = w.phones.get(0), b = w.phones.get(1);
      a.play(true);
      b.p.bpm = 141; b.changed(true);
      for (int i = 0; i < 30; i++) w.tickPump();
      long armedAt = a.armed;
      check(a.s.held() == 1 && armedAt > 0 && a.p.bpm != 141, "held while playing, armed with previewRev " + armedAt);
      Project pv = a.s.preview();
      check(pv != null && pv.bpm == 141 && a.p.bpm != 141, "preview() shows the release without changing the screen");
      a.armed = armedAt;
      b.p.swing = 33; b.changed(true);
      for (int i = 0; i < 30; i++) { b.s.tick(); a.s.tick(); w.clk.t += 100; w.pumpAll(); }
      a.armed = armedAt;
      check(a.s.held() == 2, "a second batch is held too");
      a.wrap();
      check(a.p.bpm == 141 && a.p.swing != 33 && a.s.held() == 1, "loopTop(previewRev) releases only up to that rev");
      a.arm(); a.wrap();
      check(a.p.swing == 33 && a.s.held() == 0, "the next arm and loop top release the rest");
      a.play(false);
      b.p.bpm = 77; b.changed(true);
      for (int i = 0; i < 30; i++) w.tickPump();
      check(a.p.bpm == 77 && a.s.held() == 0, "stopped: their edit is on screen at once");
    }
    // offline for a long time, then replay
    {
      World w = world(25, Ops.clone(start), "a@t", "b@t", "c@t");
      Phone a = w.phones.get(0), b = w.phones.get(1);
      w.fake.setOnline(a.raw, false);
      Random r = new Random(5);
      for (int i = 0; i < 150; i++) { randomEdit(a, r, null, null); randomEdit(b, r, null, null); w.tick(2000); w.pumpAll(); }
      check(a.s.state == SyncSession.OFFLINE, "a phone with no network says OFFLINE");
      w.fake.setOnline(a.raw, true);
      check(w.settle() && w.same(), "after a long offline time everything replays and every phone agrees " + w.diffs());
    }
    // S4: a pad swap by one phone while another has ops in flight for those pads
    {
      World w = world(26, Ops.clone(start), "a@t", "b@t");
      Phone a = w.phones.get(0), b = w.phones.get(1);
      String ka = a.p.kit[4], kb = a.p.kit[5];
      w.clk.t += 1000;
      b.p.pats[0].setStep(ka, 13, true); b.changed(true);
      b.s.tick();                                   // b's step is in flight
      swapPads(a, 4, 5); a.s.tick();
      while (w.step(a)) {}                          // the swap lands first
      while (w.step(b)) {}
      w.settle();
      boolean ok = w.same();
      for (Phone x : w.phones) ok &= x.p.kit[5].equals(ka) && x.p.pats[0].step(ka, 13) && !x.p.pats[0].step(kb, 13);
      check(ok, "S4: an op in flight follows its sound to the pad it was swapped to " + w.diffs());
    }
    // undo of my own change: skipped when someone else changed the field since, done otherwise
    {
      World w = world(27, Ops.clone(start), "a@t", "b@t");
      Phone a = w.phones.get(0), b = w.phones.get(1);
      SoundEdit e = a.p.edit(k0).copy(); e.vol = .3; a.p.setEdit(k0, e); a.changed(true); w.settle();
      e = b.p.edit(k0).copy(); e.vol = .8; b.p.setEdit(k0, e); b.changed(true); w.settle();
      a.s.undo(); w.settle();
      check(Math.abs(a.p.edit(k0).vol - .8) < 1e-9 && countKind(a, SyncSession.N_UNDO_SKIPPED) == 1 && w.same(), "undo after someone else changed the field is skipped, with the message");
      e = a.p.edit(k0).copy(); e.pan = -.5; a.p.setEdit(k0, e); a.changed(true); w.settle();
      a.s.undo(); w.settle();
      check(Math.abs(b.p.edit(k0).pan) < 1e-9 && Math.abs(b.p.edit(k0).vol - .8) < 1e-9 && w.same(), "undo of my own change nobody touched reaches every phone");
      clearSlotAt(a, 1); w.settle(); a.s.undo(); w.settle();
      check(Ops.canon(a.p).equals(Ops.canon(b.p)) && a.p.pats[1].step(k0, 3), "undo of a loop clear puts the loop back everywhere");
    }
    // ops from a newer client (S7) and junk in a batch whose v is not above mine
    {
      World w = world(28, Ops.clone(start), "a@t", "b@t", "x@t");
      Phone a = w.phones.get(0), b = w.phones.get(1), x = w.phones.get(2);
      x.kill();                                     // x is a newer app: it talks to the server itself
      w.phones.remove(x);
      w.fake.opsVMax = 2;
      String fut = "[{\"t\":\"step.set\",\"l\":0,\"p\":0,\"k\":\"" + k0 + "\",\"s\":11,\"on\":true},{\"t\":\"warp.set\",\"z\":5,\"deep\":{\"a\":[1,2]}}]";
      w.<Applied>await(d -> x.raw.applyOps(w.mess, x.raw.newId(), fut, 2, d));
      w.settle();
      int snaps = a.snapCalls() + b.snapCalls();
      check(a.s.sawNewer && b.s.sawNewer && a.noticed(SyncSession.N_NEWER), "a batch from a newer app sets sawNewer and says so");
      for (int i = 0; i < 70; i++) { a.p.pats[3].setStep(k0, i % 16, i % 2 == 0); a.changed(true); w.tickPump(); }
      w.clk.t += 70000; w.settle();
      check(a.snapCalls() + b.snapCalls() == snaps, "a phone that saw newer ops uploads no snapshot");
      boolean kept = false; for (String j : w.fake.batchesJson(w.mess, 0)) if (j.contains("\"warp.set\"") && j.contains("\"deep\":{\"a\":[1,2]}")) kept = true;
      check(kept, "the newer op is kept on the server untouched");
      setLenRaw(a.p.pats[2], 128, false); a.p.pats[2].len = 128; a.s.hint(Ops.loopLen(2, 128, false)); a.p.pats[2].rowEvery(k0, 1); a.changed(true); w.settle();
      boolean put = false; for (String j : w.fake.batchesJson(w.mess, 0)) if (j.contains("row.put")) put = true;
      check(!put && a.p.pats[2].step(k0, 100) && w.same(), "a phone that saw newer ops sends cells, never a row.put from its own copy");
      String junk = "[{\"t\":\"bogus.op\",\"a\":1},{\"t\":\"song.set\",\"b\":9,\"v\":2}]";
      w.<Applied>await(d -> x.raw.applyOps(w.mess, x.raw.newId(), junk, 1, d));
      w.settle();
      check(a.p.song[9] == 2 && b.p.song[9] == 2 && w.same(), "junk in a v1 batch is dropped by everyone, the rest of the batch applies");
    }
    {
      World w = world(29, Ops.clone(start), "a@t", "b@t");
      Phone a = w.phones.get(0), b = w.phones.get(1);
      CloudApi xa = w.raw("y@t");
      Invite inv = w.<Invite>await(d -> a.raw.makeInvite(w.mess, 7, d));
      w.<Joined>await(d -> xa.redeemInvite(inv.token, d));
      String junk = "[{\"t\":\"bogus.op\",\"a\":1},{\"t\":\"song.set\",\"b\":9,\"v\":2}]";
      w.<Applied>await(d -> xa.applyOps(w.mess, xa.newId(), junk, 1, d));
      w.settle();
      check(!a.s.sawNewer && a.p.song[9] == 2 && w.same(), "an unknown op in a batch at my version is junk: dropped, no sawNewer");
    }
    // removed from the mess: the unsent work is kept as a phone copy; refused batches keep a copy too
    {
      World w = world(30, Ops.clone(start), "a@t", "b@t");
      Phone a = w.phones.get(0), b = w.phones.get(1);
      w.<Boolean>await(d -> a.raw.removeMember(w.mess, b.me, false, d));
      b.p.bpm = 66; b.changed(true);
      for (int i = 0; i < 40; i++) w.tickPump();
      check(b.kept.contains(SyncSession.W_GONE) && b.s.state == SyncSession.GONE && b.p.bpm == 66, "removed: a local copy is kept first, the screen stays, state GONE");
    }
    {
      boolean[] on = {false};
      World w = world(31, c -> new FakeCloudB(c) {
        @Override protected Applied srvApplyOps(FakeCloud.FakeUser u, String m, String batch, String json, int v) {
          if (on[0]) throw new FakeCloud.FakeErr("forbidden", 400);
          return super.srvApplyOps(u, m, batch, json, v);
        }
      }, Ops.clone(start), "a@t", "b@t");
      Phone a = w.phones.get(0), b = w.phones.get(1);
      on[0] = true;
      b.p.bpm = 67; b.changed(true);
      for (int i = 0; i < 20; i++) w.tickPump();
      on[0] = false;
      w.settle();
      check(b.kept.contains(SyncSession.W_REFUSED) && b.p.bpm != 67 && w.same(), "forbidden: a local copy is kept, the batch dropped, the screen rebuilt");
      w.fake.bytesCap = 200;
      for (int j = 0; j < 16; j++) b.p.pats[5].setStep(k0, j, true);
      b.changed(true);
      for (int i = 0; i < 20; i++) w.tickPump();
      w.fake.bytesCap = 262144; w.settle();
      check(countKind(b, SyncSession.N_KEPT_COPY) >= 2 && w.same(), "too_big: kept a copy and moved on");
    }
    // a huge change is split into batches of at most 400 ops
    {
      World w = world(32, Ops.clone(start), "a@t", "b@t");
      Phone a = w.phones.get(0), b = w.phones.get(1);
      int before = w.fake.batchCount(w.mess);
      a.s.hint(Ops.loopLen(6, 128, false)); setLenRaw(a.p.pats[6], 128, false);
      for (int pad = 0; pad < 14; pad++) for (int j = 0; j < 60; j++) a.p.pats[6].setStep(a.p.kit[pad], j * 2, true);
      a.changed(true);
      check(w.settle() && w.same(), "a huge change settles");
      int nb = w.fake.batchCount(w.mess) - before; boolean small = true;
      for (String j : w.fake.batchesJson(w.mess, 0)) small &= Ops.fromJson(j).size() <= 400;
      check(nb >= 3 && small, "a change of 841 ops goes as " + nb + " batches of at most 400");
    }
    // the rate limit backs off and everything still goes
    {
      World w = world(33, Ops.clone(start), "a@t", "a@t");
      Phone a = w.phones.get(0), a2 = w.phones.get(1);
      w.fake.ratePerSec = 1;
      int stored0 = w.fake.batchCount(w.mess);
      for (int i = 0; i < 30; i++) { a.p.pats[0].setStep(k1, i % 16, i % 3 == 0); a.changed(true); a2.p.pats[1].setStep(k1, i % 16, i % 3 == 0); a2.changed(true); w.tickPump(); }
      check(w.settle() && w.same(), "two phones of one account under a tight rate limit settle");
      int calls = a.applyCalls() + a2.applyCalls() - 0, stored = w.fake.batchCount(w.mess) - stored0;
      check(calls > stored, "rate_limited was answered and the phones waited and sent again: " + calls + " calls, " + stored + " batches");
    }
    // two phones of one account merge like two people
    {
      World w = world(34, Ops.clone(start), "same@t", "same@t");
      Phone a = w.phones.get(0), b = w.phones.get(1);
      a.p.bpm = 111; a.changed(true); b.p.swing = 12; b.changed(true);
      w.settle();
      check(a.p.swing == 12 && b.p.bpm == 111 && w.same(), "two phones of one account see each other's edits (mine is by batch id, not user id)");
    }
    // snapshot_first, gap, the long-gap rule
    {
      World w = world(35, Ops.clone(start), "a@t", "b@t");
      Phone a = w.phones.get(0), b = w.phones.get(1);
      w.fake.snapRevs = 5;
      for (int i = 0; i < 12; i++) { a.p.pats[0].setStep(k1, i, true); a.changed(true); for (int t = 0; t < 6; t++) w.tickPump(); }
      check(w.settle() && w.same() && w.fake.docRev(w.mess) > 0, "snapshot_first: a snapshot goes up and the same batch follows");
    }
    {
      World w = world(36, Ops.clone(start), "a@t", "b@t", "c@t");
      Phone a = w.phones.get(0), c = w.phones.get(2);
      w.fake.keepBelow = 0;
      w.fake.setOnline(c.raw, false);
      c.p.bpm = 177; c.changed(true); for (int i = 0; i < 3; i++) w.tickPump();
      c.kill();
      for (int i = 0; i < 6; i++) { a.p.pats[0].setStep(k1, i, true); a.changed(true); w.tickPump(); w.tickPump(); w.tickPump(); w.tickPump(); w.tickPump(); }
      a.s.saveVersion("v1"); w.settle();
      w.fake.setOnline(c.raw, true);
      c.reopen(); w.settle();
      check(c.kept.contains(SyncSession.W_RESET) && w.same() && c.p.bpm != 177, "gap: a copy is kept, the mess is opened again from the server");
    }
    {
      World w = world(37, Ops.clone(start), "a@t", "b@t");
      Phone a = w.phones.get(0), b = w.phones.get(1);
      w.fake.setOnline(b.raw, false);
      b.p.bpm = 155; b.changed(true); w.tickPump();
      b.kill();
      a.p.swing = 21; a.changed(true); w.settle();
      w.clk.t += SyncSession.GAP_MS + 1000;
      w.fake.setOnline(b.raw, true);
      b.reopen(); w.settle();
      check(b.kept.contains(SyncSession.W_GAP) && b.noticed(SyncSession.N_KEPT_COPY) && b.p.bpm != 155 && b.p.swing == 21 && w.same(), "a cache over a day old with ops on both sides: keep mine as a copy, show the server's");
    }
    // a solo mess uploads its never-synced fields (M4); a shared one blanks them
    {
      World w = world(38, Ops.clone(start), "solo@t");
      Phone a = w.phones.get(0);
      a.p.mutes.add(k0); a.p.songMode = true; a.file = a.p.toJson(); a.s.localChanged(false);
      for (int i = 0; i < 80; i++) w.tickPump();
      a.p.bpm = 88; a.changed(true); w.settle(); a.s.saveVersion("Saved"); w.settle();
      String doc = w.fake.messDoc(w.mess);
      check(doc.contains("\"mutes\":[\"" + k0 + "\"]") && doc.contains("\"mode\":\"song\""), "solo mess: mutes and song mode reach the cloud");
      World w2 = world(39, Ops.clone(start), "a@t", "b@t");
      Phone c = w2.phones.get(0);
      c.p.mutes.add(k0); c.file = c.p.toJson(); c.s.localChanged(false);
      c.p.bpm = 89; c.changed(true); w2.settle(); c.s.saveVersion("Saved"); w2.settle();
      String d2 = w2.fake.messDoc(w2.mess);
      check(d2.contains("\"bpm\":89") && d2.contains("\"mutes\":[]"), "shared mess: the snapshot blanks mutes");
      check(w2.phones.get(1).p.mutes.isEmpty() && c.p.mutes.contains(k0), "mutes never travel between phones");
    }
    // the fake server's own rules (Slice B calls)
    serverTests(start);
  }

  static void serverTests(Project start) {
    World w = new World(50);
    CloudApi a = w.raw("own@t"), b = w.raw("ed@t"), c = w.raw("out@t");
    String mess = a.newId();
    w.<Rev>await(d -> a.createMess(mess, "Basement jam", start.toJson(), d));
    Function<Consumer<Done<Object>>, String> code = f -> { Object[] o = new Object[1]; f.accept((v, e) -> o[0] = e == null ? "ok" : e.code); w.fake.deliver(); return (String) o[0]; };
    check(code.apply(d -> b.applyOps(mess, b.newId(), "[{\"t\":\"song.set\",\"b\":1,\"v\":1}]", 1, cast(d))).equals("not_found"), "a stranger's apply_ops: not_found");
    Invite inv = w.<Invite>await(d -> a.makeInvite(mess, 7, d));
    check(code.apply(d -> b.inviteInfo(inv.token, cast(d))).equals("ok"), "invite_info works");
    check(code.apply(d -> b.makeInvite(mess, 7, cast(d))).equals("not_found"), "make_invite by a stranger: not_found");
    w.<Joined>await(d -> b.redeemInvite(inv.token, d));
    check(code.apply(d -> c.redeemInvite(inv.token, cast(d))).equals("invite_invalid"), "an invite is single use");
    check(code.apply(d -> b.makeInvite(mess, 7, cast(d))).equals("forbidden"), "make_invite by an editor: forbidden");
    check(code.apply(d -> a.makeInvite(mess, 31, cast(d))).equals("bad_input"), "make_invite days over 30: bad_input");
    check(code.apply(d -> b.applyOps(mess, "", "[{\"t\":\"song.set\"}]", 1, cast(d))).equals("bad_input"), "apply_ops with no batch id: bad_input");
    check(code.apply(d -> b.applyOps(mess, b.newId(), "[]", 1, cast(d))).equals("bad_input"), "apply_ops with no ops: bad_input");
    check(code.apply(d -> b.applyOps(mess, b.newId(), "[{\"t\":\"song.set\"}]", 2, cast(d))).equals("bad_input"), "apply_ops above ops_v_max: bad_input");
    check(code.apply(d -> b.applyOps(mess, b.newId(), "[{\"t\":\"mess.set\",\"f\":\"name\",\"v\":\"Mine now\"}]", 1, cast(d))).equals("forbidden"), "only the owner renames: forbidden");
    check(code.apply(d -> a.applyOps(mess, a.newId(), "[{\"t\":\"mess.set\",\"f\":\"name\",\"v\":\"a badword\"}]", 1, cast(d))).equals("bad_name"), "a bad name: bad_name");
    check(code.apply(d -> a.applyOps(mess, a.newId(), "[{\"t\":\"mess.set\",\"f\":\"name\",\"v\":7}]", 1, cast(d))).equals("bad_input"), "a name that is not a string: bad_input");
    String id = b.newId();
    Applied first = w.<Applied>await(d -> b.applyOps(mess, id, "[{\"t\":\"song.set\",\"b\":1,\"v\":1}]", 1, d));
    Applied again = w.<Applied>await(d -> b.applyOps(mess, id, "[{\"t\":\"song.set\",\"b\":1,\"v\":1}]", 1, d));
    check(first.rev == again.rev && w.fake.batchCount(mess) == 1, "a batch id sent twice is stored once and answered the same");
    check(code.apply(d -> a.applyOps(mess, id, "[{\"t\":\"song.set\",\"b\":1,\"v\":1}]", 1, cast(d))).equals("bad_input"), "someone else's batch id: bad_input");
    Since s = w.<Since>await(d -> a.opsSince(mess, 0, d));
    check(s.ops.size() == 1 && s.rev == 1 && s.members == 2, "ops_since lists the batch");
    check(code.apply(d -> a.leaveMess(mess, cast(d))).equals("owner_cannot_leave"), "the owner cannot leave");
    check(code.apply(d -> a.removeMember(mess, w.fake.userId("own@t"), false, cast(d))).equals("bad_input"), "the owner cannot be removed");
    // live seats: 4
    w.<Boolean>await(d -> a.leaveLive(mess, d)); w.<Boolean>await(d -> b.leaveLive(mess, d));
    List<CloudApi> many = new ArrayList<>();
    for (int i = 0; i < 4; i++) { CloudApi x = w.raw("m" + i + "@t"); Invite iv = w.<Invite>await(d -> a.makeInvite(mess, 7, d)); w.<Joined>await(d -> x.redeemInvite(iv.token, d)); many.add(x); }
    for (CloudApi x : many) w.<Live>await(d -> x.joinLive(mess, d));
    Live l5 = w.<Live>await(d -> b.joinLive(mess, d));
    check(!l5.live, "a fifth live editor watches only");
    check(code.apply(d -> b.applyOps(mess, b.newId(), "[{\"t\":\"song.set\",\"b\":2,\"v\":1}]", 1, cast(d))).equals("mess_full_live"), "a fifth editor's apply_ops: mess_full_live");
    w.<Boolean>await(d -> many.get(0).leaveLive(mess, d));
    check(w.<Live>await(d -> b.joinLive(mess, d)).live, "a freed seat can be taken");
    // members cap 8
    for (int i = 4; i < 6; i++) { CloudApi x = w.raw("m" + i + "@t"); Invite iv = w.<Invite>await(d -> a.makeInvite(mess, 7, d)); w.<Joined>await(d -> x.redeemInvite(iv.token, d)); }
    Invite iv9 = w.<Invite>await(d -> a.makeInvite(mess, 7, d));
    check(code.apply(d -> c.redeemInvite(iv9.token, cast(d))).equals("mess_full"), "a ninth member: mess_full");
    // block
    w.<Boolean>await(d -> a.blockUser(w.fake.userId("ed@t"), d));
    check(!w.fake.isMember(mess, "ed@t") && code.apply(d -> b.opsSince(mess, 0, cast(d))).equals("not_found"), "blocking removes them from my messes");
    check(code.apply(d -> b.redeemInvite(iv9.token, cast(d))).equals("blocked"), "a blocked person's redeem: blocked");
    check(code.apply(d -> a.blockUser(w.fake.userId("own@t"), cast(d))).equals("bad_input"), "blocking myself: bad_input");
    check(w.<List<Blocked>>await(d -> a.myBlocks(d)).size() == 1, "my_blocks lists them");
    w.<Boolean>await(d -> a.revokeInvite(iv9.id, d));
    check(code.apply(d -> c.inviteInfo(iv9.token, cast(d))).equals("invite_invalid"), "a revoked invite: invite_invalid");
    // snapshots: ahead, stale, client_too_old, trimming and gap
    long rev = w.fake.messRev(mess);
    check(code.apply(d -> a.saveSnapshot(mess, start.toJson(), rev + 1, 1, "", cast(d))).equals("ahead"), "save_snapshot ahead of rev: ahead");
    w.<Snapped>await(d -> a.saveSnapshot(mess, start.toJson(), rev, 1, "", d));
    check(code.apply(d -> a.saveSnapshot(mess, start.toJson(), rev, 1, "", cast(d))).equals("stale"), "the same doc_rev again: stale");
    check(code.apply(d -> a.saveSnapshot(mess, start.toJson(), rev, 1, "Mine", cast(d))).equals("ok"), "the same doc_rev with a label: a version");
    w.fake.opsVMax = 2;
    w.<Applied>await(d -> a.applyOps(mess, a.newId(), "[{\"t\":\"x\"}]", 2, d));
    check(code.apply(d -> a.saveSnapshot(mess, start.toJson(), rev + 1, 1, "", cast(d))).equals("client_too_old"), "an old app's snapshot after v2 ops: client_too_old");
    w.fake.keepBelow = 0;
    w.<Snapped>await(d -> a.saveSnapshot(mess, start.toJson(), rev + 1, 2, "", d));
    check(code.apply(d -> a.opsSince(mess, 0, cast(d))).equals("gap"), "ops_since below the trimmed ops: gap");
  }

  @SuppressWarnings("unchecked") static <T> Done<T> cast(Done<Object> d) { return (Done<T>) (Done<?>) d; }

  /* ================= the convergence fuzz and the two oracles ================= */

  static String fuzz(long seed, boolean oracle, int steps) {
    Project start = rich();
    World w = world(seed, Ops.clone(start), "a@t", "b@t", "c@t", "d@t");
    Random r = w.r;
    Project ref = oracle ? Ops.clone(w.phones.get(0).p) : null;
    free.clear(); for (String k : keys) if (start.padOf(k) < 0) free.add(k);
    for (int i = 0; i < 4 && oracle; i++) for (int pad = 16 * i; pad < 16 * i + 6; pad++) {
      Phone ph = w.phones.get(i);
      String key = free.get(i * 8 + pad - 16 * i);
      if (!ph.p.kit[pad].equals(key) && ph.p.padOf(key) < 0) { ref.assign(pad, key); putOnPad(ph, pad, key); }
    }
    for (int t = 0; t < steps; t++) {
      Phone ph = w.phones.get(r.nextInt(4));
      int roll = r.nextInt(100);
      if (!ph.alive()) { if (roll < 30) { w.trace(ph.name + " reopen"); ph.reopen(); } }
      else if (roll < 62) {
        int n = 1 + r.nextInt(3);
        int[] own = oracle ? new int[]{16 * w.phones.indexOf(ph), 16 * w.phones.indexOf(ph) + 16, 2 * w.phones.indexOf(ph), 8 * w.phones.indexOf(ph)} : null;
        for (int e = 0; e < n; e++) { String what = randomEdit(ph, r, own, ref); w.trace(ph.name + " " + what); opsRun++; }
      }
      else if (roll < 66 && !oracle) { w.trace(ph.name + " undo"); ph.s.undo(); }
      else if (roll < 70) { boolean on = !ph.playing; w.trace(ph.name + " play " + on); ph.play(on); }
      else if (roll < 78) { if (ph.playing) { if (r.nextBoolean()) ph.arm(); else { w.trace(ph.name + " wrap " + ph.armed); ph.wrap(); } } }
      else if (roll < 81) { boolean on = r.nextBoolean(); w.trace(ph.name + " online " + on); w.fake.setOnline(ph.raw, on); }
      else if (roll < 83) {
        int how = r.nextInt(3); w.trace(ph.name + " kill " + how);
        if (how == 0 || ph.proc.fifo.isEmpty()) ph.kill();
        else if (how == 1) { Call x = ph.proc.fifo.poll(); x.run.run(); ph.kill(); w.fake.deliver(); }
        else { ph.cache.frozen = true; w.step(ph); ph.kill(); ph.cache.frozen = false; }
      }
      else if (roll < 85) { w.trace(ph.name + " close"); ph.s.close(); ph.kill(); }
      else if (roll < 86) { w.trace(ph.name + " version"); ph.s.saveVersion("v"); }
      else if (roll < 87) { w.trace(ph.name + " drop answer"); w.fake.dropNextAnswer(ph.raw); }
      w.tick(50 + r.nextInt(400));
      w.pumpSome(r.nextInt(8));
    }
    for (Phone ph : w.phones) { w.fake.setOnline(ph.raw, true); if (!ph.alive()) ph.reopen(); }
    if (!w.settle()) return "seed " + seed + (oracle ? " (oracle)" : "") + ": never settled; trace: " + w.lastTrace();
    if (!w.same()) return "seed " + seed + (oracle ? " (oracle)" : "") + ": phones differ: " + w.diffs() + " trace: " + w.lastTrace();
    if (oracle && !Ops.canon(w.phones.get(0).p).equals(Ops.canon(ref))) {
      String a = Ops.canon(w.phones.get(0).p), b = Ops.canon(ref); int i = 0; while (i < a.length() && i < b.length() && a.charAt(i) == b.charAt(i)) i++;
      return "seed " + seed + " (oracle): phones differ from the plain Project at " + i + ": " + a.substring(Math.max(0, i - 80), Math.min(a.length(), i + 80)) + " <> " + b.substring(Math.max(0, i - 80), Math.min(b.length(), i + 80)) + " trace: " + w.lastTrace();
    }
    if (seed % 10 == 0) {
      float[] first = null;
      for (Phone ph : w.phones) {
        Project q = Project.fromJson(Ops.uploadDoc(ph.p, true), null).project;
        q.cur = 0;
        for (String k : q.kit) if (!k.isEmpty() && !Project.isSynth(k)) try { lib.load(k); } catch (Exception e) { throw new RuntimeException(e); }
        int len = q.pats[0].len;
        float[] a = Engine.bounce(q, lib, 22050, false, Math.max(1, 64 / len));
        if (first == null) first = a; else if (!Arrays.equals(first, a)) return "seed " + seed + ": the bounce of " + ph.name + " differs (" + a.length + " vs " + first.length + " samples)";
      }
      bounced++;
    }
    return null;
  }
  static int bounced = 0;

  /* ================= how fast Ops.change is ================= */

  static void timing() {
    Project p = rich();
    Random r = new Random(3);
    for (int i = 14; i < 48; i++) if (p.kit[i].isEmpty()) p.kit[i] = keys.get(50 + i);
    for (int l = 0; l < 8; l++) {
      Project.Pattern P = p.pats[l]; P.len = 128;
      for (int pad = 0; pad < 48; pad++) {
        String k = p.kit[pad]; if (k.isEmpty()) continue;
        for (int j = 0; j < 128; j++) { if (r.nextInt(4) == 0) P.setStep(k, j, true); if (r.nextInt(9) == 0) P.setStrength(k, j, 2); if (r.nextInt(12) == 0) P.setChance(k, j, 50); }
        for (int j = 0; j < 6; j++) P.notes(k).add(new Project.Note(r.nextInt(120), r.nextInt(24) - 12, 2));
        P.setLock(k, 1, r.nextInt(128), .5f);
        if (pad % 4 == 0) P.laneStamp(pad + ":3", 4, 16, 0f, 1f, 1f);
      }
    }
    for (int pad = 0; pad < 48; pad++) if (!p.kit[pad].isEmpty()) { SoundEdit e = new SoundEdit(); e.vol = .7; p.setEdit(p.kit[pad], e); }
    int json = p.toJson().length();
    Project shadow = Ops.clone(p);
    com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
    long tid = Thread.currentThread().getId();
    String k1 = p.kit[1];
    for (int i = 0; i < 300; i++) { SoundEdit e = p.edits.get(k1); e.vol = (i % 100) / 100.0; Ops.change(shadow, p, Collections.emptyList()); }
    int n = 2000;
    long a0 = mx.getThreadAllocatedBytes(tid), t0 = System.nanoTime(); int ops = 0;
    for (int i = 0; i < n; i++) { SoundEdit e = p.edits.get(k1); e.vol = .1 + (i % 100) / 200.0; ops += Ops.change(shadow, p, Collections.emptyList()).ops.size(); }
    long t1 = System.nanoTime(), a1 = mx.getThreadAllocatedBytes(tid);
    for (int i = 0; i < n; i++) { p.pats[i % 8].setStep(k1, i % 128, (i / 8) % 2 == 0); Ops.change(shadow, p, Collections.emptyList()); }
    long t2 = System.nanoTime(), a2 = mx.getThreadAllocatedBytes(tid);
    for (int i = 0; i < 50; i++) Ops.clone(p);
    long a3 = mx.getThreadAllocatedBytes(tid);
    for (int i = 0; i < 20; i++) p.copy();
    long a4 = mx.getThreadAllocatedBytes(tid);
    double fader = (t1 - t0) / 1e6 / n, step = (t2 - t1) / 1e6 / n;
    long fb = (a1 - a0) / n, sb = (a2 - a1) / n, cb = (a3 - a2) / 50, jb = (a4 - a3) / 20;
    System.out.printf("TIMING Ops.change on a busy 8-loop mess (%d KB JSON, 8 loops of 128 steps, 48 pads): fader move %.3f ms and %d bytes, step toggle %.3f ms and %d bytes; one Ops.clone %d bytes, one Project.copy %d bytes%n",
        json / 1024, fader, fb, step, sb, cb, jb);
    check(ops == n, "a fader move is one op");
    check(fb < cb / 4 && sb < cb / 4, "Ops.change allocates far less than one Project copy (so it makes none)");
    check(fader < 5 && step < 5, "Ops.change is fast enough for every touch move");
  }

  public static void main(String[] args) throws Exception {
    lib = new Library(p -> java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(p)), args[0]);
    for (Library.Sound s : lib.sounds) if (s.key.startsWith("samples/")) keys.add(s.key);
    for (Library.Sound s : lib.sounds) if (!s.key.startsWith("samples/") && keys.size() < 120) keys.add(s.key);
    int seeds = args.length > 1 ? Integer.parseInt(args[1]) : 60;
    long T = System.currentTimeMillis();
    opsTests();
    sessionTests();
    timing();
    int fuzzFails = 0; int ran = 0;
    for (long seed = 1; seed <= seeds; seed++) {
      String f;
      try { f = fuzz(seed, false, 400); } catch (RuntimeException e) { f = "seed " + seed + ": " + e; e.printStackTrace(System.out); }
      ran++;
      if (f != null) { fuzzFails++; check(false, "fuzz " + f); if (fuzzFails > 3) break; }
    }
    int orFails = 0;
    for (long seed = 1001; seed <= 1000 + Math.max(4, seeds / 3); seed++) {
      String f;
      try { f = fuzz(seed, true, 300); } catch (RuntimeException e) { f = "seed " + seed + ": " + e; e.printStackTrace(System.out); }
      ran++;
      if (f != null) { orFails++; check(false, "oracle " + f); if (orFails > 3) break; }
    }
    check(bounced > 0, "the bounce oracle ran");
    System.out.println("FUZZ " + ran + " seeds (4 phones each), " + opsRun + " edits and ops run, " + bounced + " bounce checks, " + (System.currentTimeMillis() - T) / 1000 + " s");
    System.out.println(checks + " checks");
    if (failures == 0) System.out.println("SYNC GOOD"); else System.out.println(failures + " FAILED");
    System.exit(failures == 0 ? 0 : 1);
  }
}
