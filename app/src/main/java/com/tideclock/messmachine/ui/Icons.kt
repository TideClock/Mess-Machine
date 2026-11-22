package com.tideclock.messmachine.ui

import com.tideclock.messmachine.R
import androidx.compose.foundation.background
import androidx.compose.ui.res.imageResource
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

/** The pixel icons (tools/icons, 32 x 32 art with an ink outline and a white glow). Written by tools/icons/export.py. */
object Icons {
    /** Icon name to drawable, and the tile color group it belongs to. */
    val all: Map<String, Pair<Int, String>> = mapOf(
        "Play" to (R.drawable.ic_play to "transport"),
        "Stop" to (R.drawable.ic_stop to "transport"),
        "Rec" to (R.drawable.ic_rec to "transport"),
        "Undo" to (R.drawable.ic_undo to "transport"),
        "Redo" to (R.drawable.ic_redo to "transport"),
        "Tempo" to (R.drawable.ic_tempo to "transport"),
        "Play tab" to (R.drawable.ic_play_tab to "place"),
        "Synth" to (R.drawable.ic_synth to "place"),
        "Steps" to (R.drawable.ic_steps to "place"),
        "Roll" to (R.drawable.ic_roll to "place"),
        "Song" to (R.drawable.ic_song to "place"),
        "Sounds" to (R.drawable.ic_sounds to "place"),
        "Pads" to (R.drawable.ic_pads to "place"),
        "Keys" to (R.drawable.ic_keys to "place"),
        "Step mode" to (R.drawable.ic_step_mode to "place"),
        "Desk" to (R.drawable.ic_desk to "place"),
        "Learn" to (R.drawable.ic_learn to "place"),
        "Messes" to (R.drawable.ic_messes to "place"),
        "MIDI map" to (R.drawable.ic_midi_map to "place"),
        "Notes (bug)" to (R.drawable.ic_notes_bug to "place"),
        "Menu" to (R.drawable.ic_menu to "perform"),
        "Edit" to (R.drawable.ic_edit to "perform"),
        "Pack" to (R.drawable.ic_pack to "perform"),
        "Hold" to (R.drawable.ic_hold to "perform"),
        "Chords" to (R.drawable.ic_chords to "perform"),
        "Repeat" to (R.drawable.ic_repeat to "perform"),
        "Glide" to (R.drawable.ic_glide to "perform"),
        "Lock" to (R.drawable.ic_lock to "perform"),
        "Pitch wheel" to (R.drawable.ic_pitch_wheel to "perform"),
        "Mouth" to (R.drawable.ic_mouth to "perform"),
        "Octave up" to (R.drawable.ic_octave_up to "perform"),
        "Octave down" to (R.drawable.ic_octave_down to "perform"),
        "Key and scale" to (R.drawable.ic_key_and_scale to "perform"),
        "Record to pad" to (R.drawable.ic_record_to_pad to "perform"),
        "FX" to (R.drawable.ic_fx to "sound"),
        "LFO" to (R.drawable.ic_lfo to "sound"),
        "Filter" to (R.drawable.ic_filter to "sound"),
        "Resonance" to (R.drawable.ic_resonance to "sound"),
        "XY square" to (R.drawable.ic_xy_square to "sound"),
        "XY sphere" to (R.drawable.ic_xy_sphere to "sound"),
        "Reverb" to (R.drawable.ic_reverb to "sound"),
        "Echo" to (R.drawable.ic_echo to "sound"),
        "Dirt" to (R.drawable.ic_dirt to "sound"),
        "Crush" to (R.drawable.ic_crush to "sound"),
        "Chorus" to (R.drawable.ic_chorus to "sound"),
        "Pump" to (R.drawable.ic_pump to "sound"),
        "Tape" to (R.drawable.ic_tape to "sound"),
        "Phaser" to (R.drawable.ic_phaser to "sound"),
        "Freeze" to (R.drawable.ic_freeze to "sound"),
        "Sine" to (R.drawable.ic_sine to "sound"),
        "Triangle" to (R.drawable.ic_triangle to "sound"),
        "Saw up" to (R.drawable.ic_saw_up to "sound"),
        "Saw down" to (R.drawable.ic_saw_down to "sound"),
        "Square" to (R.drawable.ic_square to "sound"),
        "Random steps" to (R.drawable.ic_random_steps to "sound"),
        "Sample" to (R.drawable.ic_sample to "sound"),
        "Settings" to (R.drawable.ic_settings to "tool"),
        "Copy" to (R.drawable.ic_copy to "tool"),
        "Paste" to (R.drawable.ic_paste to "tool"),
        "Clear" to (R.drawable.ic_clear to "tool"),
        "Share" to (R.drawable.ic_share to "tool"),
        "Star" to (R.drawable.ic_star to "tool"),
        "Move" to (R.drawable.ic_move to "tool"),
        "Mute" to (R.drawable.ic_mute to "tool"),
        "Solo" to (R.drawable.ic_solo to "tool"),
        "Import" to (R.drawable.ic_import to "tool"),
        "Update" to (R.drawable.ic_update to "tool"),
        "What the FAQ" to (R.drawable.ic_what_the_faq to "tool"),
        "Close" to (R.drawable.ic_close to "tool"),
        "Bake" to (R.drawable.ic_bake to "tool"),
        "Bottle it" to (R.drawable.ic_bottle_it to "tool"),
        "Random" to (R.drawable.ic_random to "tool"),
        "Auto" to (R.drawable.ic_auto to "tool"),
    )
    fun res(name: String): Int? = all[name]?.first
}

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
            androidx.compose.ui.graphics.ImageBitmap.imageResource(res), contentDescription = name,
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
        val rnd = java.util.Random(7L + size.width.toLong())
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
    on: Boolean = false, onColor: androidx.compose.ui.graphics.Color = C.Yellow, enabled: Boolean = true, tag: String? = null, word: String? = null,
    tier: Tier = Tier.Normal, color: androidx.compose.ui.graphics.Color = C.Ink,
) {
    if (state.look == "classic") { Btn(text, onClick, modifier, color = color, on = on, onColor = onColor, enabled = enabled, small = true, tight = true, tag = tag, tier = tier); return }
    if (state.look == "panel") { PanelKey(state, text, icon, onClick, modifier, on, onColor, enabled, tag, word, tier); return }
    val ring = if (state.look == "light") C.Ink else C.Cream
    androidx.compose.foundation.layout.Column(
        modifier.clickable(enabled = enabled) { onClick() }.let { if (tag != null) it.tagged(tag) else it }.padding(vertical = 2.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.border(if (on) 3.dp else 0.dp, if (on) ring else androidx.compose.ui.graphics.Color.Transparent, androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).padding(2.dp),
        ) { PixIcon(icon, 30.dp, dim = !enabled) }
        if (state.proWords && word != null) T(word.uppercase(), size = 8.sp, color = if (on) onColor.takeIf { state.look != "light" } ?: C.Ink else railText(state), lines = 1, spacing = .04.em)
    }
}


