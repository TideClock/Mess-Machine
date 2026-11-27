/* Accounts client core tests (Slice A): CloudStore and HttpCloud against FakeCloud and a scripted http, with
 * several simulated phones on one FakeCloud and real Project objects. Run with tools/cloud-check.sh. */
import com.tideclock.messmachine.engine.*;
import com.tideclock.messmachine.cloud.*;
import java.util.*;

public class CloudTest {
  static int failures = 0, checks = 0;
  static void check(boolean ok, String what) { checks++; System.out.println((ok ? "PASS " : "FAIL ") + what); if (!ok) failures++; }

  /* ---------- small seams ---------- */

  static class T implements Clock { long now = 1_795_000_000_000L; public long nowMs() { return now; } }

  static class Res<V> implements Done<V> {
    V v; CloudErr e; int n;
    public void done(V value, CloudErr err) { v = value; e = err; n++; }
    String code() { return e == null ? "" : e.code; }
  }

  static class MemTokens implements TokenStore {
    String s; int saves;
    public String load() { return s; }
    public void save(String json) { s = json; saves++; }
  }

  static class MemCache implements CloudCache {
    final Map<String, String> m = new LinkedHashMap<>();
    public String load(String name) { return m.get(name); }
    public void save(String name, String json) { if (json == null) m.remove(name); else m.put(name, json); }
  }

  /** The app side of one phone: its mess files, its Library of downloaded sounds, what it was told. */
  static class Host implements StoreHost {
    final String prefix; int next = 1;
    final Map<String, String> docs = new LinkedHashMap<>();
    final Map<String, String> lib = new LinkedHashMap<>();     // key to device path, sounds handed over by the store
    final List<String> events = new ArrayList<>();
    final List<Integer> notices = new ArrayList<>();
    final List<String> texts = new ArrayList<>();
    Host(String prefix) { this.prefix = prefix; }
    public void onAccount() {}
    public void onMesses() {}
    public String localDoc(String id) { return docs.get(id); }
    public List<String> needs(String id) {
      List<String> out = new ArrayList<>();
      String d = docs.get(id); if (d == null) return out;
      Project p = Project.fromJson(d, null).project;
      for (String k : p.kit) if (k.startsWith("bn:") && !out.contains(k)) out.add(k);
      return out;
    }
    public String adopt(MessRow row, String doc) { String id = prefix + (next++); docs.put(id, doc); events.add("adopt:" + id); return id; }
    public void takeServer(String id, String name, String doc) { docs.put(id, named(doc, name)); events.add("take:" + id); }
    public String keepBoth(String id, String copyName, String serverName, String serverDoc) {
      String copy = prefix + (next++);
      docs.put(copy, named(docs.get(id), copyName));
      docs.put(id, named(serverDoc, serverName));
      events.add("keepBoth:" + id + ">" + copy);
      return copy;
    }
    public void soundArrived(Sound s, String path) { lib.put(s.key, path); events.add("arrived:" + s.key); }
    public void soundGone(Sound s) { lib.remove(s.key); events.add("gone:" + s.key); }
    public void notice(int kind, String text) { notices.add(kind); texts.add(text); events.add("notice:" + kind); }
    public void dropLocal(String id) { docs.remove(id); events.add("drop:" + id); }
    public Sound soundInfo(String key) { return new Sound(key, "", "user", "", "wav", 0, 0, 0, "mic", "Take " + key.substring(3), 96, 0, "A minor", "ready"); }
    static String named(String doc, String name) {
      Project p = Project.fromJson(doc, null).project;
      if (name.equals(p.name)) return doc;
      p.name = name; return p.toJson();
    }
  }

  /** A FakeCloud with two doors a test needs that Slice A cannot reach: a full sound space and a mess with ops. */
  static class TestFake extends FakeCloud {
    TestFake(Clock c) { super(c); }
    void fill(String email, long bytes) { users.get(userId(email)).soundBytes = bytes; }
    void fakeOps(String messId) { FakeMess m = messes.get(messId); m.rev = m.rev + 1; }
  }

  static T clock;
  static TestFake fake;

  static class Phone {
    final String email; final CloudApi api; final MemTokens tok = new MemTokens(); final MemCache cache = new MemCache(); final Host host;
    CloudStore store;
    Phone(String email, String prefix) { this.email = email; api = fake.client(email); host = new Host(prefix); restart(); }
    /** The app is killed and opened again: a new store over the same device files. */
    void restart() { boolean d = store == null || store.dataOk; store = new CloudStore(api, tok, cache, clock, host); store.dataOk = d; store.start(); }
    String save(String id, Project p) { host.docs.put(id, p.toJson()); store.saved(id, p.name); return id; }
    Project project(String id) { return Project.fromJson(host.docs.get(id), null).project; }
    int notices(int kind) { int n = 0; for (int k : host.notices) if (k == kind) n++; return n; }
    int calls(String name) { int n = 0; for (String s : fake.calls(api)) if (s.equals(name)) n++; return n; }
  }

  static void fresh() { clock = new T(); fake = new TestFake(clock); }

  /** Seconds go by: every phone ticks each second and every queued answer is delivered. */
  static void run(int seconds, Phone... ps) {
    for (int i = 0; i < seconds; i++) {
      clock.now += 1000;
      for (Phone p : ps) p.store.tick();
      fake.deliver();
      for (Phone p : ps) p.store.tick();
      fake.deliver();
    }
  }

  static void signIn(Phone p, boolean acceptTerms) {
    clock.now += 61_000;   // one code a minute per address
    Res<Boolean> a = new Res<>(); p.store.requestCode(p.email, a); fake.deliver();
    Res<Me> v = new Res<>(); p.store.verify(p.email, fake.lastCode(p.email), v); fake.deliver();
    if (acceptTerms && p.store.state == CloudStore.TERMS) { Res<Boolean> t = new Res<>(); p.store.acceptTerms(t); fake.deliver(); }
    fake.deliver();
  }

  static String canon(String doc) { return doc == null ? "" : Project.fromJson(doc, null).project.toJson(false); }

  static Project mess(String name, int bpm) { Project p = Project.demo(); p.name = name; p.bpm = bpm; return p; }

  static byte[] wav(int frames, int seed) {
    float[] d = new float[frames]; Random r = new Random(seed);
    for (int i = 0; i < frames; i++) d[i] = (float) (r.nextGaussian() * 0.2);
    return Wav.writeMono16(d, 44100);
  }

  static String linkedRow(Phone p, String cloud) { for (MessRow r : p.store.rows) if (r.id.equals(cloud)) return r.name; return null; }

  /* ---------- the tests ---------- */

  public static void main(String[] a) throws Exception {
    signInTests();
    saveAndOpen();
    bothEdit();
    twoPhonesMerge();
    twoPhonesThisOne();
    offlineAndKill();
    lostAnswers();
    quotas();
    sounds();
    signOutAndTokens();
    soloFields();
    updateAndBlocked();
    httpCloud();
    System.out.println(failures == 0 ? "CLOUD GOOD (" + checks + " checks)" : failures + " FAILED (of " + checks + " checks)");
    System.exit(failures == 0 ? 0 : 1);
  }

