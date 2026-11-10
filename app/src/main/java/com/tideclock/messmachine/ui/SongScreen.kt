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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
            Btn("Play the loop", { state.setSongMode(false) }, on = !P.songMode, onColor = C.Teal)
            Btn("Play the song", { state.setSongMode(true) }, on = P.songMode, onColor = C.Pink)
        }
        T(
            if (len == 0) "The song lane is empty. Pick a loop below and tap bars to paint it in."
            else "$len bars, about ${secs / 60}:${(secs % 60).toString().padStart(2, '0')} at ${P.bpm} BPM.",
            size = 13.sp, color = C.Yellow, modifier = Modifier.padding(bottom = 10.dp),
        )
        T("Paint with", size = 12.sp, color = C.Dim)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                                .sticker(bg, radius = 8.dp, shadow = 2.dp, border = if (i == playBar) 3.dp else 2.dp, borderColor = if (i == playBar) C.Cream else C.Black)
                                .clickable { state.paintSong(i) },
                            contentAlignment = Alignment.Center,
                        ) {
                            T(if (v >= 0) Project.LETTERS[v].toString() else "${i + 1}", size = if (v >= 0) 16.sp else 10.sp,
                                color = if (v >= 0) C.Ink else C.Dim, weight = FontWeight.Black)
                        }
                    }
                }
            }
        }
        Row(Modifier.padding(top = 14.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Chain my loops", { state.songFromLoops() }, small = true, color = C.Teal)
            Btn("Clear the song", { state.clearSong() }, small = true, enabled = len > 0)
        }
        T(
            "Each square is one loop's worth. Tap a square to paint the loop you picked; tap it again to make it a rest. " +
                "Chain my loops lays every loop that has something in it end to end. The ☰ menu bounces the song to a WAV.",
            size = 12.sp, color = C.Dim, weight = FontWeight.Medium, modifier = Modifier.padding(top = 12.dp),
        )
    }
}
