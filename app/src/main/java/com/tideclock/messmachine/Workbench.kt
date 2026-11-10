package com.tideclock.messmachine

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import com.tideclock.messmachine.engine.Json
import com.tideclock.messmachine.engine.Library
import com.tideclock.messmachine.engine.Sample
import com.tideclock.messmachine.engine.Wav
import java.io.File
import java.nio.ByteOrder

/**
 * Your Workbench, like the site's: imports, mic takes, resampled loops, saved edits and chops.
 * Sound files live in the app's own storage; chops are a region of another sound, no new file.
 */
data class BenchItem(val id: String, val kind: String, val title: String, val at: Long, val base: String = "", val st: Double = 0.0, val en: Double = 1.0)

class Workbench(app: Context, private val lib: Library) {
    private val dir = File(app.filesDir, "bench").also { it.mkdirs() }
    private val index = File(dir, "bench.json")
    val items = mutableStateListOf<BenchItem>()

    init {
        try {
            if (index.exists()) for (o in Json.list(Json.parse(index.readText()))) {
                val m = Json.map(o)
                val b = BenchItem(
                    Json.str(m["id"], ""), Json.str(m["kind"], "imp"), Json.str(m["title"], "Sound"), Json.num(m["at"], 0.0).toLong(),
                    Json.str(m["base"], ""), Json.num(m["st"], 0.0), Json.num(m["en"], 1.0),
                )
                if (b.kind == "chop" || File(dir, b.id + ".wav").exists()) { items.add(b); register(b) }
            }
        } catch (_: Exception) {}
    }

    fun keyOf(b: BenchItem) = if (b.kind == "chop") "chop:" + b.id else "bn:" + b.id
    fun item(key: String): BenchItem? = items.firstOrNull { keyOf(it) == key }

    private fun group(kind: String) = when (kind) {
        "imp" -> "Imports"; "mic" -> "Mic takes"; "rs" -> "Resampled loops"; "take" -> "Saved edits"; else -> "Chops"
    }

    private fun register(b: BenchItem) {
        val file = if (b.kind == "chop") "" else File(dir, b.id + ".wav").absolutePath
        lib.addUser(Library.Sound(keyOf(b), b.title, "mine", file, 0.0, 0.0, group(b.kind), ""), if (b.kind == "chop") b.base else null)
    }

    private fun save() {
        val list = items.map { b ->
            linkedMapOf<String, Any?>("id" to b.id, "kind" to b.kind, "title" to b.title, "at" to b.at.toDouble(), "base" to b.base, "st" to b.st, "en" to b.en)
        }
        try { index.writeText(Json.write(list)) } catch (_: Exception) {}
    }

    private fun newId() = java.lang.Long.toString(System.currentTimeMillis(), 36) + (100..999).random()

    /** Keep a sound: write it as a WAV and add it to the Workbench jar. Returns its key. Call off the main thread. */
    fun addSample(kind: String, title: String, s: Sample): String {
        val b = BenchItem(newId(), kind, title.take(40), System.currentTimeMillis())
        File(dir, b.id + ".wav").outputStream().buffered().use { Wav.writeMono16(it, s.data, s.rate) }
        synchronized(items) { items.add(b); register(b); save() }
        lib.putSample(keyOf(b), Sample(keyOf(b), s.data, s.rate))
        return keyOf(b)
    }

    fun addChop(base: String, title: String, st: Double, en: Double): String {
        val b = BenchItem(newId(), "chop", title.take(40), System.currentTimeMillis(), base, st, en)
        synchronized(items) { items.add(b); register(b); save() }
        return keyOf(b)
    }

    fun remove(key: String) {
        val b = item(key) ?: return
        items.remove(b)
        if (b.kind != "chop") File(dir, b.id + ".wav").delete()
        lib.removeUser(key)
        // chops of this sound go too
        for (c in items.filter { it.kind == "chop" && it.base == key }) { items.remove(c); lib.removeUser(keyOf(c)) }
        save()
    }

    fun count(kind: String) = items.count { it.kind == kind }

    /* ---------------- importing any audio file ---------------- */