  static void signInTests() {
    fresh();
    Phone p1 = new Phone("mia@test", "a");
    check(p1.store.state == CloudStore.OUT && p1.store.me == null, "a phone with nothing stored starts signed out");
    Res<Boolean> r1 = new Res<>(); p1.store.requestCode("mia@test", r1);
    check(r1.n == 0, "no callback runs inside the call that took it");
    fake.deliver();
    check(r1.v == Boolean.TRUE && "mia@test".equals(p1.store.codeSentTo), "a code is sent and the store remembers where");
    Res<Boolean> r2 = new Res<>(); p1.store.requestCode("mia@test", r2); fake.deliver();
    check("code_wait".equals(r2.code()), "a second code within a minute answers code_wait: " + r2.code());
    String code = fake.lastCode("mia@test");
    String wrong = code.equals("111111") ? "222222" : "111111";
    Res<Me> w = new Res<>(); p1.store.verify("mia@test", wrong, w); fake.deliver();
    check("code_wrong".equals(w.code()) && p1.store.state == CloudStore.OUT && p1.tok.load() == null, "a wrong code answers code_wrong and signs nobody in");
    clock.now += 601_000;
    Res<Me> x = new Res<>(); p1.store.verify("mia@test", code, x); fake.deliver();
    check("code_wrong".equals(x.code()), "a code older than 10 minutes answers code_wrong: " + x.code());
    Res<Boolean> r3 = new Res<>(); p1.store.requestCode("mia@test", r3); fake.deliver();
    Res<Me> ok = new Res<>(); p1.store.verify("mia@test", " " + fake.lastCode("mia@test") + " ", ok); fake.deliver();
    check(ok.v != null && p1.store.state == CloudStore.TERMS && !ok.v.terms_ok && p1.tok.load() != null,
        "the right code saves the session and asks for the terms first");
    check(p1.store.nameNeeded() && ok.v.display_name.startsWith("Player "), "a new account has a made-up name and name_set false");
    // nothing uploads before the terms
    p1.save("a1", mess("Before terms", 100));
    run(8, p1);
    check(p1.calls("createMess") == 0 && fake.messCount("mia@test") == 0, "nothing goes online before the terms are accepted");
    Res<Boolean> t = new Res<>(); p1.store.acceptTerms(t); fake.deliver();
    check(t.v == Boolean.TRUE && p1.store.state == CloudStore.IN && p1.store.me.terms_ok, "accepting the terms signs in fully");
    run(8, p1);
    check(fake.messCount("mia@test") == 1 && p1.store.mark("a1") == CloudStore.M_SYNCED, "the mess saved before the terms goes up after them");
    Res<Me> n = new Res<>(); p1.store.setName("Mia", n); fake.deliver();
    check(n.v != null && "Mia".equals(p1.store.me.display_name) && !p1.store.nameNeeded(), "the name is set once");
    Res<Me> bad = new Res<>(); p1.store.setName("   ", bad); fake.deliver();
    check("bad_input".equals(bad.code()), "an empty name answers bad_input");
    // the second phone of the same account is asked for neither
    Phone p2 = new Phone("mia@test", "b");
    signIn(p2, false);
    check(p2.store.state == CloudStore.IN && !p2.store.nameNeeded() && "Mia".equals(p2.store.me.display_name),
        "phone 2 of the same account is asked for neither terms nor name");
    check(p2.store.rows.size() == 1, "phone 2 sees the mess list at once: " + p2.store.rows.size());
  }

  static void saveAndOpen() {
    fresh();
    Phone p1 = new Phone("mia@test", "a"), p2 = new Phone("mia@test", "b");
    signIn(p1, true); signIn(p2, true);
    Project m = mess("Basement jam", 97);
    m.pats[1].setStep("samples/basement-kick.wav", 3, true);
    p1.save("a1", m);
    run(3, p1, p2);
    check(p1.calls("createMess") == 0 && p1.store.mark("a1") == CloudStore.M_WAITING && p1.store.waiting() == 1, "a save waits 5 seconds before it goes up");
    run(3, p1, p2);
    String cid = p1.store.cloudId("a1");
    check(p1.calls("createMess") == 1 && !cid.isEmpty() && p1.store.mark("a1") == CloudStore.M_SYNCED && p1.store.waiting() == 0,
        "then it goes up once and the mark reads synced");
    check(canon(fake.messDoc(cid)).equals(m.toJson(false)) && "Basement jam".equals(fake.messName(cid)), "the server holds the mess as saved, with its name");
    // background sends at once
    p1.save("a2", mess("Quick one", 120));
    p1.store.background(); fake.deliver();
    check(!p1.store.cloudId("a2").isEmpty(), "going to the background sends at once");
    // open on phone 2
    p2.store.refresh(); fake.deliver(); fake.deliver();
    check(p2.store.rows.size() == 2 && p2.store.localId(cid).isEmpty(), "phone 2 lists both, neither on this phone yet");
    Res<String> o = new Res<>(); p2.store.open(cid, o); fake.deliver();
    check(o.v != null && canon(p2.host.docs.get(o.v)).equals(m.toJson(false)), "opened on phone 2, the Project JSON is identical");
    check(p2.store.localId(cid).equals(o.v) && p2.store.mark(o.v) == CloudStore.M_SYNCED, "phone 2 links it and marks it synced");
    // an edit on phone 1 reaches phone 2 at its next sync
    m.bpm = 131; m.pats[0].setStep("samples/wet-clap.wav", 9, true);
    p1.save("a1", m);
    run(6, p1, p2);
    long rev = fake.messRev(cid);
    p2.store.refresh(); fake.deliver(); fake.deliver(); fake.deliver();
    check(rev == 1 && canon(p2.host.docs.get(o.v)).equals(m.toJson(false)) && p2.host.events.contains("take:" + o.v),
        "an edit on phone 1 arrives on phone 2 at its next sync");
    // the pull rule runs by itself while the Messes sheet is open
    p2.store.listOpen = true;
    m.bpm = 140; p1.save("a1", m);
    run(70, p1, p2);
    check(p2.project(o.v).bpm == 140, "with the Messes sheet open phone 2 pulls by itself within a minute");
    p2.store.listOpen = false;
    // the server's name wins on every pull
    Res<Boolean> rn = new Res<>(); p1.store.rename("a1", "Basement jam 2", rn); fake.deliver();
    check(rn.v == Boolean.TRUE && "Basement jam 2".equals(fake.messName(cid)) && fake.messRev(cid) == 2, "rename sets the name, rev stays");
    p2.store.refresh(); fake.deliver(); fake.deliver(); fake.deliver();
    check("Basement jam 2".equals(p2.project(o.v).name), "the new name reaches phone 2");
    // delete: the cloud mess goes, the phone file stays
    Res<Boolean> d = new Res<>(); p1.store.delete("a2", d); fake.deliver();
    check(d.v == Boolean.TRUE && fake.messCount("mia@test") == 1 && p1.host.docs.containsKey("a2") && p1.store.cloudId("a2").isEmpty(), "delete removes the cloud mess and keeps the phone file");
    p1.save("a2", mess("Quick one", 121)); run(6, p1);
    check(fake.messCount("mia@test") == 1 && p1.store.mark("a2") == CloudStore.M_PHONE, "a deleted mess stays off the cloud on its next save");
    p1.store.keepOff("a2", false); run(6, p1);
    check(fake.messCount("mia@test") == 2 && p1.store.mark("a2") == CloudStore.M_SYNCED, "linking it again puts it back online");
    p1.store.keepOff("a2", true); run(3, p1);
    check(fake.messCount("mia@test") == 1 && p1.store.mark("a2") == CloudStore.M_PHONE, "keep off the cloud deletes my cloud mess");
    p2.store.refresh(); fake.deliver(); fake.deliver(); fake.deliver();
    check(p2.store.rows.size() == 1, "phone 2's list drops it");
  }

