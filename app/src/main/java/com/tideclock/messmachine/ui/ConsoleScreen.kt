package com.tideclock.messmachine.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tideclock.messmachine.AppState
import com.tideclock.messmachine.CONSOLE_TYPES
import com.tideclock.messmachine.FX_NAMES
import com.tideclock.messmachine.Module
import com.tideclock.messmachine.Overlay
import com.tideclock.messmachine.engine.Project
import com.tideclock.messmachine.engine.Theory
import kotlin.math.roundToInt

/**
 * M(ess)(y) Desk: a page you lay out yourself. Arrange to drag pieces anywhere on a grid, resize them
 * from the corner, let them overlap, pin them down. Several layouts, switched here or from the dock.
 */
@Composable
fun ConsoleScreen(state: AppState) {
    @Suppress("UNUSED_VARIABLE") val v = state.version
    @Suppress("UNUSED_VARIABLE") val sv = state.selV
    val arranging = state.consoleEdit && !state.locked
    ScrollPane(tag = "console") {
        PlayViews(state)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            Heading("M(ess)(y) Desk", size = 18.sp, modifier = Modifier.weight(1f))
            Btn(if (arranging) "Done" else "Arrange", { state.consoleEdit = !state.consoleEdit }, small = true,
                on = arranging, onColor = C.Pink, enabled = !state.locked, tag = "console-edit")
        }
        Wrap(Modifier.padding(bottom = 10.dp), gap = 6.dp) {
            for ((k, d) in state.desks.withIndex()) Btn(d.name, { state.switchDesk(k) }, on = k == state.deskIdx, onColor = C.Teal, small = true, enabled = !state.locked, tag = "desk-$k")
            if (arranging) Btn("+ New", { state.newDesk() }, small = true, tag = "desk-new")
        }
        if (arranging) {
            Wrap(Modifier.padding(bottom = 10.dp), gap = 6.dp) {
                Select("+ Add", CONSOLE_TYPES, { state.addModule(it) }, color = C.Teal, modifier = Modifier.tagged("console-add"))
                Btn("Start over", { state.resetConsole() }, small = true, tag = "console-reset")
            }
            // this desk: its name, a saved copy, or throw it away
            var deskName by remember(state.deskIdx) { mutableStateOf(state.desks.getOrNull(state.deskIdx)?.name ?: "") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = deskName, onValueChange = { deskName = it.take(24); state.renameDesk(deskName) }, singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = C.Cream, fontFamily = Archivo, fontSize = 14.sp),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(focusedBorderColor = C.Pink, unfocusedBorderColor = C.Card2, cursorColor = C.Pink),
                    modifier = Modifier.weight(1f).tagged("desk-name"),
                )
                Btn("Save a copy", { state.copyDesk() }, small = true, tag = "desk-copy")
                Btn("Delete", { state.deleteDesk() }, small = true, enabled = state.desks.size > 1, tier = Tier.Danger, tag = "desk-delete")
            }
            Help("Drag a piece to move it, drag its pink corner to resize it. 📌 pins it in place, ⚙ picks its pad and size, ✕ takes it off. Pieces can overlap; the one you touched last sits on top.",
                modifier = Modifier.padding(bottom = 10.dp))
        }
        if (state.console.isEmpty()) Note("Nothing here yet. Tap Arrange, then + Add.", modifier = Modifier.padding(vertical = 20.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().padding(end = 4.dp)) {
            val col = maxWidth / 12
            val unit = 24.dp
            val rows = (state.console.maxOfOrNull { it.y + it.h } ?: 0) + if (arranging) 8 else 1
            Box(Modifier.fillMaxWidth().height(unit * rows).tagged("desk-grid")) {
                if (arranging) Canvas(Modifier.matchParentSize()) {
                    val cw = size.width / 12; val rh = unit.toPx()
                    for (c in 1 until 12) drawLine(C.Card2, Offset(c * cw, 0f), Offset(c * cw, size.height), strokeWidth = 1f)
                    var y = rh
                    while (y < size.height) { drawLine(C.Card2, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f); y += rh }
                }
                for ((i, m) in state.console.toList().withIndex()) key(m.type, m.pad, m.x, m.y, i) { DeskPiece(state, i, m, col, unit, arranging) }
            }
        }
    }
}

