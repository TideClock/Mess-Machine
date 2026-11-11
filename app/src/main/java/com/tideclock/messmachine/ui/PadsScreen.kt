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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.toSize
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
        PlayViews(state)
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp, end = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            T("BANK", size = 11.sp, color = C.Dim, spacing = .1.em)
            for (b in 0 until 4) {
                val n = (0 until Project.BANK).count { P.kit[b * Project.BANK + it].isNotEmpty() }
                Btn("${b + 1}", modifier = Modifier.weight(1f), onClick = { state.bank = b }, on = state.bank == b && !state.keysMode, small = true, enabled = !state.keysMode, tag = "bank-$b")
            }
            // the Sample sheet for the pad under your hands, one tap away (holding a pad opens it too, for any pad)
            val selKey = P.kit.getOrNull(P.sel).orEmpty()
            // a pad that sounds while it is held gets no menu from a long press, so this button is its menu
            val selHeld = selKey.isNotEmpty() && state.soundsWhileHeld(P.sel)
            Btn(if (selHeld) "Menu" else "Sample", {
                if (selHeld) { state.bank = P.sel / Project.BANK; state.padMenu = P.sel } else state.overlay = Overlay.Doctor(selKey)
            }, small = true, enabled = selKey.isNotEmpty(), tag = "open-sample")
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
        if (!state.keysMode && state.keyboards.isEmpty()) {
            Help(
                "Tap a pad to play it. Empty pads open the jars. Hold a pad still, then lift, for its menu: Padiano drops a keyboard right under that pad's row, one per row. " +
                    "Keys turns the grid into a scale. Repeat on the dock rolls whatever you hold, and with Rec on while playing, every hit lands in the loop.",
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
            Select(Theory.NN[P.root], Theory.NN.mapIndexed { i, n -> i to n }, { state.setRoot(it) }, tag = "pick-root")
            Select(P.scale, Theory.SCALES.keys.map { it to it }, { state.setScale(it) }, tag = "pick-scale")
            Select(chordName(state.chordMode), chordOptions(P.scale, P.root), { state.pickChordMode(it) }, tag = "pick-chord")
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
    val origin = remember { Origin() }
    val view = androidx.compose.ui.platform.LocalView.current
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
            .tagged("pad-$d")
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
            .trackOrigin(origin)
            .onGloballyPositioned { state.padRects[d] = androidx.compose.ui.geometry.Rect(it.positionInRoot(), it.size.toSize()) }
            .pointerInput(d) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // a pad you play never scrolls the page: its touches are used up here
                    down.consume()
                    pressed = true
                    armed = false
                    state.noteTouch(down.uptimeMillis)
                    state.padDown(d)
                    if (state.buzz && state.project.kit[state.bank * Project.BANK + d].isNotEmpty()) view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    val slop = 10.dp.toPx()
                    var moved = false
                    val pi = state.bank * Project.BANK + d
                    val det = CircleDetector(40.dp.toPx())
                    var sph: AppState.Sphere? = null
                    var lastXy = 0f to 0f
                    // hold still until the outline turns yellow, then slide: you're carrying the sound to another pad
                    var carry = false
                    var lastPos = down.position
                    // follows this finger until it lifts; any slide past the slop cancels the menu,
                    // and a circle opens the pad's XY as a sphere over it until the finger lifts
                    suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.follow() {
                        while (true) {
                            val e = awaitPointerEvent()
                            val c = e.changes.firstOrNull { it.id == down.id } ?: return
                            if (!c.pressed) { c.consume(); return }
                            lastPos = c.position
                            if ((c.position - down.position).getDistance() > slop) {
                                if (armed && !carry && state.project.kit[pi].isNotEmpty()) { carry = true; state.say("Drop it on another pad to swap their sounds.") }
                                moved = true; armed = false
                            }
                            val s0 = sph
                            if (s0 != null) {
                                lastXy = sphereXy(s0, state.xyOf(pi).four, origin.at + c.position)
                                state.xyMove(pi, lastXy.first, lastXy.second, true)
                            } else if (!carry && !state.keysMode && state.project.kit[pi].isNotEmpty() && det.add(c.position)) {
                                val cc = origin.at + det.center()
                                val s1 = AppState.Sphere(pi, Int.MIN_VALUE, cc.x, cc.y, xyDp(state.xyOf(pi).size).toPx() * .5f)
                                sph = s1
                                state.sphere = s1
                            }
                            c.consume()
                        }
                    }
                    val early = withTimeoutOrNull(450) { follow(); true }
                    if (early == null) {
                        // held still long enough: lift now for the pad's menu (like a right click). Not on a pad that
                        // sounds for as long as it is held (a synth, Sustain, gate): there a long press is playing
                        if (!moved && !state.keysMode && !state.locked && !state.soundsWhileHeld(pi)) armed = true
                        // the offer lasts until about 1.3 s after the touch; hold on longer and you are playing the
                        // pad, so the yellow outline goes and lifting opens nothing (and a drifting finger carries nothing)
                        val soon = withTimeoutOrNull(850) { follow(); true }
                        if (soon == null) { armed = false; follow() }
                    }
                    pressed = false
                    state.padUp(d)
                    sph?.let { s0 -> state.xyMove(pi, lastXy.first, lastXy.second, false); if (state.sphere == s0) state.sphere = null }
                    if (armed && !moved) state.padMenu = pi
                    if (carry) {
                        val at = origin.at + lastPos
                        val to = state.padRects.entries.firstOrNull { it.key != d && it.value.contains(at) }?.key
                        if (to != null) state.swapPads(pi, state.bank * Project.BANK + to)
                    }
                    armed = false
                }
            }
            .padding(8.dp),
    ) {
        T(top, size = 11.sp, color = if (empty) C.Empty else ink(fill).copy(alpha = .75f), modifier = Modifier.align(Alignment.TopStart))
        if (!keys && !empty && P.edits.containsKey(key)) T("✂", size = 12.sp, color = ink(fill), modifier = Modifier.align(Alignment.TopEnd))
        if (!keys && !empty && P.opt(i).route > 0) T("B${P.opt(i).route}", size = 10.sp, color = ink(fill), family = Mono, modifier = Modifier.align(Alignment.TopCenter))
        // held by Hold: an H in the corner, so you can see what is still playing on its own
        if (!keys && (1000 + i) in state.latched) T("H", size = 12.sp, color = ink(fill), family = Mono, weight = FontWeight.Black, modifier = Modifier.align(Alignment.BottomEnd))
        if (!keys) PadMenu(state, i)
        T(main, size = if (keys) 18.sp else padFont(main, state.dockOpen), color = if (empty) C.Empty else ink(fill), weight = FontWeight.Black, lines = 3, modifier = Modifier.align(Alignment.BottomStart))
    }
}

