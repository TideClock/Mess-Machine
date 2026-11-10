package com.tideclock.messmachine.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tideclock.messmachine.AppState
import com.tideclock.messmachine.engine.SoundEdit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/** The doctor's table: trim, tune, shape and tone for one sound. Non-destructive, like the site. */
@Composable
fun DoctorScreen(state: AppState, key: String) {
    var e by remember(key) { mutableStateOf(state.project.edit(key).copy()) }
    var wave by remember(key) { mutableStateOf<FloatArray?>(null) }
    var seconds by remember(key) { mutableStateOf(0.0) }
    LaunchedEffect(key) {
        val s = withContext(Dispatchers.IO) { try { state.lib.load(key) } catch (_: Exception) { null } }
        if (s != null) { wave = s.overview(240); seconds = s.seconds() }
    }
    fun upd(commit: Boolean, f: SoundEdit.() -> Unit) {
        val n = e.copy()
        n.f()
        e = n
        state.setEdit(key, n, commit)
        if (commit) state.preview(key)
    }
    val col = Color(state.lib.colorOf(key))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        T(state.lib.titleOf(key), size = 18.sp)
        T(
            "%.2f s · plays %.2f s".format(seconds, seconds * (e.en - e.st) / Math.pow(2.0, (e.tune + e.fine / 100) / 12)),
            size = 12.sp, color = C.Dim, weight = FontWeight.Medium, modifier = Modifier.padding(bottom = 8.dp),
        )
        // the waveform with trim handles
        var dragging by remember { mutableStateOf(0) }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(130.dp)
                .sticker(C.Ink, radius = 12.dp)
                .pointerInput(key) {
                    detectDragGestures(
                        onDragStart = { o ->
                            val f = (o.x / size.width).toDouble()
                            dragging = if (abs(f - e.st) <= abs(f - e.en)) 1 else 2
                        },
                        onDragEnd = { dragging = 0; upd(true) {} },
                        onDragCancel = { dragging = 0 },
                    ) { change, _ ->
                        val f = (change.position.x / size.width).toDouble().coerceIn(0.0, 1.0)
                        if (dragging == 1) upd(false) { st = minOf(f, en - .01) }
                        else if (dragging == 2) upd(false) { en = maxOf(f, st + .01) }
                    }
                },
        ) {
            val w = wave
            val mid = size.height / 2
            if (w != null) {
                val n = w.size / 2
                val bw = size.width / n
                for (c in 0 until n) {
                    val x = c * bw
                    val f = c / n.toFloat()
                    val inside = f >= e.st && f <= e.en
                    val lo = w[c * 2]
                    val hi = w[c * 2 + 1]
                    drawRect(if (inside) col else C.Card2, topLeft = Offset(x, mid - hi * mid * .92f), size = Size(maxOf(1f, bw - .5f), maxOf(1f, (hi - lo) * mid * .92f)))
                }
            }
            val xs = (e.st * size.width).toFloat()
            val xe = (e.en * size.width).toFloat()
            drawRect(C.Black.copy(alpha = .55f), topLeft = Offset(0f, 0f), size = Size(xs, size.height))
            drawRect(C.Black.copy(alpha = .55f), topLeft = Offset(xe, 0f), size = Size(size.width - xe, size.height))
            drawLine(C.Yellow, Offset(xs, 0f), Offset(xs, size.height), strokeWidth = 6f)
            drawLine(C.Pink, Offset(xe, 0f), Offset(xe, size.height), strokeWidth = 6f)
        }
        T("Drag the yellow line to trim the front, the pink one to trim the back.", size = 12.sp, color = C.Dim, weight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Hear it", { state.preview(key) }, color = C.Teal, small = true)
            Btn("Reverse", { upd(true) { rev = !rev } }, on = e.rev, small = true)
            Btn("Loop", { upd(true) { loop = !loop } }, on = e.loop, small = true)
            Btn(if (e.mode == "gate") "Gate" else "One shot", { upd(true) { mode = if (mode == "gate") "one" else "gate" } }, on = e.mode == "gate", small = true)
            Btn("Normalize", { upd(true) { norm = !norm } }, on = e.norm, small = true)
        }
        SliderRow("Volume", e.vol.toFloat(), 0f..2f, { "${(it * 100).roundToInt()}%" }, { v -> upd(false) { vol = v.toDouble() } }, { upd(true) {} })
        SliderRow("Tune", e.tune.toFloat(), -24f..24f, { "${if (it > 0) "+" else ""}${it.roundToInt()} st" }, { v -> upd(false) { tune = v.roundToInt().toDouble() } }, { upd(true) {} }, steps = 47)
        SliderRow("Fine", e.fine.toFloat(), -100f..100f, { "${it.roundToInt()} ct" }, { v -> upd(false) { fine = v.roundToInt().toDouble() } }, { upd(true) {} })
        SliderRow("Pan", e.pan.toFloat(), -1f..1f, { if (abs(it) < .02f) "center" else if (it < 0) "${(-it * 100).roundToInt()}% left" else "${(it * 100).roundToInt()}% right" }, { v -> upd(false) { pan = v.toDouble() } }, { upd(true) {} }, accent = C.Teal)
        SliderRow("Tone", e.tone.toFloat(), -1f..1f, { if (abs(it) < .02f) "open" else if (it < 0) "darker ${(-it * 100).roundToInt()}" else "thinner ${(it * 100).roundToInt()}" }, { v -> upd(false) { tone = v.toDouble() } }, { upd(true) {} }, accent = C.Teal)
        SliderRow("Attack", e.att.toFloat(), 0f..1f, { "${(it * 1000).roundToInt()} ms" }, { v -> upd(false) { att = v.toDouble() } }, { upd(true) {} }, accent = C.Yellow)
        SliderRow("Release", e.rel.toFloat(), 0f..1.5f, { "${(it * 1000).roundToInt()} ms" }, { v -> upd(false) { rel = v.toDouble() } }, { upd(true) {} }, accent = C.Yellow)
        T("Choke group: sounds in the same group cut each other off, like an open and closed hat.", size = 12.sp, color = C.Dim, weight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (g in 0..4) Btn(if (g == 0) "Off" else "$g", { upd(true) { choke = g } }, on = e.choke == g, small = true)
        }
        Row(Modifier.padding(top = 14.dp, bottom = 30.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Reset everything", { val n = SoundEdit(); e = n; state.setEdit(key, n, true); state.preview(key) }, small = true, color = C.Riot)
        }
    }
}
