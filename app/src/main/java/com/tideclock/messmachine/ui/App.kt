package com.tideclock.messmachine.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.composed
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.em
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tideclock.messmachine.AppState
import com.tideclock.messmachine.Overlay
import com.tideclock.messmachine.Tab
import com.tideclock.messmachine.engine.Project
import kotlinx.coroutines.delay

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun MessMachineApp(state: AppState, ticker: Boolean = true) {
    MaterialTheme(colorScheme = darkColorScheme(primary = C.Pink, secondary = C.Teal, background = C.Paper, surface = C.Card)) {
        // build 8 of the overhaul: on a light skin the phone's status and navigation bars switch to dark icons
        val rootView = androidx.compose.ui.platform.LocalView.current
        val lightSkin = C.skin.light
        androidx.compose.runtime.SideEffect {
            val w = (rootView.context as? android.app.Activity)?.window
            if (w != null) androidx.core.view.WindowCompat.getInsetsController(w, rootView).apply {
                isAppearanceLightStatusBars = lightSkin; isAppearanceLightNavigationBars = lightSkin
            }
        }
        // Nov 21 (lag): 60 frames a second unless Smooth screen is on (Settings, Playing)
        LaunchedEffect(state.smoothScreen) {
            val act = rootView.context as? android.app.Activity
            if (act != null) try { setScreenRate(act, state.smoothScreen) } catch (_: Throwable) {}
        }
        if (ticker) LaunchedEffect(Unit) {
            while (true) {
                val t = withFrameNanos { it }
                state.tick(t)
            }
        }
        LaunchedEffect(state.tab) { state.songOpen = false }
        LaunchedEffect(state.toastId) {
            if (state.toast != null) { delay(3200); state.toast = null }
        }
        // Back closes the tray, the step sheet or a pad's menu before it ever leaves the app (a sheet over them wins: it is
        // registered after this one)
        // Nov 21 (note: things without a clear exit): every panel that opens, Back closes, top one first
        BackHandler(enabled = state.tray != null || state.stepSheet != null || state.padMenu >= 0 || state.performOpen || state.menuArmed || state.midiMapOn
                || state.keysBarOpen || state.loopStripOpen || state.movesOpen || state.stepStrip >= 0) {
            when {
                state.menuArmed -> state.menuArmed = false
                state.tray != null -> state.tray = null
                state.padMenu >= 0 -> state.padMenu = -1
                state.stepSheet != null -> state.stepSheet = null
                state.stepStrip >= 0 -> state.stepStrip = -1
                state.movesOpen -> state.movesOpen = false
                state.midiMapOn -> state.switchMidiMap(false)
                state.keysBarOpen -> state.switchKeysBar(false)
                state.loopStripOpen -> state.loopStripOpen = false
                else -> state.switchPerform(false)
            }
        }
        BackHandler(enabled = state.overlay != null) { state.overlay = null }
        // Lock: back does nothing but remind you how to unlock; the screen is pinned if you chose that
        BackHandler(enabled = state.locked) { state.say(if (state.layout == "classic") "Locked. Hold the lock on the dock for a second to unlock." else "Locked. Hold the lock in the perform row for a second to unlock.") }
        val act = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
        var pinned by remember { mutableStateOf(false) }
        LaunchedEffect(state.locked) {
            try {
                if (state.locked && state.lockPin && !pinned) { act?.startLockTask(); pinned = true }
                else if (!state.locked && pinned) { act?.stopLockTask(); pinned = false }
            } catch (_: Exception) { pinned = false }
        }
        // a pad asked to record: ask for the mic, then record onto it
        val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            val pad = state.askMicFor
            state.askMicFor = -1
            if (ok && pad == com.tideclock.messmachine.MIC_FOR_NOTE) state.bugTap()
            else if (ok && pad == com.tideclock.messmachine.MIC_FOR_MOUTH) state.switchMouth(true)
            else if (ok && pad >= 0) state.recordToPad(pad) else if (!ok) state.say("The mic needs permission. You can allow it in the phone's settings for Mess Machine.")
        }
        LaunchedEffect(state.askMicFor) { if (state.askMicFor != -1) micPerm.launch(android.Manifest.permission.RECORD_AUDIO) }
        @Suppress("UNUSED_VARIABLE") val v = state.version
        val dn = LocalDensity.current
        UiLook.widthDp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp / state.uiScale
        CompositionLocalProvider(LocalDensity provides Density(dn.density * state.uiScale, dn.fontScale), LocalLocked provides state.locked, LocalHelp provides state.helpOn) {
        // test tags show up as view ids, so the phone test on the emulator can find things by name
        Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }.background(C.Paper).grit().safeDrawingPadding().watchTouches(state)) {
            PulseBackdrop(state)
            Column(Modifier.fillMaxSize()) {
                Transport(state)
                // Nov 22 review: the strip shows on every page, Perform included (Click, Count, - + 2x live only here)
                if (state.loopStripOpen) LoopStrip(state)
                if (state.micOn) RecordingBanner(state)
                if (state.updater.ready && !state.updater.later || state.updater.busy) UpdateBar(state)
                if (state.cpuAsk) CpuBanner(state)
                Row(Modifier.weight(1f).fillMaxWidth()) {
                if (state.layout != "new") Dock(state)
                Box(Modifier.weight(1f).fillMaxHeight().tagged("screen-${state.tab.label}")) {
                    // pages change with a short fade instead of a hard cut (note #5)
                    Crossfade(targetState = state.tab, animationSpec = tween(150), label = "tab") { t ->
                        when (t) {
                            Tab.Pads -> PadsScreen(state)
                            Tab.Console -> ConsoleScreen(state)
                            Tab.Synth -> SynthScreen(state)
                            Tab.Steps -> StepsScreen(state)
                            Tab.Roll -> RollScreen(state)
                            Tab.Song -> SongScreen(state)
                            Tab.Perform -> PerformScreen(state)
                            Tab.Sounds -> SoundsScreen(state, null)
                        }
                    }
                }
                }
                // build 107: the perform row swipes up from the bottom bar, on every page
                if (state.layout == "new") PerformRow(state)
                // Edit, Hold and chords stay under whatever page you play on (build 84)
                if (state.tab == Tab.Pads || state.tab == Tab.Console || state.tab == Tab.Synth) BottomDock(state)
                Tabs(state)
            }
            // sheets slide up a little and fade, instead of snapping in
            AnimatedContent(
                targetState = state.overlay,
                transitionSpec = { (slideInVertically(tween(150)) { it / 12 } + fadeIn(tween(150))) togetherWith fadeOut(tween(120)) },
                label = "sheet",
            ) { o ->
                when (o) {
                    is Overlay.Picker -> Sheet(state, "Pick a sound for pad ${o.pad + 1}") { SoundsScreen(state, o.pad) }
                    is Overlay.Doctor -> Sheet(state, "Sample") { DoctorScreen(state, o.key) }
                    is Overlay.Xy -> Sheet(state, "XY for pad ${o.pad + 1}") { XyEditor(state, o.pad) }
                    Overlay.Files -> Sheet(state, "Messes") { FilesScreen(state) }
                    Overlay.Settings -> Sheet(state, "Settings") { SettingsScreen(state) }
                    Overlay.Tempo -> Sheet(state, "Tempo + feel") { TempoScreen(state) }
                    Overlay.Notes -> Sheet(state, "Notes for Claude") { NotesScreen(state) }
                    Overlay.Start -> Sheet(state, "Welcome to the mess") { StartScreen(state) }
                    Overlay.AudioCheck -> Sheet(state, "Latency") { AudioCheckScreen(state) }
                    Overlay.Packs -> Sheet(state, "Packs") { PacksScreen(state) }
                    Overlay.Learn -> Sheet(state, "Learn") { LearnScreen(state) }
                    Overlay.Mouth -> Sheet(state, "Mouth control") { MouthScreen(state) }
                    Overlay.Account -> Sheet(state, "Account") { AccountScreen(state) }
                    is Overlay.Share -> Sheet(state, "Share") { ShareScreen(state, o.cloudId) }
                    is Overlay.Vault -> Sheet(state, "The Vault") { VaultScreen(state, o.key) }
                    null -> Box(Modifier) {}
                }
            }
            SphereOverlay(state)
            // the MIDI map floats under the top bar, over the page's own header, so the pads, keys and knobs you aim at stay in view and live
            // in the new layouts the map sits low, in the space under the pads, so it never covers the pads you are teaching
            // (at the top it covered the first row: a tap on pad 1 landed on Run)
            if (state.midiMapOn) MidiMapPanel(state, if (state.layout == "classic") Modifier.align(Alignment.TopCenter).padding(start = 22.dp, end = 12.dp, top = 56.dp)
                else Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = 196.dp))
            // a lesson sits over everything, sheets included; its ring lets taps through
            TutorOverlay(state)
            SpeedGuideOverlay(state)
            // Menu, then tap: a clear layer catches the next tap and opens that control's settings
            MenuLayer(state)
            state.tray?.let { SettingsTray(state, it, Modifier.align(Alignment.BottomCenter)) }
            // What the FAQ goes over everything: while it is on, a tap explains instead of pressing
            FaqOverlay(state)
            if (state.quickText != null) QuickNoteBar(state, Modifier.align(Alignment.BottomStart))
            state.toast?.let { msg ->
                // above the tabs, and above the bottom dock where it shows, so a message never covers Menu or the dock's Fold
                val dockUp = state.tab == Tab.Pads || state.tab == Tab.Console || state.tab == Tab.Synth
                Box(Modifier.align(Alignment.BottomCenter).padding(start = 76.dp, end = 16.dp, bottom = if (dockUp) 136.dp else 84.dp)) {
                    Row(Modifier.sticker(C.Yellow, shadow = 5.dp).clickable { state.toast = null }.tagged("toast").padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        T(msg, color = C.Ink, size = 14.sp, modifier = Modifier.weight(1f, fill = false).padding(vertical = 3.dp))
                        // after a clear: Undo right here, so the clear buttons don't need to ask first
                        if (state.toastUndo) Btn("Undo", { state.undo(); state.toast = null }, small = true, color = C.Ink, tag = "toast-undo", modifier = Modifier.padding(start = 10.dp))
                    }
                }
            }
            // locked: a yellow edge round the whole screen, so it's clear why tabs and menus don't open
            if (state.locked) Box(Modifier.fillMaxSize().border(6.dp, C.Yellow).tagged("lock-edge"))
            state.busy?.let { msg ->
                Box(Modifier.fillMaxSize().background(C.Black.copy(alpha = .65f)).clickable(enabled = true) { }, contentAlignment = Alignment.Center) {
                    Box(Modifier.sticker(C.Teal, shadow = 6.dp).padding(22.dp)) { T(msg.uppercase(), color = C.Ink, size = 18.sp, spacing = .08.em) }
                }
            }
        }
        }
    }
}

