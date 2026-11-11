package com.tideclock.messmachine.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tideclock.messmachine.AppState
import com.tideclock.messmachine.Tutorials
import kotlinx.coroutines.delay

/** Where every tagged control sits on screen right now, so a lesson can ring the one it means. */
object TutHi {
    val rects = HashMap<String, Rect>()
    /** Which node last placed each tag: a node going away only clears its own record, never a newer copy's. */
    internal val owners = HashMap<String, Any>()
    internal fun forget(t: String, who: Any) { if (owners[t] === who) { owners.remove(t); rects.remove(t) } }
}

private class TutNode(var t: String) : Modifier.Node(), GlobalPositionAwareModifierNode {
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) { TutHi.owners[t] = this; TutHi.rects[t] = coordinates.boundsInRoot() }
    override fun onDetach() { TutHi.forget(t, this) }
}

private data class TutElement(val t: String) : ModifierNodeElement<TutNode>() {
    override fun create() = TutNode(t)
    override fun update(node: TutNode) { if (node.t != t) { TutHi.forget(node.t, node); node.t = t } }
}

/** A test tag that lessons can also find on screen. Every control in the app is tagged with this. */
fun Modifier.tagged(t: String): Modifier = this.testTag(t).then(TutElement(t))

/**
 * The lesson on top of everything: a pulsing ring round the control the step means (taps go straight through to
 * it) and a card with the step, on the half of the screen away from the ring.
 */
@Composable
fun BoxScope.TutorOverlay(state: AppState) {
    val t = state.tut ?: return
    val step = t.steps.getOrNull(state.tutStep) ?: return
    var me by remember { mutableStateOf(Rect.Zero) }
    var ring by remember { mutableStateOf<Rect?>(null) }
    val target = remember(t.id, state.tutStep, state.version, state.tab, state.overlay) { try { step.target(state) } catch (_: Exception) { null } }
    LaunchedEffect(t.id, state.tutStep, target) {
        do {
            state.tutTick()
            ring = target?.let { TutHi.rects[it] }?.takeIf { it.width > 1f && it.height > 1f }
            if (!UiFlags.testing) delay(150)
        } while (!UiFlags.testing)
    }
    val pulse by rememberInfiniteTransition(label = "tut").animateFloat(0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "tutp")
    Box(Modifier.fillMaxSize().onGloballyPositioned { me = it.boundsInRoot() }) {
        val r = ring
        if (r != null) Canvas(Modifier.fillMaxSize()) {
            val pad = 6.dp.toPx() + pulse * 4.dp.toPx()
            drawRoundRect(C.Yellow, Offset(r.left - me.left - pad, r.top - me.top - pad), Size(r.width + pad * 2, r.height + pad * 2),
                CornerRadius(6.dp.toPx()), style = Stroke(3.dp.toPx() + pulse * 2.dp.toPx()))
        }
        val low = r != null && (r.center.y - me.top) > (me.height * .55f)
        Column(
            Modifier.align(if (low) Alignment.TopCenter else Alignment.BottomCenter).fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = if (low) 64.dp else 12.dp)
                .background(C.Ink).border(3.dp, C.Yellow).padding(12.dp).tagged("tut-card"),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(t.title.uppercase(), size = 12.sp, color = C.Yellow, spacing = .1.em, modifier = Modifier.weight(1f))
                T("${state.tutStep + 1} of ${t.steps.size}", size = 12.sp, color = C.Dim, family = Mono)
            }
            T(step.text, size = 15.sp, color = C.Cream, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp).tagged("tut-text"))
            if (target != null && r == null) Note("It's on another page or off screen: scroll or switch to find it.", modifier = Modifier.padding(bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.tutStep > 0) Btn("Back", { state.tutBack() }, small = true, tier = Tier.Quiet, tag = "tut-back")
                Btn("Skip step", { state.tutSkip() }, small = true, tier = Tier.Quiet, tag = "tut-skip")
                Btn("Stop lesson", { state.endTutorial(false) }, small = true, tag = "tut-stop")
            }
        }
    }
}

/** The lessons, by group. Each runs on a practice mess; yours comes back when it ends. */
@Composable
fun LearnScreen(state: AppState) {
    ScrollPane(hPad = 14.dp, vPad = 0.dp) {
        Note("Each lesson points at one control at a time and moves on when you've done it. It uses a practice mess; yours comes back when the lesson ends.", modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
        for (g in Tutorials.all.map { it.group }.distinct()) {
            T(g.uppercase(), size = 11.sp, color = C.Dim, spacing = .1.em, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
            for (t in Tutorials.all.filter { it.group == g }) {
                val done = t.id in state.tutsDone
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).background(C.Card).padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        T((if (done) "✓ " else "") + t.title, size = 15.sp, color = if (done) C.Teal else C.Cream)
                        T(t.blurb + " " + t.steps.size + " steps.", size = 12.sp, color = C.Dim)
                    }
                    Btn(if (done) "Again" else "Start", { state.startTutorial(t) }, small = true, on = !done, onColor = C.Yellow, tag = "learn-${t.id}")
                }
            }
        }
        T(" ", size = 30.sp)
    }
}
