package com.tideclock.messmachine.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.em
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tideclock.messmachine.AppState
import com.tideclock.messmachine.Overlay
import com.tideclock.messmachine.engine.Project

/** Loop slots A to H, length, copy, clear, bottle. Shared by Steps, Roll and Song. */
@Composable
/** Loops A to H. The loop tools (length, copy, clear, bottle) live only on Steps, their home (BT-1). */
fun SlotBar(state: AppState, tools: Boolean = false) {
    val P = state.project
    Column(Modifier.padding(bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp, end = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in 0 until Project.NSLOTS) {
                val used = P.pats[i].used()
                val playingHere = state.playing && state.heardPat == i
                val on = i == P.cur
                Box(
                    Modifier
                        .weight(1f)
                        .height(36.dp)
                        .sticker(if (on) C.Yellow else C.Ink, shadow = if (on) 1.dp else 3.dp, border = if (playingHere) 4.dp else 2.5.dp, borderColor = if (playingHere) C.Pink else C.Cream)
                        .tapOrHold(i, { state.selectSlot(i) }, { if (!state.locked) state.slotMenu = i })
                        .tagged("slot-$i"),
                    contentAlignment = Alignment.Center,
                ) {
                    T(Project.LETTERS[i].toString(), size = 15.sp, weight = FontWeight.Black, color = if (on) C.Ink else if (used) C.Cream else C.Empty)
                    if (used && !on) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(5.dp).background(C.Letters[i]))
                    val L = Project.LETTERS[i]
                    HoldMenu(state.slotMenu == i, "Loop $L", { state.slotMenu = -1 }, listOfNotNull(
                        Triple("Play loop $L", "slotmenu-pick") { state.selectSlot(i) },
                        Triple("Copy to the next empty loop", "slotmenu-copy") { state.copySlotToNextEmpty(i) },
                        Triple("Clear loop $L", "slotmenu-clear") { state.clearSlotAt(i) },
                        if (P.pats[i].hasMotion()) Triple("Clear its auto", "slotmenu-moves") { state.edit { pats[i].motion.clear() } } else null,
                    ))
                }
            }
        }
        if (!tools) return@Column
        val bars = P.pat().len / 16
        Note("Editing loop ${Project.LETTERS[P.cur]}" + if (bars > 1) ", $bars bars" else "", modifier = Modifier.padding(top = 8.dp))
        // longer loops (up to 8 bars): pick the bar to see and edit
        if (bars > 1) Row(Modifier.fillMaxWidth().padding(top = 8.dp, end = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            T("BAR", size = 11.sp, color = C.Dim, spacing = .1.em)
            for (b in 0 until bars) Btn("${b + 1}", { state.page = b }, on = state.page == b, small = true, tag = if (b == 1) "page2" else "page-${b + 1}", modifier = Modifier.weight(1f))
        }
        Wrap(Modifier.padding(top = 8.dp)) {
            Btn(if (bars == 1) "1 bar" else "$bars bars", { state.toggleLen() }, small = true, tag = "looplen")
            Select("Copy to", (0 until Project.NSLOTS).filter { it != P.cur }.map { it to "Loop ${Project.LETTERS[it]}" }, { state.copySlotTo(it) })
            Btn("Clear loop", { state.clearSlot() }, small = true, enabled = P.pat().used(), tier = Tier.Danger)
            Btn("Auto", { state.movesOpen = !state.movesOpen }, on = state.movesOpen, onColor = C.Teal, small = true, tag = "moves-open")
            if (P.pat().hasMotion()) Btn("Clear auto", { state.clearMoves() }, small = true, tag = "clear-moves")
            Btn("Bottle it", { state.bottleIt() }, small = true, color = C.Pink, enabled = P.pat().used(), tag = "bottle")
        }
        Dashed(Modifier.padding(top = 12.dp))
    }
}

