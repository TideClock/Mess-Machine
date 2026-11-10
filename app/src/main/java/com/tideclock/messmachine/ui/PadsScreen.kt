package com.tideclock.messmachine.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tideclock.messmachine.AppState
import com.tideclock.messmachine.Overlay
import com.tideclock.messmachine.engine.Project
import com.tideclock.messmachine.engine.Theory

@Composable
fun PadsScreen(state: AppState) {
    val P = state.project
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp, end = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (b in 0 until 4) {
                val n = (0 until Project.BANK).count { P.kit[b * Project.BANK + it].isNotEmpty() }
                Btn(if (n > 0) "Bank ${b + 1}" else "Bank ${b + 1} ·", modifier = Modifier.weight(1f), onClick = { state.bank = b }, on = state.bank == b && !state.keysMode, small = true, enabled = !state.keysMode, tag = "bank-$b")
            }
        }
        Wrap(Modifier.padding(bottom = 12.dp)) {
            Btn("Pads", { state.keysMode = false }, on = !state.keysMode, small = true, tag = "mode-pads")
            Btn("Keys", { state.keysMode = true }, on = state.keysMode, onColor = C.Teal, small = true, tag = "mode-keys")
            Btn("FX", { state.showFx = !state.showFx }, on = state.showFx, onColor = C.Pink, small = true, tag = "fx-toggle")
        }
        if (state.keysMode || state.keyboards.isNotEmpty()) KeysBar(state)
        Box(Modifier.fillMaxWidth().widthIn(max = 560.dp).align(Alignment.CenterHorizontally)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (r in 0 until 4) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (c in 0 until 4) Pad(state, r * 4 + c, Modifier.weight(1f))
                    }
                }
            }
        }
        for (pad in state.keyboards.toList()) PianoKeyboard(state, pad)
        if (state.showFx) FxPanel(state)
        SelectedPad(state)
        if (!state.keysMode && state.keyboards.isEmpty()) {
            Note(
                "Tap a pad to play it. Empty pads open the jars. Hold a pad to drop a piano for it under the grid; up to four at once. " +
                    "Keys turns the grid into a scale. Repeat up top rolls whatever you hold, and with Rec on while playing, every hit lands in the loop.",
                modifier = Modifier.padding(top = 14.dp),
            )
        }
    }
}

