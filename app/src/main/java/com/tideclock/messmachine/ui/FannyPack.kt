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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tideclock.messmachine.AppState
import com.tideclock.messmachine.FX_STRIP
import com.tideclock.messmachine.PACK_STRIPS

/**
 * The FannyPack (build 85, Tyler's idea): everything packed on one sound, dropped under its row of pads. It holds
 * only the strips you put in it (Keys, FX, XY; LFO next). The zipper bar on top shows what is packed: tap the pull to
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
                        text = { T((if (id in packed) "✓ " else "    ") + name + when (id) { "keys" -> ": Padiano"; "fx" -> ": live effect faders"; else -> ": the pad's XY" }, size = 14.sp, weight = FontWeight.Medium) },
                        onClick = { state.togglePackStrip(pad, id) },
                        modifier = Modifier.tagged("pack-pick-$id"),
                    )
                }
            }
        }
        if (packed.isEmpty()) Note("Nothing packed yet. Tap + to put Keys, FX or XY in this pad's pack.", color = C.Yellow)
        for (id in packed) if (id !in hidden) when (id) {
            "keys" -> PianoKeyboard(state, pad, close = false, synthBtn = true)
            "fx" -> FxStrip(state, pad, col)
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
            T("FX", size = 12.sp, color = C.Yellow, spacing = .1.em)
            Note("live sends on pad ${pad + 1}; they stay put", modifier = Modifier.weight(1f))
            Btn("Off", { state.fxSendsOff(pad); state.openPadFilter(pad) }, small = true, tight = true, tag = "fxstrip-off-$pad")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for ((t, name) in FX_STRIP) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Fader(state.fxSend["$pad:$t"] ?: 0f, col, "fx-$t-$pad") { v, on -> state.fxSendTo(pad, t, v, on) }
                    T(name.uppercase(), size = 10.sp, color = C.Dim, lines = 1, modifier = Modifier.padding(top = 3.dp))
                }
            }
            // the pad's own filter: cutoff (top is open) and resonance, kept where you leave them
            val f = state.padFilt[pad]
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Fader(f?.first ?: 1f, C.Teal, "fx-cut-$pad") { v, _ -> state.keepPadFilter(pad, v, state.padFilt[pad]?.second ?: 0f) }
                T("CUT", size = 10.sp, color = C.Teal, lines = 1, modifier = Modifier.padding(top = 3.dp))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
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
