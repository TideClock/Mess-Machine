package com.tideclock.messmachine.engine;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every sound that ships with the app, read from sounds/library.json. Keys match the site
 * ("samples/basement-kick.wav", "qb:stwretch-hit") so a mess saved on the site loads here.
 * Sounds decode the first time they are needed; Qbldy ones are slices of bigger sprite files.
 */
public final class Library implements Engine.SampleSource {

    public interface Opener { InputStream open(String path) throws IOException; }

    public static final class Cab {
        public final String id, name;
        public final int color;
        Cab(String id, String name, int color) { this.id = id; this.name = name; this.color = color; }
    }

    /** Instrument types, in the order the picker shows them. */
    public static final String[][] TYPES = {
        {"kick", "Kicks"}, {"snare", "Snares + claps"}, {"hat", "Hats + cymbals"}, {"perc", "Percussion"},
        {"bass", "Bass"}, {"keys", "Keys + stabs"}, {"lead", "Leads"}, {"pad", "Pads + drones"},
        {"voice", "Voices + bodies"}, {"fx", "FX + noise"}, {"chop", "Chops + phrases"},
    };
    /** Qualities, worked out from the sound itself. */
    public static final String[][] QUALITIES = {
        {"punchy", "Punchy"}, {"long", "Long"}, {"dark", "Dark"}, {"bright", "Bright"}, {"tonal", "Tonal"}, {"gritty", "Gritty"}, {"busy", "Busy"},
    };

    public static final class Sound {
        public final String key, title, cab, file, group, album;
        public final double a, d;
        public String type = "";
        /** SE-4: a loop's tempo and length in beats (0 for one-shots). */
        public double bpm;
        public int beats;
        /** The sound's musical key when it could be measured for sure, like "A# minor" (KeyFinder); else empty. */
        public String mkey = "";
        public java.util.List<String> tags = new ArrayList<>();
        public Sound(String key, String title, String cab, String file, double a, double d, String group, String album) {
            this.key = key; this.title = title; this.cab = cab; this.file = file; this.a = a; this.d = d; this.group = group; this.album = album;
        }
        public boolean sliced() { return d > 0; }
    }

    public final List<Cab> cabs = new java.util.concurrent.CopyOnWriteArrayList<>();
    public final List<Sound> sounds = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** chop:<id> sounds play a region of another sound; this maps them to it. */
    private final Map<String, String> chopBase = new ConcurrentHashMap<>();
    private final Map<String, Sound> byKey = new ConcurrentHashMap<>();
    private final Map<String, Cab> cabById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Sample> cache = new ConcurrentHashMap<>();
    private final Map<String, SoftReference<Sample>> sprites = new LinkedHashMap<>();
    private final Opener opener;
    private final String base;

    public Library(Opener opener, String base) throws IOException {
        this.opener = opener;
        this.base = base;
        String text;
        try (InputStream in = opener.open(base + "library.json")) { text = new String(Wav.readAll(in), "UTF-8"); }
        Map<String, Object> m = Json.map(Json.parse(text));
        for (Object o : Json.list(m.get("cabs"))) {
            Map<String, Object> c = Json.map(o);
            Cab cab = new Cab(Json.str(c.get("id"), ""), Json.str(c.get("name"), ""), parseColor(Json.str(c.get("col"), "#888888")));
            cabs.add(cab);
            cabById.put(cab.id, cab);
        }
        Cab mine = new Cab("mine", "Your Workbench", 0xFFFFB547);
        cabs.add(mine);
        cabById.put(mine.id, mine);
        for (Object o : Json.list(m.get("sounds"))) {
            Map<String, Object> s = Json.map(o);
            Sound snd = new Sound(Json.str(s.get("k"), ""), Json.str(s.get("t"), ""), Json.str(s.get("c"), ""), Json.str(s.get("f"), ""),
                    Json.num(s.get("a"), 0), Json.num(s.get("d"), 0), Json.str(s.get("g"), ""), Json.str(s.get("al"), ""));
            snd.type = Json.str(s.get("ty"), "");
            for (Object q : Json.list(s.get("q"))) if (q instanceof String) snd.tags.add((String) q);
            snd.bpm = Json.num(s.get("bpm"), 0);
            snd.beats = (int) Json.num(s.get("beats"), 0);
            snd.mkey = Json.str(s.get("mkey"), "");
            sounds.add(snd);
            byKey.put(snd.key, snd);
        }
        // sounds that were replaced keep working in old messes: their keys play the sound that replaced them
        for (Map.Entry<String, Object> e : Json.map(m.get("aliases")).entrySet()) {
            Sound to = byKey.get(Json.str(e.getValue(), ""));
            if (to != null && !byKey.containsKey(e.getKey())) byKey.put(e.getKey(), to);
        }
    }

    public static int parseColor(String hex) {
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        return 0xFF000000 | (int) Long.parseLong(h, 16);
    }