/** A full-height panel over the machine, for picking, editing and files. */
/** Build 105: a new install's first screen. Three things to try, then out of the way. */
@Composable
fun StartScreen(state: AppState) {
    ScrollPane(hPad = 16.dp, vPad = 4.dp) {
        T("Three things to try:", size = 16.sp)
        for ((n, line) in listOf(
            "Tap a pad. It plays. Tap a few at once.",
            "Press Play. The beat starts looping.",
            "Flick a pad up (or tap Edit, then the pad) for its settings.",
        ).withIndex()) {
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                T("${n + 1}", size = 26.sp, color = C.Yellow, family = Bungee, modifier = Modifier.padding(end = 12.dp))
                T(line, size = 15.sp)
            }
        }
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("Make a mess", { state.overlay = null }, color = C.Yellow, tag = "start-go")
            Btn("Take a lesson", { state.overlay = Overlay.Learn }, tag = "start-learn")
        }
    }
}

@Composable
fun Sheet(state: AppState, title: String, content: @Composable () -> Unit) {
    val page = "sheet:$title"
    androidx.compose.runtime.DisposableEffect(page) { onDispose { state.markHelpSeen(page) } }
    CompositionLocalProvider(LocalHelp provides state.helpShows(page)) { SheetBody(state, title, content) }
}

