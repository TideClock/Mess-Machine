package com.tideclock.messmachine.engine

import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic

/**
 * Every sound that ships with the app, read from sounds/library.json. Keys match the site
 * ("samples/basement-kick.wav", "qb:stwretch-hit") so a mess saved on the site loads here.
 * Sounds decode the first time they are needed; Qbldy ones are slices of bigger sprite files.
 *
 * Files come through a SoundFiles (was Library.Opener, an InputStream opener): library.json and the bundled sounds
 * as base + path, your own sounds (a file starting with "/") by their full path.
 */
class Library @Throws(Exception::class) constructor(private val files: SoundFiles, private val base: String) : Engine.SampleSource {

    class Cab internal constructor(@JvmField val id: String, @JvmField val name: String, @JvmField val color: Int)

    class Sound(
        @JvmField val key: String, @JvmField val title: String, @JvmField val cab: String, @JvmField val file: String,
        @JvmField val a: Double, @JvmField val d: Double, @JvmField val group: String, @JvmField val album: String,
    ) {
        @JvmField var type: String = ""
        /** SE-4: a loop's tempo and length in beats (0 for one-shots). */
        @JvmField var bpm: Double = 0.0
        @JvmField var beats: Int = 0
        /** The sound's musical key when it could be measured for sure, like "A# minor" (KeyFinder); else empty. */
        @JvmField var mkey: String = ""
        @JvmField var tags: MutableList<String> = ArrayList()
        fun sliced(): Boolean { return d > 0 }
    }

    @JvmField val cabs: MutableList<Cab> = CowList()
    @JvmField val sounds: MutableList<Sound> = CowList()
    /** chop:<id> sounds play a region of another sound; this maps them to it. */
    private val chopBase = CowMap<String, String>()
    private val byKey = CowMap<String, Sound>()
    private val cabById = CowMap<String, Cab>()
    private val cache = CowMap<String, Sample>()
    // was a map of SoftReferences behind a lock: a plain map now (SoftReference is JVM only), so a decoded sprite
    // stays until the Library goes; read without locks, and two threads may rarely decode the same sprite
    private val sprites = CowMap<String, Sample>()

    init {
        val text = open(base + "library.json").decodeToString()
        val m = Json.map(Json.parse(text))
        for (o in Json.list(m["cabs"])) {
            val c = Json.map(o)
            val cab = Cab(Json.str(c["id"], ""), Json.str(c["name"], ""), parseColor(Json.str(c["col"], "#888888")))
            cabs.add(cab)
            cabById[cab.id] = cab
        }
        val mine = Cab("mine", "Your Workbench", 0xFFFFB547.toInt())
        cabs.add(mine)
        cabById[mine.id] = mine
        for (o in Json.list(m["sounds"])) {
            val s = Json.map(o)
            val snd = Sound(Json.str(s["k"], ""), Json.str(s["t"], ""), Json.str(s["c"], ""), Json.str(s["f"], ""),
                    Json.num(s["a"], 0.0), Json.num(s["d"], 0.0), Json.str(s["g"], ""), Json.str(s["al"], ""))
            snd.type = Json.str(s["ty"], "")
            for (q in Json.list(s["q"])) if (q is String) snd.tags.add(q)
            snd.bpm = Json.num(s["bpm"], 0.0)
            snd.beats = Json.num(s["beats"], 0.0).toInt()
            snd.mkey = Json.str(s["mkey"], "")
            sounds.add(snd)
            byKey[snd.key] = snd
        }
        // sounds that were replaced keep working in old messes: their keys play the sound that replaced them
        for (e in Json.map(m["aliases"]).entries) {
            val to = byKey[Json.str(e.value, "")]
            if (to != null && !byKey.containsKey(e.key)) byKey[e.key] = to
        }
    }

    /** A whole file through the SoundFiles; a missing one is an error, as the stream opener's was. */
    @Throws(Exception::class)
    private fun open(path: String): ByteArray {
        return files.read(path) ?: throw EngineIOException("Missing file: $path")
    }

    fun has(key: String): Boolean { return byKey.containsKey(key) }
    fun sound(key: String): Sound? { return byKey[key] }
    fun cab(id: String): Cab { val c = cabById[id]; return c ?: if (cabs.isEmpty()) Cab("", "", 0xFF888888.toInt()) else cabs[0] }
    fun colorOf(key: String): Int { val s = byKey[key]; return if (s == null) 0xFF3A3440.toInt() else cab(s.cab).color }
    /** Nov 23: a pad whose sound this phone does not have (a site or pack sound) keeps its key and reads "Missing". */
    fun titleOf(key: String?): String { val s = if (key == null) null else byKey[key]; return if (s != null) s.title else if (key == null || key.isEmpty() || key.startsWith("synth:")) "" else "Missing" }

    fun inCab(cab: String): List<Sound> {
        val l: MutableList<Sound> = ArrayList()
        for (s in sounds) if (s.cab == cab) l.add(s)
        return l
    }

    /** Already decoded, or null. Safe on the audio thread. */
    override fun get(key: String): Sample? { return cache[key] }

    fun loaded(key: String): Boolean { return cache.containsKey(key) }

