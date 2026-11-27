package com.tideclock.messmachine

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.window.ComposeViewport
import com.tideclock.messmachine.platform.MFile
import com.tideclock.messmachine.platform.Web
import com.tideclock.messmachine.platform.WebFiles
import com.tideclock.messmachine.platform.WebPlatform
import com.tideclock.messmachine.platform.jsMidiStart
import com.tideclock.messmachine.ui.MessMachineApp

@JsFun("(f) => window.mm.ready(f)") private external fun whenReady(f: () -> Unit)
@JsFun("(f) => window.mm.onHide(f)") private external fun onHide(f: () -> Unit)
@JsFun("(f) => window.mm.onShow(f)") private external fun onShow(f: () -> Unit)

/**
 * The web build's way in (docs/WEB-CMP.md): the page loads the sounds and the saved files first (window.mm.ready),
 * then the whole app runs here, the same AppState and screens as on the phone. A browser has no system fonts to fall
 * back on, so the symbols and emoji the screens use (arrows, stars, the bug) come in two small fonts, preloaded
 * before the first frame so the fonts' fallback finds them.
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
        startMidi(state)
        ComposeViewport("app") {
            val resolver = LocalFontFamilyResolver.current
            var fonts by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                for (n in listOf("mm_symbols.ttf", "mm_emoji.ttf")) {
                    val b = Web.read("font:$n") ?: continue
                    try { resolver.preload(FontFamily(Font(n, b))) } catch (_: Throwable) {}
                }
                fonts = true
            }
            if (fonts) MessMachineApp(remember { state })
        }
    }
}

/** MIDI in (Web MIDI, where the page may use it): the same parsing as Android's MidiIn, one message at a time. */
private fun startMidi(state: AppState) {
    var said = false
    jsMidiStart({ st, d1, d2 ->
        when (st and 0xF0) {
            0x90 -> if (d2 > 0) state.midiDown(d1, d2) else state.midiUp(d1)
            0x80 -> state.midiUp(d1)
            0xB0 -> state.midiCc(d1, d2)
            0xE0 -> state.midiBend((((d2 shl 7) or d1) - 8192) / 8192f)
        }
    }) { name ->
        state.say(
            if (!said && state.midiNotes.isEmpty() && state.midiKnobs.isEmpty()) "MIDI: $name plugged in. Notes 36 to 51 hit pads 1 to 16; the rest play the selected pad as keys. MIDI map, in the menu, teaches it your own layout."
            else "MIDI: $name plugged in, playing your MIDI map."
        )
        said = true
    }
}
