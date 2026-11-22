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
        // Nov 21 video: no synth on the picked pad (or Scratch picked): the tab's own synth, ready to play
        if (sp == null || state.synthScratch) {
            ScratchSynth(state, pad)
            return@ScrollPane
        }
        // header: which pad, the preset, the view
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // which pad's synth this page shows: pick another one right here (note #12)
            Select("Pad ${pad + 1}", state.synthPads().map { it to "Pad ${it + 1} · ${state.lib.titleOf(P.kit[it])}" }, { state.selectPad(it, false) }, tag = "synth-pad")
            // the same synth as a Padiano on the pad grid, next to your other pads (note #11)
            Btn("🎹 On pads", { state.padianoOnGrid(pad) }, small = true, tag = "synth-to-pads")
            Btn("Scratch", { state.synthScratch = true }, small = true, tag = "synth-scratch")
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
            for ((id, n) in listOf("simple" to "Simple", "full" to "Full", "pad" to "Synthipad")) Btn(n, { state.synthView = id }, on = state.synthView == id, onColor = C.Teal, small = true, tight = true, tag = "synthview-$id", modifier = Modifier.weight(n.length + 3f))
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

/**
 * The scratch synth (Nov 21 video): the Synth tab opens on a synth you can play right away, on no pad. Pick a kind
 * and a preset, turn its knobs, play its keys; Put on pad copies it onto an empty pad (or the picked one).
 */