@Composable
fun StepsScreen(state: AppState) {
    val P = state.project
    val pat = P.pat()
    val off = state.page.coerceIn(0, pat.len / 16 - 1) * 16
    ScrollPane {
        SlotBar(state, tools = true)
        if (state.movesOpen) MovesPanel(state)
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp, end = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            T("BANK", size = 11.sp, color = C.Dim, spacing = .1.em)
            for (b in 0 until 4) {
                Btn("${b + 1}", modifier = Modifier.weight(1f), onClick = { state.bank = b }, on = state.bank == b, small = true, tag = "steps-bank-$b")
            }
        }
        Wrap(Modifier.padding(bottom = 12.dp)) {
            Btn("Random drums", { state.randomDrums() }, small = true, tag = "random")
            Btn("Clear notes", { state.clearAllNotes() }, small = true, tier = Tier.Danger, enabled = pat.notes.values.any { it.isNotEmpty() }, tag = "clear-all-notes")
        }
        val rows = (0 until Project.BANK).map { state.bank * Project.BANK + it }.filter { P.kit[it].isNotEmpty() }
        if (rows.isEmpty()) Note("Bank ${state.bank + 1} is empty.", color = C.Yellow)
        for (i in rows) {
            val k = P.kit[i]
            val col = padColor(Color(state.lib.colorOf(k)))
            val audible = P.audible(k)
            val muted = P.mutes.contains(k)
            val solo = P.solos.contains(k)
            // one row per sound: the name, then its 16 steps. Mute, solo and the doctor are in the name's menu.
            Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // tap the name for its pitch strip; hold still and lift for the pad's menu
                    Row(
                        Modifier.width(84.dp).heightIn(min = 34.dp).alpha(if (audible) 1f else .45f)
                            .tapOrHold(i, { state.openStepStrip(i) }, { if (!state.locked) state.padMenu = i }).tagged("steps-name-$i"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(4.dp).fillMaxHeight().background(col))
                        Column(Modifier.padding(start = 5.dp, end = 3.dp)) {
                            T(
                                (if (muted) "M " else "") + (if (solo) "S " else "") + "${i + 1}" + if (state.stepStrip == i) " ▾" else "",
                                size = 9.sp, color = if (muted) C.Riot else if (solo) C.Yellow else C.Dim, family = Mono, weight = FontWeight.Bold, lines = 1,
                            )
                            // two lines, so a name like KICK BASEMENT reads whole instead of KICK BASE… (Nov 14 test pass)
                            T(state.lib.titleOf(k).uppercase(), size = 9.sp, lines = 2, spacing = .02.em, color = if (i == P.sel) C.Yellow else C.Cream)
                        }
                        Box { PadMenu(state, i) }
                    }
                    Box(Modifier.weight(1f).alpha(if (audible) 1f else .45f).tagged("steps-$i")) { StepRow(state, k, col, off, state.version) }
                }
                if (state.stepStrip == i) { RowTools(state, i); StepPitchStrip(state, i, k, col) }
                val sheetAt = state.stepSheet
                if (sheetAt != null && sheetAt.first == k) StepSheet(state, k, sheetAt.second)
            }
        }
        Help("Tap a step to turn it on. Tap again: loud, then quiet, then off. Hold a step for how often it plays (75%, every other loop...), how hard, and a filter, reverb or echo for that one hit (a teal corner); a dashed yellow edge marks one that only plays sometimes. Slide along a row to paint it. Tap a sound's name for its row tools (every 2, 4 or 8, repeat across, nudge, copy, spread) and to give its steps pitches. Hold its name, then lift, for its menu: mute, solo, the Sample sheet and more. A red M or yellow S by the number shows a muted or soloed sound. A dot means a step has a pitch or roll notes; tap it to clear it, or Clear notes for the whole loop.", modifier = Modifier.padding(top = 4.dp))
    }
}

