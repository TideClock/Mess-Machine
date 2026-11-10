package com.tideclock.messmachine.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.tideclock.messmachine.AppState
import com.tideclock.messmachine.R
import com.tideclock.messmachine.engine.Library
import com.tideclock.messmachine.engine.Project

fun jarArt(cab: String): Int = when (cab) {
    "thumps" -> R.drawable.jar_thumps
    "bodily" -> R.drawable.jar_bodily
    "machines" -> R.drawable.jar_machines
    "tones" -> R.drawable.jar_tones
    "goo" -> R.drawable.jar_goo
    "bw" -> R.drawable.jar_bw
    "qbldy" -> R.drawable.jar_qbldy
    "mine" -> R.drawable.jar_mine
    else -> R.drawable.jar_heap
}

/** The jars. pickFor = a pad number when choosing a sound for that pad, null when just browsing. */
@Composable
fun SoundsScreen(state: AppState, pickFor: Int?) {
    val P = state.project
    val cab = state.soundsCab
    var q by remember { mutableStateOf("") }
    val target = pickFor ?: P.sel
    @Suppress("UNUSED_VARIABLE") val bv = state.bench.items.size
    val ctx = LocalContext.current
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> state.importSounds(uris) }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) state.toggleMic() else state.say("The mic needs permission. You can allow it in the phone's settings for Mess Machine.")
    }
    val mode = state.soundsMode
    val list: List<Library.Sound> = when {
        q.isNotBlank() -> state.lib.sounds.filter { it.title.contains(q.trim(), ignoreCase = true) || it.group.contains(q.trim(), ignoreCase = true) }
        mode == "fav" -> state.lib.sounds.filter { it.key in state.favs }
        mode == "type" -> state.lib.sounds.filter { s ->
            (state.soundsType.isEmpty() || s.type == state.soundsType) && state.soundsTags.all { it in s.tags }
        }.sortedBy { s -> Library.TYPES.indexOfFirst { it[0] == s.type }.let { if (it < 0) 99 else it } }
        else -> state.lib.inCab(cab)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Btn("By type", { state.soundsMode = "type"; q = "" }, on = mode == "type", small = true, modifier = Modifier.weight(1f), tag = "mode-type")
            Btn("By jar", { state.soundsMode = "jar"; q = "" }, on = mode == "jar", small = true, modifier = Modifier.weight(1f), tag = "mode-jar")
            Btn("★ Favorites", { state.soundsMode = "fav"; q = "" }, on = mode == "fav", small = true, onColor = C.Pink, modifier = Modifier.weight(1.2f), tag = "mode-fav")
        }
        when (mode) {
            "type" -> {
                Row(Modifier.fadeScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Btn("All", { state.soundsType = "" }, on = state.soundsType.isEmpty(), small = true, tag = "type-all")
                    for (t in Library.TYPES) {
                        val n = state.lib.sounds.count { it.type == t[0] }
                        Btn("${t[1]} $n", { state.soundsType = t[0] }, on = state.soundsType == t[0], small = true, onColor = C.Teal, tag = "type-${t[0]}")
                    }
                }
                Row(Modifier.fadeScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    T("FEEL", size = 11.sp, color = C.Dim, spacing = .1.em)
                    for (qq in Library.QUALITIES) {
                        val on = qq[0] in state.soundsTags
                        Btn(qq[1], { state.soundsTags = if (on) state.soundsTags - qq[0] else state.soundsTags + qq[0] }, on = on, small = true, onColor = C.Pink, tag = "feel-${qq[0]}")
                    }
                }
            }
            "jar" -> Row(Modifier.fadeScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (c in state.lib.cabs) {
                    JarTab(c.id, c.name, Color(c.color), c.id == cab && q.isBlank(), jarArt(c.id)) { state.soundsCab = c.id; q = "" }
                }
            }
            else -> Note("Star a sound with ☆ in any list, or from a pad's hold menu.", color = C.Yellow, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
        }
        OutlinedTextField(
            value = q, onValueChange = { q = it }, singleLine = true,
            placeholder = { Note("Search every jar") },
            textStyle = TextStyle(color = C.Cream, fontFamily = Archivo, fontSize = 15.sp),
            shape = RectangleShape,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.Pink, unfocusedBorderColor = C.Cream, cursorColor = C.Pink),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("search"),
        )
        if (mode == "jar" && cab == "mine" && q.isBlank()) {
            Wrap(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp)) {
                Btn("Import sounds", { importer.launch(arrayOf("audio/*")) }, small = true, color = C.Teal, tag = "import")
                Btn(if (state.micOn) "■ Stop recording" else "● Sample the mic", {
                    if (state.micOn) state.toggleMic()
                    else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) state.toggleMic()
                    else micPerm.launch(Manifest.permission.RECORD_AUDIO)
                }, small = true, on = state.micOn, onColor = C.Riot, tag = "mic")
                Btn("Resample loop ${Project.LETTERS[P.cur]}", { state.resampleLoop() }, small = true, enabled = P.pat().used())
            }
            Note("WAV, MP3, M4A, OGG or FLAC, up to a minute each. Mic takes stop at 30 seconds. Your sounds stay on this phone.", modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
        }
        Note("Tap a sound to hear it. The button puts it on pad ${target + 1}. ☆ stars it.", modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        if (list.isEmpty()) Note(
            when { q.isNotBlank() -> "Nothing in the jars matches that."; mode == "fav" -> "No favorites yet. Tap ☆ on any sound."; mode == "type" -> "Nothing with that type and feel. Turn a feel off."; cab == "mine" -> "Nothing here yet. Import a sound, sample the mic or resample a loop."; else -> "Empty jar." },
            color = C.Yellow, modifier = Modifier.padding(horizontal = 14.dp),
        )
        val ls = rememberLazyListState()
        Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(Modifier.fillMaxSize().padding(end = 14.dp).testTag("sound-list"), state = ls) {
            var lastGroup = ""
            val rows = ArrayList<Pair<String?, Library.Sound>>()
            for (s in list) {
                val g = when {
                    q.isNotBlank() || mode == "fav" -> state.lib.cab(s.cab).name
                    mode == "type" -> Library.TYPES.firstOrNull { it[0] == s.type }?.get(1) ?: "Other"
                    else -> s.group
                }
                rows.add((if (g != lastGroup && g.isNotEmpty()) g else null) to s)
                lastGroup = g
            }
            items(rows, key = { it.second.key }) { (head, s) ->
                Column(Modifier.padding(horizontal = 12.dp)) {
                    if (head != null) T(head.uppercase() + (if (s.album.isNotEmpty() && q.isBlank() && mode == "jar") " · ${s.album}" else ""), size = 12.sp, color = C.Yellow, spacing = .1.em, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
                    SoundRow(state, s, target, pickFor != null)
                }
            }
        }
        LazyScrollBar(ls, list.size, Modifier.align(Alignment.CenterEnd))
        }
    }
}

