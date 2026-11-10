package com.tideclock.messmachine.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tideclock.messmachine.AppState
import com.tideclock.messmachine.engine.Theory

/** Hold-to-use effects and the filter pad, like the site's live bus. */
@Composable
fun FxPanel(state: AppState) {
    val names = listOf(
        "stutter" to "Stutter", "echo" to "Echo throw", "crush" to "Crush",
        "tape" to "Tape stop", "gate" to "Gate", "dirty" to "Dirt",
        "ring" to "Ring mod", "oct" to "Octave down", "rev" to "Reverse",
    )
    Card(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Column {
            T("Hold an effect while you play. Let go and it stops; echo tails keep ringing.", size = 12.sp, color = C.Dim, weight = FontWeight.Medium, modifier = Modifier.padding(bottom = 8.dp))
            for (r in 0 until 3) {
                Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in 0 until 3) {
                        val (id, label) = names[r * 3 + c]
                        HoldButton(label, Modifier.weight(1f), C.Letters[(r * 3 + c) % C.Letters.size]) { on -> state.fx(id, on) }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)) {
                T(if (state.xyHP) "Thin filter" else "Mud filter", size = 14.sp, modifier = Modifier.weight(1f))
                Btn("Low pass", { state.switchXyHP(false) }, on = !state.xyHP, small = true, onColor = C.Teal)
                Btn("High pass", { state.switchXyHP(true) }, on = state.xyHP, small = true, onColor = C.Teal)
            }
            XyPad(state)
            T("Slide left and right for the cutoff, up for more resonance.", size = 12.sp, color = C.Dim, weight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
fun HoldButton(label: String, modifier: Modifier, color: Color, onHold: (Boolean) -> Unit) {
    var held by remember { mutableStateOf(false) }
    Box(
        modifier
            .height(54.dp)
            .sticker(if (held) color else C.Card2, radius = 12.dp, shadow = if (held) 1.dp else 3.dp)
            .pointerInput(label) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    held = true
                    onHold(true)
                    // stay on until every finger on this button is up
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
        T(label, size = 13.sp, color = if (held) ink(color) else C.Cream, lines = 1)
    }
}

@Composable
fun XyPad(state: AppState) {
    var dot by remember { mutableStateOf<Offset?>(null) }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(170.dp)
            .sticker(C.Ink, radius = 14.dp)
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
            drawCircle(C.Pink, radius = 22f, center = c)
            drawCircle(C.Black, radius = 22f, center = c, style = Stroke(width = 4f))
        }
    }
}

/** The site's keys strip: every in-scale note across two octaves, for the pad you picked. */
@Composable
fun KeysStrip(state: AppState) {
    @Suppress("UNUSED_VARIABLE") val sv = state.selV
    val P = state.project
    val notes = state.stripNotes()
    val k = state.selKey()
    val col = if (k.isEmpty()) C.Empty else Color(state.lib.colorOf(k))
    Column(Modifier.padding(top = 12.dp)) {
        T(if (k.isEmpty()) "Pick a pad with a sound on it" else "Keys strip · pad ${P.sel + 1} · ${state.lib.titleOf(k)}", size = 12.sp, color = C.Dim)
        Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in notes.indices) {
                val root = Theory.pcOf(notes[i] - P.root) == 0
                var pressed by remember(i) { mutableStateOf(false) }
                Box(
                    Modifier
                        .width(48.dp)
                        .height(96.dp)
                        .sticker(if (pressed) col else if (root) lerp(C.Cream, col, .35f) else C.Cream, radius = 8.dp, shadow = if (pressed) 1.dp else 3.dp)
                        .pointerInput(i, notes.size) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                pressed = true
                                state.stripDown(i)
                                while (true) {
                                    val e = awaitPointerEvent()
                                    if (e.changes.none { it.pressed }) break
                                }
                                pressed = false
                                state.stripUp(i)
                            }
                        }
                        .padding(4.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    T(state.stripLabel(i), size = 10.sp, color = C.Ink, lines = 2)
                }
            }
        }
    }
}