/** One piece on the desk: its content, and in Arrange mode a frame to drag, resize, pin and remove it. */
@Composable
private fun DeskPiece(state: AppState, i: Int, m: Module, col: Dp, unit: Dp, arranging: Boolean) {
    var dx by remember { mutableStateOf(0f) }
    var dy by remember { mutableStateOf(0f) }
    var dw by remember { mutableStateOf(0f) }
    var dh by remember { mutableStateOf(0f) }
    var gear by remember { mutableStateOf(false) }
    val dens = LocalDensity.current
    val colPx = with(dens) { col.toPx() }
    val unitPx = with(dens) { unit.toPx() }
    val name = CONSOLE_TYPES.firstOrNull { it.first == m.type }?.second ?: m.type
    Box(
        Modifier
            .offset { IntOffset((m.x * colPx + dx).roundToInt(), (m.y * unitPx + dy).roundToInt()) }
            .size(width = with(dens) { (m.w * colPx + dw).coerceAtLeast(colPx * 2).toDp() }, height = with(dens) { (m.h * unitPx + dh).coerceAtLeast(unitPx * 2).toDp() })
            .padding(3.dp)
            .tagged("mod-$i"),
    ) {
        // no scrolling inside a piece (a scroll inside a scrolling page fights your thumb): resize it instead
        // measured at full height and clipped (note #8: squeezing a piece's content below zero crashed)
        Box(Modifier.fillMaxSize().clipToBounds()) {
            Column(Modifier.fillMaxWidth().wrapContentHeight(align = Alignment.Top, unbounded = true)) { ModuleBody(state, m) }
        }
        if (arranging) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(C.Black.copy(alpha = .45f))
                    .border(2.dp, if (m.pinned) C.Yellow else C.Pink)
                    .pointerInput(i, m) {
                        if (m.pinned) return@pointerInput
                        detectDragGestures(
                            onDrag = { c, d -> c.consume(); dx += d.x; dy += d.y },
                            onDragEnd = {
                                val nx = ((m.x * colPx + dx) / colPx).roundToInt()
                                val ny = ((m.y * unitPx + dy) / unitPx).roundToInt()
                                dx = 0f; dy = 0f
                                state.placeModule(i, nx, ny)
                            },
                            onDragCancel = { dx = 0f; dy = 0f },
                        )
                    }
                    .tagged("mod-drag-$i"),
            ) {
                Row(Modifier.fillMaxWidth().background(C.Ink.copy(alpha = .9f)).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    T(name.uppercase(), size = 10.sp, color = if (m.pinned) C.Yellow else C.Pink, lines = 1, spacing = .06.em, modifier = Modifier.weight(1f))
                    Box {
                        T("⚙", size = 14.sp, modifier = Modifier.clickable { gear = true }.padding(horizontal = 6.dp).tagged("mod-gear-$i"))
                        DropdownMenu(expanded = gear, onDismissRequest = { gear = false }, modifier = Modifier.background(C.Ink)) {
                            ModuleControls(state, i, m)
                        }
                    }
                    T(if (m.pinned) "📌" else "📍", size = 13.sp, modifier = Modifier.clickable { state.pinModule(i) }.padding(horizontal = 6.dp).tagged("mod-pin-$i"))
                    T("✕", size = 13.sp, color = C.Riot, modifier = Modifier.clickable { state.removeModule(i) }.padding(horizontal = 6.dp).tagged("mod-x-$i"))
                }
                if (!m.pinned) Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(30.dp)
                        .background(C.Pink)
                        .pointerInput(i, m) {
                            detectDragGestures(
                                onDrag = { c, d -> c.consume(); dw += d.x; dh += d.y },
                                onDragEnd = {
                                    val nw = ((m.w * colPx + dw) / colPx).roundToInt()
                                    val nh = ((m.h * unitPx + dh) / unitPx).roundToInt()
                                    dw = 0f; dh = 0f
                                    state.resizeModule(i, nw, nh)
                                },
                                onDragCancel = { dw = 0f; dh = 0f },
                            )
                        }
                        .tagged("mod-size-$i"),
                    contentAlignment = Alignment.Center,
                ) { T("◢", size = 14.sp, color = C.Ink) }
            }
        }
    }
}

