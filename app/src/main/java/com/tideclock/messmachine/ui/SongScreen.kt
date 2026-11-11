package com.tideclock.messmachine.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tideclock.messmachine.AppState
import com.tideclock.messmachine.engine.Project

@Composable
fun SongScreen(state: AppState) {
    val P = state.project
    val len = P.songLen()
    var steps = 0
    for (k in 0 until len) steps += if (P.song[k] >= 0) P.pats[P.song[k]].len else 16
    val secs = (steps * 60.0 / P.bpm / 4).toInt()
    ScrollPane {
        SlotBar(state)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 14.dp)) {
            Btn("Play the loop", { state.setSongMode(false) }, on = !P.songMode, onColor = C.Teal)
            Btn("Play the song", { state.setSongMode(true) }, on = P.songMode, onColor = C.Pink)
        }
        Heading("Song", size = 18.sp)
        Note(
            if (len == 0) "Empty. Pick a loop below and tap bars to paint it in, or Rough it in."
            else "$len bars, about ${secs / 60}:${(secs % 60).toString().padStart(2, '0')} at ${P.bpm} BPM.",
            color = C.Yellow, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        T("PAINT WITH", size = 12.sp, color = C.Dim, spacing = .1.em)
        Wrap(Modifier.padding(top = 6.dp, bottom = 12.dp), gap = 6.dp) {
            for (i in 0 until Project.NSLOTS) {
                Btn(
                    Project.LETTERS[i].toString() + if (P.pats[i].used()) "" else "·", { state.brush = i },
                    on = state.brush == i, onColor = C.Letters[i], small = true,
                )
            }
            Btn("Rest", { state.brush = -1 }, on = state.brush == -1, onColor = C.Cream, small = true)
        }
        val playBar = if (state.playing && P.songMode) state.heardSong else -1
        val shown = minOf(Project.MAXSONG, maxOf(16, ((len + 8) / 8) * 8))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (r in 0 until shown / 8) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in 0 until 8) {
                        val i = r * 8 + c
                        val v = P.song[i]
                        val bg = if (v >= 0) C.Letters[v] else if (i < len) C.Card2 else C.Card
                        Box(
                            Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .sticker(bg, shadow = if (i == playBar) 4.dp else 0.dp, border = if (i == playBar) 3.dp else 2.dp, borderColor = if (i == playBar) C.Cream else if (v >= 0) C.Black else C.Empty)
                                .tagged("bar-$i")
                                .tapOrHold(i, { state.paintSong(i) }, { if (!state.locked) state.barMenu = i }),
                            contentAlignment = Alignment.Center,
                        ) {
                            HoldMenu(state.barMenu == i, "Bar ${i + 1}", { state.barMenu = -1 }, listOf(
                                Triple("Clear this bar", "barmenu-clear") { state.clearBar(i) },
                                Triple("Put a bar in here", "barmenu-insert") { state.insertBar(i) },
                                Triple("Take this bar out", "barmenu-delete") { state.deleteBar(i) },
                            ))
                            if (v >= 0) T(Project.LETTERS[v].toString(), size = 16.sp, color = C.Ink, weight = FontWeight.Black)
                            else Note("${i + 1}", color = C.Empty, size = 10.sp)
                        }
                    }
                }
            }
        }
        Wrap(Modifier.padding(top = 14.dp)) {
            Btn("Rough it in", { state.songFromLoops() }, small = true, color = C.Teal, tag = "rough")
            Btn("Share the song", { state.bounce(true, 1) }, small = true, color = C.Pink, enabled = len > 0, tag = "share-song")
            Btn("Clear the song", { state.clearSong() }, small = true, enabled = len > 0, tier = Tier.Danger)
        }
        Help(
            "Each square is one loop's worth. Tap a square to paint the loop you picked; tap it again to make it a rest. " +
                "Rough it in lays every loop that has something in it down twice, in order. ☰, then Messes, bounces the song to a WAV.",
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}
