package com.tideclock.messmachine.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tideclock.messmachine.AppState
import com.tideclock.messmachine.FX_NAMES
import com.tideclock.messmachine.XY_TARGETS
import com.tideclock.messmachine.XyDir
import com.tideclock.messmachine.XySetup
import com.tideclock.messmachine.xyTarget
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/* ================= perform effects you can play, slide and edit ================= */

/**
 * One perform effect: hold it (or tap, if it stays on), slide along it to set how much, tap ✎ in the
 * corner for its settings. A bar along the bottom shows the amount; a tag shows where it acts.
 */
@Composable
fun FxButton(state: AppState, id: String, label: String, modifier: Modifier, color: Color, height: Dp = 58.dp) {
    var held by remember { mutableStateOf(false) }
    val s = state.fxSet(id)
    val lit = held || id in state.fxOn
    val amt = state.fxLive[id] ?: s.amount
    Box(
        modifier
            .height(height)
            .padding(end = 4.dp, bottom = 4.dp)
            .sticker(if (lit) color else C.Ink, shadow = if (lit) 0.dp else 4.dp, shadowColor = color)
            .pointerInput(id) {
                awaitEachGesture {
                    val d = awaitFirstDown(requireUnconsumed = false)
                    // the ✎ corner is for settings, not for playing
                    if (d.position.x > size.width - 26.dp.toPx() && d.position.y < 26.dp.toPx()) return@awaitEachGesture
                    d.consume()
                    held = true
                    val slider = state.fxSet(id).behave == "slider"
                    if (slider) state.fxPress(id, true, (d.position.x / size.width).coerceIn(0f, 1f)) else state.fxPress(id, true)
                    var slid = false
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == d.id } ?: break
                        if (!c.pressed) { c.consume(); break }
                        if (abs(c.position.x - d.position.x) > 12.dp.toPx()) slid = true
                        if (slid || slider) state.fxSlide(id, (c.position.x / size.width).coerceIn(0f, 1f))
                        c.consume()
                    }
                    held = false
                    if (!slider) state.fxPress(id, false)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(amt.coerceIn(0.02f, 1f)).height(4.dp).background(if (lit) C.Ink.copy(alpha = .5f) else color.copy(alpha = .8f)))
        T(label.uppercase(), size = 12.sp, color = if (lit) ink(color) else C.Cream, lines = 1, spacing = .06.em)
        if (s.target >= 0 || s.behave != "held") {
            T((if (s.target >= 0) state.fxTargetName(s.target).replace("Pad ", "P").replace("Bus ", "B") else "") + (if (s.behave == "stays") " ●" else if (s.behave == "slider") " ═" else ""),
                size = 9.sp, color = if (lit) C.Ink else C.Yellow, family = Mono, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = 6.dp))
        }
        Box(Modifier.align(Alignment.TopEnd).size(24.dp).clickable { state.fxEditing = if (state.fxEditing == id) null else id }.testTag("fxedit-$id"), contentAlignment = Alignment.Center) {
            T("✎", size = 11.sp, color = if (lit) C.Ink else C.Dim)
        }
    }
}

