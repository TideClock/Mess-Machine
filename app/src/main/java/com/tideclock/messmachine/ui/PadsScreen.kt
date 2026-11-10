package com.tideclock.messmachine.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
    ScrollPane {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp, end = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (b in 0 until 4) {
                val n = (0 until Project.BANK).count { P.kit[b * Project.BANK + it].isNotEmpty() }
                Btn("Bank ${b + 1}", modifier = Modifier.weight(1f), onClick = { state.bank = b }, on = state.bank == b && !state.keysMode, small = true, enabled = !state.keysMode, tag = "bank-$b")
            }
        }
        Wrap(Modifier.padding(bottom = 12.dp)) {
            Btn("Pads", { state.keysMode = false }, on = !state.keysMode, small = true, tag = "mode-pads")
            Btn("Keys", { state.keysMode = true }, on = state.keysMode, onColor = C.Teal, small = true, tag = "mode-keys")
            Btn("FX", { state.showFx = !state.showFx }, on = state.showFx, onColor = C.Pink, small = true, tag = "fx-toggle")
        }
        if (state.keysMode || state.keyboards.isNotEmpty()) KeysBar(state)
        // zoomed out, the pad grid shrinks with everything else so more of the machine fits
        Box(Modifier.fillMaxWidth(minOf(1f, state.uiScale)).widthIn(max = 560.dp).align(Alignment.CenterHorizontally)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (r in 0 until 4) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (c in 0 until 4) Pad(state, r * 4 + c, Modifier.weight(1f))
                    }
                    // a piano opened from this row drops right here, under its pad
                    if (!state.keysMode) for (pad in state.keyboards.toList()) {
                        if (pad / Project.BANK == state.bank && (pad % Project.BANK) / 4 == r) PianoKeyboard(state, pad)
                    }
                }
            }
        }
        if (state.showFx) FxPanel(state)
        SelectedPad(state)
        if (!state.keysMode && state.keyboards.isEmpty()) {
            Note(
                "Tap a pad to play it. Empty pads open the jars. Hold a pad still, then lift, for its menu: Padiano drops a keyboard right under that pad's row, one per row. " +
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
            Btn("In key only", { state.switchInKey(!state.inKeyOnly) }, on = state.inKeyOnly, small = true, onColor = C.Yellow, tag = "in-key")
            if (state.chordMode != "off") {
                Btn("Play chord", { state.switchChordPlay(true) }, on = state.chordPlay, small = true, onColor = C.Teal, tag = "chord-play")
                Btn("Show chord", { state.switchChordPlay(false) }, on = !state.chordPlay, small = true, onColor = C.Pink, tag = "chord-show")
            }
        }
        HoldBar(state)
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
    var armed by remember { mutableStateOf(false) }
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
                if (empty && state.micOn && state.micPad == i) {
                    drawRoundRect(C.Riot, cornerRadius = r)
                } else if (empty) {
                    drawRoundRect(C.Empty, cornerRadius = r, style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))))
                } else {
                    val sh = if (pressed) 0f else 4.dp.toPx()
                    drawRoundRect(if (sel) C.Pink else C.Black, topLeft = Offset(sh, sh), size = size, cornerRadius = r)
                    drawRoundRect(Brush.verticalGradient(listOf(lerp(fill, C.Cream, .12f), lerp(fill, C.Ink, .18f))), cornerRadius = r)
                    // a hard highlight along the top, the "oomph"
                    drawRoundRect(Color.White.copy(alpha = .16f), topLeft = Offset(5.dp.toPx(), 4.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width - 10.dp.toPx(), 4.dp.toPx()), cornerRadius = CornerRadius(2.dp.toPx()))
                    if (pressed) drawRoundRect(if (armed) C.Yellow else C.Pink, cornerRadius = r, style = Stroke(width = 6.dp.toPx()))
                    if (hasKeys) {
                        // the pad lights along its bottom edge: its piano is open below this row
                        val h = 7.dp.toPx()
                        drawRoundRect(C.Yellow, topLeft = Offset(0f, size.height - h), size = androidx.compose.ui.geometry.Size(size.width, h), cornerRadius = CornerRadius(3.dp.toPx()))
                        drawRect(C.Pink.copy(alpha = .55f), topLeft = Offset(0f, size.height), size = androidx.compose.ui.geometry.Size(size.width, 6.dp.toPx()))
                    }
                }
            }
            .then(if (!empty) Modifier.border(if (sel) 3.dp else 2.dp, if (sel) C.Cream else C.Black, RoundedCornerShape(7.dp)) else Modifier)
            .pointerInput(d) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // a pad you play never scrolls the page: its touches are used up here
                    down.consume()
                    pressed = true
                    armed = false
                    state.padDown(d)
                    val slop = 10.dp.toPx()
                    var moved = false
                    // follows this finger until it lifts; any slide past the slop cancels the menu
                    suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.follow() {
                        while (true) {
                            val e = awaitPointerEvent()
                            val c = e.changes.firstOrNull { it.id == down.id } ?: return
                            if (!c.pressed) { c.consume(); return }
                            if ((c.position - down.position).getDistance() > slop) { moved = true; armed = false }
                            c.consume()
                        }
                    }
                    val early = withTimeoutOrNull(450) { follow(); true }
                    if (early == null) {
                        // held still long enough: lift now for the pad's menu (like a right click)
                        if (!moved && !state.keysMode && !state.locked) armed = true
                        follow()
                    }
                    pressed = false
                    state.padUp(d)
                    if (armed && !moved) state.padMenu = state.bank * Project.BANK + d
                    armed = false
                }
            }
            .padding(8.dp),
    ) {
        T(top, size = 11.sp, color = if (empty) C.Empty else ink(fill).copy(alpha = .75f), modifier = Modifier.align(Alignment.TopStart))
        if (!keys && !empty && P.edits.containsKey(key)) T("✂", size = 12.sp, color = ink(fill), modifier = Modifier.align(Alignment.TopEnd))
        if (!keys) PadMenu(state, i)
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
                Btn(if (P.sel in state.keyboards) "Hide Padiano" else "🎹 Padiano", { state.toggleKeyboard(P.sel) }, small = true, enabled = k.isNotEmpty(), tag = "keys-for-pad")
                Btn("Hear", { state.preview(k) }, small = true, enabled = k.isNotEmpty())
                Btn("Clear pad", { state.clearPad(P.sel) }, small = true, enabled = k.isNotEmpty())
                Btn(if (state.micOn && state.micPad == P.sel) "■ Stop" else "● Record onto it", { state.askRecord(P.sel) }, small = true, color = C.Riot,
                    enabled = !state.micOn || state.micPad == P.sel, tag = "record-pad")
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

/** Hold: one tap on or off. The first N notes you play keep sounding; press one again to let it go. */
@Composable
fun HoldBar(state: AppState, modifier: Modifier = Modifier) {
    Wrap(modifier.padding(top = 8.dp), gap = 6.dp) {
        HoldToggle(state, tag = "hold-toggle")
        Btn("−", { state.changeHoldCount(state.holdCount - 1) }, small = true, enabled = state.holdCount > 0, tag = "hold-down")
        Box(Modifier.size(width = 54.dp, height = 34.dp).background(C.Black).border(2.dp, if (state.holdOn) C.Yellow else C.Cream), contentAlignment = Alignment.Center) {
            val n = if (state.holdCount == 0) "LAST" else "${state.holdCount}"
            T(if (state.holdOn && state.holdCount > 0) "${state.latched.size}/$n" else n, size = 13.sp, color = if (state.holdOn) C.Yellow else C.Dim, family = Mono, weight = FontWeight.Bold)
        }
        Btn("+", { state.changeHoldCount(state.holdCount + 1) }, small = true, enabled = state.holdCount < 8, tag = "hold-up")
        Btn("Let go", { state.releaseHolds() }, small = true, color = C.Riot, enabled = state.latched.isNotEmpty(), tag = "hold-release")
    }
}

/** The big Hold switch: yellow when on, with how many notes are holding. */
@Composable
fun HoldToggle(state: AppState, tag: String, modifier: Modifier = Modifier) {
    Btn(if (!state.holdOn) "○ Hold" else if (state.holdCount == 0) "● Hold last" else "● Hold ${state.latched.size}/${state.holdCount}", { state.switchHold() }, modifier = modifier.widthIn(min = 92.dp),
        on = state.holdOn, onColor = C.Yellow, small = true, tag = tag)
}

/** Hold a pad: its own menu, like a right click. */
@Composable
fun PadMenu(state: AppState, i: Int) {
    val P = state.project
    val k = P.kit[i]
    val open = state.padMenu == i
    fun close() { state.padMenu = -1 }
    DropdownMenu(expanded = open, onDismissRequest = { close() }, modifier = Modifier.background(C.Ink).border(3.dp, C.Cream).testTag("padmenu")) {
        T("PAD ${i + 1}" + if (k.isNotEmpty()) " · ${state.lib.titleOf(k).uppercase()}" else "", size = 12.sp, color = C.Yellow, lines = 1, spacing = .06.em,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
        @Composable
        fun item(label: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
            DropdownMenuItem(
                text = { T(label.uppercase(), size = 13.sp, color = if (enabled) C.Cream else C.Empty, spacing = .06.em) },
                onClick = { close(); onClick() }, enabled = enabled, modifier = Modifier.testTag(tag),
            )
        }
        val recHere = state.micOn && state.micPad == i
        item(if (recHere) "■ Stop recording" else "● Record onto this pad", "menu-record", enabled = !state.micOn || recHere) { state.askRecord(i) }
        if (k.isEmpty()) {
            item("Pick a sound", "menu-pick") { state.selectPad(i, false); state.overlay = Overlay.Picker(i) }
        } else {
            item(if (i in state.keyboards) "🎹 Hide the Padiano" else "🎹 Padiano under this row", "menu-piano") { state.selectPad(i, false); state.toggleKeyboard(i) }
            item("✂ Doctor it", "menu-doctor") { state.selectPad(i, false); state.overlay = Overlay.Doctor(k) }
            item("Hear it", "menu-hear") { state.preview(k) }
            item("Swap sound", "menu-swap") { state.selectPad(i, false); state.overlay = Overlay.Picker(i) }
            item(if (k in state.favs) "★ Unstar" else "☆ Star", "menu-star") { state.toggleFav(k) }
            item("Keep in Your Workbench", "menu-keep") { state.keepInBench(i) }
            item("Share this sound", "menu-share") { state.shareSoundWav(k) }
            item("Clear pad", "menu-clear") { state.clearPad(i) }
        }
    }
}

/** The shared look of a small coloured square used in lists. */
@Composable
fun Swatch(c: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(14.dp)) { drawRect(c); drawRect(C.Cream, style = Stroke(2.dp.toPx())) }
}