@Composable
private fun SheetBody(state: AppState, title: String, content: @Composable () -> Unit) {
    // the dimmed backdrop only blocks taps to the page behind; it is not a button (a clickable here
    // swallowed the whole sheet into one node for screen readers and for the design check)
    Box(Modifier.fillMaxSize().background(C.Black.copy(alpha = .6f)).pointerInput(Unit) { detectTapGestures { } }) {
        Column(Modifier.fillMaxSize().padding(top = 18.dp).background(C.Paper).grit().drawBehind {
            drawLine(C.Pink, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 4.dp.toPx())
        }.tagged("sheet")) {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Heading(title, size = 20.sp, modifier = Modifier.weight(1f))
                CloseScrew({ state.overlay = null }, tag = "done")
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }
}

@Composable
fun Transport(state: AppState) {
    val P = state.project
    @Suppress("UNUSED_VARIABLE") val lv = state.liveV
    val pulse = beatPulse(state)
    val free = !state.locked
    Row(
        // its own layer (Nov 21, lag): the beat flash redraws the top bar only, not the screen under it
        Modifier.fillMaxWidth().graphicsLayer().rail(state).drawBehind {
            // the line under the top bar flashes on every beat, brighter on the one
            val v = pulse.value
            val down = state.heardStep >= 0 && state.heardStep % 16 < 4
            if (v > .01f) drawRect(Brush.verticalGradient(listOf(Color.Transparent, (if (down) C.Pink else C.Yellow).copy(alpha = .35f * v)), startY = size.height * .5f, endY = size.height), topLeft = Offset(0f, size.height * .5f))
            // Nov 22 look spec: in the panel Look the frame's rule is brass, lighting up on the beat (pink is for recording)
            drawLine(lerp(if (UiLook.panel) Hw.Brass else C.Pink, if (down) C.Cream else C.Yellow, v), Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = (if (UiLook.panel) 2 + 4 * v else 3 + 4 * v).dp.toPx())
        }.padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 9.dp).tagged("topbar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        BarBtn(state, if (state.playing) "❚❚" else "▶ Play", "Play", { state.togglePlay() }, color = C.Teal, on = state.playing, onColor = C.Yellow, tag = "play", tier = Tier.Primary, word = if (state.playing) "Pause" else "Play", modifier = Modifier.weight(if (state.look == "panel") 1f else 1.25f))
        BarBtn(state, "■", "Stop", { state.stop() }, tag = "stop", word = "Stop", modifier = Modifier.weight(if (state.look == "panel") 1f else .55f))
        // the panel Look (Nov 21): every key gets the same slot, so the caps come out one size
        RecBtn(state, Modifier.weight(if (state.look == "panel") 1f else 1.05f))
        BarBtn(state, "↶", "Undo", { state.undo() }, enabled = state.canUndo && free, tag = "undo", word = "Undo", modifier = Modifier.weight(if (state.look == "panel") 1f else .55f))
        // tempo and where the music is; the loop letter is its own button now (Nov 18), for loops from every page
        Box(
            Modifier.weight(if (state.look == "panel") 1.5f else .8f).height(36.dp).let { if (state.look == "panel") it.lcd() else it.sticker(C.Well, shadow = 2.dp, border = 2.dp) }
                .clickable(enabled = free) { if (state.midiMapOn) state.midiAim(com.tideclock.messmachine.MidiTarget("tempo", 0)) else state.overlay = Overlay.Tempo }.tagged("bpm"),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                T("${P.bpm}", size = if (state.look == "panel") 14.sp else 12.sp, color = if (state.look == "panel") Hw.Amber else C.Cream, lines = 1, family = if (state.look == "panel") Lcd else Mono, weight = FontWeight.Bold)
                // the Pro looks show the lag here (Nov 18), so a creep is visible as it happens
                if (state.look == "classic") T("BPM", size = 9.sp, color = C.Dim, lines = 1, family = Mono, weight = FontWeight.Bold)
                else {
                    val lag by androidx.compose.runtime.produceState(state.audio.outputMs) { while (true) { value = state.audio.outputMs; if (UiFlags.testing) break; kotlinx.coroutines.delay(1000) } }
                    // Nov 21 (L7): clicks this minute beside the lag, red, so a dropout shows the moment it happens
                    val clicks by androidx.compose.runtime.produceState(0) { while (true) { value = state.audio.clicksLastMinute(); if (UiFlags.testing) break; kotlinx.coroutines.delay(1000) } }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T(if (lag >= 0) "${lag.toInt()} MS" else "BPM", size = 9.sp, color = if (state.look == "panel") Hw.Amber.copy(alpha = .6f) else C.Dim, lines = 1, family = if (state.look == "panel") Lcd else Mono, weight = FontWeight.Bold, modifier = Modifier.tagged("lag"))
                        if (clicks > 0) T(" ${clicks}✕", size = 9.sp, color = C.Riot, lines = 1, family = if (state.look == "panel") Lcd else Mono, weight = FontWeight.Bold, modifier = Modifier.tagged("clicks"))
                    }
                }
            }
        }
        // the loop: where the music is; tap for the row of loops A to H (a pick waits for the bar line while playing)
        Box(
            Modifier.weight(if (state.look == "panel") 1.7f else .9f).height(36.dp).let { if (state.look == "panel") it.lcd(lit = state.loopStripOpen || state.pendingSlot >= 0) else it.sticker(if (state.loopStripOpen) C.Card2 else C.Black, shadow = 2.dp, border = 2.dp, borderColor = if (state.pendingSlot >= 0) C.Yellow else C.Cream) }
                // Nov 22 (Tyler's video, the loop): swipe it for the next or previous loop, tap for the strip
                .pointerInput(free) {
                    if (!free) return@pointerInput
                    var dx = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dx = 0f },
                        onDragEnd = { val far = 28.dp.toPx(); if (dx > far) state.stepLoop(1) else if (dx < -far) state.stepLoop(-1) },
                    ) { ch, d -> dx += d; ch.consume() }
                }
                .clickable(enabled = free) { state.loopStripOpen = !state.loopStripOpen }.tagged("loop-open"),
            contentAlignment = Alignment.Center,
        ) {
            T(if (state.countNow > 0) "COUNT ${state.countNow}" else if (state.pendingSlot >= 0) "→ " + com.tideclock.messmachine.engine.Project.LETTERS[state.pendingSlot] else position(state), size = if (state.look == "panel") 12.sp else 10.sp, color = if (state.look == "panel") Hw.Amber else C.Yellow, lines = 1, family = if (state.look == "panel") Lcd else Mono, weight = FontWeight.Bold, modifier = Modifier.tagged("position"))
        }
        // What the FAQ (Tyler's name): tap it, then tap anything to learn what it is. Page help moved onto its card
        BarBtn(state, "?", "What the FAQ", { state.switchFaq() }, on = state.faqOn, onColor = C.Teal, tag = "help-toggle", tier = Tier.Quiet, word = "FAQ", modifier = Modifier.weight(if (state.look == "panel") 1f else .55f))
        TopMenu(state, Modifier.weight(if (state.look == "panel") 1f else .55f))
    }
}

/** ☰: messes, settings, lessons and redo, so the top bar stays one row. Notes for Claude is the bug by the tabs. */
@Composable
fun TopMenu(state: AppState, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val free = !state.locked
    Box(modifier) {
        BarBtn(state, "☰", "Settings", { open = true }, enabled = free, color = if (state.crash != null) C.Riot else C.Ink, tag = "menu", tier = if (state.crash != null) Tier.Normal else Tier.Quiet, word = "Menu", modifier = Modifier.fillMaxWidth())
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(C.Ink).border(3.dp, C.Cream).tagged("topmenu")) {
            // accounts: the Account entry is there only on a build with a server (state.store is null otherwise)
            val store = state.store
            for ((label, tag, act) in listOfNotNull(
                Triple("☰  Messes", "messes") { state.refreshFiles(); state.cloudRefresh(); state.overlay = Overlay.Files },
                if (store == null) null else Triple(if (store.state == com.tideclock.messmachine.cloud.CloudStore.OUT) "👤  Sign in to save online" else "👤  Account", "acct-open") { state.overlay = Overlay.Account },
                Triple("⚙  Settings", "settings") { state.overlay = Overlay.Settings },
                Triple("🐞  Notes for Claude" + state.waitingNotes.size.let { if (it > 0) " ($it waiting)" else "" }, "notes-open") { state.overlay = Overlay.Notes },
                Triple("🎓  Learn: lessons for every feature", "learn") { state.overlay = Overlay.Learn },
                Triple("👄  Mouth control: wub with your voice", "mouth") { state.overlay = Overlay.Mouth },
                Triple("🎛  MIDI map: teach your keyboard", "midimap-open") { state.switchMidiMap(true) },
                Triple("↷  Redo", "redo") { state.redo() },
            )) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { T(label, size = 15.sp, weight = FontWeight.Medium) },
                    onClick = { open = false; act() },
                    enabled = tag != "redo" || state.canRedo,
                    modifier = Modifier.tagged(tag),
                )
            }
        }
    }
}

