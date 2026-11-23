package com.tideclock.messmachine.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tideclock.messmachine.AppState
import com.tideclock.messmachine.engine.Project
import com.tideclock.messmachine.engine.Synth
import com.tideclock.messmachine.engine.Theory

/**
 * The Synth tab (SE-11): the synth on the selected pad, or the scratch synth (Project.SCRATCH, a 65th pad in no
 * bank) when that pad has none. Simple shows the knobs that matter most, Full every knob in groups (Synthipad is
 * gone, Nov 22). The tile on the left is the synth's pad: tap to play it, swipe it like a grid pad
 * (HotKeys), the same swipe again closes what it opened. The keyboard is the Padiano, the same one as on the pads.
 */
@Composable
fun SynthScreen(state: AppState) {
    @Suppress("UNUSED_VARIABLE") val v = state.version
    val P = state.project
    val sel = P.sel
    val scratch = state.synthScratch || state.synthOn(sel) == null
    val pad = if (scratch) Project.SCRATCH else sel
    val sp = state.synthOn(pad) ?: return
    ScrollPane(hPad = 12.dp, vPad = 0.dp) {
        // Nov 22 (second video, Tyler: "a ludicrous amount of space at the top"): the synth's pad plays it, and beside it
        // two short rows: which synth and its preset share one, Simple or Full and the mode slide under them
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SynthPadTile(state, pad, sp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // which synth this page shows: the scratch one or any pad's (note #12)
                    Select(
                        if (scratch) "Scratch" else "Pad ${pad + 1}",
                        listOf(-1 to "Scratch synth") + state.synthPads().map { it to "Pad ${it + 1} · ${state.lib.titleOf(P.kit[it])}" },
                        { if (it < 0) state.synthScratch = true else { state.synthScratch = false; state.selectPad(it, false) } },
                        modifier = Modifier.weight(1f), tag = "synth-pad",
                    )
                    Select(
                        sp.name,
                        sp.kind.presets.map { it.name to it.name },
                        { n -> sp.kind.presets.firstOrNull { it.name == n }?.let { state.loadSynthPreset(pad, it) } },
                        modifier = Modifier.weight(1.5f), tag = if (scratch) "nosynth-preset" else "synth-preset",
                    )
                    // the same synth as a Padiano on the pad grid, next to your other pads (note #11)
                    if (!scratch) Btn("Pads", { state.padianoOnGrid(pad) }, small = true, tight = true, tag = "synth-to-pads", icon = "Keys")
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val full = state.synthView == "full"
                    PickSlider(listOf("Simple" to null, "Full" to null), if (full) 1 else 0, { state.synthView = if (it == 1) "full" else "simple" }, Modifier.weight(1f),
                        tags = listOf("synthview-simple", "synthview-full"), tag = "synthview-chips", onColor = C.Teal, icons = false)
                    sp.kind.params.firstOrNull { it.id == "mode" }?.let { mp ->
                        val m = sp.get("mode").toInt().coerceIn(0, mp.choices.size - 1)
                        PickSlider(mp.choices.map { it to null }, m, { state.setSynth(pad, "mode", it.toDouble(), true) }, Modifier.weight(1.5f),
                            tags = mp.choices.indices.map { "synthmode-$it" }, tag = "synthmode-chips", onColor = C.Yellow, icons = false)
                    }
                }
            }
        }
        // what kind of synth, as one slider (Nov 22). Switching starts from that kind's first preset; Undo brings the
        // old one back. Grain reads a sound off a pad, so scratch has no Grain.
        val kinds = Synth.KINDS.filter { !scratch || it != Synth.GRAIN }
        PickSlider(kinds.map { kindShort(it) to it.name }, kinds.indexOf(sp.kind), { i -> val k = kinds[i]; if (sp.kind != k) state.loadSynthPreset(pad, k.presets.first()) },
            Modifier.padding(bottom = 8.dp, end = 4.dp), tags = kinds.map { if (scratch) "scratchkind-${it.id}" else "synthkind-${it.id}" }, tag = "synthkind-chips", onColor = C.Yellow)
        if (scratch) {
            // the scratch synth onto a pad: an empty one in one tap; a pad with a sound on it takes a second tap
            // (Nov 22: it was too easy to overwrite the snare you had picked)
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp, end = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                val empty = state.emptyPadInBank()
                if (empty >= 0) Btn("To pad ${empty + 1}", { state.scratchToPad(empty) }, small = true, tight = true, tag = "synth-new-empty")
                val selFull = P.kit.getOrNull(sel).orEmpty().isNotEmpty()
                val armed = state.replaceArmed == sel
                if (!selFull) { if (empty != sel) Btn("To pad ${sel + 1}", { state.scratchToPad(sel) }, small = true, tight = true, tag = "synth-new-here") }
                else Btn(if (armed) "Replace ${sel + 1}?" else "Over ${sel + 1}", { if (armed) { if (state.replaceReady()) { state.replaceArmed = -1; state.scratchToPad(sel) } } else state.armReplace(sel) },
                    on = armed, onColor = C.Riot, small = true, tight = true, tag = "synth-new-here")
            }
        }
        // the model choice some kinds have (drums' kit, the plucked string's body)
        sp.kind.params.firstOrNull { it.id == "model" }?.let { mp ->
            Wrap(Modifier.padding(bottom = 10.dp).tagged("synthmodel-chips")) {
                for ((i, n) in mp.choices.withIndex()) Btn(n, { state.setSynth(pad, "model", i.toDouble(), true) }, on = sp.get("model").toInt() == i, onColor = C.Yellow, small = true, tag = "synthmodel-$i")
            }
        }
        // SE-15: a granular synth reads one of the mess's sounds
        if (sp.kind == Synth.GRAIN) {
            val srcs = state.grainSources()
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                T("GRAINS OF", size = 11.sp, color = C.Dim, spacing = .1.em)
                if (srcs.isEmpty()) Note("No sounds on pads yet.", modifier = Modifier.weight(1f))
                else Select(state.lib.titleOf(sp.src).ifEmpty { "Pick a sound" }, srcs.map { it to state.lib.titleOf(it) }, { k -> state.setGrainSource(pad, k) }, modifier = Modifier.weight(1f), tag = "grain-src")
            }
        }
        val keys = @Composable { if (!state.synthKeysHidden) PianoKeyboard(state, pad, close = false) }
        if (state.synthView == "full") {
            for (g in sp.kind.params.map { it.group }.distinct()) {
                val ps = sp.kind.params.filter { it.group == g && it.id != "mode" && it.id != "model" }
                if (ps.isEmpty()) continue
                T(g.uppercase(), size = 11.sp, color = C.Dim, spacing = .1.em, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
                KnobRows(state, pad, sp, ps, "knobs-${g.lowercase().replace(' ', '-')}-chips")
            }
            Spacer(Modifier.height(10.dp))
        } else KnobRows(state, pad, sp, sp.kind.params.filter { it.simple && it.id != "mode" && it.id != "model" }, "knobs-simple-chips")
        keys()
        T(" ", size = 30.sp)
    }
}

