package com.tideclock.messmachine.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tideclock.messmachine.AppState
import com.tideclock.messmachine.DOCK_TYPES
import kotlinx.coroutines.launch

/**
 * Build 107: the perform row. The side dock's controls (wheel, filter, Lock, mouth, throws...) in one row that swipes
 * up from the bottom bar, where the thumbs are. Tap or swipe up the handle to open it, swipe down or tap to close.
 * ⋯ picks what lives in it, from the same list the side dock used (Hold and Rec are left out: they are on screen already).
 */
@Composable
fun PerformRow(state: AppState) {
    Column(Modifier.fillMaxWidth().background(C.Ink).tagged("performrow")) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 26.dp)
                .pointerInput(state.performOpen) {
                    awaitEachGesture {
                        val d = awaitFirstDown(requireUnconsumed = false)
                        d.consume()
                        var dy = 0f
                        while (true) {
                            val e = awaitPointerEvent()
                            val c = e.changes.firstOrNull { it.id == d.id } ?: break
                            dy = c.position.y - d.position.y
                            c.consume()
                            if (!c.pressed) break
                        }
                        // a tap flips it; a swipe opens (up) or closes (down)
                        state.switchPerform(if (dy < -12.dp.toPx()) true else if (dy > 12.dp.toPx()) false else !state.performOpen)
                    }
                }
                .tagged("perform-handle"),
            contentAlignment = Alignment.Center,
        ) {
            T(if (state.performOpen) "▾ PERFORM ROW" else "▴ PERFORM ROW", size = 10.sp, color = C.Dim, family = Mono, spacing = .12.em)
        }
        if (!state.performOpen) return
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 8.dp, end = 8.dp, bottom = 8.dp).heightIn(max = 190.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            for (t in state.dockItems.toList()) if (t !in com.tideclock.messmachine.PERFORM_SKIP) Column(Modifier.width(62.dp), horizontalAlignment = Alignment.CenterHorizontally) { DockItem(state, t) }
            var pick by remember { mutableStateOf(false) }
            Box(Modifier.width(52.dp)) {
                DockBtn("⋯", { pick = true }, tag = "perform-edit", enabled = !state.locked)
                DropdownMenu(expanded = pick, onDismissRequest = { pick = false }, modifier = Modifier.background(C.Ink)) {
                    T("IN THE PERFORM ROW", size = 11.sp, color = C.Yellow, spacing = .08.em, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                    for ((id, name) in DOCK_TYPES) if (id !in com.tideclock.messmachine.PERFORM_SKIP) {
                        DropdownMenuItem(
                            text = { T((if (id in state.dockItems) "✓ " else "    ") + name, size = 14.sp, weight = FontWeight.Medium) },
                            onClick = { state.toggleDockItem(id) },
                            modifier = Modifier.tagged("perform-pick-$id"),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The side dock: a slim column on the left edge of every page with the controls you want while
 * playing. Folds to a thin tab. ⋯ picks what sits on it.
 */
@Composable
fun Dock(state: AppState) {
    if (!state.dockOpen) {
        // folded (the default): a thin edge strip; tap it or swipe right from it to open the dock
        Box(
            Modifier.width(14.dp).fillMaxHeight().background(C.Ink)
                .drawBehind { drawLine(C.Pink, Offset(size.width, 0f), Offset(size.width, size.height), strokeWidth = 2.dp.toPx()) }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val d = awaitFirstDown(requireUnconsumed = false)
                        d.consume()
                        var dx = 0f
                        while (true) {
                            val e = awaitPointerEvent()
                            val c = e.changes.firstOrNull { it.id == d.id } ?: break
                            dx = c.position.x - d.position.x
                            c.consume()
                            if (!c.pressed) break
                            if (dx > 24.dp.toPx()) break
                        }
                        state.switchDockOpen(true)
                    }
                }
                .tagged("dock-open"),
            contentAlignment = Alignment.Center,
        ) { T("›", size = 14.sp, color = C.Pink, weight = FontWeight.Bold) }
        return
    }
    Column(
        Modifier.width(62.dp).fillMaxHeight().background(C.Ink).drawBehind {
            drawLine(C.Pink, Offset(size.width, 0f), Offset(size.width, size.height), strokeWidth = 3.dp.toPx())
        }.verticalScroll(rememberScrollState()).padding(start = 5.dp, end = 8.dp, top = 8.dp, bottom = 8.dp).tagged("dock"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        for (t in state.dockItems.toList()) DockItem(state, t)
        var pick by remember { mutableStateOf(false) }
        Box {
            DockBtn("⋯", { pick = true }, tag = "dock-edit", enabled = !state.locked)
            DropdownMenu(expanded = pick, onDismissRequest = { pick = false }, modifier = Modifier.background(C.Ink)) {
                T("ON THE DOCK", size = 11.sp, color = C.Yellow, spacing = .08.em, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                for ((id, name) in DOCK_TYPES) {
                    DropdownMenuItem(
                        text = { T((if (id in state.dockItems) "✓ " else "    ") + name, size = 14.sp, weight = FontWeight.Medium) },
                        onClick = { state.toggleDockItem(id) },
                        modifier = Modifier.tagged("dock-pick-$id"),
                    )
                }
                DropdownMenuItem(
                    text = { T((if (state.lockPin) "✓ " else "    ") + "Lock pins the screen", size = 14.sp, weight = FontWeight.Medium) },
                    onClick = { state.switchLockPin(!state.lockPin) },
                )
            }
        }
        DockBtn("◀", { state.switchDockOpen(false) }, tag = "dock-fold", enabled = !state.locked)
    }
}

@Composable
private fun DockBtn(label: String, onClick: () -> Unit, tag: String, on: Boolean = false, color: androidx.compose.ui.graphics.Color = C.Ink, onColor: androidx.compose.ui.graphics.Color = C.Yellow, enabled: Boolean = true, modifier: Modifier = Modifier) {
    Btn(label, onClick, modifier = modifier.fillMaxWidth(), small = true, on = on, color = color, onColor = onColor, enabled = enabled, tag = tag)
}

/**
 * A small word under a dock button that is a button itself (Repeat's rate, Hold's count, the filter's target):
 * the dock's whole width and 32 dp tall. As bare text they were about 24 by 13 dp, too small to hit while playing.
 */
@Composable
private fun DockWord(text: String, color: androidx.compose.ui.graphics.Color, tag: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = 32.dp).clickable { onClick() }.tagged(tag), contentAlignment = Alignment.Center) {
        T(text, size = 10.sp, color = color, family = Mono, lines = 1)
    }
}

@Composable
private fun DockItem(state: AppState, t: String) {
    // Repeat and Rec live outside the mess: read their change counter so the dock lights up with them (note #7)
    @Suppress("UNUSED_VARIABLE") val lv = state.liveV
    when (t) {
        "lock" -> LockButton(state)
        "hold" -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            DockBtn("H", { state.switchHold() }, tag = "dock-hold", on = state.holdOn)
            DockWord(if (state.holdCount == 0) "LAST" else if (state.holdOn) "${state.latched.size}/${state.holdCount}" else "${state.holdCount}", if (state.holdOn) C.Yellow else C.Dim, "dock-hold-n") {
                state.changeHoldCount(if (state.holdCount >= 8) 0 else state.holdCount + 1)
            }
        }
        "filter" -> DockFilter(state)
        "repeat" -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Repeat's one home: tap RPT to turn it on, tap the rate under it for 1/8, 1/16, 1/32 or an arp
            var rates by remember { mutableStateOf(false) }
            DockBtn("RPT", { state.setLive { nr = !nr } }, tag = "dock-repeat", on = state.live.nr, onColor = C.Pink)
            Box {
                DockWord(
                    rateName(state.live.nrRate) + (if (state.live.arp != "rep") " " + arpShort(state.live.arp) else "") + styleShort(state.live.nrStyle) + (if (state.live.nrPat.isNotEmpty()) " P" else ""),
                    if (state.live.nr) C.Pink else C.Dim, "dock-rate",
                ) { rates = true }
                RepeatMenu(state, rates) { rates = false }
            }
        }
        "rec" -> { val pulse = beatPulse(state); DockBtn("●", { state.setLive { rec = !rec } }, tag = "dock-rec", on = state.live.rec, onColor = C.Riot, modifier = Modifier.recGlow(state, pulse)) }
        "beat" -> BeatLight(state, beatPulse(state))
        "stutter", "echo" -> FxButton(state, t, if (t == "echo") "ECHO" else "STUT", Modifier.fillMaxWidth().tagged("dock-$t"), if (t == "echo") C.Teal else C.Pink, height = 46.dp)
        "desk" -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            DockBtn("DSK", { state.nextDesk() }, tag = "dock-desk", onColor = C.Teal)
            T(state.desks.getOrNull(state.deskIdx)?.name?.uppercase()?.take(6) ?: "", size = 9.sp, color = C.Teal, family = Mono, modifier = Modifier.padding(top = 3.dp))
        }
        "mouth" -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // mouth control: tap to listen; the word under it opens its settings
            DockBtn("MTH", { state.switchMouth() }, tag = "dock-mouth", on = state.mouthOn, onColor = C.Pink)
            DockWord(if (state.mouthOn && state.mouthHeard) "WUB" else "SET", if (state.mouthOn) C.Pink else C.Dim, "dock-mouth-set") { state.overlay = com.tideclock.messmachine.Overlay.Mouth }
        }
        "pitch" -> PitchWheel(state)
        // build 107: the perform effects panel (stutter, echo throw, tape stop...) moved here from the bottom bar's FX
        "throws" -> DockBtn("THRW", { state.showFx = !state.showFx }, tag = "dock-throws", on = state.showFx, onColor = C.Pink)
        "xy" -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val pad = state.project.sel
            DockBtn("XY", { if (state.project.kit[pad].isNotEmpty()) state.cycleXy(pad) }, tag = "dock-xy", onColor = C.Pink)
            T(state.xyOf(pad).name.uppercase().take(6), size = 9.sp, color = C.Yellow, family = Mono, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

/** Lock: tap to lock the page; hold for a second to unlock, with a ring that fills. */
@Composable
private fun LockButton(state: AppState) {
    val fill = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val locked = state.locked
    Box(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .sticker(if (locked) C.Pink else C.Ink, shadow = if (locked) 0.dp else 3.dp, border = 2.5.dp, shadowColor = C.Yellow)
            .tagged("dock-lock")
            .pointerInput(locked) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    if (!locked) {
                        if (waitForUpOrCancellation() != null) state.lock()
                    } else {
                        scope.launch { fill.snapTo(0f); fill.animateTo(1f, tween(1000, easing = LinearEasing)) }
                        val up = withTimeoutOrNull(1000) { waitForUpOrCancellation() }
                        if (up == null) { state.unlock(); waitForUpOrCancellation() }
                        scope.launch { fill.snapTo(0f) }
                    }
                }
            }
            .drawBehind {
                val v = fill.value
                if (v > 0f) drawArc(C.Yellow, -90f, 360f * v, false, topLeft = Offset(6f, 6f), size = Size(size.width - 12f, size.height - 12f), style = Stroke(5.dp.toPx()))
            },
        contentAlignment = Alignment.Center,
    ) {
        T(if (locked) "🔒" else "🔓", size = 18.sp)
    }
    if (locked) T("HOLD", size = 9.sp, color = C.Pink, family = Mono)
}

/**
 * The pitch wheel (build 85, note #16): an upright strip that bends the picked pad (or every pad) up or down and
 * springs back to the middle when you let go. With Rec on while the loop plays, the bend records like XY pitch.
 * The words under it pick the range (2, 7 or 12 semitones) and what it bends.
 */
@Composable
private fun PitchWheel(state: AppState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val v = state.wheelAt
        Canvas(
            Modifier
                .size(width = 26.dp, height = 130.dp)
                .background(C.Black)
                .tagged("dock-pitch")
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val d = awaitFirstDown(requireUnconsumed = false)
                        fun at(y: Float) = (1f - 2f * y / size.height).coerceIn(-1f, 1f)
                        d.consume()
                        state.pitchWheel(at(d.position.y), true)
                        while (true) {
                            val e = awaitPointerEvent()
                            val c = e.changes.firstOrNull { it.id == d.id } ?: break
                            if (!c.pressed) { c.consume(); break }
                            state.pitchWheel(at(c.position.y), true)
                            c.consume()
                        }
                        // Spring goes home; Stay settles on the nearest note in the key from where you let go
                        state.pitchWheel(state.wheelAt, false)
                    }
                },
        ) {
            val mid = size.height / 2
            drawLine(C.Dim, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 2.dp.toPx())
            val y = mid - v * mid
            drawRect(C.Yellow.copy(alpha = .85f), topLeft = Offset(0f, minOf(y, mid)), size = Size(size.width, kotlin.math.abs(y - mid)))
            drawRect(C.Cream, topLeft = Offset(2f, y - 5.dp.toPx()), size = Size(size.width - 4f, 10.dp.toPx()))
            drawRect(C.Cream, style = Stroke(2.dp.toPx()))
        }
        DockWord("±${state.wheelRange}", C.Yellow, "dock-pitch-range") { state.cycleWheelRange() }
        DockWord(if (state.wheelAll) "ALL" else "PAD", C.Dim, "dock-pitch-what") { state.switchWheelAll() }
        DockWord(if (state.wheelStay) "STAY" else "SPRING", if (state.wheelStay) C.Teal else C.Dim, "dock-pitch-stay") { state.switchWheelStay() }
    }
}

