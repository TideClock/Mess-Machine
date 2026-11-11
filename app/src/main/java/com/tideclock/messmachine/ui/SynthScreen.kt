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
import com.tideclock.messmachine.engine.Synth
import com.tideclock.messmachine.engine.Theory

/**
 * The Synth tab (SE-11): the synth on the selected pad. Simple shows the knobs that matter most, Full shows
 * every knob in groups, Synthipad turns the page into a playing surface (in-key pads and an XY for the
 * filter). Steps, Roll, Hold and Repeat play a synth pad like any other pad.
 */
@Composable
fun SynthScreen(state: AppState) {
    @Suppress("UNUSED_VARIABLE") val v = state.version
    val P = state.project
    val pad = P.sel
    val sp = state.synthOn(pad)
    ScrollPane(hPad = 12.dp, vPad = 0.dp) {
        if (sp == null) {
            NoSynth(state, pad)
            return@ScrollPane
        }
        // header: which pad, the preset, the view
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            T("PAD ${pad + 1}", size = 12.sp, color = C.Yellow, spacing = .08.em)
            Select(
                sp.name,
                sp.kind.presets.map { it.name to it.name },
                { n -> sp.kind.presets.firstOrNull { it.name == n }?.let { state.loadSynthPreset(pad, it) } },
                modifier = Modifier.weight(1f), tag = "synth-preset",
            )
        }
        // what kind of synth (switching starts from that kind's first preset; Undo brings the old one back)
        Wrap(Modifier.padding(bottom = 8.dp).tagged("synthkind-chips"), gap = 6.dp) {
            for (k in Synth.KINDS) Btn(k.name, { if (sp.kind != k) state.loadSynthPreset(pad, k.presets.first()) }, on = sp.kind == k, onColor = C.Yellow, small = true, tag = "synthkind-${k.id}")
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp, end = 4.dp).tagged("synthview-chips"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((id, n) in listOf("simple" to "Simple", "full" to "Full", "pad" to "Synthipad")) Btn(n, { state.synthView = id }, on = state.synthView == id, onColor = C.Teal, small = true, tag = "synthview-$id", modifier = Modifier.weight(1f))
        }
        // SE-15: a granular synth reads one of the mess's sounds
        if (sp.kind == Synth.GRAIN) {
            val srcs = state.grainSources()
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                T("GRAINS OF", size = 11.sp, color = C.Dim, spacing = .1.em)
                if (srcs.isEmpty()) Note("Put a sound on a pad first: the grains come from it.", modifier = Modifier.weight(1f))
                else Select(state.lib.titleOf(sp.src).ifEmpty { "Pick a sound" }, srcs.map { it to state.lib.titleOf(it) }, { k -> state.setGrainSource(pad, k) }, modifier = Modifier.weight(1f), tag = "grain-src")
            }
        }
        when (state.synthView) {
            "full" -> {
                ModeChips(state, pad, sp)
                for (g in sp.kind.params.map { it.group }.distinct()) {
                    val ps = sp.kind.params.filter { it.group == g && it.id != "mode" && it.id != "model" }
                    if (ps.isEmpty()) continue
                    T(g.uppercase(), size = 11.sp, color = C.Dim, spacing = .1.em, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
                    KnobRows(state, pad, sp, ps, "knobs-${g.lowercase().replace(' ', '-')}-chips")
                }
                Spacer(Modifier.height(10.dp))
                PianoKeyboard(state, pad, close = false)
            }
            "pad" -> Synthipad(state, pad, sp)
            else -> {
                ModeChips(state, pad, sp)
                KnobRows(state, pad, sp, sp.kind.params.filter { it.simple && it.id != "mode" && it.id != "model" }, "knobs-simple-chips")
                Help(sp.kind.blurb + " Full has every knob.", modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
                PianoKeyboard(state, pad, close = false)
            }
        }
        T(" ", size = 30.sp)
    }
}

