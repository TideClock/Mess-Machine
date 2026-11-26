package com.tideclock.messmachine.platform

import com.tideclock.messmachine.Bench
import com.tideclock.messmachine.NoteBook
import com.tideclock.messmachine.Packs
import com.tideclock.messmachine.engine.Engine
import com.tideclock.messmachine.engine.Library

/**
 * Everything AppState needs from the phone, in one place (CMP step 4, the end of it): AppState takes a Platform, so
 * it holds no Android type. Android's is AndroidPlatform; the iPhone's comes from Swift or Kotlin/Native.
 */
interface Platform {
    val device: Device
    val settings: Settings
    val main: MainThread
    val worker: Worker
    val files: FileStore
    /** the app's own folder and its cache folder */
    val filesDir: String
    val cacheDir: String
    fun audioOut(engine: Engine, rate: Int, burst: Int): AudioOut
    fun bench(lib: Library): Bench
    fun notes(): NoteBook
    fun packs(lib: Library): Packs
    fun speech(heard: (String) -> Unit, done: (text: String, problem: String?) -> Unit): SpeechIn
    fun updater(): AppUpdater
    fun mouthIn(): MouthIn
}
