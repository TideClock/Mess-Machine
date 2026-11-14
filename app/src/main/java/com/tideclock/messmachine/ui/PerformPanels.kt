package com.tideclock.messmachine.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import com.tideclock.messmachine.AppState
import com.tideclock.messmachine.engine.Theory
import kotlin.math.floor

/** Hold-to-use effects and the filter pad, like the site's Perform tab. */
@Composable
fun FxPanel(state: AppState) {
    val names = com.tideclock.messmachine.FX_NAMES
    Card(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Column {
            Heading("Perform", size = 18.sp)
            Help("Hold an effect while you play. Slide along it to set how much. ✎ in its corner: stays on, slider, or aim it at one pad or a bus.", modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
            FxEditor(state)
            for (r in 0 until 3) {
                Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (c in 0 until 3) {
                        val (id, label) = names[r * 3 + c]
                        FxButton(state, id, label, Modifier.weight(1f).tagged("fx-$id"), C.Letters[(r * 3 + c) % C.Letters.size])
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)) {
                T(if (state.xyHP) "THIN FILTER" else "MUD FILTER", size = 13.sp, color = C.Yellow, spacing = .1.em, modifier = Modifier.weight(1f))
                Btn("Low pass", { state.switchXyHP(false) }, on = !state.xyHP, small = true, onColor = C.Teal)
                Btn("High pass", { state.switchXyHP(true) }, on = state.xyHP, small = true, onColor = C.Teal)
            }
            XyPad(state)
            Help("Slide left and right for the cutoff, up for more resonance.", modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
fun HoldButton(label: String, modifier: Modifier, color: Color, height: androidx.compose.ui.unit.Dp = 58.dp, onHold: (Boolean) -> Unit) {
    var held by remember { mutableStateOf(false) }
    Box(
        modifier
            .height(height)
            .padding(end = 4.dp, bottom = 4.dp)
            .sticker(if (held) color else C.Ink, shadow = if (held) 0.dp else 4.dp, shadowColor = color)
            .pointerInput(label) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    held = true
                    onHold(true)
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.changes.none { it.pressed }) break
                    }
                    held = false
                    onHold(false)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        T(label.uppercase(), size = 12.sp, color = if (held) ink(color) else C.Cream, lines = 1, spacing = .06.em)
    }
}

@Composable
fun XyPad(state: AppState, height: androidx.compose.ui.unit.Dp = 180.dp) {
    var dot by remember { mutableStateOf<Offset?>(null) }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(height)
            .padding(end = 6.dp, bottom = 6.dp)
            .sticker(C.Paper, shadow = 6.dp, shadowColor = C.Teal)
            .tagged("xy")
            .pointerInput(Unit) {
                awaitEachGesture {
                    val d = awaitFirstDown(requireUnconsumed = false)
                    fun send(p: Offset, on: Boolean) {
                        val x = (p.x / size.width).coerceIn(0f, 1f)
                        val y = (p.y / size.height).coerceIn(0f, 1f)
                        dot = if (on) Offset(x, y) else null
                        state.xy(x, y, on)
                    }
                    send(d.position, true)
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == d.id } ?: e.changes.first()
                        if (!c.pressed) break
                        if (c.positionChange() != Offset.Zero) send(c.position, true)
                        c.consume()
                    }
                    send(Offset.Zero, false)
                }
            },
    ) {
        for (i in 1..3) {
            drawLine(C.Card2, Offset(size.width * i / 4, 0f), Offset(size.width * i / 4, size.height), strokeWidth = 1.5f)
            drawLine(C.Card2, Offset(0f, size.height * i / 4), Offset(size.width, size.height * i / 4), strokeWidth = 1.5f)
        }
        dot?.let { p ->
            val c = Offset(p.x * size.width, p.y * size.height)
            drawLine(C.Pink.copy(alpha = .5f), Offset(c.x, 0f), Offset(c.x, size.height), strokeWidth = 2f)
            drawLine(C.Pink.copy(alpha = .5f), Offset(0f, c.y), Offset(size.width, c.y), strokeWidth = 2f)
            drawCircle(C.Pink, radius = 24f, center = c)
            drawCircle(C.Cream, radius = 24f, center = c, style = Stroke(width = 5f))
        }
    }
}

