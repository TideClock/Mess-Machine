package com.tideclock.messmachine.ui

import com.tideclock.messmachine.ui.res.*

import com.tideclock.messmachine.R
import androidx.compose.foundation.background
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.getValue
import androidx.compose.foundation.interaction.collectIsPressedAsState


/** Tile colors by button type (the Pro looks use these too). */
fun tileColor(group: String): androidx.compose.ui.graphics.Color = when (group) {
    "transport" -> C.Yellow
    "place" -> androidx.compose.ui.graphics.Color(0xFFA070E0)
    "perform" -> C.Pink
    "sound" -> C.Teal
    else -> androidx.compose.ui.graphics.Color(0xFF5C5668)
}

/** A pixel icon on its colored tile, drawn sharp (no smoothing between pixels). */
@androidx.compose.runtime.Composable
fun PixIcon(name: String, size: androidx.compose.ui.unit.Dp, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier, tile: androidx.compose.ui.graphics.Color? = null, dim: Boolean = false) {
    val (res, group) = Icons.all[name] ?: return
    androidx.compose.foundation.layout.Box(
        modifier.size(size).background(if (dim) (tile ?: tileColor(group)).copy(alpha = .35f) else tile ?: tileColor(group), androidx.compose.foundation.shape.RoundedCornerShape(size / 8)),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        androidx.compose.foundation.Image(
            org.jetbrains.compose.resources.imageResource(res), contentDescription = name,
            modifier = androidx.compose.ui.Modifier.size(size), filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
            alpha = if (dim) .55f else 1f,
        )
    }
}

/* ---------------- the Pro looks (Nov 18): icon tiles on rails, dark or light; Classic is today's look ---------------- */

val PRO_DARK_RAIL = androidx.compose.ui.graphics.Color(0xFF1E1B21)
val PRO_LIGHT_RAIL = androidx.compose.ui.graphics.Color(0xFFFAF0E0)

/** A bar's background: Classic is flat ink; the Pro looks are printed rails with a dot texture. */
fun androidx.compose.ui.Modifier.rail(state: com.tideclock.messmachine.AppState, classic: androidx.compose.ui.graphics.Color = C.Ink): androidx.compose.ui.Modifier = when (state.look) {
    "classic" -> this.background(classic)
    // the hardware panel (Nov 21): a strip of worn gunmetal, brushed sideways, scratched, held on by two screws
    "panel" -> this.drawBehind {
        drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Hw.PlateTop, Hw.Plate, androidx.compose.ui.graphics.Color(0xFF151311))))
        // a tiny hash, not a new Random on every redraw (Nov 21, lag)
        val rnd = object { var x = 7 + size.width.toInt(); fun nextFloat(): Float { x = x * 1103515245 + 12345; return ((x ushr 8) and 0xFFFF) / 65535f } }
        var y = 1f
        while (y < size.height) {
            drawLine(androidx.compose.ui.graphics.Color.White.copy(alpha = .015f + rnd.nextFloat() * .03f), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), 1f)
            y += 2f + rnd.nextFloat() * 3f
        }
        repeat((size.width / 90f).toInt().coerceAtMost(14)) {
            val x = rnd.nextFloat() * size.width; val yy = rnd.nextFloat() * size.height
            drawLine(androidx.compose.ui.graphics.Color.White.copy(alpha = .08f), androidx.compose.ui.geometry.Offset(x, yy), androidx.compose.ui.geometry.Offset(x + 10f + rnd.nextFloat() * 30f, yy + (rnd.nextFloat() - .5f) * 6f), 1f)
        }
        drawLine(androidx.compose.ui.graphics.Color.White.copy(alpha = .12f), androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(size.width, 0f), 1.5f)
        drawLine(androidx.compose.ui.graphics.Color.Black.copy(alpha = .6f), androidx.compose.ui.geometry.Offset(0f, size.height - 1f), androidx.compose.ui.geometry.Offset(size.width, size.height - 1f), 2f)
        val sr = 3.dp.toPx()
        for (sx in listOf(7.dp.toPx(), size.width - 7.dp.toPx())) {
            val c = androidx.compose.ui.geometry.Offset(sx, size.height / 2)
            drawCircle(Hw.MetalLow, sr, c); drawCircle(Hw.Metal, sr * .8f, c)
            drawLine(androidx.compose.ui.graphics.Color(0xFF1A1613), androidx.compose.ui.geometry.Offset(c.x - sr * .6f, c.y - sr * .3f), androidx.compose.ui.geometry.Offset(c.x + sr * .6f, c.y + sr * .3f), 1.5f)
        }
    }
    else -> {
        val light = state.look == "light"
        val base = if (light) PRO_LIGHT_RAIL else PRO_DARK_RAIL
        val dot = if (light) androidx.compose.ui.graphics.Color(0xFFECD6C8) else androidx.compose.ui.graphics.Color(0xFF2C2830)
        this.background(base).drawBehind {
            val step = 6.dp.toPx(); val r = 1.2.dp.toPx()
            var y = 0f; var row = 0
            while (y < size.height) {
                var x = if (row % 2 == 0) 0f else step / 2
                while (x < size.width) { drawCircle(dot, r, androidx.compose.ui.geometry.Offset(x, y)); x += step }
                y += step; row++
            }
        }
    }
}