  static void bothEdit() {
    fresh();
    Phone p1 = new Phone("mia@test", "a"), p2 = new Phone("mia@test", "b");
    signIn(p1, true); signIn(p2, true);
    p1.save("a1", mess("Duet", 100)); run(6, p1, p2);
    String cid = p1.store.cloudId("a1");
    Res<String> o = new Res<>(); p2.store.open(cid, o); fake.deliver();
    String b1 = o.v;
    Project m1 = p1.project("a1"); m1.bpm = 111; m1.pats[2].setStep("samples/basement-kick.wav", 1, true);
    Project m2 = p2.project(b1); m2.bpm = 122; m2.swing = 40;
    p1.save("a1", m1); p2.save(b1, m2);
    run(8, p1, p2);
    // Tyler's answer 7: my own mess on two of my phones asks, and keeps both until answered
    check(p2.notices(CloudStore.N_TWO_PHONES) == 1 && p1.notices(CloudStore.N_TWO_PHONES) == 0 && p2.store.two != null && p2.store.two.canMerge,
        "a clash between two of my phones asks once, on the phone that lost the race, and can merge");
    String copy = null;
    for (String id : p2.host.docs.keySet()) if (!id.equals(b1)) copy = id;
    check(copy != null && "Duet (this phone)".equals(p2.project(copy).name), "the copy is named '<name> (this phone)'");
    check(canon(p2.host.docs.get(b1)).equals(m1.toJson(false)) && p2.store.localId(cid).equals(b1), "the old local id holds the server's mess and keeps the cloud id");
    check(p2.project(copy).bpm == 122 && p2.project(copy).swing == 40, "the copy holds this phone's edit");
    String ccid = p2.store.cloudId(copy);
    check(!ccid.isEmpty() && fake.messCount("mia@test") == 2 && canon(fake.messDoc(ccid)).equals(canon(p2.host.docs.get(copy))),
        "the copy goes up as a new mess, so nothing is lost");
    check(canon(fake.messDoc(cid)).equals(m1.toJson(false)) && p2.store.mark(b1) == CloudStore.M_SYNCED && p2.store.mark(copy) == CloudStore.M_SYNCED,
        "the server's mess is untouched and both marks read synced");
    p1.store.refresh(); fake.deliver(); fake.deliver(); fake.deliver();
    check(p1.store.rows.size() == 2 && p1.project("a1").bpm == 111, "phone 1 sees both messes and keeps its own");
    p2.store.settleTwo(CloudStore.TWO_KEEP_BOTH);
    check(p2.store.two == null && p2.host.docs.size() == 2, "Keep both leaves both");
  }

  /** Tyler's answer 7: Merge lays this phone's changes on the other phone's, and the copy goes. */
  static void twoPhonesMerge() {
    fresh();
    Phone p1 = new Phone("mia@test", "a"), p2 = new Phone("mia@test", "b");
    signIn(p1, true); signIn(p2, true);
    p1.save("a1", mess("Duet", 100)); run(6, p1, p2);
    String cid = p1.store.cloudId("a1");
    Res<String> o = new Res<>(); p2.store.open(cid, o); fake.deliver();
    String b1 = o.v;
    Project m1 = p1.project("a1"); m1.bpm = 111; m1.pats[2].setStep("samples/basement-kick.wav", 1, true);
    Project m2 = p2.project(b1); m2.swing = 40; m2.pats[3].setStep("samples/basement-kick.wav", 5, true);
    p1.save("a1", m1); p2.save(b1, m2);
    run(8, p1, p2);
    check(p2.store.two != null, "the question waits");
    String copy = p2.store.two.copyId;
    p2.store.settleTwo(CloudStore.TWO_MERGE);
    run(8, p1, p2);
    Project got = Project.fromJson(fake.messDoc(cid), null).project;
    check(got.bpm == 111 && got.swing == 40 && got.pats[2].step("samples/basement-kick.wav", 1) && got.pats[3].step("samples/basement-kick.wav", 5),
        "merge keeps both phones' changes: bpm " + got.bpm + ", swing " + got.swing);
    check(!p2.host.docs.containsKey(copy) && fake.messCount("mia@test") == 1, "the copy is gone, on the phone and online");
    p1.store.refresh(); fake.deliver(); fake.deliver(); fake.deliver(); run(3, p1);
    check(p1.project("a1").swing == 40, "phone 1 gets the merge");
  }

  /** Tyler's answer 7: Keep this one makes this phone's the main mess. */
  static void twoPhonesThisOne() {
    fresh();
    Phone p1 = new Phone("mia@test", "a"), p2 = new Phone("mia@test", "b");
    signIn(p1, true); signIn(p2, true);
    p1.save("a1", mess("Duet", 100)); run(6, p1, p2);
    String cid = p1.store.cloudId("a1");
    Res<String> o = new Res<>(); p2.store.open(cid, o); fake.deliver();
    String b1 = o.v;
    Project m1 = p1.project("a1"); m1.bpm = 111;
    Project m2 = p2.project(b1); m2.bpm = 122;
    p1.save("a1", m1); p2.save(b1, m2);
    run(8, p1, p2);
    p2.store.settleTwo(CloudStore.TWO_THIS_ONE);
    run(8, p1, p2);
    check(Project.fromJson(fake.messDoc(cid), null).project.bpm == 122 && fake.messCount("mia@test") == 1 && p2.host.docs.size() == 1,
        "this phone's is the main mess and the copy is gone");
  }

  static void offlineAndKill() {
    fresh();
    Phone p1 = new Phone("mia@test", "a");
    signIn(p1, true);
    p1.save("a1", mess("Train ride", 90)); run(6, p1);
    String cid = p1.store.cloudId("a1");
    fake.setOnline(p1.api, false);
    Project m = p1.project("a1"); m.bpm = 91; p1.save("a1", m);
    run(8, p1);
    check(p1.store.mark("a1") == CloudStore.M_OFFLINE && p1.store.waiting() == 1 && fake.messRev(cid) == 0 && !p1.store.online,
        "offline, the edit waits and the mark reads offline");
    m.bpm = 92; p1.save("a1", m);
    p1.save("a2", mess("Made offline", 70));
    run(3, p1);
    fake.setOnline(p1.api, true);
    run(15, p1);
    check(fake.messRev(cid) == 1 && canon(fake.messDoc(cid)).equals(m.toJson(false)) && p1.store.mark("a1") == CloudStore.M_SYNCED,
        "back online, the queued edits go out as one save");
    check(!p1.store.cloudId("a2").isEmpty() && fake.messCount("mia@test") == 2 && p1.store.online, "a mess made offline goes up too");
    // killed while offline with a change waiting: the change survives in the cache and goes out on the next launch
    fake.setOnline(p1.api, false);
    m.bpm = 93; p1.save("a1", m);
    run(1, p1);
    fake.setOnline(p1.api, true);
    p1.restart();
    check(p1.store.state == CloudStore.IN && p1.store.mark("a1") == CloudStore.M_WAITING, "after a kill the store reopens signed in, with the change waiting");
    run(3, p1);
    check(fake.messRev(cid) == 2 && p1.project("a1").bpm == 93 && canon(fake.messDoc(cid)).equals(m.toJson(false)), "the waiting change goes out after the relaunch");
    int puts = p1.calls("putDoc");
    p1.restart(); run(10, p1);
    check(p1.calls("putDoc") == puts && p1.calls("createMess") == 2, "a relaunch with nothing changed sends nothing");
  }

  static void lostAnswers() {
    fresh();
    Phone p1 = new Phone("mia@test", "a");
    signIn(p1, true);
    p1.save("a1", mess("Lost", 100));
    fake.dropNextAnswer(p1.api);
    run(12, p1);
    check(fake.messCount("mia@test") == 1 && p1.calls("createMess") == 2 && p1.store.mark("a1") == CloudStore.M_SYNCED,
        "a lost create answer is retried with the same id and makes one mess");
    String cid = p1.store.cloudId("a1");
    check(p1.calls("putDoc") == 0 && fake.messRev(cid) == 0, "and no extra save follows when the server already holds this doc");
    Project m = p1.project("a1"); m.bpm = 101; p1.save("a1", m);
    fake.dropNextAnswer(p1.api);
    run(12, p1);
    check(fake.messRev(cid) == 1 && p1.notices(CloudStore.N_KEPT_BOTH) == 0 && fake.messCount("mia@test") == 1 && p1.store.mark("a1") == CloudStore.M_SYNCED,
        "a lost save answer does not save twice and keeps no copy");
    // lost answer, then another edit before the retry
    m.bpm = 102; p1.save("a1", m);
    fake.dropNextAnswer(p1.api);
    run(6, p1);
    m.bpm = 103; p1.save("a1", m);
    run(12, p1);
    check(fake.messRev(cid) == 3 && canon(fake.messDoc(cid)).equals(m.toJson(false)) && p1.notices(CloudStore.N_KEPT_BOTH) == 0 && p1.host.docs.size() == 1,
        "a lost save followed by a newer edit sends the newer one and keeps no copy");
    // a lost create, then the app is killed before any answer: the retry still settles on one mess with the newest doc
    p1.save("a2", mess("Killed", 60));
    fake.setOnline(p1.api, false);
    run(6, p1);
    fake.setOnline(p1.api, true);
    p1.restart();
    run(4, p1);
    String c2 = p1.store.cloudId("a2");
    check(!c2.isEmpty() && fake.messCount("mia@test") == 2 && canon(fake.messDoc(c2)).equals(canon(p1.host.docs.get("a2"))), "a create cut off by a kill makes one mess on relaunch");
  }

