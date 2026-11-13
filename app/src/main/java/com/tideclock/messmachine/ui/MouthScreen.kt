package com.tideclock.messmachine.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tideclock.messmachine.AppState
import com.tideclock.messmachine.XY_TARGETS
import com.tideclock.messmachine.xyTarget

/**
 * Mouth control: the mic as a hands-free XY for the pad you picked. The vowel (dark "ooo" to bright "eee") moves
 * one thing, how loud you are moves another. With Rec on while the loop plays, it records like a finger on XY.
 */
@Composable
fun MouthScreen(state: AppState) {
    val P = state.project
    val pad = P.sel
    val k = P.kit.getOrNull(pad).orEmpty()
    ScrollPane(hPad = 14.dp, vPad = 0.dp) {
        Note("Make a sound and your mouth moves the pad you picked: \"ooo\" is dark, \"eee\" is bright. Say \"wow wow wow\" over a held synth for a wub.", modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn(if (state.mouthOn) "● Listening" else "○ Listen", { state.switchMouth() }, on = state.mouthOn, onColor = C.Pink, tag = "mouth-toggle")
            Note(if (k.isEmpty()) "Pick a pad with a sound first." else "Moves pad ${pad + 1}, ${state.lib.titleOf(k).lowercase()}", modifier = Modifier.weight(1f))
        }
        MouthMeter(state)
        T("THE VOWEL MOVES", size = 11.sp, color = C.Dim, spacing = .1.em, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        Select(xyTarget(state.mouthVowelTo).name, XY_TARGETS.map { it.id to it.name }, { state.pickMouthVowel(it) }, tag = "mouth-vowel-to")
        T("LOUDNESS MOVES", size = 11.sp, color = C.Dim, spacing = .1.em, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
        Select(xyTarget(state.mouthLevelTo).name, XY_TARGETS.map { it.id to it.name }, { state.pickMouthLevel(it) }, tag = "mouth-level-to")
        T("WHEN YOU GO QUIET", size = 11.sp, color = C.Dim, spacing = .1.em, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
        Wrap(Modifier.tagged("mouth-quiet-chips"), gap = 6.dp) {
            Btn("Stay put", { state.switchMouthStay(true) }, on = state.mouthStay, onColor = C.Teal, small = true, tag = "mouth-stay")
            Btn("Spring back", { state.switchMouthStay(false) }, on = !state.mouthStay, onColor = C.Teal, small = true, tag = "mouth-letgo")
        }
        SliderRow("Sensitivity", -state.mouthGate, 20f..70f, { "quiet under -${it.toInt()} dB" }, { state.changeMouthGate(-it) }, {}, accent = C.Pink)
        Help("Headphones work best: on the speaker the mic hears the music too, and the music would move things. Turn Sensitivity down if it moves while you're silent. With Rec on while the loop plays, what your mouth does lands in the loop as XY moves (Clear moves takes it out). Nothing you say is kept: only the two numbers.", modifier = Modifier.padding(top = 8.dp))
        T(" ", size = 30.sp)
    }
}

/** What the mic hears right now: a dot for vowel (across) and loudness (up), lit while you make a sound. */
@Composable
fun MouthMeter(state: AppState) {
    val (vw, lv, on) = state.mouthNow
    Canvas(Modifier.fillMaxWidth().height(120.dp).padding(end = 6.dp).sticker(C.Card2).tagged("mouth-meter")) {
        for (i in 1..3) drawLine(C.Ink, Offset(size.width * i / 4, 0f), Offset(size.width * i / 4, size.height), strokeWidth = 1.5f)
        if (!state.mouthOn) return@Canvas
        val c = Offset(vw * size.width, (1 - lv) * size.height)
        drawCircle(if (on) C.Pink else C.Dim, radius = 16.dp.toPx(), center = c)
        drawCircle(C.Cream, radius = 16.dp.toPx(), center = c, style = Stroke(3.dp.toPx()))
        // the gate line: under it is quiet
        drawRect(C.Black.copy(alpha = .35f), topLeft = Offset(0f, size.height * .92f), size = Size(size.width, size.height * .08f))
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, end = 6.dp)) {
        T("OOO", size = 10.sp, color = C.Dim, spacing = .1.em, modifier = Modifier.weight(1f))
        T("AAH", size = 10.sp, color = C.Dim, spacing = .1.em, modifier = Modifier.weight(1f))
        T("EEE", size = 10.sp, color = C.Dim, spacing = .1.em)
    }
}