/** Words on a rail: cream on dark, ink on the light rail. */
fun railText(state: com.tideclock.messmachine.AppState): androidx.compose.ui.graphics.Color = when (state.look) { "light" -> C.Ink; "panel" -> Hw.Engrave; else -> C.Cream }

/**
 * A button on a bar: words in Classic, an icon tile (with an optional small word under it) in the Pro looks.
 * Lit: a cream ring (ink on the light rail).
 */
@androidx.compose.runtime.Composable
fun BarBtn(
    state: com.tideclock.messmachine.AppState, text: String, icon: String, onClick: () -> Unit, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    on: Boolean? = null, onColor: androidx.compose.ui.graphics.Color = C.Yellow, enabled: Boolean = true, tag: String? = null, word: String? = null,
    tier: Tier = Tier.Normal, color: androidx.compose.ui.graphics.Color = C.Ink,
) {
    val lamped = on != null
    @Suppress("NAME_SHADOWING") val on = on == true
    if (state.look == "classic") { Btn(text, onClick, modifier, color = color, on = if (lamped) on else null, onColor = onColor, enabled = enabled, small = true, tight = true, tag = tag, tier = tier); return }
    if (state.look == "panel") { PanelKey(state, text, icon, onClick, modifier, on, onColor, enabled, tag, word, tier, lamped); return }
    val ring = if (state.look == "light") C.Ink else C.Cream
    androidx.compose.foundation.layout.Column(
        modifier.clickable(enabled = enabled) { onClick() }.let { if (tag != null) it.tagged(tag) else it }.padding(vertical = 2.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.border(if (on) 3.dp else 0.dp, if (on) ring else androidx.compose.ui.graphics.Color.Transparent, androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).padding(2.dp),
        ) { PixIcon(icon, 30.dp, dim = !enabled) }
        if (state.proWords && word != null) T(word.uppercase(), size = (8f * UiLook.big).sp, color = if (on) onColor.takeIf { state.look != "light" } ?: C.Ink else railText(state), lines = 1, spacing = .04.em)
    }
}


/**
 * A bar button in the hardware panel Look: a square bakelite key with a glyph from Tyler's own drawings engraved into
 * it (the Pro icons, cut to one colour), a lamp that lights when it is on, and the word stamped under it.
 */
@androidx.compose.runtime.Composable
fun PanelKey(
    state: com.tideclock.messmachine.AppState, text: String, icon: String, onClick: () -> Unit, modifier: androidx.compose.ui.Modifier,
    on: Boolean, onColor: androidx.compose.ui.graphics.Color, enabled: Boolean, tag: String?, word: String?, tier: Tier, lamped: Boolean = true,
) {
    // Nov 21 video: keys grow with the screen up to a cap (a Fold's inner screen gets big keys, not small ones far apart),
    // the glyph as big as the cap allows, and the lamp in the lip under the cap so the glyph sits in the middle
    // Nov 21 (second video): bigger again, and Large keys (Settings, Look) a quarter bigger still
    // Nov 22 sizing (Tyler: "on my tablet the dock buttons are enormous"): one size in real dp, not grown with the screen
    val capW = 64.rdp.value * UiLook.big
    val capH = 40.rdp.value * UiLook.big
    val src = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val res = Icons.all[icon]?.first
    androidx.compose.foundation.layout.Column(
        modifier.heightIn(min = Floor.KEY.rdp)
            .clickable(src, indication = null, enabled = enabled) { onClick() }.let { if (tag != null) it.tagged(tag) else it }
            .alpha(if (enabled) 1f else .4f).padding(vertical = 2.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.widthIn(max = capW.dp).fillMaxWidth().height(capH.dp + UiLook.lip).offset(0.dp, if (pressed) 2.dp else 0.dp)
                .bakelite(on, lampFor(tag, onColor, tier == Tier.Danger), pressed, danger = tier == Tier.Danger, brass = tier == Tier.Primary, lamped = lamped,
                    tint = Icons.all[icon]?.second?.let { tileFill(it) } ?: capTint(icon), fill = Icons.all[icon] != null)
                .padding(bottom = UiLook.lip),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            // the icon in its own colours (aged, crisp whole pixels), centred on the cap
            if (res != null) InlayIcon(res, (capH - 4f).dp, true, text)
            else T((if (text.length <= 5) text else text.take(3)).uppercase(), size = 12.sp, color = if (on) lerp(Hw.Engrave, onColor, .5f) else Hw.Engrave, lines = 1, spacing = 0.em, family = Worn, weight = androidx.compose.ui.text.font.FontWeight.Normal)
        }
        if (state.proWords && word != null) {
            if (state.wordPlate) PlateWord(word, on, onColor)
            else T(word.uppercase(), size = (8f * UiLook.big).sp, color = if (on) onColor else Hw.Engrave.copy(alpha = .8f), lines = 1, spacing = 0.em, family = Stencil, weight = androidx.compose.ui.text.font.FontWeight.Normal,
                modifier = androidx.compose.ui.Modifier.padding(top = 2.dp))
        }
    }
}
