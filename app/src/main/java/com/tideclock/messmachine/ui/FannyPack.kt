package com.tideclock.messmachine.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.ui.graphics.drawscope.clipPath
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
                // Nov 18 (note #22): four colored tiles, an icon and a word each, lit when packed; no descriptions
                DropdownMenu(expanded = add, onDismissRequest = { add = false }, modifier = Modifier.background(C.Ink).tagged("pack-menu")) {
                    val tiles = listOf(Triple("keys", "Keys", C.Pink), Triple("fx", "FX", C.Teal), Triple("lfo", "LFO", C.Yellow), Triple("xy", "XY square", tileColor("place")))
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (row in tiles.chunked(2)) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            for ((id, icon, col) in row) {
                                val on = id in packed
                                Column(
                                    Modifier.width(84.dp).border(3.dp, if (on) C.Cream else C.Card2).clickable { state.togglePackStrip(pad, id); add = false }.padding(6.dp).tagged("pack-pick-$id"),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    PixIcon(icon, 56.dp, tile = col, dim = !on)
                                    T(PACK_STRIPS.first { it.first == id }.second.uppercase(), size = 12.sp, color = if (on) C.Cream else C.Dim, modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        if (packed.isEmpty()) Note("Empty: tap +", color = C.Yellow)
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
    if (UiLook.panel) { VialStrip(state, pad); return }
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
            // the pad's own filter: cutoff (top is open) and resonance. Build 106: where it rests, saved with the mess,
            // the same filter an LFO, XY or lane moves
            val ft = com.tideclock.messmachine.engine.Engine.T_FILTER
            val rt = com.tideclock.messmachine.engine.Engine.T_RES
            if ("Cut" in state.fxShown) Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Fader(1f - (state.fxSend["$pad:$ft"] ?: 0f), C.Teal, "fx-cut-$pad") { v, on -> state.fxSendTo(pad, ft, 1f - v, on) }
                T("CUT", size = 10.sp, color = C.Teal, lines = 1, modifier = Modifier.padding(top = 3.dp))
            }
            if ("Res" in state.fxShown) Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Fader(state.fxSend["$pad:$rt"] ?: 0f, C.Pink, "fx-res-$pad") { v, on -> state.fxSendTo(pad, rt, v, on) }
                T("RES", size = 10.sp, color = C.Pink, lines = 1, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

/** Each effect's liquid colour in its vial. */
private fun vialColor(t: Int): Color = when (t) {
    com.tideclock.messmachine.engine.Engine.T_RVB -> Color(0xFF6FC7C0)
    com.tideclock.messmachine.engine.Engine.T_ECHO -> Color(0xFF6F9BE0)
    com.tideclock.messmachine.engine.Engine.T_DIRT -> Color(0xFFE07A3C)
    com.tideclock.messmachine.engine.Engine.T_CRUSH -> Color(0xFFE8C547)
    com.tideclock.messmachine.engine.Engine.T_CHORUS -> Color(0xFFB08BE0)
    com.tideclock.messmachine.engine.Engine.T_PUMP -> Color(0xFFE0609A)
    com.tideclock.messmachine.engine.Engine.T_WARM -> Color(0xFFC08A52)
    com.tideclock.messmachine.engine.Engine.T_PHASER -> Color(0xFF7FD068)
    com.tideclock.messmachine.engine.Engine.T_FREEZE -> Color(0xFFBFE6F2)
    com.tideclock.messmachine.engine.Engine.T_FILTER -> Color(0xFF3FB6A8)
    else -> Color(0xFFE85A8A)
}

/**
 * The FX vials (Nov 21 video): every effect on the pad as a glass vial filled to how much of it there is. Drag up or
 * down on a vial to fill it. The lamp under it takes that effect out and puts it back where it was; hold the lamp
 * for the effect's Auto lane. Bypass takes them all out at once. Cut is the pad's filter (full is open).
 */
@Composable
fun VialStrip(state: AppState, pad: Int) {
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp).tagged("fxstrip-$pad")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
            T("FX · PAD ${pad + 1}", size = 12.sp, color = C.Yellow, spacing = .1.em, modifier = Modifier.weight(1f))
            Btn("Bypass", { state.toggleBypass(pad) }, on = pad in state.padBypass, onColor = Hw.Oxblood, small = true, tag = "fxstrip-off-$pad")
        }
        val ft = com.tideclock.messmachine.engine.Engine.T_FILTER
        val rt = com.tideclock.messmachine.engine.Engine.T_RES
        val all = FX_STRIP + listOf(ft to "Cut", rt to "Res")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for ((t, name) in all) {
                val raw = state.fxSend["$pad:$t"] ?: 0f
                val v = if (t == ft) 1f - raw else raw
                val inNow = state.fxIn(pad, t)
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Vial(v, vialColor(t), inNow, "fx-$t-$pad") { x, on -> state.fxSendTo(pad, t, if (t == ft) 1f - x else x, on) }
                    // the lamp: in or out; hold it for this effect's Auto lane
                    Box(
                        Modifier.padding(top = 4.dp).size(26.dp).tagged("fxin-$t-$pad")
                            .tapOrHold(t to pad, onTap = { state.toggleFxIn(pad, t) }, onHold = { if (t != ft && t != rt) { state.closeEdit(); state.tray = null; state.openAutoLane(pad, t) } })
                            .semantics { selected = inNow },
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(Modifier.size(14.dp)) {
                            val r = size.minDimension / 2
                            drawCircle(Color.Black.copy(alpha = .6f), r, center)
                            if (inNow && v > .01f) drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(vialColor(t).copy(alpha = .55f), Color.Transparent), center, r * 2.2f), r * 2.2f, center)
                            drawCircle(if (inNow) (if (v > .01f) vialColor(t) else Color(0xFF3A3530)) else Hw.Oxblood.copy(alpha = .8f), r * .7f, center)
                            drawCircle(Color.White.copy(alpha = .35f), r * .22f, Offset(center.x - r * .25f, center.y - r * .25f))
                        }
                    }
                    T(name.uppercase(), size = if (name.length > 4) 7.5.sp else 9.sp, color = if (inNow) Hw.Engrave else Hw.Engrave.copy(alpha = .45f), lines = 1, spacing = (-.02).em, family = Stencil,
                        weight = FontWeight.Normal, modifier = Modifier.padding(top = 1.dp))
                }
            }
        }
    }
}