    /** WAV through our own reader, anything else (MP3, M4A, OGG, FLAC) through Android's decoders. Mono, max 60 s. */
    fun decode(ctx: Context, uri: Uri): Sample {
        val head = ByteArray(12)
        val got = ctx.contentResolver.openInputStream(uri)?.use { it.read(head) } ?: throw IllegalStateException("Could not open the file")
        if (got < 12) throw IllegalStateException("That file is empty")
        if (String(head, 0, 4) == "RIFF" && String(head, 8, 4) == "WAVE") {
            ctx.contentResolver.openInputStream(uri)!!.use { return Wav.read("imp", it) }
        }
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        var track = -1
        for (i in 0 until ex.trackCount) if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; break }
        if (track < 0) throw IllegalStateException("No sound in that file")
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var isFloat = false
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
        val out = FloatList()
        val info = MediaCodec.BufferInfo()
        var inDone = false
        try {
            while (true) {
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    isFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (oi >= 0) {
                    val ob = codec.getOutputBuffer(oi)!!.order(ByteOrder.LITTLE_ENDIAN)
                    ob.position(info.offset); ob.limit(info.offset + info.size)
                    if (isFloat) {
                        val fb = ob.asFloatBuffer()
                        while (fb.remaining() >= ch) { var s = 0f; repeat(ch) { s += fb.get() }; out.add(s / ch) }
                    } else {
                        val sb = ob.asShortBuffer()
                        while (sb.remaining() >= ch) { var s = 0f; repeat(ch) { s += sb.get() / 32768f }; out.add(s / ch) }
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    if (out.size > rate * 60) break
                }
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            ex.release()
        }
        return Sample("imp", out.toArray(), rate)
    }

    /* ---------------- the mic ---------------- */

    @Volatile private var recording = false
    private var recThread: Thread? = null

    /** Start sampling the mic (permission already granted). done gets the take when you stop or at 30 seconds. */
    @SuppressLint("MissingPermission")
    fun startMic(done: (Sample?) -> Unit) {
        if (recording) return
        val rate = 44100
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        fun open(src: Int): AudioRecord? = try {
            AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192))
                .let { r -> if (r.state == AudioRecord.STATE_INITIALIZED) r else { r.release(); null } }
        } catch (e: Exception) { null }
        // the raw mic if the phone has it (no noise suppression), the normal mic otherwise
        val rec = open(MediaRecorder.AudioSource.UNPROCESSED) ?: open(MediaRecorder.AudioSource.MIC)
        if (rec == null) { done(null); return }
        recording = true
        recThread = Thread({
            val out = FloatList()
            val buf = ShortArray(2048)
            try {
                rec.startRecording()
                while (recording && out.size < rate * 30) {
                    val n = rec.read(buf, 0, buf.size)
                    for (i in 0 until maxOf(0, n)) out.add(buf[i] / 32768f)
                }
                rec.stop()
            } catch (_: Exception) {}
            rec.release()
            recording = false
            done(trimSilence(Sample("mic", out.toArray(), rate)))
        }, "mess-mic").also { it.start() }
    }

    fun stopMic() { recording = false }
    fun isRecording() = recording

    /** Cut the quiet off both ends, keeping 10 ms before the first sound. */
    private fun trimSilence(s: Sample): Sample? {
        val d = s.data
        if (d.isEmpty()) return null
        var peak = 0f
        for (v in d) if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v)
        if (peak < .002f) return null
        val th = peak * .03f
        var a = 0
        while (a < d.size && kotlin.math.abs(d[a]) < th) a++
        var z = d.size - 1
        while (z > a && kotlin.math.abs(d[z]) < th) z--
        a = maxOf(0, a - s.rate / 100)
        z = minOf(d.size - 1, z + s.rate / 20)
        return Sample("mic", d.copyOfRange(a, z + 1), s.rate)
    }
}

/** A growable float list without boxing. */
class FloatList {
    private var a = FloatArray(1 shl 16)
    var size = 0
        private set
    fun add(v: Float) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
    fun toArray(): FloatArray = a.copyOf(size)
}
