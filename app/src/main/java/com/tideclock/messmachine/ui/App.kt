package com.tideclock.messmachine.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tideclock.messmachine.AppState
import com.tideclock.messmachine.Overlay
import com.tideclock.messmachine.Tab
import com.tideclock.messmachine.engine.Project
import kotlinx.coroutines.delay

@Composable
fun MessMachineApp(state: AppState) {
    MaterialTheme(colorScheme = darkColorScheme(primary = C.Pink, secondary = C.Teal, background = C.Paper, surface = C.Card)) {
        LaunchedEffect(Unit) {
            while (true) {
                withFrameMillis { }
                state.tick()
            }
        }
        LaunchedEffect(state.toastId) {
            if (state.toast != null) { delay(3200); state.toast = null }
        }
        BackHandler(enabled = state.overlay != null) { state.overlay = null }
        @Suppress("UNUSED_VARIABLE") val v = state.version
        Box(Modifier.fillMaxSize().background(C.Paper).statusBarsPadding().navigationBarsPadding()) {
            Column(Modifier.fillMaxSize()) {
                Transport(state)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (state.tab) {
                        Tab.Pads -> PadsScreen(state)
                        Tab.Steps -> StepsScreen(state)
                        Tab.Roll -> RollScreen(state)
                        Tab.Song -> SongScreen(state)
                        Tab.Sounds -> SoundsScreen(state, null)
                    }
                }
                Tabs(state)
            }
            when (val o = state.overlay) {
                is Overlay.Picker -> Sheet(state, "Pick a sound for pad ${o.pad + 1}") { SoundsScreen(state, o.pad) }
                is Overlay.Doctor -> Sheet(state, "The doctor's table") { DoctorScreen(state, o.key) }
                Overlay.Files -> Sheet(state, "Messes") { FilesScreen(state) }
                Overlay.Tempo -> Sheet(state, "Tempo and feel") { TempoScreen(state) }
                null -> {}
            }
            state.toast?.let { msg ->
                Box(Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 84.dp)) {
                    Box(Modifier.sticker(C.Yellow, radius = 12.dp).clickable { state.toast = null }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        T(msg, color = C.Ink, size = 14.sp)
                    }
                }
            }
            state.busy?.let { msg ->
                Box(Modifier.fillMaxSize().background(C.Black.copy(alpha = .6f)).clickable(enabled = true) { }, contentAlignment = Alignment.Center) {
                    Box(Modifier.sticker(C.Teal).padding(20.dp)) { T(msg, color = C.Ink, size = 18.sp) }
                }
            }
        }
    }
}

/** A full-height panel over the machine, for picking, editing and files. */
@Composable
fun Sheet(state: AppState, title: String, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(C.Black.copy(alpha = .55f)).clickable(enabled = true) { }) {
        Column(Modifier.fillMaxSize().padding(top = 24.dp).background(C.Paper)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                T(title, size = 20.sp, weight = FontWeight.Black, modifier = Modifier.weight(1f))
                Btn("Done", { state.overlay = null }, color = C.Pink, small = true)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }
}

@Composable
fun Transport(state: AppState) {
    val P = state.project
    @Suppress("UNUSED_VARIABLE") val lv = state.liveV
    Column(Modifier.fillMaxWidth().background(C.Ink).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn(if (state.playing) "❚❚ Pause" else "▶ Play", { state.togglePlay() }, color = C.Teal, on = state.playing, onColor = C.Teal)
            Btn("■", { state.stop() })
            Btn("● Rec", { state.setLive { rec = !rec } }, on = state.live.rec, onColor = C.Riot)
            Btn("${P.bpm} BPM", { state.overlay = Overlay.Tempo })
            Spacer(Modifier.weight(1f))
            Btn("☰", { state.refreshFiles(); state.overlay = Overlay.Files })
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            T(position(state), size = 13.sp, color = C.Yellow, modifier = Modifier.width(92.dp), lines = 1)
            Btn("Click", { state.setLive { click = !click } }, on = state.live.click, small = true)
            Btn("Repeat", { state.setLive { nr = !nr } }, on = state.live.nr, onColor = C.Pink, small = true)
            Select(
                when (state.live.nrRate) { 2.0 -> "1/8"; 0.5 -> "1/32"; else -> "1/16" },
                listOf(2.0 to "1/8", 1.0 to "1/16", 0.5 to "1/32"), { r -> state.setLive { nrRate = r } },
            )
            Select(
                arpName(state.live.arp),
                listOf("rep", "up", "down", "updown", "rand").map { it to arpName(it) },
                { a -> state.setLive { arp = a; if (a != "rep") nr = true } },
            )
            Btn("↶", { state.undo() }, enabled = state.canUndo, small = true)
            Btn("↷", { state.redo() }, enabled = state.canRedo, small = true)
        }
    }
}

fun arpName(a: String) = when (a) { "up" -> "Arp up"; "down" -> "Arp down"; "updown" -> "Arp up-down"; "rand" -> "Arp random"; else -> "Repeat notes" }

fun position(state: AppState): String {
    val s = state.heardStep
    if (!state.playing || s < 0) {
        val p = state.project
        return if (p.songMode) "Song · ${p.songLen()} bars" else "Loop ${Project.LETTERS[p.cur]}"
    }
    val pat = state.heardPat
    val pre = if (state.project.songMode) "${state.heardSong + 1}·" else ""
    return "$pre${if (pat >= 0) Project.LETTERS[pat].toString() else "rest"} ${s / 16 + 1}.${s % 16 / 4 + 1}.${s % 4 + 1}"
}

@Composable
fun Tabs(state: AppState) {
    Row(Modifier.fillMaxWidth().background(C.Ink).padding(horizontal = 6.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (t in Tab.entries) {
            Btn(t.label, { state.tab = t }, modifier = Modifier.weight(1f), on = state.tab == t, onColor = C.Pink, small = true)
        }
    }
}
