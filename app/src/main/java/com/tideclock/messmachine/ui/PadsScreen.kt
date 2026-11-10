package com.tideclock.messmachine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.horizontalScroll
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tideclock.messmachine.AppState
import com.tideclock.messmachine.Overlay
import com.tideclock.messmachine.engine.Project
import com.tideclock.messmachine.engine.Theory

@Composable
fun PadsScreen(state: AppState) {
    val P = state.project
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            for (b in 0 until 4) {
                val n = (0 until Project.BANK).count { P.kit[b * Project.BANK + it].isNotEmpty() }
                Btn("Bank ${b + 1} · $n", { state.bank = b }, on = state.bank == b && !state.keysMode, small = true, enabled = !state.keysMode)
            }
            Btn(if (state.keysMode) "Keys" else "Pads", { state.keysMode = !state.keysMode }, on = state.keysMode, onColor = C.Teal, small = true)
        }
        if (state.keysMode) KeysBar(state)
        // the grid
        Box(Modifier.fillMaxWidth().widthIn(max = 560.dp).align(Alignment.CenterHorizontally)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (r in 0 until 4) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (c in 0 until 4) Pad(state, r * 4 + c, Modifier.weight(1f))
                    }
                }
            }
        }
        SelectedPad(state)
        if (!state.keysMode) {
            T(
                "Tap a pad to play it. Empty pads open the jars. Keys turns the grid into a scale for the pad you picked; " +
                    "Repeat up top rolls whatever you hold, and with Rec on while playing, every hit lands in the loop.",
                size = 12.sp, color = C.Dim, weight = FontWeight.Medium, modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
fun KeysBar(state: AppState) {
    val P = state.project
    Column(Modifier.padding(bottom = 10.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Select(Theory.NN[P.root], Theory.NN.mapIndexed { i, n -> i to n }, { state.setRoot(it) })
            Select(P.scale, Theory.SCALES.keys.map { it to it }, { state.setScale(it) })
            Select(chordName(state.chordMode), chordOptions(P.scale, P.root), { state.setChordMode(it) })
        }
        T(
            if (state.lastChord.isNotEmpty()) state.lastChord else "Keys play ${state.lib.titleOf(state.selKey()).ifEmpty { "the pad you picked" }} in ${Theory.NN[P.root]} ${P.scale.lowercase()}.",
            size = 13.sp, color = C.Yellow, modifier = Modifier.padding(top = 8.dp), lines = 1,
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
    val P = state.project
    var pressed by remember { mutableStateOf(false) }
    val keys = state.keysMode
    val i = state.bank * Project.BANK + d
    val key = if (keys) state.selKey() else P.kit[i]
    val col = if (key.isEmpty()) C.Empty else Color(state.lib.colorOf(key))
    val sel = !keys && i == P.sel
    val top: String
    val main: String
    if (keys) {
        val off = state.keyOffset(d)
        top = if (off == 0) "root" else (if (off > 0) "+$off" else "$off")
        main = state.keyLabel(d)
    } else {
        top = "${i + 1}"
        main = if (key.isEmpty()) "empty" else state.lib.titleOf(key)
    }
    val bg = if (pressed) lerp(col, Color.White, .35f) else col
    Box(
        modifier
            .aspectRatio(1f)
            .scale(if (pressed) .95f else 1f)
            .sticker(bg, radius = 14.dp, shadow = if (pressed) 1.dp else 4.dp, border = if (sel) 4.dp else 2.dp, borderColor = if (sel) C.Yellow else C.Black)
            .pointerInput(d) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    state.padDown(d)
                    waitForUpOrCancellation()
                    pressed = false
                    state.padUp(d)
                }
            }
            .padding(8.dp),
    ) {
        T(top, size = 11.sp, color = ink(bg).copy(alpha = .7f), modifier = Modifier.align(Alignment.TopStart))
        T(main, size = if (keys) 18.sp else 13.sp, color = ink(bg), weight = FontWeight.Black, lines = 3, modifier = Modifier.align(Alignment.BottomStart))
        if (!keys && key.isNotEmpty() && P.edits.containsKey(key)) {
            Box(Modifier.align(Alignment.TopEnd).background(C.Ink, RoundedCornerShape(6.dp)).border(1.dp, C.Black, RoundedCornerShape(6.dp)).padding(horizontal = 4.dp, vertical = 1.dp)) {
                T("✂", size = 10.sp, color = C.Yellow)
            }
        }
    }
}

@Composable
fun SelectedPad(state: AppState) {
    val P = state.project
    val k = P.kit[P.sel]
    Card(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.padding(end = 10.dp).sticker(if (k.isEmpty()) C.Empty else Color(state.lib.colorOf(k)), radius = 6.dp, shadow = 0.dp).padding(9.dp)) {}
                Column(Modifier.weight(1f)) {
                    T("Pad ${P.sel + 1}", size = 12.sp, color = C.Dim)
                    T(if (k.isEmpty()) "Empty" else state.lib.titleOf(k), size = 17.sp, lines = 1)
                }
            }
            Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn(if (k.isEmpty()) "Pick a sound" else "Swap sound", { state.overlay = Overlay.Picker(P.sel) }, small = true, color = C.Teal)
                Btn("Doctor ✂", { state.overlay = Overlay.Doctor(k) }, small = true, enabled = k.isNotEmpty())
                Btn("Hear", { state.preview(k) }, small = true, enabled = k.isNotEmpty())
                Btn("Clear pad", { state.clearPad(P.sel) }, small = true, enabled = k.isNotEmpty())
            }
        }
    }
}