/** A glass vial with a metal cap, filled from the bottom: drag anywhere on it. Out (bypassed): the liquid goes grey. */
@Composable
fun Vial(value: Float, color: Color, live: Boolean, tag: String, height: androidx.compose.ui.unit.Dp = 112.dp, onChange: (Float, Boolean) -> Unit) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .widthIn(max = 44.dp)
            .height(height)
            .tagged(tag)
            .pointerInput(tag) {
                awaitEachGesture {
                    val d = awaitFirstDown(requireUnconsumed = false)
                    val top = 14.dp.toPx(); val bot = 6.dp.toPx()
                    fun at(y: Float) = (1f - (y - top) / (size.height - top - bot)).coerceIn(0f, 1f)
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
        val w = size.width.coerceAtMost(30.dp.toPx()); val x0 = (size.width - w) / 2
        val capH = 10.dp.toPx(); val neck = 4.dp.toPx(); val bot = 6.dp.toPx()
        val glassTop = capH + neck; val glassH = size.height - glassTop - bot
        val r = w / 2
        // the cap: worn metal, with a groove
        drawRoundRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Hw.MetalHi, Hw.Metal, Hw.MetalLow)), topLeft = Offset(x0 - 2f, 0f), size = Size(w + 4f, capH), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
        drawLine(Color.Black.copy(alpha = .35f), Offset(x0, capH * .55f), Offset(x0 + w, capH * .55f), 1.5f)
        // the glass: a tube with a round bottom
        val tube = androidx.compose.ui.graphics.Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(x0, capH, x0 + w, size.height - bot + r * .2f, topLeftCornerRadius = androidx.compose.ui.geometry.CornerRadius(3f), topRightCornerRadius = androidx.compose.ui.geometry.CornerRadius(3f),
                bottomLeftCornerRadius = androidx.compose.ui.geometry.CornerRadius(r), bottomRightCornerRadius = androidx.compose.ui.geometry.CornerRadius(r)))
        }
        drawPath(tube, Color(0xFF0D0B0A).copy(alpha = .85f))
        // the liquid, to the level, with a bright meniscus line
        val liq = if (live) color else Color(0xFF6A6560)
        val lvl = glassH * value.coerceIn(0f, 1f)
        if (value > .005f) clipPath(tube) {
            val y = glassTop + glassH - lvl
            drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(liq.copy(alpha = .75f), liq, liq.copy(alpha = .6f)), startX = x0, endX = x0 + w), topLeft = Offset(x0, y), size = Size(w, size.height - y))
            drawLine(Color.White.copy(alpha = if (live) .55f else .25f), Offset(x0 + 2f, y + 1f), Offset(x0 + w - 2f, y + 1f), 2f)
            // a few bubbles
            if (live) for (i in 0 until 3) { val by = y + (size.height - y) * (.25f + i * .22f); if (by < size.height - bot) drawCircle(Color.White.copy(alpha = .22f), 1.6.dp.toPx() - i * .3f, Offset(x0 + w * (.3f + i * .2f), by)) }
        }
        // marks on the glass, and its shine
        for (i in 1..4) { val y = glassTop + glassH * (1f - i / 5f); drawLine(Hw.Engrave.copy(alpha = .3f), Offset(x0 + 2f, y), Offset(x0 + w * .3f, y), 1f) }
        drawPath(tube, Hw.Engrave.copy(alpha = .55f), style = Stroke(1.5.dp.toPx()))
        drawLine(Color.White.copy(alpha = .22f), Offset(x0 + w * .72f, glassTop + 4f), Offset(x0 + w * .72f, size.height - bot - r), 2.5f)
    }
}

