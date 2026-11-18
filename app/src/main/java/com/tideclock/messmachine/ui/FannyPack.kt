package com.tideclock.messmachine.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tideclock.messmachine.AppState
import com.tideclock.messmachine.FX_STRIP
import com.tideclock.messmachine.PACK_STRIPS

/**
 * The FannyPack (build 85, Tyler's idea): everything packed on one sound, dropped under its row of pads. It holds
 * only the strips you put in it (Keys, FX, LFO, XY). The zipper bar on top shows what is packed: tap the pull to
 * zip it shut, tap a strip's name to hide or show just that strip, + to pack or unpack strips.
 */
@Composable
fun FannyPack(state: AppState, pad: Int) {
    val P = state.project
    val k = P.kit.getOrNull(pad).orEmpty()
    if (k.isEmpty()) return
    val col = padColor(Color(state.lib.colorOf(k)))
    val packed = state.packOf(pad)
    val hidden = state.packHidden[pad] ?: emptySet()
    Column(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp).tagged("pack-$pad")) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 6.dp).sticker(C.Ink, shadow = 3.dp, shadowColor = col).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            // the pull: zips the pack shut (the pad keeps everything packed for next time)
            Box(Modifier.size(width = 30.dp, height = 34.dp).tagged("pack-zip-$pad"), contentAlignment = Alignment.Center) {
                Btn("▾", { state.togglePack(pad) }, small = true, tight = true, on = true, onColor = col, tag = "pack-pull-$pad")
            }
            Canvas(Modifier.width(26.dp).height(10.dp)) {
                drawLine(col, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 5f)))
            }
            T("${pad + 1} ${state.lib.titleOf(k).uppercase()}", size = 11.sp, color = C.Yellow, lines = 1, spacing = .04.em, modifier = Modifier.weight(1f))
            for ((id, name) in PACK_STRIPS) if (id in packed) {
                Btn(name, { state.togglePackHidden(pad, id) }, on = id !in hidden, onColor = C.Teal, small = true, tight = true, tag = "pack-show-$id-$pad")
            }
            var add by remember(pad) { mutableStateOf(false) }
            Box {
                Btn("+", { add = true }, small = true, tight = true, tag = "pack-add-$pad")
                DropdownMenu(expanded = add, onDismissRequest = { add = false }, modifier = Modifier.background(C.Ink).tagged("pack-menu")) {
                    T("IN THE FANNYPACK", size = 11.sp, color = C.Yellow, spacing = .08.em, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                    for ((id, name) in PACK_STRIPS) DropdownMenuItem(
                        text = { T((if (id in packed) "✓ " else "    ") + name + when (id) { "keys" -> ": Padiano"; "fx" -> ": live effect faders"; "lfo" -> ": a wave that moves the sound"; else -> ": the pad's XY" }, size = 14.sp, weight = FontWeight.Medium) },
                        onClick = { state.togglePackStrip(pad, id); add = false },
                        modifier = Modifier.tagged("pack-pick-$id"),
                    )
                }
            }
        }
        if (packed.isEmpty()) Note("Nothing packed yet. Tap + to put Keys, FX, LFO or XY in this pad's pack.", color = C.Yellow)
        for (id in packed) if (id !in hidden) when (id) {
            "keys" -> PianoKeyboard(state, pad, close = false, synthBtn = true)
            "fx" -> FxStrip(state, pad, col)
            "lfo" -> LfoStrip(state, pad, col)
            "xy" -> PadXy(state, pad, Modifier.padding(bottom = 6.dp))
        }
    }
}

/**
 * The FX strip: one fader per effect send, plus the pad's filter. They stay where you leave them; with Rec on while
 * the loop plays, every move records like an XY move. Off sends them all back to zero.
 */