/** SE-6: how each repeat treats a held note. Cut stops it at the next repeat, Ring lets it play out, Flow holds it and pulses its volume. */
val REPEAT_STYLES = listOf("cut" to "Cut each repeat", "ring" to "Let repeats ring", "flow" to "Flow (hold and pulse)")

fun styleShort(s: String): String = when (s) { "ring" -> " RNG"; "flow" -> " FLO"; else -> "" }

/** Note repeat on or off, and its rate or arp. */
@Composable
fun RepeatControls(state: AppState, tag: String = "repeat") {
    // Nov 23 (Tyler: "tapping Click isn't working most of the time"): the live settings are not Compose state; reading
    // liveV makes this redraw when one changes (Click toggled, but its key only showed it on the next redraw)
    @Suppress("UNUSED_VARIABLE") val lvBump = state.liveV
    Btn("Repeat", { state.setLive { nr = !nr } }, on = state.live.nr, onColor = C.Pink, small = true, tag = tag)
    // Nov 21 note: the rate and the pattern are two choices, not one list
    Select(
        "Rate " + rateName(state.live.nrRate) + styleShort(state.live.nrStyle),
        listOf(4.0, 2.0, 1.0, .5).flatMap { b -> listOf(1.0, 2.0 / 3, 1.5).map { m -> "r${b * m}" to "Every " + rateName(b * m) } } +
            REPEAT_STYLES.map { (s, n) -> "s$s" to (if (state.live.nrStyle == s) "✓ " else "") + n },
        { v -> if (v.startsWith("s")) state.setLive { nrStyle = v.substring(1) } else state.setLive { nrRate = v.substring(1).toDouble() } },
        tag = "$tag-rate",
    )
    Select(
        "Pattern " + repeatPatternWord(state),
        listOf("rep", "up", "down", "updown", "rand").map { "a$it" to arpName(it) } + REPEAT_PATTERNS.map { (p, n) -> "p$p" to "Rests: $n" },
        { v -> if (v.startsWith("a")) { val a = v.substring(1); state.setLive { arp = a; if (a != "rep") nr = true } } else { val p = v.substring(1); state.setLive { nrPat = p; if (p.isNotEmpty()) nr = true } } },
        tag = "$tag-pattern",
    )
}

/** SE-3: the phone has stayed busy with live grain playing; offer to bake those pads, once a session. */
@Composable
fun CpuBanner(state: AppState) {
    val pads = state.grainPadsToBake()
    Column(Modifier.fillMaxWidth().background(C.Card2).tagged("cpu-banner").padding(horizontal = 14.dp, vertical = 10.dp)) {
        T("THE PHONE IS WORKING HARD (${(state.audio.load() * 100).toInt()}%)", size = 13.sp, color = C.Yellow, spacing = .06.em, lines = 1)
        T("Live grain on " + (if (pads.size == 1) "pad ${pads[0] + 1}" else "${pads.size} pads") + " costs three times a plain sound. Baking keeps the lag low and sounds cleaner.",
            size = 13.sp, weight = androidx.compose.ui.text.font.FontWeight.Medium, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Bake them", { state.bakePads(pads); state.cpuAsk = false }, on = true, onColor = C.Teal, small = true, tag = "cpu-bake")
            Btn("Latency", { state.cpuAsk = false; state.overlay = Overlay.AudioCheck }, small = true, tag = "cpu-latency")
            Btn("Not now", { state.cpuAsk = false }, small = true, tag = "cpu-no")
        }
    }
}

/** While the mic records: a red bar you can see from any page, tap to stop. */
@Composable
fun RecordingBanner(state: AppState) {
    val pulse = beatPulse(state)
    Box(
        Modifier.fillMaxWidth().background(lerp(C.Riot, C.Pink, pulse.value * .5f)).clickable { if (state.micPad >= 0) state.recordToPad(state.micPad) else state.toggleMic() }
            .tagged("rec-banner").padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        T("● RECORDING" + (if (state.micPad >= 0) " ONTO PAD ${state.micPad + 1}" else "") + " · TAP TO STOP", size = 14.sp, color = C.Cream, spacing = .06.em, lines = 1)
    }
}

/** The beat glow behind everything: a soft wash from the middle, pink on the one, yellow on the other beats. */
@Composable
fun PulseBackdrop(state: AppState) {
    if (state.pulseLevel == 0) return
    val pulse = beatPulse(state)
    val strength = if (state.pulseLevel == 2) .30f else .14f
    // Nov 21 (lag): the glow lives in its own layer and only its see-through changes each frame. Drawn the old way, every
    // frame of every beat redrew the whole screen behind it (every key, pad and vial), 120 times a second on a Fold
    Box(Modifier.fillMaxSize().tagged("pulse").graphicsLayer { alpha = pulse.value.coerceIn(0f, 1f) }.drawBehind {
        val down = state.heardStep >= 0 && state.heardStep % 16 < 4
        val c = if (down) C.Pink else C.Yellow
        drawRect(c.copy(alpha = strength * .35f))
        drawRect(Brush.radialGradient(listOf(c.copy(alpha = strength), Color.Transparent), center = center, radius = size.maxDimension * .7f))
    })
}

/** Repeat's rate split into its note and its feel: 1 straight, 2/3 triplet, 1.5 dotted (note #18). */
fun repeatParts(r: Double): Pair<Double, Double> {
    for (b in listOf(4.0, 2.0, 1.0, .5)) for (m in listOf(1.0, 2.0 / 3, 1.5)) if (kotlin.math.abs(b * m - r) < 1e-6) return b to m
    return 1.0 to 1.0
}

/** Repeat's rate as a note length: steps between repeats (4 is a quarter note); T is a triplet, a dot is dotted. */
fun rateName(r: Double): String {
    val (b, m) = repeatParts(r)
    return (when (b) { 4.0 -> "1/4"; 2.0 -> "1/8"; 0.5 -> "1/32"; else -> "1/16" }) + (if (m < 1.0) "T" else if (m > 1.0) "." else "")
}

fun arpShort(a: String) = when (a) { "up" -> "UP"; "down" -> "DN"; "updown" -> "UD"; "rand" -> "RND"; else -> "" }

/** 1 on each beat while playing, falling back to 0 before the next. */
@Composable
fun beatPulse(state: AppState): Animatable<Float, AnimationVector1D> {
    val pulse = remember { Animatable(0f) }
    val beat = if (state.playing && state.heardStep >= 0) state.heardStep / 4 else -1
    LaunchedEffect(beat) {
        if (beat >= 0) {
            pulse.snapTo(1f)
            pulse.animateTo(0f, tween((60000f / state.project.bpm * .8f).toInt(), easing = LinearOutSlowInEasing))
        } else pulse.snapTo(0f)
    }
    return pulse
}

