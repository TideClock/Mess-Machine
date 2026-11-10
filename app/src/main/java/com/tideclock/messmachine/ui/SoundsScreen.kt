package com.tideclock.messmachine.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tideclock.messmachine.AppState
import com.tideclock.messmachine.R
import com.tideclock.messmachine.engine.Library

fun jarArt(cab: String): Int = when (cab) {
    "thumps" -> R.drawable.jar_thumps
    "bodily" -> R.drawable.jar_bodily
    "machines" -> R.drawable.jar_machines
    "tones" -> R.drawable.jar_tones
    "goo" -> R.drawable.jar_goo
    "bw" -> R.drawable.jar_bw
    "qbldy" -> R.drawable.jar_qbldy
    else -> R.drawable.jar_heap
}

private var lastCab = "thumps"

/** The jars. pickFor = a pad number when choosing a sound for that pad, null when just browsing. */
@Composable
fun SoundsScreen(state: AppState, pickFor: Int?) {
    val P = state.project
    var cab by remember { mutableStateOf(lastCab) }
    var q by remember { mutableStateOf("") }
    val target = pickFor ?: P.sel
    val list: List<Library.Sound> = if (q.isBlank()) state.lib.inCab(cab)
    else state.lib.sounds.filter { it.title.contains(q.trim(), ignoreCase = true) || it.group.contains(q.trim(), ignoreCase = true) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (c in state.lib.cabs) {
                val on = c.id == cab && q.isBlank()
                Column(
                    Modifier
                        .width(86.dp)
                        .sticker(if (on) Color(c.color) else C.Card, radius = 12.dp, shadow = if (on) 1.dp else 3.dp)
                        .clickable { cab = c.id; lastCab = c.id; q = "" }
                        .padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(painterResource(jarArt(c.id)), contentDescription = null, modifier = Modifier.height(64.dp), contentScale = ContentScale.Fit)
                    T(c.name, size = 10.sp, color = if (on) ink(Color(c.color)) else C.Cream, lines = 2)
                }
            }
        }
        OutlinedTextField(
            value = q, onValueChange = { q = it }, singleLine = true,
            placeholder = { T("Search every jar", color = C.Dim, weight = FontWeight.Medium) },
            textStyle = TextStyle(color = C.Cream, fontFamily = Archivo, fontSize = 15.sp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.Pink, unfocusedBorderColor = C.Card2, cursorColor = C.Pink),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        T(
            "Tap a sound to hear it. The button puts it on pad ${target + 1}.",
            size = 12.sp, color = C.Dim, weight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            var lastGroup = ""
            val rows = ArrayList<Pair<String?, Library.Sound>>()
            for (s in list) {
                val g = if (q.isBlank()) s.group else state.lib.cab(s.cab).name
                rows.add((if (g != lastGroup && g.isNotEmpty()) g else null) to s)
                lastGroup = g
            }
            items(rows, key = { it.second.key }) { (head, s) ->
                Column(Modifier.padding(horizontal = 12.dp)) {
                    if (head != null) T(head + (if (s.album.isNotEmpty() && q.isBlank()) " · ${s.album}" else ""), size = 13.sp, color = C.Yellow, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
                    SoundRow(state, s, target, pickFor != null)
                }
            }
        }
    }
}

@Composable
fun SoundRow(state: AppState, s: Library.Sound, target: Int, picking: Boolean) {
    val P = state.project
    val onPad = P.padOf(s.key)
    val col = Color(state.lib.cab(s.cab).color)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .sticker(C.Card, radius = 12.dp, shadow = 2.dp)
            .clickable { state.preview(s.key) }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 8.dp, height = 34.dp).background(col, RoundedCornerShape(4.dp)))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            T(s.title, size = 15.sp, lines = 1)
            if (onPad >= 0) T("on pad ${onPad + 1}", size = 11.sp, color = C.Teal, weight = FontWeight.Medium)
        }
        if (onPad == target) T("here", size = 12.sp, color = C.Dim)
        else Btn("Pad ${target + 1}", {
            state.putOnPad(target, s.key)
            if (picking) state.overlay = null
        }, small = true, color = C.Pink)
    }
}