/** One pad's 16 steps, drawn in one go. The playhead is read while drawing, so it repaints without rebuilding the screen. */
@Composable
fun StepRow(state: AppState, k: String, col: Color, off: Int, ver: Int) {
    val P = state.project
    val pat = P.pat()
    val on = BooleanArray(16) { pat.step(k, off + it) }
    val dot = BooleanArray(16) { pat.hasNoteAt(k, off + it) }
    // build 108: how hard each step hits: 0 ghost (drawn faint and small), 1 normal, 2 accent (a cream ring)
    val str = IntArray(16) { pat.strength(k, off + it) }
    val chance = IntArray(16) { pat.chance(k, off + it) }
    val locked = BooleanArray(16) { pat.hasLock(k, off + it) }
    val sheet = state.stepSheet?.let { if (it.first == k) it.second - off else -1 } ?: -1
    // the finer grid (Nov 18): hits between the sixteenths, as ticks, drawn as thin marks where they fall
    val offTicks = pat.offs[k].orEmpty().filter { it / com.tideclock.messmachine.engine.Project.TICKS in off until off + 16 }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(30.dp)
            .pointerInput(k, off, P.cur, ver) {
                // a tap flips a step; a finger sliding sideways paints every step it crosses the way the first one went
                // (note #16). A finger moving up or down is left to scroll the page
                awaitEachGesture {
                    val d = awaitFirstDown(requireUnconsumed = false)
                    fun cell(x: Float) = (x / (size.width / 16f)).toInt().coerceIn(0, 15)
                    val j0 = cell(d.position.x)
                    // with this row's pitch strip open, a tap picks the step to set instead
                    if (state.stepStrip >= 0 && state.project.kit[state.stepStrip] == k) {
                        if (waitForUpOrCancellation() != null) state.stepPick = off + j0
                        return@awaitEachGesture
                    }
                    val slop = viewConfiguration.touchSlop
                    var painting = false
                    var paintOn = false
                    var last = -1
                    var tap = true
                    // build 6 of the overhaul: hold a step still for its sheet (chance, every other loop, strength)
                    val holdAt = d.uptimeMillis + viewConfiguration.longPressTimeoutMillis
                    var lastAt = d.uptimeMillis
                    var held = false
                    while (true) {
                        val e = if (tap && !painting) withTimeoutOrNull((holdAt - lastAt).coerceAtLeast(1L)) { awaitPointerEvent() } else awaitPointerEvent()
                        if (e == null) { held = true; break }
                        val c = e.changes.firstOrNull { it.id == d.id } ?: break
                        lastAt = c.uptimeMillis
                        if (!c.pressed) break
                        val dx = c.position.x - d.position.x
                        val dy = c.position.y - d.position.y
                        if (!painting) {
                            if (kotlin.math.abs(dy) > slop && kotlin.math.abs(dy) > kotlin.math.abs(dx)) { tap = false; break }
                            if (kotlin.math.abs(dx) > slop) {
                                painting = true; tap = false
                                paintOn = !state.project.pat().step(k, off + j0)
                                state.paintStep(k, off + j0, paintOn, false)
                                last = j0
                            }
                        }
                        if (painting) {
                            val j = cell(c.position.x)
                            if (j != last) {
                                val a = minOf(j, last); val b = maxOf(j, last)
                                for (x in a..b) state.paintStep(k, off + x, paintOn, false)
                                last = j
                            }
                            c.consume()
                        }
                    }
                    if (held) {
                        state.openStepSheet(k, off + j0)
                        // the finger is still down: let it lift without doing anything else
                        while (true) { val e2 = awaitPointerEvent(); e2.changes.forEach { it.consume() }; if (e2.changes.none { it.pressed }) break }
                    } else if (painting) state.paintStep(k, off + last, paintOn, true)
                    else if (tap) state.toggleStep(k, off + j0)
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
            val ghost = on[c] && str[c] == 0
            drawRoundRect(if ((off + c) % 4 == 0) C.Card2 else C.Ink, tl, sz, r)
            if (on[c]) {
                val fc = if (hot) lerp(col, C.Cream, .5f) else col
                // a ghost fills only the lower part, so it reads as quieter at a glance
                if (ghost) drawRoundRect(fc.copy(alpha = .55f), Offset(tl.x, tl.y + sz.height * .45f), Size(sz.width, sz.height * .55f), r)
                else drawRoundRect(fc, tl, sz, r)
            }
            drawRoundRect(if (hot) C.Yellow else if (on[c] && !ghost) C.Cream else C.Empty, tl, sz, r, style = Stroke(width = if (hot) 3.dp.toPx() else 1.5.dp.toPx()))
            // build 6 of the overhaul: a step that only plays sometimes gets a dashed yellow edge
            if (on[c] && chance[c] != 100) drawRoundRect(C.Yellow, Offset(tl.x + 1.5.dp.toPx(), tl.y + 1.5.dp.toPx()), Size(sz.width - 3.dp.toPx(), sz.height - 3.dp.toPx()), r,
                style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f))))
            // a step lock: a small teal corner
            if (on[c] && locked[c]) drawCircle(C.Teal, radius = 3.dp.toPx(), center = Offset(tl.x + sz.width - 5.dp.toPx(), tl.y + 5.dp.toPx()))
            if (sheet == c) drawRoundRect(C.Teal, Offset(tl.x - 2, tl.y - 2), Size(sz.width + 4, sz.height + 4), r, style = Stroke(width = 4.dp.toPx()))
            if (on[c] && str[c] == 2) drawRoundRect(C.Cream, Offset(tl.x + 3.dp.toPx(), tl.y + 3.dp.toPx()), Size(sz.width - 6.dp.toPx(), sz.height - 6.dp.toPx()), r, style = Stroke(width = 2.5.dp.toPx()))
            if (dot[c]) drawCircle(if (on[c]) C.Ink else C.Cream, radius = 3.dp.toPx(), center = Offset(tl.x + sz.width / 2, sz.height / 2))
            if (state.stepStrip >= 0 && state.project.kit[state.stepStrip] == k && state.stepPick == off + c) drawRoundRect(C.Yellow, Offset(tl.x - 2, tl.y - 2), Size(sz.width + 4, sz.height + 4), r, style = Stroke(width = 4.dp.toPx()))
        }
        for (t in offTicks) {
            val x = (t / com.tideclock.messmachine.engine.Project.TICKS.toFloat() - off) * cw
            drawRect(col, Offset(x - 1.5.dp.toPx(), size.height * .15f), Size(3.dp.toPx(), size.height * .7f))
            drawRect(C.Cream, Offset(x - 1.5.dp.toPx(), size.height * .15f), Size(3.dp.toPx(), size.height * .7f), style = Stroke(width = 1f))
        }
    }
}