    public boolean has(String key) { return byKey.containsKey(key); }
    public Sound sound(String key) { return byKey.get(key); }
    public Cab cab(String id) { Cab c = cabById.get(id); return c != null ? c : cabs.isEmpty() ? new Cab("", "", 0xFF888888) : cabs.get(0); }
    public int colorOf(String key) { Sound s = byKey.get(key); return s == null ? 0xFF3A3440 : cab(s.cab).color; }
    public String titleOf(String key) { Sound s = byKey.get(key); return s == null ? "" : s.title; }

    public List<Sound> inCab(String cab) {
        List<Sound> l = new ArrayList<>();
        for (Sound s : sounds) if (s.cab.equals(cab)) l.add(s);
        return l;
    }

    /** How many sounds are being read from storage right now, so the Audio check can tell a slow block was loading (MM-3). */
    private static final java.util.concurrent.atomic.AtomicInteger DECODING = new java.util.concurrent.atomic.AtomicInteger();
    public static int decoding() { return DECODING.get(); }

    /** Already decoded, or null. Safe on the audio thread. */
    @Override public Sample get(String key) { return cache.get(key); }

    public boolean loaded(String key) { return cache.containsKey(key); }

    /** Your own sounds (imports, mic takes, resamples, saved edits): file is a full path on the phone. */
    public void addUser(Sound s, String chopOf) {
        if (byKey.containsKey(s.key)) return;
        sounds.add(s);
        byKey.put(s.key, s);
        if (chopOf != null) chopBase.put(s.key, chopOf);
    }

    public void removeUser(String key) {
        Sound s = byKey.remove(key);
        if (s != null) sounds.remove(s);
        chopBase.remove(key);
        cache.remove(key);
    }

    public void putSample(String key, Sample s) { cache.put(key, s); }

    /** SE-17: a jar for an installed pack (its sounds come in through addUser). */
    public void addCab(String id, String name, int color) {
        if (cabById.containsKey(id)) return;
        Cab c = new Cab(id, name, color);
        cabs.add(c);
        cabById.put(id, c);
    }

    /** Take a pack's jar and every sound in it back out. */
    public void removeCab(String id) {
        for (Sound s : new ArrayList<>(sounds)) if (s.cab.equals(id)) removeUser(s.key);
        Cab c = cabById.remove(id);
        if (c != null) cabs.remove(c);
    }

    public String chopOf(String key) { return chopBase.get(key); }

    /** SE-11: a synth in the Synths jar. It "loads" as a short silence: the engine plays the patch, not the sample. */
    public void addSynth(String key, String title) {
        Sound old = byKey.get(key);
        if (old != null && old.title.equals(title)) return;
        if (old != null) sounds.remove(old);
        if (!cabById.containsKey("synths")) { Cab c = new Cab("synths", "Your Synths", 0xFF46D3C0); cabs.add(c); cabById.put(c.id, c); }
        Sound s = new Sound(key, title, "synths", "synth:", 0, 0, "", "");
        s.type = "keys";
        sounds.add(s);
        byKey.put(key, s);
        cache.put(key, SILENCE(key));
    }

    private static Sample SILENCE(String key) { return new Sample(key, new float[64], 44100); }

    /** Decode a sound (blocking, call off the main thread). */
    public Sample load(String key) throws IOException {
        Sample s = cache.get(key);
        if (s != null) return s;
        if (key.startsWith("synth:")) { s = SILENCE(key); cache.put(key, s); return s; }
        String parent = chopBase.get(key);
        if (parent != null) {
            Sample b = load(parent);
            if (b == null) return null;
            cache.put(key, b);
            return b;
        }
        Sound snd = byKey.get(key);
        if (snd == null) return null;
        DECODING.incrementAndGet();
        try {
        if (snd.file.startsWith("/")) {
            try (InputStream in = new java.io.FileInputStream(snd.file)) { s = decode(key, snd.file, in); }
        } else if (snd.sliced()) {
            Sample sprite = sprite(snd.file);
            s = Sample.slice(key, sprite, snd.a, snd.d);
        } else {
            try (InputStream in = opener.open(base + snd.file)) { s = decode(key, snd.file, in); }
        }
        } finally { DECODING.decrementAndGet(); }
        if (s != null && snd.bpm > 0) { s.bpm = snd.bpm; s.beats = snd.beats; }
        cache.put(key, s);
        return s;
    }

    /** The bundled sounds are FLAC (MM-8); your own sounds and older files are WAV. */
    private static Sample decode(String key, String file, InputStream in) throws IOException {
        return file.endsWith(".flac") ? Flac.read(key, in) : Wav.read(key, in);
    }

    private Sample sprite(String file) throws IOException {
        synchronized (sprites) {
            SoftReference<Sample> r = sprites.get(file);
            Sample s = r != null ? r.get() : null;
            if (s != null) return s;
            try (InputStream in = opener.open(base + file)) { s = decode(file, file, in); }
            sprites.put(file, new SoftReference<>(s));
            return s;
        }
    }

    /** Drop decoded sounds that are not in use, to keep memory down. */
    public void trim(java.util.Set<String> keep) {
        for (String k : new ArrayList<>(cache.keySet())) if (!keep.contains(k) && !k.startsWith("synth:")) cache.remove(k);
    }
}