private val BLACK_PC = intArrayOf(1, 3, 6, 8, 10)
private fun isBlack(p: Int) = Theory.pcOf(p) in BLACK_PC

/** What the Padiano shows on each note: pressed, held, chord tones, scale, root. */
/** Chord tones to light: each at its own spot when visible, else moved by octaves to the nearest visible copy. */
internal fun chordOnScreen(tones: Iterable<Int>, down: Set<Int>, visible: Collection<Int>): Set<Int> {
    val c = HashSet<Int>()
    for (x in tones) {
        val y = if (visible.isEmpty() || x in visible) x else visible.filter { Theory.pcOf(it - x) == 0 }.minByOrNull { kotlin.math.abs(it - x) } ?: x
        if (y !in down) c.add(y)
    }
    return c
}

private class NoteLook(state: AppState, pad: Int, fingers: Set<Int>, visible: Collection<Int>) {
    val P = state.project
    val chromatic = P.scale == "Chromatic"
    val holding = state.heldPitches(pad)
    val down = fingers + holding
    val playsChord = state.chordPlay
    /**
     * The chord's other notes, each moved by octaves onto a note you can see when its own spot is off
     * screen (a one-row Padiano shows about an octave, so the chord of a higher note used to light
     * nothing: only the lowest note's chord fit).
     */
    val chord = chordOnScreen(down.flatMap { state.chordFor(it).asIterable() }, down, visible)
    fun inScale(p: Int) = chromatic || Theory.inScale(p, P.scale, P.root)
    fun isRoot(p: Int) = Theory.pcOf(p - P.root) == 0
}

/**
 * Multi-finger play with slides: each finger plays the note under it and changes note as it slides.
 * A finger that draws a circle snaps back to the note it started on and opens a sphere: from then on it
 * moves that note (pitch, level) and the pad's XY until it lifts.
 */
private fun Modifier.playNotes(state: AppState, pad: Int, key: Any, origin: Origin, hit: androidx.compose.ui.input.pointer.PointerInputScope.(Offset) -> Int?, onFingers: (Set<Int>) -> Unit): Modifier =
    this.pointerInput(pad, key) {
        awaitEachGesture {
            val active = HashMap<PointerId, Int>()
            val starts = HashMap<PointerId, Int>()
            val dets = HashMap<PointerId, CircleDetector>()
            val spheres = HashMap<PointerId, AppState.Sphere>()
            val lastXy = HashMap<PointerId, Pair<Float, Float>>()
            do {
                val ev = awaitPointerEvent()
                for (c in ev.changes) {
                    val cur = active[c.id]
                    val sp = spheres[c.id]
                    if (c.pressed && sp != null) {
                        val v = sphereXy(sp, state.xyOf(pad).four, origin.at + c.position)
                        lastXy[c.id] = v
                        state.xyMove(pad, v.first, v.second, true, note = sp.note)
                        c.consume()
                        continue
                    }
                    if (c.pressed) {
                        val p = hit(c.position)
                        if (p != cur) {
                            if (cur != null) state.pianoUp(pad, cur)
                            if (p != null) { state.pianoDown(pad, p); active[c.id] = p; if (c.id !in starts) starts[c.id] = p } else active.remove(c.id)
                        }
                        val det = dets.getOrPut(c.id) { CircleDetector(36.dp.toPx()) }
                        val start = starts[c.id]
                        if (start != null && det.add(c.position)) {
                            val now = active[c.id]
                            if (now != start) { if (now != null) state.pianoUp(pad, now); state.pianoDown(pad, start); active[c.id] = start }
                            val cc = origin.at + det.center()
                            val sp2 = AppState.Sphere(pad, start, cc.x, cc.y, xyDp(state.xyOf(pad).size).toPx() * .5f)
                            spheres[c.id] = sp2
                            state.sphere = sp2
                        }
                        c.consume()
                    } else {
                        spheres.remove(c.id)?.let { s0 ->
                            val v = lastXy[c.id] ?: (0f to 0f)
                            state.xyMove(pad, v.first, v.second, false, note = s0.note)
                            state.dropSphere(s0)
                        }
                        if (cur != null) { state.pianoUp(pad, cur); active.remove(c.id) }
                        dets.remove(c.id); starts.remove(c.id)
                    }
                }
                onFingers(active.values.toSet())
            } while (ev.changes.any { it.pressed })
            for ((id, s0) in spheres) { val v = lastXy[id] ?: (0f to 0f); state.xyMove(pad, v.first, v.second, false, note = s0.note) }
            for (s0 in spheres.values) state.dropSphere(s0)
            for (p in active.values) state.pianoUp(pad, p)
            onFingers(emptySet())
        }
    }

