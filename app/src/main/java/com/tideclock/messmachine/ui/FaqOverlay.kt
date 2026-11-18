package com.tideclock.messmachine.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tideclock.messmachine.AppState
import com.tideclock.messmachine.Faq
import com.tideclock.messmachine.Tutorials
import kotlinx.coroutines.delay

/** The tag under a point: the smallest tagged control there that has a FAQ line (a button before the page it sits on). */
fun faqTagAt(p: Offset): String? =
    TutHi.rects.entries.filter { it.value.contains(p) && it.value.width > 1f && Faq.lineFor(it.key) != null }
        .minByOrNull { it.value.width * it.value.height }?.key

/**
 * What the FAQ: a clear layer over the whole app while it is on. A tap does not press anything; it rings the control
 * under the finger and says what it is, what it does, and which lesson shows it.
 */
@Composable
fun BoxScope.FaqOverlay(state: AppState) {
    if (!state.faqOn) return
    var me by remember { mutableStateOf(Rect.Zero) }
    val tag = state.faqTag
    val line = tag?.let { Faq.lineFor(it) }
    val ring = tag?.let { TutHi.rects[it] }
    Box(
        Modifier.fillMaxSize().onGloballyPositioned { me = it.boundsInRoot() }.tagged("faq-layer")
            .pointerInput(Unit) { detectTapGestures { o -> state.faqTag = faqTagAt(o + me.topLeft) } },
    ) {
        if (ring != null) Canvas(Modifier.fillMaxSize()) {
            val pad = 5.dp.toPx()
            drawRoundRect(C.Teal, Offset(ring.left - me.left - pad, ring.top - me.top - pad), Size(ring.width + pad * 2, ring.height + pad * 2),
                CornerRadius(6.dp.toPx()), style = Stroke(4.dp.toPx()))
        }
        val low = ring != null && (ring.center.y - me.top) > me.height * .55f
        Column(
            Modifier.align(if (low) Alignment.TopCenter else Alignment.BottomCenter).fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = if (low) 64.dp else 12.dp)
                .background(C.Ink).border(3.dp, C.Teal).padding(12.dp).tagged("faq-card"),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("WHAT THE FAQ", size = 12.sp, color = C.Teal, spacing = .1.em, modifier = Modifier.weight(1f))
                Btn("Done", { state.switchFaq(false) }, small = true, tag = "faq-done")
            }
            if (line == null) T("Tap anything on the screen: it says what it is instead of pressing it.", size = 15.sp, color = C.Cream, modifier = Modifier.padding(top = 4.dp))
            else {
                T(line.name, size = 17.sp, color = C.Yellow, modifier = Modifier.padding(top = 4.dp).tagged("faq-name"))
                T(line.text, size = 15.sp, color = C.Cream, modifier = Modifier.padding(top = 2.dp).tagged("faq-text"))
                val lesson = line.lesson?.let { id -> Tutorials.all.firstOrNull { it.id == id } }
                if (lesson != null) Btn("Lesson: ${lesson.title}", { state.switchFaq(false); state.startTutorial(lesson) }, small = true, color = C.Teal, tag = "faq-lesson",
                    modifier = Modifier.padding(top = 8.dp))
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn(if (state.helpOn) "Page help: on" else "Page help: first time", { state.switchHelp() }, small = true, tier = Tier.Quiet, tag = "faq-help")
                Btn("Speed guide", { state.switchFaq(false); state.startSpeedGuide() }, small = true, tier = Tier.Quiet, tag = "faq-speed")
            }
        }
    }
}

/** The speed guide's stops: what to ring, and one line about it. */
val SPEED_STOPS = listOf(
    "topbar" to "The top bar: Play, Stop, Rec, Undo, the tempo, What the FAQ (tap it, then tap anything) and the menu.",
    "tab-Pads" to "The tabs: Play, Synth, Steps, Roll, Song and Sounds.",
    "mode-pads" to "Play's views: Pads, Keys (the pads as notes), Step (the pads as steps) and My Desk, your own layout.",
    "pad-0" to "Pads: tap to play, hold to keep playing, draw a circle for the pad's XY. Empty pads: top corner records, bottom corner picks a sound.",
    "open-padmenu" to "The bottom bar stays under the page: Menu for the picked pad, Pack, Hold, Chords and the key.",
    "pack-toggle" to "Pack opens the picked pad's FannyPack under its row: the Padiano, live FX faders, an LFO and XY, whichever you packed.",
    "dock-open" to "The side dock: Lock, Hold, the pitch wheel, the filter, Repeat and whatever else you put on it.",
    "tab-Steps" to "Steps: tap or slide to paint steps; tap a sound's name for row tools; Moves shows recorded moves as lanes.",
    "notes" to "The bug: a note to Claude about anything. Replies show under each note.",
)

/**
 * The speed guide: one card that stays put while a ring hops around the screen, about five seconds a stop, with
 * pause, back and done. Under a minute for the whole app.
 */
@Composable
fun BoxScope.SpeedGuideOverlay(state: AppState) {
    val i = state.speedStop
    if (i < 0) return
    var me by remember { mutableStateOf(Rect.Zero) }
    val (tag, text) = SPEED_STOPS[i.coerceIn(0, SPEED_STOPS.size - 1)]
    var ring by remember { mutableStateOf<Rect?>(null) }
    LaunchedEffect(i, state.speedPaused) {
        ring = TutHi.rects[tag]
        if (UiFlags.testing || state.speedPaused) return@LaunchedEffect
        delay(5000)
        state.speedStop = if (i + 1 >= SPEED_STOPS.size) -1 else i + 1
    }
    Box(Modifier.fillMaxSize().onGloballyPositioned { me = it.boundsInRoot() }) {
        val r = ring
        if (r != null) Canvas(Modifier.fillMaxSize()) {
            val pad = 6.dp.toPx()
            drawRoundRect(C.Yellow, Offset(r.left - me.left - pad, r.top - me.top - pad), Size(r.width + pad * 2, r.height + pad * 2), CornerRadius(6.dp.toPx()), style = Stroke(4.dp.toPx()))
        }
        val low = r != null && (r.center.y - me.top) > me.height * .55f
        Column(
            Modifier.align(if (low) Alignment.TopCenter else Alignment.BottomCenter).fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = if (low) 64.dp else 12.dp)
                .background(C.Ink).border(3.dp, C.Yellow).padding(12.dp).tagged("speed-card"),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("SPEED GUIDE", size = 12.sp, color = C.Yellow, spacing = .1.em, modifier = Modifier.weight(1f))
                T("${i + 1} of ${SPEED_STOPS.size}", size = 12.sp, color = C.Dim, family = Mono)
            }
            T(text, size = 15.sp, color = C.Cream, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp).tagged("speed-text"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("Back", { state.speedStop = (i - 1).coerceAtLeast(0) }, small = true, tier = Tier.Quiet, enabled = i > 0, tag = "speed-back")
                Btn(if (state.speedPaused) "Go on" else "Pause", { state.speedPaused = !state.speedPaused }, small = true, tier = Tier.Quiet, tag = "speed-pause")
                Btn("Next", { state.speedStop = if (i + 1 >= SPEED_STOPS.size) -1 else i + 1 }, small = true, tag = "speed-next")
                Btn("Done", { state.speedStop = -1 }, small = true, tag = "speed-done")
            }
        }
    }
}
