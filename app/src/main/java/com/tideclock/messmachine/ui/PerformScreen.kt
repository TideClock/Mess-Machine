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
        // Nov 22 (second video): no row of loops here; the loop readout and the strip it pulls down are on every page
        FxPanel(state)
        Card(Modifier.fillMaxWidth().padding(top = 14.dp).tagged("perform-pad")) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Heading(if (k.isEmpty()) "Pad ${pad + 1}" else "Pad ${pad + 1} · " + state.lib.titleOf(k), size = 18.sp, modifier = Modifier.weight(1f))
                }
                if (k.isEmpty()) {
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
        // Nov 22: two views, one pick, so a slider
        PickSlider(listOf("Steps" to "Steps", "Roll" to "Roll"), if (state.tab == Tab.Roll) 1 else 0, { state.tab = if (it == 1) Tab.Roll else Tab.Steps },
            Modifier.weight(1f), tags = listOf("seq-steps", "seq-roll"), tag = "seqview-chips")
    }
}