/**
 * A bar button in the hardware panel Look: a square bakelite key with a glyph from Tyler's own drawings engraved into
 * it (the Pro icons, cut to one colour), a lamp that lights when it is on, and the word stamped under it.
 */
@androidx.compose.runtime.Composable
fun PanelKey(
    state: com.tideclock.messmachine.AppState, text: String, icon: String, onClick: () -> Unit, modifier: androidx.compose.ui.Modifier,
    on: Boolean, onColor: androidx.compose.ui.graphics.Color, enabled: Boolean, tag: String?, word: String?, tier: Tier,
) {
    val src = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val res = Icons.all[icon]?.first
    androidx.compose.foundation.layout.Column(
        modifier.heightIn(min = (44f / UiLook.scale.coerceIn(.4f, 2f)).coerceAtLeast(40f).dp)
            .clickable(src, indication = null, enabled = enabled) { onClick() }.let { if (tag != null) it.tagged(tag) else it }
            .alpha(if (enabled) 1f else .4f).padding(vertical = 2.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.widthIn(max = 44.dp).fillMaxWidth().height(32.dp).offset(0.dp, if (pressed) 2.dp else 0.dp)
                .bakelite(on, onColor, pressed, danger = tier == Tier.Danger, brass = tier == Tier.Primary),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            if (res != null) androidx.compose.foundation.Image(
                androidx.compose.ui.graphics.ImageBitmap.imageResource(res), contentDescription = text,
                modifier = androidx.compose.ui.Modifier.padding(start = 8.dp).size(24.dp),
                filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(if (on) lerp(Hw.Engrave, onColor, .5f) else Hw.Engrave, androidx.compose.ui.graphics.BlendMode.SrcIn),
            ) else T(text.take(3).uppercase(), size = 11.sp, color = Hw.Engrave, lines = 1, modifier = androidx.compose.ui.Modifier.padding(start = 8.dp))
        }
        if (state.proWords && word != null) T(word.uppercase(), size = 8.sp, color = if (on) onColor else Hw.Engrave.copy(alpha = .8f), lines = 1, spacing = 0.em,
            modifier = androidx.compose.ui.Modifier.padding(top = 2.dp))
    }
}