/** Settings for the effect being edited: amount, how it behaves, where it acts, and the gate's length. */
@Composable
fun FxEditor(state: AppState) {
    val id = state.fxEditing ?: return
    val name = FX_NAMES.firstOrNull { it.first == id }?.second ?: id
    val s = state.fxSet(id)
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).sticker(C.Black, shadow = 0.dp, border = 2.dp, borderColor = C.Yellow).padding(10.dp).testTag("fx-editor")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("EDIT ${name.uppercase()}", size = 12.sp, color = C.Yellow, spacing = .08.em, modifier = Modifier.weight(1f))
            Btn("Done", { state.fxEditing = null }, small = true, tag = "fx-editor-done")
        }
        SliderRow("Amount", s.amount, 0f..1f, { "${(it * 100).toInt()}%" }, { v -> state.changeFx(id, s.copy(amount = v)) }, {})
        Note("How it plays", modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
        Wrap(gap = 6.dp) {
            for ((b, n) in listOf("held" to "While held", "stays" to "Stays on", "slider" to "Slider")) {
                Btn(n, { state.changeFx(id, s.copy(behave = b)) }, on = s.behave == b, onColor = C.Teal, small = true, tag = "fxb-$id-$b")
            }
        }
        Note("Where it acts", modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
        Wrap(gap = 6.dp) {
            Btn("Mix", { state.changeFx(id, s.copy(target = -1)) }, on = s.target < 0, onColor = C.Pink, small = true, tag = "fxt-$id-mix")
            for (b in 1..4) Btn("Bus $b", { state.changeFx(id, s.copy(target = 64 + b)) }, on = s.target == 64 + b, onColor = C.Pink, small = true, tag = "fxt-$id-bus$b")
            val sel = state.project.sel
            Btn("Pad ${sel + 1}", { state.changeFx(id, s.copy(target = sel)) }, on = s.target == sel, onColor = C.Pink, small = true, tag = "fxt-$id-pad")
            if (s.target in 0..63 && s.target != sel) Btn("Pad ${s.target + 1}", {}, on = true, onColor = C.Pink, small = true)
        }
        if (id == "gate") {
            Note("Gate length", modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
            Wrap(gap = 6.dp) { for ((st, n) in listOf(1 to "1/16", 2 to "1/8", 4 to "1/4")) Btn(n, { state.changeFx(id, s.copy(steps = st)) }, on = s.steps == st, small = true) }
        }
        Help("Pads go to a bus from their menu (Route). Slide along an effect while you hold it to change how much it does.", modifier = Modifier.padding(top = 8.dp))
    }
}

/* ================= a pad's XY ================= */

/** The size of a pad's XY in each place, S M L. */
fun xyDp(size: Int): Dp = listOf(130.dp, 190.dp, 260.dp)[size.coerceIn(0, 2)]

/**
 * A pad's own XY surface, square or ring. Two-way: zero at the bottom left. Four-way: zero in the middle
 * with a small dead zone. Springs back on lift unless the setup stays.
 */
@Composable
fun PadXy(state: AppState, pad: Int, modifier: Modifier = Modifier, tag: String = "xyp-$pad", edge: Dp? = null) {
    val x = state.xyOf(pad)
    val live = state.xyLive[pad]
    val k = state.project.kit.getOrElse(pad) { "" }
    val col = if (k.isEmpty()) C.Teal else padColor(Color(state.lib.colorOf(k)))
    val side = edge ?: xyDp(x.size)
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
            T("XY ${pad + 1} · ${x.name.uppercase()}", size = 11.sp, color = C.Yellow, spacing = .06.em, lines = 1, modifier = Modifier.weight(1f))
            T(if (x.four) "4-WAY" else "2-WAY", size = 9.sp, color = C.Dim, family = Mono)
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .widthIn(max = side * 1.6f)
                .height(side)
                .padding(end = 6.dp, bottom = 6.dp)
                .then(if (x.ring) Modifier else Modifier.sticker(C.Paper, shadow = 5.dp, shadowColor = col))
                .testTag(tag)
                .pointerInput(pad, x) {
                    awaitEachGesture {
                        val d = awaitFirstDown(requireUnconsumed = false)
                        fun map(p: Offset): Pair<Float, Float> {
                            val w = size.width.toFloat(); val h = size.height.toFloat()
                            return if (x.four) ((p.x - w / 2) / (w / 2)).coerceIn(-1f, 1f) to ((h / 2 - p.y) / (h / 2)).coerceIn(-1f, 1f)
                            else (p.x / w).coerceIn(0f, 1f) to (1f - p.y / h).coerceIn(0f, 1f)
                        }
                        d.consume()
                        var last = map(d.position)
                        state.xyMove(pad, last.first, last.second, true)
                        while (true) {
                            val e = awaitPointerEvent()
                            val c = e.changes.firstOrNull { it.id == d.id } ?: break
                            if (!c.pressed) { c.consume(); break }
                            last = map(c.position)
                            state.xyMove(pad, last.first, last.second, true)
                            c.consume()
                        }
                        state.xyMove(pad, last.first, last.second, false)
                    }
                },
        ) {
            val cx = size.width / 2; val cy = size.height / 2
            if (x.ring) {
                val r = minOf(cx, cy)
                drawCircle(C.Paper, radius = r, center = Offset(cx, cy))
                drawCircle(col, radius = r, center = Offset(cx, cy), style = Stroke(4.dp.toPx()))
            }
            for (i in 1..3) {
                drawLine(C.Card2, Offset(size.width * i / 4, 0f), Offset(size.width * i / 4, size.height), strokeWidth = 1.5f)
                drawLine(C.Card2, Offset(0f, size.height * i / 4), Offset(size.width, size.height * i / 4), strokeWidth = 1.5f)
            }
            if (x.four) drawCircle(C.Empty, radius = minOf(cx, cy) * .12f, center = Offset(cx, cy), style = Stroke(2f))
            live?.let { (fx, fy) ->
                val px = if (x.four) cx + fx * cx else fx * size.width
                val py = if (x.four) cy - fy * cy else (1 - fy) * size.height
                drawLine(C.Pink.copy(alpha = .5f), Offset(px, 0f), Offset(px, size.height), strokeWidth = 2f)
                drawLine(C.Pink.copy(alpha = .5f), Offset(0f, py), Offset(size.width, py), strokeWidth = 2f)
                drawCircle(C.Pink, radius = 20f, center = Offset(px, py))
                drawCircle(C.Cream, radius = 20f, center = Offset(px, py), style = Stroke(4f))
            }
        }
        Note(xyLegend(x), size = 10.sp)
    }
}

