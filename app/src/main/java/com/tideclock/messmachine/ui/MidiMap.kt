package com.tideclock.messmachine.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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
            if (c == null) "Tap a pad, a Padiano key or a synth knob."
            else if (c.kind == "knob") "Next: ${state.midiLabel(c)}. Turn a knob."
            else "Next: ${state.midiLabel(c)}. Press a key.",
            size = 13.sp, color = C.Cream, lines = 2, modifier = Modifier.padding(top = 4.dp),
        )
        T(state.midiLast.ifEmpty { "${state.midiNotes.size} keys and ${state.midiKnobs.size} knobs mapped." }, size = 11.sp, color = C.Dim, lines = 2,
            weight = androidx.compose.ui.text.font.FontWeight.Normal, family = Mono, modifier = Modifier.padding(top = 2.dp))
    }
}
