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
import com.tideclock.messmachine.AppState
import com.tideclock.messmachine.engine.Theory
import kotlin.math.floor

/** Hold-to-use effects and the filter pad, like the site's Perform tab. */
@Composable
fun FxPanel(state: AppState) {
    val names = listOf(
        "stutter" to "Stutter", "echo" to "Echo throw", "crush" to "Crush",
        "tape" to "Tape stop", "gate" to "Gate", "dirty" to "Dirty",
        "ring" to "Ring mod", "oct" to "Octave down", "rev" to "Reverse",
    )
    Card(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Column {
            Heading("Perform", size = 18.sp)
            Note("Hold an effect while you play. Let go and it stops; echo tails keep ringing.", modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
            for (r in 0 until 3) {
                Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (c in 0 until 3) {
                        val (id, label) = names[r * 3 + c]
                        HoldButton(label, Modifier.weight(1f).testTag("fx-$id"), C.Letters[(r * 3 + c) % C.Letters.size]) { on -> state.fx(id, on) }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)) {
                T(if (state.xyHP) "THIN FILTER" else "MUD FILTER", size = 13.sp, color = C.Yellow, spacing = .1.em, modifier = Modifier.weight(1f))
                Btn("Low pass", { state.switchXyHP(false) }, on = !state.xyHP, small = true, onColor = C.Teal)
                Btn("High pass", { state.switchXyHP(true) }, on = state.xyHP, small = true, onColor = C.Teal)
            }
            XyPad(state)
            Note("Slide left and right for the cutoff, up for more resonance.", modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
fun HoldButton(label: String, modifier: Modifier, color: Color, onHold: (Boolean) -> Unit) {
    var held by remember { mutableStateOf(false) }
    Box(
        modifier
            .height(58.dp)
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
fun XyPad(state: AppState) {
    var dot by remember { mutableStateOf<Offset?>(null) }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(180.dp)
            .padding(end = 6.dp, bottom = 6.dp)
            .sticker(C.Paper, shadow = 6.dp, shadowColor = C.Teal)
            .testTag("xy")
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

/**
 * A piano for one pad, dropped below the grid. Two octaves around the sound's own pitch (C3 to C5),
 * at least an octave and a half on screen, slide or multi-finger, shaded for the scale.
 */
@Composable
fun PianoKeyboard(state: AppState, pad: Int) {
    val P = state.project
    val k = P.kit[pad]
    if (k.isEmpty()) return
    val col = padColor(Color(state.lib.colorOf(k)))
    val whites = (Theory.ROLL_LO..Theory.ROLL_HI).filter { !isBlack(it) }
    var start by remember(pad) { mutableIntStateOf(-1) }
    var held by remember(pad) { mutableStateOf(setOf<Int>()) }
    val tm = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp).testTag("piano-$pad")) {
        val avail = maxWidth - 6.dp
        val wk = maxOf(34.dp, avail / whites.size)
        val visible = floor(avail / wk).toInt().coerceIn(1, whites.size)
        val maxStart = whites.size - visible
        val first = if (start < 0) (whites.indexOf(0) - 2).coerceIn(0, maxStart) else start.coerceIn(0, maxStart)
        val shown = whites.subList(first, first + visible)
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Swatch(col)
                T(" PAD ${pad + 1} · ${state.lib.titleOf(k).uppercase()}", size = 12.sp, color = C.Yellow, lines = 1, spacing = .06.em, modifier = Modifier.weight(1f))
                Btn("◀", { start = (first - 3).coerceAtLeast(0) }, small = true, enabled = first > 0)
                Btn("▶", { start = (first + 3).coerceAtMost(maxStart) }, small = true, enabled = first < maxStart, modifier = Modifier.padding(start = 6.dp))
                Btn("✕", { state.toggleKeyboard(pad) }, small = true, color = C.Riot, modifier = Modifier.padding(start = 6.dp), tag = "piano-close-$pad")
            }
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .padding(end = 6.dp, bottom = 6.dp)
                    .height(132.dp)
                    .sticker(C.Ink, shadow = 6.dp, shadowColor = col)
                    .testTag("piano-keys-$pad")
                    .pointerInput(pad, first, visible) {
                        val ww = size.width / visible.toFloat()
                        val bh = size.height * .6f
                        fun hit(o: Offset): Int? {
                            if (o.x < 0 || o.x > size.width || o.y < 0 || o.y > size.height) return null
                            val wi = floor(o.x / ww).toInt().coerceIn(0, visible - 1)
                            if (o.y < bh) {
                                // a black key sits on the line between two whites
                                val edge = (o.x / ww) - wi
                                val left = shown[wi] - 1
                                val right = shown[wi] + 1
                                if (edge < .3f && wi > 0 && isBlack(left)) return left
                                if (edge > .7f && wi < visible - 1 && isBlack(right)) return right
                            }
                            return shown[wi]
                        }
                        awaitEachGesture {
                            val active = HashMap<PointerId, Int>()
                            do {
                                val ev = awaitPointerEvent()
                                for (c in ev.changes) {
                                    val cur = active[c.id]
                                    if (c.pressed) {
                                        val p = hit(c.position)
                                        if (p != cur) {
                                            if (cur != null) state.pianoUp(pad, cur)
                                            if (p != null) { state.pianoDown(pad, p); active[c.id] = p } else active.remove(c.id)
                                        }
                                        c.consume()
                                    } else if (cur != null) {
                                        state.pianoUp(pad, cur)
                                        active.remove(c.id)
                                    }
                                }
                                held = active.values.toSet()
                            } while (ev.changes.any { it.pressed })
                            for (p in active.values) state.pianoUp(pad, p)
                            held = emptySet()
                        }
                    },
            ) {
                val ww = size.width / visible
                val bh = size.height * .6f
                val bw = ww * .6f
                val chromatic = P.scale == "Chromatic"
                val holding = state.heldPitches(pad)
                val down = held + holding
                val chord = HashSet<Int>()
                for (h in down) for (c in state.chordFor(h)) if (c !in down) chord.add(c)
                val playsChord = state.chordPlay
                shown.forEachIndexed { i, p ->
                    val x = i * ww
                    val inS = chromatic || Theory.inScale(p, P.scale, P.root)
                    val root = Theory.pcOf(p - P.root) == 0
                    val fill = when {
                        p in down -> col
                        p in chord && playsChord -> lerp(col, C.Cream, .25f)
                        root -> lerp(C.Cream, col, .5f)
                        chromatic -> C.Cream
                        inS -> lerp(C.Cream, col, .14f)
                        else -> lerp(C.Cream, C.Ink, .5f)
                    }
                    drawRect(fill, topLeft = Offset(x, 0f), size = Size(ww, size.height))
                    // in the scale: a soft glow along the bottom of the key
                    if (!chromatic && inS && p !in down) drawRect(Brush.verticalGradient(listOf(Color.Transparent, col.copy(alpha = .55f)), startY = size.height * .55f, endY = size.height), topLeft = Offset(x, size.height * .55f), size = Size(ww, size.height * .45f))
                    // the root: a dot
                    if (root) drawCircle(C.Pink, radius = ww * .13f, center = Offset(x + ww / 2, size.height - ww * .55f))
                    // a chord tone you can play yourself: a glowing ring
                    if (p in chord && !playsChord) {
                        drawRect(C.Pink.copy(alpha = .28f), topLeft = Offset(x, 0f), size = Size(ww, size.height))
                        drawRect(C.Pink, topLeft = Offset(x + 3f, 3f), size = Size(ww - 6f, size.height - 6f), style = Stroke(width = 5f))
                    }
                    // holding: a yellow bar across the top of the key
                    if (p in holding) drawRect(C.Yellow, topLeft = Offset(x + 3f, 3f), size = Size(ww - 6f, 10f))
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
                    val inS = chromatic || Theory.inScale(b, P.scale, P.root)
                    val fill = when {
                        b in down -> col
                        b in chord && playsChord -> lerp(col, C.Ink, .2f)
                        Theory.pcOf(b - P.root) == 0 -> lerp(C.Ink, col, .65f)
                        chromatic -> C.Ink
                        inS -> lerp(C.Ink, col, .3f)
                        else -> lerp(C.Ink, C.Paper, .6f)
                    }
                    drawRect(C.Black, topLeft = Offset(x + 3f, 3f), size = Size(bw, bh))
                    drawRect(fill, topLeft = Offset(x, 0f), size = Size(bw, bh))
                    if (b in holding) drawRect(C.Yellow, topLeft = Offset(x + 3f, 3f), size = Size(bw - 6f, 10f))
                    if (!chromatic && inS && b !in down) drawRect(col, topLeft = Offset(x + 3f, bh - 8f), size = Size(bw - 6f, 5f))
                    if (Theory.pcOf(b - P.root) == 0) drawCircle(C.Pink, radius = bw * .16f, center = Offset(x + bw / 2, bh - bw * .45f))
                    if (b in chord && !playsChord) drawRect(C.Pink, topLeft = Offset(x + 2f, 2f), size = Size(bw - 4f, bh - 4f), style = Stroke(width = 5f))
                    drawRect(if (!chromatic && !inS) C.Empty else C.Black, topLeft = Offset(x, 0f), size = Size(bw, bh), style = Stroke(width = 3f))
                }
            }
            Note(
                "${Theory.NN[P.root]} ${P.scale.lowercase()} · " + (if (state.chordMode == "off") "single notes" else chordName(state.chordMode).lowercase() + if (state.chordPlay) ", playing" else ", showing") +
                    " · ${Theory.noteName(shown.first())} to ${Theory.noteName(shown.last())} · ◀ ▶ to shift",
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