/** Two upright sliders: cutoff (top is open) and resonance (top is most). They spring back unless Stay. */
@Composable
private fun DockFilter(state: AppState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            UpSlider(state.dockCut, C.Teal, "dock-cut") { v, on -> if (on) state.midiAim(com.tideclock.messmachine.MidiTarget("dock", 0, 0, "cut")); if (v != null) state.dockCut = v; state.dockFilter(on) }
            UpSlider(state.dockRes, C.Pink, "dock-res") { v, on -> if (on) state.midiAim(com.tideclock.messmachine.MidiTarget("dock", 0, 0, "res")); if (v != null) state.dockRes = v; state.dockFilter(on) }
        }
        Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            T("C", size = 9.sp, color = C.Teal, family = Mono)
            T("R", size = 9.sp, color = C.Pink, family = Mono)
        }
        DockWord(if (state.dockTarget < 0) "MIX" else "P${state.dockTarget + 1}", C.Yellow, "dock-target") { state.cycleDockTarget() }
        DockWord(if (state.dockStay) "STAY" else "SPRING", if (state.dockStay) C.Teal else C.Dim, "dock-stay") { state.dockStay = !state.dockStay; if (!state.dockStay) state.dockFilter(false) }
    }
}

@Composable
private fun UpSlider(value: Float, color: androidx.compose.ui.graphics.Color, tag: String, onChange: (Float?, Boolean) -> Unit) {
    Canvas(
        Modifier
            .size(width = 20.dp, height = 150.dp)
            .background(C.Black)
            .tagged(tag)
            .pointerInput(tag) {
                awaitEachGesture {
                    val d = awaitFirstDown(requireUnconsumed = false)
                    fun at(y: Float) = (1f - y / size.height).coerceIn(0f, 1f)
                    d.consume()
                    onChange(at(d.position.y), true)
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == d.id } ?: break
                        if (!c.pressed) { c.consume(); break }
                        onChange(at(c.position.y), true)
                        c.consume()
                    }
                    onChange(null, false)
                }
            },
    ) {
        val h = size.height * value
        drawRect(color.copy(alpha = .85f), topLeft = Offset(0f, size.height - h), size = Size(size.width, h))
        drawRect(C.Cream, style = Stroke(2.dp.toPx()))
    }
}