@Composable
fun FxStrip(state: AppState, pad: Int, col: Color) {
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp).tagged("fxstrip-$pad")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 4.dp)) {
            T("FX", size = 12.sp, color = C.Yellow, spacing = .1.em, modifier = Modifier.weight(1f))
            // which faders show (Nov 18): the strip keeps only the ones you play
            var pick by remember(pad) { mutableStateOf(false) }
            Box {
                Btn("Faders ▾", { pick = true }, small = true, tight = true, tag = "fxstrip-pick-$pad")
                DropdownMenu(expanded = pick, onDismissRequest = { pick = false }, modifier = Modifier.background(C.Ink).tagged("fxstrip-pickmenu")) {
                    for (name in com.tideclock.messmachine.FX_FADERS) DropdownMenuItem(
                        text = { T((if (name in state.fxShown) "✓ " else "    ") + name, size = 14.sp, weight = FontWeight.Medium) },
                        onClick = { state.toggleFxShown(name) },
                        modifier = Modifier.tagged("fxshow-$name"),
                    )
                }
            }
            Btn("Off", { state.fxSendsOff(pad); state.openPadFilter(pad) }, small = true, tight = true, tag = "fxstrip-off-$pad")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for ((t, name) in FX_STRIP) if (name in state.fxShown) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Fader(state.fxSend["$pad:$t"] ?: 0f, col, "fx-$t-$pad") { v, on -> state.fxSendTo(pad, t, v, on) }
                    T(name.uppercase(), size = 10.sp, color = C.Dim, lines = 1, modifier = Modifier.padding(top = 3.dp))
                }
            }
            // the pad's own filter: cutoff (top is open) and resonance, kept where you leave them
            val f = state.padFilt[pad]
            if ("Cut" in state.fxShown) Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Fader(f?.first ?: 1f, C.Teal, "fx-cut-$pad") { v, _ -> state.keepPadFilter(pad, v, state.padFilt[pad]?.second ?: 0f) }
                T("CUT", size = 10.sp, color = C.Teal, lines = 1, modifier = Modifier.padding(top = 3.dp))
            }
            if ("Res" in state.fxShown) Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Fader(f?.second ?: 0f, C.Pink, "fx-res-$pad") { v, _ -> state.keepPadFilter(pad, state.padFilt[pad]?.first ?: 1f, v) }
                T("RES", size = 10.sp, color = C.Pink, lines = 1, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

/** An upright fader, 0 at the bottom: drag anywhere on it. It reports the value and whether the finger is still down. */
@Composable
fun Fader(value: Float, color: Color, tag: String, height: androidx.compose.ui.unit.Dp = 104.dp, onChange: (Float, Boolean) -> Unit) {
    Canvas(
        Modifier
            .size(width = 30.dp, height = height)
            .background(C.Black)
            .tagged(tag)
            .pointerInput(tag) {
                awaitEachGesture {
                    val d = awaitFirstDown(requireUnconsumed = false)
                    fun at(y: Float) = (1f - y / size.height).coerceIn(0f, 1f)
                    d.consume()
                    var last = at(d.position.y)
                    onChange(last, true)
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == d.id } ?: break
                        if (!c.pressed) { c.consume(); break }
                        last = at(c.position.y)
                        onChange(last, true)
                        c.consume()
                    }
                    onChange(last, false)
                }
            },
    ) {
        val h = size.height * value
        drawRect(color.copy(alpha = .85f), topLeft = Offset(0f, size.height - h), size = Size(size.width, h))
        drawRect(C.Cream, topLeft = Offset(0f, size.height - h - 2.dp.toPx()), size = Size(size.width, 4.dp.toPx()))
        drawRect(C.Cream, style = Stroke(2.dp.toPx()))
    }
}

/** The rate strip's stops, slowest on the left: steps per full wave. */
val LFO_STOPS = listOf(32.0 to "2 bars", 16.0 to "1 bar", 8.0 to "1/2", 4.0 to "1/4", 2.0 to "1/8", 1.0 to "1/16", .5 to "1/32")

