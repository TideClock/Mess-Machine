package com.tideclock.messmachine

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
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
        super.onCreate(savedInstanceState)
        setContent { MessMachineApp(state) }
        if (savedInstanceState == null) handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        state.audio.start()
    }

    override fun onStop() {
        super.onStop()
        state.saveNow()
        // keep looping in the background only if the music is playing
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
