package com.tideclock.messmachine

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import com.tideclock.messmachine.ui.MessMachineApp

/** Holds the one AppState so it outlives the screen. */
open class MessApp : Application() {  // open: the big test runs TestMessApp (app/src/test)
    // accounts: SupabaseApi.orNull is null when this build has no server, and then the app is exactly as before
    val state: AppState by lazy { AppState(this, SupabaseApi.orNull(this)) }

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
        // the new app ID's first launch: take the old app's messes before anything reads the settings (Nov 24)
        if (Move.needed(this)) {
            moving = true
            setContent { MovingScreen() }
            Thread({
                val said = Move.run(this)
                runOnUiThread {
                    moving = false
                    setContent { MessMachineApp(state) }
                    state.say(said)
                    startSound()
                    if (savedInstanceState == null) handle(intent)
                }
            }, "mess-move-in").start()
            return
        }
        setContent { MessMachineApp(state) }
        if (savedInstanceState == null) handle(intent)
    }

    /** True while the old app's files are coming over; nothing may touch the AppState until then. */
    private var moving = false

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private val midi by lazy { MidiIn(this, state) }

    override fun onStart() {
        super.onStart()
        if (moving) return
        startSound()
    }

    private fun startSound() {
        state.onScreen = true
        state.audio.start()
        try { midi.start() } catch (_: Exception) {}
    }

    override fun onStop() {
        super.onStop()
        if (moving) return
        try { midi.stop() } catch (_: Exception) {}
        state.saveNow()
        // accounts: a mess saved in the last few seconds goes online now, not when the app comes back
        state.cloudBackground()
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
            // accounts: an invite or share link (messmachine://j/<token>, or the site's /join/ page) opens here; anything
            // else is a mess file
            Intent.ACTION_VIEW -> i.data?.let { u ->
                val sch = u.scheme ?: ""
                if (sch == "messmachine" || sch == "https" || sch == "http") { if (!state.cloudLink(u.toString())) state.say("That link is not one this app opens.") }
                else state.importUri(u)
            }
        }
    }
}

/** Shown for the few seconds the old app's files take to move over. */
@androidx.compose.runtime.Composable
private fun MovingScreen() {
    androidx.compose.foundation.layout.Box(
        androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF1E1B20)),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        androidx.compose.material3.Text("Moving your messes over from the old app…", color = androidx.compose.ui.graphics.Color(0xFFFCF8EE))
    }
}