@Composable
fun KeysBar(state: AppState) {
    @Suppress("UNUSED_VARIABLE") val sv = state.selV
    val P = state.project
    Column(Modifier.padding(bottom = 12.dp)) {
        Wrap(Modifier.padding(bottom = 4.dp)) {
            Select(Theory.NN[P.root], Theory.NN.mapIndexed { i, n -> i to n }, { state.setRoot(it) })
            Select(P.scale, Theory.SCALES.keys.map { it to it }, { state.setScale(it) })
            Select(chordName(state.chordMode), chordOptions(P.scale, P.root), { state.pickChordMode(it) })
        }
        Note(
            if (state.lastChord.isNotEmpty()) state.lastChord else "${Theory.NN[P.root]} ${P.scale.lowercase()}" +
                if (state.keysMode) " · the grid plays ${state.lib.titleOf(state.selKey()).ifEmpty { "the pad you picked" }}" else "",
            color = C.Yellow, size = 13.sp, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

fun chordName(m: String) = when {
    m == "off" -> "Chords off"
    m == "triad" -> "Triads"
    m == "seventh" -> "7ths"
    m == "power" -> "Power"
    m.startsWith("set:") -> m.substring(4)
    else -> m
}

fun chordOptions(scale: String, root: Int): List<Pair<String, String>> {
    val out = mutableListOf("off" to "Off", "triad" to "Triads from the scale", "seventh" to "7ths from the scale", "power" to "Power chords")
    for ((name, set) in Theory.CHORDSETS) {
        out += ("set:$name") to (name + if (Theory.setFits(set, scale)) "" else " (moves the scale)")
    }
    return out
}

@Composable
fun Pad(state: AppState, d: Int, modifier: Modifier) {
    @Suppress("UNUSED_VARIABLE") val sv = state.selV
    val P = state.project
    var pressed by remember { mutableStateOf(false) }
    val keys = state.keysMode
    val i = state.bank * Project.BANK + d
    val key = if (keys) state.selKey() else P.kit[i]
    val empty = key.isEmpty()
    val base = if (empty) C.Card else padColor(Color(state.lib.colorOf(key)))
    val sel = !keys && i == P.sel
    val hasKeys = !keys && i in state.keyboards
    val top: String
    val main: String
    if (keys) {
        val off = state.keyOffset(d)
        top = if (off == 0) "ROOT" else (if (off > 0) "+$off" else "$off")
        main = state.keyLabel(d)
    } else {
        top = "${i + 1}"
        main = if (empty) "EMPTY" else state.lib.titleOf(key).uppercase()
    }
    val fill = if (pressed) lerp(base, C.Cream, .55f) else base
    Box(
        modifier
            .aspectRatio(1f)
            .scale(if (pressed) .93f else 1f)
            .testTag("pad-$d")
            .drawBehind {
                val r = CornerRadius(7.dp.toPx())
                if (empty) {
                    drawRoundRect(C.Empty, cornerRadius = r, style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))))
                } else {
                    val sh = if (pressed) 0f else 4.dp.toPx()
                    drawRoundRect(if (sel) C.Pink else C.Black, topLeft = Offset(sh, sh), size = size, cornerRadius = r)
                    drawRoundRect(Brush.verticalGradient(listOf(lerp(fill, C.Cream, .12f), lerp(fill, C.Ink, .18f))), cornerRadius = r)
                    // a hard highlight along the top, the "oomph"
                    drawRoundRect(Color.White.copy(alpha = .16f), topLeft = Offset(5.dp.toPx(), 4.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width - 10.dp.toPx(), 4.dp.toPx()), cornerRadius = CornerRadius(2.dp.toPx()))
                    if (pressed) drawRoundRect(C.Pink, cornerRadius = r, style = Stroke(width = 6.dp.toPx()))
                }
            }
            .then(if (!empty) Modifier.border(if (sel) 3.dp else 2.dp, if (sel) C.Cream else C.Black, RoundedCornerShape(7.dp)) else Modifier)
            .pointerInput(d) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    state.padDown(d)
                    // hold a loaded pad to drop a piano for it
                    val ended = withTimeoutOrNull(450) { waitForUpOrCancellation(); true }
                    if (ended == null) {
                        if (!state.keysMode) state.toggleKeyboard(state.bank * Project.BANK + d)
                        waitForUpOrCancellation()
                    }
                    pressed = false
                    state.padUp(d)
                }
            }
            .padding(8.dp),
    ) {
        T(top, size = 11.sp, color = if (empty) C.Empty else ink(fill).copy(alpha = .75f), modifier = Modifier.align(Alignment.TopStart))
        if (hasKeys) T("🎹", size = 12.sp, modifier = Modifier.align(Alignment.TopEnd))
        else if (!keys && !empty && P.edits.containsKey(key)) T("✂", size = 12.sp, color = ink(fill), modifier = Modifier.align(Alignment.TopEnd))
        T(main, size = if (keys) 18.sp else 11.sp, color = if (empty) C.Empty else ink(fill), weight = FontWeight.Black, lines = 3, modifier = Modifier.align(Alignment.BottomStart))
    }
}

@Composable
fun SelectedPad(state: AppState) {
    @Suppress("UNUSED_VARIABLE") val sv = state.selV
    val P = state.project
    val k = P.kit[P.sel]
    Card(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.padding(end = 10.dp).size(18.dp).background(if (k.isEmpty()) C.Empty else padColor(Color(state.lib.colorOf(k)))).border(2.dp, C.Cream))
                T("PAD ${P.sel + 1}", size = 14.sp, color = C.Yellow, spacing = .06.em)
                T("  " + if (k.isEmpty()) "empty" else state.lib.titleOf(k), size = 15.sp, lines = 1, modifier = Modifier.weight(1f))
            }
            Wrap(Modifier.padding(top = 12.dp)) {
                Btn(if (k.isEmpty()) "Pick a sound" else "Swap sound", { state.overlay = Overlay.Picker(P.sel) }, small = true, color = C.Teal, tag = "pick-sound")
                Btn("✂ Doctor it", { state.overlay = Overlay.Doctor(k) }, small = true, color = C.Yellow, enabled = k.isNotEmpty(), tag = "doctor")
                Btn(if (P.sel in state.keyboards) "Hide keys" else "Keys ▾", { state.toggleKeyboard(P.sel) }, small = true, enabled = k.isNotEmpty(), tag = "keys-for-pad")
                Btn("Hear", { state.preview(k) }, small = true, enabled = k.isNotEmpty())
                Btn("Clear pad", { state.clearPad(P.sel) }, small = true, enabled = k.isNotEmpty())
            }
            Dashed(Modifier.padding(vertical = 12.dp))
            T("GET SOUNDS", size = 12.sp, color = C.Yellow, spacing = .1.em)
            Wrap(Modifier.padding(top = 8.dp)) {
                Btn("The jars", { state.tab = com.tideclock.messmachine.Tab.Sounds }, small = true)
                Btn("Your Workbench", { state.openWorkbench() }, small = true)
                Btn("Resample this loop", { state.resampleLoop() }, small = true, enabled = P.pat().used())
            }
        }
    }
}

/** The shared look of a small coloured square used in lists. */
@Composable
fun Swatch(c: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(14.dp)) { drawRect(c); drawRect(C.Cream, style = Stroke(2.dp.toPx())) }
}