/**
 * The top of the Play tab: what the page shows. Pads and Keys are the pad grid's two modes, My Desk is
 * your own layout. FX opens the effects under the grid.
 */
@Composable
fun PlayViews(state: AppState) {
    val desk = state.tab == com.tideclock.messmachine.Tab.Console
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp, end = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Btn("Pads", { state.keysMode = false; state.tab = com.tideclock.messmachine.Tab.Pads }, on = !desk && !state.keysMode, small = true, tag = "mode-pads", modifier = Modifier.weight(1f))
        Btn("Keys", { state.keysMode = true; state.tab = com.tideclock.messmachine.Tab.Pads }, on = !desk && state.keysMode, onColor = C.Teal, small = true, tag = "mode-keys", modifier = Modifier.weight(1f))
        Btn("My Desk", { state.tab = com.tideclock.messmachine.Tab.Console }, on = desk, onColor = C.Teal, small = true, enabled = !state.locked, tag = "view-desk", modifier = Modifier.weight(1.4f))
        Btn("FX", { state.showFx = !state.showFx }, on = state.showFx && !desk, onColor = C.Pink, small = true, enabled = !desk, tag = "fx-toggle", modifier = Modifier.weight(.8f))
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
    DropdownMenu(expanded = open, onDismissRequest = { close() }, modifier = Modifier.background(C.Ink).border(3.dp, C.Cream).tagged("padmenu")) {
        T("PAD ${i + 1}" + if (k.isNotEmpty()) " · ${state.lib.titleOf(k).uppercase()}" else "", size = 12.sp, color = C.Yellow, lines = 1, spacing = .06.em,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
        @Composable
        fun item(label: String, tag: String, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
            DropdownMenuItem(
                text = { T(label.uppercase(), size = 13.sp, color = if (!enabled) C.Empty else if (danger) C.Riot else C.Cream, spacing = .06.em) },
                onClick = { close(); onClick() }, enabled = enabled, modifier = Modifier.tagged(tag),
            )
        }
        // over 8 items, so in groups (BT-1): playing it, the sound itself, then clears last
        @Composable
        fun group(name: String) = T(name, size = 10.sp, color = C.Dim, spacing = .12.em, modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 2.dp))
        val recHere = state.micOn && state.micPad == i
        if (k.isEmpty()) {
            item(if (recHere) "■ Stop recording" else "● Record onto this pad", "menu-record", enabled = !state.micOn || recHere) { state.askRecord(i) }
            item("Pick a sound", "menu-pick") { state.selectPad(i, false); state.overlay = Overlay.Picker(i) }
        } else {
            group("PLAY")
            item(if (i in state.keyboards) "🎹 Hide the Padiano" else "🎹 Padiano under this row", "menu-piano") { state.selectPad(i, false); state.toggleKeyboard(i) }
            item("Hear it", "menu-hear") { state.preview(k) }
            item(if (P.mutes.contains(k)) "Unmute" else "Mute", "menu-mute-$i") { state.toggleMute(k) }
            item(if (P.solos.contains(k)) "Unsolo" else "Solo", "menu-solo-$i") { state.toggleSolo(k) }
            item("XY setup (${state.xyOf(i).name})", "menu-xy") { state.selectPad(i, false); state.overlay = Overlay.Xy(i) }
            item("Route: " + (if (P.opt(i).route == 0) "mix" else "bus ${P.opt(i).route}") + " (tap for next)", "menu-route") { state.cycleRoute(i) }
            group("SOUND")
            item(if (recHere) "■ Stop recording" else "● Record onto this pad", "menu-record", enabled = !state.micOn || recHere) { state.askRecord(i) }
            if (com.tideclock.messmachine.engine.Project.isSynth(k)) item("◎ Synth", "menu-synth") { state.selectPad(i, false); state.padMenu = -1; state.tab = com.tideclock.messmachine.Tab.Synth }
            else item("✂ Sample", "menu-doctor") { state.selectPad(i, false); state.overlay = Overlay.Doctor(k) }
            item("Swap sound", "menu-swap") { state.selectPad(i, false); state.overlay = Overlay.Picker(i) }
            item(if (k in state.favs) "★ Unstar" else "☆ Star", "menu-star") { state.toggleFav(k) }
            item("Keep in Your Workbench", "menu-keep") { state.keepInBench(i) }
            item("Share this sound", "menu-share") { state.shareSoundWav(k) }
            group("CLEAR")
            if (P.pat().usedBy(k)) item("Clear it from loop ${com.tideclock.messmachine.engine.Project.LETTERS[P.cur]}", "menu-clearloop", danger = true) { state.clearSoundInLoop(i) }
            item("Clear pad", "menu-clear", danger = true) { state.clearPad(i) }
        }
    }
}

/** A pad's name size: smaller for long words, so a word never breaks in the middle. */
fun padFont(title: String, narrow: Boolean): androidx.compose.ui.unit.TextUnit {
    val longest = title.split(" ").maxOfOrNull { it.length } ?: 1
    val base = if (narrow) 10f else 11.5f
    return minOf(base, (if (narrow) 66f else 78f) / maxOf(1, longest)).coerceAtLeast(7f).sp
}

/** The shared look of a small coloured square used in lists. */
@Composable
fun Swatch(c: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(14.dp)) { drawRect(c); drawRect(C.Cream, style = Stroke(2.dp.toPx())) }
}
