package com.tideclock.messmachine.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
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
        // the bank numbers and the picked pad's sound. Its menu, Hold and chords are on the bottom dock, which stays on
        // screen however far down the page you are (build 84, Tyler's Nov 17 note)
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp, end = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.stepMode) BarButtons(state) else BankButtons(state)
        }
        if (state.levelsPad >= 0) Box(Modifier.fillMaxWidth().clickable { state.switchLevels() }.tagged("levels-banner")) {
            Note("16 LEVELS: " + state.lib.titleOf(state.project.kit.getOrElse(state.levelsPad) { "" }).uppercase() + " · TAP TO STOP", color = C.Yellow)
        }
        if (state.stepMode) Note("STEP MODE · " + state.lib.titleOf(state.selKey()).ifEmpty { "the picked pad" }.uppercase() + " · BAR ${state.page + 1}",
            color = C.Yellow, modifier = Modifier.padding(bottom = 8.dp))
        // Nov 22 sizing: the pads no longer shrink a second time with View size (the density already scales them);
        // they fill the width, up to 560 real dp, so a drum pad stays well over 64 real dp on any phone
        Box(Modifier.fillMaxWidth().widthIn(max = 560.rdp).align(Alignment.CenterHorizontally)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (r in 0 until 4) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (c in 0 until 4) if (state.stepMode) StepPad(state, r * 4 + c, Modifier.weight(1f)) else Pad(state, r * 4 + c, Modifier.weight(1f))
                    }
                    // an open FannyPack drops right here, under its pad's row (one per row)
                    if (!state.keysMode && !state.stepMode) for (pad in state.keyboards.toList()) {
                        if (pad / Project.BANK == state.bank && (pad % Project.BANK) / 4 == r) FannyPack(state, pad)
                    }
                }
            }
        }
        if (state.showFx) FxPanel(state)
        if (!state.keysMode && state.keyboards.isEmpty()) {
            Help(
                "Tap a pad to play it. On an empty pad, the top corner records and the bottom corner picks a sound. Edit, on the bar at the bottom, is the picked pad's menu. Pack opens its FannyPack under its row (one per row): the Padiano, live FX faders and XY, whichever you put in it. " +
                    "The key, by the bank numbers, sets the scale and chords. Repeat rolls whatever you hold, and with Rec on while playing, every hit lands in the loop.",
                modifier = Modifier.padding(top = 14.dp),
            )
        }
    }
}

/** Step mode's bar numbers in place of the banks: which bar of the loop the 16 pads show. */
@Composable
private fun RowScope.BarButtons(state: AppState) {
    val bars = state.project.pat().len / 16
    T("BAR", size = 11.sp, color = C.Yellow, spacing = .1.em)
    for (b in 0 until maxOf(1, minOf(bars, 8))) {
        Btn("${b + 1}", modifier = Modifier.weight(1f), onClick = { state.page = b }, on = state.page == b, small = true, onColor = C.Yellow, tag = "stepbar-$b")
    }
}

/** One step in step mode: lit when it plays, outlined when the loop is on it. */
@Composable
fun StepPad(state: AppState, d: Int, modifier: Modifier) {
    @Suppress("UNUSED_VARIABLE") val vv = state.version
    val P = state.project
    val k = state.selKey()
    val j = state.page * 16 + d
    val on = k.isNotEmpty() && P.pat().step(k, j)
    val col = if (k.isEmpty()) C.Card else padColor(Color(state.lib.colorOf(k)))
    val now = state.playing && state.heardPat == P.cur && state.heardStep == j
    Box(
        modifier
            .aspectRatio(1f)
            .tagged("steppad-$d")
            .drawBehind {
                val r = CornerRadius(7.dp.toPx())
                drawRoundRect(if (on) col else if (d % 4 == 0) C.Card2 else C.Card, cornerRadius = r)
                drawRoundRect(if (now) C.Yellow else if (on) C.Cream else C.Empty, cornerRadius = r, style = Stroke(width = (if (now) 5 else 2).dp.toPx()))
            }
            .pointerInput(d, k) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    state.stepPadTap(d)
                }
            }
            .padding(8.dp),
    ) {
        T("${j + 1}", size = 12.sp, color = if (on) ink(col) else C.Dim, family = Mono, modifier = Modifier.align(Alignment.TopStart))
    }
}

