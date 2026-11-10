package com.tideclock.messmachine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tideclock.messmachine.AppState
import com.tideclock.messmachine.Overlay
import com.tideclock.messmachine.engine.Project

/** Loop slots A to H, length, copy, clear. Shared by Steps and Roll. */
@Composable
fun SlotBar(state: AppState) {
    val P = state.project
    Column(Modifier.padding(bottom = 10.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            for (i in 0 until Project.NSLOTS) {
                val used = P.pats[i].used()
                val playingHere = state.playing && state.heardPat == i
                Box(
                    Modifier
                        .size(40.dp)
                        .sticker(
                            if (i == P.cur) C.Letters[i] else if (used) C.Card2 else C.Card, radius = 10.dp,
                            shadow = if (i == P.cur) 1.dp else 3.dp, border = if (playingHere) 3.dp else 2.dp, borderColor = if (playingHere) C.Yellow else C.Black,
                        )
                        .clickable { state.selectSlot(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    T(Project.LETTERS[i].toString(), size = 16.sp, weight = FontWeight.Black, color = if (i == P.cur) C.Ink else if (used) C.Cream else C.Dim)
                }
            }
        }
        Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Btn(if (P.pat().len == 32) "2 bars" else "1 bar", { state.toggleLen() }, small = true)
            if (P.pat().len == 32) {
                Btn("1-16", { state.page = 0 }, on = state.page == 0, small = true)
                Btn("17-32", { state.page = 1 }, on = state.page == 1, small = true)
            }
            Select("Copy to", (0 until Project.NSLOTS).filter { it != P.cur }.map { it to "Loop ${Project.LETTERS[it]}" }, { state.copySlotTo(it) })
            Btn("Clear loop", { state.clearSlot() }, small = true, enabled = P.pat().used())
        }
    }
}

@Composable
fun StepsScreen(state: AppState) {
    val P = state.project
    val pat = P.pat()
    val off = (if (pat.len == 32) state.page else 0) * 16
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        SlotBar(state)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (b in 0 until 4) {
                val n = (0 until Project.BANK).count { P.kit[b * Project.BANK + it].isNotEmpty() }
                Btn("Bank ${b + 1} · $n", { state.bank = b }, on = state.bank == b, small = true)
            }
            Btn("Hear on tap", { state.switchPlayOnTap(!state.playOnTap) }, on = state.playOnTap, onColor = C.Teal, small = true)
        }
        val rows = (0 until Project.BANK).map { state.bank * Project.BANK + it }.filter { P.kit[it].isNotEmpty() }
        if (rows.isEmpty()) {
            T("Bank ${state.bank + 1} is empty. Put sounds on its pads from the Pads or Sounds tab.", color = C.Dim, weight = FontWeight.Medium)
        }
        val playStep = if (state.playing && state.heardPat == P.cur) state.heardStep else -1
        for (i in rows) {
            val k = P.kit[i]
            val col = Color(state.lib.colorOf(k))
            val audible = P.audible(k)
            Card(Modifier.fillMaxWidth().padding(bottom = 10.dp).alpha(if (audible) 1f else .55f), color = if (i == P.sel) C.Card2 else C.Card) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.weight(1f).clickable { state.selectPad(i, true) }, verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(14.dp).background(col, CircleShape).border(2.dp, C.Black, CircleShape))
                            T("${i + 1}", size = 12.sp, color = C.Dim, modifier = Modifier.padding(start = 6.dp, end = 6.dp))
                            T(state.lib.titleOf(k), size = 14.sp, lines = 1)
                        }
                        Btn("M", { state.toggleMute(k) }, on = P.mutes.contains(k), onColor = C.Riot, small = true)
                        Btn("S", { state.toggleSolo(k) }, on = P.solos.contains(k), onColor = C.Yellow, small = true)
                        Btn("✂", { state.overlay = Overlay.Doctor(k) }, small = true)
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        for (j in off until off + 16) {
                            val on = pat.step(k, j)
                            val beat = j % 4 == 0
                            val now = j == playStep
                            Box(
                                Modifier
                                    .weight(1f)
                                    .aspectRatio(.8f)
                                    .background(if (on) col else if (beat) C.Card2 else C.Paper, RoundedCornerShape(5.dp))
                                    .border(if (now) 3.dp else 1.5.dp, if (now) C.Yellow else C.Black, RoundedCornerShape(5.dp))
                                    .clickable { state.toggleStep(k, j) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (pat.hasNoteAt(k, j)) Box(Modifier.size(6.dp).background(if (on) C.Ink else C.Cream, CircleShape))
                            }
                        }
                    }
                }
            }
        }
        T("M mutes, S solos, ✂ opens the doctor. A dot means the pad has piano roll notes on that step.", size = 12.sp, color = C.Dim, weight = FontWeight.Medium)
    }
}
