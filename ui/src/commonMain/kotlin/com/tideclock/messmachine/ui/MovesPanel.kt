package com.tideclock.messmachine.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tideclock.messmachine.AppState
import com.tideclock.messmachine.XY_TARGETS
import com.tideclock.messmachine.engine.Project

/**
 * Moves (note #17): the recorded XY, FX and wheel moves of the loop as lanes you can see and change. Pick a lane
 * (which pad, what it moves), draw it with a finger or erase a stretch, Smooth a shaky take, or stamp a shape (hold,
 * ramps, triangle, sine, saw, square) every beat, half bar or bar, as strongly as you like. Each change is one undo.
 */
@Composable
fun MovesPanel(state: AppState, onClose: () -> Unit = { state.movesOpen = false }) {
    @Suppress("UNUSED_VARIABLE") val vv = state.version
    val P = state.project
    val lanes = state.moveLanes()
    var lane = state.moveLane.takeIf { it in lanes } ?: lanes.firstOrNull().orEmpty()
    var newPad by remember { mutableIntStateOf(P.sel) }
    var erase by remember { mutableStateOf(false) }
    var shape by remember { mutableIntStateOf(4) }
    var span by remember { mutableIntStateOf(4) }
    var strength by remember { mutableFloatStateOf(1f) }
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).sticker(C.Ink, shadow = 3.dp, borderColor = C.Teal).padding(10.dp).tagged("moves")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            T("AUTO", size = 12.sp, color = C.Yellow, spacing = .1.em)
            Select(if (lane.isEmpty()) "No lanes yet" else state.laneLabel(lane), lanes.map { it to state.laneLabel(it) }, { state.moveLane = it }, tag = "moves-lane", modifier = Modifier.weight(1f))
            // note (Nov 21): the lanes had no way out but the Auto button again
            CloseScrew(onClose, tag = "moves-close", label = "Close the lanes")
        }
        // a new lane: a pad and what it moves
        val loaded = (0 until Project.NPADS).filter { P.kit[it].isNotEmpty() }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            T("NEW", size = 11.sp, color = C.Dim, spacing = .1.em)
            Select("Pad ${newPad + 1}", loaded.map { it to "Pad ${it + 1} · ${state.lib.titleOf(P.kit[it])}" }, { newPad = it }, tag = "moves-newpad")
            Select("New lane… ▾", XY_TARGETS.filter { it.id >= 0 }.map { it.id to it.name }, { state.newLane(newPad, it) }, tag = "moves-newtarget")
        }
        if (lane.isEmpty()) {
            Note("Nothing recorded yet.", modifier = Modifier.padding(top = 8.dp))
            return@Column
        }
        // the whole loop at once (Nov 18): every step of every bar across the lane
        val off = 0
        val n = P.pat().len
        val t = com.tideclock.messmachine.xyTarget(lane.substringAfter(":").toIntOrNull() ?: -1)
        val vals = P.pat().motion[lane]
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(130.dp)
                .padding(top = 8.dp)
                .background(C.Well)
                .tagged("moves-lane-canvas")
                .pointerInput(lane, n, erase) {
                    awaitEachGesture {
                        val d = awaitFirstDown(requireUnconsumed = false)
                        d.consume()
                        fun put(o: Offset, commit: Boolean) {
                            val st = off + (o.x / (size.width / n.toFloat())).toInt().coerceIn(0, n - 1)
                            state.drawLane(lane, st, if (erase) null else (1f - o.y / size.height), commit)
                        }
                        put(d.position, false)
                        var last = d.position
                        while (true) {
                            val e = awaitPointerEvent()
                            val c = e.changes.firstOrNull { it.id == d.id } ?: break
                            if (!c.pressed) { c.consume(); break }
                            last = c.position
                            put(c.position, false)
                            c.consume()
                        }
                        put(last, true)
                    }
                },
        ) {
            val w = size.width / n
            for (c in 0..n) if (c % 4 == 0 || n <= 16) drawLine(if (c % 16 == 0) C.Cream.copy(alpha = .5f) else if (c % 4 == 0) C.Card2 else C.Ink, Offset(c * w, 0f), Offset(c * w, size.height), strokeWidth = if (c % 16 == 0) 3f else if (c % 4 == 0) 2f else 1f)
            drawLine(C.Card2, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 1f)
            var prev: Offset? = null
            for (c in 0 until n) {
                val v = vals?.getOrNull(off + c) ?: Float.NaN
                if (v.isNaN()) { prev = null; continue }
                val nv = ((v - t.lo) / (t.hi - t.lo)).coerceIn(0f, 1f)
                val y = size.height * (1 - nv)
                drawRect(C.Teal.copy(alpha = .35f), topLeft = Offset(c * w + (if (n > 32) 0f else 2f), y), size = Size(w - (if (n > 32) 0f else 4f), size.height - y))
                val pt = Offset(c * w + w / 2, y)
                prev?.let { drawLine(C.Teal, it, pt, strokeWidth = 4f) }
                if (n <= 32) drawCircle(C.Cream, 5f, pt)
                prev = pt
            }
            val now = if (state.playing && state.heardPat == P.cur) state.heardStep - off else -1
            if (now in 0 until n) drawRect(C.Yellow, topLeft = Offset(now * w, 0f), size = Size(3f, size.height))
            drawRect(C.Cream, style = Stroke(2f))
        }
        Note("${t.name}: bottom is ${t.lo}${t.unit}, top is ${t.hi}${t.unit}. the whole loop, ${n / 16} bar${if (n > 16) "s" else ""}.", modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Btn("Draw", { erase = false }, on = !erase, onColor = C.Teal, small = true, tight = true, tag = "moves-draw")
            Btn("Erase", { erase = true }, on = erase, onColor = C.Pink, small = true, tight = true, tag = "moves-erase")
            Btn("Smooth", { state.smoothLane(lane) }, small = true, tight = true, tag = "moves-smooth")
            Btn("Clear lane", { state.clearLane(lane) }, small = true, tight = true, tier = Tier.Danger, tag = "moves-clear")
        }
        // the quantize for curves: a shape stamped every beat, half bar or bar
        Wrap(Modifier.padding(top = 8.dp).tagged("moves-shape-chips"), gap = 6.dp) {
            for ((sh, n) in listOf(0 to "Hold", 1 to "Ramp ↑", 2 to "Ramp ↓", 3 to "Triangle", 4 to "Sine", 5 to "Saw", 6 to "Square")) {
                Btn(n, { shape = sh }, on = shape == sh, onColor = C.Yellow, small = true, tight = true, tag = "moves-shape-$sh")
            }
        }
        Row(Modifier.padding(top = 6.dp).tagged("moves-span-chips"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            T("EVERY", size = 11.sp, color = C.Dim, spacing = .1.em)
            for ((sp, n) in listOf(4 to "Beat", 8 to "Half bar", 16 to "Bar")) Btn(n, { span = sp }, on = span == sp, onColor = C.Yellow, small = true, tight = true, tag = "moves-span-$sp")
        }
        SliderRow("Strength", strength, 0f..1f, { "${(it * 100).toInt()}%" }, { strength = it }, { }, accent = C.Yellow)
        Btn("Stamp the shape", { state.stampLane(lane, shape, span, strength) }, small = true, color = C.Teal, tag = "moves-stamp")
    }
}
