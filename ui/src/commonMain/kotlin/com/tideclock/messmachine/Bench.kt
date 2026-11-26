package com.tideclock.messmachine

import com.tideclock.messmachine.engine.Sample
import com.tideclock.messmachine.platform.MFile

/**
 * Your Workbench, like the site's: imports, mic takes, resampled loops, saved edits and chops.
 * Sound files live in the app's own storage; chops are a region of another sound, no new file.
 */
data class BenchItem(
    val id: String, val kind: String, val title: String, val at: Long, val base: String = "", val st: Double = 0.0, val en: Double = 1.0,
    /** The mess's tempo when this was made (0 when unknown), and for a resampled loop its length in beats. */
    val bpm: Double = 0.0, val beats: Int = 0,
    /** The measured key, like "A# minor"; empty when it could not be told for sure; null when never measured. */
    val mkey: String? = null,
)

/**
 * Your Workbench as AppState sees it (CMP step 4, MicIn and Files): your own sounds kept as WAVs, chops of them, the
 * mic, and importing audio files. Android's is Workbench (AudioRecord, MediaExtractor).
 */
interface Bench {
    val items: MutableList<BenchItem>
    fun keyOf(b: BenchItem): String
    fun item(key: String): BenchItem?
    /** keep a sound as a WAV in the Workbench jar; returns its key. Call off the main thread. */
    fun addSample(kind: String, title: String, s: Sample, bpm: Double = 0.0, beats: Int = 0): String
    /** one of your own sounds came down from your account */
    fun adoptCloud(id: String, kind: String, title: String, from: MFile, bpm: Double, beats: Int, mkey: String): Boolean
    fun addChop(base: String, title: String, st: Double, en: Double): String
    fun remove(key: String)
    fun count(kind: String): Int
    /** an audio file the person picked (the platform's own address for it): mono, at most 60 s */
    fun decode(uri: String): Sample
    /** sample the mic; done gets the take when stopped or at 30 s */
    fun startMic(trim: Boolean = true, auto: Boolean = false, done: (Sample?) -> Unit)
    fun stopMic()
    fun isRecording(): Boolean
    fun trimSilence(s: Sample): Sample?
}
