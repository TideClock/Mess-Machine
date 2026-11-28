package com.tideclock.messmachine

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import com.tideclock.messmachine.platform.Platform
import com.tideclock.messmachine.ui.MessMachineApp
import platform.UIKit.UIViewController

/**
 * The iPhone's way in (CMP step 7): the whole app, the same screens and AppState as Android, in one view controller.
 * The iOS app makes a Platform (IosPlatform plus its Swift sound out, mic and pickers) and shows what this returns.
 */
fun MainViewController(platform: Platform): UIViewController = ComposeUIViewController {
    val state = remember { AppState(platform).also { IosApp.attach(it) } }
    MessMachineApp(state)
}

/**
 * What Android's MainActivity does around the AppState, for the iPhone's Swift side: start the sound when the app
 * shows, save and let the sound go when it hides, and open a mess file or link handed to the app. Swift calls these on
 * the main thread (scene phase, onOpenURL, the lock screen's Stop). Calls that come before the screens exist wait.
 */
object IosApp {
    private var state: AppState? = null
    private var shownEarly = false
    private val waiting = mutableListOf<String>()

    internal fun attach(s: AppState) {
        state = s
        if (shownEarly) shown()
        val w = waiting.toList(); waiting.clear()
        w.forEach { open(it) }
    }

    /** Android's onStart: the app is on screen. */
    fun shown() {
        val s = state ?: run { shownEarly = true; return }
        s.onScreen = true
        s.audio.start()
    }

    /** Android's onStop: the app left the screen. */
    fun hidden() {
        shownEarly = false
        val s = state ?: return
        s.saveNow()
        // accounts: a mess saved in the last few seconds goes online now, not when the app comes back
        s.cloudBackground()
        s.onScreen = false
        // keep looping in the background only if the music is playing and Settings allows it (MM-10)
        if (s.playing && !s.bgPlay) s.togglePlay()
        if (!s.playing) s.audio.stop()
    }

    /** Stop pressed on the lock screen or in Control Centre (Android: the notification's Stop). */
    fun stopFromLockScreen() { state?.stopFromNotification() }

    /** A mess file (a file: address) or an invite or share link (messmachine:, https:) handed to the app. */
    fun open(url: String) {
        val s = state ?: run { waiting += url; return }
        val scheme = url.substringBefore(':', "").lowercase()
        if (scheme == "messmachine" || scheme == "https" || scheme == "http") {
            if (!s.cloudLink(url)) s.say("That link is not one this app opens.")
        } else s.importUri(url)
    }
}