  static void quotas() {
    fresh();
    Phone p1 = new Phone("mia@test", "a");
    signIn(p1, true);
    for (int i = 0; i < 201; i++) p1.save("m" + i, mess("Mess " + i, 100));
    run(6, p1);
    int refused = 0;
    for (int i = 0; i < 201; i++) if (p1.store.mark("m" + i) == CloudStore.M_REFUSED) refused++;
    check(fake.messCount("mia@test") == 200 && refused == 1 && p1.notices(CloudStore.N_FULL) == 1, "the 201st mess is refused: " + fake.messCount("mia@test") + " online, " + refused + " refused");
    check(p1.host.texts.contains("You have 200 messes online. Delete one to save another."), "with a plain message: " + p1.host.texts);
    CloudApi raw = fake.client("mia@test"); raw.setSession(fake.newSession("mia@test"));
    Res<Rev> r = new Res<>(); raw.createMess(raw.newId(), "One more", "{\"name\":\"x\"}", r); fake.deliver();
    check("too_many_messes".equals(r.code()), "create_mess answers too_many_messes: " + r.code());

    fresh();
    p1 = new Phone("mia@test", "a");
    signIn(p1, true);
    Project big = mess("Huge", 100);
    StringBuilder sb = new StringBuilder(); while (sb.length() < 1_100_000) sb.append("0123456789");
    big.extra.put("padding", sb.toString());
    p1.save("a1", big);
    p1.save("a2", mess("Small", 100));
    run(6, p1);
    check(p1.store.mark("a1") == CloudStore.M_REFUSED && p1.host.texts.contains("This mess is too big to save online.") && p1.store.mark("a2") == CloudStore.M_SYNCED,
        "a mess over 1 MB is refused with a plain message; others still go");
    raw = fake.client("mia@test"); raw.setSession(fake.newSession("mia@test"));
    Res<Saved> pd = new Res<>(); raw.putDoc(p1.store.cloudId("a2"), big.toJson(), 0, "Saved", pd); fake.deliver();
    check("too_big".equals(pd.code()), "put_doc of a doc over 1 MB answers too_big: " + pd.code());
    Res<Saved> pc = new Res<>(); raw.putDoc(p1.store.cloudId("a2"), mess("x", 1).toJson(), 7, "Saved", pc); fake.deliver();
    check("conflict".equals(pc.code()), "put_doc with an old base_rev answers conflict: " + pc.code());

    // sounds over the caps: a notice each, the sound stays on the phone, the mess still saves
    Project s = mess("Sounds", 100);
    fake.putLocalSound(p1.api, "bn:long1", wav(200, 1), 101.0, 44100);
    fake.putLocalSound(p1.api, "bn:huge1", new byte[20_971_521], 50.0, 44100);
    s.kit[20] = "bn:long1"; s.kit[21] = "bn:huge1";
    p1.store.dataOk = true;
    p1.save("a3", s);
    run(6, p1);
    check(p1.notices(CloudStore.N_TOO_LONG) == 1 && p1.host.texts.contains("That sound is over 100 seconds. It stays on this phone."), "a sound over 100 s gives too_long with a plain message");
    check(p1.notices(CloudStore.N_TOO_BIG) == 1 && p1.host.texts.contains("That sound is over 20 MB. It stays on this phone."), "a sound over 20 MB gives too_big with a plain message");
    check(!p1.store.cloudId("a3").isEmpty() && fake.uploads(p1.api) == 0 && p1.store.mark("a3") == CloudStore.M_SYNCED, "the mess still saves and nothing is uploaded");
    p1.save("a3", s); run(6, p1);
    check(p1.notices(CloudStore.N_TOO_LONG) == 1 && p1.calls("registerSound") == 2, "a refused sound is not tried again on every save");
    // 250 MB full
    fake.fill("mia@test", 262_144_000L - 1000);
    byte[] w = wav(2000, 2);
    fake.putLocalSound(p1.api, "bn:take9", w, 0.05, 44100);
    Project q = mess("Full", 100); q.kit[22] = "bn:take9";
    p1.save("a4", q); run(6, p1);
    check(p1.notices(CloudStore.N_QUOTA) == 1 && p1.host.texts.contains("Your online sound space is full. This sound stays on this phone.") && !p1.store.cloudId("a4").isEmpty(),
        "a full 250 MB space gives quota with a plain message, and the mess still saves");
    Res<Prepared> pr = new Res<>(); p1.api.prepareUpload("bn:take9", pr); fake.deliver();
    Res<Registered> rg = new Res<>(); p1.api.registerSound(pr.v, "mic", "bn:take9", "T", 0, 0, "", rg); fake.deliver();
    check("quota".equals(rg.code()), "register_sound answers quota: " + rg.code());
  }

