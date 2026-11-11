package com.tideclock.messmachine.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tideclock.messmachine.AppState

/**
 * Packs (SE-17): sound packs sent from the Vault. Each installs as its own jar in Sounds; Remove takes it off
 * the phone (pads that used it go quiet until it is installed again).
 */
@Composable
fun PacksScreen(state: AppState) {
    @Suppress("UNUSED_VARIABLE") val v = state.version
    ScrollPane(hPad = 14.dp, vPad = 0.dp) {
        Note("Packs are sounds picked in the Vault and sent to the app. Each one lands in Sounds as its own jar.", modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Check for packs", { state.refreshPacks() }, small = true, tag = "packs-refresh")
            if (state.packNote.isNotEmpty()) Note(state.packNote, modifier = Modifier.weight(1f))
        }
        val offers = state.packOffers.orEmpty()
        val installed = state.packs.installed
        for (o in offers) {
            val have = installed[o.id]
            Card(Modifier.fillMaxWidth().padding(bottom = 10.dp).tagged("pack-${o.id}")) {
                Column {
                    T(o.name.uppercase(), size = 13.sp, color = C.Yellow, spacing = .08.em)
                    if (o.blurb.isNotEmpty()) T(o.blurb, size = 13.sp, color = C.Cream, modifier = Modifier.padding(top = 2.dp))
                    Note("${o.clips} sounds · %.1f MB".format(o.bytes / 1e6) + (if (have != null) " · installed" + (if (have < o.version) ", a newer version is here" else "") else ""), modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)) // format-ok: number only
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val busy = state.packBusy == o.id
                        if (have == null || have < o.version) Btn(if (busy) "Installing…" else if (have == null) "Install" else "Update", { state.installPack(o) }, on = true, onColor = C.Teal, small = true, enabled = state.packBusy.isEmpty(), tag = "pack-install-${o.id}")
                        if (have != null) Btn("Remove", { state.removePack(o.id) }, small = true, tier = Tier.Quiet, tag = "pack-remove-${o.id}")
                    }
                }
            }
        }
        // packs on the phone that the release no longer lists
        for ((id, _) in installed.filter { (id, _) -> offers.none { it.id == id } }) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                T(state.lib.cab("pack:$id").name, size = 13.sp, modifier = Modifier.weight(1f))
                Btn("Remove", { state.removePack(id) }, small = true, tier = Tier.Quiet, tag = "pack-remove-$id")
            }
        }
        T(" ", size = 30.sp)
    }
}