@Composable
private fun ScratchSynth(state: AppState, pad: Int) {
    @Suppress("UNUSED_VARIABLE") val sv = state.scratchV
    val P = state.project
    val sp = state.scratch
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        T("SCRATCH SYNTH", size = 13.sp, color = C.Yellow, spacing = .1.em, modifier = Modifier.weight(1f))
    }
    // the synths this mess already has: one tap to each (note #12)
    val have = state.synthPads()
    if (have.isNotEmpty()) Wrap(Modifier.padding(bottom = 8.dp).tagged("synthpads-chips"), gap = 6.dp) {
        for (i in have) Btn("Pad ${i + 1} · ${state.lib.titleOf(P.kit[i])}", { state.synthScratch = false; state.selectPad(i, false) }, small = true, tag = "synth-go-$i")
    }
    // what kind (the grain synth reads a sound off a pad, so it starts on a pad: Put on pad, then Grain)
    Wrap(Modifier.padding(bottom = 8.dp).tagged("scratchkind-chips"), gap = 6.dp) {
        for (k in Synth.KINDS) if (k != Synth.GRAIN) Btn(k.name, { if (sp.kind != k) state.loadScratch(k.presets.first()) }, on = sp.kind == k, onColor = C.Yellow, small = true, tag = "scratchkind-${k.id}")
    }
    Select(sp.name, sp.kind.presets.map { it.name to it.name }, { n -> sp.kind.presets.firstOrNull { it.name == n }?.let { state.loadScratch(it) } }, tag = "nosynth-preset")
    Spacer(Modifier.height(10.dp))
    sp.kind.params.firstOrNull { it.id == "mode" }?.let { mp ->
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp, end = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((i, n) in mp.choices.withIndex()) Btn(n, { state.turnScratch("mode", i.toDouble()) }, on = sp.get("mode").toInt() == i, onColor = C.Pink, small = true, tag = "scratchmode-$i", modifier = Modifier.weight(1f))
        }
    }
    val ps = sp.kind.params.filter { it.simple && it.id != "mode" && it.id != "model" }
    Column(Modifier.fillMaxWidth().tagged("scratch-knobs")) {
        for (row in ps.chunked(4)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                for (pm in row) Knob(pm, sp.get(pm.id), { x, _ -> state.turnScratch(pm.id, x) }, Modifier.weight(1f), "sknob-${pm.id}")
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        T("KEYS", size = 11.sp, color = C.Dim, spacing = .1.em, modifier = Modifier.weight(1f))
        Btn("Oct −", { state.scratchOct = (state.scratchOct - 1).coerceAtLeast(-3) }, small = true, enabled = state.scratchOct > -3, tag = "scratch-octdown")
        T(if (state.scratchOct == 0) "C4" else "C${4 + state.scratchOct}", size = 13.sp, color = C.Yellow, family = Mono)
        Btn("Oct +", { state.scratchOct = (state.scratchOct + 1).coerceAtMost(3) }, small = true, enabled = state.scratchOct < 3, tag = "scratch-octup")
    }
    ScratchKeys(state)
    // keep it: onto a pad, where Steps, the Roll and the Padiano play it
    T("PUT IT ON A PAD", size = 11.sp, color = C.Dim, spacing = .1.em, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
    Wrap(gap = 6.dp) {
        val empty = state.emptyPadInBank()
        if (empty >= 0) Btn("Empty pad ${empty + 1}", { state.scratchToPad(empty) }, tag = "synth-new-empty")
        Btn("Pad ${pad + 1}", { state.scratchToPad(pad) }, tag = "synth-new-here")
    }
    val k = P.kit.getOrNull(pad).orEmpty()
    if (k.isNotEmpty()) Note("Pad ${pad + 1} replaces ${state.lib.titleOf(k)} (Undo brings it back)", modifier = Modifier.padding(top = 6.dp))
}

/** The scratch synth's keys: two octaves, any number of fingers, slide to change notes. Pitch 0 is middle C. */
@Composable
private fun ScratchKeys(state: AppState) {
    val base = state.scratchOct * 12
    val lit = remember { androidx.compose.runtime.mutableStateMapOf<Long, Int>() }
    val whites = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    val blackAfter = intArrayOf(0, 1, 3, 4, 5)
    val blackSemi = intArrayOf(1, 3, 6, 8, 10)
    val nW = 14
    fun keyAt(x: Float, y: Float, w: Float, h: Float): Int {
        val ww = w / nW; val bw = ww * .62f
        if (y < h * .6f) for (o in 0 until 2) for (b in blackAfter.indices) {
            val cx = (o * 7 + blackAfter[b] + 1) * ww
            if (x >= cx - bw / 2 && x <= cx + bw / 2) return o * 12 + blackSemi[b]
        }
        val wi = (x / ww).toInt().coerceIn(0, nW - 1)
        return (wi / 7) * 12 + whites[wi % 7]
    }
    var nextId by remember { mutableStateOf(9000) }
    Canvas(
        Modifier.fillMaxWidth().height(150.dp).tagged("scratch-keys").pointerInput(base) {
            awaitPointerEventScope {
                val mine = HashMap<PointerId, Pair<Int, Int>>()
                while (true) {
                    val e = awaitPointerEvent()
                    for (c in e.changes) {
                        val now = mine[c.id]
                        if (c.pressed && now == null) {
                            val p = keyAt(c.position.x, c.position.y, size.width.toFloat(), size.height.toFloat())
                            val id = nextId++; mine[c.id] = id to p; lit[c.id.value] = p
                            state.scratchDown(id, base + p); c.consume()
                        } else if (c.pressed && now != null) {
                            val p = keyAt(c.position.x, c.position.y, size.width.toFloat(), size.height.toFloat())
                            if (p != now.second) {
                                state.scratchUp(now.first)
                                val id = nextId++; mine[c.id] = id to p; lit[c.id.value] = p
                                state.scratchDown(id, base + p)
                            }
                            c.consume()
                        } else if (!c.pressed && now != null) {
                            state.scratchUp(now.first); mine.remove(c.id); lit.remove(c.id.value); c.consume()
                        }
                    }
                }
            }
        },
    ) {
        val ww = size.width / nW; val bw = ww * .62f
        val on = lit.values.toSet()
        for (i in 0 until nW) {
            val semi = (i / 7) * 12 + whites[i % 7]
            drawRect(if (semi in on) Hw.Amber else Hw.Engrave, topLeft = Offset(i * ww + 1f, 0f), size = Size(ww - 2f, size.height))
            drawRect(Color.Black.copy(alpha = .35f), topLeft = Offset(i * ww + 1f, size.height - 5.dp.toPx()), size = Size(ww - 2f, 5.dp.toPx()))
        }
        for (o in 0 until 2) for (b in blackAfter.indices) {
            val semi = o * 12 + blackSemi[b]
            val cx = (o * 7 + blackAfter[b] + 1) * ww
            drawRect(if (semi in on) Hw.Brass else Hw.Bake, topLeft = Offset(cx - bw / 2, 0f), size = Size(bw, size.height * .6f))
            drawRect(Color.White.copy(alpha = .08f), topLeft = Offset(cx - bw / 2 + 2f, 2f), size = Size(bw - 4f, 3f))
        }
    }
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
    // key, chords and Hold: on the bottom dock, which stays on screen (build 84)
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