  static void sounds() {
    fresh();
    Phone p1 = new Phone("mia@test", "a"), p2 = new Phone("mia@test", "b");
    signIn(p1, true); signIn(p2, true);
    byte[] take = wav(22050, 7);
    fake.putLocalSound(p1.api, "bn:mf3k2a417", take, 0.5, 44100);
    Project m = mess("Mic jam", 100);
    m.kit[16] = "bn:mf3k2a417";
    m.pats[0].setStep("bn:mf3k2a417", 0, true);
    p1.store.dataOk = false;
    p1.save("a1", m);
    run(6, p1, p2);
    String cid = p1.store.cloudId("a1");
    check(!cid.isEmpty() && fake.uploads(p1.api) == 0 && p1.store.mark("a1") == CloudStore.M_WAITING, "off Wi-Fi the doc goes up, the sound waits, the mark reads waiting");
    p1.store.dataOk = true;
    run(3, p1, p2);
    String uid = fake.userId("mia@test");
    String sha = Sha256.hex(take);
    check(fake.uploads(p1.api) == 1 && Arrays.equals(fake.stored(uid + "/" + sha + ".wav"), take) && "ready".equals(fake.fileState("mia@test", sha)),
        "on Wi-Fi the WAV uploads once, under my own folder, bytes identical");
    check(fake.soundBytes("mia@test") == take.length && p1.store.me.sound_bytes == take.length, "usage counts it on the server and on the phone");
    check(fake.messRev(cid) == 1 && p1.store.mark("a1") == CloudStore.M_SYNCED && p1.store.sounds("a1").size() == 1, "the doc goes again so other phones look, and the sounds list sits beside the mess");
    check(p1.cache.load(cid + ".sounds.json") != null && !fake.messDoc(cid).contains("\"sounds\"") && fake.messDoc(cid).contains("\"v\":3"),
        "the sounds list is kept beside the mess; the mess format stays v3");
    // phone 2 opens it and gets the sound
    p2.store.dataOk = true;
    Res<String> o = new Res<>(); p2.store.open(cid, o); fake.deliver(); fake.deliver();
    String path = p2.host.lib.get("bn:mf3k2a417");
    check(o.v != null && path != null && Arrays.equals(fake.deviceFile(p2.api, path), take), "phone 2 plays the sound: bytes identical");
    check(fake.downloads(p2.api) == 1 && p2.store.missing(o.v) == 0, "downloaded once, nothing missing");
    m.bpm = 101; p1.save("a1", m); run(6, p1, p2);
    p2.store.refresh(); fake.deliver(); fake.deliver(); fake.deliver(); fake.deliver();
    Res<String> o2 = new Res<>(); p2.store.open(cid, o2); fake.deliver(); fake.deliver();
    check(fake.downloads(p2.api) == 1 && p2.project(o.v).bpm == 101, "later syncs and opens do not download it again");
    p2.restart(); run(2, p2);
    Res<String> o3 = new Res<>(); p2.store.open(cid, o3); fake.deliver(); fake.deliver();
    check(fake.downloads(p2.api) == 1 && fake.calls(p2.api).contains("cachedSound"), "after a relaunch it comes from the cache by sha256, not the network");
    check(fake.uploads(p2.api) == 0, "phone 2 never uploads a sound it only received");
    // phone 2 off Wi-Fi: the sound waits until "Get sounds now"
    fresh();
    p1 = new Phone("mia@test", "a"); Phone p3 = new Phone("mia@test", "c");
    signIn(p1, true); signIn(p3, true);
    fake.putLocalSound(p1.api, "bn:t1", take, 0.5, 44100);
    m = mess("Later", 100); m.kit[16] = "bn:t1";
    p1.store.dataOk = true; p1.save("a1", m); run(6, p1);
    p3.store.dataOk = false;
    Res<String> o4 = new Res<>(); p3.store.open(p1.store.cloudId("a1"), o4); fake.deliver(); fake.deliver();
    check(o4.v != null && fake.downloads(p3.api) == 0 && p3.store.missing(o4.v) == 1, "off Wi-Fi phone 3 opens the mess with 1 sound missing");
    p3.store.getSounds(o4.v); fake.deliver(); fake.deliver(); fake.deliver();
    check(fake.downloads(p3.api) == 1 && p3.store.missing(o4.v) == 0 && p3.host.lib.containsKey("bn:t1"), "Get sounds now fetches it once whatever dataOk says");

    // another owner's sound is attached, never uploaded (Review A M5); when its maker deletes it, it is purged
    fresh();
    Phone a = new Phone("mia@test", "a"), b = new Phone("sam@test", "s");
    signIn(a, true); signIn(b, true);
    a.store.dataOk = true; b.store.dataOk = true;
    fake.putLocalSound(a.api, "bn:mia1", take, 0.5, 44100);
    m = mess("Mia's", 100); m.kit[16] = "bn:mia1";
    a.save("a1", m); run(6, a, b);
    String acid = a.store.cloudId("a1");
    fake.addMember(acid, "sam@test");
    Res<String> ob = new Res<>(); b.store.open(acid, ob); fake.deliver(); fake.deliver();
    check(ob.v != null && b.host.lib.containsKey("bn:mia1"), "Sam opens Mia's mess and gets her sound");
    Project sm = mess("Sam's", 90); sm.kit[3] = "bn:mia1";
    b.save("s9", sm); run(6, a, b);
    String bcid = b.store.cloudId("s9");
    List<Sound> bl = b.store.sounds("s9");
    check(!bcid.isEmpty() && b.calls("attachSound") == 1 && b.calls("registerSound") == 0 && fake.uploads(b.api) == 0,
        "Sam using Mia's sound in his own mess attaches it and uploads nothing");
    check(bl.size() == 1 && bl.get(0).owner.equals(fake.userId("mia@test")) && fake.storedCount() == 1, "the attached sound stays Mia's file");
    // Mia deletes the sound: delete_sound, remove the object, delete_sound again
    String sha2 = Sha256.hex(take);
    Res<Deleted> d1 = new Res<>(); a.api.deleteSound(sha2, d1); fake.deliver();
    Res<Boolean> rm = new Res<>(); a.api.removeObject(d1.v.path, rm); fake.deliver();
    Res<Deleted> d2 = new Res<>(); a.api.deleteSound(sha2, d2); fake.deliver();
    check("deleting".equals(d1.v.state) && rm.v == Boolean.TRUE && "deleted".equals(d2.v.state) && fake.storedCount() == 0 && fake.soundBytes("mia@test") == 0,
        "delete_sound runs its three steps and frees the space");
    String cached = b.host.lib.get("bn:mia1");
    b.host.events.clear();
    Res<String> og = new Res<>();
    b.store.open(acid, new Done<String>() { public void done(String v, CloudErr e) { b.host.events.add("opened"); og.done(v, e); } });
    fake.deliver(); fake.deliver();
    int gi = b.host.events.indexOf("gone:bn:mia1"), oi = b.host.events.indexOf("opened");
    check(gi >= 0 && oi > gi, "a gone sound is purged before the open answers (so before anything plays): " + b.host.events);
    check(fake.deviceFile(b.api, cached) == null && !b.host.lib.containsKey("bn:mia1"), "its cache file and Library entry are gone");
    Project.Loaded L = Project.fromJson(b.host.docs.get(og.v), k -> !k.startsWith("bn:") || b.host.lib.containsKey(k));
    check(L.missing == 1 && "bn:mia1".equals(L.project.kit[16]), "the pad keeps the key and reads Missing");
    b.store.refresh(); fake.deliver(); fake.deliver(); fake.deliver();
    Res<String> og2 = new Res<>(); b.store.open(bcid, og2); fake.deliver(); fake.deliver();
    check(b.store.sounds("s9").get(0).src.equals("gone") && b.store.missing("s9") == 0, "Sam's own mess lists it as gone too, not as missing");
    fake.putLocalSound(b.api, "bn:mia1", take, 0.5, 44100);    // as if it still sat in Sam's Library
    sm.bpm = 91; b.save("s9", sm); run(6, a, b);
    check(fake.uploads(b.api) == 0 && b.calls("registerSound") == 0 && fake.storedCount() == 0, "Sam's phone never uploads Mia's deleted sound as his own");
  }

  static void signOutAndTokens() {
    // refreshed five minutes before the end
    fresh();
    fake.tokenSeconds = 600;
    Phone p1 = new Phone("mia@test", "a");
    signIn(p1, true);
    String t0 = p1.tok.load();
    run(200, p1);
    check(p1.calls("refresh") == 0 && t0.equals(p1.tok.load()), "no refresh while the token has over 5 minutes left");
    run(110, p1);
    check(p1.calls("refresh") == 1 && !t0.equals(p1.tok.load()), "the token is refreshed 5 minutes before it ends and saved at once");
    Res<Session> old = new Res<>(); p1.api.refresh(Wire.session(Json.parse(t0)).refresh, old); fake.deliver();
    check("auth".equals(old.code()), "the old refresh token is single use: " + old.code());
    // a dead access token: the api refreshes once on the 401, retries, and the store saves the new session
    fake.expireToken(p1.api);
    String t1 = p1.tok.load();
    p1.store.refresh(); fake.deliver(); fake.deliver();
    check(p1.store.state == CloudStore.IN && !t1.equals(p1.tok.load()) && p1.store.me != null, "a 401 refreshes once, retries, and the new session is saved");
    // a dead session signs out and says so
    p1.save("a1", mess("Keep me", 100)); run(6, p1);
    fake.killSession(p1.api);
    p1.store.refresh(); fake.deliver(); fake.deliver();
    check(p1.store.state == CloudStore.OUT && p1.tok.load() == null && p1.notices(CloudStore.N_SIGNED_OUT) == 1 && p1.host.docs.containsKey("a1"),
        "a dead session signs out with a notice and keeps the phone's messes");
    // sign out by hand
    signIn(p1, true);
    run(2, p1);
    check(p1.store.mark("a1") == CloudStore.M_SYNCED, "signed in again, the mess is linked as before");
    p1.store.signOut(); fake.deliver();
    check(p1.store.state == CloudStore.OUT && p1.tok.load() == null && p1.store.me == null && p1.store.rows.isEmpty(), "sign out clears the tokens, me and the list");
    check(p1.host.docs.containsKey("a1") && p1.store.mark("a1") == CloudStore.M_PHONE && p1.cache.load("index.json") != null, "and keeps the phone's messes and links");
    int before = fake.calls(p1.api).size();
    Project m = p1.project("a1"); m.bpm = 77; p1.save("a1", m); run(8, p1);
    check(fake.calls(p1.api).size() == before, "signed out, nothing is sent");
    p1.restart();
    check(p1.store.state == CloudStore.OUT, "a relaunch after sign out stays signed out");
    signIn(p1, true); run(8, p1);
    String cid = p1.store.cloudId("a1");
    check(p1.store.mark("a1") == CloudStore.M_SYNCED && canon(fake.messDoc(cid)).equals(m.toJson(false)), "the change made while signed out goes up after signing in");
  }

