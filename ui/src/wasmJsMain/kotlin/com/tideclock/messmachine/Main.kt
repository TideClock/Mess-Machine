package com.tideclock.messmachine

import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.tideclock.messmachine.platform.MFile
import com.tideclock.messmachine.platform.WebFiles
import com.tideclock.messmachine.platform.WebPlatform
import com.tideclock.messmachine.ui.MessMachineApp

@JsFun("(f) => window.mm.ready(f)") private external fun whenReady(f: () -> Unit)
@JsFun("(f) => window.mm.onHide(f)") private external fun onHide(f: () -> Unit)
@JsFun("(f) => window.mm.onShow(f)") private external fun onShow(f: () -> Unit)

/**
 * The web build's way in (docs/WEB-CMP.md): the page loads the sounds and the saved files first (window.mm.ready),
 * then the whole app runs here, the same AppState and screens as on the phone.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    MFile.store = WebFiles
    whenReady {
        val platform = WebPlatform()
        val state = AppState(platform)
        state.onScreen = true
        state.audio.start()
        onHide { state.saveNow(); state.onScreen = false; if (!state.playing) state.audio.stop() }
        onShow { state.onScreen = true; state.audio.start() }
        ComposeViewport("app") { MessMachineApp(remember { state }) }
    }
}
