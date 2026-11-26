package com.tideclock.messmachine.ui

import com.tideclock.messmachine.ui.res.*

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
