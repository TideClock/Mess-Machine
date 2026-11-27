package com.tideclock.messmachine.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import com.tideclock.messmachine.SWIPE_ACTIONS
import com.tideclock.messmachine.engine.Project
import kotlin.math.roundToInt

@Composable
fun FilesScreen(state: AppState) {
    val P = state.project
    var name by remember { mutableStateOf(P.name) }
    var sureNew by remember { mutableStateOf(false) }
    var sureDel by remember { mutableStateOf<String?>(null) }
    var sureOff by remember { mutableStateOf<String?>(null) }
    val pick = rememberPicker(false) { uris -> state.importUri(uris.first()) }
    ScrollPane(hPad = 14.dp, vPad = 0.dp) {
        // accounts: only on a build with a server (state.store is null otherwise, and the sheet is as it was)
        if (state.store != null) AccountRow(state)
        Card(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp)) {
            Column {
                T("THIS MESS", size = 12.sp, color = C.Yellow, spacing = .1.em)
                // accounts: who else has this mess open right now (only in a mess edited together; never on the pads)
                if (state.store != null) HereChips(state)
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(48) }, singleLine = true,
                    placeholder = { T("Untitled mess", color = C.Dim, weight = FontWeight.Medium) },
                    textStyle = TextStyle(color = C.Cream, fontFamily = Archivo, fontSize = 16.sp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.Pink, unfocusedBorderColor = C.Card2, cursorColor = C.Pink),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                )
                Wrap(Modifier) {
                    Btn(if (state.fileId == null) "Save" else "Save changes", { state.saveMess(name, false) }, color = C.Teal, small = true, tag = "mess-save")
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
                Wrap(Modifier) {
                    val L = Project.LETTERS[P.cur]
                    Btn("Loop $L once", { state.bounce(false, 1) }, small = true, color = C.Pink)
                    Btn("Loop $L ×4", { state.bounce(false, 4) }, small = true)
                    Btn("The song", { state.bounce(true, 1) }, small = true, enabled = P.songLen() > 0)
                }
                // build 7 of the overhaul: for a DAW. Each sound alone (lined up from zero), and the notes as MIDI
                T("FOR A DAW", size = 10.sp, color = C.Dim, spacing = .1.em, modifier = Modifier.padding(top = 10.dp))
                Wrap(Modifier.padding(top = 4.dp).tagged("daw-chips")) {
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
                androidx.compose.foundation.layout.Spacer(Modifier.size(1.dp, 6.dp))
                Wrap(Modifier) {
                    Btn("Open a mess file", { pick(arrayOf("application/json", "text/plain", "application/octet-stream")) }, small = true, color = C.Teal)
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
                    if (state.store != null) CloudMark(state, f)
                    val rowButtons: @Composable () -> Unit = {
                        Btn("Open", { state.openMess(f.id) }, small = true, color = C.Teal)
                        Btn(if (sureDel == f.id) "Really toss it?" else "Toss", {
                            if (sureDel == f.id) { sureDel = null; state.deleteMess(f.id) } else { sureDel = f.id }
                        }, small = true, color = if (sureDel == f.id) C.Riot else C.Card2)
                    }
                    if (state.store == null) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { rowButtons() }
                    else Wrap(Modifier.padding(top = 8.dp)) {
                        rowButtons()
                        CloudRowButton(state, f, sureOff == f.id) { sureOff = if (it) f.id else null }
                    }
                }
            }
        }
        if (state.store != null) OnlineOnly(state)
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

/**
 * ⚙ Settings: how the app looks and plays (split from the Messes sheet, which keeps files only).
 * Nov 24 (Tyler's video: "way too much empty space", "these can be converted into the switch mode"): two groups, Look
 * and Playing, side by side when the screen is wide; every on/off is a switch on one short line; Build sits in the header.
 */
@Composable
fun SettingsScreen(state: AppState) {
    // Nov 23 (Tyler: "tapping Click isn't working most of the time"): the live settings are not Compose state; reading
    // liveV makes this redraw when one changes (Click toggled, but its key only showed it on the next redraw)
    @Suppress("UNUSED_VARIABLE") val lvBump = state.liveV
    val wide = screenWidthDp() >= 560
    ScrollPane(hPad = 14.dp, vPad = 0.dp) {
        // update from inside the app (Tyler, Nov 17), as one line
        Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                T("BUILD ${state.updater.current}", size = 12.sp, color = C.Yellow, spacing = .1.em)
                if (state.updater.status.isNotEmpty()) Note(state.updater.status)
            }
            if (state.updater.ready) Btn("Update to build ${state.updater.latest}", { state.installUpdate() }, small = true, color = C.Teal, enabled = !state.updater.busy, tag = "update-go")
            else Btn("Check for updates", { state.updater.check(false) }, small = true, enabled = !state.updater.busy, tag = "update-check")
        }
        if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) { LookSettings(state) }
            Column(Modifier.weight(1f)) { PlaySettings(state); HotkeySettings(state) }
        } else { LookSettings(state); PlaySettings(state); HotkeySettings(state) }
        T(" ", size = 30.sp)
    }
}

