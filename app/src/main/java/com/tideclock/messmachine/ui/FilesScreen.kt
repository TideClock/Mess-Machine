package com.tideclock.messmachine.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tideclock.messmachine.AppState
import com.tideclock.messmachine.engine.Project
import kotlin.math.roundToInt

@Composable
fun FilesScreen(state: AppState) {
    val P = state.project
    var name by remember { mutableStateOf(P.name) }
    var sureNew by remember { mutableStateOf(false) }
    var sureDel by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) state.importUri(uri) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        Card(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
            Column {
                T("This mess", size = 13.sp, color = C.Dim)
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(48) }, singleLine = true,
                    placeholder = { T("Untitled mess", color = C.Dim, weight = FontWeight.Medium) },
                    textStyle = TextStyle(color = C.Cream, fontFamily = Archivo, fontSize = 16.sp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.Pink, unfocusedBorderColor = C.Card2, cursorColor = C.Pink),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn(if (state.fileId == null) "Save" else "Save changes", { state.saveMess(name, false) }, color = C.Teal, small = true)
                    if (state.fileId != null) Btn("Save as new", { state.saveMess(name, true) }, small = true)
                    Btn(if (sureNew) "Really? Tap again" else "New mess", {
                        if (sureNew) { sureNew = false; state.newMess() } else { sureNew = true }
                    }, small = true, color = if (sureNew) C.Riot else C.Card2)
                }
            }
        }
        Card(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
            Column {
                T("Bounce to a WAV", size = 13.sp, color = C.Dim)
                T("Renders the music, then opens your share sheet so you can save it or send it anywhere.", size = 12.sp, color = C.Dim, weight = FontWeight.Medium, modifier = Modifier.padding(bottom = 8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val L = Project.LETTERS[P.cur]
                    Btn("Loop $L once", { state.bounce(false, 1) }, small = true, color = C.Pink)
                    Btn("Loop $L ×4", { state.bounce(false, 4) }, small = true)
                    Btn("The song", { state.bounce(true, 1) }, small = true, enabled = P.songLen() > 0)
                }
            }
        }
        Card(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
            Column {
                T("Move a mess between the site and your phone", size = 13.sp, color = C.Dim)
                T(
                    "The app reads and writes the same mess files as the Mess Machine on the site. Sounds you uploaded on the site stay there; those pads come in empty.",
                    size = 12.sp, color = C.Dim, weight = FontWeight.Medium, modifier = Modifier.padding(bottom = 8.dp),
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("Open a mess file", { pick.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, small = true, color = C.Teal)
                    Btn("Share this mess", { state.shareMess() }, small = true)
                }
            }
        }
        T("Saved on this phone", size = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
        if (state.files.isEmpty()) T("Nothing saved yet. Name the mess and hit Save.", size = 13.sp, color = C.Dim, weight = FontWeight.Medium)
        for (f in state.files) {
            Card(Modifier.fillMaxWidth().padding(bottom = 10.dp), color = if (f.id == state.fileId) C.Card2 else C.Card) {
                Column {
                    T(f.name, size = 16.sp, lines = 1)
                    T(
                        "${f.bpm} BPM · ${f.pads} pads · ${f.loops} loop${if (f.loops == 1) "" else "s"}" + (if (f.songBars > 0) " · ${f.songBars} bar song" else "") +
                            (if (f.at.length >= 10) " · ${f.at.substring(0, 10)}" else ""),
                        size = 12.sp, color = C.Dim, weight = FontWeight.Medium,
                    )
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Btn("Open", { state.openMess(f.id) }, small = true, color = C.Teal)
                        Btn(if (sureDel == f.id) "Really toss it?" else "Toss", {
                            if (sureDel == f.id) { sureDel = null; state.deleteMess(f.id) } else { sureDel = f.id }
                        }, small = true, color = if (sureDel == f.id) C.Riot else C.Card2)
                    }
                }
            }
        }
        T(" ", size = 30.sp)
    }
}

@Composable
fun TempoScreen(state: AppState) {
    val P = state.project
    var bpm by remember { mutableStateOf(P.bpm.toFloat()) }
    var swing by remember { mutableStateOf(P.swing.toFloat()) }
    @Suppress("UNUSED_VARIABLE") val lv = state.liveV
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("−", { state.setBpm(P.bpm - 1); bpm = state.project.bpm.toFloat() })
            T("${state.project.bpm}", size = 44.sp, family = Bungee)
            T("BPM", size = 14.sp, color = C.Dim)
            Btn("+", { state.setBpm(P.bpm + 1); bpm = state.project.bpm.toFloat() })
            Btn("Tap", { state.tapTempo(); bpm = state.project.bpm.toFloat() }, color = C.Teal)
        }
        SliderRow("Tempo", bpm, 60f..180f, { "${it.roundToInt()} BPM" }, { bpm = it }, { state.setBpm(bpm.roundToInt()) })
        SliderRow("Swing", swing, 0f..100f, { "${it.roundToInt()}%" }, { swing = it }, { state.edit { this.swing = swing.roundToInt() } }, accent = C.Teal)
        T("Swing pushes every other 16th late, like the site.", size = 12.sp, color = C.Dim, weight = FontWeight.Medium, modifier = Modifier.padding(bottom = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Click", { state.setLive { click = !click } }, on = state.live.click, small = true)
            Btn("Hear steps when you tap them", { state.setPlayOnTap(!state.playOnTap) }, on = state.playOnTap, small = true, onColor = C.Teal)
        }
    }
}
