package com.tideclock.messmachine.platform

import androidx.compose.runtime.mutableStateListOf
import com.tideclock.messmachine.Bench
import com.tideclock.messmachine.BenchItem
import com.tideclock.messmachine.engine.Json
import com.tideclock.messmachine.engine.KeyFinder
import com.tideclock.messmachine.engine.Library
import com.tideclock.messmachine.engine.Sample
import com.tideclock.messmachine.engine.Wav

/**
 * Your Workbench in a browser: the same jar as Android's Workbench (imports, mic takes, resampled loops, saved edits,
 * chops), kept as WAVs under /files/bench. The page decodes picked sound files into WAVs (the browser's own decoders)
 * and records the mic; the auto sample and trim rules are Android's, line for line.
 */
class WebBench(private val lib: Library) : Bench {
    private val dir = "/files/bench"
    private val index = "$dir/bench.json"
    override val items = mutableStateListOf<BenchItem>()

    init {
        WebFiles.mkdirs(dir)
        try {
            if (WebFiles.isFile(index)) for (o in Json.list(Json.parse(WebFiles.readBytes(index).decodeToString()))) {
                val m = Json.map(o)
                val b = BenchItem(
                    Json.str(m["id"], ""), Json.str(m["kind"], "imp"), Json.str(m["title"], "Sound"), Json.num(m["at"], 0.0).toLong(),
                    Json.str(m["base"], ""), Json.num(m["st"], 0.0), Json.num(m["en"], 1.0),
                    Json.num(m["bpm"], 0.0), Json.num(m["beats"], 0.0).toInt(), if (m.containsKey("mkey")) Json.str(m["mkey"], "") else null,
                )
                if (b.kind == "chop" || WebFiles.isFile(wav(b.id))) { items.add(b); register(b) }
            }
        } catch (_: Exception) {}
    }

    private fun wav(id: String) = "$dir/$id.wav"
    override fun keyOf(b: BenchItem) = if (b.kind == "chop") "chop:" + b.id else "bn:" + b.id
    override fun item(key: String): BenchItem? = items.firstOrNull { keyOf(it) == key }

    private fun group(kind: String) = when (kind) {
        "imp" -> "Imports"; "mic" -> "Mic takes"; "rs" -> "Resampled loops"; "take" -> "Saved edits"; else -> "Chops"
    }

    private fun register(b: BenchItem) {
        val snd = Library.Sound(keyOf(b), b.title, "mine", if (b.kind == "chop") "" else wav(b.id), 0.0, 0.0, group(b.kind), "")
        if (b.bpm > 0 && b.beats > 0) { snd.bpm = b.bpm; snd.beats = b.beats }
        snd.mkey = b.mkey ?: ""
        lib.addUser(snd, if (b.kind == "chop") b.base else null)
    }

    private fun save() {
        val list = items.map { b ->
            linkedMapOf<String, Any?>("id" to b.id, "kind" to b.kind, "title" to b.title, "at" to b.at.toDouble(), "base" to b.base, "st" to b.st, "en" to b.en).also {
                if (b.bpm > 0) { it["bpm"] = b.bpm; it["beats"] = b.beats.toDouble() }
                if (b.mkey != null) it["mkey"] = b.mkey
            }
        }
        try { WebFiles.writeBytes(index, Json.write(list).encodeToByteArray()) } catch (_: Exception) {}
    }

    private fun newId() = jsDate().toLong().toString(36) + (100..999).random()

    override fun addSample(kind: String, title: String, s: Sample, bpm: Double, beats: Int): String {
        val mk = try { KeyFinder.find(s.data, s.rate) } catch (_: Throwable) { "" }
        val b = BenchItem(newId(), kind, title.take(40), jsDate().toLong(), bpm = bpm, beats = beats, mkey = mk)
        WebFiles.writeBytes(wav(b.id), Wav.writeMono16(s.data, s.rate))
        items.add(b); register(b); save()
        lib.putSample(keyOf(b), Sample(keyOf(b), s.data, s.rate).also { if (bpm > 0 && beats > 0) { it.bpm = bpm; it.beats = beats } })
        return keyOf(b)
    }

    override fun adoptCloud(id: String, kind: String, title: String, from: MFile, bpm: Double, beats: Int, mkey: String): Boolean {
        if (id.isEmpty() || item("bn:$id") != null) return false
        WebFiles.writeBytes(wav(id), from.readBytes())
        val k = if (kind == "imp" || kind == "mic" || kind == "rs" || kind == "take") kind else "imp"
        val b = BenchItem(id, k, title.ifEmpty { "Sound" }.take(40), jsDate().toLong(), bpm = bpm, beats = beats, mkey = mkey)
        items.add(b); register(b); save()
        return true
    }

    override fun addChop(base: String, title: String, st: Double, en: Double): String {
        val b = BenchItem(newId(), "chop", title.take(40), jsDate().toLong(), base, st, en)
        items.add(b); register(b); save()
        return keyOf(b)
    }

    override fun remove(key: String) {
        val b = item(key) ?: return
        items.remove(b)
        if (b.kind != "chop") WebFiles.delete(wav(b.id))
        lib.removeUser(key)
        for (c in items.filter { it.kind == "chop" && it.base == key }) { items.remove(c); lib.removeUser(keyOf(c)) }
        save()
    }