/** A round light that thumps with the beat: pink on the one, yellow on the others. */
@Composable
fun BeatLight(state: AppState, pulse: Animatable<Float, AnimationVector1D>) {
    Canvas(Modifier.size(26.dp).graphicsLayer().tagged("beatlight")) {
        val v = pulse.value
        val down = state.heardStep >= 0 && state.heardStep % 16 < 4
        val c = if (down) C.Pink else C.Yellow
        val r = size.minDimension / 2
        if (v > .01f) drawCircle(c.copy(alpha = .35f * v), radius = r)
        drawCircle(lerp(C.Card2, c, v), radius = r * (.55f + .2f * v))
        drawCircle(C.Cream, radius = r * (.55f + .2f * v), style = Stroke(2.dp.toPx()))
    }
}

/** Rec blinks on the beat while it is armed and the loop plays. */
@Composable
fun RecBtn(state: AppState, modifier: Modifier = Modifier) {
    @Suppress("UNUSED_VARIABLE") val lv = state.liveV
    val pulse = beatPulse(state)
    BarBtn(state, "● Rec", "Rec", { state.setLive { rec = !rec } }, on = state.live.rec, onColor = C.Riot, tag = "rec", tier = Tier.Primary, word = "Rec", modifier = modifier.recGlow(state, pulse))
}

/**
 * Rec's light (note #10): armed, it stays red; while the loop plays it flares on every beat at the same
 * moment as the beat light and the glow (brighter on the one), with a red halo around it.
 */
fun Modifier.recGlow(state: AppState, pulse: Animatable<Float, AnimationVector1D>): Modifier = this
    .graphicsLayer()
    .drawBehind {
        if (!state.live.rec) return@drawBehind
        val v = if (state.playing) pulse.value else .35f
        if (v < .01f) return@drawBehind
        val r = size.maxDimension * (.7f + .25f * v)
        drawCircle(Brush.radialGradient(listOf(C.Riot.copy(alpha = .55f * v), Color.Transparent), center = center, radius = r), radius = r, center = center)
    }
    .drawWithContent {
        drawContent()
        if (!state.live.rec || !state.playing) return@drawWithContent
        val v = pulse.value
        val one = state.heardStep >= 0 && state.heardStep % 16 < 4
        if (v > .01f) drawRect(Color.White.copy(alpha = (if (one) .42f else .26f) * v))
    }

@Composable
fun PositionText(state: AppState) {
    Box(Modifier.width(80.dp).height(34.dp).background(C.Well).border(2.dp, C.Cream), contentAlignment = Alignment.Center) {
        T(position(state), size = 13.sp, color = C.Yellow, lines = 1, family = Mono, weight = FontWeight.Bold)
    }
}

fun arpName(a: String) = when (a) { "up" -> "Arp up"; "down" -> "Arp down"; "updown" -> "Arp up-down"; "rand" -> "Arp random"; else -> "Plain repeat" }

fun position(state: AppState): String {
    val s = state.heardStep
    if (!state.playing || s < 0) {
        val p = state.project
        return if (p.songMode) "SONG ${p.songLen()}" else "LOOP ${Project.LETTERS[p.cur]}"
    }
    val pat = state.heardPat
    val pre = if (state.project.songMode) "${state.heardSong + 1}·" else ""
    return "$pre${if (pat >= 0) Project.LETTERS[pat].toString() else "-"} ${s / 16 + 1}.${s % 16 / 4 + 1}.${s % 4 + 1}"
}

@Composable
fun Tabs(state: AppState) {
    Row(Modifier.fillMaxWidth().rail(state).drawBehind {
        if (UiLook.panel) drawLine(Hw.Brass, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 2.dp.toPx())
        else drawLine(C.Pink, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 3.dp.toPx())
    }.padding(horizontal = 4.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        // the bug button in the bottom left corner of every page (note #16). Note #20: tap, talk, tap again, and the
        // note waits on the phone. Red while listening or when a crash waits (then it opens the note box)
        val hot = state.crash != null || state.quickText != null
        if (state.look == "panel" && state.tabSlot) {
            BarBtn(state, "🐞", "Notes (bug)", { state.bugTap() }, on = hot, onColor = C.Riot, enabled = !state.locked || state.quickText != null, tag = "notes", word = if (state.quickText != null) "Talk" else "Note")
            TabSlot(state, Modifier.weight(1f))
            return@Row
        }
        if (state.look != "classic") {
            // the Pro looks: icon tiles (the bug is red while listening or when a crash waits)
            BarBtn(state, "🐞", "Notes (bug)", { state.bugTap() }, on = hot, onColor = C.Riot, enabled = !state.locked || state.quickText != null, tag = "notes", word = if (state.quickText != null) "Talk" else "Note")
            for (t in com.tideclock.messmachine.BAR_TABS) {
                val play = t == Tab.Pads
                val on = state.tab == t || (play && state.tab == Tab.Console) || (t == Tab.Steps && state.tab == Tab.Roll)
                val icon = when (t) { Tab.Pads -> "Play tab"; Tab.Synth -> "Synth"; Tab.Steps -> "Steps"; Tab.Roll -> "Roll"; Tab.Song -> "Song"; Tab.Perform -> "FX"; else -> "Sounds" }
                BarBtn(state, t.label, icon, { if (play) state.openPlay() else state.tab = t }, on = on, enabled = !state.locked, tag = "tab-${t.label}", word = if (play) "Play" else if (t == Tab.Steps) "Seq" else t.label, modifier = Modifier.weight(1f))
            }
            return@Row
        }
        Box(
            Modifier.width(34.dp).height(34.dp).background(if (hot) C.Riot else C.Ink).border(2.dp, if (hot) C.Cream else C.Card2)
                .clickable(enabled = !state.locked || state.quickText != null) { state.bugTap() }.tagged("notes"),
            contentAlignment = Alignment.Center,
        ) { T(if (state.quickText != null) "●" else "🐞", size = 16.sp, color = C.Cream) }
        // Pads and Desk share one Play tab: its views (Pads, Keys, My Desk) switch at the top of the page
        for (t in com.tideclock.messmachine.BAR_TABS) {
            val play = t == Tab.Pads
            val on = state.tab == t || (play && state.tab == Tab.Console) || (t == Tab.Steps && state.tab == Tab.Roll)
            Box(
                Modifier
                    .weight(1f)
                    .height(34.dp)
                    .background(if (on) C.Yellow else C.Ink)
                    .border(if (on) 3.dp else 0.dp, C.Cream)
                    .clickable(enabled = !state.locked) { if (play) state.openPlay() else state.tab = t }
                    .tagged("tab-${t.label}"),
                contentAlignment = Alignment.Center,
            ) {
                T(if (play) "PLAY" else if (t == Tab.Steps) "SEQ" else t.label.uppercase(), size = 11.sp, color = if (on) C.Ink else if (state.locked) C.Empty else C.Dim, spacing = .02.em, lines = 1)
            }
        }
    }
}


/**
 * Nov 22 (Tyler, from the look review): the page tabs sit in one milled slot cut into the rail; the page you are on is
 * the one raised block, the others are engraved flat into the slot. Settings, Look has "Tabs: keys" to go back.
 */
@Composable
fun TabSlot(state: AppState, modifier: Modifier) {
    Row(
        modifier.padding(start = 4.dp).drawBehind {
            val r = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx())
            // the slot: cut into the plate, dark at the top edge where the light can't reach
            drawRoundRect(Color(0xFF14110F), cornerRadius = r)
            drawRoundRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .55f), Color.Transparent), endY = 10.dp.toPx()), cornerRadius = r)
            drawRoundRect(Color.White.copy(alpha = .08f), topLeft = Offset(0f, size.height - 1.5f), size = androidx.compose.ui.geometry.Size(size.width, 1.5f))
        }.padding(horizontal = 3.dp, vertical = 3.dp).tagged("tab-slot"),
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        for (t in com.tideclock.messmachine.BAR_TABS) {
            val play = t == Tab.Pads
            val on = state.tab == t || (play && state.tab == Tab.Console) || (t == Tab.Steps && state.tab == Tab.Roll)
            val icon = when (t) { Tab.Pads -> "Play tab"; Tab.Synth -> "Synth"; Tab.Steps -> "Steps"; Tab.Roll -> "Roll"; Tab.Song -> "Song"; Tab.Perform -> "FX"; else -> "Sounds" }
            val word = if (play) "Play" else if (t == Tab.Steps) "Seq" else t.label
            val res = Icons.all[icon]?.first
            Column(
                Modifier.weight(1f).clickable(enabled = !state.locked) { if (play) state.openPlay() else state.tab = t }.tagged("tab-${t.label}")
                    .semantics { selected = on }.alpha(if (state.locked) .45f else 1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val capH = 40.rdp.value * UiLook.big  // Nov 22 sizing: one size in real dp, not grown with the screen
                Box(
                    Modifier.fillMaxWidth().height(capH.dp).let { if (on) it.bakelite(true, C.Yellow, false, lamped = false, tint = tileFill("place"), fill = true).padding(bottom = UiLook.lip) else it },
                    contentAlignment = Alignment.Center,
                ) {
                    if (res != null) Box(Modifier.alpha(if (on) 1f else .7f)) { InlayIcon(res, (capH - 6f).dp, true, word) }
                    else T(word.uppercase(), size = 10.sp, color = Hw.Engrave, lines = 1, family = Stencil)
                }
                if (state.proWords) { if (state.wordPlate) PlateWord(word, on, C.Yellow) else T(word.uppercase(), size = (8f * UiLook.big).sp, color = if (on) C.Yellow else Hw.Engrave.copy(alpha = .55f), lines = 1, family = Stencil, weight = FontWeight.Normal, modifier = Modifier.padding(top = 2.dp)) }
            }
        }
    }
}