/** No synth on the selected pad yet: put one there, or on the first empty pad. */
@Composable
private fun NoSynth(state: AppState, pad: Int) {
    val P = state.project
    val all = remember { Synth.KINDS.flatMap { k -> k.presets.map { "${k.name}: ${it.name}" to it } } }
    var preset by remember { mutableStateOf(Synth.ANALOG.presets.first()) }
    Note("A synth plays on a pad like a sound: steps, Roll, keys, Hold and Repeat all work. Pick where to put one.", modifier = Modifier.padding(top = 10.dp, bottom = 12.dp))
    T("START FROM", size = 11.sp, color = C.Dim, spacing = .1.em, modifier = Modifier.padding(bottom = 4.dp))
    Select("${preset.kind.name}: ${preset.name}", all.map { it.first to it.first }, { n -> all.firstOrNull { it.first == n }?.let { preset = it.second } }, tag = "nosynth-preset")
    Spacer(Modifier.height(12.dp))
    val empty = state.emptyPadInBank()
    if (empty >= 0) Btn("On empty pad ${empty + 1}", { state.newSynth(empty, preset) }, on = true, onColor = C.Teal, tag = "synth-new-empty")
    Spacer(Modifier.height(8.dp))
    val k = P.kit.getOrNull(pad).orEmpty()
    Btn(if (k.isEmpty()) "On pad ${pad + 1}" else "On pad ${pad + 1}, instead of ${state.lib.titleOf(k)}", { state.newSynth(pad, preset) }, tag = "synth-new-here")
}

/** Mode (Poly, Mono, Acid) and, for drums, the model: choices you pick, not knobs you turn. */
@Composable
private fun ModeChips(state: AppState, pad: Int, sp: Synth.Patch) {
    sp.kind.params.firstOrNull { it.id == "mode" }?.let { mp ->
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp, end = 4.dp).tagged("synthmode-chips"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((i, n) in mp.choices.withIndex()) Btn(n, { state.setSynth(pad, "mode", i.toDouble(), true) }, on = sp.get("mode").toInt() == i, onColor = C.Pink, small = true, tag = "synthmode-$i", modifier = Modifier.weight(1f))
        }
    }
    sp.kind.params.firstOrNull { it.id == "model" }?.let { mp ->
        Wrap(Modifier.padding(bottom = 10.dp).tagged("synthmodel-chips")) {
            for ((i, n) in mp.choices.withIndex()) Btn(n, { state.setSynth(pad, "model", i.toDouble(), true) }, on = sp.get("model").toInt() == i, onColor = C.Pink, small = true, tag = "synthmodel-$i")
        }
    }
}

