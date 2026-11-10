package com.tideclock.messmachine.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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

/**
 * Your Console: a page built from modules. Edit to add, remove, move, resize, make half width, and
 * point pianos, filters and single effects at the pad or effect you want. Saved on the phone.
 */
@Composable
fun ConsoleScreen(state: AppState) {
    @Suppress("UNUSED_VARIABLE") val v = state.version
    @Suppress("UNUSED_VARIABLE") val sv = state.selV
    ScrollPane(tag = "console") {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
            Heading("Your console", size = 18.sp, modifier = Modifier.weight(1f))
            Btn(if (state.consoleEdit) "Done" else "Edit", { state.consoleEdit = !state.consoleEdit }, small = true,
                on = state.consoleEdit, onColor = C.Pink, enabled = !state.locked, tag = "console-edit")
        }
        if (state.consoleEdit) {
            Wrap(Modifier.padding(bottom = 12.dp)) {
                Select("+ Add", CONSOLE_TYPES, { state.addModule(it) }, color = C.Teal, modifier = Modifier.testTag("console-add"))
                Btn("Perform layout", { state.resetConsole() }, small = true, tag = "console-reset")
            }
            Note("Each piece: ▲ ▼ move it, S M L sets its size, ½ puts two side by side, ✕ takes it off. Everything still plays while you edit.",
                modifier = Modifier.padding(bottom = 12.dp))
        }
        if (state.console.isEmpty()) {
            Note("Nothing here yet. Tap Edit, then + Add.", modifier = Modifier.padding(vertical = 20.dp))
        }
        // half-width pieces pair up, side by side
        val mods = state.console.toList()
        var i = 0
        while (i < mods.size) {
            val m = mods[i]
            if (m.half) {
                val j = i + 1
                Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) { ModuleBox(state, i, m) }
                    if (j < mods.size && mods[j].half) Box(Modifier.weight(1f)) { ModuleBox(state, j, mods[j]) } else Spacer(Modifier.weight(1f))
                }
                i += if (j < mods.size && mods[j].half) 2 else 1
            } else {
                Box(Modifier.fillMaxWidth().padding(bottom = 14.dp)) { ModuleBox(state, i, m) }
                i++
            }
        }
    }
}

private fun needsPad(t: String) = t == "piano" || t == "pfilt"

@Composable
private fun ModuleBox(state: AppState, i: Int, m: Module) {
    Column(Modifier.fillMaxWidth().testTag("mod-$i")) {
        if (state.consoleEdit) ModuleControls(state, i, m)
        ModuleBody(state, m)
    }
}

@Composable
private fun ModuleControls(state: AppState, i: Int, m: Module) {
    val P = state.project
    val name = CONSOLE_TYPES.firstOrNull { it.first == m.type }?.second ?: m.type
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).sticker(C.Black, shadow = 0.dp, border = 2.dp, borderColor = C.Pink).padding(8.dp)) {
        T(name.uppercase(), size = 11.sp, color = C.Pink, lines = 1, spacing = .08.em)
        Wrap(Modifier.padding(top = 6.dp), gap = 6.dp) {
            Btn("▲", { state.moveModule(i, -1) }, small = true, enabled = i > 0, tag = "mod-up-$i")
            Btn("▼", { state.moveModule(i, 1) }, small = true, enabled = i < state.console.size - 1, tag = "mod-down-$i")
            Btn("SML"[m.size].toString(), { state.changeModule(i, m.copy(size = (m.size + 1) % 3)) }, small = true, color = C.Yellow, tag = "mod-size-$i")
            Btn(if (m.half) "½" else "Full", { state.changeModule(i, m.copy(half = !m.half)) }, small = true, tag = "mod-width-$i")
            if (needsPad(m.type)) {
                val opts = (0 until Project.NPADS).filter { P.kit[it].isNotEmpty() }.map { it to "Pad ${it + 1} · ${state.lib.titleOf(P.kit[it])}" }
                Select("Pad ${m.pad + 1}", opts, { state.changeModule(i, m.copy(pad = it)) }, modifier = Modifier.testTag("mod-pad-$i"))
            }
            if (m.type == "row") Select("Row ${m.pad + 1}", (0 until 4).map { it to "Row ${it + 1} (pads ${it * 4 + 1} to ${it * 4 + 4})" }, { state.changeModule(i, m.copy(pad = it)) })
            if (m.type == "fx1") Select(FX_NAMES.getOrNull(m.pad)?.second ?: "Effect", FX_NAMES.mapIndexed { k, f -> k to f.second }, { state.changeModule(i, m.copy(pad = it)) })
            Btn("✕", { state.removeModule(i) }, small = true, color = C.Riot, tag = "mod-x-$i")
        }
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
            val cols = if (m.half) 2 else 3
            Column {
                for (r in 0 until (FX_NAMES.size + cols - 1) / cols) {
                    Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (c in 0 until cols) {
                            val k = r * cols + c
                            if (k < FX_NAMES.size) {
                                val (id, label) = FX_NAMES[k]
                                HoldButton(label, Modifier.weight(1f).testTag("cfx-$id"), C.Letters[k % C.Letters.size], height = h) { on -> state.fx(id, on) }
                            } else Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        "fx1" -> {
            val (id, label) = FX_NAMES.getOrElse(m.pad) { FX_NAMES[0] }
            HoldButton(label, Modifier.fillMaxWidth().testTag("cfx1-$id"), C.Letters[m.pad.coerceAtLeast(0) % C.Letters.size], height = pick(m.size, 58.dp, 90.dp, 140.dp)) { on -> state.fx(id, on) }
        }
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
                        .clickable { state.selectSlot(k) }.testTag("cslot-$k"),
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
