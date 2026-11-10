package com.tideclock.messmachine.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.em
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.height
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import com.tideclock.messmachine.engine.Theory
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.TextStyle
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tideclock.messmachine.AppState
import com.tideclock.messmachine.Overlay
import com.tideclock.messmachine.engine.Project

/** Loop slots A to H, length, copy, clear, bottle. Shared by Steps, Roll and Song. */
@Composable
fun SlotBar(state: AppState) {
    val P = state.project
    Column(Modifier.padding(bottom = 12.dp)) {
        Wrap(Modifier.padding(bottom = 4.dp), gap = 6.dp) {
            for (i in 0 until Project.NSLOTS) {
                val used = P.pats[i].used()
                val playingHere = state.playing && state.heardPat == i
                val on = i == P.cur
                Box(
                    Modifier
                        .size(36.dp)
                        .sticker(if (on) C.Yellow else C.Ink, shadow = if (on) 1.dp else 3.dp, border = if (playingHere) 4.dp else 2.5.dp, borderColor = if (playingHere) C.Pink else C.Cream)
                        .tapOrHold(i, { state.selectSlot(i) }, { if (!state.locked) state.slotMenu = i })
                        .testTag("slot-$i"),
                    contentAlignment = Alignment.Center,
                ) {
                    T(Project.LETTERS[i].toString(), size = 15.sp, weight = FontWeight.Black, color = if (on) C.Ink else if (used) C.Cream else C.Empty)
                    if (used && !on) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(5.dp).background(C.Letters[i]))
                    val L = Project.LETTERS[i]
                    HoldMenu(state.slotMenu == i, "Loop $L", { state.slotMenu = -1 }, listOfNotNull(
                        Triple("Play loop $L", "slotmenu-pick") { state.selectSlot(i) },
                        Triple("Copy to the next empty loop", "slotmenu-copy") { state.copySlotToNextEmpty(i) },
                        Triple("Clear loop $L", "slotmenu-clear") { state.clearSlotAt(i) },
                        if (P.pats[i].hasMotion()) Triple("Clear its XY moves", "slotmenu-moves") { state.edit { pats[i].motion.clear() } } else null,
                    ))
                }
            }
        }
        Note("Editing loop ${Project.LETTERS[P.cur]}" + if (P.pat().len == 32) ", two bars" else "", modifier = Modifier.padding(top = 8.dp))
        Wrap(Modifier.padding(top = 8.dp)) {
            Btn(if (P.pat().len == 32) "2 bars" else "1 bar", { state.toggleLen() }, small = true, tag = "looplen")
            if (P.pat().len == 32) {
                Btn("1-16", { state.page = 0 }, on = state.page == 0, small = true)
                Btn("17-32", { state.page = 1 }, on = state.page == 1, small = true, tag = "page2")
            }
            Select("Copy to", (0 until Project.NSLOTS).filter { it != P.cur }.map { it to "Loop ${Project.LETTERS[it]}" }, { state.copySlotTo(it) })
            Btn("Clear loop", { state.clearSlot() }, small = true, enabled = P.pat().used())
            if (P.pat().hasMotion()) Btn("Clear moves", { state.clearMoves() }, small = true, tag = "clear-moves")
            Btn("Bottle it", { state.bottleIt() }, small = true, color = C.Pink, enabled = P.pat().used() || P.songLen() > 0, tag = "bottle")
        }
        Dashed(Modifier.padding(top = 12.dp))
    }
}