/** Knobs four to a row; choice knobs show as a knob too, stepping through their choices. */
@Composable
private fun KnobRows(state: AppState, pad: Int, sp: Synth.Patch, ps: List<Synth.Param>, tag: String) {
    Column(Modifier.fillMaxWidth().tagged(tag)) {
        for (row in ps.chunked(4)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                for (pm in row) Knob(pm, sp.get(pm.id), { x, c -> state.setSynth(pad, pm.id, x, c) }, Modifier.weight(1f), "knob-${pm.id}")
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
fun Knob(pm: Synth.Param, value: Double, set: (Double, Boolean) -> Unit, modifier: Modifier = Modifier, tag: String) {
    var typing by remember { mutableStateOf(false) }
    val pos = pm.toKnob(value).toFloat()
    // the gestures are set up once per knob; these keep them reading today's value and pad
    val cur by rememberUpdatedState(value)
    val setNow by rememberUpdatedState(set)
    Column(modifier.tagged(tag), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier.size(54.dp)
                .pointerInput(pm.id) {
                    detectTapGestures(onDoubleTap = { setNow(pm.def, true) }, onLongPress = { typing = true })
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
        T(pm.show(value), size = 11.sp, color = C.Cream, lines = 1)
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

/**
 * Synthipad: the page as a playing surface. Four rows of in-key notes over two octaves (more fingers, more
 * notes) under an XY square for the filter: across is cutoff, up is resonance.
 */
@Composable
private fun Synthipad(state: AppState, pad: Int, sp: Synth.Patch) {
    val P = state.project
    val cutP = sp.kind.params.firstOrNull { it.id == "cut" }
    val resP = sp.kind.params.firstOrNull { it.id == "res" }
    if (cutP != null && resP != null) {
        val x = cutP.toKnob(sp.get("cut")).toFloat()
        val y = resP.toKnob(sp.get("res")).toFloat()
        Box(Modifier.fillMaxWidth().padding(end = 4.dp, bottom = 10.dp)) {
            val h = 150.dp
            Canvas(
                Modifier.fillMaxWidth().height(h).sticker(C.Card2).tagged("synthipad-xy")
                    .pointerInput(pad) {
                        awaitEachGesture {
                            val d = awaitFirstDown()
                            fun at(o: Offset, commit: Boolean) {
                                val kx = (o.x / size.width).coerceIn(0f, 1f).toDouble()
                                val ky = (1 - o.y / size.height).coerceIn(0f, 1f).toDouble()
                                state.setSynth(pad, "cut", cutP.fromKnob(kx), false)
                                state.setSynth(pad, "res", resP.fromKnob(ky), commit)
                            }
                            at(d.position, false)
                            var lastPos = d.position
                            while (true) {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull { it.id == d.id } ?: break
                                if (!c.pressed) break
                                lastPos = c.position
                                at(c.position, false)
                                c.consume()
                            }
                            at(lastPos, true)
                        }
                    },
            ) {
                drawCircle(C.Teal, 14.dp.toPx(), Offset(x * size.width, (1 - y) * size.height))
                drawLine(C.Dim.copy(alpha = .4f), Offset(x * size.width, 0f), Offset(x * size.width, size.height), strokeWidth = 1.dp.toPx())
                drawLine(C.Dim.copy(alpha = .4f), Offset(0f, (1 - y) * size.height), Offset(size.width, (1 - y) * size.height), strokeWidth = 1.dp.toPx())
            }
            T("CUTOFF ${cutP.show(sp.get("cut"))} · RESONANCE ${resP.show(sp.get("res"))}", size = 10.sp, color = C.Dim, spacing = .04.em, modifier = Modifier.padding(start = 8.dp, top = 6.dp))
        }
    }
    // four rows of in-key notes, lowest at the bottom, like a pad controller
    val scale = if (P.scale == "Chromatic") "Major" else P.scale
    val notes = (-12..24).filter { Theory.inScale(it, scale, P.root) }.take(16 + 4).let { all -> if (all.size >= 16) all.take(16) else all }
    val rows = notes.chunked(4).reversed()
    var down by remember(pad) { mutableStateOf(setOf<Int>()) }
    Column(
        Modifier.fillMaxWidth().padding(end = 4.dp).tagged("synthipad-notes")
            .pointerInput(pad, notes) {
                awaitEachGesture {
                    val active = HashMap<PointerId, Int>()
                    do {
                        val ev = awaitPointerEvent()
                        val cellW = size.width / 4f
                        val cellH = size.height / rows.size.toFloat()
                        for (c in ev.changes) {
                            val cur = active[c.id]
                            if (c.pressed) {
                                val col = (c.position.x / cellW).toInt()
                                val row = (c.position.y / cellH).toInt()
                                val n = rows.getOrNull(row)?.getOrNull(col)
                                if (n != cur) {
                                    if (cur != null) state.pianoUp(pad, cur)
                                    if (n != null) { state.pianoDown(pad, n); active[c.id] = n } else active.remove(c.id)
                                }
                                c.consume()
                            } else if (cur != null) { state.pianoUp(pad, cur); active.remove(c.id) }
                        }
                        down = active.values.toSet()
                    } while (ev.changes.any { it.pressed })
                    for (n in active.values) state.pianoUp(pad, n)
                    down = emptySet()
                }
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (row in rows) Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (n in row) {
                val root = Theory.pcOf(n - P.root) == 0
                Box(Modifier.weight(1f).height(64.dp).sticker(if (n in down) C.Teal else if (root) C.Pink.copy(alpha = .55f) else C.Card2), contentAlignment = Alignment.Center) {
                    T(Theory.noteName(n), size = 12.sp, color = if (n in down) C.Ink else C.Cream)
                }
            }
            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    Help("Steps and Roll play this synth on pad ${pad + 1}. Hold and Repeat work here too.", modifier = Modifier.padding(top = 10.dp))
}