/** Build 6 of the overhaul: one step's sheet. How often it plays, which time round the loop, and how hard. */
@Composable
fun StepSheet(state: AppState, k: String, j: Int) {
    val pat = state.project.pat()
    val ch = pat.chance(k, j)
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 6.dp).sticker(C.Card, radius = 6.dp, shadow = 3.dp, border = 2.dp, borderColor = C.Teal, shadowColor = C.Teal).padding(10.dp).tagged("stepsheet")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("STEP ${j + 1}", size = 12.sp, color = C.Teal, spacing = .1.em, modifier = Modifier.weight(1f))
            Btn("Done", { state.stepSheet = null }, small = true, tag = "stepsheet-done")
        }
        T("HOW OFTEN", size = 10.sp, color = C.Dim, spacing = .1.em, modifier = Modifier.padding(top = 6.dp))
        Wrap(Modifier.padding(top = 4.dp)) {
            for (v in intArrayOf(100, 75, 50, 25)) Btn(if (v == 100) "Always" else "$v%", { state.setStepChance(k, j, v) }, on = ch == v, onColor = C.Yellow, small = true, tag = "chance-$v")
        }
        T("WHICH TIME ROUND", size = 10.sp, color = C.Dim, spacing = .1.em, modifier = Modifier.padding(top = 6.dp))
        Wrap(Modifier.padding(top = 4.dp)) {
            for ((v, name) in listOf(101 to "1st of 2", 102 to "2nd of 2", 103 to "1st of 4", 104 to "4th of 4")) Btn(name, { state.setStepChance(k, j, v) }, on = ch == v, onColor = C.Yellow, small = true, tag = "chance-$v")
        }
        // step locks: these snap back on the next step
        T("THIS STEP ONLY", size = 10.sp, color = C.Dim, spacing = .1.em, modifier = Modifier.padding(top = 6.dp))
        for ((t, name, vals) in listOf(
            Triple(com.tideclock.messmachine.engine.Engine.T_FILTER, "Filter shut", floatArrayOf(.25f, .5f, .75f, .9f)),
            Triple(com.tideclock.messmachine.engine.Engine.T_RVB, "Reverb", floatArrayOf(.3f, .6f, 1f)),
            Triple(com.tideclock.messmachine.engine.Engine.T_ECHO, "Echo", floatArrayOf(.3f, .6f, 1f)),
        )) {
            val cur = pat.lock(k, t, j)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                T(name.uppercase(), size = 10.sp, color = C.Cream, modifier = Modifier.width(78.dp))
                Wrap(Modifier.weight(1f)) {
                    Btn("Off", { state.setStepLock(k, j, t, Float.NaN) }, on = cur.isNaN(), small = true, tight = true, tag = "lock-$t-off")
                    for (v in vals) Btn("${(v * 100).toInt()}%", { state.setStepLock(k, j, t, v) }, on = !cur.isNaN() && kotlin.math.abs(cur - v) < .01f, onColor = C.Teal, small = true, tight = true, tag = "lock-$t-${(v * 100).toInt()}")
                }
            }
        }
        T("HOW HARD", size = 10.sp, color = C.Dim, spacing = .1.em, modifier = Modifier.padding(top = 6.dp))
        Wrap(Modifier.padding(top = 4.dp)) {
            val st = pat.strength(k, j)
            for ((v, name) in listOf(0 to "Quiet", 1 to "Normal", 2 to "Loud")) Btn(name, { state.setStepStrength(k, j, v) }, on = st == v, onColor = C.Pink, small = true, tag = "strength-$v")
        }
    }
}

