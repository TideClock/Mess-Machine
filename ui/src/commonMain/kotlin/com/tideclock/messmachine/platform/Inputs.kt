package com.tideclock.messmachine.platform

import com.tideclock.messmachine.engine.MouthTracker

/** Speech to text for a quick note (CMP step 4, NotesOut). Android's is VoiceNote (SpeechRecognizer). */
interface SpeechIn {
    fun available(): Boolean
    /** false when it could not start */
    fun start(): Boolean
    fun stop()
    fun cancel()
}

/** The mic feeding the mouth tracker (CMP step 4, MicIn). Android's is MouthMic (AudioRecord at 16 kHz). */
interface MouthIn {
    val tracker: MouthTracker
    val running: Boolean
    fun start(): Boolean
    fun stop()
}

/**
 * The in-app updater (CMP step 4). Android downloads the newest build from the latest release; on iOS the App Store
 * updates the app, so iOS's version finds nothing.
 */
interface AppUpdater {
    val latest: Int
    val status: String
    val busy: Boolean
    var later: Boolean
    val current: Int
    val ready: Boolean
    fun found(build: Int, url: String, size: Long)
    fun check(quiet: Boolean, done: (String) -> Unit = {})
    fun allowed(): Boolean
    fun download(before: () -> Unit)
}
