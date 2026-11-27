package com.tideclock.messmachine.platform

/**
 * The sound out (CMP step 4): a thread that keeps calling the engine and hands its blocks to the speaker, plus what
 * the notes, Settings and the latency check read about it. Android's is AndroidAudioOut (AAudio, then AudioTrack).
 */
interface AudioOut {
    fun start()
    fun stop()
    /** stop and start again when running (a new path or buffer rule) */
    fun restart()
    /** try the low-latency path first (AAudio on Android) */
    var useAAudio: Boolean
    /** how many bursts the buffer may grow after underruns */
    var growCap: Int
    val bufferFrames: Int
    val underruns: Int
    val outputMs: Double
    val fastPath: String
    val cpuHint: String
    val grown: Int
    val shrunk: Int
    val growVoices: Int
    /** the screen tells it when a frame ran long, so slow audio blocks can be matched to slow frames */
    var lastSlowFrameNanos: Long
    val path: String
    fun clicksLastMinute(): Int
    fun loadText(): String
    fun load(): Double
    fun ms(frames: Int): Double
    fun longBlocksText(max: Int = 6): String
}
