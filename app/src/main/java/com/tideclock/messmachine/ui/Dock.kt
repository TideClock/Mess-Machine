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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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

@Composable
private fun DockItem(state: AppState, t: String) {
    // Repeat and Rec live outside the mess: read their change counter so the dock lights up with them (note #7)
    @Suppress("UNUSED_VARIABLE") val lv = state.liveV
    when (t) {
        "lock" -> LockButton(state)
        "hold" -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            DockBtn("H", { state.switchHold() }, tag = "dock-hold", on = state.holdOn)
            T(if (state.holdCount == 0) "LAST" else if (state.holdOn) "${state.latched.size}/${state.holdCount}" else "${state.holdCount}", size = 10.sp, color = if (state.holdOn) C.Yellow else C.Dim, family = Mono,
                modifier = Modifier.padding(top = 3.dp).clickable { state.changeHoldCount(if (state.holdCount >= 8) 0 else state.holdCount + 1) }.tagged("dock-hold-n"))
        }
        "filter" -> DockFilter(state)
        "repeat" -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Repeat's one home: tap RPT to turn it on, tap the rate under it for 1/8, 1/16, 1/32 or an arp
            var rates by remember { mutableStateOf(false) }
            DockBtn("RPT", { state.setLive { nr = !nr } }, tag = "dock-repeat", on = state.live.nr, onColor = C.Pink)
            Box {
                T(
                    (when (state.live.nrRate) { 2.0 -> "1/8"; 0.5 -> "1/32"; else -> "1/16" }) + (if (state.live.arp != "rep") " " + arpShort(state.live.arp) else "") + styleShort(state.live.nrStyle) + (if (state.live.nrPat.isNotEmpty()) " P" else ""),
                    size = 10.sp, color = if (state.live.nr) C.Pink else C.Dim, family = Mono, lines = 1,
                    modifier = Modifier.padding(top = 3.dp).clickable { rates = true }.tagged("dock-rate"),
                )
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
            T(if (state.mouthOn && state.mouthHeard) "WUB" else "SET", size = 9.sp, color = if (state.mouthOn) C.Pink else C.Dim, family = Mono,
                modifier = Modifier.padding(top = 3.dp).clickable { state.overlay = com.tideclock.messmachine.Overlay.Mouth }.tagged("dock-mouth-set"))
        }
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

/** Two upright sliders: cutoff (top is open) and resonance (top is most). They spring back unless Stay. */
@Composable
private fun DockFilter(state: AppState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            UpSlider(state.dockCut, C.Teal, "dock-cut") { v, on -> if (v != null) state.dockCut = v; state.dockFilter(on) }
            UpSlider(state.dockRes, C.Pink, "dock-res") { v, on -> if (v != null) state.dockRes = v; state.dockFilter(on) }
        }
        Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            T("C", size = 9.sp, color = C.Teal, family = Mono)
            T("R", size = 9.sp, color = C.Pink, family = Mono)
        }
        T(if (state.dockTarget < 0) "MIX" else "P${state.dockTarget + 1}", size = 10.sp, color = C.Yellow, family = Mono, weight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp).clickable { state.cycleDockTarget() }.tagged("dock-target"))
        T(if (state.dockStay) "STAY" else "SPRING", size = 9.sp, color = if (state.dockStay) C.Teal else C.Dim, family = Mono,
            modifier = Modifier.padding(top = 3.dp).clickable { state.dockStay = !state.dockStay; if (!state.dockStay) state.dockFilter(false) }.tagged("dock-stay"))
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
                for ((r, n, tag) in listOf(Triple(2.0, "1/8", "rate-r2"), Triple(1.0, "1/16", "rate-r1"), Triple(.5, "1/32", "rate-r.5"))) {
                    Btn(n, { onClose(); state.setLive { nrRate = r } }, on = state.live.nrRate == r, onColor = C.Pink, small = true, tag = tag)
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
            Note("Cut stops each repeat at the next. Ring lets them play out. Flow holds the note and pulses it.", modifier = Modifier.padding(bottom = 10.dp))
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
    Note("Each step is one repeat. With Hold on, held notes follow the pattern too.", modifier = Modifier.padding(bottom = 6.dp))
}
