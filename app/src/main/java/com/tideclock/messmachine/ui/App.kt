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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.withFrameNanos
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

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun MessMachineApp(state: AppState, ticker: Boolean = true) {
    MaterialTheme(colorScheme = darkColorScheme(primary = C.Pink, secondary = C.Teal, background = C.Paper, surface = C.Card)) {
        if (ticker) LaunchedEffect(Unit) {
            while (true) {
                val t = withFrameNanos { it }
                state.tick(t)
            }
        }
        LaunchedEffect(state.toastId) {
            if (state.toast != null) { delay(3200); state.toast = null }
        }
        BackHandler(enabled = state.overlay != null) { state.overlay = null }
        // Lock: back does nothing but remind you how to unlock; the screen is pinned if you chose that
        BackHandler(enabled = state.locked) { state.say("Locked. Hold the lock on the dock for a second to unlock.") }
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
            if (ok && pad >= 0) state.recordToPad(pad) else if (!ok) state.say("The mic needs permission. You can allow it in the phone's settings for Mess Machine.")
        }
        LaunchedEffect(state.askMicFor) { if (state.askMicFor >= 0) micPerm.launch(android.Manifest.permission.RECORD_AUDIO) }
        @Suppress("UNUSED_VARIABLE") val v = state.version
        val dn = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(dn.density * state.uiScale, dn.fontScale), LocalLocked provides state.locked, LocalHelp provides state.helpShows(state.tab.label)) {
        // test tags show up as view ids, so the phone test on the emulator can find things by name
        Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }.background(C.Paper).grit().statusBarsPadding().navigationBarsPadding()) {
            PulseBackdrop(state)
            Column(Modifier.fillMaxSize()) {
                Transport(state)
                if (state.micOn) RecordingBanner(state)
                Row(Modifier.weight(1f).fillMaxWidth()) {
                Dock(state)
                Box(Modifier.weight(1f).fillMaxHeight().testTag("screen-${state.tab.label}")) {
                    // pages change with a short fade instead of a hard cut (note #5)
                    Crossfade(targetState = state.tab, animationSpec = tween(150), label = "tab") { t ->
                        when (t) {
                            Tab.Pads -> PadsScreen(state)
                            Tab.Console -> ConsoleScreen(state)
                            Tab.Steps -> StepsScreen(state)
                            Tab.Roll -> RollScreen(state)
                            Tab.Song -> SongScreen(state)
                            Tab.Sounds -> SoundsScreen(state, null)
                        }
                    }
                }
                }
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
                    is Overlay.Doctor -> Sheet(state, "Doctor's table") { DoctorScreen(state, o.key) }
                    is Overlay.Xy -> Sheet(state, "XY for pad ${o.pad + 1}") { XyEditor(state, o.pad) }
                    Overlay.Files -> Sheet(state, "Messes") { FilesScreen(state) }
                    Overlay.Settings -> Sheet(state, "Settings") { SettingsScreen(state) }
                    Overlay.Tempo -> Sheet(state, "Tempo + feel") { TempoScreen(state) }
                    Overlay.Notes -> Sheet(state, "Notes for Claude") { NotesScreen(state) }
                    null -> Box(Modifier) {}
                }
            }
            SphereOverlay(state)
            state.toast?.let { msg ->
                Box(Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 84.dp)) {
                    Box(Modifier.sticker(C.Yellow, shadow = 5.dp).clickable { state.toast = null }.testTag("toast").padding(horizontal = 14.dp, vertical = 11.dp)) {
                        T(msg, color = C.Ink, size = 14.sp)
                    }
                }
            }
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
@Composable
fun Sheet(state: AppState, title: String, content: @Composable () -> Unit) {
    val page = "sheet:$title"
    androidx.compose.runtime.DisposableEffect(page) { onDispose { state.markHelpSeen(page) } }
    CompositionLocalProvider(LocalHelp provides state.helpShows(page)) { SheetBody(state, title, content) }
}

@Composable
private fun SheetBody(state: AppState, title: String, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(C.Black.copy(alpha = .6f)).clickable(enabled = true) { }) {
        Column(Modifier.fillMaxSize().padding(top = 18.dp).background(C.Paper).grit().drawBehind {
            drawLine(C.Pink, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 4.dp.toPx())
        }.testTag("sheet")) {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Heading(title, size = 20.sp, modifier = Modifier.weight(1f))
                Btn("Done", { state.overlay = null }, color = C.Pink, small = true, tag = "done")
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
        Modifier.fillMaxWidth().background(C.Ink).drawBehind {
            // the line under the top bar flashes on every beat, brighter on the one
            val v = pulse.value
            val down = state.heardStep >= 0 && state.heardStep % 16 < 4
            if (v > .01f) drawRect(Brush.verticalGradient(listOf(Color.Transparent, (if (down) C.Pink else C.Yellow).copy(alpha = .35f * v)), startY = size.height * .5f, endY = size.height), topLeft = Offset(0f, size.height * .5f))
            drawLine(lerp(C.Pink, if (down) C.Cream else C.Yellow, v), Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = (3 + 4 * v).dp.toPx())
        }.padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 9.dp).testTag("topbar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Btn(if (state.playing) "❚❚" else "▶ Play", { state.togglePlay() }, color = C.Teal, on = state.playing, onColor = C.Yellow, small = true, tag = "play", modifier = Modifier.weight(1.25f))
        Btn("■", { state.stop() }, small = true, tag = "stop", modifier = Modifier.weight(.55f))
        RecBtn(state, Modifier.weight(.95f))
        Btn("↶", { state.undo() }, enabled = state.canUndo && free, small = true, tag = "undo", modifier = Modifier.weight(.5f))
        // tempo and where the music is, in one button: tap for tempo, swing and click
        Box(
            Modifier.weight(1.25f).height(36.dp).sticker(C.Black, shadow = 2.dp, border = 2.dp)
                .clickable(enabled = free) { state.overlay = Overlay.Tempo }.testTag("bpm"),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                T("${P.bpm} BPM", size = 11.sp, color = C.Cream, lines = 1, family = Mono, weight = FontWeight.Bold)
                T(position(state), size = 10.sp, color = C.Yellow, lines = 1, family = Mono, weight = FontWeight.Bold, modifier = Modifier.testTag("position"))
            }
        }
        Btn("?", { state.switchHelp() }, small = true, on = state.helpOn, onColor = C.Teal, tag = "help-toggle", modifier = Modifier.weight(.45f))
        TopMenu(state, Modifier.weight(.5f))
    }
}

