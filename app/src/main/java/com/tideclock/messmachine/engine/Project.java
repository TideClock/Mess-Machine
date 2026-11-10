package com.tideclock.messmachine.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One mess: 64 pads, 8 loops, a song lane, tempo, swing, mutes and every sound's doctor
 * settings. Saves to the same JSON the site's Mess Machine saves (v3), so a mess can move
 * between the site and the phone.
 */
public final class Project {
    public static final int NPADS = 64, BANK = 16, NSLOTS = 8, MAXSTEPS = 32, MAXSONG = 64;
    public static final String LETTERS = "ABCDEFGH";
    public static final String[] DEFAULT_KIT = {"basement-kick", "wet-clap", "pot-lid-hat", "bw-snare", "tom-of-doom", "bubble-pop", "spring-doink", "tape-wobble-chord"};

    public static final class Note {
        public int s, p, l;
        public Note(int s, int p, int l) { this.s = s; this.p = p; this.l = l; }
        public Note copy() { return new Note(s, p, l); }
    }

    public static final class Pattern {
        public int len = 16;
        public final Map<String, boolean[]> steps = new LinkedHashMap<>();
        public final Map<String, List<Note>> notes = new LinkedHashMap<>();
        /** App only: recorded XY moves, "pad:target" to one value per step (NaN = nothing on that step). */
        public final Map<String, float[]> motion = new LinkedHashMap<>();

        public void setMotion(int pad, int target, int step, float v) {
            float[] a = motion.get(pad + ":" + target);
            if (a == null) { a = new float[MAXSTEPS]; Arrays.fill(a, Float.NaN); motion.put(pad + ":" + target, a); }
            if (step >= 0 && step < a.length) a[step] = v;
        }

        public boolean hasMotion() { return !motion.isEmpty(); }

        public boolean step(String k, int j) { boolean[] a = steps.get(k); return a != null && j >= 0 && j < a.length && a[j]; }

        public void setStep(String k, int j, boolean v) {
            boolean[] a = steps.get(k);
            if (a == null) { if (!v) return; a = new boolean[MAXSTEPS]; steps.put(k, a); }
            if (j >= 0 && j < a.length) a[j] = v;
        }

        public List<Note> notes(String k) {
            List<Note> n = notes.get(k);
            if (n == null) { n = new ArrayList<>(); notes.put(k, n); }
            return n;
        }

        public boolean hasNoteAt(String k, int s) {
            List<Note> n = notes.get(k);
            if (n != null) for (Note x : n) if (x.s == s) return true;
            return false;
        }

        public boolean used() {
            for (List<Note> n : notes.values()) if (!n.isEmpty()) return true;
            for (boolean[] a : steps.values()) for (int j = 0; j < len; j++) if (a[j]) return true;
            return false;
        }

        public boolean usedBy(String k) {
            List<Note> n = notes.get(k);
            if (n != null && !n.isEmpty()) return true;
            boolean[] a = steps.get(k);
            if (a != null) for (int j = 0; j < len; j++) if (a[j]) return true;
            return false;
        }

        public Pattern copy() {
            Pattern p = new Pattern();
            p.len = len;
            for (Map.Entry<String, boolean[]> e : steps.entrySet()) p.steps.put(e.getKey(), e.getValue().clone());
            for (Map.Entry<String, List<Note>> e : notes.entrySet()) {
                List<Note> l = new ArrayList<>();
                for (Note n : e.getValue()) l.add(n.copy());
                p.notes.put(e.getKey(), l);
            }
            for (Map.Entry<String, float[]> e : motion.entrySet()) p.motion.put(e.getKey(), e.getValue().clone());
            return p;
        }

        public void clear() { steps.clear(); notes.clear(); motion.clear(); }

        /** Pull a sound out of the loop entirely (when its pad is cleared). */
        public void forget(String k) { steps.remove(k); notes.remove(k); }
    }

    public String name = "";
    public final String[] kit = new String[NPADS];
    /** Per-pad settings (glide, routing, XY); null = the defaults. */
    public final PadOpts[] opts = new PadOpts[NPADS];