/** Note #20: while the bug button listens, what it heard so far, with Keep and Drop. Sits just above the tabs. */
@Composable
fun QuickNoteBar(state: AppState, modifier: Modifier = Modifier) {
    val heard = state.quickText.orEmpty()
    // above the bottom bar where it shows, so Menu, Hold and Chords stay in reach while you talk
    val dockUp = state.tab == Tab.Pads || state.tab == Tab.Console || state.tab == Tab.Synth
    Row(
        modifier.padding(start = 6.dp, end = 12.dp, bottom = if (dockUp) 100.dp else 52.dp).sticker(C.Riot, shadow = 5.dp).tagged("quick-note").padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T(if (heard.isEmpty()) "● Listening. Talk, then tap the bug." else "● " + heard.takeLast(90), color = C.Cream, size = 13.sp, lines = 2, modifier = Modifier.weight(1f, fill = false))
        Btn("Keep", { state.quickStop() }, small = true, color = C.Ink, tag = "quick-save", modifier = Modifier.padding(start = 8.dp))
        Btn("Drop", { state.quickDrop() }, small = true, color = C.Ink, tier = Tier.Quiet, tag = "quick-drop", modifier = Modifier.padding(start = 4.dp))
    }
}

/** A newer build is on GitHub: one tap downloads and installs it (Tyler, Nov 17). */
@Composable
fun UpdateBar(state: AppState) {
    val u = state.updater
    Row(Modifier.fillMaxWidth().background(C.Teal).tagged("update-bar").padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        T(if (u.busy) u.status else "Build ${u.latest} is ready.", color = C.Ink, size = 13.sp, lines = 2, modifier = Modifier.weight(1f))
        if (!u.busy) {
            Btn("Update", { state.installUpdate() }, small = true, color = C.Ink, tag = "update-go", modifier = Modifier.padding(start = 8.dp))
            Btn("Later", { u.later = true }, small = true, color = C.Teal, tier = Tier.Quiet, tag = "update-later", modifier = Modifier.padding(start = 4.dp))
        }
    }
}


/**
 * The loop strip (Nov 22, Tyler's video): the one place for loops. Two sides, Loop and Song (second video: with Song
 * open it read as one long page). Loop: pad-sized loops A to H, when a pick lands, the click and the count-in, the
 * loop's bars and its tools. Song: the song painter. Keys are only as wide as what is on them.
 */
@Composable
fun LoopStrip(state: AppState) {
    val P = state.project
    val bars = P.pat().len / 16
    // Nov 22 review: at most half the screen, so a row of pads stays in view even with the song open
    val tall = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp / state.uiScale * .5f).dp
    @Suppress("UNUSED_VARIABLE") val lv = state.liveV
    Column(
        Modifier.fillMaxWidth().plate(bottom = true).tagged("loopstrip").heightIn(max = tall)
            .verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            PickSlider(listOf("Loop" to null, (if (P.songMode) "Song on" else "Song") to null), if (state.songOpen) 1 else 0, { state.songOpen = it == 1 },
                Modifier.weight(1f), tags = listOf("loop-tab-loop", "loop-song"), tag = "loopside-chips", onColor = C.Yellow, icons = false)
            CloseScrew({ state.loopStripOpen = false }, tag = "loop-close", label = "Close the loops", small = true)
        }
        // no early return here: inside a Column it breaks Compose's bookkeeping (see the perform row)
        if (state.songOpen) SongBody(state) else LoopSide(state, bars)
    }
}