/**
 * The Padiano: a keyboard for one pad, dropped under its row of pads. Squares (the default) is a row
 * of small squares, as many semitones as you set; Keys is a piano from C3 to C5. In key only hides
 * out-of-key notes. Slide or play with several fingers.
 */
@Composable
fun PianoKeyboard(state: AppState, pad: Int, keysHeight: Dp = 132.dp, filter: Boolean = false, close: Boolean = true) {
    val P = state.project
    val k = P.kit[pad]
    if (k.isEmpty()) return
    val col = padColor(Color(state.lib.colorOf(k)))
    var showSet by remember(pad) { mutableStateOf(false) }
    var showXy by remember(pad) { mutableStateOf(false) }
    val squares = !state.padianoKeys || state.inKeyOnly
    val base = state.padianoBase[pad] ?: 0
    Column(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp).tagged("piano-$pad")) {
        // one thin header so the keys get the room: name, octave, Glide, XY, settings, close.
        // Hold lives on the dock and the keys bar now; the pad filter is the dock's (aim it at this pad) or a desk piece.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 4.dp)) {
            Swatch(col)
            T(" ${pad + 1} ${state.lib.titleOf(k).uppercase()}", size = 11.sp, color = C.Yellow, lines = 1, spacing = .04.em, modifier = Modifier.weight(1f))
            if (squares) {
                Btn("◀", { state.shiftPadiano(pad, -12) }, small = true, enabled = base > -36, tag = "piano-down-$pad")
                Btn("▶", { state.shiftPadiano(pad, 12) }, small = true, enabled = base < 24, tag = "piano-up-$pad")
            }
            PadianoExtras(state, pad)
            // a synth's Padiano on the grid: one tap to its knobs
            if (close && com.tideclock.messmachine.engine.Project.isSynth(k)) Btn("◎", { state.selectPad(pad, false); state.tab = com.tideclock.messmachine.Tab.Synth }, small = true, tag = "piano-synth-$pad")
            Btn("XY", { showXy = !showXy }, on = showXy, onColor = C.Pink, small = true, tag = "piano-xy-$pad")
            Btn("⚙", { showSet = !showSet }, small = true, on = showSet, tag = "piano-set-$pad", tier = Tier.Quiet)
            if (close) Btn("✕", { state.toggleKeyboard(pad) }, small = true, tag = "piano-close-$pad", tier = Tier.Quiet)
        }
        GlideRow(state, pad)
        if (showSet) PadianoSettings(state)
        if (squares) PadianoSquares(state, pad, col, base)
        else PadianoKeys(state, pad, col, if (keysHeight != 132.dp) keysHeight else listOf(96.dp, 132.dp, 176.dp, 236.dp)[state.padianoSize])
        if (showXy) PadXy(state, pad, Modifier.padding(top = 8.dp))
        if (filter) PadFilterStrip(state, pad, Modifier.padding(top = 8.dp))
    }
}

/** Glide on the Padiano header: tap to switch it on; its time and Legato show underneath. */
@Composable
fun PadianoExtras(state: AppState, pad: Int) {
    val o = state.project.opt(pad)
    Btn("Glide", { state.setPadOpt(pad) { glide = !glide } }, on = o.glide, onColor = C.Teal, small = true, tag = "glide-$pad")
}

@Composable
fun GlideRow(state: AppState, pad: Int) {
    val o = state.project.opt(pad)
    if (!o.glide) return
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        SliderRow("Glide time", o.glideT.toFloat(), 0f..1f, { "${(it * 1000).toInt()} ms" }, { v -> state.setPadOpt(pad, commit = false) { glideT = v.toDouble() } }, { state.setPadOpt(pad) {} }, accent = C.Teal)
        Wrap(gap = 6.dp) {
            Btn("Always", { state.setPadOpt(pad) { legato = false } }, on = !o.legato, onColor = C.Teal, small = true, tag = "glide-always-$pad")
            Btn("Legato only", { state.setPadOpt(pad) { legato = true } }, on = o.legato, onColor = C.Teal, small = true, tag = "glide-legato-$pad")
        }
    }
}