/** ☰: messes, settings, redo and notes for Claude (the bug button), so the top bar stays one row. */
@Composable
fun TopMenu(state: AppState, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val free = !state.locked
    Box(modifier) {
        Btn("☰", { open = true }, small = true, enabled = free, color = if (state.crash != null) C.Riot else C.Ink, tag = "menu", modifier = Modifier.fillMaxWidth())
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(C.Ink).border(3.dp, C.Cream).testTag("topmenu")) {
            for ((label, tag, act) in listOf(
                Triple("☰  Messes", "messes") { state.refreshFiles(); state.overlay = Overlay.Files },
                Triple("⚙  Settings", "settings") { state.overlay = Overlay.Settings },
                Triple("↷  Redo", "redo") { state.redo() },
                Triple("🐞  Notes for Claude" + if (state.crash != null) " (a crash to send)" else "", "notes") { state.overlay = Overlay.Notes },
            )) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { T(label, size = 15.sp, weight = FontWeight.Medium) },
                    onClick = { open = false; act() },
                    enabled = tag != "redo" || state.canRedo,
                    modifier = Modifier.testTag(tag),
                )
            }
        }
    }
}

/** Note repeat on or off, and its rate or arp. */
@Composable
fun RepeatControls(state: AppState, tag: String = "repeat") {
    Btn("Repeat", { state.setLive { nr = !nr } }, on = state.live.nr, onColor = C.Pink, small = true, tag = tag)
    Select(
        (when (state.live.nrRate) { 2.0 -> "1/8"; 0.5 -> "1/32"; else -> "1/16" }) + if (state.live.arp != "rep") " " + arpShort(state.live.arp) else "",
        listOf("r2" to "Every 1/8", "r1" to "Every 1/16", "r.5" to "Every 1/32") + listOf("rep", "up", "down", "updown", "rand").map { "a$it" to arpName(it) },
        { v ->
            if (v.startsWith("r")) state.setLive { nrRate = v.substring(1).toDouble() }
            else { val a = v.substring(1); state.setLive { arp = a; if (a != "rep") nr = true } }
        },
    )
}

/** While the mic records: a red bar you can see from any page, tap to stop. */
@Composable
fun RecordingBanner(state: AppState) {
    val pulse = beatPulse(state)
    Box(
        Modifier.fillMaxWidth().background(lerp(C.Riot, C.Pink, pulse.value * .5f)).clickable { if (state.micPad >= 0) state.recordToPad(state.micPad) else state.toggleMic() }
            .testTag("rec-banner").padding(horizontal = 14.dp, vertical = 10.dp),
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
    Box(Modifier.fillMaxSize().testTag("pulse").drawBehind {
        val v = pulse.value
        if (v < .01f) return@drawBehind
        val down = state.heardStep >= 0 && state.heardStep % 16 < 4
        val c = if (down) C.Pink else C.Yellow
        drawRect(c.copy(alpha = strength * .35f * v))
        drawRect(Brush.radialGradient(listOf(c.copy(alpha = strength * v), Color.Transparent), center = center, radius = size.maxDimension * .7f))
    })
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
    Canvas(Modifier.size(26.dp).testTag("beatlight")) {
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
    Btn("● Rec", { state.setLive { rec = !rec } }, on = state.live.rec, onColor = C.Riot, tag = "rec", modifier = modifier.recGlow(state, pulse))
}

/**
 * Rec's light (note #10): armed, it stays red; while the loop plays it flares on every beat at the same
 * moment as the beat light and the glow (brighter on the one), with a red halo around it.
 */
fun Modifier.recGlow(state: AppState, pulse: Animatable<Float, AnimationVector1D>): Modifier = this
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
    Box(Modifier.width(80.dp).height(34.dp).background(C.Black).border(2.dp, C.Cream), contentAlignment = Alignment.Center) {
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
    Row(Modifier.fillMaxWidth().background(C.Ink).drawBehind {
        drawLine(C.Pink, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 3.dp.toPx())
    }.padding(horizontal = 4.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (t in Tab.entries) {
            val on = state.tab == t
            Box(
                Modifier
                    .weight(1f)
                    .height(34.dp)
                    .background(if (on) C.Yellow else C.Ink)
                    .border(if (on) 3.dp else 0.dp, C.Cream)
                    .clickable(enabled = !state.locked) { state.tab = t }
                    .testTag("tab-${t.label}"),
                contentAlignment = Alignment.Center,
            ) {
                T(if (t == Tab.Console) "DESK" else t.label.uppercase(), size = 11.sp, color = if (on) C.Ink else if (state.locked) C.Empty else C.Dim, spacing = .02.em, lines = 1)
            }
        }
    }
}