/** The Loop side of the strip. */
@Composable
private fun LoopSide(state: AppState, bars: Int) {
    val P = state.project
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in 0 until com.tideclock.messmachine.engine.Project.NSLOTS) LoopPad(state, i, Modifier.weight(1f))
        }
        // when a pick lands, then the loop's length (Click and Count moved to the Play page, Nov 22)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Btn(if (state.loopAtBar) "Wait bar" else "Now", { state.switchLoopAtBar() }, small = true, tight = true, on = state.loopAtBar, onColor = C.Teal, tag = "loop-when", modifier = Modifier.widthIn(min = Floor.KEY.rdp), icon = if (state.loopAtBar) "Wait bar" else "Now")
            Spacer(Modifier.weight(1f))
            T("${bars} BAR${if (bars > 1) "S" else ""}", size = 11.sp, color = C.Dim, spacing = .06.em, lines = 1, modifier = Modifier.tagged("loop-bars"))
            Btn("−", { state.dropBar() }, small = true, tight = true, enabled = bars > 1, tag = "loop-minus", modifier = Modifier.widthIn(min = Floor.KEY.rdp), icon = "Bar minus")
            Btn("+", { state.addBar() }, small = true, tight = true, enabled = bars < 8, tag = "loop-plus", modifier = Modifier.widthIn(min = Floor.KEY.rdp), icon = "Bar plus")
            Btn("2×", { state.doubleLoop() }, small = true, tight = true, enabled = bars <= 4, tag = "loop-double", modifier = Modifier.widthIn(min = Floor.KEY.rdp), icon = "Double")
        }
        // the bars of this loop: pick the one Steps and Roll show
        if (bars > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            T("BAR", size = 11.sp, color = C.Dim, spacing = .1.em)
            for (b in 0 until bars) Btn("${b + 1}", { state.page = b }, on = state.page == b, small = true, tight = true, tag = if (b == 1) "page2" else "page-${b + 1}", modifier = Modifier.weight(1f))
        }
        Wrap(gap = 6.dp) {
            Select("Copy to", (0 until com.tideclock.messmachine.engine.Project.NSLOTS).filter { it != P.cur }.map { it to "Loop ${com.tideclock.messmachine.engine.Project.LETTERS[it]}" }, { state.copySlotTo(it) })
            Btn("Clear loop", { state.clearSlot() }, small = true, enabled = P.pat().used(), tier = Tier.Danger, tag = "loop-clear", icon = "Clear")
            Btn("Auto", { state.movesOpen = !state.movesOpen }, on = state.movesOpen, onColor = C.Teal, small = true, tag = "moves-open")
            if (P.pat().hasMotion()) Btn("Clear auto", { state.clearMoves() }, small = true, tag = "clear-moves")
            Btn("Bottle it", { state.bottleIt() }, small = true, color = C.Pink, enabled = P.pat().used(), tag = "bottle")
        }
        if (state.movesOpen) MovesPanel(state)
    }
}

/** One loop in the strip, pad sized: tap picks it (at the bar line while playing), hold for its menu. */
@Composable
fun LoopPad(state: AppState, i: Int, modifier: Modifier) {
    val P = state.project
    val used = P.pats[i].used(); val cur = P.cur == i; val wait = state.pendingSlot == i
    val playingHere = state.playing && state.heardPat == i
    val L = com.tideclock.messmachine.engine.Project.LETTERS[i]
    Box(
        modifier.height(58.dp)
            .sticker(if (cur) C.Yellow else if (wait) C.Pink else if (used) C.Card2 else C.Ink, shadow = if (cur) 1.dp else 3.dp, border = if (playingHere) 4.dp else 2.5.dp, borderColor = if (playingHere) C.Pink else C.Cream)
            .tapOrHold(i, { state.pickLoop(i) }, { if (!state.locked) state.slotMenu = i })
            .tagged("loop-$i"),
        contentAlignment = Alignment.Center,
    ) {
        T(L.toString(), size = 18.sp, weight = FontWeight.Black, color = if (cur || wait) C.Ink else if (used) C.Cream else C.Empty)
        if (used && !cur) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp).size(6.dp).background(C.Letters[i]))
        val n = P.pats[i].len / 16
        if (n > 1) T("$n", size = 9.sp, color = if (cur || wait) C.Ink else C.Dim, family = Mono, weight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp, top = 2.dp))
        HoldMenu(state.slotMenu == i, "Loop $L", { state.slotMenu = -1 }, listOfNotNull(
            Triple("Play loop $L", "slotmenu-pick") { state.selectSlot(i) },
            Triple("Copy to the next empty loop", "slotmenu-copy") { state.copySlotToNextEmpty(i) },
            Triple("Clear loop $L", "slotmenu-clear") { state.clearSlotAt(i) },
            if (P.pats[i].hasMotion()) Triple("Clear its auto", "slotmenu-moves") { state.edit { pats[i].motion.clear() } } else null,
        ))
    }
}



/**
 * Every touch, seen before the control under it gets it (nothing is consumed): remembers the last control with
 * settings for Menu twice, and, with Flick on, a quick flick up off a button opens that button's settings.
 */
fun Modifier.watchTouches(state: AppState): Modifier = composed {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val dens = LocalDensity.current
    this.onGloballyPositioned { origin = it.boundsInRoot().topLeft }.pointerInput(Unit) {
        awaitPointerEventScope {
            // Nov 22 review: each finger keeps its own start, so one finger moving never reads as another one's swipe
            class Touch(val at: Offset, val t: Long, val tag: String?) { var cancelled = false }
            val touches = HashMap<androidx.compose.ui.input.pointer.PointerId, Touch>()
            while (true) {
                val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                val far = with(dens) { 44.dp.toPx() }
                // Nov 22 (Tyler: a swipe still plays): the hit is taken back as soon as the finger has clearly gone 24 dp one
                // way, not 44, so the blip before it fades is about half as long. The swipe's job still needs 44 dp.
                val near = with(dens) { 24.dp.toPx() }
                fun dirOf(from: Offset, to: Offset, dist: Float = far): String? {
                    val dy = from.y - to.y; val sx = to.x - from.x
                    val dx = kotlin.math.abs(sx); val ady = kotlin.math.abs(dy)
                    return when {
                        dy > dist && dx < dy / 2 -> "up"
                        -dy > dist && dx < ady / 2 -> "down"
                        sx < -dist && ady < dx / 2 -> "left"
                        sx > dist && ady < dx / 2 -> "right"
                        else -> null
                    }
                }
                // a gesture cancelled without an up (the system took it) leaves no finger behind
                if (e.changes.none { it.pressed || it.previousPressed }) touches.clear()
                for (c in e.changes) {
                    if (c.pressed && !c.previousPressed) {
                        // Nov 22 (Tyler: scrolling the notes sheet set off the pad under it): nothing under a sheet, or under
                        // the settings tray, counts as touched, so a scroll there is never a pad swipe
                        val at = c.position + origin
                        val covered = state.overlay != null || state.dockMoreOpen || (state.tray != null && TutHi.rects["tray"]?.contains(at) == true)
                        val tag = if (covered) null else settingsTagAt(at, state)
                        touches[c.id] = Touch(c.position, c.uptimeMillis, tag)
                        state.noteTouch(tag)
                        continue
                    }
                    val t = touches[c.id] ?: continue
                    if (c.pressed && c.previousPressed) {
                        // Nov 22 (Tyler): a swipe cancels its own hit. The hit already sounded on touch (no added
                        // latency); the moment this finger has gone far enough to be a swipe, its hit fades out and
                        // a recorded hit is taken back
                        if (t.cancelled || !state.padFlick || t.tag?.startsWith("pad-") != true || c.uptimeMillis - t.t >= 300) continue
                        val dir = dirOf(t.at, c.position, near)
                        if (dir != null && state.swipeDoes(dir)) { t.cancelled = true; t.tag!!.substringAfterLast('-').toIntOrNull()?.let { state.cancelPadHit(it) } }
                    } else if (!c.pressed && c.previousPressed) {
                        touches.remove(c.id)
                        // only a quick swipe does its job: a slow drag is a scroll (a slow-lift swipe here opened trays
                        // under the phone test's page scrolls and hid the tabs)
                        val quick = c.uptimeMillis - t.t < 300
                        val dir = dirOf(t.at, c.position)
                        // Nov 22 (second video, Tyler: "I would just swipe up on these and they would give me my settings"): the bottom
                        // bar's and the perform row's keys always flick up; other buttons only with the Settings switch
                        if ((state.flickOn || t.tag in com.tideclock.messmachine.DOCK_FLICK) && t.tag != null && !t.tag.startsWith("pad-") && quick && dir == "up") state.openSettingsFor(t.tag)
                        // Nov 21: a quick swipe off a pad does what HotKeys (Settings) says for that direction:
                        // up the Edit tray, down the Padiano, left Sample, right FX, out of the box
                        else if (state.padFlick && (t.tag?.startsWith("pad-") == true || t.tag == "synth-tile") && quick && dir != null) {
                            if (t.tag == "synth-tile") state.padSwipeAt(state.synthTilePad, dir, null)
                            else state.padSwipe(t.tag!!, dir)
                        }
                    }
                }
            }
        }
    }
}

