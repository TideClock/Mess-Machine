package com.tideclock.messmachine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.util.Log
import com.tideclock.messmachine.engine.Engine
import com.tideclock.messmachine.engine.Library

/**
 * Feeds the engine to the speaker. Starts with a small buffer for low lag, grows it a burst at a time
 * if the phone ever runs dry (an underrun is a click you can hear), and gives a burst back after a
 * quiet minute (MM-2). It also measures itself for the Audio check (MM-1, MM-3) and asks Android to
 * keep the audio thread's CPU fast enough for each block (MM-6).
 */
class AudioOut(private val engine: Engine, private val rate: Int, framesPerBurst: Int, private val ctx: Context? = null) {
    private val burst = framesPerBurst.coerceIn(64, 1024)
    @Volatile private var running = false
    private var thread: Thread? = null

    @Volatile var bufferFrames = 0
        private set
    @Volatile var underruns = 0
        private set
    @Volatile private var loadAvg = 0.0
    @Volatile private var loadPeak = 0.0

    /** Time from a block being written to it leaving the speaker, from Android's own clock (MM-1). -1 until known. */
    @Volatile var outputMs = -1.0
        private set
    /** Steady CPU (MM-6): "on", "not on this phone" or "off" before audio starts. */
    @Volatile var cpuHint = "off"
        private set
    /** Buffer changes this session, for the notes: grown and given back. */
    @Volatile var grown = 0
        private set
    @Volatile var shrunk = 0
        private set
    /** Set by the screen when a frame took over 50 ms, so a long audio block can say the screen was busy too. */
    @Volatile var lastSlowFrameNanos = 0L

    // MM-3: the last 16 blocks that took over 150% of their time, kept as plain numbers (nothing allocated on the audio thread)
    private val lbAt = LongArray(16)
    private val lbLoad = FloatArray(16)
    private val lbVoices = IntArray(16)
    private val lbFlags = IntArray(16)
    @Volatile private var lbCount = 0
    private var startedNanos = 0L

    /** Share of each buffer's time the engine spends making sound. Over 100% means dropouts. */
    fun loadText(): String = "%.0f%% average, %.0f%% peak".format(loadAvg * 100, loadPeak * 100)

    fun ms(frames: Int): Double = frames * 1000.0 / rate