    override fun count(kind: String) = items.count { it.kind == kind }

    /** The page already turned a picked sound file into a mono WAV (index.html, mm.pick). */
    override fun decode(uri: String): Sample {
        val b = Web.read(uri) ?: throw IllegalStateException("Could not open the file")
        if (b.size < 12) throw IllegalStateException("That file is empty")
        if (b.decodeToString(0, 4) != "RIFF" || b.decodeToString(8, 12) != "WAVE") throw IllegalStateException("the browser could not read it as sound")
        return Wav.read("imp", b)
    }

    /* ---------------- the mic ---------------- */

    private var recording = false
    private var timer = -1
    private var out = FloatArray(1 shl 16)
    private var n = 0
    private fun add(v: Float) { if (n == out.size) out = out.copyOf(out.size * 2); out[n++] = v }
    private fun keepLast(k: Int) { if (n > k) { out.copyInto(out, 0, n - k, n); n = k } }

    /**
     * Sample the mic (permission asked first by the screen). done gets the take when you stop or at 30 seconds.
     * auto: listen, keep only the last half second until a sound comes, then record it and stop after a third of a
     * second of quiet (Android's Workbench, the same numbers).
     */
    override fun startMic(trim: Boolean, auto: Boolean, done: (Sample?) -> Unit) {
        if (recording) return
        if (jsMicBlocked()) { withPhone(trim, done); return }
        recording = true
        n = 0
        jsMicStart { ok ->
            if (!ok) { recording = false; withPhone(trim, done); return@jsMicStart }
            val rate = jsMicRate()
            var floor = 1f; var started = false; var quietFor = 0; var heardFor = 0
            fun finish() {
                if (timer >= 0) { jsClearInterval(timer); timer = -1 }
                jsMicStop()
                recording = false
                val raw = Sample("mic", out.copyOf(n), rate)
                done(if (trim) trimSilence(raw) else raw.takeIf { it.data.isNotEmpty() })
            }
            timer = jsInterval({
                val got = jsMicPull()
                var e = 0.0
                for (i in 0 until got) { val v = jsF32(i); add(v); e += v * v.toDouble() }
                var stop = !recording || n >= rate * 30
                if (auto && got > 0 && !stop) {
                    val r = kotlin.math.sqrt(e / got).toFloat()
                    heardFor += got
                    if (!started) {
                        floor = minOf(floor, maxOf(r, .0005f))
                        if (heardFor > rate / 10 && r > maxOf(floor * 6f, .02f)) started = true
                        else if (n > rate / 2) keepLast(rate / 2)
                    } else {
                        quietFor = if (r < maxOf(floor * 2.5f, .008f)) quietFor + got else 0
                        if (quietFor > rate / 3) stop = true
                    }
                }
                if (stop) finish()
            }, 40)
        }
    }

    /**
     * Where the page may not use the mic (a claude.ai artifact, Hubbub's game frame): the phone's own recorder.
     * The take comes back like an import (the page decodes it), trimmed the same way. A recorder can only open inside
     * the tap; when the tap is too long ago, a notice asks for one more tap.
     */
    private fun withPhone(trim: Boolean, done: (Sample?) -> Unit) {
        recording = true
        val opened = jsRecordWithPhone { key ->
            recording = false
            if (key.isEmpty()) { done(null); return@jsRecordWithPhone }
            val s = try { decode(key) } catch (_: Exception) { null }
            done(if (s == null) null else if (trim) trimSilence(s) ?: s else s)
        }
        if (!opened) {
            recording = false
            jsToast("This page cannot use the mic, so it records with your phone's recorder. Tap record again to open it.")
            done(null)
        }
    }

    override fun stopMic() { recording = false }
    override fun isRecording() = recording

    /** Cut the quiet off both ends (Auto trim): Android's Workbench.trimSilence. */
    override fun trimSilence(s: Sample): Sample? {
        val d = s.data
        if (d.isEmpty()) return null
        var peak = 0f
        for (v in d) if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v)
        if (peak < .002f) return null
        val w = maxOf(1, s.rate / 200)
        val nw = (d.size + w - 1) / w
        val rms = FloatArray(nw)
        for (k in 0 until nw) {
            var e = 0.0; val a0 = k * w; val a1 = minOf(d.size, a0 + w)
            for (i in a0 until a1) e += d[i] * d[i].toDouble()
            rms[k] = kotlin.math.sqrt(e / maxOf(1, a1 - a0)).toFloat()
        }
        val sorted = rms.copyOf().also { it.sort() }
        val floor = sorted[minOf(sorted.size - 1, sorted.size / 10)]
        val th = maxOf(peak * .03f, floor * 4f, .001f)
        var first = 0
        while (first < nw && rms[first] < th) first++
        if (first >= nw) return null
        var last = nw - 1
        while (last > first && rms[last] < th) last--
        val a = maxOf(0, first * w - s.rate / 200)
        val z = minOf(d.size - 1, (last + 1) * w + s.rate / 20)
        val o = d.copyOfRange(a, z + 1)
        val fade = minOf(o.size, s.rate / 100)
        for (i in 0 until fade) o[o.size - 1 - i] *= i / fade.toFloat()
        return Sample("mic", o, s.rate)
    }
}