/** An upright fader, 0 at the bottom: drag anywhere on it. It reports the value and whether the finger is still down. */
@Composable
fun Fader(value: Float, color: Color, tag: String, height: androidx.compose.ui.unit.Dp = 104.dp, onChange: (Float, Boolean) -> Unit) {
    Canvas(
        Modifier
            .size(width = 30.dp, height = height)
            .background(C.Well)
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
    com.tideclock.messmachine.engine.Engine.T_PUMP to "Pump", com.tideclock.messmachine.engine.Engine.T_WARM to "Tape", com.tideclock.messmachine.engine.Engine.T_PHASER to "Phaser",
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
    // Nov 18 (Tyler): what you change live sits on the strip (rate, depth, cutoff, res); the wave, filter type, target and
    // restart moved into the settings tray (Menu, then tap the strip, or the wave button here)
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).sticker(C.Well, shadow = 0.dp, border = 2.dp, borderColor = col).padding(8.dp).tagged("lfostrip-$pad")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            T("LFO", size = 12.sp, color = C.Yellow, spacing = .1.em)
            Btn(if (o.lfoOn) "On" else "Off", { set { lfoOn = !lfoOn } }, on = o.lfoOn, onColor = C.Teal, small = true, tight = true, tag = "lfo-on-$pad")
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            val wave = LFO_WAVES.firstOrNull { it.first == o.lfoWave }?.second ?: "Sine"
            Btn("$wave · " + (LFO_TARGETS.firstOrNull { it.first == o.lfoTarget }?.second ?: "Filter") + " ▾", { state.openTray("lfo:$pad") }, small = true, tight = true, tier = Tier.Quiet, tag = "lfo-more-$pad")
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
        }
        SliderRow("Depth", o.lfoDepth.toFloat(), 0f..1f, { "${(it * 100).toInt()}%" }, { v -> state.midiAim(com.tideclock.messmachine.MidiTarget("lfo", pad, 0, "depth")); set(false) { lfoDepth = v.toDouble() } }, { set { } }, accent = col)
        // build 106: the LFO has no filter of its own; aimed at the filter it moves the pad filter, and these set where
        // that filter rests (the same as the FX strip's Cut and Res)
        val ft = com.tideclock.messmachine.engine.Engine.T_FILTER
        val rt = com.tideclock.messmachine.engine.Engine.T_RES
        if (o.lfoTarget == ft || o.lfoTarget == rt) {
            SliderRow("Cutoff", 1f - (state.fxSend["$pad:$ft"] ?: 0f), 0f..1f, { "${(90 * Math.pow(20000.0 / 90, it.toDouble())).toInt()} Hz" }, { v -> state.fxSendTo(pad, ft, 1f - v, true) }, { state.fxSendTo(pad, ft, 1f - (state.fxSend["$pad:$ft"] ?: 0f), false) }, accent = C.Teal)
            SliderRow("Res", state.fxSend["$pad:$rt"] ?: 0f, 0f..1f, { "${(it * 100).toInt()}%" }, { v -> state.fxSendTo(pad, rt, v, true) }, { state.fxSendTo(pad, rt, state.fxSend["$pad:$rt"] ?: 0f, false) }, accent = C.Pink)
        }
    }
}