private fun needsPad(t: String) = t == "piano" || t == "pfilt" || t == "xyp"

/** A piece's settings: its pad, row or effect, and its size. */
@Composable
private fun ModuleControls(state: AppState, i: Int, m: Module) {
    val P = state.project
    Column(Modifier.padding(10.dp).widthIn(min = 220.dp)) {
        T("SIZE INSIDE", size = 10.sp, color = C.Dim, spacing = .08.em)
        Wrap(Modifier.padding(top = 4.dp, bottom = 8.dp), gap = 6.dp) {
            for (z in 0..2) Btn("SML"[z].toString(), { state.changeModule(i, m.copy(size = z)) }, on = m.size == z, small = true)
        }
        if (needsPad(m.type)) {
            val opts = (0 until Project.NPADS).filter { P.kit[it].isNotEmpty() }.map { it to "Pad ${it + 1} · ${state.lib.titleOf(P.kit[it])}" }
            Select("Pad ${m.pad + 1}", opts, { state.changeModule(i, m.copy(pad = it)) }, modifier = Modifier.tagged("mod-pad-$i"))
        }
        if (m.type == "row") Select("Row ${m.pad + 1}", (0 until 4).map { it to "Row ${it + 1} (pads ${it * 4 + 1} to ${it * 4 + 4})" }, { state.changeModule(i, m.copy(pad = it)) })
        if (m.type == "fx1") Select(FX_NAMES.getOrNull(m.pad)?.second ?: "Effect", FX_NAMES.mapIndexed { k, f -> k to f.second }, { state.changeModule(i, m.copy(pad = it)) })
    }
}

private fun pick(size: Int, s: Dp, m: Dp, l: Dp) = when (size) { 0 -> s; 2 -> l; else -> m }

