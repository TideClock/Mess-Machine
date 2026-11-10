package com.tideclock.messmachine.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.em
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tideclock.messmachine.AppState
import com.tideclock.messmachine.engine.Project
import com.tideclock.messmachine.engine.Theory

@Composable
fun RollScreen(state: AppState) {
    val P = state.project
    val pat = P.pat()
    val k = state.selKey()
    val loaded = (0 until Project.NPADS).filter { P.kit[it].isNotEmpty() }
    ScrollPane {
        SlotBar(state)
        if (k.isNotEmpty()) Row(Modifier.padding(bottom = 8.dp).tapOrHold(P.sel, { state.preview(k) }, { if (!state.locked) state.padMenu = P.sel }).testTag("roll-name"), verticalAlignment = Alignment.CenterVertically) {
            Swatch(padColor(Color(state.lib.colorOf(k))))
            T("  PAD ${P.sel + 1} · ${state.lib.titleOf(k).uppercase()}", size = 13.sp, color = C.Yellow, lines = 1, spacing = .04.em)
            Box { PadMenu(state, P.sel) }
        }
        Wrap(Modifier.padding(bottom = 10.dp)) {
            Select(
                if (k.isEmpty()) "Pick a pad" else "Pad ${P.sel + 1} · ${state.lib.titleOf(k)}",
                loaded.map { it to "Pad ${it + 1} · ${state.lib.titleOf(P.kit[it])}" },
                { state.selectPad(it, true); state.bank = it / Project.BANK },
                color = C.Teal,
            )
            Btn("✂", { state.overlay = com.tideclock.messmachine.Overlay.Doctor(k) }, small = true, enabled = k.isNotEmpty())
            Select(Theory.NN[P.root], Theory.NN.mapIndexed { i, n -> i to n }, { state.setRoot(it) })
            Select(P.scale, Theory.SCALES.keys.map { it to it }, { state.setScale(it) })
        }
        Wrap(Modifier.padding(bottom = 10.dp)) {
            Select(chordName(state.chordMode), chordOptions(P.scale, P.root), { state.pickChordMode(it) })
            Btn(if (P.fold) "Unfold" else "Fold to scale", { state.edit { fold = !fold } }, on = P.fold, small = true)
            Btn("Clear notes", { state.clearNotes() }, small = true, enabled = k.isNotEmpty() && pat.notes(k).isNotEmpty(), tag = "roll-clear")
        }
        Wrap(Modifier.padding(bottom = 12.dp)) {
            T("NOTE", size = 12.sp, color = C.Dim, spacing = .1.em)
            for ((l, name) in listOf(1 to "1 step", 2 to "2 steps", 4 to "1 beat", 8 to "Half a bar", 16 to "Whole bar")) {
                Btn(name, { state.noteLen = l }, on = state.noteLen == l, onColor = C.Teal, small = true)
            }
        }
        if (k.isEmpty()) {
            Note("Pick a pad with a sound on it. Its own pitch sits on C4, the bright row.", color = C.Yellow)
        } else {
            Roll(state)
            Help(
                "Tap a square to drop a note, tap it again to pull it. With chords on, one tap drops the whole chord. Tap a note name to hear it.",
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
fun Roll(state: AppState) {
    val P = state.project
    val pat = P.pat()
    val k = state.selKey()
    val rows = (Theory.ROLL_HI downTo Theory.ROLL_LO).filter { !P.fold || Theory.inScale(it, P.scale, P.root) }
    val off = state.page.coerceIn(0, pat.len / 16 - 1) * 16
    val notes = pat.notes(k).map { Triple(it.s, it.p, it.l) }
    val tm = rememberTextMeasurer()
    val rowH = 30.dp
    val keyW = 46.dp
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(rowH * rows.size)
            .testTag("roll")
            .pointerInput(rows, off, k, P.cur) {
                detectTapGestures { o ->
                    val kw = keyW.toPx()
                    val r = (o.y / rowH.toPx()).toInt().coerceIn(0, rows.size - 1)
                    val p = rows[r]
                    if (o.x < kw) { state.audition(intArrayOf(p)); return@detectTapGestures }
                    val cw = (size.width - kw) / 16f
                    val st = off + ((o.x - kw) / cw).toInt().coerceIn(0, 15)
                    state.rollTap(p, st)
                }
            },
    ) {
        val kw = keyW.toPx()
        val rh = rowH.toPx()
        val cw = (size.width - kw) / 16f
        val col0 = Color(state.lib.colorOf(k))
        rows.forEachIndexed { r, p ->
            val y = r * rh
            val ins = Theory.inScale(p, P.scale, P.root)
            val black = Theory.pcOf(p) in intArrayOf(1, 3, 6, 8, 10)
            val bg = when {
                p == 0 -> lerp(C.Ink, C.Yellow, .22f)
                !ins -> C.Paper
                else -> lerp(C.Ink, pcColor(p - P.root), if (black) .07f else .13f)
            }
            drawRect(bg, topLeft = Offset(kw, y), size = Size(size.width - kw, rh))
            drawRect(if (black) C.Ink else lerp(C.Cream, pcColor(p - P.root), .25f), topLeft = Offset(0f, y), size = Size(kw - 2, rh - 1))
            if (Theory.pcOf(p) == 0 || p == Theory.ROLL_HI || P.fold) {
                drawText(tm, Theory.noteName(p), topLeft = Offset(5f, y + rh * .22f),
                    style = TextStyle(color = if (black) C.Cream else C.Ink, fontSize = 11.sp, fontFamily = Archivo, fontWeight = FontWeight.ExtraBold))
            }
            drawLine(C.Black, Offset(0f, y + rh), Offset(size.width, y + rh), strokeWidth = 1f)
        }
        for (c in 0..16) {
            val x = kw + c * cw
            drawLine(if (c % 4 == 0) C.Black else C.Black.copy(alpha = .45f), Offset(x, 0f), Offset(x, size.height), strokeWidth = if (c % 4 == 0) 3f else 1f)
        }
        val playStep = if (state.playing && state.heardPat == P.cur) state.heardStep else -1
        if (playStep in off until off + 16) {
            drawRect(C.Pink.copy(alpha = .22f), topLeft = Offset(kw + (playStep - off) * cw, 0f), size = Size(cw, size.height))
            drawRect(C.Yellow, topLeft = Offset(kw + (playStep - off) * cw, 0f), size = Size(3f, size.height))
        }
        for ((s, p, l) in notes) {
            val r = rows.indexOf(p)
            if (r < 0) continue
            val a = maxOf(s, off) - off
            val z = minOf(s + l, off + 16) - off
            if (z <= 0 || a >= 16) continue
            val c = lerp(pcColor(p - P.root), col0, .15f)
            val tl = Offset(kw + a * cw + 2, r * rh + 3)
            val sz = Size((z - a) * cw - 4, rh - 6)
            drawRoundRect(c, topLeft = tl, size = sz, cornerRadius = CornerRadius(8f))
            drawRoundRect(C.Cream, topLeft = tl, size = sz, cornerRadius = CornerRadius(8f), style = Stroke(width = 3f))
        }
    }
}
