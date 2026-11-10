package com.tideclock.messmachine

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Build
import com.tideclock.messmachine.engine.Engine
import com.tideclock.messmachine.engine.Sample

/**
 * The speaker test (MM-1): plays five clicks through the engine and listens with the mic. The mic's own
 * timestamps say when each click reached it, so the result is the real time from the app sending a sound
 * to it leaving the speaker, on this phone. The mic's own delay is not in it.
 * Needs the mic permission. Blocks for about 4 seconds; run it off the main thread.
 */
object SpeakerTest {
    /** Returns the middle delay in ms (-1 if no click was heard) and a line for the screen and the notes. */
    @android.annotation.SuppressLint("MissingPermission")
    fun run(engine: Engine, rate: Int, click: Sample): Pair<Double, String> {
        val src = if (Build.VERSION.SDK_INT >= 24) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return -1.0 to "The mic is not available."
        val rec = try {
            AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate / 5 * 2)).also {
                if (it.state != AudioRecord.STATE_INITIALIZED) { it.release(); throw IllegalStateException() }
            }
        } catch (_: Exception) {
            try { AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate / 5 * 2)) }
            catch (_: Exception) { return -1.0 to "The mic would not open." }
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return -1.0 to "The mic would not open." }
        val total = rate * 4
        val data = ShortArray(total)
        val clickAt = longArrayOf(0, 0, 0, 0, 0)
        val clickFrames = intArrayOf(rate * 4 / 10, rate, rate * 16 / 10, rate * 22 / 10, rate * 28 / 10)
        var next = 0
        val ts = AudioTimestamp()
        var tsFrame = -1L
        var tsNanos = 0L
        try {
            rec.startRecording()
            var got = 0
            while (got < total) {
                val r = rec.read(data, got, minOf(rate / 100, total - got))
                if (r <= 0) break
                got += r
                if (next < 5 && got >= clickFrames[next]) {
                    clickAt[next] = System.nanoTime()
                    engine.preview(click, null, 0, false)
                    next++
                }
                if (Build.VERSION.SDK_INT >= 24 && got > rate / 2 && rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                    tsFrame = ts.framePosition; tsNanos = ts.nanoTime
                }
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
        if (tsFrame < 0) return -1.0 to "This phone's mic gives no timestamps, so the test cannot time the clicks."
        // the room's own noise, before the first click
        var noise = 0
        for (k in 0 until rate * 3 / 10) noise = maxOf(noise, Math.abs(data[k].toInt()))
        val thr = maxOf(noise * 4, 1500)
        val found = ArrayList<Double>()
        for (i in 0 until 5) {
            if (clickAt[i] == 0L) continue
            // the mic frame recorded at the moment the click was sent, then look up to half a second after it
            val from = (tsFrame + (clickAt[i] - tsNanos) * rate / 1_000_000_000L).toInt().coerceIn(0, total - 1)
            val to = minOf(total, from + rate / 2)
            for (k in from until to) {
                if (Math.abs(data[k].toInt()) > thr) {
                    val heard = tsNanos + (k - tsFrame) * 1_000_000_000L / rate
                    val ms = (heard - clickAt[i]) / 1e6
                    if (ms in 0.0..500.0) found.add(ms)
                    break
                }
            }
        }
        if (found.isEmpty()) return -1.0 to "No click heard. Turn the volume up, take headphones out and try in a quiet room."
        found.sort()
        val mid = found[found.size / 2]
        return mid to "%.0f ms from the app to the speaker (%d of 5 clicks heard, %.0f to %.0f ms)".format(mid, found.size, found.first(), found.last())
    }
}
