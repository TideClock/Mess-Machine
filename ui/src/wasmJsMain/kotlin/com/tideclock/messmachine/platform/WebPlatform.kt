package com.tideclock.messmachine.platform

import com.tideclock.messmachine.Bench
import com.tideclock.messmachine.NoteBook
import com.tideclock.messmachine.Packs
import com.tideclock.messmachine.cloud.CloudCache
import com.tideclock.messmachine.cloud.TokenStore
import com.tideclock.messmachine.engine.Engine
import com.tideclock.messmachine.engine.Library
import com.tideclock.messmachine.engine.MouthTracker
import com.tideclock.messmachine.engine.Sample

/*
 * The browser as a Platform (docs/WEB-CMP.md): the same AppState and screens as Android and the iPhone. Files live in
 * the page (IndexedDB behind it), settings in localStorage, the sound in an AudioWorklet fed from here.
 * Not on the web yet: speech to text, the updater (a page is always the newest), the mouth mic and packs (none sent yet).
 */
class WebPlatform : Platform {
    override val device: Device = WebDevice
    override val settings: Settings = WebSettings()
    override val main: MainThread = WebMain
    override val worker: Worker = WebMain
    override val files: FileStore = WebFiles
    override val filesDir = "/files"
    override val cacheDir = "/cache"
    override fun audioOut(engine: Engine, rate: Int, burst: Int): AudioOut = WebAudioOut(engine, rate, burst)
    override fun bench(lib: Library): Bench = WebBench(lib)
    override fun notes(): NoteBook = WebNotes()
    override fun packs(lib: Library): Packs = WebPacks()
    override fun speech(heard: (String) -> Unit, done: (text: String, problem: String?) -> Unit): SpeechIn = object : SpeechIn {
        override fun available() = false
        override fun start() = false
        override fun stop() {}
        override fun cancel() {}
    }
    override fun updater(): AppUpdater = object : AppUpdater {
        override val latest get() = current
        override val status = ""
        override val busy = false
        override var later = false
        override val current get() = jsBuild()
        override val ready = false
        override fun found(build: Int, url: String, size: Long) {}
        override fun check(quiet: Boolean, done: (String) -> Unit) { done("") }
        override fun allowed() = false
        override fun download(before: () -> Unit) {}
    }
    override fun mouthIn(): MouthIn = object : MouthIn {
        override val tracker = MouthTracker(48000)
        override val running = false
        override fun start() = false
        override fun stop() {}
    }
}

/** Settings in localStorage, each value as text under "mess.<key>". */
class WebSettings(private val prefix: String = "mess.") : Settings {
    private fun k(key: String) = prefix + key
    private fun raw(key: String): String? = lsGet(k(key))
    override fun getBoolean(key: String, def: Boolean) = raw(key)?.let { it == "true" } ?: def
    override fun getInt(key: String, def: Int) = raw(key)?.toIntOrNull() ?: def
    override fun getFloat(key: String, def: Float) = raw(key)?.toFloatOrNull() ?: def
    override fun getString(key: String, def: String?) = raw(key) ?: def
    override fun getStringSet(key: String, def: Set<String>?): Set<String>? =
        raw(key)?.let { s -> if (s.isEmpty()) emptySet() else s.split('\u0001').toSet() } ?: def
    override fun edit(): Settings.Editor = object : Settings.Editor {
        val ch = ArrayList<() -> Unit>()
        override fun putBoolean(key: String, v: Boolean) = also { ch += { lsSet(k(key), v.toString()) } }
        override fun putInt(key: String, v: Int) = also { ch += { lsSet(k(key), v.toString()) } }
        override fun putFloat(key: String, v: Float) = also { ch += { lsSet(k(key), v.toString()) } }
        override fun putString(key: String, v: String?) = also { ch += { if (v == null) lsDel(k(key)) else lsSet(k(key), v) } }
        override fun putStringSet(key: String, v: Set<String>?) = also { ch += { if (v == null) lsDel(k(key)) else lsSet(k(key), v.joinToString("\u0001")) } }
        override fun remove(key: String) = also { ch += { lsDel(k(key)) } }
        override fun apply() { commit() }
        override fun commit(): Boolean { ch.forEach { it() }; return true }
    }
}