    public PadOpts opt(int pad) { return pad >= 0 && pad < NPADS && opts[pad] != null ? opts[pad] : PadOpts.DEFAULT; }
    public void setOpt(int pad, PadOpts o) { if (pad >= 0 && pad < NPADS) opts[pad] = o == null || o.isDefault() ? null : o.copy(); }
    public final Pattern[] pats = new Pattern[NSLOTS];
    public final int[] song = new int[MAXSONG];
    public int bpm = 112, swing = 0, sel = 7, root = 0, cur = 0;
    public boolean fold = false, songMode = false;
    public String scale = "Minor"; // new messes start in C minor (Tyler, 2026-11-10); files without a scale still load as Chromatic
    public final Set<String> mutes = new LinkedHashSet<>(), solos = new LinkedHashSet<>();
    public final Map<String, SoundEdit> edits = new LinkedHashMap<>();

    public Project() {
        Arrays.fill(kit, "");
        Arrays.fill(song, -1);
        for (int k = 0; k < NSLOTS; k++) pats[k] = new Pattern();
    }

    /** A fresh mess with the site's default eight pads. */
    public static Project starter() {
        Project p = new Project();
        for (int k = 0; k < DEFAULT_KIT.length; k++) p.kit[k] = "samples/" + DEFAULT_KIT[k] + ".wav";
        return p;
    }

    /** The first-launch mess: the default kit with a little beat on loop A so it makes noise right away. */
    public static Project demo() {
        Project p = starter();
        p.name = "First mess";
        p.bpm = 100;
        p.swing = 18;
        Pattern a = p.pats[0];
        int[] kick = {0, 6, 8, 11}, clap = {4, 12}, hat = {2, 6, 10, 14, 15};
        for (int j : kick) a.setStep(p.kit[0], j, true);
        for (int j : clap) a.setStep(p.kit[1], j, true);
        for (int j : hat) a.setStep(p.kit[2], j, true);
        a.notes(p.kit[7]).add(new Note(0, 0, 6));
        a.notes(p.kit[7]).add(new Note(8, -4, 6));
        p.edits.put(p.kit[7], editWith(e -> { e.mode = "gate"; e.rel = .08; e.vol = .7; }));
        p.sel = 0;
        return p;
    }

    private interface EditFn { void apply(SoundEdit e); }
    private static SoundEdit editWith(EditFn f) { SoundEdit e = new SoundEdit(); f.apply(e); return e; }

    public Pattern pat() { return pats[cur]; }

    public SoundEdit edit(String k) { SoundEdit e = edits.get(k); return e != null ? e : SoundEdit.DEFAULT; }

    public void setEdit(String k, SoundEdit e) { if (e == null || e.isDefault()) edits.remove(k); else edits.put(k, e.copy()); }

    public boolean audible(String k) { return solos.isEmpty() ? !mutes.contains(k) : solos.contains(k); }

    public int songLen() { int n = 0; for (int k = 0; k < MAXSONG; k++) if (song[k] >= 0) n = k + 1; return n; }

    public int padOf(String k) { for (int k2 = 0; k2 < NPADS; k2++) if (kit[k2].equals(k)) return k2; return -1; }

    public int loadedCount() { int n = 0; for (String k : kit) if (!k.isEmpty()) n++; return n; }

    /** Put a sound on a pad. A sound lives on one pad at a time, like the site. */
    public void assign(int pad, String key) {
        int old = padOf(key);
        if (old >= 0 && old != pad) kit[old] = "";
        String was = kit[pad];
        kit[pad] = key;
        if (!was.isEmpty() && !was.equals(key) && padOf(was) < 0) for (Pattern p : pats) p.forget(was);
        sel = pad;
    }

    public void clearPad(int pad) {
        String k = kit[pad];
        if (k.isEmpty()) return;
        kit[pad] = "";
        for (Pattern p : pats) p.forget(k);
        mutes.remove(k);
        solos.remove(k);
    }

    public Project copy() { return fromJson(toJson(), null).project; }

    /* ---------- the site's saved-mess format, v3 ---------- */

    public String toJson() { return toJson(true); }