/**
 * Row tools for one sound's steps (build 85): fill every 2, 4 or 8; repeat the first 4, 8 or the first bar across;
 * nudge left or right; copy and paste a row; spread a number of hits evenly; clear. Each is one undo.
 */
@Composable
fun RowTools(state: AppState, pad: Int) {
    var spread by remember(pad) { mutableIntStateOf(3) }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).tagged("rowtools-$pad")) {
        Wrap(Modifier.padding(bottom = 6.dp).tagged("rowtools-chips"), gap = 6.dp) {
            Btn("Every 2", { state.rowTool(pad, "every2") }, small = true, tight = true, tag = "row-every2")
            Btn("Every 4", { state.rowTool(pad, "every4") }, small = true, tight = true, tag = "row-every4")
            Btn("Every 8", { state.rowTool(pad, "every8") }, small = true, tight = true, tag = "row-every8")
            Btn("Repeat 4", { state.rowTool(pad, "rep4") }, small = true, tight = true, tag = "row-rep4")
            Btn("Repeat 8", { state.rowTool(pad, "rep8") }, small = true, tight = true, tag = "row-rep8")
            if (state.project.pat().len > 16) Btn("Repeat bar", { state.rowTool(pad, "repbar") }, small = true, tight = true, tag = "row-repbar")
            Btn("◀", { state.rowTool(pad, "left") }, small = true, tag = "row-left")
            Btn("▶", { state.rowTool(pad, "right") }, small = true, tag = "row-right")
            Btn("Copy", { state.rowTool(pad, "copy") }, small = true, tight = true, tag = "row-copy")
            Btn("Paste", { state.rowTool(pad, "paste") }, small = true, tight = true, enabled = state.rowClip != null, tag = "row-paste")
            Btn("Clear", { state.rowTool(pad, "clear") }, small = true, tight = true, tier = Tier.Danger, tag = "row-clear")
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            T("SPREAD", size = 11.sp, color = C.Dim, spacing = .1.em)
            Btn("−", { spread = (spread - 1).coerceAtLeast(1) }, small = true, tag = "row-spread-down")
            T("$spread", size = 14.sp, color = C.Yellow, family = Mono)
            Btn("+", { spread = (spread + 1).coerceAtMost(16) }, small = true, tag = "row-spread-up")
            Btn("Spread $spread hits", { state.rowTool(pad, "spread$spread") }, small = true, tight = true, onColor = C.Teal, tag = "row-spread")
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
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).tagged("pitch-strip-$pad")) {
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
                .tagged("pitch-keys-$pad")
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