/** One thread in a browser page: "post" is a timer, a "worker" runs on the next turn of the same thread. */
object WebMain : MainThread, Worker {
    private val pending = HashMap<() -> Unit, MutableList<Int>>()
    private fun later(f: () -> Unit, ms: Int) {
        var id = 0
        id = jsTimeout({ pending[f]?.let { it.remove(id); if (it.isEmpty()) pending.remove(f) }; f() }, ms)
        pending.getOrPut(f) { ArrayList() } += id
    }
    override fun post(f: () -> Unit) = later(f, 0)
    override fun postDelayed(f: () -> Unit, ms: Long) = later(f, ms.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
    override fun cancel(f: () -> Unit) { pending.remove(f)?.forEach { jsClearTimeout(it) } }
    override fun execute(f: () -> Unit) = later(f, 0)
}

/** Files by path, kept by the page (memory now, IndexedDB behind it so they last). */
object WebFiles : FileStore {
    override fun exists(path: String) = jsHas(path) || jsIsDir(path)
    override fun isFile(path: String) = jsHas(path)
    override fun isDirectory(path: String) = jsIsDir(path)
    override fun readBytes(path: String): ByteArray = Web.read(path) ?: throw IllegalStateException("$path is not there")
    override fun writeBytes(path: String, b: ByteArray) = Web.write(path, b)
    override fun delete(path: String) = jsDel(path)
    override fun rename(from: String, to: String) = jsRename(from, to)
    override fun list(path: String): List<String>? = jsList(path)?.let { if (it.isEmpty()) emptyList() else it.split('\n') }
    override fun mkdirs(path: String) = jsMkdirs(path)
    override fun lastModified(path: String) = jsMtime(path).toLong()
    override fun length(path: String) = (Web.read(path)?.size ?: 0).toLong()
}

object WebDevice : Device {
    override fun readAsset(path: String): ByteArray = Web.read("asset:$path") ?: throw IllegalStateException("no asset $path")
    override val outputRate: Int? = 48000
    override val outputBurst: Int? = 256
    override fun uptimeMillis() = jsNow().toLong()
    override fun nowMillis() = jsDate().toLong()
    override fun nanoTime() = (jsNow() * 1_000_000.0).toLong()
    override val isTestRun = false
    override fun backgroundPlay(on: Boolean) = false
    override fun shareFile(f: MFile, mime: String, title: String, subject: Boolean) = jsDownload(f.path, mime, f.name)
    override fun shareText(text: String, title: String) = jsShareText(text)
    override fun hasMicPermission() = jsMicOk()
    override fun screenDp(): Pair<Int, Int> = jsW() to jsH()
    override fun unmeteredNetwork() = true
    override fun clipboardText() = ""
    override fun readUri(uri: String): ByteArray? = Web.read(uri)
    override fun displayName(uri: String) = uri.substringAfterLast('/')
    override val buildCode get() = jsBuild()
    override val buildVersion get() = "web ${jsBuild()}"
    override val gitSha get() = jsSha()
    override val siteHost = ""
    override fun describe() = jsInfo() + ", saves in " + jsStore()
    override fun speakerTest(engine: Engine, rate: Int, click: Sample): Pair<Double, String> = 0.0 to "The web build has no speaker test yet."
    override fun writeZip(f: MFile, entries: List<Pair<String, ByteArray>>) = f.writeBytes(StoredZip.of(entries))
    override fun thread(name: String, f: () -> Unit) { jsTimeout(f, 0) }
    override fun lowWorker(name: String): Worker = WebMain
    override fun cloudTokens(): TokenStore = object : TokenStore {
        override fun load() = lsGet("mess.cloud.tokens")
        override fun save(json: String?) { if (json == null) lsDel("mess.cloud.tokens") else lsSet("mess.cloud.tokens", json) }
    }
    override fun cloudCache(): CloudCache = object : CloudCache {
        override fun load(name: String) = lsGet("mess.cloud.cache.$name")
        override fun save(name: String, json: String?) { if (json == null) lsDel("mess.cloud.cache.$name") else lsSet("mess.cloud.cache.$name", json) }
    }
}

/** A zip with every entry stored (no compression): enough for the stems export, with no zip library in the browser. */
object StoredZip {
    private val table = IntArray(256) { n -> var c = n; repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }; c }
    private fun crc(b: ByteArray): Int { var c = -1; for (x in b) c = table[(c xor x.toInt()) and 0xff] xor (c ushr 8); return c.inv() }
    fun of(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ArrayList<Byte>(); val central = ArrayList<Byte>()
        fun MutableList<Byte>.u16(v: Int) { add(v.toByte()); add((v shr 8).toByte()) }
        fun MutableList<Byte>.u32(v: Int) { u16(v and 0xffff); u16(v ushr 16) }
        for ((name, data) in entries) {
            val nb = name.encodeToByteArray(); val c = crc(data); val at = out.size
            out.u32(0x04034b50); out.u16(20); out.u16(0); out.u16(0); out.u16(0); out.u16(0x21)
            out.u32(c); out.u32(data.size); out.u32(data.size); out.u16(nb.size); out.u16(0); nb.forEach { out.add(it) }; data.forEach { out.add(it) }
            central.u32(0x02014b50); central.u16(20); central.u16(20); central.u16(0); central.u16(0); central.u16(0); central.u16(0x21)
            central.u32(c); central.u32(data.size); central.u32(data.size); central.u16(nb.size); central.u16(0); central.u16(0)
            central.u16(0); central.u16(0); central.u32(0); central.u32(at); nb.forEach { central.add(it) }
        }
        val cd = out.size; out.addAll(central)
        out.u32(0x06054b50); out.u16(0); out.u16(0); out.u16(entries.size); out.u16(entries.size); out.u32(central.size); out.u32(cd); out.u16(0)
        return out.toByteArray()
    }
}

