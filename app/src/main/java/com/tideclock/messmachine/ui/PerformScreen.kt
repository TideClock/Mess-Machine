package com.tideclock.messmachine.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tideclock.messmachine.AppState
import com.tideclock.messmachine.Tab

/**
 * The Perform tab (Nov 21 note): the whole performance surface on one page. The throws and the Mud filter for the
 * mix, then the picked pad's XY square and its FX faders. The perform row stays the quick strip; this is the full one.
 */
@Composable
fun PerformScreen(state: AppState) {
    val P = state.project
    val pad = P.sel
    val k = P.kit.getOrNull(pad).orEmpty()
    ScrollPane {
        // Nov 21 video: loops and mutes on Perform (the same rows ride under LOOP on every page)
        Column(Modifier.fillMaxWidth().padding(top = 6.dp).tagged("perform-live")) {
            T("LOOPS", size = 11.sp, color = C.Yellow, spacing = .1.em, modifier = Modifier.padding(bottom = 4.dp))
            LoopKeys(state, close = false)
            MuteRows(state, Modifier.padding(top = 8.dp))
        }
        FxPanel(state)
        Card(Modifier.fillMaxWidth().padding(top = 14.dp).tagged("perform-pad")) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Heading(if (k.isEmpty()) "Pad ${pad + 1}" else "Pad ${pad + 1} · " + state.lib.titleOf(k), size = 18.sp, modifier = Modifier.weight(1f))
                }
                if (k.isEmpty()) {
                    Help("Pick a loaded pad on Play and its XY square and FX faders show here.", modifier = Modifier.padding(top = 6.dp))
                } else {
                    val col = padColor(Color(state.lib.colorOf(k)))
                    T("XY SQUARE · " + state.xyOf(pad).name.uppercase(), size = 11.sp, color = C.Yellow, spacing = .1.em, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
                    PadXy(state, pad, Modifier.fillMaxWidth(), tag = "perform-xy")
                    T("FX FADERS", size = 11.sp, color = C.Yellow, spacing = .1.em, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                    FxStrip(state, pad, col)
                }
            }
        }
    }
}

/** Steps and Roll are one Sequence tab (Nov 21 note): two views of the same loop, switched at the top. */
@Composable
fun SeqSwitch(state: AppState) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).tagged("seq-switch"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        T("SEQUENCE", size = 11.sp, color = C.Dim, spacing = .12.em)
        Btn("Steps", { state.tab = Tab.Steps }, on = state.tab == Tab.Steps, onColor = C.Yellow, small = true, tag = "seq-steps", modifier = Modifier.weight(1f))
        Btn("Roll", { state.tab = Tab.Roll }, on = state.tab == Tab.Roll, onColor = C.Teal, small = true, tag = "seq-roll", modifier = Modifier.weight(1f))
    }
}