    /** One line per recent long block: when, how long, voices playing, and what else was going on. */
    fun longBlocksText(max: Int = 6): String {
        val n = lbCount
        if (n == 0) return "none"
        val now = System.nanoTime()
        val out = StringBuilder("$n in all")
        for (k in 0 until minOf(max, n, 16)) {
            val i = (n - 1 - k) and 15
            val ago = (now - lbAt[i]) / 1_000_000_000L
            val f = lbFlags[i]
            val why = buildList {
                if (f and 1 != 0) add("loading a sound")
                if (f and 2 != 0) add("screen busy")
                if (f and 4 != 0) add("first minute")
            }.joinToString(", ").ifEmpty { "nothing else seen" }
            out.append("; ${ago}s ago ${(lbLoad[i] * 100).toInt()}% with ${lbVoices[i]} voices ($why)")
        }
        return out.toString()
    }

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "mess-audio").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(600)
        thread = null
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, burst * 8 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (e: Exception) {
            running = false
            return
        }
        var size = track.setBufferSizeInFrames(burst * 3)
        if (size <= 0) size = track.bufferSizeInFrames
        val startSize = size
        bufferFrames = size
        engine.setLatencyFrames(size + burst)
        val buf = FloatArray(burst * 2)
        val ts = AudioTimestamp()
        var written = 0L
        var seen = track.underrunCount
        var lastClick = System.nanoTime()
        var n = 0
        val budget = burst * 1e9 / rate
        val hint = hintSession(budget.toLong())
        startedNanos = System.nanoTime()
        track.play()
        try {
            var peak = 0.0
            while (running) {
                val t0 = System.nanoTime()
                engine.render(buf, burst)
                val took = System.nanoTime() - t0
                val load = took / budget
                loadAvg += (load - loadAvg) * .01
                if (load > peak) peak = load
                if (hint != null) try { HintCompat.report(hint, took.coerceAtLeast(1L)) } catch (_: Throwable) {}
                if (load > 1.5) noteLong(t0, load)
                track.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
                written += burst
                if (++n % 64 == 0) {
                    val u = track.underrunCount
                    underruns = u
                    val now = System.nanoTime()
                    if (u > seen && size < track.bufferCapacityInFrames) {
                        val g = track.setBufferSizeInFrames(size + burst)
                        if (g > 0) { size = g; grown++ }
                        bufferFrames = size
                        engine.setLatencyFrames(size + burst)
                    }
                    if (u > seen) lastClick = now
                    else if (size > startSize && now - lastClick > 60_000_000_000L) {
                        // a quiet minute: give one burst back, so one click does not cost lag for the whole session
                        val s = track.setBufferSizeInFrames(size - burst)
                        if (s > 0) { size = s; shrunk++ }
                        bufferFrames = size
                        engine.setLatencyFrames(size + burst)
                        lastClick = now
                    }
                    seen = u
                    // how long until what we just wrote is heard, from the track's own timestamp
                    if (track.getTimestamp(ts) && ts.framePosition > 0) {
                        val behind = written - ts.framePosition - (now - ts.nanoTime) * rate / 1e9
                        val m = behind * 1000.0 / rate
                        if (m in 0.0..2000.0) outputMs = if (outputMs < 0) m else outputMs + (m - outputMs) * .2
                    }
                    if (n % 1024 == 0) {
                        loadPeak = peak; peak = 0.0
                        // read by the phone test (tools/phone-test.sh) to report clicks, load and lag
                        // no format() over loadText(): its "%" signs would be read as format codes (build 58's phone test crash)
                        Log.i("MessAudio", "underruns=$u buffer=$size burst=$burst ${loadText()} out=${"%.1f".format(outputMs)}ms long=$lbCount cpu=$cpuHint")
                    }
                }
            }
        } finally {
            try { track.pause(); track.flush(); track.stop() } catch (_: Exception) {}
            track.release()
            if (hint != null) try { HintCompat.close(hint) } catch (_: Throwable) {}
        }
    }

    private fun noteLong(t0: Long, load: Double) {
        val i = lbCount and 15
        lbAt[i] = t0
        lbLoad[i] = load.toFloat()
        lbVoices[i] = engine.activeVoices()
        var f = 0
        if (Library.decoding() > 0) f = f or 1
        if (t0 - lastSlowFrameNanos < 100_000_000L) f = f or 2
        if (t0 - startedNanos < 60_000_000_000L) f = f or 4
        lbFlags[i] = f
        lbCount++
    }

    private fun hintSession(budgetNanos: Long): Any? {
        if (ctx == null || Build.VERSION.SDK_INT < 31) { cpuHint = "not on this phone"; return null }
        return try {
            HintCompat.create(ctx, Process.myTid(), budgetNanos).also { cpuHint = if (it != null) "on" else "not on this phone" }
        } catch (_: Throwable) { cpuHint = "not on this phone"; null }
    }
}

/** PerformanceHintManager lives on Android 12 and up; kept in its own object so older phones never load it. */
@androidx.annotation.RequiresApi(31)
private object HintCompat {
    fun create(ctx: Context, tid: Int, target: Long): Any? =
        ctx.getSystemService(android.os.PerformanceHintManager::class.java)?.createHintSession(intArrayOf(tid), target)
    fun report(h: Any, nanos: Long) = (h as android.os.PerformanceHintManager.Session).reportActualWorkDuration(nanos)
    fun close(h: Any) = (h as android.os.PerformanceHintManager.Session).close()
}