/**
 * The sound out: a timer renders the engine a little ahead of the speaker and hands each block to the page's
 * AudioWorklet. The browser decides its own buffer; this keeps [aheadMs] more queued, which is what a tap waits for.
 * Tight (the default) starts at 30 ms and may grow to 180; Steady buffer starts at 60 and may grow to 300. A gap adds
 * 15 ms (at most one step a quarter second); 20 s without one gives 5 ms back, never below the start. Gaps in the
 * first 3 s are not counted (the page is still drawing its first screens).
 */
class WebAudioOut(private val engine: Engine, private val rate: Int, burst: Int) : AudioOut {
    private val block = 128
    private val buf = FloatArray(block * 2)
    private var timer = -1
    private var made = 0.0
    private val steady get() = growCap > 3
    private val startMs get() = (jsLead().takeIf { it > 0 } ?: 30) * (if (steady) 2 else 1)
    private val maxMs get() = if (steady) 300 else 180
    private var aheadMs = 30
    private var lastUnder = 0
    private var lastGapAt = 0.0
    private var lastGrowAt = 0.0
    private var startedAt = 0.0
    private var grownN = 0
    private var shrunkN = 0
    override var useAAudio = false
    override var growCap = 3
        set(v) { field = v; aheadMs = startMs }
    override val bufferFrames get() = aheadMs * rate / 1000
    override val underruns get() = jsUnderruns()
    override val outputMs get() = aheadMs + jsOutMs()
    override val fastPath = "AudioWorklet"
    override val cpuHint = ""
    override val grown get() = grownN
    override val shrunk get() = shrunkN
    override val growVoices = 0
    override var lastSlowFrameNanos = 0L
    override val path get() = jsAudioInfo()
    private val slow = ArrayList<Double>()
    override fun start() {
        if (timer >= 0) return
        jsAudioOpen(rate)
        made = 0.0
        aheadMs = maxOf(aheadMs, startMs)
        startedAt = jsNow(); lastGapAt = startedAt; lastUnder = jsUnderruns()
        timer = jsInterval({ pump() }, 5)
    }
    private fun pump() {
        val now = jsNow()
        val u = jsUnderruns()
        if (u > lastUnder) {
            if (now - startedAt > 3000 && now - lastGrowAt > 250 && aheadMs < maxMs) { aheadMs = minOf(maxMs, aheadMs + 15); grownN++; lastGrowAt = now }
            lastUnder = u; lastGapAt = now
        } else if (now - lastGapAt > 20_000 && aheadMs > startMs) { aheadMs = maxOf(startMs, aheadMs - 5); shrunkN++; lastGapAt = now }
        jsAheadNow(aheadMs)
        val heard = jsAudioFrames()
        if (made < heard) made = heard // fell behind (a hidden tab): skip ahead, do not catch up with old sound
        val want = heard + aheadMs * rate / 1000.0
        while (made < want) {
            val t0 = jsNow()
            engine.render(buf, block)
            jsBlockStart(block)
            for (i in 0 until block * 2) jsBlockSet(i, buf[i])
            jsBlockSend()
            made += block
            val ms = jsNow() - t0
            if (ms > block * 1000.0 / rate) { slow += ms; if (slow.size > 30) slow.removeAt(0) }
        }
    }
    override fun stop() { if (timer >= 0) { jsClearInterval(timer); timer = -1; jsAudioClose() } }
    override fun restart() { if (timer >= 0) { stop(); start() } }
    override fun clicksLastMinute() = 0
    override fun loadText() = "web"
    override fun load() = 0.0
    override fun ms(frames: Int) = frames * 1000.0 / rate
    override fun longBlocksText(max: Int) = slow.takeLast(max).joinToString(", ") { ((it * 10).toInt() / 10.0).toString() + " ms" }
}

/** Packs: the packs release has none yet, so the web build offers none (docs/WEB-CMP.md). */
class WebPacks : Packs {
    override val installed: MutableMap<String, Int> = HashMap()
    override fun loadInstalled() {}
    override fun fetchOffers(): List<Packs.Offer> = emptyList()
    override fun install(o: Packs.Offer) {}
    override fun installZip(zip: MFile, id: String) {}
    override fun remove(id: String) {}
}