/** One group of settings: a heading and its lines, in a box only as tall as they are. */
@Composable
fun SetGroup(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column {
            T(title, size = 12.sp, color = C.Yellow, spacing = .1.em, modifier = Modifier.padding(bottom = 2.dp))
            content()
        }
    }
}

/** A small label over a row of choices inside a group. */
@Composable
fun SetLabel(text: String) = T(text, size = 11.sp, color = C.Dim, spacing = .1.em, lines = 1, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))

/** An on/off setting as one line: its name, and a switch. The whole line is the switch. */
@Composable
fun SwitchLine(label: String, on: Boolean, onToggle: () -> Unit, tag: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable { onToggle() }.tagged(tag)
            .semantics { role = androidx.compose.ui.semantics.Role.Switch; stateDescription = if (on) "on" else "off" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T(label, size = 14.sp, color = C.Cream, modifier = Modifier.weight(1f))
        Box(Modifier.size(width = 44.dp, height = 24.dp).background(if (on) C.Teal else C.Well, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .border(2.dp, if (on) C.Teal else C.Dim, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(3.dp),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.size(18.dp).background(if (on) C.Ink else C.Dim, androidx.compose.foundation.shape.CircleShape))
        }
    }
}

@Composable
fun LookSettings(state: AppState) {
    SetGroup("LOOK") {
        SetLabel("VIEW SIZE")
        Wrap(Modifier.tagged("zoom-chips"), gap = 6.dp) {
            for (z in listOf(.7f, .8f, .9f, 1f, 1.1f, 1.2f)) {
                Btn("${(z * 100).toInt()}%", { state.setViewSize(z) }, on = kotlin.math.abs(state.uiScale - z) < .01f, small = true, tight = true, tag = "zoom-${(z * 100).toInt()}")
            }
        }
        // build 5 of the overhaul: colour skins. The Look below is the button skin; any skin goes with any Look
        SetLabel("SKIN")
        Wrap(Modifier.tagged("skin-chips"), gap = 6.dp) {
            Skins.all.forEach { k ->
                Btn(k.name, { state.pickSkin(k.id) }, on = state.skin == k.id, onColor = androidx.compose.ui.graphics.Color(k.pink), small = true, tight = true, tag = "skin-${k.id}")
            }
        }
        // the looks (Nov 18): Classic, or the Pro looks with icon tiles on printed rails, dark or light
        SetLabel("BUTTONS")
        Wrap(Modifier.tagged("look-chips"), gap = 6.dp) {
            Btn("Panel", { state.pickLook("panel") }, on = state.look == "panel", onColor = C.Yellow, small = true, tight = true, tag = "look-panel")
            Btn("Classic", { state.pickLook("classic") }, on = state.look == "classic", small = true, tight = true, tag = "look-classic")
            Btn("Pro dark", { state.pickLook("dark") }, on = state.look == "dark", onColor = C.Teal, small = true, tight = true, tag = "look-dark")
            Btn("Pro light", { state.pickLook("light") }, on = state.look == "light", onColor = C.Pink, small = true, tight = true, tag = "look-light")
        }
        if (state.look != "classic") SwitchLine("Words under icons", state.proWords, { state.switchProWords() }, "look-words")
        if (state.look == "panel") SwitchLine("Tabs in a slot (off: keys)", state.tabSlot, { state.switchTabSlot() }, "look-tabslot")
        if (state.look == "panel" && state.proWords) SwitchLine("Words on plates (off: stamped)", state.wordPlate, { state.switchWordPlate() }, "look-wordplate")
        // Nov 21: a quarter bigger everywhere, for playing at 70 to 80 percent view size
        SwitchLine("Large keys", state.largeKeys, { state.switchLargeKeys(!state.largeKeys) }, "look-large")
        // build 107: where things live. New is the default; Classic puts back build 104's Menu, FX and side dock
        SetLabel("LAYOUT")
        Wrap(Modifier.tagged("layout-chips"), gap = 6.dp) {
            Btn("New", { state.pickLayout("new") }, on = state.layout == "new", onColor = C.Teal, small = true, tight = true, tag = "layout-new")
            Btn("Row on the side", { state.pickLayout("side") }, on = state.layout == "side", onColor = C.Teal, small = true, tight = true, tag = "layout-side")
            Btn("Classic", { state.pickLayout("classic") }, on = state.layout == "classic", small = true, tight = true, tag = "layout-classic")
        }
        // My Desk is a way of seeing the machine, so it lives here (Tyler, Nov 18): the Play tab shows the pads or your desk
        SetLabel("PLAY TAB SHOWS")
        Wrap(Modifier.tagged("playlayout-chips"), gap = 6.dp) {
            val desk = state.playView == com.tideclock.messmachine.Tab.Console
            Btn("Pads", { state.pickPlayLayout(false) }, on = !desk, small = true, tight = true, tag = "layout-pads")
            Btn("My Desk", { state.pickPlayLayout(true) }, on = desk, onColor = C.Teal, small = true, tight = true, tag = "layout-desk")
        }
    }
}

@Composable
fun PlaySettings(state: AppState) {
    // Android 13 and up ask before an app shows notifications; without it the music still plays, the Stop notification just stays hidden
    val notif = rememberAsk(Perm.NOTIFICATIONS) { }
    SetGroup("PLAYING") {
        SwitchLine("Keep playing when I leave", state.bgPlay, {
            val on = !state.bgPlay
            state.switchBgPlay(on)
            if (on && !notif.granted) notif.ask()
        }, "set-bgplay")
        SwitchLine("Pad buzz", state.buzz, { state.switchBuzz(!state.buzz) }, "set-buzz")
        // Nov 18: the finer time grid lets live hits record exactly where they were played
        SwitchLine("Record exactly where I hit (off: snap to 16ths)", state.live.recExact, { state.switchRecExact() }, "set-recexact")
        // Nov 24 (Tyler's video): trim the quiet off both ends of a new recording
        SwitchLine("Auto trim new recordings", state.autoTrim, { state.switchAutoTrim(!state.autoTrim) }, "set-autotrim")
        // Nov 26 (Tyler): every new sound on a pad re-pitched (one note to C, a key into the mess's key), key ones following the key
        SwitchLine("Auto-pitch new sounds", state.autoPitch, { state.switchAutoPitch(!state.autoPitch) }, "set-autopitch")
        // Nov 18 / 21: a quick flick up off a button opens its settings
        SwitchLine("Flick up off a button for its settings", state.flickOn, { state.switchFlick() }, "set-flick")
    }
    SetGroup("SOUND AND SPEED") {
        // Nov 21 (lag): the screen at 60 a second leaves more time for the sound
        SwitchLine("Smooth screen (off: 60 a second, more time for sound)", state.smoothScreen, { state.switchSmoothScreen(!state.smoothScreen) }, "set-smooth")
        SwitchLine("Steady buffer (off: tight, lower latency)", state.steadyBuffer, { state.switchSteadyBuffer(!state.steadyBuffer) }, "set-buffer")
        // Nov 21 (L4): AAudio is Android's fast path; the older one is there for a phone where it misbehaves
        SwitchLine("Fast sound path, AAudio (now: ${state.audio.path})", state.aaudio, { state.switchAAudio(!state.aaudio) }, "set-aaudio")
        SetLabel("BEAT GLOW")
        Wrap(Modifier.tagged("glow-chips"), gap = 6.dp) {
            for ((v, n) in listOf(0 to "Off", 1 to "Soft", 2 to "Full")) Btn(n, { state.setPulse(v) }, on = state.pulseLevel == v, small = true, tight = true, tag = "set-pulse-$v")
        }
    }
}

/** Nov 21 video: HotKeys, what a quick swipe off a pad does in each direction. */
@Composable
fun HotkeySettings(state: AppState) {
    SetGroup("HOTKEYS", Modifier.tagged("hotkeys")) {
        SwitchLine("Pad swipes", state.padFlick, { state.switchPadFlick() }, "set-padflick")
        for ((dir, word) in listOf("up" to "Up", "down" to "Down", "left" to "Left", "right" to "Right")) {
            val now = SWIPE_ACTIONS.firstOrNull { it.first == state.swipe[dir] }?.second ?: "Nothing"
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                T(word.uppercase(), size = 12.sp, spacing = .08.em, color = if (state.padFlick) C.Cream else C.Dim, modifier = Modifier.width(64.dp))
                Select(now, SWIPE_ACTIONS, { state.setSwipe(dir, it) }, tag = "swipe-$dir")
            }
        }
    }
}