@Composable
fun PadianoSettings(state: AppState) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).sticker(C.Black, shadow = 0.dp, border = 2.dp, borderColor = C.Teal).padding(8.dp)) {
        Wrap(gap = 6.dp) {
            Btn("Squares", { state.switchPadianoKeys(false) }, on = !state.padianoKeys, onColor = C.Teal, small = true, tag = "pset-squares")
            Btn("Keyboard", { state.switchPadianoKeys(true) }, on = state.padianoKeys, onColor = C.Teal, small = true, tag = "pset-keys")
            Btn("In key only", { state.switchInKey(!state.inKeyOnly) }, on = state.inKeyOnly, onColor = C.Yellow, small = true, tag = "pset-inkey")
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
            T("NOTES", size = 11.sp, color = C.Dim, spacing = .1.em)
            Btn("−", { state.changePadianoN(state.padianoN - 1) }, small = true, enabled = state.padianoN > 7, tag = "pset-n-down")
            T("${state.padianoN}", size = 14.sp, color = C.Yellow, family = Mono)
            Btn("+", { state.changePadianoN(state.padianoN + 1) }, small = true, enabled = state.padianoN < 36, tag = "pset-n-up")
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
            T("SIZE", size = 11.sp, color = C.Dim, spacing = .1.em)
            for ((z, n) in listOf("S", "M", "L", "XL").withIndex()) Btn(n, { state.changePadianoSize(z) }, on = state.padianoSize == z, small = true, tag = "pset-size-$z")
            T("ROWS", size = 11.sp, color = C.Dim, spacing = .1.em, modifier = Modifier.padding(start = 8.dp))
            for (r in 1..3) Btn("$r", { state.changePadianoRows(r) }, on = state.padianoRows == r, small = true, tag = "pset-rows-$r")
        }
        Help("Fewer notes make bigger squares. XL is tall, with wider squares (they wrap sooner). Each extra row is the same notes an octave up.", modifier = Modifier.padding(top = 6.dp))
    }
}

