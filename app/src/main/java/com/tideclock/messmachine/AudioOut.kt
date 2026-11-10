package com.tideclock.messmachine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import com.tideclock.messmachine.engine.Engine

/**
 * Feeds the engine to the speaker. Starts with a small buffer for low lag, and grows it a burst
 * at a time if the phone ever runs dry (an underrun is a click you can hear).
 */
class AudioOut(private val engine: Engine, private val rate: Int, framesPerBurst: Int) {
    private val burst = framesPerBurst.coerceIn(64, 1024)
    @Volatile private var running = false
    private var thread: Thread? = null

    @Volatile var bufferFrames = 0
        private set
    @Volatile var underruns = 0
        private set
    @Volatile private var loadAvg = 0.0
    @Volatile private var loadPeak = 0.0

    /** Share of each buffer's time the engine spends making sound. Over 100% means dropouts. */
    fun loadText(): String = "%.0f%% average, %.0f%% peak".format(loadAvg * 100, loadPeak * 100)

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
        bufferFrames = size
        engine.setLatencyFrames(size + burst)
        val buf = FloatArray(burst * 2)
        var seen = track.underrunCount
        var n = 0
        track.play()
        try {
            val budget = burst * 1e9 / rate
            var peak = 0.0
            while (running) {
                val t0 = System.nanoTime()
                engine.render(buf, burst)
                val load = (System.nanoTime() - t0) / budget
                loadAvg += (load - loadAvg) * .01
                if (load > peak) peak = load
                track.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
                if (++n % 64 == 0) {
                    val u = track.underrunCount
                    underruns = u
                    if (u > seen && size < track.bufferCapacityInFrames) {
                        val grown = track.setBufferSizeInFrames(size + burst)
                        if (grown > 0) size = grown
                        bufferFrames = size
                        engine.setLatencyFrames(size + burst)
                    }
                    seen = u
                    if (n % 1024 == 0) {
                        loadPeak = peak; peak = 0.0
                        // read by the phone test (tools/phone-test.sh) to report clicks and load
                        Log.i("MessAudio", "underruns=$u buffer=$size burst=$burst ${loadText()}")
                    }
                }
            }
        } finally {
            try { track.pause(); track.flush(); track.stop() } catch (_: Exception) {}
            track.release()
        }
    }
}
