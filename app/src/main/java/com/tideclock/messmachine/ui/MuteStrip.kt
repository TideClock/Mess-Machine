package com.tideclock.messmachine.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tideclock.messmachine.AppState
import com.tideclock.messmachine.engine.Project

/**
 * Mutes (Nov 21 video): a bank's 16 pads as keys whose lamp is lit while the pad is heard, the four banks to pick
 * which one shows, and the whole bank on or off. Under the loops strip (any page) and on Perform.
 */
@Composable
fun MuteRows(state: AppState, modifier: Modifier = Modifier) {
    @Suppress("UNUSED_VARIABLE") val vv = state.version
    val P = state.project
    val b = if (state.muteBankView in 0 until Project.NPADS / Project.BANK) state.muteBankView else state.bank
    Column(modifier.fillMaxWidth().tagged("mute-rows")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            T("MUTES", size = 11.sp, color = C.Yellow, spacing = .1.em, modifier = Modifier.width(54.dp))
            for (i in 0 until Project.NPADS / Project.BANK) Btn("${i + 1}", { state.muteBankView = i }, on = b == i, small = true, tight = true, tag = "mute-view-$i", modifier = Modifier.weight(1f))
            // the whole bank: lamp lit while any of it is heard
            Btn("Bank ${b + 1}", { state.toggleBankMute(b) }, on = !state.bankMuted(b), onColor = C.Teal, small = true, tag = "mute-bank")
        }
        for (r in 0 until 2) Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (c in 0 until 8) {
                val pad = b * Project.BANK + r * 8 + c
                val k = P.kit[pad]
                val col = if (k.isEmpty()) C.Dim else padColor(Color(state.lib.colorOf(k)))
                Btn("${pad % Project.BANK + 1}", { state.togglePadMute(pad) }, on = state.padAudible(pad), onColor = col, enabled = k.isNotEmpty(), small = true, tight = true,
                    tag = "mute-pad-${r * 8 + c}", modifier = Modifier.weight(1f))
            }
        }
    }
}