/** BANK and the four bank numbers, sharing whatever width the row has left. */
@Composable
private fun RowScope.BankButtons(state: AppState) {
    T("BANK", size = 11.sp, color = C.Dim, spacing = .1.em)
    for (b in 0 until 4) {
        // hold a bank number: the whole bank muted, or back in (Nov 21)
        Btn("${b + 1}", modifier = Modifier.weight(1f).holdFor(b) { state.toggleBankMute(b) }, onClick = { state.bank = b }, on = state.bank == b && !state.keysMode, small = true, enabled = !state.keysMode, tag = "bank-$b")
    }
}

/** One key on the bottom dock: its tag, when it goes into ⋯ (higher goes first, 0 never), its words, and how it draws. */
private class DockKey(val tag: String, val prio: Int, val words: List<String>, val body: @Composable (Modifier) -> Unit)

/**
 * The bottom dock (build 84, Tyler's Nov 17 note): Menu for the picked pad, Hold, Chords on or off and the key,
 * on a bar that stays put under the page while it scrolls. Shown on Play, My Desk and Synth. The key button opens
 * the key, chord and Hold settings just above it, so they never sit at the top of a page you have scrolled down.
 */
@Composable
fun BottomDock(state: AppState) {
    @Suppress("UNUSED_VARIABLE") val lv = state.liveV
    @Suppress("UNUSED_VARIABLE") val sv = state.selV
    @Suppress("UNUSED_VARIABLE") val vv = state.version
    val P = state.project
    Column(Modifier.fillMaxWidth().rail(state).drawBehind {
        drawLine(if (UiLook.panel) Hw.Brass else C.Teal, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 2.dp.toPx())
    }.tagged("bottomdock")) {
        if (state.keysBarOpen) KeysPanel(state)
        // Nov 22 sizing (Tyler: "build it"): every key in the bar is the same width, at least 44 real dp, sized for its
        // widest word, and never shrinks or scrolls. Keys that don't fit go into ⋯ at the end, Chords first, then Drop,
        // then Repeat; Edit never does. The key readout takes what is left.
        val ed = if (state.layout == "classic") "Menu" else "Edit"
        val keys = ArrayList<DockKey>()
        keys.add(DockKey("open-padmenu", 0, listOf(ed)) { m ->
            // Edit (Menu before build 107), then tap anything: its settings. Edit twice: what you touched last (Nov 18)
            Box(m, contentAlignment = Alignment.Center) {
                BarBtn(state, if (state.menuArmed) "$ed ●" else ed, ed, { state.menuTap() }, on = state.menuArmed || state.padMenu >= 0 || state.tray != null, onColor = C.Yellow,
                    enabled = !state.locked, tag = "open-padmenu", word = ed, modifier = Modifier.fillMaxWidth())
                if (state.padMenu >= 0) PadMenu(state, state.padMenu)
            }
        })
        // the picked pad's FannyPack: one tap zips it open under its row, or shut
        if (state.tab != com.tideclock.messmachine.Tab.Synth) keys.add(DockKey("pack-toggle", 2, listOf("Pack")) { m ->
            BarBtn(state, "Pack", "Pack", { state.togglePack(state.project.sel) }, on = state.project.sel in state.keyboards, onColor = C.Teal,
                enabled = !state.locked && !state.keysMode && state.project.kit.getOrNull(state.project.sel).orEmpty().isNotEmpty(), tag = "pack-toggle", word = "Pack", modifier = m)
        })
        keys.add(DockKey("hold-toggle", 1, listOf("Hold", "Last", "8/8")) { m ->
            BarBtn(state, if (!state.holdOn) "○ Hold" else if (state.holdCount == 0) "● Last" else "● ${state.latched.size}/${state.holdCount}", "Hold", { state.switchHold() },
                on = state.holdOn, onColor = C.Yellow, tag = "hold-toggle", word = if (!state.holdOn) "Hold" else if (state.holdCount == 0) "Last" else "${state.latched.size}/${state.holdCount}", modifier = m)
        })
        keys.add(DockKey("chords-toggle", 6, listOf("Chords")) { m ->
            BarBtn(state, if (state.chordMode == "off") "○ Chords" else "● Chords", "Chords", { state.toggleChords() }, on = state.chordMode != "off", onColor = C.Teal, tag = "chords-toggle", word = "Chords", modifier = m)
        })
        // the perform effects (stutter, echo throw, tape stop...), moved down from the view row (Nov 18)
        if (state.layout == "classic") { if (state.tab == com.tideclock.messmachine.Tab.Pads) keys.add(DockKey("fx-toggle", 4, listOf("FX")) { m ->
            BarBtn(state, "FX", "FX", { state.showFx = !state.showFx }, on = state.showFx, onColor = C.Pink, tag = "fx-toggle", word = "FX", modifier = m)
        }) }
        // build 107: Repeat sits beside Hold (the throws moved into the perform row); Edit, then Repeat, for its rate
        else keys.add(DockKey("repeat-toggle", 4, listOf("Repeat")) { m ->
            BarBtn(state, if (state.live.nr) "● Repeat" else "○ Repeat", "Repeat", { state.switchRepeat() }, on = state.live.nr, onColor = C.Pink, tag = "repeat-toggle", word = "Repeat", modifier = m)
        })
        // Nov 21 (second video): MUTE turns the pads into mute switches (hold it, or tap it to stay on); DROP, held,
        // leaves only the pads you touch
        keys.add(DockKey("mute-key", 3, listOf("Mute")) { m ->
            PressKey({ down, quick -> if (down) state.muteHeld = true else { state.muteHeld = false; if (quick) state.switchMuteMode() } }, m) {
                BarBtn(state, "Mute", "Mute", {}, on = state.muting, onColor = Hw.Oxblood, enabled = !state.locked, tag = "mute-key", word = "Mute", modifier = Modifier.fillMaxWidth())
            }
        })
        keys.add(DockKey("drop-key", 5, listOf("Drop")) { m ->
            PressKey({ down, _ -> state.drop(down) }, m) {
                BarBtn(state, "Drop", "Drop", {}, on = state.dropOn, onColor = C.Pink, enabled = !state.locked, tag = "drop-key", word = "Drop", modifier = Modifier.fillMaxWidth())
            }
        })
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp)) {
            val tm = androidx.compose.ui.text.rememberTextMeasurer()
            val dens = androidx.compose.ui.platform.LocalDensity.current
            val wordStyle = androidx.compose.ui.text.TextStyle(fontFamily = Block, fontWeight = FontWeight.Black, fontSize = (9.5f * UiLook.big).sp)
            val wordW = keys.maxOf { k -> k.words.maxOf { w -> with(dens) { tm.measure(w.uppercase(), wordStyle).size.width.toDp().value } } } * .86f + 10f
            val capH = ((UiLook.widthDp / 7f).coerceIn(48f, 96f) * UiLook.big * .72f).coerceIn(40f, 62f * UiLook.big)
            val base = maxOf(Floor.KEY.rdp.value, wordW, if (state.look == "panel") capH + 6f else 38f)
            val gap = 5f
            val plan = BarPlan.plan(keys.map { it.prio }, maxWidth.value, base, gap, base, Floor.READOUT.rdp.value)
            val over = plan.overflow.map { keys[it].tag }.toSet()
            androidx.compose.runtime.SideEffect { if (state.dockOverflow != over) state.dockOverflow = over }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap.dp)) {
                for (i in plan.shown) keys[i].body(Modifier.width(plan.slot.dp))
                if (plan.overflow.isNotEmpty()) Box(Modifier.width(base.dp), contentAlignment = Alignment.Center) {
                    BarBtn(state, "More", "More", { state.dockMoreOpen = true }, on = state.dockMoreOpen, onColor = C.Yellow, tag = "dock-more", word = "More", modifier = Modifier.fillMaxWidth())
                    DropdownMenu(expanded = state.dockMoreOpen, onDismissRequest = { state.dockMoreOpen = false }, modifier = Modifier.background(if (UiLook.panel) Hw.Plate else C.Ink).tagged("dock-more-menu")) {
                        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(gap.dp)) {
                            // a key in here closes the menu when the finger lifts (after it has done its job)
                            for (i in plan.overflow) Box(Modifier.pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                    waitForUpOrCancellation(androidx.compose.ui.input.pointer.PointerEventPass.Final)
                                    state.dockMoreOpen = false
                                }
                            }) { keys[i].body(Modifier.width(plan.slot.dp)) }
                        }
                    }
                }
            // Nov 21 fix: the chord shows only while Chords is on (it used to show the last one with Chords off)
            val keyText = "${Theory.NN[P.root]} ${P.scale.lowercase()}" + (if (state.chordMode != "off") " · " + chordName(state.chordMode).lowercase() else "") + if (state.keysBarOpen) " ▾" else " ▴"
            if (state.look == "classic") Btn(keyText, { state.switchKeysBar() }, small = true, tight = true, tier = Tier.Quiet, tag = "keysbar-open", modifier = Modifier.weight(1f))
            // Nov 21 video: in the panel Look the key reads out on an amber LCD like BPM and Loop (the pink key tile was left over)
            else if (UiLook.panel) Box(Modifier.weight(1f).padding(start = 4.dp, end = 2.dp).heightIn(min = 40.dp).lcd().clickable { state.switchKeysBar() }.tagged("keysbar-open")
                .padding(horizontal = 8.dp, vertical = 6.dp), contentAlignment = Alignment.CenterStart) {
                T(keyText.uppercase(), size = 13.sp, color = Hw.Amber, lines = 1, family = Lcd, weight = androidx.compose.ui.text.font.FontWeight.Normal)
            }
            else Row(Modifier.weight(1f).clickable { state.switchKeysBar() }.tagged("keysbar-open"), verticalAlignment = Alignment.CenterVertically) {
                PixIcon("Key and scale", 30.dp)
                T(keyText.uppercase(), size = 11.sp, color = railText(state), lines = 1, modifier = Modifier.padding(start = 6.dp))
            }
            }
        }
    }
}

