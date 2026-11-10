package com.tideclock.messmachine

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import com.tideclock.messmachine.ui.MessMachineApp

/** Holds the one AppState so it outlives the screen. */
class MessApp : Application() {
    val state: AppState by lazy { AppState(this) }

    override fun onCreate() {
        super.onCreate()
        // keep the crash for the next launch, so it can go to Claude as a bug note
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                java.io.File(filesDir, "last_crash.txt").writeText(sw.toString())
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }
}

class MainActivity : ComponentActivity() {
    private val state get() = (application as MessApp).state

    override fun onCreate(savedInstanceState: Bundle?) {
        // API 36 draws every app edge to edge (MM-9): the screens pad themselves clear of the bars and the camera cutout
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent { MessMachineApp(state) }
        if (savedInstanceState == null) handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private val midi by lazy { MidiIn(this, state) }

    override fun onStart() {
        super.onStart()
        state.onScreen = true
        state.audio.start()
        try { midi.start() } catch (_: Exception) {}
    }

    override fun onStop() {
        super.onStop()
        try { midi.stop() } catch (_: Exception) {}
        state.saveNow()
        state.onScreen = false
        // keep looping in the background only if the music is playing and Settings allows it (MM-10)
        if (state.playing && !state.bgPlay) state.togglePlay()
        if (!state.playing) state.audio.stop()
    }

    private fun handle(i: Intent?) {
        if (i == null) return
        when (i.action) {
            Intent.ACTION_SEND -> {
                val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                val text = i.getStringExtra(Intent.EXTRA_TEXT)
                if (uri != null) state.importUri(uri)
                else if (text != null && text.trimStart().startsWith("{")) state.importText(text)
                else if (text != null) state.say("That share was not a Mess Machine file.")
            }
            Intent.ACTION_VIEW -> i.data?.let { state.importUri(it) }
        }
    }
}