/**
 * Repeat's settings as one small panel: three rows of choices (how often, what it plays, how the repeats
 * sound) in place of a list of eleven rows. Picking one closes it, as before.
 */
@Composable
private fun RepeatMenu(state: AppState, open: Boolean, onClose: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val lv = state.liveV
    DropdownMenu(expanded = open, onDismissRequest = onClose, modifier = Modifier.background(C.Ink)) {
        Column(Modifier.width(268.dp).padding(horizontal = 12.dp, vertical = 4.dp)) {
            T("REPEAT", size = 12.sp, color = C.Yellow, lines = 1, spacing = .06.em, modifier = Modifier.padding(bottom = 8.dp))
            T("EVERY", size = 10.sp, color = C.Dim, spacing = .12.em, modifier = Modifier.padding(bottom = 4.dp))
            Wrap(Modifier.padding(bottom = 10.dp).testTag("rate-chips"), gap = 6.dp) {
                val (rb, rm) = repeatParts(state.live.nrRate)
                for ((r, n, tag) in listOf(Triple(4.0, "1/4", "rate-r4"), Triple(2.0, "1/8", "rate-r2"), Triple(1.0, "1/16", "rate-r1"), Triple(.5, "1/32", "rate-r.5"))) {
                    Btn(n, { onClose(); state.setLive { nrRate = r * rm } }, on = rb == r, onColor = C.Pink, small = true, tag = tag)
                }
            }
            // straight, triplet or dotted (note #18): the same note, a different feel
            Wrap(Modifier.padding(bottom = 10.dp).testTag("feel-chips"), gap = 6.dp) {
                val (rb, rm) = repeatParts(state.live.nrRate)
                for ((m, n, tag) in listOf(Triple(1.0, "Straight", "feel-straight"), Triple(2.0 / 3, "Triplet", "feel-trip"), Triple(1.5, "Dotted", "feel-dot"))) {
                    Btn(n, { onClose(); state.setLive { nrRate = rb * m } }, on = kotlin.math.abs(rm - m) < 1e-6, onColor = C.Yellow, small = true, tag = tag)
                }
            }
            T("PLAYS", size = 10.sp, color = C.Dim, spacing = .12.em, modifier = Modifier.padding(bottom = 4.dp))
            Wrap(Modifier.padding(bottom = 10.dp).testTag("arp-chips"), gap = 6.dp) {
                for ((a, n) in listOf("rep" to "Repeat", "up" to "Up", "down" to "Down", "updown" to "Up-down", "rand" to "Random")) {
                    Btn(n, { onClose(); state.setLive { arp = a; if (a != "rep") nr = true } }, on = state.live.arp == a, onColor = C.Teal, small = true, tag = "rate-a$a")
                }
            }
            T("EACH REPEAT", size = 10.sp, color = C.Dim, spacing = .12.em, modifier = Modifier.padding(bottom = 4.dp))
            Wrap(Modifier.padding(bottom = 6.dp).testTag("style-chips"), gap = 6.dp) {
                for ((st, n) in listOf("cut" to "Cut", "ring" to "Ring", "flow" to "Flow")) {
                    Btn(n, { onClose(); state.setLive { nrStyle = st } }, on = state.live.nrStyle == st, onColor = C.Yellow, small = true, tag = "rate-s$st")
                }
            }
            RepeatPatternPicker(state)
        }
    }
}