@Composable
private fun ModuleBody(state: AppState, m: Module) {
    val P = state.project
    when (m.type) {
        "transport" -> TransportModule(state)
        "pads" -> Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (b in 0 until 4) Btn("${b + 1}", { state.bank = b }, modifier = Modifier.weight(1f), on = state.bank == b, small = true)
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = pick(m.size, 300.dp, 440.dp, 600.dp)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (r in 0 until 4) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { for (c in 0 until 4) Pad(state, r * 4 + c, Modifier.weight(1f)) }
                }
            }
        }
        "row" -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Row(Modifier.widthIn(max = pick(m.size, 300.dp, 440.dp, 600.dp)), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (c in 0 until 4) Pad(state, m.pad.coerceIn(0, 3) * 4 + c, Modifier.weight(1f))
            }
        }
        "piano" -> if (m.pad in 0 until Project.NPADS && P.kit[m.pad].isNotEmpty()) {
            PianoKeyboard(state, m.pad, keysHeight = pick(m.size, 92.dp, 132.dp, 184.dp), filter = false, close = false)
        } else EmptyModule("Pad ${m.pad + 1} is empty. Edit to point this Padiano at another pad.")
        "pfilt" -> PadFilterStrip(state, m.pad, height = pick(m.size, 42.dp, 64.dp, 110.dp))
        "hold" -> HoldBar(state, Modifier.padding(top = 0.dp))
        "fx" -> {
            val h = pick(m.size, 44.dp, 58.dp, 80.dp)
            val cols = if (m.w < 9) 2 else 3
            Column {
                FxEditor(state)
                for (r in 0 until (FX_NAMES.size + cols - 1) / cols) {
                    Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (c in 0 until cols) {
                            val k = r * cols + c
                            if (k < FX_NAMES.size) {
                                val (id, label) = FX_NAMES[k]
                                FxButton(state, id, label, Modifier.weight(1f).tagged("cfx-$id"), C.Letters[k % C.Letters.size], height = h)
                            } else Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        "fx1" -> {
            val (id, label) = FX_NAMES.getOrElse(m.pad) { FX_NAMES[0] }
            FxButton(state, id, label, Modifier.fillMaxWidth().tagged("cfx1-$id"), C.Letters[m.pad.coerceAtLeast(0) % C.Letters.size], height = pick(m.size, 58.dp, 90.dp, 140.dp))
        }
        "xyp" -> if (m.pad in 0 until Project.NPADS && P.kit[m.pad].isNotEmpty()) PadXy(state, m.pad, tag = "cxyp-${m.pad}", edge = pick(m.size, 130.dp, 190.dp, 260.dp))
            else EmptyModule("Pad ${m.pad + 1} is empty. Edit to point this XY at another pad.")
        "xy" -> Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                T(if (state.xyHP) "THIN" else "MUD", size = 12.sp, color = C.Yellow, spacing = .1.em, modifier = Modifier.weight(1f))
                Btn("LP", { state.switchXyHP(false) }, on = !state.xyHP, small = true, onColor = C.Teal)
                Btn("HP", { state.switchXyHP(true) }, on = state.xyHP, small = true, onColor = C.Teal)
            }
            XyPad(state, height = pick(m.size, 110.dp, 180.dp, 260.dp))
        }
        "repeat" -> Wrap(gap = 6.dp) { RepeatControls(state, tag = "crepeat") }
        "loops" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            for (k in 0 until Project.NSLOTS) {
                val on = k == P.cur
                val used = P.pats[k].used()
                val here = state.playing && state.heardPat == k
                Box(
                    Modifier.weight(1f).height(pick(m.size, 34.dp, 42.dp, 56.dp))
                        .sticker(if (on) C.Yellow else C.Ink, shadow = if (on) 1.dp else 3.dp, border = if (here) 4.dp else 2.5.dp, borderColor = if (here) C.Pink else C.Cream)
                        .clickable { state.selectSlot(k) }.tagged("cslot-$k"),
                    contentAlignment = Alignment.Center,
                ) { T(Project.LETTERS[k].toString(), size = 14.sp, weight = FontWeight.Black, color = if (on) C.Ink else if (used) C.Cream else C.Empty) }
            }
        }
        "scale" -> Wrap(gap = 6.dp) {
            Select(Theory.NN[P.root], Theory.NN.mapIndexed { k, n -> k to n }, { state.setRoot(it) })
            Select(P.scale, Theory.SCALES.keys.map { it to it }, { state.setScale(it) })
            Select(chordName(state.chordMode), chordOptions(P.scale, P.root), { state.pickChordMode(it) })
            if (state.chordMode != "off") {
                Btn("Play", { state.switchChordPlay(true) }, on = state.chordPlay, small = true, onColor = C.Teal)
                Btn("Show", { state.switchChordPlay(false) }, on = !state.chordPlay, small = true, onColor = C.Pink)
            }
        }
        "beat" -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BeatLight(state, beatPulse(state))
            PositionText(state)
        }
        else -> EmptyModule("Unknown piece.")
    }
}

@Composable
private fun EmptyModule(msg: String) {
    Box(Modifier.fillMaxWidth().sticker(C.Ink, shadow = 0.dp, border = 2.dp, borderColor = C.Empty).padding(14.dp)) { Note(msg) }
}

@Composable
private fun TransportModule(state: AppState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(end = 4.dp)) {
        Btn(if (state.playing) "❚❚" else "▶ Play", { state.togglePlay() }, color = C.Teal, on = state.playing, onColor = C.Yellow, tag = "cplay", modifier = Modifier.weight(1.2f))
        Btn("■", { state.stop() }, modifier = Modifier.weight(.6f))
        RecBtn(state, Modifier.weight(1f))
        Btn("${state.project.bpm} BPM", { state.overlay = Overlay.Tempo }, modifier = Modifier.weight(1.1f))
    }
}