/** A rate split into its stop and its feel: 1 straight, 2/3 triplet, 1.5 dotted. */
fun lfoParts(r: Double): Pair<Double, Double> {
    for ((b, _) in LFO_STOPS) for (m in listOf(1.0, 2.0 / 3, 1.5)) if (kotlin.math.abs(b * m - r) < 1e-6) return b to m
    return 4.0 to 1.0
}

/** What an LFO can move (Engine targets). */
val LFO_TARGETS = listOf(
    com.tideclock.messmachine.engine.Engine.T_FILTER to "Filter", com.tideclock.messmachine.engine.Engine.T_RES to "Resonance",
    com.tideclock.messmachine.engine.Engine.T_VOL to "Volume", com.tideclock.messmachine.engine.Engine.T_PAN to "Pan",
    com.tideclock.messmachine.engine.Engine.T_PITCH to "Pitch", com.tideclock.messmachine.engine.Engine.T_RVB to "Reverb",
    com.tideclock.messmachine.engine.Engine.T_ECHO to "Echo", com.tideclock.messmachine.engine.Engine.T_DIRT to "Dirty",
    com.tideclock.messmachine.engine.Engine.T_CRUSH to "Crush", com.tideclock.messmachine.engine.Engine.T_CHORUS to "Chorus",
)

/**
 * The LFO strip (Tyler's spec): a wave (sine, triangle, saw up and down, square, random), a rate strip in the middle
 * from 2 bars to 1/32 that snaps at its marks (T and dot make the stop triplet or dotted), what it moves, how far, and
 * the strip's own filter: Lo, Hi or Band with cutoff and resonance. It runs with the loop, or restarts on every hit.
 */
@Composable
fun LfoStrip(state: AppState, pad: Int, col: Color) {
    val o = state.project.opt(pad)
    val (base, feel) = lfoParts(o.lfoRate)
    fun set(commit: Boolean = true, f: com.tideclock.messmachine.engine.PadOpts.() -> Unit) = state.setPadOpt(pad, commit, f)
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).sticker(C.Black, shadow = 0.dp, border = 2.dp, borderColor = col).padding(8.dp).tagged("lfostrip-$pad")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            T("LFO", size = 12.sp, color = C.Yellow, spacing = .1.em)
            Btn(if (o.lfoOn) "On" else "Off", { set { lfoOn = !lfoOn } }, on = o.lfoOn, onColor = C.Teal, small = true, tight = true, tag = "lfo-on-$pad")
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            Btn(if (o.lfoRetrig) "Each hit" else "With loop", { set { lfoRetrig = !lfoRetrig } }, small = true, tight = true, tier = Tier.Quiet, tag = "lfo-retrig-$pad")
        }
        Wrap(Modifier.padding(top = 6.dp).tagged("lfowave-chips"), gap = 6.dp) {
            for ((w, n) in listOf(0 to "Sine", 1 to "Tri", 2 to "Saw ↑", 3 to "Saw ↓", 4 to "Square", 5 to "Random")) {
                Btn(n, { set { lfoWave = w; lfoOn = true } }, on = o.lfoWave == w, onColor = C.Teal, small = true, tight = true, tag = "lfo-wave-$w-$pad")
            }
            // the old Wobble effect, folded in as a preset (Nov 18): a gentle pitch sway on a quarter-note sine
            Btn("Wobble", { set { lfoOn = true; lfoWave = 0; lfoRate = 4.0; lfoTarget = com.tideclock.messmachine.engine.Engine.T_PITCH; lfoDepth = .03; lfoType = 0; lfoRetrig = false } },
                on = o.lfoOn && o.lfoTarget == com.tideclock.messmachine.engine.Engine.T_PITCH && o.lfoDepth <= .05, onColor = C.Pink, small = true, tight = true, tag = "lfo-wobble-$pad")
        }
        // the rate strip: drag along it and it snaps at each mark; T and dot change the stop's feel
        RateStrip(base, col, "lfo-rate-$pad") { b, done ->
            state.midiAim(com.tideclock.messmachine.MidiTarget("lfo", pad, 0, "rate"))
            set(done) { lfoRate = b * feel; lfoOn = true }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            Btn("Straight", { set { lfoRate = base } }, on = feel == 1.0, onColor = C.Yellow, small = true, tight = true, tag = "lfo-straight-$pad")
            Btn("T", { set { lfoRate = base * 2 / 3 } }, on = feel < 1.0, onColor = C.Yellow, small = true, tag = "lfo-trip-$pad")
            Btn("Dot", { set { lfoRate = base * 1.5 } }, on = feel > 1.0, onColor = C.Yellow, small = true, tight = true, tag = "lfo-dot-$pad")
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            Select("Target: " + (LFO_TARGETS.firstOrNull { it.first == o.lfoTarget }?.second ?: "Filter"), LFO_TARGETS, { t -> set { lfoTarget = t; lfoOn = true } }, tag = "lfo-target-$pad")
        }
        SliderRow("Depth", o.lfoDepth.toFloat(), 0f..1f, { "${(it * 100).toInt()}%" }, { v -> state.midiAim(com.tideclock.messmachine.MidiTarget("lfo", pad, 0, "depth")); set(false) { lfoDepth = v.toDouble() } }, { set { } }, accent = col)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp).tagged("lfotype-chips")) {
            T("FILTER", size = 11.sp, color = C.Dim, spacing = .1.em)
            for ((ty, n) in listOf(0 to "Off", 1 to "Lo", 2 to "Hi", 3 to "Band")) Btn(n, { set { lfoType = ty } }, on = o.lfoType == ty, onColor = C.Pink, small = true, tight = true, tag = "lfo-type-$ty-$pad")
        }
        if (o.lfoType > 0) {
            SliderRow("Cutoff", o.lfoCut.toFloat(), 0f..1f, { "${(40 * Math.pow(18000.0 / 40, it.toDouble())).toInt()} Hz" }, { v -> set(false) { lfoCut = v.toDouble() } }, { set { } }, accent = C.Teal)
            SliderRow("Res", o.lfoRes.toFloat(), 0f..1f, { "${(it * 100).toInt()}%" }, { v -> set(false) { lfoRes = v.toDouble() } }, { set { } }, accent = C.Pink)
        }
    }
}