/**
 * Tempo + feel as a drop-down under the tempo readout (Nov 24, Tyler's video: "unnecessary to load out into its own
 * whole screen"). The page stays in view; a tap outside or the screw puts it away.
 */
@Composable
fun TempoDrop(state: AppState) {
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { state.overlay = null } }) {
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 60.dp, start = 8.dp, end = 8.dp).widthIn(max = 480.dp).fillMaxWidth()
                .heightIn(max = 520.dp).trayFrame().pointerInput(Unit) { detectTapGestures { } }.tagged("sheet").padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("TEMPO + FEEL", size = 12.sp, color = C.Yellow, spacing = .1.em, modifier = Modifier.weight(1f))
                CloseScrew({ state.overlay = null }, tag = "done", small = true)
            }
            TempoScreen(state)
        }
    }
}

@Composable
fun TempoScreen(state: AppState) {
    val P = state.project
    var bpm by remember { mutableStateOf(P.bpm.toFloat()) }
    var swing by remember { mutableStateOf(P.swing.toFloat()) }
    @Suppress("UNUSED_VARIABLE") val lv = state.liveV
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 6.dp)) {
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
        // the last build of the overhaul: the master bus, the last thing every sound goes through
        var squash by remember { mutableStateOf(P.squash.toFloat()) }
        var level by remember { mutableStateOf(P.level.toFloat()) }
        T("THE MASTER BUS", size = 12.sp, color = C.Yellow, spacing = .1.em, modifier = Modifier.padding(top = 4.dp).tagged("master-bus"))
        SliderRow("Squash", squash, 0f..1f, { v -> when { v < .05f -> "off"; v < .35f -> "light"; v < .65f -> "as it was"; v < .85f -> "pressed"; else -> "flattened" } },
            { squash = it; state.setMaster(squash = it.toDouble(), commit = false) }, { state.setMaster(squash = squash.toDouble()) }, accent = C.Pink)
        SliderRow("Level", level, 0f..1.6f, { "${(it * 100).roundToInt()}%" },
            { level = it; state.setMaster(level = it.toDouble(), commit = false) }, { state.setMaster(level = level.toDouble()) }, accent = C.Teal)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 4.dp)) {
            Btn("As it was", { squash = .5f; level = 1f; state.setMaster(.5, 1.0) }, small = true, tag = "master-reset")
        }
        Wrap(gap = 6.dp) {
            Btn("Click", { state.switchClick() }, on = state.live.click, onColor = C.Yellow, small = true, tight = true, icon = "Click", tag = "tempo-click")
            Btn(if (state.countInBars == 0) "Count-in: off" else "Count-in: ${state.countInBars} bar${if (state.countInBars > 1) "s" else ""}", { state.cycleCountIn() }, small = true, tight = true, on = state.countInBars > 0, onColor = C.Yellow, icon = "Count in", tag = "tempo-countin")
            Btn("Hear steps on tap", { state.switchPlayOnTap(!state.playOnTap) }, on = state.playOnTap, small = true, onColor = C.Teal)
        }
    }
}
