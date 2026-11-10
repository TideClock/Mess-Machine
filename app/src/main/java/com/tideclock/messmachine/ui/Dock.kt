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
        Box(
            Modifier.width(16.dp).fillMaxHeight().background(C.Ink).clickable { state.switchDockOpen(true) }.testTag("dock-open"),
            contentAlignment = Alignment.Center,
        ) { T("▶", size = 10.sp, color = C.Pink) }
        return
    }
    Column(
        Modifier.width(62.dp).fillMaxHeight().background(C.Ink).drawBehind {
            drawLine(C.Pink, Offset(size.width, 0f), Offset(size.width, size.height), strokeWidth = 3.dp.toPx())
        }.verticalScroll(rememberScrollState()).padding(start = 5.dp, end = 8.dp, top = 8.dp, bottom = 8.dp).testTag("dock"),
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
                        modifier = Modifier.testTag("dock-pick-$id"),
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
private fun DockBtn(label: String, onClick: () -> Unit, tag: String, on: Boolean = false, color: androidx.compose.ui.graphics.Color = C.Ink, onColor: androidx.compose.ui.graphics.Color = C.Yellow, enabled: Boolean = true) {
    Btn(label, onClick, modifier = Modifier.fillMaxWidth(), small = true, on = on, color = color, onColor = onColor, enabled = enabled, tag = tag)
}

@Composable
private fun DockItem(state: AppState, t: String) {
    when (t) {
        "lock" -> LockButton(state)
        "hold" -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            DockBtn("H", { state.switchHold() }, tag = "dock-hold", on = state.holdOn)
            T(if (state.holdCount == 0) "LAST" else if (state.holdOn) "${state.latched.size}/${state.holdCount}" else "${state.holdCount}", size = 10.sp, color = if (state.holdOn) C.Yellow else C.Dim, family = Mono,
                modifier = Modifier.padding(top = 3.dp).clickable { state.changeHoldCount(if (state.holdCount >= 8) 0 else state.holdCount + 1) }.testTag("dock-hold-n"))
        }
        "filter" -> DockFilter(state)
        "repeat" -> DockBtn("RPT", { state.setLive { nr = !nr } }, tag = "dock-repeat", on = state.live.nr, onColor = C.Pink)
        "hush" -> DockBtn("✕", { state.hush() }, tag = "dock-hush", color = C.Riot)
        "rec" -> DockBtn("●", { state.setLive { rec = !rec } }, tag = "dock-rec", on = state.live.rec, onColor = C.Riot)
        "beat" -> BeatLight(state, beatPulse(state))
        "stutter", "echo" -> FxButton(state, t, if (t == "echo") "ECHO" else "STUT", Modifier.fillMaxWidth().testTag("dock-$t"), if (t == "echo") C.Teal else C.Pink, height = 46.dp)
        "desk" -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            DockBtn("DSK", { state.nextDesk() }, tag = "dock-desk", onColor = C.Teal)
            T(state.desks.getOrNull(state.deskIdx)?.name?.uppercase()?.take(6) ?: "", size = 9.sp, color = C.Teal, family = Mono, modifier = Modifier.padding(top = 3.dp))
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
            .testTag("dock-lock")
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
            modifier = Modifier.padding(top = 4.dp).clickable { state.cycleDockTarget() }.testTag("dock-target"))
        T(if (state.dockStay) "STAY" else "SPRING", size = 9.sp, color = if (state.dockStay) C.Teal else C.Dim, family = Mono,
            modifier = Modifier.padding(top = 3.dp).clickable { state.dockStay = !state.dockStay; if (!state.dockStay) state.dockFilter(false) }.testTag("dock-stay"))
    }
}

@Composable
private fun UpSlider(value: Float, color: androidx.compose.ui.graphics.Color, tag: String, onChange: (Float?, Boolean) -> Unit) {
    Canvas(
        Modifier
            .size(width = 20.dp, height = 150.dp)
            .background(C.Black)
            .testTag(tag)
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