@Composable
fun StepsScreen(state: AppState) {
    val P = state.project
    val pat = P.pat()
    val off = (if (pat.len == 32) state.page else 0) * 16
    ScrollPane {
        SlotBar(state)
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp, end = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (b in 0 until 4) {
                val n = (0 until Project.BANK).count { P.kit[b * Project.BANK + it].isNotEmpty() }
                Btn("Bank ${b + 1}", modifier = Modifier.weight(1f), onClick = { state.bank = b }, on = state.bank == b, small = true)
            }
        }
        Wrap(Modifier.padding(bottom = 12.dp)) {
            Btn("Click to hear: " + if (state.playOnTap) "on" else "off", { state.switchPlayOnTap(!state.playOnTap) }, on = state.playOnTap, small = true)
            Btn("Random drums", { state.randomDrums() }, small = true, tag = "random")
            Btn("Resample to a pad", { state.resampleLoop() }, small = true, enabled = pat.used())
        }
        val rows = (0 until Project.BANK).map { state.bank * Project.BANK + it }.filter { P.kit[it].isNotEmpty() }
        if (rows.isEmpty()) Note("Bank ${state.bank + 1} is empty. Put sounds on its pads from the Pads or Sounds tab.", color = C.Yellow)
        for (i in rows) {
            val k = P.kit[i]
            val col = padColor(Color(state.lib.colorOf(k)))
            val audible = P.audible(k)
            Column(Modifier.fillMaxWidth().padding(bottom = 6.dp).alpha(if (audible) 1f else .45f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // tap the name for its pitch strip; hold still and lift for the pad's menu
                    Row(Modifier.weight(1f).tapOrHold(i, { state.openStepStrip(i) }, { if (!state.locked) state.padMenu = i }).testTag("steps-name-$i"), verticalAlignment = Alignment.CenterVertically) {
                        Swatch(col)
                        T("${i + 1}", size = 11.sp, color = C.Dim, family = Mono, weight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp, end = 6.dp))
                        T(state.lib.titleOf(k).uppercase() + if (state.stepStrip == i) "  ▾" else "", size = 13.sp, lines = 1, spacing = .04.em, color = if (i == P.sel) C.Yellow else C.Cream)
                        Box { PadMenu(state, i) }
                    }
                    Btn("M", { state.toggleMute(k) }, on = P.mutes.contains(k), onColor = C.Riot, small = true, tag = "mute-$i")
                    Btn("S", { state.toggleSolo(k) }, on = P.solos.contains(k), onColor = C.Yellow, small = true)
                    Btn("✂", { state.overlay = Overlay.Doctor(k) }, small = true)
                }
                Box(Modifier.testTag("steps-$i")) { StepRow(state, k, col, off, state.version) }
                if (state.stepStrip == i) StepPitchStrip(state, i, k, col)
                Dashed(Modifier.padding(top = 10.dp))
            }
        }
        Note("Tap a sound's name to give its steps pitches. Hold its name, then lift, for its menu. M mutes, S solos, ✂ opens the doctor. A dot means a step has a pitch or roll notes.", modifier = Modifier.padding(top = 4.dp))
    }
}

/** One pad's 16 steps, drawn in one go. The playhead is read while drawing, so it repaints without rebuilding the screen. */
@Composable
fun StepRow(state: AppState, k: String, col: Color, off: Int, ver: Int) {
    val P = state.project
    val pat = P.pat()
    val on = BooleanArray(16) { pat.step(k, off + it) }
    val dot = BooleanArray(16) { pat.hasNoteAt(k, off + it) }
    Canvas(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .height(30.dp)
            .pointerInput(k, off, P.cur, ver) {
                detectTapGestures { o ->
                    val j = (o.x / (size.width / 16f)).toInt().coerceIn(0, 15)
                    // with this row's pitch strip open, a tap picks the step to set instead
                    if (state.stepStrip >= 0 && state.project.kit[state.stepStrip] == k) state.stepPick = off + j
                    else state.toggleStep(k, off + j)
                }
            },
    ) {
        val cw = size.width / 16f
        val gap = 3.dp.toPx()
        val r = CornerRadius(2.dp.toPx())
        val now = if (state.playing && state.heardPat == P.cur) state.heardStep - off else -1
        for (c in 0 until 16) {
            val tl = Offset(c * cw + gap / 2, 0f)
            val sz = Size(cw - gap, size.height)
            val hot = c == now
            if (hot) drawRoundRect(C.Pink.copy(alpha = .55f), Offset(tl.x - 3, tl.y - 3), Size(sz.width + 6, sz.height + 6), r)
            drawRoundRect(if (on[c]) (if (hot) lerp(col, C.Cream, .5f) else col) else if ((off + c) % 4 == 0) C.Card2 else C.Ink, tl, sz, r)
            drawRoundRect(if (hot) C.Yellow else if (on[c]) C.Cream else C.Empty, tl, sz, r, style = Stroke(width = if (hot) 3.dp.toPx() else 1.5.dp.toPx()))
            if (dot[c]) drawCircle(if (on[c]) C.Ink else C.Cream, radius = 3.dp.toPx(), center = Offset(tl.x + sz.width / 2, sz.height / 2))
            if (state.stepStrip >= 0 && state.project.kit[state.stepStrip] == k && state.stepPick == off + c) drawRoundRect(C.Yellow, Offset(tl.x - 2, tl.y - 2), Size(sz.width + 4, sz.height + 4), r, style = Stroke(width = 4.dp.toPx()))
        }
    }
}

