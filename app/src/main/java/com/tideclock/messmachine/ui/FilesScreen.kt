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
                T("POUR IT OUT", size = 12.sp, color = C.Yellow, spacing = .1.em)
                Help("Makes a WAV of the music, then opens your share sheet. Stems are a zip with each sound on its own, all starting together; MIDI is the notes, one track per sound.", modifier = Modifier.padding(bottom = 8.dp))
                Wrap(Modifier) {
                    val L = Project.LETTERS[P.cur]
                    Btn("Loop $L once", { state.bounce(false, 1) }, small = true, color = C.Pink)
                    Btn("Loop $L ×4", { state.bounce(false, 4) }, small = true)
                    Btn("The song", { state.bounce(true, 1) }, small = true, enabled = P.songLen() > 0)
                }
                // build 7 of the overhaul: for a DAW. Each sound alone (lined up from zero), and the notes as MIDI
                T("FOR A DAW", size = 10.sp, color = C.Dim, spacing = .1.em, modifier = Modifier.padding(top = 10.dp))
                Wrap(Modifier.padding(top = 4.dp)) {
                    val L = Project.LETTERS[P.cur]
                    Btn("Stems: loop $L", { state.shareStems(false, 1) }, small = true, color = C.Teal, tag = "stems-loop")
                    Btn("Stems: the song", { state.shareStems(true, 1) }, small = true, enabled = P.songLen() > 0, tag = "stems-song")
                    Btn("MIDI: loop $L", { state.shareMidi(false) }, small = true, tag = "midi-loop")
                    Btn("MIDI: the song", { state.shareMidi(true) }, small = true, enabled = P.songLen() > 0, tag = "midi-song")
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
    androidx.compose.foundation.Canvas(modifier.size(width = 92.dp, height = 40.dp).tagged("mess-pic-${f.id}")) {
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
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Android 13 and up ask before an app shows notifications; without it the music still plays, the Stop notification just stays hidden
    val notif = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    ScrollPane(hPad = 14.dp, vPad = 0.dp) {
        // update from inside the app (Tyler, Nov 17)
        Card(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp)) {
            Column {
                T("BUILD ${state.updater.current}", size = 12.sp, color = C.Yellow, spacing = .1.em)
                if (state.updater.status.isNotEmpty()) Note(state.updater.status, modifier = Modifier.padding(top = 2.dp))
                Wrap(Modifier.padding(top = 6.dp)) {
                    if (state.updater.ready) Btn("Update to build ${state.updater.latest}", { state.installUpdate() }, small = true, color = C.Teal, enabled = !state.updater.busy, tag = "update-go")
                    else Btn("Check for updates", { state.updater.check(false) }, small = true, enabled = !state.updater.busy, tag = "update-check")
                }
            }
        }
        Card(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
            Column {
                T("VIEW SIZE", size = 12.sp, color = C.Yellow, spacing = .1.em)
                Help("Zoom out to fit more of the machine on the screen.", modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
                Wrap(Modifier.tagged("zoom-chips")) {
                    for (z in listOf(.7f, .8f, .9f, 1f, 1.1f, 1.2f)) {
                        Btn("${(z * 100).toInt()}%", { state.setViewSize(z) }, on = kotlin.math.abs(state.uiScale - z) < .01f, small = true, tag = "zoom-${(z * 100).toInt()}")
                    }
                }
            }
        }
        // build 5 of the overhaul: colour skins. The Look below is the button skin; any skin goes with any Look
        Card(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
            Column {
                T("SKIN", size = 12.sp, color = C.Yellow, spacing = .1.em)
                if (LocalHelp.current) T("The colours of the whole machine. Specimen is a lab notebook at night; Stage is black and loud, for dark rooms; Felt and Paper is for daylight; Rust is a machine left out in the rain.", size = 11.sp, color = C.Dim, weight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp))
                Wrap(Modifier.padding(top = 6.dp).tagged("skin-chips")) {
                    Skins.all.forEach { k ->
                        Btn(k.name, { state.pickSkin(k.id) }, on = state.skin == k.id, onColor = androidx.compose.ui.graphics.Color(k.pink), small = true, tag = "skin-${k.id}")
                    }
                }
            }
        }
        // the looks (Nov 18): Classic, or the Pro looks with icon tiles on printed rails, dark or light
        Card(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
            Column {
                T("LOOK (BUTTONS)", size = 12.sp, color = C.Yellow, spacing = .1.em)
                Wrap(Modifier.padding(top = 6.dp).tagged("look-chips")) {
                    Btn("Classic", { state.pickLook("classic") }, on = state.look == "classic", small = true, tag = "look-classic")
                    Btn("Pro dark", { state.pickLook("dark") }, on = state.look == "dark", onColor = C.Teal, small = true, tag = "look-dark")
                    Btn("Pro light", { state.pickLook("light") }, on = state.look == "light", onColor = C.Pink, small = true, tag = "look-light")
                }
                if (state.look != "classic") Btn(if (state.proWords) "Words under icons: on" else "Words under icons: off", { state.switchProWords() }, on = state.proWords, onColor = C.Teal, small = true, tag = "look-words",
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
        // build 107: where things live. New is the default; Classic puts back build 104's Menu, FX and side dock
        Card(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
            Column {
                T("LAYOUT", size = 12.sp, color = C.Yellow, spacing = .1.em)
                Wrap(Modifier.padding(top = 6.dp).tagged("layout-chips")) {
                    Btn("New", { state.pickLayout("new") }, on = state.layout == "new", onColor = C.Teal, small = true, tag = "layout-new")
                    Btn("New, row on the side", { state.pickLayout("side") }, on = state.layout == "side", onColor = C.Teal, small = true, tag = "layout-side")
                    Btn("Classic", { state.pickLayout("classic") }, on = state.layout == "classic", small = true, tag = "layout-classic")
                }
                Help("New: Edit and Repeat on the bar; the perform row swipes up. Classic: Menu, FX and the side dock.", modifier = Modifier.padding(top = 6.dp))
            }
        }
        // My Desk is a way of seeing the machine, so it lives here (Tyler, Nov 18): the Play tab shows the pads or your desk
        Card(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
            Column {
                T("PLAY LAYOUT", size = 12.sp, color = C.Yellow, spacing = .1.em)
                Wrap(Modifier.padding(top = 6.dp).tagged("layout-chips")) {
                    val desk = state.playView == com.tideclock.messmachine.Tab.Console
                    Btn("Pads", { state.pickPlayLayout(false) }, on = !desk, small = true, tag = "layout-pads")
                    Btn("My Desk", { state.pickPlayLayout(true) }, on = desk, onColor = C.Teal, small = true, tag = "layout-desk")
                }
            }
        }
        Card(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
            Column {
                T("PLAYING", size = 12.sp, color = C.Yellow, spacing = .1.em)
                Note("An empty pad's top corner records onto it with the mic: tap to start and stop, or hold while you make the sound.", modifier = Modifier.padding(top = 2.dp, bottom = 2.dp))
                Wrap(Modifier.padding(top = 6.dp).tagged("recmode-chips")) {
                    Btn("Tap start, tap stop", { state.switchRecordHold(false) }, on = !state.recordHold, onColor = C.Teal, small = true, tag = "set-rectap")
                    Btn("Hold to record", { state.switchRecordHold(true) }, on = state.recordHold, onColor = C.Teal, small = true, tag = "set-rechold")
                }
                Note("Leave the app while it plays and the music keeps going, with Stop in a notification.", modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
                Btn(if (state.bgPlay) "Keep playing when I leave: on" else "Keep playing when I leave: off", {
                    val on = !state.bgPlay
                    state.switchBgPlay(on)
                    if (on && android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        notif.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }, on = state.bgPlay, onColor = C.Teal, small = true, tag = "set-bgplay")
                Note("A short buzz when you hit a pad.", modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
                Btn(if (state.buzz) "Pad buzz: on" else "Pad buzz: off", { state.switchBuzz(!state.buzz) }, on = state.buzz, onColor = C.Teal, small = true, tag = "set-buzz")
                // Nov 18: the finer time grid lets live hits record exactly where they were played
                Btn(if (state.live.recExact) "Recording: exactly where you hit" else "Recording: snapped to 16ths", { state.switchRecExact() }, on = state.live.recExact, onColor = C.Teal, small = true, tag = "set-recexact",
                    modifier = Modifier.padding(top = 8.dp))
                // Nov 18: a quick flick up off a button opens its settings, like Menu then tap (try it; off by default)
                Btn(if (state.flickOn) "Flick up for settings: on" else "Flick up for settings: off", { state.switchFlick() }, on = state.flickOn, onColor = C.Teal, small = true, tag = "set-flick",
                    modifier = Modifier.padding(top = 8.dp))
                Note("Beat glow behind everything.", modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
                Wrap(Modifier.tagged("glow-chips")) {
                    for ((v, n) in listOf(0 to "Off", 1 to "Soft", 2 to "Full")) Btn(n, { state.setPulse(v) }, on = state.pulseLevel == v, small = true, tag = "set-pulse-$v")
                }
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
        // the tempo follows the slider as it moves; letting go makes it one undo step
        SliderRow("Tempo", bpm, 60f..180f, { "${it.roundToInt()} BPM" },
            { bpm = it; val v = it.roundToInt().coerceIn(60, 180); if (v != state.project.bpm) state.edit(commit = false) { this.bpm = v } },
            { state.setBpm(bpm.roundToInt()) })
        SliderRow("Swing", swing, 0f..100f, { "${it.roundToInt()}%" }, { swing = it }, { state.edit { this.swing = swing.roundToInt() } }, accent = C.Teal)
        Help("Swing pushes every other 16th late, like the site.", modifier = Modifier.padding(bottom = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Click", { state.setLive { click = !click } }, on = state.live.click, small = true)
            Btn("Hear steps on tap", { state.switchPlayOnTap(!state.playOnTap) }, on = state.playOnTap, small = true, onColor = C.Teal)
        }
    }
}