val LFO_WAVES = listOf(0 to "Sine", 1 to "Tri", 2 to "Saw ↑", 3 to "Saw ↓", 4 to "Square", 5 to "Random")

/** The LFO's settings in the tray: wave, the Wobble preset, what it moves, its filter type, and how it restarts. */
@Composable
fun LfoTray(state: AppState, pad: Int) {
    val o = state.project.opt(pad)
    fun set(f: com.tideclock.messmachine.engine.PadOpts.() -> Unit) = state.setPadOpt(pad, true, f)
    Wrap(Modifier.tagged("lfowave-chips"), gap = 6.dp) {
        for ((w, n) in LFO_WAVES) Btn(n, { set { lfoWave = w; lfoOn = true } }, on = o.lfoWave == w, onColor = C.Teal, small = true, tight = true, tag = "lfo-wave-$w-$pad")
        // the old Wobble effect, folded in as a preset (Nov 18): a gentle pitch sway on a quarter-note sine
        Btn("Wobble", { set { lfoOn = true; lfoWave = 0; lfoRate = 4.0; lfoTarget = com.tideclock.messmachine.engine.Engine.T_PITCH; lfoDepth = .03; lfoRetrig = false } },
            on = o.lfoOn && o.lfoTarget == com.tideclock.messmachine.engine.Engine.T_PITCH && o.lfoDepth <= .05, onColor = C.Pink, small = true, tight = true, tag = "lfo-wobble-$pad")
    }
    Wrap(Modifier.padding(top = 8.dp).tagged("lfotype-chips"), gap = 6.dp) {
        // build 106: the pad filter's kind (everything that moves the filter moves this one)
        T("PAD FILTER", size = 11.sp, color = C.Dim, spacing = .1.em)
        for ((ty, n) in listOf(1 to "Lo", 2 to "Hi", 3 to "Band")) Btn(n, { set { ftype = ty } }, on = o.ftype == ty, onColor = C.Pink, small = true, tight = true, tag = "lfo-type-$ty-$pad")
    }
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Select("Target: " + (LFO_TARGETS.firstOrNull { it.first == o.lfoTarget }?.second ?: "Filter"), LFO_TARGETS, { t -> set { lfoTarget = t; lfoOn = true } }, tag = "lfo-target-$pad")
        Btn(if (o.lfoRetrig) "Each hit" else "With loop", { set { lfoRetrig = !lfoRetrig } }, small = true, tight = true, tier = Tier.Quiet, tag = "lfo-retrig-$pad")
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


/** The FX strip's settings in the tray: which faders show, and straight to a fader's automation lane (Auto). */
@Composable
fun FxTray(state: AppState, pad: Int) {
    // the vials (panel Look) show every effect, so there is nothing to pick
    if (!UiLook.panel) {
        T("SHOW", size = 11.sp, color = C.Dim, spacing = .1.em)
        Wrap(Modifier.padding(top = 4.dp), gap = 6.dp) {
            for (name in com.tideclock.messmachine.FX_FADERS) Btn(name, { state.toggleFxShown(name) }, on = name in state.fxShown, onColor = C.Teal, small = true, tight = true, tag = "fxshow-$name")
        }
    }
    T("AUTO LANE", size = 11.sp, color = C.Dim, spacing = .1.em, modifier = Modifier.padding(top = 10.dp))
    Wrap(Modifier.padding(top = 4.dp), gap = 6.dp) {
        for ((t, name) in FX_STRIP) Btn(name, { state.tray = null; state.openAutoLane(pad, t) }, small = true, tight = true, tag = "fxauto-$t")
    }
}

/** The pack's settings in the tray: which strips are packed. */
@Composable
fun PackTray(state: AppState, pad: Int) {
    val packed = state.packOf(pad)
    Wrap(gap = 6.dp) {
        for ((id, name) in PACK_STRIPS) Btn(name, { state.togglePackStrip(pad, id) }, on = id in packed, onColor = C.Teal, small = true, tag = "packtray-$id")
    }
}
