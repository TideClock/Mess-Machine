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

    public static final class Sound {
        public final String key, title, cab, file, group, album;
        public final double a, d;
        Sound(String key, String title, String cab, String file, double a, double d, String group, String album) {
            this.key = key; this.title = title; this.cab = cab; this.file = file; this.a = a; this.d = d; this.group = group; this.album = album;
        }
        public boolean sliced() { return d > 0; }
    }

    public final List<Cab> cabs = new ArrayList<>();
    public final List<Sound> sounds = new ArrayList<>();
    private final Map<String, Sound> byKey = new LinkedHashMap<>();
    private final Map<String, Cab> cabById = new LinkedHashMap<>();
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
        for (Object o : Json.list(m.get("sounds"))) {
            Map<String, Object> s = Json.map(o);
            Sound snd = new Sound(Json.str(s.get("k"), ""), Json.str(s.get("t"), ""), Json.str(s.get("c"), ""), Json.str(s.get("f"), ""),
                    Json.num(s.get("a"), 0), Json.num(s.get("d"), 0), Json.str(s.get("g"), ""), Json.str(s.get("al"), ""));
            sounds.add(snd);
            byKey.put(snd.key, snd);
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

    /** Already decoded, or null. Safe on the audio thread. */
    @Override public Sample get(String key) { return cache.get(key); }

    public boolean loaded(String key) { return cache.containsKey(key); }

    /** Decode a sound (blocking, call off the main thread). */
    public Sample load(String key) throws IOException {
        Sample s = cache.get(key);
        if (s != null) return s;
        Sound snd = byKey.get(key);
        if (snd == null) return null;
        if (snd.sliced()) {
            Sample sprite = sprite(snd.file);
            s = Sample.slice(key, sprite, snd.a, snd.d);
        } else {
            try (InputStream in = opener.open(base + snd.file)) { s = Wav.read(key, in); }
        }
        cache.put(key, s);
        return s;
    }

    private Sample sprite(String file) throws IOException {
        synchronized (sprites) {
            SoftReference<Sample> r = sprites.get(file);
            Sample s = r != null ? r.get() : null;
            if (s != null) return s;
            try (InputStream in = opener.open(base + file)) { s = Wav.read(file, in); }
            sprites.put(file, new SoftReference<>(s));
            return s;
        }
    }

    /** Drop decoded sounds that are not in use, to keep memory down. */
    public void trim(java.util.Set<String> keep) {
        for (String k : new ArrayList<>(cache.keySet())) if (!keep.contains(k)) cache.remove(k);
    }
}