/** Key, scale, chord type, play or show, and Hold's count: opened from the bottom dock's key button. */
@Composable
fun KeysPanel(state: AppState) {
    val P = state.project
    // clear of the rail's screws at the edges (the HOLD word sat on one)
    Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 8.dp).tagged("keys-panel")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("THE KEY", size = 11.sp, color = C.Yellow, spacing = .1.em, modifier = Modifier.weight(1f))
            CloseScrew({ state.switchKeysBar(false) }, tag = "keys-close", label = "Close the key")
        }
        Wrap(Modifier.padding(bottom = 4.dp)) {
            Select(Theory.NN[P.root], Theory.NN.mapIndexed { i, n -> i to n }, { state.setRoot(it) }, tag = "pick-root")
            Select(P.scale, Theory.SCALES.keys.map { it to it }, { state.setScale(it) }, tag = "pick-scale")
            Select(chordName(state.chordMode), chordOptions(P.scale, P.root), { state.pickChordMode(it) }, tag = "pick-chord")
            if (state.chordMode != "off") {
                Btn("Play chord", { state.switchChordPlay(true) }, on = state.chordPlay, small = true, onColor = C.Teal, tag = "chord-play")
                Btn("Show chord", { state.switchChordPlay(false) }, on = !state.chordPlay, small = true, onColor = C.Pink, tag = "chord-show")
                // Nov 22: Smooth (the default) picks the inversion closest to the last chord; Root plays every chord from its root
                Btn(if (state.chordSmooth) "Smooth moves" else "From the root", { state.switchChordSmooth(!state.chordSmooth) }, on = state.chordSmooth, small = true, onColor = C.Teal, tag = "chord-smooth")
            }
        }
        HoldBar(state)
        val readout = if (state.lastChord.isNotEmpty()) state.lastChord else "${Theory.NN[P.root]} ${P.scale.lowercase()}" +
            if (state.keysMode) " · the grid plays ${state.lib.titleOf(state.selKey()).ifEmpty { "the pad you picked" }}" else ""
        if (UiLook.panel) Box(Modifier.padding(top = 8.dp).lcd().padding(horizontal = 10.dp, vertical = 6.dp)) {
            T(readout.uppercase(), size = 14.sp, color = Hw.Amber, family = Lcd, weight = androidx.compose.ui.text.font.FontWeight.Normal)
        } else Note(readout, color = C.Yellow, size = 13.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

fun chordName(m: String) = when {
    m == "off" -> "Chords off"
    m == "triad" -> "Triads"
    m == "seventh" -> "7ths"
    m == "sus" -> "1-4-5"
    m == "octave" -> "Octaves"
    m == "power" -> "Power"
    m.startsWith("set:") -> m.substring(4)
    else -> m
}

fun chordOptions(scale: String, root: Int): List<Pair<String, String>> {
    val out = mutableListOf("off" to "Off", "triad" to "Triads from the scale", "seventh" to "7ths from the scale", "sus" to "1-4-5 from the scale (sus)", "power" to "Power chords", "octave" to "Octaves")
    // Nov 22: twelve chords a set, all in key; a set written in another scale moves the scale to it
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
    val origin = remember { Origin() }
    val view = androidx.compose.ui.platform.LocalView.current
    // the pad's own width in dp once it is laid out, so its name is sized to fit this pad on this screen
    val dens = androidx.compose.ui.platform.LocalDensity.current.density
    var padDp by remember { mutableStateOf(0f) }
    val keys = state.keysMode
    val i = state.bank * Project.BANK + d
    // build 108: 16 Levels shows one sound on every pad, darker for soft and brighter for hard
    val lv = !keys && state.levelsPad >= 0
    val key = if (keys) state.selKey() else if (lv) P.kit.getOrElse(state.levelsPad) { "" } else P.kit[i]
    val empty = key.isEmpty()
    val base = if (empty) C.Card else if (lv) lerp(C.Black, padColor(Color(state.lib.colorOf(key))), .3f + .7f * (d + 1) / 16f) else padColor(Color(state.lib.colorOf(key)))
    val sel = !keys && i == P.sel
    val hasKeys = !keys && i in state.keyboards
    val top: String
    val main: String
    if (keys) {
        val off = state.keyOffset(d)
        top = if (off == 0) "ROOT" else (if (off > 0) "+$off" else "$off")
        main = state.keyLabel(d)
    } else if (lv) {
        top = "LEVEL"
        main = "${d + 1}"
    } else {
        top = if (empty) "${i + 1} ●" else "${i + 1}"
        main = if (empty) "+" else state.lib.titleOf(key).uppercase()
    }
    val fill = if (pressed) lerp(base, C.Cream, .55f) else base
    // words on the pad: on the jar they take the pad's colour; on Specimen glass and the Stage body they are light
    val onPad = if (C.skin.pad == "jar" || C.skin.pad == "felt") ink(fill) else C.Cream
    val onTop = if (C.skin.pad == "band") ink(fill) else onPad
    // Nov 21: muted (or left out by Drop) pads go dark with a slash; in Mute mode every loaded pad gets a red rim
    val muted = !keys && !lv && !empty && !state.padAudible(i)
    val muteRim = !keys && !lv && !empty && state.muting
    Box(
        modifier
            .aspectRatio(1f)
            .scale(if (pressed) .93f else 1f)
            .drawWithContent {
                drawContent()
                if (muted) {
                    drawRect(Color.Black.copy(alpha = .6f))
                    drawLine(Hw.Oxblood, Offset(size.width * .12f, size.height * .88f), Offset(size.width * .88f, size.height * .12f), strokeWidth = 5.dp.toPx())
                }
                if (muteRim) drawRect(Hw.Oxblood, style = androidx.compose.ui.graphics.drawscope.Stroke(4.dp.toPx()))
            }
            .tagged("pad-$d")
            .drawBehind {
                val r = CornerRadius(7.dp.toPx())
                if (empty && state.micOn && state.micPad == i) {
                    drawRoundRect(C.Riot, cornerRadius = r)
                } else if (empty) {
                    drawRoundRect(C.Empty, cornerRadius = r, style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))))
                    // note #14: a slash splits an empty pad. Top left records onto it, bottom right picks a sound
                    if (!keys) {
                        if (pressed) drawRoundRect(C.Empty.copy(alpha = .25f), cornerRadius = r)
                        drawLine(C.Empty, Offset(size.width * .08f, size.height * .92f), Offset(size.width * .92f, size.height * .08f), strokeWidth = 2.dp.toPx())
                    }
                } else {
                    val sh = if (pressed) 0f else 4.dp.toPx()
                    when (C.skin.pad) {
                        // Specimen: dark glass with the sound's colour glowing up from inside
                        "glass" -> {
                            drawRoundRect(C.Black.copy(alpha = .7f), topLeft = Offset(sh * .6f, sh * .6f), size = size, cornerRadius = r)
                            drawRoundRect(lerp(fill, C.Ink, .78f), cornerRadius = r)
                            drawRoundRect(Brush.radialGradient(listOf(fill.copy(alpha = if (pressed) .95f else .7f), fill.copy(alpha = 0f)),
                                center = Offset(size.width * .5f, size.height * .62f), radius = size.minDimension * .62f), cornerRadius = r)
                            drawRoundRect(Color.White.copy(alpha = .08f), topLeft = Offset(4.dp.toPx(), 3.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width - 8.dp.toPx(), size.height * .34f), cornerRadius = CornerRadius(5.dp.toPx()))
                        }
                        // Stage: black, with a thick band of the sound's colour on top, readable from across a room
                        "band" -> {
                            drawRoundRect(if (pressed) fill.copy(alpha = .35f) else C.Paper, cornerRadius = r)
                            drawRoundRect(fill, size = androidx.compose.ui.geometry.Size(size.width, size.height * .22f), cornerRadius = r)
                            drawRect(fill, topLeft = Offset(0f, size.height * .11f), size = androidx.compose.ui.geometry.Size(size.width, size.height * .11f))
                            // an edge in the sound's colour, or the pad vanishes into the black page
                            drawRoundRect(fill.copy(alpha = .7f), cornerRadius = r, style = Stroke(width = 2.dp.toPx()))
                        }
                        // Felt and Paper: a flat felt square with a soft drop and a stitched edge
                        "felt" -> {
                            drawRoundRect(C.Black.copy(alpha = .22f), topLeft = Offset(sh * .5f, sh * .7f), size = size, cornerRadius = r)
                            drawRoundRect(fill, cornerRadius = r)
                            val inset = 4.dp.toPx()
                            drawRoundRect(Color.White.copy(alpha = .35f), topLeft = Offset(inset, inset), size = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2),
                                cornerRadius = CornerRadius(4.dp.toPx()), style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))))
                        }
                        else -> {
                            drawRoundRect(if (sel) C.Pink else C.Black, topLeft = Offset(sh, sh), size = size, cornerRadius = r)
                            drawRoundRect(Brush.verticalGradient(listOf(lerp(fill, C.Cream, .12f), lerp(fill, C.Ink, .18f))), cornerRadius = r)
                            // a hard highlight along the top, the "oomph"
                            drawRoundRect(Color.White.copy(alpha = .16f), topLeft = Offset(5.dp.toPx(), 4.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width - 10.dp.toPx(), 4.dp.toPx()), cornerRadius = CornerRadius(2.dp.toPx()))
                        }
                    }
                    if (pressed) drawRoundRect(C.Pink, cornerRadius = r, style = Stroke(width = 6.dp.toPx()))
                    if (state.swapFrom == i) drawRoundRect(C.Yellow, cornerRadius = r, style = Stroke(width = 5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 8f))))
                    if (!hasKeys && P.opt(i).pack.isNotEmpty()) {
                        // something is packed on this pad (zipped shut): a little zipper along its bottom edge
                        drawLine(C.Yellow.copy(alpha = .8f), Offset(10.dp.toPx(), size.height - 5.dp.toPx()), Offset(size.width * .45f, size.height - 5.dp.toPx()),
                            strokeWidth = 3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 6f)))
                    }
                    if (!empty && !keys) {
                        // build 107: what moves this pad, one dot each down its right edge: yellow, a wave (LFO);
                        // teal, a lane in this loop; red, an FX fader resting above zero
                        val o = P.opt(i)
                        val rr = 3.5.dp.toPx(); val x = size.width - 8.dp.toPx(); var y = size.height * .36f
                        fun mark(c: Color) { drawCircle(C.Black, rr + 1.5.dp.toPx(), Offset(x, y)); drawCircle(c, rr, Offset(x, y)); y += 11.dp.toPx() }
                        if (o.lfoOn) mark(C.Yellow)
                        if (P.pat().motion.keys.any { it.startsWith("$i:") }) mark(C.Teal)
                        if (o.fx.isNotEmpty()) mark(C.Riot)
                    }
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
            .onGloballyPositioned { state.padRects[d] = androidx.compose.ui.geometry.Rect(it.positionInRoot(), it.size.toSize()); padDp = it.size.width / dens }
            .pointerInput(d) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // a pad you play never scrolls the page: its touches are used up here
                    down.consume()
                    pressed = true
                    state.noteTouch(down.uptimeMillis)
                    // on an empty pad, which side of the slash: clearly above it records, the rest picks a sound (note #14).
                    // A touch on the slash itself picks: a stray tap should never start the mic
                    val top = down.position.y < size.height * (.92f - down.position.x / size.width.coerceAtLeast(1))
                    // Nov 21 fix: an empty pad acts on lift, and only for a still tap, so a brushed or scrolled pad never
                    // starts the mic or throws the picker over the page (hold-to-record and stopping a take stay on touch)
                    val deferEmpty = state.emptyActsOnLift(d)
                    var movedFar = false
                    if (!deferEmpty) state.padDown(d, emptyTop = top)
                    if (state.buzz && state.project.kit[state.bank * Project.BANK + d].isNotEmpty()) view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    val pi = state.bank * Project.BANK + d
                    val det = CircleDetector(40.dp.toPx())
                    var sph: AppState.Sphere? = null
                    var lastXy = 0f to 0f
                    // a hold is always playing now (build 84): the pad's menu is Menu on the bottom dock. A circle
                    // still opens the pad's XY as a sphere over it until the finger lifts
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) movedFar = true
                        if (!c.pressed) { c.consume(); break }
                        val s0 = sph
                        if (s0 != null) {
                            lastXy = sphereXy(s0, state.xyOf(pi).four, origin.at + c.position)
                            state.xyMove(pi, lastXy.first, lastXy.second, true)
                        } else if (!state.keysMode && state.project.kit[pi].isNotEmpty() && det.add(c.position)) {
                            val cc = origin.at + det.center()
                            val s1 = AppState.Sphere(pi, Int.MIN_VALUE, cc.x, cc.y, xyDp(state.xyOf(pi).size).toPx() * .5f)
                            sph = s1
                            state.sphere = s1
                        }
                        c.consume()
                    }
                    pressed = false
                    if (deferEmpty) { if (!movedFar) state.padDown(d, emptyTop = top) }
                    else state.padUp(d)
                    sph?.let { s0 -> state.xyMove(pi, lastXy.first, lastXy.second, false); if (state.sphere == s0) state.sphere = null }
                }
            }
            .padding(8.dp),
    ) {
        T(top, size = 11.sp, color = if (empty) C.Empty else onTop.copy(alpha = .75f), modifier = Modifier.align(Alignment.TopStart))
        if (state.midiMapOn) {
            // the MIDI map is up: the controller key on this pad, and a ? on the pad the next key lands on
            val here = com.tideclock.messmachine.MidiTarget("pad", d)
            val txt = if (state.midiCursor == here) "♪ ?" else state.midiMappedTo(here).let { if (it.isEmpty()) "" else "♪ $it" }
            if (txt.isNotEmpty()) T(txt, size = 11.sp, color = if (empty) C.Yellow else onTop, family = Mono, weight = FontWeight.Black, modifier = Modifier.align(Alignment.TopEnd))
        } else if (!keys && !empty && P.edits.containsKey(key)) T("✂", size = 12.sp, color = onTop, modifier = Modifier.align(Alignment.TopEnd))
        if (!keys && !empty && P.opt(i).route > 0) T("B${P.opt(i).route}", size = 10.sp, color = onTop, family = Mono, modifier = Modifier.align(Alignment.TopCenter))
        // held by Hold: an H in the corner, so you can see what is still playing on its own
        if (!keys && (1000 + i) in state.latched) T("H", size = 12.sp, color = onPad, family = Mono, weight = FontWeight.Black, modifier = Modifier.align(Alignment.BottomEnd))
        if (empty && !keys) T("+", size = 22.sp, color = C.Empty, weight = FontWeight.Black, modifier = Modifier.align(Alignment.BottomEnd).tagged("pad-pick-$d"))
        // Nov 21 video: with a sheet over the bottom of the grid (the key, a tray) the names go to the top, where they still show
        else T(main, size = if (keys) 18.sp else padFont(main, state.dockOpen, padDp), color = if (empty) C.Empty else onPad, weight = FontWeight.Black, lines = 3,
            modifier = if (!keys && !empty && (state.keysBarOpen || state.tray != null)) Modifier.align(Alignment.TopStart).padding(top = 18.dp) else Modifier.align(Alignment.BottomStart))
    }
}