/**
 * The synth's own pad (Nov 21 video 2): a pad tile on the Synth page. Tap plays the synth's own note; a swipe does
 * what HotKeys says, as on the grid (Edit, FX, Mute, Padiano folds the keys away), and the same swipe closes it.
 */
@Composable
private fun SynthPadTile(state: AppState, pad: Int, sp: Synth.Patch) {
    val k = state.project.kit.getOrNull(pad).orEmpty()
    val col = padColor(Color(state.lib.colorOf(k)))
    var down by remember { mutableStateOf(false) }
    androidx.compose.runtime.SideEffect { state.synthTilePad = pad }
    val muted = k in state.project.mutes
    Box(
        Modifier
            .size((76 * UiLook.big).dp)
            .bakelite(on = down, lamp = col, pressed = down, tint = col.copy(alpha = if (down) .9f else .55f))
            .tagged("synth-tile")
            // tap plays the synth's own note; a quick swipe is read by the app's touch watcher (watchTouches), the same
            // way as a grid pad's, and does what HotKeys says
            .pointerInput(pad) {
                awaitEachGesture {
                    val d = awaitFirstDown()
                    d.consume()
                    down = true
                    state.pianoDown(pad, 0)
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == d.id } ?: break
                        c.consume()
                        if (!c.pressed) break
                    }
                    state.pianoUp(pad, 0)
                    down = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            T(if (pad == Project.SCRATCH) "SCRATCH" else "PAD ${pad + 1}", size = 9.sp, color = Hw.Engrave.copy(alpha = .8f), lines = 1, spacing = .06.em, family = Stencil)
            T(sp.name.uppercase(), size = 11.sp, color = Hw.Engrave, lines = 2, spacing = 0.em, family = Worn)
            if (muted) T("MUTED", size = 9.sp, color = C.Pink, lines = 1, family = Stencil)
        }
    }
}

/** Short names so every kind fits in one row (Nov 22). */
fun kindShort(k: Synth.Kind): String = when (k.name.lowercase()) { "wavetable" -> "Wave"; "granular" -> "Grain"; "drums" -> "Drum"; else -> k.name }