/** The smallest tagged control under a point that has settings. */
fun settingsTagAt(p: Offset, state: AppState): String? =
    TutHi.rects.entries.filter { it.value.contains(p) && it.value.width > 1f && state.settingsKind(it.key) != null }
        .minByOrNull { it.value.width * it.value.height }?.key

/** While Menu is armed: a clear layer over the page. The next tap opens what is under it; Menu again opens the last thing. */
@Composable
fun BoxScope.MenuLayer(state: AppState) {
    if (!state.menuArmed) return
    var me by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    Box(
        Modifier.fillMaxSize().onGloballyPositioned { me = it.boundsInRoot() }.tagged("menu-layer")
            .pointerInput(Unit) {
                detectTapGestures { o ->
                    val p = o + me.topLeft
                    val menu = TutHi.rects["open-padmenu"]
                    if (menu != null && menu.contains(p)) { state.menuTap(); return@detectTapGestures }
                    val tag = settingsTagAt(p, state)
                    if (tag == null || !state.openSettingsFor(tag)) { state.menuArmed = false; state.say("That has no settings. Edit, then tap a pad, a strip, a fader or a perform row button.") }
                }
            },
    ) {
        Box(Modifier.align(Alignment.TopCenter).padding(top = 60.dp).sticker(C.Yellow, shadow = 4.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
            T("Tap anything for its settings", color = C.Ink, size = 14.sp)
        }
    }
}

/** The settings tray (the light layer): what you change about a control, just above the bottom bar. */
@Composable
fun SettingsTray(state: AppState, kind: String, modifier: Modifier = Modifier) {
    val dockUp = state.tab == Tab.Pads || state.tab == Tab.Console || state.tab == Tab.Synth
    val pad = kind.substringAfter(':', "").toIntOrNull() ?: state.project.sel
    val title = when (kind.substringBefore(':')) {
        "glide" -> "GLIDE · PAD ${pad + 1}"; "lfo" -> "LFO · PAD ${pad + 1}"; "fx" -> "FX · PAD ${pad + 1}"; "padiano" -> "PADIANO · PAD ${pad + 1}"
        "pack" -> "FANNYPACK · PAD ${pad + 1}"; "edit" -> (if (pad == com.tideclock.messmachine.engine.Project.SCRATCH) "EDIT · SCRATCH SYNTH · " else "EDIT · PAD ${pad + 1} · ") + state.lib.titleOf(state.project.kit.getOrNull(pad).orEmpty()).uppercase(); "hold" -> "HOLD"; "repeat" -> "REPEAT"; "wheel" -> "PITCH WHEEL"; "dockfilter" -> "DOCK FILTER"; else -> kind.uppercase()
    }
    // Nov 22 (second video): Hold's settings are three things, so its tray is one short bar, no title
    if (kind == "hold") {
        Row(
            modifier.padding(start = 8.dp, end = 8.dp, bottom = if (dockUp) 100.dp else 52.dp).trayFrame()
                .padding(horizontal = 8.dp, vertical = 6.dp).tagged("tray"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HoldBar(state)
            CloseScrew({ state.tray = null }, tag = "tray-done", small = true)
        }
        return
    }
    Column(
        modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = if (dockUp) 100.dp else 52.dp).trayFrame()
            .heightIn(min = if (kind.startsWith("edit:")) 430.dp else 0.dp, max = if (kind.startsWith("edit:")) 470.dp else 380.dp).verticalScroll(rememberScrollState()).padding(12.dp).tagged("tray"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(title, size = 12.sp, color = C.Yellow, spacing = .1.em, lines = 1, modifier = Modifier.weight(1f))
            // the deep layer: the sound's own Sample sheet (its FX page and the rest)
            if (kind.startsWith("fx:") && state.project.kit.getOrNull(pad).orEmpty().isNotEmpty()) Btn("More", {
                state.tray = null; state.overlay = Overlay.Doctor(state.project.kit[pad])
            }, small = true, tight = true, tier = Tier.Quiet, tag = "tray-more")
            CloseScrew({ state.tray = null }, tag = "tray-done", modifier = Modifier.padding(start = 6.dp))
        }
        Column(Modifier.padding(top = 8.dp)) {
            when (kind.substringBefore(':')) {
                "glide" -> GlideTray(state, pad)
                "lfo" -> LfoTray(state, pad)
                "fx" -> FxTray(state, pad)
                "padiano" -> PadianoSettings(state)
                "pack" -> PackTray(state, pad)
                "edit" -> EditTray(state, pad)
                "hold" -> HoldBar(state)
                "repeat" -> Wrap(gap = 6.dp) { RepeatControls(state, "tray-repeat") }
                "wheel" -> Wrap(gap = 6.dp) {
                    Btn("Range ±${state.wheelRange}", { state.cycleWheelRange() }, small = true, tag = "tray-wheel-range")
                    Btn(if (state.wheelAll) "Bends every pad" else "Bends the picked pad", { state.switchWheelAll() }, small = true, tag = "tray-wheel-what")
                    Btn(if (state.wheelStay) "Stay" else "Spring", { state.switchWheelStay() }, on = state.wheelStay, onColor = C.Teal, small = true, tag = "tray-wheel-stay")
                }
                "dockfilter" -> DockFilterTray(state)
                else -> T("Nothing to set here yet.", size = 13.sp, color = C.Dim)
            }
        }
    }
}


/** Ask for 60 frames a second (or the fastest the screen has) at the screen's own size. A hint: the phone may still choose. */
fun setScreenRate(act: android.app.Activity, smooth: Boolean) {
    val w = act.window
    val lp = w.attributes
    if (android.os.Build.VERSION.SDK_INT >= 30) {
        val d = act.display
        val cur = d?.mode
        val same = d?.supportedModes?.filter { cur == null || (it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight) }.orEmpty()
        val want = if (smooth) same.maxByOrNull { it.refreshRate } else same.minByOrNull { kotlin.math.abs(it.refreshRate - 60f) }
        if (want != null) lp.preferredDisplayModeId = want.modeId
    }
    lp.preferredRefreshRate = if (smooth) 0f else 60f
    w.attributes = lp
}
