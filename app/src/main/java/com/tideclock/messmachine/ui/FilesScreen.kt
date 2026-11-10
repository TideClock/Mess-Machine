package com.tideclock.messmachine.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.unit.em
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
    ScrollPane(hPad = 14.dp, vPad = 0.dp) {
        Card(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp)) {
            Column {
                T("THIS MESS", size = 12.sp, color = C.Yellow, spacing = .1.em)
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(48) }, singleLine = true,
                    placeholder = { T("Untitled mess", color = C.Dim, weight = FontWeight.Medium) },
                    textStyle = TextStyle(color = C.Cream, fontFamily = Archivo, fontSize = 16.sp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.Pink, unfocusedBorderColor = C.Card2, cursorColor = C.Pink),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                )
                Wrap(Modifier) {
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
                T("BOTTLE IT", size = 12.sp, color = C.Yellow, spacing = .1.em)
                Help("Renders the music, then opens your share sheet so you can save it or send it anywhere.", modifier = Modifier.padding(bottom = 8.dp))
                Wrap(Modifier) {
                    val L = Project.LETTERS[P.cur]
                    Btn("Loop $L once", { state.bounce(false, 1) }, small = true, color = C.Pink)
                    Btn("Loop $L ×4", { state.bounce(false, 4) }, small = true)
                    Btn("The song", { state.bounce(true, 1) }, small = true, enabled = P.songLen() > 0)
                }
            }
        }
        Card(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
            Column {
                T("SITE AND PHONE", size = 12.sp, color = C.Yellow, spacing = .1.em)
                T(
                    "The app reads and writes the same mess files as the Mess Machine on the site. Sounds you uploaded on the site stay there; those pads come in empty.",
                    size = 12.sp, color = C.Dim, weight = FontWeight.Medium, modifier = Modifier.padding(bottom = 8.dp),
                )
                Wrap(Modifier) {
                    Btn("Open a mess file", { pick.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, small = true, color = C.Teal)
                    Btn("Share this mess", { state.shareMess() }, small = true)
                }
            }
        }
        Heading("Lab logbook", size = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
        if (state.files.isEmpty()) T("Nothing saved yet. Name the mess and hit Save.", size = 13.sp, color = C.Dim, weight = FontWeight.Medium)
        for (f in state.files) {
            Card(Modifier.fillMaxWidth().padding(bottom = 10.dp), color = if (f.id == state.fileId) C.Card2 else C.Card) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MessPicture(f, Modifier.padding(end = 10.dp))
                        T(f.name, size = 16.sp, lines = 1, modifier = Modifier.weight(1f))
                    }
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

/** A saved mess's little picture: its pads (left) and its first loop's steps (right). */
@Composable
fun MessPicture(f: com.tideclock.messmachine.MessFile, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.size(width = 92.dp, height = 40.dp).testTag("mess-pic-${f.id}")) {
        val cell = size.height / 4
        for (k in 0 until 16) {
            val c = f.colors.getOrNull(k) ?: 0
            val x = (k % 4) * cell
            val y = (k / 4) * cell
            drawRect(if (c == 0) C.Card2 else padColor(androidx.compose.ui.graphics.Color(c)), topLeft = androidx.compose.ui.geometry.Offset(x + 1f, y + 1f), size = androidx.compose.ui.geometry.Size(cell - 2f, cell - 2f))
        }
        val gx = 4 * cell + 6f
        val gw = size.width - gx
        val rows = maxOf(1, f.grid.size)
        val sw = gw / 16
        val sh = size.height / rows
        drawRect(C.Black, topLeft = androidx.compose.ui.geometry.Offset(gx, 0f), size = androidx.compose.ui.geometry.Size(gw, size.height))
        f.grid.forEachIndexed { r, line ->
            for (j in line.indices) if (line[j] == '1') drawRect(C.Yellow, topLeft = androidx.compose.ui.geometry.Offset(gx + j * sw + .5f, r * sh + .5f), size = androidx.compose.ui.geometry.Size(maxOf(1f, sw - 1f), maxOf(1f, sh - 1f)))
        }
    }
}

/** ⚙ Settings: how the app looks and plays (split from the Messes sheet, which keeps files only). */
@Composable
fun SettingsScreen(state: AppState) {
    ScrollPane(hPad = 14.dp, vPad = 0.dp) {
        Card(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp)) {
            Column {
                T("VIEW SIZE", size = 12.sp, color = C.Yellow, spacing = .1.em)
                Help("Zoom out to fit more of the machine on the screen.", modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
                Wrap {
                    for (z in listOf(.7f, .8f, .9f, 1f, 1.1f, 1.2f)) {
                        Btn("${(z * 100).toInt()}%", { state.setViewSize(z) }, on = kotlin.math.abs(state.uiScale - z) < .01f, small = true, tag = "zoom-${(z * 100).toInt()}")
                    }
                }
            }
        }
        Card(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
            Column {
                T("PLAYING", size = 12.sp, color = C.Yellow, spacing = .1.em)
                Note("Empty pads record: an empty pad becomes a record button for the mic.", modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
                Wrap {
                    Btn(if (state.emptyRecord) "Empty pads record: on" else "Empty pads record: off", { state.switchEmptyRecord(!state.emptyRecord) }, on = state.emptyRecord, onColor = C.Riot, small = true, tag = "set-emptyrec")
                    Btn("Tap start, tap stop", { state.switchRecordHold(false) }, on = !state.recordHold, onColor = C.Teal, small = true, tag = "set-rectap")
                    Btn("Hold to record", { state.switchRecordHold(true) }, on = state.recordHold, onColor = C.Teal, small = true, tag = "set-rechold")
                }
                Note("A short buzz when you hit a pad.", modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
                Btn(if (state.buzz) "Pad buzz: on" else "Pad buzz: off", { state.switchBuzz(!state.buzz) }, on = state.buzz, onColor = C.Teal, small = true, tag = "set-buzz")
                Note("Beat glow behind everything.", modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
                Wrap {
                    for ((v, n) in listOf(0 to "Off", 1 to "Soft", 2 to "Full")) Btn(n, { state.setPulse(v) }, on = state.pulseLevel == v, small = true, tag = "set-pulse-$v")
                }
                Note("Pianos show only the scale's notes.", modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
                Btn(if (state.inKeyOnly) "In key only: on" else "In key only: off", { state.switchInKey(!state.inKeyOnly) }, on = state.inKeyOnly, onColor = C.Yellow, small = true, tag = "set-inkey")
            }
        }
        Card(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
            Column {
                T("HELP", size = 12.sp, color = C.Yellow, spacing = .1.em)
                Help("How-to text shows once on each page the first time you open it. ? in the top bar shows it again.", Modifier.padding(top = 2.dp, bottom = 8.dp))
                Btn(if (state.helpOn) "How-to text: always" else "How-to text: first time only", { state.switchHelp() }, on = state.helpOn, onColor = C.Teal, small = true, tag = "set-help")
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
    ScrollPane(hPad = 14.dp, vPad = 0.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("−", { state.setBpm(P.bpm - 1); bpm = state.project.bpm.toFloat() })
            T("${state.project.bpm}", size = 44.sp, family = Bungee)
            T("BPM", size = 14.sp, color = C.Dim)
            Btn("+", { state.setBpm(P.bpm + 1); bpm = state.project.bpm.toFloat() })
            Btn("Tap", { state.tapTempo(); bpm = state.project.bpm.toFloat() }, color = C.Teal)
        }
        SliderRow("Tempo", bpm, 60f..180f, { "${it.roundToInt()} BPM" }, { bpm = it }, { state.setBpm(bpm.roundToInt()) })
        SliderRow("Swing", swing, 0f..100f, { "${it.roundToInt()}%" }, { swing = it }, { state.edit { this.swing = swing.roundToInt() } }, accent = C.Teal)
        Help("Swing pushes every other 16th late, like the site.", modifier = Modifier.padding(bottom = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Click", { state.setLive { click = !click } }, on = state.live.click, small = true)
            Btn("Hear steps when you tap them", { state.switchPlayOnTap(!state.playOnTap) }, on = state.playOnTap, small = true, onColor = C.Teal)
        }
    }
}