  static void soloFields() {
    fresh();
    Phone p1 = new Phone("mia@test", "a"), p2 = new Phone("mia@test", "b");
    signIn(p1, true); signIn(p2, true);
    Project m = mess("Solo", 100);
    m.mutes.add("samples/wet-clap.wav"); m.solos.add("samples/basement-kick.wav");
    m.songMode = true; m.cur = 2; m.song[0] = 0; m.song[1] = 2;
    p1.save("a1", m); run(6, p1, p2);
    Res<String> o = new Res<>(); p2.store.open(p1.store.cloudId("a1"), o); fake.deliver();
    Project q = p2.project(o.v);
    check(q.mutes.contains("samples/wet-clap.wav") && q.solos.contains("samples/basement-kick.wav") && q.songMode && q.cur == 2,
        "a mess with one member carries mutes, solos, song mode and the picked loop to phone 2 (Review B M4)");
    m.mutes.clear(); m.songMode = false; m.cur = 5; p1.save("a1", m); run(6, p1, p2);
    p2.store.refresh(); fake.deliver(); fake.deliver(); fake.deliver();
    q = p2.project(o.v);
    check(q.mutes.isEmpty() && !q.songMode && q.cur == 5, "a change to only those fields still syncs");
  }

  static void updateAndBlocked() {
    fresh();
    Phone p1 = new Phone("mia@test", "a"), p2 = new Phone("mia@test", "b");
    signIn(p1, true); signIn(p2, true);
    p1.save("a1", mess("Newer app", 100)); run(6, p1, p2);
    String cid = p1.store.cloudId("a1");
    fake.fakeOps(cid);
    Res<String> o = new Res<>(); p2.store.open(cid, o); fake.deliver();
    check(o.v == null && p2.notices(CloudStore.N_UPDATE) == 1 && p2.host.texts.contains("Update to open this mess") && p2.host.docs.isEmpty(),
        "a mess that has ops answers 'Update to open this mess' and is left alone");
    Project m = p1.project("a1"); m.bpm = 120; p1.save("a1", m); run(8, p1);
    check(fake.messRev(cid) == 1 && fake.messCount("mia@test") == 2 && p1.notices(CloudStore.N_UPDATE) == 1,
        "an edit to it from a Slice A phone goes up as its own mess, never over the ops");
    // blocked
    fresh();
    p1 = new Phone("mia@test", "a");
    signIn(p1, true);
    p1.save("a1", mess("Blocked", 100)); run(6, p1);
    fake.blockMess(p1.store.cloudId("a1"), true);
    m = p1.project("a1"); m.bpm = 99; p1.save("a1", m); run(6, p1);
    check(p1.store.mark("a1") == CloudStore.M_BLOCKED, "a blocked mess reads blocked");
  }

  /* ---------- HttpCloud against a scripted http ---------- */

  static class Scripted extends HttpCloud {
    final List<HttpReq> reqs = new ArrayList<>();
    final List<String> fileArgs = new ArrayList<>();
    final ArrayDeque<HttpRes> answers = new ArrayDeque<>();
    final List<Runnable> queue = new ArrayList<>();
    long now = 0;
    Scripted() { super("https://abc.supabase.co/", " pk_test "); }
    void answer(int status, String body) { answers.add(new HttpRes(status, body)); }
    protected void http(HttpReq r, Done<HttpRes> cb) { reqs.add(r); HttpRes a = answers.isEmpty() ? new HttpRes(0, "") : answers.poll(); queue.add(() -> cb.done(a, null)); }
    protected void sendFile(HttpReq r, String localFile, long bytes, Done<HttpRes> cb) { fileArgs.add(localFile + ":" + bytes); http(r, cb); }
    protected long nowMs() { return now; }
    void pump() { while (!queue.isEmpty()) queue.remove(0).run(); }
    HttpReq last() { return reqs.get(reqs.size() - 1); }
    public String newId() { return "id"; }
    public void prepareUpload(String key, Done<Prepared> cb) {}
    public void cachedSound(String sha256, Done<String> cb) {}
    public void fetchSound(String url, String sha256, long bytes, Done<String> cb) {}
    public void dropSound(String sha256) {}
  }

  static final String SESSION = "{\"access_token\":\"A1\",\"token_type\":\"bearer\",\"expires_in\":3600,\"expires_at\":1800000000,\"refresh_token\":\"R1\",\"user\":{\"id\":\"u-1\",\"email\":\"mia@test\"}}";
  static final String SESSION2 = "{\"access_token\":\"A2\",\"token_type\":\"bearer\",\"expires_in\":3600,\"expires_at\":1800003600,\"refresh_token\":\"R2\",\"user\":{\"id\":\"u-1\",\"email\":\"mia@test\"}}";
  static final String U = "https://abc.supabase.co";