/** Squares: one row of small squares; light for white-key notes, dark for black-key ones. */
@Composable
fun PadianoSquares(state: AppState, pad: Int, col: Color, base: Int) {
    val P = state.project
    var fingers by remember(pad) { mutableStateOf(setOf<Int>()) }
    val origin = remember { Origin() }
    val one = (base until base + state.padianoN).filter { !state.inKeyOnly || P.scale == "Chromatic" || Theory.inScale(it, P.scale, P.root) }
    if (one.isEmpty()) return
    // extra rows: the same notes an octave up each, the highest on top (like a pad controller)
    val octs = state.padianoRows
    val blocks = (octs - 1 downTo 0).map { o -> one.map { it + 12 * o } }
    val notes = blocks.flatten()
    val tm = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val avail = maxWidth - 6.dp
        // as many squares in a row as fit at 22 dp or wider (XL: 30 dp); wrapped rows share the notes evenly
        val fit = maxOf(1, floor(avail / (if (state.padianoSize == 3) 30.dp else 22.dp)).toInt())
        val sub = (one.size + fit - 1) / fit
        val perRow = (one.size + sub - 1) / sub
        val rows = sub * octs
        val rowH = listOf(48.dp, 64.dp, 86.dp, 124.dp)[state.padianoSize]
        // the note at row r, column c: block r / sub, wrapped line r % sub of it
        fun noteAt(r: Int, c: Int): Int? {
            val b = blocks.getOrNull(r / sub) ?: return null
            val line = r % sub
            val inRow = if (line == sub - 1) one.size - line * perRow else perRow
            return if (c < 0 || c >= inRow) null else b.getOrNull(line * perRow + c)
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .padding(end = 6.dp, bottom = 6.dp)
                .height(rowH * rows)
                .sticker(C.Ink, shadow = 5.dp, shadowColor = col)
                .tagged("piano-keys-$pad")
                .trackOrigin(origin)
                .playNotes(state, pad, listOf(notes, perRow, sub), origin, { o ->
                    if (o.x < 0 || o.x > size.width || o.y < 0 || o.y > size.height) null
                    else {
                        val r = (o.y / (size.height / rows.toFloat())).toInt().coerceIn(0, rows - 1)
                        val c = (o.x / (size.width / perRow.toFloat())).toInt()
                        noteAt(r, c)
                    }
                }) { fingers = it },
        ) {
            val L = NoteLook(state, pad, fingers, notes)
            val w = size.width / perRow
            val h = size.height / rows
            for (r in 0 until rows) for (c in 0 until perRow) {
                val p = noteAt(r, c) ?: continue
                val i = (r % sub) * perRow + c
                val x = c * w
                val y = r * h
                val blk = isBlack(p)
                val inS = L.inScale(p)
                val fill = when {
                    p in L.down -> col
                    p in L.chord && L.playsChord -> lerp(col, C.Cream, .25f)
                    blk -> if (inS) lerp(C.Card2, col, .35f) else lerp(C.Ink, C.Paper, .5f)
                    else -> if (inS) lerp(C.Cream, col, .18f) else lerp(C.Cream, C.Ink, .5f)
                }
                drawRect(fill, topLeft = Offset(x + 2f, y + 2f), size = Size(w - 4f, h - 4f))
                if (!L.chromatic && inS && p !in L.down) drawRect(col, topLeft = Offset(x + 4f, y + h - 9f), size = Size(w - 8f, 5f))
                if (L.isRoot(p)) drawCircle(C.Pink, radius = minOf(w, h) * .11f, center = Offset(x + w / 2, y + h * .62f))
                if (p in L.chord && !L.playsChord) drawRect(C.Pink, topLeft = Offset(x + 3f, y + 3f), size = Size(w - 6f, h - 6f), style = Stroke(width = 5f))
                if (p in L.holding) drawRect(C.Yellow, topLeft = Offset(x + 4f, y + 4f), size = Size(w - 8f, 8f))
                drawRect(C.Black, topLeft = Offset(x + 1f, y + 1f), size = Size(w - 2f, h - 2f), style = Stroke(width = 2.5f))
                if (Theory.pcOf(p) == 0 || L.isRoot(p) || i == 0) {
                    drawText(tm, Theory.noteName(p), topLeft = Offset(x + 4f, y + 5f),
                        style = TextStyle(color = if (blk && p !in L.down) C.Cream else C.Ink, fontSize = 9.sp, fontFamily = Mono, fontWeight = FontWeight.Bold))
                }
            }
        }
    }
    Note(
        "${Theory.NN[P.root]} ${P.scale.lowercase()} · " + (if (state.chordMode == "off") "single notes" else chordName(state.chordMode).lowercase() + if (state.chordPlay) ", playing" else ", showing") +
            " · ${Theory.noteName(one.first())} to ${Theory.noteName(one.last() + 12 * (octs - 1))}" + (if (octs > 1) " · $octs rows" else "") + if (state.inKeyOnly) " · in key only" else "",
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** Keys: the piano, two octaves around the sound's own pitch, about an octave and a half on screen. */
@Composable
fun PadianoKeys(state: AppState, pad: Int, col: Color, keysHeight: Dp) {
    val P = state.project
    val whites = (Theory.ROLL_LO..Theory.ROLL_HI).filter { !isBlack(it) }
    var start by remember(pad) { mutableIntStateOf(-1) }
    // one canvas per row (rows up are an octave higher each), each with its own fingers and place on screen
    val fingersBy = remember(pad) { androidx.compose.runtime.mutableStateMapOf<Int, Set<Int>>() }
    val origins = remember { List(3) { Origin() } }
    val octs = state.padianoRows
    val tm = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val avail = maxWidth - 6.dp
        val wk = maxOf(if (state.padianoSize == 3) 44.dp else 34.dp, avail / whites.size)
        val visible = floor(avail / wk).toInt().coerceIn(1, whites.size)
        val maxStart = whites.size - visible
        val first = if (start < 0) (whites.indexOf(0) - 2).coerceIn(0, maxStart) else start.coerceIn(0, maxStart)
        val shown0 = whites.subList(first, first + visible)
        Column {
            Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Btn("◀", { start = (first - 3).coerceAtLeast(0) }, small = true, enabled = first > 0)
                Btn("▶", { start = (first + 3).coerceAtMost(maxStart) }, small = true, enabled = first < maxStart)
            }
            for (o in octs - 1 downTo 0) {
                val shown = shown0.map { it + 12 * o }
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .padding(end = 6.dp, bottom = 6.dp)
                        .height(keysHeight)
                        .sticker(C.Ink, shadow = 6.dp, shadowColor = col)
                        .tagged(if (o == 0) "piano-keys-$pad" else "piano-keys-$pad-r$o")
                        .trackOrigin(origins[o])
                        .playNotes(state, pad, listOf(first, visible, o), origins[o], { at ->
                            val ww = size.width / visible.toFloat()
                            val bh = size.height * .6f
                            if (at.x < 0 || at.x > size.width || at.y < 0 || at.y > size.height) null
                            else {
                                val wi = floor(at.x / ww).toInt().coerceIn(0, visible - 1)
                                val edge = (at.x / ww) - wi
                                val left = shown[wi] - 1
                                val right = shown[wi] + 1
                                when {
                                    at.y < bh && edge < .3f && wi > 0 && isBlack(left) -> left
                                    at.y < bh && edge > .7f && wi < visible - 1 && isBlack(right) -> right
                                    else -> shown[wi]
                                }
                            }
                        }) { fingersBy[o] = it },
                ) {
                    val L = NoteLook(state, pad, fingersBy[o] ?: emptySet(), (shown.first()..shown.last() + 1).toList())
                    val ww = size.width / visible
                    val bh = size.height * .6f
                    val bw = ww * .6f
                    shown.forEachIndexed { i, p ->
                        val x = i * ww
                        val inS = L.inScale(p)
                        val root = L.isRoot(p)
                        val fill = when {
                            p in L.down -> col
                            p in L.chord && L.playsChord -> lerp(col, C.Cream, .25f)
                            root -> lerp(C.Cream, col, .5f)
                            L.chromatic -> C.Cream
                            inS -> lerp(C.Cream, col, .14f)
                            else -> lerp(C.Cream, C.Ink, .5f)
                        }
                        drawRect(fill, topLeft = Offset(x, 0f), size = Size(ww, size.height))
                        if (!L.chromatic && inS && p !in L.down) drawRect(Brush.verticalGradient(listOf(Color.Transparent, col.copy(alpha = .55f)), startY = size.height * .55f, endY = size.height), topLeft = Offset(x, size.height * .55f), size = Size(ww, size.height * .45f))
                        if (root) drawCircle(C.Pink, radius = ww * .13f, center = Offset(x + ww / 2, size.height - ww * .55f))
                        if (p in L.chord && !L.playsChord) {
                            drawRect(C.Pink.copy(alpha = .28f), topLeft = Offset(x, 0f), size = Size(ww, size.height))
                            drawRect(C.Pink, topLeft = Offset(x + 3f, 3f), size = Size(ww - 6f, size.height - 6f), style = Stroke(width = 5f))
                        }
                        if (p in L.holding) drawRect(C.Yellow, topLeft = Offset(x + 3f, 3f), size = Size(ww - 6f, 10f))
                        drawRect(C.Black, topLeft = Offset(x, 0f), size = Size(ww, size.height), style = Stroke(width = 3f))
                        if (Theory.pcOf(p) == 0 || root) {
                            drawText(tm, Theory.noteName(p), topLeft = Offset(x + 4f, size.height - 34f),
                                style = TextStyle(color = C.Ink, fontSize = 10.sp, fontFamily = Mono, fontWeight = FontWeight.Bold))
                        }
                    }
                    for (i in 0 until visible - 1) {
                        val b = shown[i] + 1
                        if (!isBlack(b)) continue
                        val x = (i + 1) * ww - bw / 2
                        val inS = L.inScale(b)
                        val fill = when {
                            b in L.down -> col
                            b in L.chord && L.playsChord -> lerp(col, C.Ink, .2f)
                            L.isRoot(b) -> lerp(C.Ink, col, .65f)
                            L.chromatic -> C.Ink
                            inS -> lerp(C.Ink, col, .3f)
                            else -> lerp(C.Ink, C.Paper, .6f)
                        }
                        drawRect(C.Black, topLeft = Offset(x + 3f, 3f), size = Size(bw, bh))
                        drawRect(fill, topLeft = Offset(x, 0f), size = Size(bw, bh))
                        if (b in L.holding) drawRect(C.Yellow, topLeft = Offset(x + 3f, 3f), size = Size(bw - 6f, 10f))
                        if (!L.chromatic && inS && b !in L.down) drawRect(col, topLeft = Offset(x + 3f, bh - 8f), size = Size(bw - 6f, 5f))
                        if (L.isRoot(b)) drawCircle(C.Pink, radius = bw * .16f, center = Offset(x + bw / 2, bh - bw * .45f))
                        if (b in L.chord && !L.playsChord) drawRect(C.Pink, topLeft = Offset(x + 2f, 2f), size = Size(bw - 4f, bh - 4f), style = Stroke(width = 5f))
                        drawRect(if (!L.chromatic && !inS) C.Empty else C.Black, topLeft = Offset(x, 0f), size = Size(bw, bh), style = Stroke(width = 3f))
                    }
                }
            }
            Note(
                "${Theory.NN[P.root]} ${P.scale.lowercase()} · " + (if (state.chordMode == "off") "single notes" else chordName(state.chordMode).lowercase() + if (state.chordPlay) ", playing" else ", showing") +
                    " · ${Theory.noteName(shown0.first())} to ${Theory.noteName(shown0.last() + 12 * (octs - 1))}" + if (octs > 1) " · $octs rows" else "",
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * A filter and resonance for one pad only, to play while you play: slide left to close the cutoff,
 * up for more resonance. Lift and it opens again, unless Stay is on.
 */
@Composable
fun PadFilterStrip(state: AppState, pad: Int, modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 58.dp) {
    val k = state.project.kit[pad]
    val col = if (k.isEmpty()) C.Teal else padColor(Color(state.lib.colorOf(k)))
    val at = state.padFilt[pad]
    val stay = pad in state.padFiltStay
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
            T("FILTER", size = 12.sp, color = if (at != null) C.Yellow else C.Dim, spacing = .1.em)
            Note(if (at == null) "open · pad ${pad + 1} ${state.lib.titleOf(k).lowercase()} only" else "cut ${(at.first * 100).toInt()} · res ${(at.second * 100).toInt()}", color = if (at != null) C.Cream else C.Dim, modifier = Modifier.weight(1f))
            Btn(if (stay) "Stay on" else "Stay", { state.switchFiltStay(pad) }, on = stay, onColor = C.Teal, small = true, tag = "pfilt-stay-$pad")
            if (stay && at != null) Btn("Open", { state.openPadFilter(pad) }, small = true, tag = "pfilt-open-$pad")
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .padding(end = 6.dp, bottom = 5.dp)
                .sticker(C.Black, shadow = 5.dp, shadowColor = col)
                .tagged("pfilt-$pad")
                .pointerInput(pad) {
                    awaitEachGesture {
                        val d = awaitFirstDown(requireUnconsumed = false)
                        fun send(p: Offset, on: Boolean) {
                            val x = (p.x / size.width).coerceIn(0f, 1f)
                            val y = 1f - (p.y / size.height).coerceIn(0f, 1f)
                            state.padFilter(pad, x, y, on)
                        }
                        send(d.position, true)
                        d.consume()
                        var last = d.position
                        while (true) {
                            val e = awaitPointerEvent()
                            val c = e.changes.firstOrNull { it.id == d.id } ?: e.changes.first()
                            if (!c.pressed) break
                            if (c.positionChange() != Offset.Zero) { last = c.position; send(c.position, true) }
                            c.consume()
                        }
                        send(last, false)
                    }
                },
        ) {
            // a dark-to-bright ramp: left is muffled, right is open
            drawRect(Brush.horizontalGradient(listOf(lerp(C.Ink, col, .15f), lerp(C.Ink, col, .55f))))
            for (i in 1..7) drawLine(C.Card2, Offset(size.width * i / 8, 0f), Offset(size.width * i / 8, size.height), strokeWidth = 1.5f)
            if (at != null) {
                val cx = at.first * size.width
                val cy = (1f - at.second) * size.height
                drawRect(C.Black.copy(alpha = .45f), topLeft = Offset(cx, 0f), size = Size(size.width - cx, size.height))
                drawLine(C.Pink, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 4f)
                drawCircle(C.Pink, radius = 16f, center = Offset(cx, cy))
                drawCircle(C.Cream, radius = 16f, center = Offset(cx, cy), style = Stroke(width = 4f))
            }
        }
    }
}
