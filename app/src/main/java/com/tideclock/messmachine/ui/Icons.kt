package com.tideclock.messmachine.ui

import com.tideclock.messmachine.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size

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
            androidx.compose.ui.res.painterResource(res), contentDescription = name,
            modifier = androidx.compose.ui.Modifier.size(size), filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
            alpha = if (dim) .55f else 1f,
        )
    }
}
