package com.tideclock.messmachine

import android.content.Context
import com.tideclock.messmachine.engine.Engine
import com.tideclock.messmachine.engine.Library
import com.tideclock.messmachine.platform.Platform

/** The phone for AppState on Android: the same objects AppState built for itself before CMP step 4. */
class AndroidPlatform(private val app: Context) : Platform {
    override val device = AndroidDevice(app)
    override val settings = AndroidSettings(app.getSharedPreferences("mess", Context.MODE_PRIVATE))
    override val main = AndroidMainThread()
    override val worker = AndroidWorker()
    override val files = JavaFileStore
    override val filesDir: String get() = app.filesDir.path
    override val cacheDir: String get() = app.cacheDir.path
    override fun audioOut(engine: Engine, rate: Int, burst: Int) = AndroidAudioOut(engine, rate, burst, app)
    override fun bench(lib: Library) = Workbench(app, lib)
    override fun notes() = NotesBook(app)
    override fun packs(lib: Library) = AndroidPacks(app.filesDir, lib)
    override fun speech(heard: (String) -> Unit, done: (text: String, problem: String?) -> Unit) = VoiceNote(app, heard, done)
    override fun updater() = Updater(app).also { Updater.live = it }
    override fun mouthIn() = MouthMic()
}

/** AppState as Android makes it: every caller (the activity, the tests) keeps writing AppState(context, ...). */
fun AppState(app: Context, cloudApi: com.tideclock.messmachine.cloud.CloudApi? = null, cloudClock: com.tideclock.messmachine.cloud.Clock? = null) =
    AppState(AndroidPlatform(app), cloudApi, cloudClock)
