package com.tideclock.messmachine.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tideclock.messmachine.AppState

/**
 * The MIDI map: a small panel that floats over the page while you teach a controller your layout. Touch a pad, a
 * Padiano key or a synth knob, then press keys (or turn knobs) on the controller: each one lands where the panel
 * says and the place moves on, to the right and then down. The page under it stays live, tabs included.
 */
@Composable
fun MidiMapPanel(state: AppState, modifier: Modifier = Modifier) {
    val c = state.midiCursor
    // kept short (three thin rows) so it covers the page's header and not the first row of pads
    Column(modifier.fillMaxWidth().sticker(C.Ink, shadow = 5.dp, borderColor = C.Yellow).tagged("midimap").padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            T("MIDI MAP", size = 12.sp, color = C.Yellow, spacing = .1.em, lines = 1, modifier = Modifier.weight(1f))
            Btn("Skip", { state.midiSkip() }, small = true, enabled = c != null, tag = "midimap-skip")
            Btn("Clear", { state.midiClearAll() }, small = true, tier = Tier.Danger, enabled = state.midiNotes.isNotEmpty() || state.midiKnobs.isNotEmpty(), tag = "midimap-clear")
            Btn("Done", { state.switchMidiMap(false) }, small = true, on = true, onColor = C.Teal, tag = "midimap-done")
        }
        T(
            if (c == null) "Tap a pad, a Padiano key or chord box, or touch a knob, fader, the pitch wheel or the tempo. Buttons maps Play, Rec and more."
            else if (c.kind in listOf("knob", "fx", "wheel", "dock", "lfo", "tempo")) "Next: ${state.midiLabel(c)}. Turn a knob or slider."
            else "Next: ${state.midiLabel(c)}. Press a key or button.",
            size = 13.sp, color = C.Cream, lines = 2, modifier = Modifier.padding(top = 4.dp),
        )
        T(state.midiLast.ifEmpty { "${state.midiNotes.size} keys and ${state.midiKnobs.size} knobs mapped." }, size = 11.sp, color = C.Dim, lines = 2,
            weight = androidx.compose.ui.text.font.FontWeight.Normal, family = Mono, modifier = Modifier.padding(top = 2.dp))
        var list by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // note #19: Run moves on by itself after each key; One at a time waits for the next tap
            Btn("Run", { state.switchMidiRun(true) }, on = state.midiRun, onColor = C.Teal, small = true, tight = true, tag = "midimap-run")
            Btn("One at a time", { state.switchMidiRun(false) }, on = !state.midiRun, onColor = C.Teal, small = true, tight = true, tag = "midimap-one")
            Btn(if (list) "List ▴" else "List ▾", { list = !list }, on = list, small = true, tight = true, enabled = state.midiNotes.isNotEmpty() || state.midiKnobs.isNotEmpty(), tag = "midimap-list")
        }
        // round two: pages A to D (the same controller does other things on each), Shift's layer, and buttons
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            T("PAGE", size = 11.sp, color = C.Dim, spacing = .1.em)
            for (p in 0 until 4) Btn("ABCD"[p].toString(), { state.midiPageTo(p) }, on = state.midiPage == p, onColor = C.Yellow, small = true, tag = "midimap-page-$p")
            if (state.midiShift) T("⇧ SHIFT", size = 11.sp, color = C.Pink, family = Mono)
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            Select("Buttons ▾", state.midiActs, { state.midiAimAct(it) }, tag = "midimap-acts")
        }
        if (list) MidiMapList(state)
    }
}

/** Every key and knob on the map as a row: what it plays, teach it again, or take it off. */
@Composable
private fun MidiMapList(state: AppState) {
    val rows = state.midiNotes.entries.sortedBy { it.key }.map { Triple(it.key as Int?, null as Int?, it.value) } +
        state.midiKnobs.entries.sortedBy { it.key }.map { Triple(null as Int?, it.key as Int?, it.value) }
    Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(top = 6.dp).tagged("midimap-rows")) {
        for ((note, cc, t) in rows) {
            val code = note?.let { "n$it" } ?: "c$cc"
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                T(note?.let { state.midiKeyName(it, false) } ?: state.midiKeyName(cc ?: 0, true), size = 12.sp, color = C.Yellow, family = Mono, lines = 1, modifier = Modifier.width(70.dp))
                T(state.midiLabel(t), size = 12.sp, color = C.Cream, lines = 1, modifier = Modifier.weight(1f))
                Btn("Teach", { state.midiReteach(note, cc) }, small = true, tight = true, tag = "midimap-teach-$code")
                Btn("✕", { state.midiForget(note, cc) }, small = true, tight = true, tier = Tier.Danger, tag = "midimap-x-$code")
            }
        }
    }
}