    /** Your own sounds (imports, mic takes, resamples, saved edits): file is a full path on the phone. */
    fun addUser(s: Sound, chopOf: String?) {
        if (byKey.containsKey(s.key)) return
        sounds.add(s)
        byKey[s.key] = s
        if (chopOf != null) chopBase[s.key] = chopOf
    }

    fun removeUser(key: String) {
        val s = byKey.remove(key)
        if (s != null) sounds.remove(s)
        chopBase.remove(key)
        cache.remove(key)
    }

    fun putSample(key: String, s: Sample) { cache[key] = s }

    /** SE-17: a jar for an installed pack (its sounds come in through addUser). */
    fun addCab(id: String, name: String, color: Int) {
        if (cabById.containsKey(id)) return
        val c = Cab(id, name, color)
        cabs.add(c)
        cabById[id] = c
    }

    /** Take a pack's jar and every sound in it back out. */
    fun removeCab(id: String) {
        for (s in ArrayList(sounds)) if (s.cab == id) removeUser(s.key)
        val c = cabById.remove(id)
        if (c != null) cabs.remove(c)
    }

    fun chopOf(key: String): String? { return chopBase[key] }

    /** SE-11: a synth in the Synths jar. It "loads" as a short silence: the engine plays the patch, not the sample. */
    fun addSynth(key: String, title: String) {
        val old = byKey[key]
        if (old != null && old.title == title) return
        if (old != null) sounds.remove(old)
        if (!cabById.containsKey("synths")) { val c = Cab("synths", "Your Synths", 0xFF46D3C0.toInt()); cabs.add(c); cabById[c.id] = c }
        val s = Sound(key, title, "synths", "synth:", 0.0, 0.0, "", "")
        s.type = "keys"
        sounds.add(s)
        byKey[key] = s
        cache[key] = SILENCE(key)
    }

    /** Decode a sound (blocking, call off the main thread). */
    @Throws(Exception::class)
    fun load(key: String): Sample? {
        var s = cache[key]
        if (s != null) return s
        if (key.startsWith("synth:")) { s = SILENCE(key); cache[key] = s; return s }
        val parent = chopBase[key]
        if (parent != null) {
            val b = load(parent) ?: return null
            cache[key] = b
            return b
        }
        val snd = byKey[key] ?: return null
        val got: Sample
        DECODING.incrementAndGet()
        try {
        if (snd.file.startsWith("/")) {
            // a full path on the phone, through the same SoundFiles (was a FileInputStream)
            got = decode(key, snd.file, open(snd.file))
        } else if (snd.sliced()) {
            val sprite = sprite(snd.file)
            got = Sample.slice(key, sprite, snd.a, snd.d)
        } else {
            got = decode(key, snd.file, open(base + snd.file))
        }
        } finally { DECODING.decrementAndGet() }
        if (snd.bpm > 0) { got.bpm = snd.bpm; got.beats = snd.beats }
        cache[key] = got
        return got
    }

    @Throws(Exception::class)
    private fun sprite(file: String): Sample {
        val have = sprites[file]
        if (have != null) return have
        val s = decode(file, file, open(base + file))
        return sprites.putIfAbsent(file, s) ?: s
    }

    /** Drop decoded sounds that are not in use, to keep memory down. */
    fun trim(keep: Set<String>) {
        for (k in ArrayList(cache.keys)) if (!keep.contains(k) && !k.startsWith("synth:")) cache.remove(k)
    }

    companion object {
        /** Instrument types, in the order the picker shows them. */
        @JvmField val TYPES: Array<Array<String>> = arrayOf(
            arrayOf("kick", "Kicks"), arrayOf("snare", "Snares + claps"), arrayOf("hat", "Hats + cymbals"), arrayOf("perc", "Percussion"),
            arrayOf("bass", "Bass"), arrayOf("keys", "Keys + stabs"), arrayOf("lead", "Leads"), arrayOf("pad", "Pads + drones"),
            arrayOf("voice", "Voices + bodies"), arrayOf("fx", "FX + noise"), arrayOf("chop", "Chops + phrases"),
        )
        /** Qualities, worked out from the sound itself. */
        @JvmField val QUALITIES: Array<Array<String>> = arrayOf(
            arrayOf("punchy", "Punchy"), arrayOf("long", "Long"), arrayOf("dark", "Dark"), arrayOf("bright", "Bright"), arrayOf("tonal", "Tonal"), arrayOf("gritty", "Gritty"), arrayOf("busy", "Busy"),
        )

        @JvmStatic
        fun parseColor(hex: String): Int {
            val h = if (hex.startsWith("#")) hex.substring(1) else hex
            return 0xFF000000.toInt() or h.toLong(16).toInt()
        }

        /** How many sounds are being read from storage right now, so the Audio check can tell a slow block was loading (MM-3). */
        private val DECODING = AtomicCounter()
        @JvmStatic fun decoding(): Int { return DECODING.get() }

        private fun SILENCE(key: String): Sample { return Sample(key, FloatArray(64), 44100) }

        /** The bundled sounds are FLAC (MM-8); your own sounds and older files are WAV. */
        @Throws(Exception::class)
        private fun decode(key: String, file: String, b: ByteArray): Sample {
            return if (file.endsWith(".flac")) Flac.read(key, b) else Wav.read(key, b)
        }
    }
}