  static Map<String, String> h(String... kv) { Map<String, String> m = new LinkedHashMap<>(); for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]); return m; }
  static final Map<String, String> ANON = h("apikey", "pk_test", "Content-Type", "application/json");

  static boolean req(HttpReq r, String method, String url, Map<String, String> headers, String body) {
    boolean ok = r.method.equals(method) && r.url.equals(url) && r.headers.equals(headers) && Objects.equals(r.body, body);
    if (!ok) System.out.println("   got " + r.method + " " + r.url + " " + r.headers + " " + r.body);
    return ok;
  }

  static void httpCloud() {
    Scripted x = new Scripted();
    check(x.configured(), "HttpCloud is configured with a url and a key");
    List<Session> heard = new ArrayList<>();
    x.setSessionListener(s -> heard.add(s));
    // sign-in
    Res<Boolean> rc = new Res<>(); x.answer(200, "{}"); x.requestCode("mia@test", rc);
    check(rc.n == 0, "HttpCloud never answers inside the call");
    x.pump();
    check(rc.v == Boolean.TRUE && req(x.last(), "POST", U + "/auth/v1/otp", ANON, "{\"email\":\"mia@test\",\"create_user\":true}"), "ask for a code: request");
    Res<Boolean> rw = new Res<>(); x.answer(429, "{\"code\":429,\"error_code\":\"over_email_send_rate_limit\",\"msg\":\"wait\"}"); x.requestCode("mia@test", rw); x.pump();
    check("code_wait".equals(rw.code()), "ask for a code: 429 is code_wait");
    Res<Session> v1 = new Res<>(); x.answer(403, "{\"code\":403,\"error_code\":\"otp_expired\",\"msg\":\"Token has expired or is invalid\"}"); x.verifyCode("mia@test", "123456", v1); x.pump();
    check("code_wrong".equals(v1.code()) && req(x.last(), "POST", U + "/auth/v1/verify", ANON, "{\"type\":\"email\",\"email\":\"mia@test\",\"token\":\"123456\"}"), "check a code: request, and otp_expired is code_wrong");
    Res<Session> v2 = new Res<>(); x.answer(429, "{}"); x.verifyCode("mia@test", "123456", v2); x.pump();
    check("code_wait".equals(v2.code()), "check a code: 429 is code_wait");
    Res<Session> v3 = new Res<>(); x.answer(0, ""); x.verifyCode("mia@test", "123456", v3); x.pump();
    check("net".equals(v3.code()), "no answer is net");
    Res<Session> v4 = new Res<>(); x.answer(200, SESSION); x.verifyCode("mia@test", "123456", v4); x.pump();
    check(v4.v != null && "A1".equals(v4.v.access) && "R1".equals(v4.v.refresh) && v4.v.expiresAt == 1800000000L && "u-1".equals(v4.v.userId) && "mia@test".equals(v4.v.email),
        "check a code: SessionJson is read");
    // signed out: an RPC carries the key and no Authorization
    Res<Me> m0 = new Res<>(); x.answer(401, "{}"); x.me(m0); x.pump();
    check("auth".equals(m0.code()) && req(x.last(), "POST", U + "/rest/v1/rpc/me", ANON, "{}") && x.reqs.size() == 7, "signed out, a 401 is auth with no refresh, and the key never goes in Authorization");
    x.setSession(v4.v);
    Map<String, String> SIGNED = h("apikey", "pk_test", "Authorization", "Bearer A1", "Content-Type", "application/json");
    String doc = "{\"name\":\"Jam\",\"bpm\":112}";
    String sha = "ab".repeat(32);
    // every Slice A call: exact URL, headers and body
    Object[][] calls = {
      {"me", "{}", (Runnable) () -> x.me(new Res<>())},
      {"set_profile", "{\"p_display_name\":\"Mia\"}", (Runnable) () -> x.setProfile("Mia", new Res<>())},
      {"accept_terms", "{\"p_version\":\"2026-11\"}", (Runnable) () -> x.acceptTerms("2026-11", new Res<>())},
      {"create_mess", "{\"p_id\":\"m-1\",\"p_name\":\"Jam\",\"p_doc\":" + doc + "}", (Runnable) () -> x.createMess("m-1", "Jam", doc, new Res<>())},
      {"my_messes", "{}", (Runnable) () -> x.myMesses(new Res<>())},
      {"open_mess", "{\"p_mess\":\"m-1\"}", (Runnable) () -> x.openMess("m-1", new Res<>())},
      {"put_doc", "{\"p_mess\":\"m-1\",\"p_doc\":" + doc + ",\"p_base_rev\":3,\"p_label\":\"Saved\"}", (Runnable) () -> x.putDoc("m-1", doc, 3, "Saved", new Res<>())},
      {"rename_mess", "{\"p_mess\":\"m-1\",\"p_name\":\"New \\\"one\\\"\"}", (Runnable) () -> x.renameMess("m-1", "New \"one\"", new Res<>())},
      {"delete_mess", "{\"p_mess\":\"m-1\"}", (Runnable) () -> x.deleteMess("m-1", new Res<>())},
      {"register_sound", "{\"p_sha256\":\"" + sha + "\",\"p_bytes\":44144,\"p_seconds\":0.5,\"p_rate\":44100,\"p_kind\":\"mic\",\"p_local_key\":\"bn:t1\",\"p_title\":\"Take 1\",\"p_bpm\":96,\"p_beats\":4,\"p_mkey\":\"A minor\"}",
        (Runnable) () -> x.registerSound(new Prepared("/f/t1.wav", sha, 44144, 0.5, 44100), "mic", "bn:t1", "Take 1", 96, 4, "A minor", new Res<>())},
      {"finish_upload", "{\"p_sha256\":\"" + sha + "\"}", (Runnable) () -> x.finishUpload(sha, new Res<>())},
      {"my_sounds", "{}", (Runnable) () -> x.mySounds(new Res<>())},
      {"attach_sound", "{\"p_mess\":\"m-1\",\"p_key\":\"bn:t1\",\"p_owner\":\"u-1\",\"p_sha256\":\"" + sha + "\"}", (Runnable) () -> x.attachSound("m-1", "bn:t1", "u-1", sha, new Res<>())},
      {"delete_sound", "{\"p_sha256\":\"" + sha + "\"}", (Runnable) () -> x.deleteSound(sha, new Res<>())},
    };
    boolean all = true;
    for (Object[] c : calls) {
      x.answer(200, "{}");
      ((Runnable) c[2]).run(); x.pump();
      all &= req(x.last(), "POST", U + "/rest/v1/rpc/" + c[0], SIGNED, (String) c[1]);
    }
    check(all, "every Slice A RPC: POST /rest/v1/rpc/<name>, apikey plus bearer, the named arguments as a JSON object");
    Res<List<SoundUrl>> su = new Res<>();
    x.answer(200, "{\"sounds\":[{\"key\":\"bn:t1\",\"sha256\":\"" + sha + "\",\"bytes\":44144,\"url\":\"https://abc.supabase.co/storage/v1/object/sign/sounds/u-1/x.wav?token=t\"}],\"expires_in\":600}");
    x.soundUrls("m-1", "", su); x.pump();
    check(req(x.last(), "POST", U + "/functions/v1/sound-urls", SIGNED, "{\"mess\":\"m-1\"}") && su.v.size() == 1 && su.v.get(0).bytes == 44144 && su.v.get(0).url.endsWith("token=t"),
        "sound-urls: POST /functions/v1/sound-urls with {\"mess\"}, answer read");
    Res<Boolean> up = new Res<>(); x.answer(200, "{\"Key\":\"sounds/u-1/x.wav\"}"); x.upload("u-1/" + sha + ".wav", "/f/t1.wav", 44144, up); x.pump();
    check(up.v == Boolean.TRUE && req(x.last(), "POST", U + "/storage/v1/object/sounds/u-1/" + sha + ".wav",
        h("apikey", "pk_test", "Authorization", "Bearer A1", "Content-Type", "audio/wav", "x-upsert", "false"), null) && x.fileArgs.get(0).equals("/f/t1.wav:44144"),
        "upload: POST /storage/v1/object/sounds/<path>, audio/wav, x-upsert false, the file with its fixed length");
    Res<Boolean> dup = new Res<>(); x.answer(400, "{\"statusCode\":\"409\",\"error\":\"Duplicate\",\"message\":\"The resource already exists\"}"); x.upload("u-1/a.wav", "/f/a.wav", 10, dup); x.pump();
    check(dup.v == Boolean.TRUE, "upload: 'already exists' counts as done");
    Res<Boolean> un = new Res<>(); x.answer(0, ""); x.upload("u-1/a.wav", "/f/a.wav", 10, un); x.pump();
    check("net".equals(un.code()), "upload: no answer is net");
    Res<Boolean> ro = new Res<>(); x.answer(404, "{}"); x.removeObject("u-1/a.wav", ro); x.pump();
    check(ro.v == Boolean.TRUE && req(x.last(), "DELETE", U + "/storage/v1/object/sounds/u-1/a.wav", h("apikey", "pk_test", "Authorization", "Bearer A1"), null), "remove: DELETE with the bearer, 404 counts as gone");
    // answers parsed
    Res<List<MessRow>> mm = new Res<>();
    x.answer(200, "[{\"id\":\"m-1\",\"name\":\"Jam\",\"role\":\"owner\",\"owner_name\":\"Mia\",\"rev\":4,\"updated_at\":\"2026-11-24T10:00:00Z\",\"members\":1,\"share_mode\":\"off\",\"doc_bytes\":120,\"blocked\":false,\"extra\":1}]");
    x.myMesses(mm); x.pump();
    check(mm.v.size() == 1 && mm.v.get(0).rev == 4 && "Mia".equals(mm.v.get(0).owner_name) && mm.v.get(0).doc_bytes == 120, "my_messes: rows read, unknown fields ignored");
    Res<Opened> op = new Res<>();
    x.answer(200, "{\"id\":\"m-1\",\"name\":\"Jam\",\"role\":\"owner\",\"owner_id\":\"u-1\",\"rev\":4,\"doc_rev\":4,\"doc\":{\"name\":\"Jam\",\"bpm\":112},\"ops_v\":1,\"ops\":[],"
        + "\"sounds\":[{\"key\":\"bn:t1\",\"sha256\":\"" + sha + "\",\"src\":\"gone\",\"owner\":null,\"fmt\":\"wav\",\"bytes\":44144,\"seconds\":0.5,\"rate\":44100,\"kind\":\"mic\",\"title\":\"T\",\"bpm\":96,\"beats\":0,\"mkey\":\"\",\"state\":\"ready\"}],"
        + "\"members\":[{\"id\":\"u-1\",\"name\":\"Mia\",\"colour\":3,\"role\":\"owner\"}],\"share_mode\":\"off\",\"share_token\":null,\"topic\":null}");
    x.openMess("m-1", op); x.pump();
    check(op.v != null && "{\"name\":\"Jam\",\"bpm\":112}".equals(op.v.doc) && op.v.sounds.get(0).owner.isEmpty() && "gone".equals(op.v.sounds.get(0).src)
        && op.v.members.get(0).colour == 3 && op.v.share_token.isEmpty() && op.v.topic.isEmpty(), "open_mess: the doc as JSON text, nulls as empty");
    // the error envelopes
    Res<Saved> e1 = new Res<>(); x.answer(400, "{\"code\":\"P0001\",\"message\":\"conflict\",\"details\":null,\"hint\":null}"); x.putDoc("m-1", doc, 1, "", e1); x.pump();
    check("conflict".equals(e1.code()) && e1.e.http == 400, "RPC: 400 with code P0001 gives the message as the code");
    Res<Saved> e2 = new Res<>(); x.answer(400, "{\"code\":\"22P02\",\"message\":\"invalid input syntax for type uuid\"}"); x.putDoc("m-1", doc, 1, "", e2); x.pump();
    check("http".equals(e2.code()), "RPC: any other code is http");
    Res<Me> e3 = new Res<>(); x.answer(403, "{\"code\":\"42501\",\"message\":\"permission denied\"}"); x.me(e3); x.pump();
    Res<Me> e4 = new Res<>(); x.answer(400, "{\"code\":\"42501\",\"message\":\"permission denied\"}"); x.me(e4); x.pump();
    check("auth".equals(e3.code()) && "auth".equals(e4.code()), "RPC: 403 and code 42501 are auth");
    Res<Me> e5 = new Res<>(); x.answer(0, ""); x.me(e5); x.pump();
    Res<Me> e6 = new Res<>(); x.answer(500, "oops"); x.me(e6); x.pump();
    Res<Me> e7 = new Res<>(); x.answer(200, "not json"); x.me(e7); x.pump();
    check("net".equals(e5.code()) && "http".equals(e6.code()) && e6.e.http == 500 && "http".equals(e7.code()), "RPC: no answer is net, a 500 or a bad body is http");
    Res<List<SoundUrl>> e8 = new Res<>(); x.answer(400, "{\"error\":\"not_found\"}"); x.soundUrls("m-9", "", e8); x.pump();
    Res<List<SoundUrl>> e9 = new Res<>(); x.answer(502, "Bad gateway"); x.soundUrls("m-9", "", e9); x.pump();
    check("not_found".equals(e8.code()) && "http".equals(e9.code()), "Edge Function: {\"error\"} gives the code, anything else is http");
    // 401: refresh once, retry with the new token, tell the listener
    int n0 = x.reqs.size();
    Res<Me> r1 = new Res<>(); x.answer(401, "{\"message\":\"JWT expired\"}"); x.answer(200, SESSION2); x.answer(200, "{\"id\":\"u-1\",\"display_name\":\"Mia\",\"name_set\":true,\"terms_ok\":true}");
    x.me(r1); x.pump();
    check(x.reqs.size() == n0 + 3 && req(x.reqs.get(n0 + 1), "POST", U + "/auth/v1/token?grant_type=refresh_token", ANON, "{\"refresh_token\":\"R1\"}")
        && req(x.reqs.get(n0 + 2), "POST", U + "/rest/v1/rpc/me", h("apikey", "pk_test", "Authorization", "Bearer A2", "Content-Type", "application/json"), "{}"),
        "a 401 refreshes once and sends the call again with the new token");
    check(r1.v != null && r1.v.name_set && heard.size() == 1 && "A2".equals(heard.get(0).access), "the call answers and the listener gets the new session");
    int n1 = x.reqs.size();
    Res<Me> r2 = new Res<>(); x.answer(401, "{}"); x.answer(200, SESSION.replace("A1", "A3").replace("R1", "R3")); x.answer(401, "{}"); x.me(r2); x.pump();
    check("auth".equals(r2.code()) && x.reqs.size() == n1 + 3, "a second 401 after the refresh is auth, with no second refresh");
    Res<Me> r3 = new Res<>(); x.answer(401, "{}"); x.answer(400, "{\"code\":400,\"error_code\":\"refresh_token_not_found\"}"); x.me(r3); x.pump();
    check("auth".equals(r3.code()) && heard.get(heard.size() - 1) == null, "a refresh refused with 400 is auth, and the listener hears the session is dead");
    Res<Me> r4 = new Res<>(); x.answer(200, "{}"); x.me(r4); x.pump();
    check(req(x.last(), "POST", U + "/rest/v1/rpc/me", ANON, "{}"), "after that calls carry no token");
    // two calls at once on a dead token share one refresh
    x.setSession(Wire.session(Json.parse(SESSION)));
    heard.clear();
    int n2 = x.reqs.size();
    x.answer(401, "{}"); x.answer(401, "{}");
    Res<Me> c1 = new Res<>(), c2 = new Res<>();
    x.me(c1); x.me(c2);
    x.answer(200, SESSION2); x.answer(200, "{}"); x.answer(200, "{}");
    x.pump();
    int refreshes = 0; for (int i = n2; i < x.reqs.size(); i++) if (x.reqs.get(i).url.contains("grant_type=refresh_token")) refreshes++;
    check(refreshes == 1 && c1.v != null && c2.v != null && heard.size() == 1, "two calls that both meet a 401 share one refresh");
    // refreshed before the end when the platform gives the time
    x.setSession(Wire.session(Json.parse(SESSION)));
    x.now = 1800000000L * 1000L - 10_000;
    int n3 = x.reqs.size();
    Res<Me> e10 = new Res<>(); x.answer(200, SESSION2); x.answer(200, "{}"); x.me(e10); x.pump();
    check(x.reqs.get(n3).url.contains("grant_type=refresh_token") && x.reqs.get(n3 + 1).headers.get("Authorization").equals("Bearer A2") && e10.v != null,
        "with the time known, a call in the token's last 30 s refreshes first");
    x.now = 0;
    // the refresh call itself
    Res<Session> rf = new Res<>(); x.answer(0, ""); x.refresh("R9", rf); x.pump();
    Res<Session> rf2 = new Res<>(); x.answer(401, "{}"); x.refresh("R9", rf2); x.pump();
    check("net".equals(rf.code()) && "auth".equals(rf2.code()), "refresh: no answer is net (keep the session), 401 is auth");
    // sign out
    x.setSession(Wire.session(Json.parse(SESSION)));
    Res<Boolean> so = new Res<>(); x.answer(500, "x"); x.signOut(so); x.pump();
    check(so.v == Boolean.TRUE && req(x.last(), "POST", U + "/auth/v1/logout?scope=local", h("apikey", "pk_test", "Authorization", "Bearer A1"), null),
        "sign out: POST /auth/v1/logout?scope=local with the bearer and no body, true whatever the answer");
    // later slices: built here, unbuilt nowhere
    Res<Since> sn = new Res<>(); x.answer(200, "{\"rev\":5,\"doc_rev\":4,\"members\":2,\"topic\":\"mess:m-1:0123456789abcdef\",\"ops\":[{\"rev\":5,\"user\":null,\"batch\":\"b-1\",\"v\":1,\"ops\":null,\"at\":\"t\"}]}");
    x.opsSince("m-1", 4, sn); x.pump();
    check(sn.v != null && sn.v.ops.get(0).ops == null && sn.v.ops.get(0).user.isEmpty() && req(x.last(), "POST", U + "/rest/v1/rpc/ops_since", SIGNED, "{\"p_mess\":\"m-1\",\"p_from\":4}"),
        "Slice B calls are built in HttpCloud too (ops_since, a batch with ops null)");
    Res<Boolean> rp = new Res<>(); x.answer(200, "{\"id\":\"r-1\"}"); x.reportMess("", "tok", "spam", "", "", rp); x.pump();
    check(req(x.last(), "POST", U + "/rest/v1/rpc/report_mess", SIGNED, "{\"p_mess\":null,\"p_token\":\"tok\",\"p_reason\":\"spam\",\"p_details\":\"\",\"p_user\":null}"),
        "an absent uuid goes as null");
    Res<Boolean> da = new Res<>(); x.answer(400, "{\"error\":\"stale_login\"}"); x.deleteAccount(da); x.pump();
    check("stale_login".equals(da.code()) && x.last().url.equals(U + "/functions/v1/delete-account"), "delete-account: the Edge Function's error code");
    // a FakeCloud phone answers unbuilt for Slice B (FakeCloudB adds it)
    fresh();
    CloudApi f = fake.client("x@test");
    Res<Invite> inv = new Res<>(); f.makeInvite("m", 7, inv);
    check("unbuilt".equals(inv.code()), "a slice not built answers unbuilt");
  }
}
