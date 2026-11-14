package com.tideclock.messmachine

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import com.tideclock.messmachine.engine.MouthTracker

/** Asked for the mic for mouth control (askMicFor), rather than for a pad to record onto. */
const val MIC_FOR_MOUTH = -2

/**
 * The mic for mouth control: 16 kHz, read 8 ms at a time on its own thread into a [MouthTracker]. It keeps nothing:
 * no audio is stored or sent anywhere, only the two numbers the tracker makes.
 */
class MouthMic {
    val tracker = MouthTracker(16000)
    @Volatile var running = false
        private set
    private var th: Thread? = null
    /** For renders and tests: no mic is opened, and whoever set this feeds the tracker itself. */
    @Volatile var testFeed = false

    /** Open the mic (permission already granted); false when it would not open. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        if (testFeed) { tracker.reset(); running = true; return true }
        val rate = tracker.rate
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        fun open(src: Int): AudioRecord? = try {
            AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 2048))
                .let { r -> if (r.state == AudioRecord.STATE_INITIALIZED) r else { r.release(); null } }
        } catch (_: Exception) { null }
        // voice recognition: the phone's clean voice mic (no loudness evening-out, which would flatten the level)
        val rec = open(MediaRecorder.AudioSource.VOICE_RECOGNITION) ?: open(MediaRecorder.AudioSource.MIC) ?: return false
        // where the phone has one, the echo canceller takes some of the music back out of what the mic hears
        val aec = try { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.also { it.enabled = true } else null } catch (_: Exception) { null }
        tracker.reset()
        running = true
        th = Thread({
            val sb = ShortArray(128)
            val fb = FloatArray(128)
            try {
                rec.startRecording()
                while (running) {
                    val n = rec.read(sb, 0, sb.size)
                    if (n < 0) break
                    for (i in 0 until n) fb[i] = sb[i] / 32768f
                    if (n > 0) tracker.feed(fb, 0, n)
                }
                rec.stop()
            } catch (_: Exception) {}
            try { aec?.release() } catch (_: Exception) {}
            rec.release()
            running = false
        }, "mess-mouth").also { it.priority = Thread.MAX_PRIORITY; it.start() }
        return true
    }

    fun stop() { running = false }
}