@Composable
private fun JarTab(id: String, name: String, col: Color, on: Boolean, art: Int?, onClick: () -> Unit) {
    Column(
        Modifier
            .width(104.dp)
            .padding(end = 4.dp, bottom = 4.dp)
            .sticker(if (on) col else C.Ink, shadow = if (on) 2.dp else 4.dp)
            .clickable { onClick() }
            .testTag("jar-$id")
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (art != null) Image(painterResource(art), contentDescription = null, modifier = Modifier.height(64.dp), contentScale = ContentScale.Fit)
        else Box(Modifier.height(64.dp), contentAlignment = Alignment.Center) { T("★", size = 40.sp, color = if (on) C.Ink else C.Yellow) }
        T(name.uppercase(), size = 10.sp, color = if (on) ink(col) else C.Cream, lines = 2)
    }
}

@Composable
fun SoundRow(state: AppState, s: Library.Sound, target: Int, picking: Boolean) {
    val P = state.project
    val onPad = P.padOf(s.key)
    val col = padColor(Color(state.lib.cab(s.cab).color))
    var menu by remember(s.key) { mutableStateOf(false) }
    Column {
        HoldMenu(menu, s.title, { menu = false }, listOfNotNull(
            Triple("Hear it", "rowmenu-hear") { state.preview(s.key) },
            Triple(if (s.key in state.favs) "Unstar" else "Star", "rowmenu-star") { state.toggleFav(s.key) },
            Triple("Put it on pad ${target + 1}", "rowmenu-put") { state.putOnPad(target, s.key); if (picking) state.overlay = null },
            if (onPad >= 0) Triple("Doctor it", "rowmenu-doctor") { state.overlay = com.tideclock.messmachine.Overlay.Doctor(s.key) } else null,
            if (s.cab == "mine") Triple("Share it", "rowmenu-share") { state.shareSoundWav(s.key) } else null,
        ))
        Row(
            Modifier
                .fillMaxWidth()
                .tapOrHold(s.key, { state.preview(s.key) }, { if (!state.locked) menu = true })
                .testTag("row-${s.key}")
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(width = 10.dp, height = 34.dp).background(col))
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                T(s.title, size = 15.sp, lines = 1)
                val info = listOfNotNull(
                    if (onPad >= 0) "on pad ${onPad + 1}" else null,
                    state.lib.cab(s.cab).name.takeIf { it.isNotEmpty() },
                    s.tags.joinToString(" ").takeIf { it.isNotEmpty() },
                ).joinToString(" · ")
                if (info.isNotEmpty()) Note(info, color = if (onPad >= 0) C.Teal else C.Dim, size = 11.sp)
            }
            T(if (s.key in state.favs) "★" else "☆", size = 22.sp, color = C.Yellow, modifier = Modifier.clickable { state.toggleFav(s.key) }.padding(horizontal = 8.dp))
            if (s.cab == "mine") {
                Btn("Share", { state.shareSoundWav(s.key) }, small = true, modifier = Modifier.padding(end = 8.dp), tag = "share-${s.key}")
                var sure by remember { mutableStateOf(false) }
                Btn(if (sure) "Sure?" else "Toss", { if (sure) state.removeFromBench(s.key) else { sure = true } }, small = true, color = if (sure) C.Riot else C.Ink, modifier = Modifier.padding(end = 8.dp))
            }
            if (onPad == target) Note("here", color = C.Yellow)
            else Btn("Pad ${target + 1}", {
                state.putOnPad(target, s.key)
                if (picking) state.overlay = null
            }, small = true, color = C.Pink, tag = "put-${s.key}")
        }
        Dashed()
    }
}