/** Ready patterns for Repeat: x plays, . rests. */
val REPEAT_PATTERNS = listOf(
    "" to "Every one", "xx." to "2 on, 1 off", "x." to "On, off", "xxx." to "3 on, 1 off",
    "x.." to "1 on, 2 off", "x.xx" to "Gallop", "x..x..x." to "Tresillo", "x..x..x...x.x..." to "Clave",
)

/**
 * Repeat's pattern: which repeats play and which rest, so Repeat (and anything Hold keeps going) can go
 * hit, hit, skip. Tap a step in the strip to flip it; that makes it your own pattern, kept for next time.
 */
@Composable
fun RepeatPatternPicker(state: AppState) {
    @Suppress("UNUSED_VARIABLE") val lv = state.liveV
    val cur = state.live.nrPat
    T("PATTERN", size = 10.sp, color = C.Dim, spacing = .12.em, modifier = Modifier.padding(bottom = 4.dp))
    Wrap(Modifier.padding(bottom = 8.dp).testTag("pattern-chips"), gap = 6.dp) {
        for ((pt, n) in REPEAT_PATTERNS) {
            val code = pt.replace('.', 'o').ifEmpty { "all" }
            Btn(n, { state.setRepeatPattern(pt) }, on = cur == pt, onColor = C.Teal, small = true, tag = "pat-$code")
        }
        if (REPEAT_PATTERNS.none { it.first == state.ownPattern }) Btn("Mine", { state.setRepeatPattern(state.ownPattern) }, on = cur == state.ownPattern, onColor = C.Teal, small = true, tag = "pat-mine")
    }
    // the strip shows the pattern playing (or yours, when every repeat plays); tap a step to flip it
    val shown = cur.ifEmpty { state.ownPattern }
    Canvas(
        Modifier.fillMaxWidth().height(30.dp).background(C.Black).tagged("pattern-strip")
            .pointerInput(shown) {
                awaitEachGesture {
                    val d = awaitFirstDown(requireUnconsumed = false)
                    d.consume()
                    val i = (d.position.x / (size.width / shown.length.toFloat())).toInt().coerceIn(0, shown.length - 1)
                    val flipped = shown.toCharArray().also { it[i] = if (it[i] == 'x') '.' else 'x' }.concatToString()
                    state.editOwnPattern(flipped)
                }
            },
    ) {
        val w = size.width / shown.length
        for ((i, ch) in shown.withIndex()) {
            val on = ch == 'x'
            val c = if (cur.isEmpty()) C.Dim else if (on) C.Teal else C.Card2
            drawRect(c, topLeft = Offset(i * w + 2f, if (on) 3f else size.height * .55f), size = Size(w - 4f, if (on) size.height - 6f else size.height * .45f - 3f))
        }
    }
    Row(Modifier.padding(top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Btn("−", { state.editOwnPattern(shown.dropLast(1)) }, small = true, enabled = shown.length > 2, tag = "pat-shorter")
        T("${shown.length} STEPS", size = 10.sp, color = C.Dim, family = Mono)
        Btn("+", { state.editOwnPattern(shown + "x") }, small = true, enabled = shown.length < 16, tag = "pat-longer")
    }
}


/** The dock filter's settings in the tray: what it filters, and Stay or Spring. */
@Composable
fun DockFilterTray(state: AppState) {
    Wrap(gap = 6.dp) {
        Btn(if (state.dockTarget < 0) "Filters the mix" else "Filters pad ${state.dockTarget + 1}", { state.cycleDockTarget() }, small = true, tag = "tray-dock-target")
        Btn(if (state.dockStay) "Stay" else "Spring", { state.dockStay = !state.dockStay; if (!state.dockStay) state.dockFilter(false) }, on = state.dockStay, onColor = C.Teal, small = true, tag = "tray-dock-stay")
    }
}