fun xyLegend(x: XySetup): String {
    fun n(d: XyDir) = if (d.target < 0) "nothing" else xyTarget(d.target).name.lowercase()
    return if (x.four) "→ ${n(x.dirs[0])} · ↑ ${n(x.dirs[1])} · ← ${n(x.dirs[2])} · ↓ ${n(x.dirs[3])}"
    else "→ ${n(x.dirs[0])} · ↑ ${n(x.dirs[1])}"
}

/** The XY setup sheet for one pad: presets, two-way or four-way, what each direction moves and how far. */
@Composable
fun XyEditor(state: AppState, pad: Int) {
    val x = state.xyOf(pad)
    ScrollPane(hPad = 14.dp, vPad = 0.dp, tag = "xy-editor") {
        Help("Pad ${pad + 1}'s XY. It belongs to the pad, so it stays when you swap sounds. Draw a circle on the pad or one of its Padiano notes to play it anywhere.",
            modifier = Modifier.padding(bottom = 10.dp))
        T("PRESETS", size = 12.sp, color = C.Yellow, spacing = .1.em)
        Wrap(Modifier.padding(top = 6.dp, bottom = 12.dp), gap = 6.dp) {
            for (p in state.allXy()) Btn(p.name, { state.setXy(pad, p) }, on = x.name == p.name, onColor = C.Teal, small = true, tag = "xy-preset-${p.name}")
        }
        Wrap(Modifier.padding(bottom = 10.dp), gap = 6.dp) {
            Btn("Two-way", { state.setXy(pad, x.copy(four = false)) }, on = !x.four, onColor = C.Pink, small = true, tag = "xy-two")
            Btn("Four-way", { state.setXy(pad, x.copy(four = true, size = maxOf(1, x.size))) }, on = x.four, onColor = C.Pink, small = true, tag = "xy-four")
            Btn(if (x.spring) "Springs back" else "Stays put", { state.setXy(pad, x.copy(spring = !x.spring)) }, small = true, tag = "xy-spring")
            Btn(if (x.ring) "Ring" else "Square", { state.setXy(pad, x.copy(ring = !x.ring)) }, small = true, tag = "xy-shape")
            for (z in (if (x.four) 1 else 0)..2) Btn("SML"[z].toString(), { state.setXy(pad, x.copy(size = z)) }, on = x.size == z, small = true)
            Btn(if (x.steps) "Pitch: scale steps" else "Pitch: smooth", { state.setXy(pad, x.copy(steps = !x.steps)) }, small = true, tag = "xy-steps")
        }
        val labels = if (x.four) listOf("Right →", "Up ↑", "Left ←", "Down ↓") else listOf("Across →", "Up ↑")
        for ((i, label) in labels.withIndex()) {
            val d = x.dirs[i]
            val t = xyTarget(d.target)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                T(label.uppercase(), size = 11.sp, color = C.Dim, spacing = .08.em, modifier = Modifier.weight(1f))
                Select(t.name, XY_TARGETS.map { it.id to it.name }, { id ->
                    val nd = x.dirs.toMutableList(); nd[i] = XyDir(id, xyTarget(id).def)
                    state.setXy(pad, x.copy(dirs = nd, name = "Custom"))
                }, modifier = Modifier.testTag("xy-dir-$i"))
            }
            if (d.target >= 0) SliderRow("At the edge", d.to, t.lo..t.hi, { v -> "%.2f ${t.unit}".format(v).trim() }, { v ->
                val nd = x.dirs.toMutableList(); nd[i] = d.copy(to = v)
                state.setXy(pad, x.copy(dirs = nd, name = "Custom"))
            }, {}, accent = C.Teal)
        }
        Wrap(Modifier.padding(top = 10.dp, bottom = 12.dp), gap = 6.dp) {
            Btn("Save as mine", { state.saveMyXy(x) }, small = true, color = C.Teal, tag = "xy-save")
        }
        PadXy(state, pad, Modifier.padding(bottom = 20.dp), tag = "xy-try")
    }
}

