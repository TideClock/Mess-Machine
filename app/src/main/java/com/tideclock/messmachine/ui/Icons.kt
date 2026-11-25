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

/** The pixel icons (tools/icons, 32 x 32 art with an ink outline and a white glow). Written by tools/icons/export.py. */
object Icons {
    /** The icon for a button's words (Nov 21): "✂ Sample" finds Sample, "☆ Star" finds Star; null when there is none. */
    fun forLabel(text: String): org.jetbrains.compose.resources.DrawableResource? {
        val w = text.replace(Regex("[^A-Za-z ]"), " ").trim().replace(Regex("\\s+"), " ")
        if (w.isEmpty()) return null
        all[w]?.let { return it.first }
        return all.entries.firstOrNull { it.key.equals(w, ignoreCase = true) }?.value?.first
    }

    /** Icon name to drawable, and the tile color group it belongs to. */
    val all: Map<String, Pair<org.jetbrains.compose.resources.DrawableResource, String>> = mapOf(
        "Play" to (Res.drawable.ic_play to "transport"),
        "Stop" to (Res.drawable.ic_stop to "transport"),
        "Rec" to (Res.drawable.ic_rec to "transport"),
        "Undo" to (Res.drawable.ic_undo to "transport"),
        "Redo" to (Res.drawable.ic_redo to "transport"),
        "Tempo" to (Res.drawable.ic_tempo to "transport"),
        "Play tab" to (Res.drawable.ic_play_tab to "place"),
        "Synth" to (Res.drawable.ic_synth to "place"),
        "Steps" to (Res.drawable.ic_steps to "place"),
        "Roll" to (Res.drawable.ic_roll to "place"),
        "Song" to (Res.drawable.ic_song to "place"),
        "Sounds" to (Res.drawable.ic_sounds to "place"),
        "Pads" to (Res.drawable.ic_pads to "place"),
        "Keys" to (Res.drawable.ic_keys to "place"),
        "Step mode" to (Res.drawable.ic_step_mode to "place"),
        "Desk" to (Res.drawable.ic_desk to "place"),
        "Learn" to (Res.drawable.ic_learn to "place"),
        "Messes" to (Res.drawable.ic_messes to "place"),
        "MIDI map" to (Res.drawable.ic_midi_map to "place"),
        "Notes (bug)" to (Res.drawable.ic_notes_bug to "place"),
        "Menu" to (Res.drawable.ic_menu to "perform"),
        "Edit" to (Res.drawable.ic_edit to "perform"),
        "Pack" to (Res.drawable.ic_pack to "perform"),
        "Hold" to (Res.drawable.ic_hold to "perform"),
        "Chords" to (Res.drawable.ic_chords to "perform"),
        "Repeat" to (Res.drawable.ic_repeat to "perform"),
        "Glide" to (Res.drawable.ic_glide to "perform"),
        "Lock" to (Res.drawable.ic_lock to "perform"),
        "Pitch wheel" to (Res.drawable.ic_pitch_wheel to "perform"),
        "Mouth" to (Res.drawable.ic_mouth to "perform"),
        "Octave up" to (Res.drawable.ic_octave_up to "perform"),
        "Octave down" to (Res.drawable.ic_octave_down to "perform"),
        "Key and scale" to (Res.drawable.ic_key_and_scale to "perform"),
        "Record to pad" to (Res.drawable.ic_record_to_pad to "perform"),
        "FX" to (Res.drawable.ic_fx to "sound"),
        "LFO" to (Res.drawable.ic_lfo to "sound"),
        "Filter" to (Res.drawable.ic_filter to "sound"),
        "Resonance" to (Res.drawable.ic_resonance to "sound"),
        "XY square" to (Res.drawable.ic_xy_square to "sound"),
        "XY sphere" to (Res.drawable.ic_xy_sphere to "sound"),
        "Reverb" to (Res.drawable.ic_reverb to "sound"),
        "Echo" to (Res.drawable.ic_echo to "sound"),
        "Dirt" to (Res.drawable.ic_dirt to "sound"),
        "Crush" to (Res.drawable.ic_crush to "sound"),
        "Chorus" to (Res.drawable.ic_chorus to "sound"),
        "Pump" to (Res.drawable.ic_pump to "sound"),
        "Tape" to (Res.drawable.ic_tape to "sound"),
        "Phaser" to (Res.drawable.ic_phaser to "sound"),
        "Freeze" to (Res.drawable.ic_freeze to "sound"),
        "Sine" to (Res.drawable.ic_sine to "sound"),
        "Triangle" to (Res.drawable.ic_triangle to "sound"),
        "Saw up" to (Res.drawable.ic_saw_up to "sound"),
        "Saw down" to (Res.drawable.ic_saw_down to "sound"),
        "Square" to (Res.drawable.ic_square to "sound"),
        "Random steps" to (Res.drawable.ic_random_steps to "sound"),
        "Sample" to (Res.drawable.ic_sample to "sound"),
        "Settings" to (Res.drawable.ic_settings to "tool"),
        "Copy" to (Res.drawable.ic_copy to "tool"),
        "Paste" to (Res.drawable.ic_paste to "tool"),
        "Clear" to (Res.drawable.ic_clear to "tool"),
        "Share" to (Res.drawable.ic_share to "tool"),
        "Star" to (Res.drawable.ic_star to "tool"),
        "Move" to (Res.drawable.ic_move to "tool"),
        "Mute" to (Res.drawable.ic_mute to "tool"),
        "Solo" to (Res.drawable.ic_solo to "tool"),
        "Import" to (Res.drawable.ic_import to "tool"),
        "Update" to (Res.drawable.ic_update to "tool"),
        "What the FAQ" to (Res.drawable.ic_what_the_faq to "tool"),
        "Close" to (Res.drawable.ic_close to "tool"),
        "Drop" to (Res.drawable.ic_drop to "tool"),
        "Bake" to (Res.drawable.ic_bake to "tool"),
        "Bottle it" to (Res.drawable.ic_bottle_it to "tool"),
        "Random" to (Res.drawable.ic_random to "tool"),
        "Auto" to (Res.drawable.ic_auto to "tool"),
        // Nov 22 (Tyler: "if they're not in, put them in"): the synth, the waves and filter shapes, the loop strip,
        // the throws, the edit tray's tabs
        "Analog" to (Res.drawable.ic_analog to "synth"),
        "FM" to (Res.drawable.ic_fm to "synth"),
        "Wavetable" to (Res.drawable.ic_wavetable to "synth"),
        "Granular" to (Res.drawable.ic_granular to "synth"),
        "Drums" to (Res.drawable.ic_drums to "synth"),
        "Pluck" to (Res.drawable.ic_pluck to "synth"),
        "Poly" to (Res.drawable.ic_poly to "synth"),
        "Mono" to (Res.drawable.ic_mono to "synth"),
        "Acid" to (Res.drawable.ic_acid to "synth"),
        "Simple" to (Res.drawable.ic_simple to "synth"),
        "Full" to (Res.drawable.ic_full to "synth"),
        "Wobble" to (Res.drawable.ic_wobble to "sound"),
        "Low pass" to (Res.drawable.ic_low_pass to "sound"),
        "High pass" to (Res.drawable.ic_high_pass to "sound"),
        "Band pass" to (Res.drawable.ic_band_pass to "sound"),
        "Wait bar" to (Res.drawable.ic_wait_bar to "loop"),
        "Now" to (Res.drawable.ic_now to "loop"),
        "Count in" to (Res.drawable.ic_count_in to "loop"),
        "Click" to (Res.drawable.ic_click to "loop"),
        "Bar plus" to (Res.drawable.ic_bar_plus to "loop"),
        "Bar minus" to (Res.drawable.ic_bar_minus to "loop"),
        "Double" to (Res.drawable.ic_double to "loop"),
        "Stutter" to (Res.drawable.ic_stutter to "throw"),
        "Tape stop" to (Res.drawable.ic_tape_stop to "throw"),
        "Gate" to (Res.drawable.ic_gate to "throw"),
        "Ring mod" to (Res.drawable.ic_ring_mod to "throw"),
        "Reverse" to (Res.drawable.ic_reverse to "throw"),
        // Nov 24 (Tyler's video): the loops as block letters and Rest as a bed, for the song painter
        "Loop A" to (Res.drawable.ic_loop_a to "loop"), "Loop B" to (Res.drawable.ic_loop_b to "loop"), "Loop C" to (Res.drawable.ic_loop_c to "loop"),
        "Loop D" to (Res.drawable.ic_loop_d to "loop"), "Loop E" to (Res.drawable.ic_loop_e to "loop"), "Loop F" to (Res.drawable.ic_loop_f to "loop"),
        "Loop G" to (Res.drawable.ic_loop_g to "loop"), "Loop H" to (Res.drawable.ic_loop_h to "loop"), "Rest" to (Res.drawable.ic_rest to "loop"),
        "Sound" to (Res.drawable.ic_sound to "edit"),
        "More" to (Res.drawable.ic_more to "edit"),
    )
    /** Short names the app shows for things whose icon has a longer name (the vials, waves, throws, tabs). */
    val alias: Map<String, String> = mapOf(
        "Verb" to "Reverb", "Chor" to "Chorus", "Phase" to "Phaser", "Cut" to "Filter", "Res" to "Resonance",
        "Tri" to "Triangle", "Saw ↑" to "Saw up", "Saw ↓" to "Saw down", "Lo" to "Low pass", "Hi" to "High pass", "Band" to "Band pass",
        "Random" to "Random steps", "Echo throw" to "Echo", "Dirty" to "Dirt", "Oct down" to "Octave down", "Mod" to "LFO",
        "Wavetable" to "Wavetable", "Granular" to "Granular", "Drums" to "Drums",
    )
    /** The icon for a name, through the short names. */
    fun named(name: String?): org.jetbrains.compose.resources.DrawableResource? = if (name == null) null else all[alias[name] ?: name]?.first
    /** The tile colour group of an icon by name or by a button's words; null when there is no icon. */
    fun groupOf(name: String?): String? {
        if (name == null) return null
        all[alias[name] ?: name]?.let { return it.second }
        val w = name.replace(Regex("[^A-Za-z ]"), " ").trim().replace(Regex("\\s+"), " ")
        if (w.isEmpty()) return null
        return all.entries.firstOrNull { it.key.equals(w, ignoreCase = true) }?.value?.second
    }
    fun res(name: String): org.jetbrains.compose.resources.DrawableResource? = all[name]?.first
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
