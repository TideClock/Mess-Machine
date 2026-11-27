package com.tideclock.messmachine.ui

import androidx.compose.foundation.layout.Arrangement
import kotlin.math.roundToInt
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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
        // Nov 24 (Tyler's video): a third view, the Mixer, for each pad's level and each bank's
        PickSlider(listOf("Steps" to "Steps", "Roll" to "Roll", "Mixer" to "Desk"), if (state.mixerOn) 2 else if (state.tab == Tab.Roll) 1 else 0,
            { state.mixerOn = it == 2; state.tab = if (it == 1) Tab.Roll else Tab.Steps },
            Modifier.weight(1f), tags = listOf("seq-steps", "seq-roll", "seq-mixer"), tag = "seqview-chips")
    }
}

/**
 * The Mixer (Nov 24, Tyler's video: "a way to control volume on a per pad, per bank basis"): the bank's sixteen pads
 * as faders, each its sound's level (the same Volume as the Sample sheet's Shape), and one fader that moves the whole
 * bank together, keeping the pads' balance. Hold a bank number to mute it, as on the Play page.
 */
@Composable
fun MixerScreen(state: AppState) {
    @Suppress("UNUSED_VARIABLE") val v = state.version
    val P = state.project
    ScrollPane {
        SeqSwitch(state)
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            T("BANK", size = 11.sp, color = C.Dim, spacing = .1.em)
            for (b in 0 until 4) Btn("${b + 1}", { state.bank = b }, on = state.bank == b, small = true, tight = true, tag = "mixer-bank-$b",
                modifier = Modifier.weight(1f), color = if (state.bankMuted(b)) C.Riot else C.Ink)
        }
        val pads = (state.bank * 16 until state.bank * 16 + 16).filter { P.kit.getOrNull(it).orEmpty().isNotEmpty() }
        if (pads.isEmpty()) Note("Bank ${state.bank + 1} has no sounds yet.")
        else MixerFaders(state, pads)
    }
}

@Composable
private fun MixerFaders(state: AppState, pads: List<Int>) {
    val P = state.project
    Column {
        // the bank fader: everything in the bank up or down together
        var start by remember(state.bank) { mutableStateOf<Map<Int, Double>?>(null) }
        var bankLvl by remember(state.bank, state.version) { mutableStateOf(pads.map { P.edit(P.kit[it]).vol }.average().toFloat()) }
        androidx.compose.foundation.layout.Box(Modifier.tagged("mixer-bank")) { SliderRow("Bank ${state.bank + 1}", bankLvl, 0f..2f, { "${(it * 100).roundToInt()}%" }, { nv ->
            val from = start ?: pads.associateWith { P.edit(P.kit[it]).vol }.also { start = it }
            val avg = from.values.average().coerceAtLeast(.01)
            bankLvl = nv
            for ((pd, was) in from) { val k = P.kit[pd]; val e = P.edit(k).copy(); e.vol = (was * nv / avg).coerceIn(0.0, 2.0); state.setEdit(k, e, false) }
        }, { start = null; state.edit { } }, accent = C.Yellow) }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp).tagged("mixer-pads"), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (pd in pads) {
                val k = P.kit[pd]
                val col = padColor(Color(state.lib.colorOf(k)))
                val vol = P.edit(k).vol.toFloat()
                Column(Modifier.width(52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    T("${(vol * 100).roundToInt()}", size = 10.sp, color = C.Dim, family = Mono, lines = 1)
                    Vial(vol / 2f, col, true, "mixer-$pd", height = 150.dp) { x, on ->
                        if (on) { val e = P.edit(k).copy(); e.vol = (x * 2f).toDouble().coerceIn(0.0, 2.0); state.setEdit(k, e, false) }
                        else state.edit { }
                    }
                    T("${pd + 1}", size = 11.sp, color = C.Cream, lines = 1, modifier = Modifier.padding(top = 2.dp))
                    T(state.lib.titleOf(k).take(7).uppercase(), size = 8.sp, color = C.Dim, lines = 1)
                }
            }
        }
    }
}