    /** withTime = false leaves out the save time, for comparing two states (undo). */
    public String toJson(boolean withTime) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("name", (name == null || name.isEmpty() ? "Untitled mess" : name.length() > 48 ? name.substring(0, 48) : name));
        o.put("bpm", (double) bpm);
        o.put("v", 3.0);
        o.put("kit", new ArrayList<Object>(Arrays.asList((Object[]) kit)));
        o.put("sel", (double) sel);
        o.put("steps", encSteps(pats[0]));
        List<Object> pl = new ArrayList<>();
        for (Pattern p : pats) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("len", (double) p.len);
            x.put("steps", encSteps(p));
            Map<String, Object> nm = new LinkedHashMap<>();
            for (Map.Entry<String, List<Note>> e : p.notes.entrySet()) {
                if (e.getValue().isEmpty() || padOf(e.getKey()) < 0) continue;
                StringBuilder b = new StringBuilder();
                for (Note n : e.getValue()) { if (b.length() > 0) b.append(','); b.append(n.s).append('.').append(n.p).append('.').append(n.l); }
                nm.put(e.getKey(), b.toString());
            }
            x.put("notes", nm);
            if (!p.motion.isEmpty()) {
                Map<String, Object> mm = new LinkedHashMap<>();
                for (Map.Entry<String, float[]> e : p.motion.entrySet()) {
                    StringBuilder b = new StringBuilder();
                    float[] a = e.getValue();
                    for (int j = 0; j < p.len; j++) { if (j > 0) b.append(','); if (!Float.isNaN(a[j])) b.append(Math.round(a[j] * 1000) / 1000f); }
                    mm.put(e.getKey(), b.toString());
                }
                x.put("motion", mm);
            }
            pl.add(x);
        }
        o.put("pats", pl);
        StringBuilder sg = new StringBuilder();
        int sl = Math.max(16, songLen());
        for (int k = 0; k < sl; k++) sg.append(song[k] >= 0 ? LETTERS.charAt(song[k]) : '-');
        o.put("song", sg.toString());
        o.put("swing", (double) swing);
        o.put("mutes", new ArrayList<Object>(mutes));
        o.put("solos", new ArrayList<Object>(solos));
        o.put("scale", scale);
        o.put("root", (double) root);
        o.put("fold", fold);
        Map<String, Object> em = new LinkedHashMap<>();
        for (String k : kit) if (!k.isEmpty() && edits.containsKey(k)) em.put(k, edits.get(k).toMap());
        o.put("edits", em);
        Map<String, Object> po = new LinkedHashMap<>();
        for (int k = 0; k < NPADS; k++) if (opts[k] != null && !opts[k].isDefault()) po.put(String.valueOf(k), opts[k].toMap());
        if (!po.isEmpty()) o.put("padOpts", po);
        if (withTime) o.put("at", isoNow());
        o.put("app", "android");
        o.put("mode", songMode ? "song" : "loop");
        o.put("cur", (double) cur);
        return Json.write(o);
    }

    private Map<String, Object> encSteps(Pattern p) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : kit) {
            if (k.isEmpty()) continue;
            StringBuilder b = new StringBuilder();
            for (int j = 0; j < p.len; j++) b.append(p.step(k, j) ? '1' : '0');
            m.put(k, b.toString());
        }
        return m;
    }

    static String isoNow() {
        try { return java.time.Instant.now().toString(); } catch (Throwable t) { return ""; }
    }

    /** What loading a mess found. missing counts pads whose sound this app does not have. */
    public static final class Loaded {
        public final Project project;
        public final int missing;
        Loaded(Project p, int m) { project = p; missing = m; }
    }

    public interface Known { boolean has(String key); }

    public static Loaded fromJson(String text, Known known) {
        Map<String, Object> q = Json.map(Json.parse(text));
        Project p = new Project();
        p.name = Json.str(q.get("name"), "");
        for (Map.Entry<String, Object> e : Json.map(q.get("padOpts")).entrySet()) {
            try { int k = Integer.parseInt(e.getKey()); if (k >= 0 && k < NPADS) p.opts[k] = PadOpts.fromMap(Json.map(e.getValue())); } catch (NumberFormatException ignored) {}
        }
        List<Object> kl = Json.list(q.get("kit"));
        int missing = 0;
        for (int k = 0; k < kl.size() && k < NPADS; k++) {
            String key = Json.str(kl.get(k), "");
            if (key.isEmpty()) continue;
            if (known == null || known.has(key)) {
                if (p.padOf(key) < 0) p.kit[k] = key;
            } else missing++;
        }
        Map<String, Object> ed = Json.map(q.get("edits"));
        for (Map.Entry<String, Object> e : ed.entrySet()) {
            if (p.padOf(e.getKey()) < 0) continue;
            SoundEdit se = SoundEdit.fromMap(Json.map(e.getValue()));
            if (!se.isDefault()) p.edits.put(e.getKey(), se);
        }
        List<Object> pl = Json.list(q.get("pats"));
        if (!pl.isEmpty()) {
            for (int i = 0; i < NSLOTS && i < pl.size(); i++) {
                Map<String, Object> x = Json.map(pl.get(i));
                Pattern P = p.pats[i];
                P.len = Json.num(x.get("len"), 16) == 32 ? 32 : 16;
                decSteps(P, Json.map(x.get("steps")));
                for (Map.Entry<String, Object> e : Json.map(x.get("motion")).entrySet()) {
                    float[] a = new float[MAXSTEPS];
                    Arrays.fill(a, Float.NaN);
                    String[] f = Json.str(e.getValue(), "").split(",", -1);
                    for (int j = 0; j < f.length && j < a.length; j++) if (!f[j].isEmpty()) try { a[j] = Float.parseFloat(f[j]); } catch (NumberFormatException ignored) {}
                    P.motion.put(e.getKey(), a);
                }
                Object nv = x.get("notes");
                if (nv instanceof String) {
                    String inst = Json.str(x.get("inst"), "");
                    if (!inst.isEmpty()) decNotes(P, inst, (String) nv);
                } else for (Map.Entry<String, Object> e : Json.map(nv).entrySet()) decNotes(P, e.getKey(), Json.str(e.getValue(), ""));
            }
            String sg = Json.str(q.get("song"), "");
            for (int k = 0; k < sg.length() && k < MAXSONG; k++) p.song[k] = LETTERS.indexOf(sg.charAt(k));
        } else decSteps(p.pats[0], Json.map(q.get("steps")));
        // drop anything that points at a sound that is not on a pad
        for (Pattern P : p.pats) {
            P.steps.keySet().removeIf(k -> p.padOf(k) < 0);
            P.notes.keySet().removeIf(k -> p.padOf(k) < 0);
        }
        p.swing = (int) SoundEdit.clamp(Json.num(q.get("swing"), 0), 0, 100);
        for (Object m : Json.list(q.get("mutes"))) if (m instanceof String) p.mutes.add((String) m);
        for (Object m : Json.list(q.get("solos"))) if (m instanceof String) p.solos.add((String) m);
        String sc = Json.str(q.get("scale"), "Chromatic");
        p.scale = Theory.SCALES.containsKey(sc) ? sc : "Chromatic";
        p.root = (int) SoundEdit.clamp(Json.num(q.get("root"), 0), 0, 11);
        p.fold = Json.bool(q.get("fold"), false);
        p.bpm = (int) SoundEdit.clamp(Json.num(q.get("bpm"), 112), 60, 180);
        int sel = (int) Json.num(q.get("sel"), -1);
        if (sel < 0 || sel >= NPADS || p.kit[sel].isEmpty()) {
            sel = 0;
            for (int k = 0; k < NPADS; k++) if (!p.kit[k].isEmpty()) { sel = k; break; }
        }
        p.sel = sel;
        p.songMode = "song".equals(q.get("mode")) && p.songLen() > 0;
        p.cur = (int) SoundEdit.clamp(Json.num(q.get("cur"), 0), 0, NSLOTS - 1);
        return new Loaded(p, missing);
    }

    private static void decSteps(Pattern P, Map<String, Object> m) {
        for (Map.Entry<String, Object> e : m.entrySet()) {
            String v = Json.str(e.getValue(), "");
            boolean[] a = new boolean[MAXSTEPS];
            boolean any = false;
            for (int j = 0; j < Math.min(v.length(), MAXSTEPS); j++) if (v.charAt(j) == '1') { a[j] = true; any = true; }
            if (any) P.steps.put(e.getKey(), a);
        }
    }

    private static void decNotes(Pattern P, String k, String str) {
        if (str.isEmpty()) return;
        List<Note> l = P.notes(k);
        for (String t : str.split(",")) {
            String[] f = t.split("\\.");
            if (f.length < 3) continue;
            try {
                int s = Integer.parseInt(f[0].trim()), pp = Integer.parseInt(f[1].trim()), ll = Integer.parseInt(f[2].trim());
                if (s >= 0 && s < P.len) l.add(new Note(s, Math.max(Theory.ROLL_LO, Math.min(Theory.ROLL_HI, pp)), Math.max(1, Math.min(P.len - s, ll))));
            } catch (NumberFormatException ignored) {}
        }
    }
}
