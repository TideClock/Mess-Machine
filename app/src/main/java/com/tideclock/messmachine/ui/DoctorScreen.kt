package com.tideclock.messmachine.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tideclock.messmachine.AppState
import com.tideclock.messmachine.engine.Sample
import com.tideclock.messmachine.engine.SoundEdit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/** The doctor's table: trim, zoom, snap, tune, shape, tone, fit to tempo, chop to pads, save as new. Non-destructive, like the site. */
@Composable
fun DoctorScreen(state: AppState, key: String) {
    var e by remember(key) { mutableStateOf(state.project.edit(key).copy()) }
    var smp by remember(key) { mutableStateOf<Sample?>(state.lib.get(key)) }
    var v0 by remember(key) { mutableStateOf(0.0) }
    var v1 by remember(key) { mutableStateOf(1.0) }
    var snap by remember { mutableStateOf(true) }
    var looping by remember { mutableStateOf(false) }
    var cuts by remember(key) { mutableStateOf(listOf<Double>()) }
    var chopMode by remember { mutableStateOf("equal") }
    var chopN by remember { mutableStateOf(4) }
    var sens by remember { mutableStateOf(.5f) }
    var lazyT0 by remember { mutableStateOf(0L) }
    var fitBars by remember { mutableStateOf(1.0) }
    LaunchedEffect(key) {
        if (smp == null) smp = withContext(Dispatchers.IO) { try { state.lib.load(key) } catch (_: Exception) { null } }
    }
    DisposableEffect(key) { onDispose { state.engine.stopPreviews() } }
    val wave = remember(smp, v0, v1) { smp?.overview(v0, v1, 260) }
    val seconds = smp?.seconds() ?: 0.0
    fun upd(commit: Boolean, f: SoundEdit.() -> Unit) {
        val n = e.copy()
        n.f()
        e = n
        state.setEdit(key, n, commit)
        if (commit && !looping) state.preview(key)
        if (commit && looping) smp?.let { state.engine.preview(it, n, 0, true) }
    }
    fun equalCuts(n: Int): List<Double> = (1 until n).map { e.st + (e.en - e.st) * it / n }
    fun refreshCuts() {
        cuts = when (chopMode) {
            "equal" -> equalCuts(chopN)
            "auto" -> smp?.onsets(e.st, e.en, sens.toDouble())?.toList() ?: emptyList()
            else -> cuts
        }
    }
    val col = Color(state.lib.colorOf(key))
    val inBench = state.bench.item(key) != null
    ScrollPane(hPad = 14.dp, vPad = 0.dp) {
        T(state.lib.titleOf(key), size = 18.sp)
        T(
            "%.2f s · plays %.2f s".format(seconds, seconds * (e.en - e.st) / Math.pow(2.0, (e.tune + e.fine / 100) / 12)),
            size = 12.sp, color = C.Dim, weight = FontWeight.Medium, modifier = Modifier.padding(bottom = 8.dp),
        )
        var dragging by remember { mutableStateOf(0) }
        fun toFrac(x: Float, w: Int) = (v0 + (x / w) * (v1 - v0)).coerceIn(0.0, 1.0)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .sticker(C.Ink, radius = 12.dp)
                .pointerInput(key, v0, v1, chopMode) {
                    detectTapGestures { o ->
                        if (chopMode != "tap") return@detectTapGestures
                        val f = toFrac(o.x, size.width)
                        val near = cuts.firstOrNull { abs(it - f) * size.width / (v1 - v0) < 28 }
                        cuts = if (near != null) cuts - near else (cuts + f).sorted()
                    }
                }
                .pointerInput(key, v0, v1, snap) {
                    detectDragGestures(
                        onDragStart = { o ->
                            val f = toFrac(o.x, size.width)
                            dragging = if (abs(f - e.st) <= abs(f - e.en)) 1 else 2
                        },
                        onDragEnd = { dragging = 0; upd(true) {}; if (chopMode == "equal" || chopMode == "auto") refreshCuts() },
                        onDragCancel = { dragging = 0 },
                    ) { change, _ ->
                        var f = toFrac(change.position.x, size.width)
                        if (snap) f = smp?.snapZero(f) ?: f
                        if (dragging == 1) upd(false) { st = minOf(f, en - .002) }
                        else if (dragging == 2) upd(false) { en = maxOf(f, st + .002) }
                    }
                },
        ) {
            val w = wave
            val mid = size.height / 2
            fun xOf(f: Double) = ((f - v0) / (v1 - v0) * size.width).toFloat()
            if (w != null) {
                val n = w.size / 2
                val bw = size.width / n
                for (c in 0 until n) {
                    val f = v0 + (v1 - v0) * c / n
                    val inside = f >= e.st && f <= e.en
                    val lo = w[c * 2]
                    val hi = w[c * 2 + 1]
                    drawRect(if (inside) col else C.Card2, topLeft = Offset(c * bw, mid - hi * mid * .92f), size = Size(maxOf(1f, bw - .5f), maxOf(1f, (hi - lo) * mid * .92f)))
                }
            }
            val xs = xOf(e.st).coerceIn(0f, size.width)
            val xe = xOf(e.en).coerceIn(0f, size.width)
            drawRect(C.Black.copy(alpha = .55f), topLeft = Offset(0f, 0f), size = Size(xs, size.height))
            drawRect(C.Black.copy(alpha = .55f), topLeft = Offset(xe, 0f), size = Size(size.width - xe, size.height))
            for (cut in cuts) { val x = xOf(cut); if (x in 0f..size.width) drawLine(C.Cream, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3f) }
            drawLine(C.Yellow, Offset(xs, 0f), Offset(xs, size.height), strokeWidth = 6f)
            drawLine(C.Pink, Offset(xe, 0f), Offset(xe, size.height), strokeWidth = 6f)
        }
        Help("Drag the yellow line to trim the front, the pink one to trim the back.", modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
        Wrap(Modifier.padding(bottom = 8.dp)) {
            Btn("Zoom to trim", {
                val pad = (e.en - e.st) * .08
                v0 = (e.st - pad).coerceAtLeast(0.0); v1 = (e.en + pad).coerceAtMost(1.0)
            }, small = true)
            Btn("Whole sound", { v0 = 0.0; v1 = 1.0 }, small = true, enabled = v0 > 0 || v1 < 1)
            Btn("Snap to zero", { snap = !snap }, on = snap, onColor = C.Teal, small = true)
        }
        Wrap(Modifier.padding(bottom = 8.dp)) {
            Btn("Hear it", { looping = false; state.engine.stopPreviews(); state.preview(key) }, color = C.Teal, small = true)
            Btn(if (looping) "■ Stop loop" else "Loop the trim", {
                looping = !looping
                if (looping) smp?.let { state.engine.preview(it, e, 0, true) } else state.engine.stopPreviews()
            }, on = looping, onColor = C.Pink, small = true)
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
        Help("Choke group: sounds in the same group cut each other off, like an open and closed hat.", modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
        Wrap(Modifier) {
            for (g in 0..6) Btn(if (g == 0) "Off" else "$g", { upd(true) { choke = g } }, on = e.choke == g, small = true)
        }
        Help("When Hold keeps this sound: loop its trim, freeze its tail, or let it play once.", modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
        Wrap(Modifier) {
            for ((v, n) in listOf("loop" to "Loop it", "tail" to "Hold the tail", "once" to "Play once")) Btn(n, { upd(true) { held = v } }, on = e.held == v, small = true, tag = "held-$v")
        }

        Section("Effects")
        Help("These stay with the sound wherever it plays. Reverb and echo share one space each; the sound you played last sets its size and time.", modifier = Modifier.padding(bottom = 6.dp))
        val pct = { v: Float -> "${(v * 100).roundToInt()}%" }
        @Composable
        fun fxRow(name: String, tag: String, amount: Double, toggle: () -> Unit, body: @Composable () -> Unit) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Btn(name, toggle, on = amount > 0, onColor = C.Teal, small = true, tag = tag)
                if (amount > 0) Note("  ${(amount * 100).roundToInt()}%", color = C.Yellow)
            }
            if (amount > 0) body()
        }
        fxRow("Reverb", "dfx-rvb", e.rvb, { upd(true) { rvb = if (rvb > 0) 0.0 else .4 } }) {
            SliderRow("Amount", e.rvb.toFloat(), 0f..1f, pct, { v -> upd(false) { rvb = v.toDouble() } }, { upd(true) {} }, accent = C.Teal)
            SliderRow("Space", e.rvbSize.toFloat(), 0f..1f, { if (it < .33f) "small room" else if (it < .66f) "hall" else "cave" }, { v -> upd(false) { rvbSize = v.toDouble() } }, { upd(true) {} }, accent = C.Teal)
        }
        fxRow("Echo", "dfx-echo", e.echo, { upd(true) { echo = if (echo > 0) 0.0 else .4 } }) {
            SliderRow("Amount", e.echo.toFloat(), 0f..1f, pct, { v -> upd(false) { echo = v.toDouble() } }, { upd(true) {} }, accent = C.Teal)
            Wrap(Modifier.padding(bottom = 4.dp), gap = 6.dp) {
                for ((t, n) in listOf(0 to "1/16", 1 to "1/8", 2 to "3/16", 3 to "1/4")) Btn(n, { upd(true) { echoT = t } }, on = e.echoT == t, small = true)
            }
        }
        fxRow("Dirty", "dfx-dirt", e.dirt, { upd(true) { dirt = if (dirt > 0) 0.0 else .5 } }) {
            SliderRow("Drive", e.dirt.toFloat(), 0f..1f, pct, { v -> upd(false) { dirt = v.toDouble() } }, { upd(true) {} }, accent = C.Riot)
        }
        fxRow("Crush", "dfx-crush", e.crush, { upd(true) { crush = if (crush > 0) 0.0 else .5 } }) {
            SliderRow("Bits and rate", e.crush.toFloat(), 0f..1f, pct, { v -> upd(false) { crush = v.toDouble() } }, { upd(true) {} }, accent = C.Riot)
        }
        fxRow("Chorus", "dfx-chorus", e.chorus, { upd(true) { chorus = if (chorus > 0) 0.0 else .5 } }) {
            SliderRow("Depth", e.chorus.toFloat(), 0f..1f, pct, { v -> upd(false) { chorus = v.toDouble() } }, { upd(true) {} }, accent = C.Pink)
        }
        fxRow("Wobble", "dfx-wob", e.wob, { upd(true) { wob = if (wob > 0) 0.0 else .5 } }) {
            SliderRow("Depth", e.wob.toFloat(), 0f..1f, pct, { v -> upd(false) { wob = v.toDouble() } }, { upd(true) {} }, accent = C.Pink)
            SliderRow("Speed", e.wobRate.toFloat(), 0f..1f, { "%.1f Hz".format(.3f + it * 5.7f) }, { v -> upd(false) { wobRate = v.toDouble() } }, { upd(true) {} }, accent = C.Pink)
        }

        Section("Re-pitch to fit")
        Wrap(Modifier) {
            for ((b, label) in listOf(.25 to "1 beat", .5 to "2 beats", 1.0 to "1 bar", 2.0 to "2 bars", 4.0 to "4 bars")) {
                Btn(label, { fitBars = b }, on = fitBars == b, onColor = C.Teal, small = true)
            }
            Btn("Fit", { state.fitToTempo(key, fitBars); e = state.project.edit(key).copy() }, small = true, color = C.Pink)
        }

        Section("Chop to pads")
        Wrap(Modifier) {
            for ((m, label) in listOf("equal" to "Equal", "auto" to "Auto (by hits)", "tap" to "Tap the wave", "lazy" to "Lazy (while it plays)")) {
                Btn(label, { chopMode = m; cuts = emptyList(); refreshCuts() }, on = chopMode == m, onColor = C.Teal, small = true)
            }
        }
        when (chopMode) {
            "equal" -> Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (n in listOf(2, 4, 8, 16)) Btn("$n slices", { chopN = n; cuts = equalCuts(n) }, on = chopN == n, small = true)
            }
            "auto" -> SliderRow("Sensitivity", sens, 0f..1f, { "${(it * 100).roundToInt()}" }, { sens = it }, { refreshCuts() }, accent = C.Teal)
            "tap" -> Help("Tap the waveform to add a cut; tap a cut to take it away. Zoom in for fine work.", modifier = Modifier.padding(top = 8.dp))
            else -> Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("Play", { cuts = emptyList(); looping = false; state.engine.stopPreviews(); state.preview(key); lazyT0 = System.nanoTime() }, small = true, color = C.Teal)
                Btn("Chop here", {
                    if (lazyT0 == 0L) state.say("Tap Play first, then Chop here at each spot you want a cut.")
                    else {
                        val el = (System.nanoTime() - lazyT0) / 1e9 * Math.pow(2.0, (e.tune + e.fine / 100) / 12) / maxOf(.001, seconds)
                        val f = e.st + el
                        if (f > e.st + .002 && f < e.en - .002) cuts = (cuts + f).sorted()
                    }
                }, small = true, color = C.Pink)
            }
        }
        Wrap(Modifier.padding(top = 10.dp)) {
            T("${cuts.count { it > e.st && it < e.en } + 1} slices", size = 13.sp, color = C.Yellow)
            Btn("Clear cuts", { cuts = emptyList() }, small = true)
            Btn("Chop to pads", { state.chopToPads(key, cuts); state.overlay = null }, small = true, color = C.Pink)
        }

        Section("Keep it")
        Wrap(Modifier) {
            Btn("Save as a new sound", { state.saveEditAsNew(key) }, small = true, color = C.Teal)
            Btn("Share as WAV", { state.shareSoundWav(key) }, small = true)
            if (inBench) {
                var sure by remember { mutableStateOf(false) }
                Btn(if (sure) "Really toss it?" else "Toss from Workbench", {
                    if (sure) { state.overlay = null; state.removeFromBench(key) } else { sure = true }
                }, small = true, color = if (sure) C.Riot else C.Card2)
            }
        }
        Row(Modifier.padding(top = 14.dp, bottom = 30.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Reset everything", { val n = SoundEdit(); e = n; state.setEdit(key, n, true); state.preview(key) }, small = true, color = C.Riot)
        }
    }
}

@Composable
private fun Section(title: String) {
    T(title, size = 15.sp, color = C.Yellow, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
}