/**
 * The top of the Play tab: what the page shows. Pads and Keys are the pad grid's two modes, My Desk is
 * your own layout. FX opens the effects under the grid.
 */
@Composable
fun PlayViews(state: AppState) {
    // Nov 18 (Tyler): the view row is gone. Step and Keys modes are retired (the Padiano's Full size has Keys mode's big
    // targets), FX moved to the bottom bar, and My Desk is a layout picked in Settings, next to View size.
    if (state.keysMode || state.stepMode) { state.keysMode = false; state.stepMode = false }
}


/** Hold: one tap on or off. The first N notes you play keep sounding; press one again to let it go. */
@Composable
fun HoldBar(state: AppState, modifier: Modifier = Modifier) {
    // Nov 22 (second video, Tyler: "we just really need these three things"): how many, and Let go, in one short row
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Btn("−", { state.changeHoldCount(state.holdCount - 1) }, small = true, tight = true, enabled = state.holdCount > 0, tag = "hold-down", modifier = Modifier.widthIn(min = 44.dp))
        Box(Modifier.size(width = 64.dp, height = if (UiLook.panel) 42.dp else 34.dp).then(if (UiLook.panel) Modifier.lcd() else Modifier.background(C.Well).border(2.dp, if (state.holdOn) C.Yellow else C.Cream)).tagged("hold-count"), contentAlignment = Alignment.Center) {
            val n = if (state.holdCount == 0) "LAST" else "${state.holdCount}"
            T(if (state.holdOn && state.holdCount > 0) "${state.latched.size}/$n" else n, size = if (UiLook.panel) 16.sp else 13.sp,
                color = if (UiLook.panel) Hw.Amber.copy(alpha = if (state.holdOn) 1f else .6f) else if (state.holdOn) C.Yellow else C.Dim,
                family = if (UiLook.panel) Lcd else Mono, weight = if (UiLook.panel) FontWeight.Normal else FontWeight.Bold, lines = 1)
        }
        Btn("+", { state.changeHoldCount(state.holdCount + 1) }, small = true, tight = true, enabled = state.holdCount < 8, tag = "hold-up", modifier = Modifier.widthIn(min = 44.dp))
        Btn("Let go", { state.releaseHolds() }, small = true, tight = true, color = C.Riot, enabled = state.latched.isNotEmpty(), tag = "hold-release")
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
            item(if (i in state.keyboards) "Zip the FannyPack" else if (state.packOf(i).isEmpty()) "FannyPack: Padiano under this row" else "Open the FannyPack (${state.packOf(i).size})", "menu-piano") { state.selectPad(i, false); state.togglePack(i) }
            item("Hear it", "menu-hear") { state.preview(k) }
            item(if (state.levelsPad == i) "16 Levels: off" else "16 Levels: play it soft to hard", "menu-levels") { state.switchLevels(i) }
            item(if (P.mutes.contains(k)) "Unmute" else "Mute", "menu-mute-$i") { state.toggleMute(k) }
            item(if (P.solos.contains(k)) "Unsolo" else "Solo", "menu-solo-$i") { state.toggleSolo(k) }
            item("XY setup (${state.xyOf(i).name})", "menu-xy") { state.selectPad(i, false); state.overlay = Overlay.Xy(i) }
            item("Route: " + (if (P.opt(i).route == 0) "mix" else "bus ${P.opt(i).route}") + " (tap for next)", "menu-route") { state.cycleRoute(i) }
            group("SOUND")
            item(if (recHere) "■ Stop recording" else "● Record onto this pad", "menu-record", enabled = !state.micOn || recHere) { state.askRecord(i) }
            if (com.tideclock.messmachine.engine.Project.isSynth(k)) item("◎ Synth", "menu-synth") { state.selectPad(i, false); state.padMenu = -1; state.tab = com.tideclock.messmachine.Tab.Synth }
            else item("✂ Sample", "menu-doctor") { state.selectPad(i, false); state.overlay = Overlay.Doctor(k) }
            item("Swap sound", "menu-swap") { state.selectPad(i, false); state.overlay = Overlay.Picker(i) }
            item("Move to another pad", "menu-move") { state.armSwap(i) }
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
fun padFont(title: String, narrow: Boolean, padDp: Float = 0f): androidx.compose.ui.unit.TextUnit {
    val longest = title.split(" ").maxOfOrNull { it.length } ?: 1
    if (padDp > 0f) {
        // sized from the pad itself: a small phone's pads are narrower than the guess below (words broke in the
        // middle there), and a Fold opened has room for bigger names
        val room = padDp - 16f
        val cap = (room / 6f).coerceIn(11.5f, 15f)
        return minOf(cap, room * 1.13f / maxOf(1, longest)).coerceAtLeast(7f).sp
    }
    val base = if (narrow) 10f else 11.5f
    return minOf(base, (if (narrow) 66f else 78f) / maxOf(1, longest)).coerceAtLeast(7f).sp
}

/** The shared look of a small coloured square used in lists. */
@Composable
fun Swatch(c: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(14.dp)) { drawRect(c); drawRect(C.Cream, style = Stroke(2.dp.toPx())) }
}


/** A key that knows when it goes down and comes up: quick is a tap under 300 ms. The key inside still draws as pressed. */
@Composable
fun PressKey(onPress: (down: Boolean, quick: Boolean) -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val cb by androidx.compose.runtime.rememberUpdatedState(onPress)
    Box(modifier.pointerInput(Unit) {
        awaitEachGesture {
            val d = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
            cb(true, false)
            val up = waitForUpOrCancellation(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
            cb(false, up != null && up.uptimeMillis - d.uptimeMillis < 300)
        }
    }, contentAlignment = Alignment.Center) { content() }
}

/** Hold for half a second: onHold (the tap underneath still happens when the finger lifts). */
fun Modifier.holdFor(key: Any?, ms: Long = 500, onHold: () -> Unit): Modifier = this.pointerInput(key) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
        val up = withTimeoutOrNull(ms) { waitForUpOrCancellation(androidx.compose.ui.input.pointer.PointerEventPass.Initial) }
        if (up == null) onHold()
    }
}