/* ================= circles drawn on pads and Padiano notes ================= */

/** Watches a finger's path and says when it has gone round in a circle (most of a turn, long enough). */
class CircleDetector(private val minPath: Float) {
    private var last: Offset? = null
    private var lastDir = Float.NaN
    private var turn = 0f
    private var path = 0f
    private val pts = ArrayList<Offset>()

    fun add(p: Offset): Boolean {
        val l = last
        if (l == null) { last = p; pts.add(p); return false }
        val dx = p.x - l.x; val dy = p.y - l.y
        val len = hypot(dx, dy)
        if (len < 3f) return false
        val dir = atan2(dy, dx)
        if (!lastDir.isNaN()) {
            var dd = dir - lastDir
            while (dd > PI) dd -= (2 * PI).toFloat()
            while (dd < -PI) dd += (2 * PI).toFloat()
            turn += dd
        }
        lastDir = dir; path += len; last = p; pts.add(p)
        return abs(turn) > 5.0f && path > minPath
    }

    fun center(): Offset {
        var sx = 0f; var sy = 0f
        for (p in pts) { sx += p.x; sy += p.y }
        return Offset(sx / pts.size, sy / pts.size)
    }
}

/** Where a node sits on the screen, kept outside of Compose state so it never redraws anything. */
class Origin { var at = Offset.Zero }

fun Modifier.trackOrigin(o: Origin): Modifier = this.onGloballyPositioned { o.at = it.positionInRoot() }

/** A finger at a screen position inside a sphere: the XY values it gives. */
fun sphereXy(s: AppState.Sphere, four: Boolean, at: Offset): Pair<Float, Float> =
    if (four) ((at.x - s.cx) / s.r).coerceIn(-1f, 1f) to ((s.cy - at.y) / s.r).coerceIn(-1f, 1f)
    else ((at.x - (s.cx - s.r)) / (2 * s.r)).coerceIn(0f, 1f) to (((s.cy + s.r) - at.y) / (2 * s.r)).coerceIn(0f, 1f)

/** The mostly clear sphere drawn over the screen while a circled pad or note is being played. */
@Composable
fun SphereOverlay(state: AppState) {
    val s = state.sphere ?: return
    val x = state.xyOf(s.pad)
    val live = state.xyLive[s.pad]
    val me = remember { Origin() }
    val k = state.project.kit.getOrElse(s.pad) { "" }
    val col = if (k.isEmpty()) C.Teal else padColor(Color(state.lib.colorOf(k)))
    Canvas(Modifier.fillMaxSize().trackOrigin(me).testTag("sphere")) {
        val c = Offset(s.cx - me.at.x, s.cy - me.at.y)
        drawCircle(col.copy(alpha = .16f), radius = s.r, center = c)
        drawCircle(C.Cream.copy(alpha = .7f), radius = s.r, center = c, style = Stroke(3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))))
        if (x.four) {
            drawLine(C.Cream.copy(alpha = .25f), Offset(c.x - s.r, c.y), Offset(c.x + s.r, c.y), strokeWidth = 2f)
            drawLine(C.Cream.copy(alpha = .25f), Offset(c.x, c.y - s.r), Offset(c.x, c.y + s.r), strokeWidth = 2f)
        }
        live?.let { (fx, fy) ->
            val p = if (x.four) Offset(c.x + fx * s.r, c.y - fy * s.r) else Offset(c.x - s.r + fx * 2 * s.r, c.y + s.r - fy * 2 * s.r)
            drawCircle(C.Pink, radius = 16f, center = p)
        }
    }
}