/**
 * The pitch strip under one sound's steps: pick a step, then a note, and that step plays at that pitch
 * (it becomes a one-step roll note). Plain hit takes the pitch off again.
 */
@Composable
fun StepPitchStrip(state: AppState, pad: Int, k: String, col: Color) {
    val P = state.project
    var base by remember(pad) { mutableIntStateOf(0) }
    val notes = (base..base + 12).filter { !state.inKeyOnly || P.scale == "Chromatic" || Theory.inScale(it, P.scale, P.root) }
    val pick = state.stepPick
    val now = if (pick >= 0) P.pat().notes(k).firstOrNull { it.s == pick }?.p else null
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("pitch-strip-$pad")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
            Note(if (pick < 0) "Pick a step above, then a note." else "Step ${pick + 1}: " + (now?.let { Theory.noteName(it) } ?: if (P.pat().step(k, pick)) "plain hit" else "empty"),
                color = C.Yellow, modifier = Modifier.weight(1f))
            Btn("◀", { base = (base - 12).coerceAtLeast(-24) }, small = true)
            Btn("▶", { base = (base + 12).coerceAtMost(12) }, small = true)
            Btn("Plain", { if (pick >= 0) state.setStepPitch(k, pick, null) }, small = true, enabled = pick >= 0, tag = "pitch-plain")
            Btn("✕", { state.openStepStrip(pad) }, small = true, color = C.Riot)
        }
        val tm = rememberTextMeasurer()
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .padding(end = 4.dp)
                .sticker(C.Ink, shadow = 4.dp, shadowColor = col)
                .testTag("pitch-keys-$pad")
                .pointerInput(pad, notes, pick) {
                    detectTapGestures { o ->
                        val i = (o.x / (size.width / notes.size.toFloat())).toInt().coerceIn(0, notes.size - 1)
                        if (state.stepPick < 0) state.say("Pick a step first, then a note.")
                        else state.setStepPitch(k, state.stepPick, notes[i])
                    }
                },
        ) {
            val w = size.width / notes.size
            notes.forEachIndexed { i, p ->
                val x = i * w
                val black = Theory.pcOf(p) in intArrayOf(1, 3, 6, 8, 10)
                val fill = if (p == now) col else if (black) C.Card2 else lerp(C.Cream, col, .15f)
                drawRect(fill, topLeft = Offset(x + 2f, 2f), size = Size(w - 4f, size.height - 4f))
                drawRect(C.Black, topLeft = Offset(x + 1f, 1f), size = Size(w - 2f, size.height - 2f), style = Stroke(width = 2f))
                if (Theory.pcOf(p) == 0 || p == now) drawText(tm, Theory.noteName(p), topLeft = Offset(x + 3f, 4f),
                    style = TextStyle(color = if (black && p != now) C.Cream else C.Ink, fontSize = 9.sp, fontFamily = Mono, fontWeight = FontWeight.Bold))
            }
        }
    }
}