/**
 * The rate strip in the middle of the LFO: seven marks from 2 bars (left) to 1/32 (right). A finger dragged along it
 * snaps to the nearest mark, so it is quantized however fast you move. onPick gets the stop and whether the finger is up.
 */
@Composable
fun RateStrip(stop: Double, col: Color, tag: String, onPick: (Double, Boolean) -> Unit) {
    val tm = androidx.compose.ui.text.rememberTextMeasurer()
    val idx = LFO_STOPS.indexOfFirst { it.first == stop }.coerceAtLeast(0)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .padding(top = 6.dp)
            .background(C.Ink)
            .tagged(tag)
            .pointerInput(tag) {
                awaitEachGesture {
                    val d = awaitFirstDown(requireUnconsumed = false)
                    fun at(x: Float) = LFO_STOPS[((x / size.width) * LFO_STOPS.size).toInt().coerceIn(0, LFO_STOPS.size - 1)].first
                    d.consume()
                    var last = at(d.position.x)
                    onPick(last, false)
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == d.id } ?: break
                        if (!c.pressed) { c.consume(); break }
                        val now = at(c.position.x)
                        if (now != last) { last = now; onPick(now, false) }
                        c.consume()
                    }
                    onPick(last, true)
                }
            },
    ) {
        val w = size.width / LFO_STOPS.size
        for ((i, s) in LFO_STOPS.withIndex()) {
            val on = i == idx
            drawRect(if (on) col else C.Card2, topLeft = Offset(i * w + 2f, 2f), size = Size(w - 4f, size.height - 4f))
            drawText(tm, s.second, topLeft = Offset(i * w + 5f, size.height * .3f),
                style = androidx.compose.ui.text.TextStyle(color = if (on) ink(col) else C.Cream, fontSize = 10.sp, fontWeight = FontWeight.Bold))
        }
    }
}
