package com.tideclock.messmachine.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.tideclock.messmachine.AppState
import com.tideclock.messmachine.Overlay
import kotlinx.coroutines.delay

/**
 * Latency (MM-1, SE-3): what the phone adds between a tap and the sound. The live numbers, a pad to tap,
 * a speaker test that times clicks with the mic, and what live grain costs with a way to bake it away.
 * Every note carries the same numbers.
 */
@Composable
fun AudioCheckScreen(state: AppState) {
    var live by remember { mutableStateOf("") }
    var taps by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        do {
            val a = state.audio
            live = "Buffer: ${a.bufferFrames} frames, %.1f ms at ${state.rate} Hz\n".format(a.ms(a.bufferFrames)) + // format-ok: numbers only
                "Output (Android's clock): " + (if (a.outputMs >= 0) "%.0f ms".format(a.outputMs) else "measuring") + "\n" +
                "Sound engine: ${a.loadText()}\n" +
                "Dropouts: ${a.underruns}, buffer grown ${a.grown}, given back ${a.shrunk}\n" +
                "Steady CPU: ${a.cpuHint}\n" +
                "Long blocks: ${a.longBlocksText(3)}"
            if (!UiFlags.testing) delay(500)
        } while (!UiFlags.testing)
    }
    val ctx = LocalContext.current
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) state.runSpeakerTest() else state.say("The speaker test needs the mic. You can allow it in the phone's settings for Mess Machine.")
    }
    ScrollPane(hPad = 14.dp, vPad = 0.dp) {
        Note("What the phone adds between your tap and the sound. Every note you send carries these numbers.", modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
        Card(Modifier.fillMaxWidth().padding(bottom = 14.dp).testTag("check-live")) {
            Column {
                T("RIGHT NOW", size = 12.sp, color = C.Yellow, spacing = .1.em)
                T(live, size = 13.sp, weight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Card(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
            Column {
                T("1. TAP", size = 12.sp, color = C.Yellow, spacing = .1.em)
                Note("Tap the square ten times. Each tap plays a click and times your touch reaching the app.", modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
                Box(
                    Modifier.fillMaxWidth().height(110.dp).sticker(C.Card2).testTag("check-pad")
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val d = awaitFirstDown(requireUnconsumed = false)
                                d.consume()
                                state.noteTouch(d.uptimeMillis)
                                state.checkClick()
                                taps++
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) { T(if (taps == 0) "TAP HERE" else "$taps", size = 20.sp, color = C.Cream) }
                val t = state.touchMs()
                if (taps > 0) T("Touch to app: ${if (t >= 0) "$t ms" else "no taps yet"}. Last click waited %.1f ms for the next block.".format(state.engine.lastHitWaitNanos / 1e6),
                    size = 13.sp, weight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
            }
        }
        Card(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
            Column {
                T("2. SPEAKER TEST", size = 12.sp, color = C.Yellow, spacing = .1.em)
                Note("Five clicks, timed by the mic. Volume up, headphones out, a quiet room. Takes 4 seconds.", modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
                Btn("Run the speaker test", {
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) state.runSpeakerTest()
                    else mic.launch(Manifest.permission.RECORD_AUDIO)
                }, small = true, tag = "check-speaker", enabled = state.speakerNote != "Listening...")
                if (state.speakerNote.isNotEmpty()) T(state.speakerNote, size = 13.sp, weight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
                val t = state.touchMs()
                if (state.speakerMs >= 0 && t >= 0) T("Tap to sound, about ${t + state.speakerMs.toInt()} ms (plus the screen's own scan, which no app can see).",
                    size = 13.sp, color = C.Teal, weight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
            }
        }
        Card(Modifier.fillMaxWidth().padding(bottom = 14.dp).testTag("check-light")) {
            Column {
                T("3. KEEP IT LIGHT", size = 12.sp, color = C.Yellow, spacing = .1.em)
                Note("Grain keeps a sound's length when it changes pitch, live, at about three times the work of Stretch. Bake does that work ahead of time, a few seconds per sound, and plays at Stretch's cost. It also sounds cleaner.", modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
                val toBake = state.grainPadsToBake()
                val baked = state.project.kit.filter { it.isNotEmpty() }.toSet().count { state.project.edit(it).bake }
                T("Live grain voices now: ${state.engine.grainVoices()}. Sounds baked: $baked" + if (state.baking.isNotEmpty()) " (baking ${state.baking.size})" else "",
                    size = 13.sp, weight = FontWeight.Medium)
                if (toBake.isEmpty()) Note("No pad has needed live grain yet this session.", modifier = Modifier.padding(top = 6.dp))
                else {
                    T("Played with live grain: " + toBake.joinToString(", ") { "pad ${it + 1} (${state.lib.titleOf(state.project.kit[it])})" }, size = 13.sp, weight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
                    Btn("Bake them", { state.bakePads(toBake) }, small = true, tag = "check-bake")
                }
            }
        }
        Btn("Save as a note", {
            state.saveNote("tuning", "audio check", state.audioCheckSummary(), false)
            state.overlay = Overlay.Notes
        }, tag = "check-save")
        T(" ", size = 30.sp)
    }
}