/** Knobs four to a row; choice knobs show as a knob too, stepping through their choices. */
@Composable
private fun KnobRows(state: AppState, pad: Int, sp: Synth.Patch, ps: List<Synth.Param>, tag: String) {
    Column(Modifier.fillMaxWidth().tagged(tag)) {
        for (row in ps.chunked(4)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                for (pm in row) {
                    // the MIDI map is up: touching a knob aims the next controller knob at it, and it shows what it has
                    val here = com.tideclock.messmachine.MidiTarget("knob", pad, 0, pm.id)
                    val badge = if (!state.midiMapOn) "" else if (state.midiCursor == here) "turn a knob" else state.midiMappedTo(here)
                    Knob(pm, sp.get(pm.id), { x, c -> state.midiAim(here); state.setSynth(pad, pm.id, x, c) }, Modifier.weight(1f), "knob-${pm.id}",
                        onTap = { state.midiAim(here) }, badge = badge)
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * A knob: drag up or right to turn it up (slow near the finger, the whole range over about 200 dp), double tap
 * for its default, hold to type a value. The arc shows where it is; the label under it says the value in words.
 */
@Composable
fun Knob(pm: Synth.Param, value: Double, set: (Double, Boolean) -> Unit, modifier: Modifier = Modifier, tag: String, onTap: () -> Unit = {}, badge: String = "") {
    var typing by remember { mutableStateOf(false) }
    val pos = pm.toKnob(value).toFloat()
    // the gestures are set up once per knob; these keep them reading today's value and pad
    val cur by rememberUpdatedState(value)
    val setNow by rememberUpdatedState(set)
    val tapNow by rememberUpdatedState(onTap)
    Column(modifier.tagged(tag), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier.size(54.dp)
                .pointerInput(pm.id) {
                    detectTapGestures(onTap = { tapNow() }, onDoubleTap = { setNow(pm.def, true) }, onLongPress = { typing = true })
                }
                .pointerInput(pm.id, "drag") {
                    awaitEachGesture {
                        val d = awaitFirstDown(requireUnconsumed = false)
                        var k0 = pm.toKnob(cur)
                        var last = d.position
                        var moved = false
                        val span = 200.dp.toPx()
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == d.id } ?: break
                            if (!c.pressed) break
                            val dx = c.position.x - last.x
                            val dy = last.y - c.position.y
                            if (!moved && Math.abs(c.position.y - d.position.y) + Math.abs(c.position.x - d.position.x) < 6.dp.toPx()) continue
                            moved = true
                            last = c.position
                            k0 = (k0 + (dx + dy) / span).coerceIn(0.0, 1.0)
                            setNow(pm.fromKnob(k0), false)
                            c.consume()
                        }
                        if (moved) setNow(pm.fromKnob(k0), true)
                    }
                },
        ) {
            val sw = 5.dp.toPx()
            val r = size.minDimension / 2 - sw
            val tl = Offset(center.x - r, center.y - r)
            drawArc(C.Card2, 135f, 270f, false, tl, Size(r * 2, r * 2), style = Stroke(sw, cap = StrokeCap.Round))
            val bipolar = pm.min < 0 && pm.max > 0 && pm.curve != Synth.CHOICE
            if (bipolar) {
                val mid = pm.toKnob(0.0).toFloat()
                val a0 = 135f + 270f * minOf(mid, pos)
                drawArc(C.Teal, a0, 270f * Math.abs(pos - mid), false, tl, Size(r * 2, r * 2), style = Stroke(sw, cap = StrokeCap.Round))
            } else drawArc(C.Teal, 135f, 270f * pos, false, tl, Size(r * 2, r * 2), style = Stroke(sw, cap = StrokeCap.Round))
            val a = Math.toRadians(135.0 + 270.0 * pos)
            drawLine(C.Cream, center, Offset(center.x + (r * .7f * Math.cos(a)).toFloat(), center.y + (r * .7f * Math.sin(a)).toFloat()), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        }
        T(pm.name.uppercase(), size = 10.sp, color = C.Dim, spacing = .04.em, lines = 1)
        // while the MIDI map is up: the controller knob mapped here, in place of the value
        if (badge.isEmpty()) T(pm.show(value), size = 11.sp, color = C.Cream, lines = 1)
        else T(badge.uppercase(), size = 10.sp, color = C.Yellow, lines = 1, family = Mono)
    }
    if (typing) {
        var text by remember { mutableStateOf(if (pm.curve == Synth.CHOICE) "" else "%.3f".format(value).trimEnd('0').trimEnd('.')) } // format-ok: number only
        AlertDialog(
            onDismissRequest = { typing = false },
            title = { Text(pm.name) },
            text = {
                if (pm.curve == Synth.CHOICE) Column { for ((i, n) in pm.choices.withIndex()) TextButton({ set(pm.min + i, true); typing = false }) { Text(n) } }
                else OutlinedTextField(text, { text = it }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = { Text("From ${pm.show(pm.min)} to ${pm.show(pm.max)}" + if (pm.unit == "%") ", as 0 to 1" else "") })
            },
            confirmButton = { if (pm.curve != Synth.CHOICE) TextButton({ text.toDoubleOrNull()?.let { set(pm.clamp(it), true) }; typing = false }) { Text("Set") } },
            dismissButton = { TextButton({ typing = false }) { Text("Cancel") } },
        )
    }
}

