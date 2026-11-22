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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.tideclock.messmachine.AppState
import com.tideclock.messmachine.FX_STRIP
import com.tideclock.messmachine.Overlay
import com.tideclock.messmachine.Tab
import com.tideclock.messmachine.engine.Project

/** The Edit tray's five drawers, in the order the tabs show them. */
val EDIT_TABS = listOf("sound" to "Sound", "fx" to "FX", "keys" to "Keys", "mod" to "Mod", "more" to "More")

/**
 * The Edit tray (the overhaul's last build): Edit, then a pad, opens everything about that pad in one place, in five
 * drawers. Sound is the sound itself, FX its faders and filter, Keys how it plays as notes, Mod what moves it (the
 * LFO, the XY square, lanes), More the rest (mute, solo, route, move, clear). The pad menu's list is the Classic
 * layout's way in and is still there.
 */
@Composable
fun EditTray(state: AppState, pad: Int) {
    val P = state.project
    val k = P.kit.getOrNull(pad).orEmpty()
    if (k.isEmpty()) { T("This pad is empty.", size = 13.sp, color = C.Dim); return }
    val col = padColor(Color(state.lib.colorOf(k)))
    Row(Modifier.fillMaxWidth().tagged("edit-tabs"), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        for ((id, name) in EDIT_TABS) {
            if (UiLook.panel) {
                // the panel Look: five drawer keys, the open drawer's lamp lit in the pad's colour; narrow words, so a Fold's cover fits them
                val on = state.editTab == id
                androidx.compose.foundation.layout.Box(
                    Modifier.weight(1f).heightIn(min = (40f / UiLook.scale.coerceIn(.4f, 2f)).coerceAtLeast(36f).dp + UiLook.lip)
                        .bakelite(on, col, false).clickable { state.pickEditTab(id) }.tagged("edit-tab-$id")
                        .semantics { selected = on }.padding(start = 2.dp, end = 2.dp, bottom = UiLook.lip),
                    contentAlignment = Alignment.Center,
                ) { T(name.uppercase(), size = 12.sp, color = if (on) lerp(Hw.Engrave, col, .35f) else Hw.Engrave, lines = 1, spacing = 0.em, family = Worn, weight = androidx.compose.ui.text.font.FontWeight.Normal) }
            } else Btn(name, { state.pickEditTab(id) }, on = state.editTab == id, onColor = col, small = true, tight = true,
                tag = "edit-tab-$id", modifier = Modifier.weight(1f))
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 10.dp).tagged("edit-${state.editTab}")) {
        when (state.editTab) {
            "sound" -> {
                val recHere = state.micOn && state.micPad == pad
                Wrap(gap = 6.dp) {
                    Btn("Hear it", { state.preview(k) }, color = col, tag = "menu-hear")
                    if (Project.isSynth(k)) Btn("◎ Synth", { state.closeEdit(); state.tab = Tab.Synth }, tag = "menu-synth")
                    else Btn("✂ Sample", { state.closeEdit(); state.overlay = Overlay.Doctor(k) }, tag = "menu-doctor")
                    Btn("Swap sound", { state.closeEdit(); state.overlay = Overlay.Picker(pad) }, tag = "menu-swap")
                    Btn(if (recHere) "■ Stop recording" else "● Record onto it", { state.closeEdit(); state.askRecord(pad) }, enabled = !state.micOn || recHere, tag = "menu-record")
                    Btn(if (k in state.favs) "★ Starred" else "☆ Star", { state.toggleFav(k) }, on = k in state.favs, onColor = C.Yellow, tag = "menu-star")
                    Btn("Keep in Workbench", { state.keepInBench(pad) }, tag = "menu-keep")
                    Btn("Share this sound", { state.shareSoundWav(k) }, tag = "menu-share")
                }
            }
            "fx" -> {
                FxStrip(state, pad, col)
                // the panel Look's vials carry their own lamps (in, out, hold for Auto): no rows of chips under them
                if (!UiLook.panel) FxTray(state, pad)
                else Help("Drag a vial to fill it. Its lamp takes the effect out and back; hold the lamp for its Auto lane.", Modifier.padding(top = 4.dp))
            }
            "keys" -> {
                Wrap(gap = 6.dp) {
                    Btn(if (pad in state.keyboards) "Padiano: hide" else "Padiano under this row", { state.togglePack(pad) }, on = pad in state.keyboards, onColor = col, tag = "menu-piano")
                    Btn(if (state.levelsPad == pad) "16 Levels: off" else "16 Levels: soft to hard", { state.switchLevels(pad) }, on = state.levelsPad == pad, onColor = C.Yellow, tag = "menu-levels")
                }
                T("GLIDE", size = 11.sp, color = C.Dim, spacing = .1.em, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                GlideTray(state, pad)
            }
            "mod" -> {
                // the XY square first: one tap, where the thumb lands; the LFO below it fills the drawer
                Wrap(Modifier.padding(bottom = 8.dp), gap = 6.dp) {
                    Btn("XY square: ${state.xyOf(pad).name}", { state.closeEdit(); state.overlay = Overlay.Xy(pad) }, tag = "menu-xy")
                }
                LfoStrip(state, pad, col)
                LfoTray(state, pad)
                T("RECORD A LANE FOR", size = 11.sp, color = C.Dim, spacing = .1.em, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                Wrap(gap = 6.dp) {
                    for ((t, name) in FX_STRIP) Btn(name, { state.closeEdit(); state.openAutoLane(pad, t) }, small = true, tight = true, tag = "edit-lane-$t")
                }
            }
            else -> {
                Wrap(gap = 6.dp) {
                    Btn(if (P.mutes.contains(k)) "Unmute" else "Mute", { state.toggleMute(k) }, on = P.mutes.contains(k), onColor = C.Riot, tag = "menu-mute-$pad")
                    Btn(if (P.solos.contains(k)) "Unsolo" else "Solo", { state.toggleSolo(k) }, on = P.solos.contains(k), onColor = C.Yellow, tag = "menu-solo-$pad")
                    Btn("Route: " + (if (P.opt(pad).route == 0) "mix" else "bus ${P.opt(pad).route}"), { state.cycleRoute(pad) }, tag = "menu-route")
                    Btn("Move to another pad", { state.closeEdit(); state.armSwap(pad) }, tag = "menu-move")
                }
                T("FANNYPACK STRIPS", size = 11.sp, color = C.Dim, spacing = .1.em, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                PackTray(state, pad)
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (P.pat().usedBy(k)) Btn("Clear from loop ${Project.LETTERS[P.cur]}", { state.clearSoundInLoop(pad) }, tier = Tier.Danger, tag = "menu-clearloop")
                    Btn("Clear pad", { state.closeEdit(); state.clearPad(pad) }, tier = Tier.Danger, tag = "menu-clear")
                }
            }
        }
    }
}
