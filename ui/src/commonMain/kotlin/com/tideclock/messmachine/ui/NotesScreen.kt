package com.tideclock.messmachine.ui

import com.tideclock.messmachine.platform.fmt

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tideclock.messmachine.AppState
import com.tideclock.messmachine.NOTE_CATS
import com.tideclock.messmachine.catLabel
import com.tideclock.messmachine.engine.Json

private val catColor get() = mapOf("bug" to C.Riot, "tuning" to C.Yellow, "feature" to C.Teal, "question" to C.Purple, "discuss" to C.Pink, "wishlist" to C.Lime)

@Composable
fun NotesScreen(state: AppState) {
    var cat by remember { mutableStateOf(if (state.crash != null) "bug" else state.noteCat) }
    var topic by remember { mutableStateOf("") }
    var text by remember { mutableStateOf(if (state.crash != null) "The app crashed. I was " else "") }
    var sureDel by remember { mutableStateOf<String?>(null) }
    val speak = rememberSpeechAsk("Say your note", { said -> text = if (text.isBlank()) said else text.trimEnd() + " " + said }) {
        state.say("No speech app on this phone. The keyboard's mic key works too.")
    }
    val fieldColors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.Pink, unfocusedBorderColor = C.Card2, cursorColor = C.Pink)
    ScrollPane(hPad = 14.dp, vPad = 0.dp) {
        Guide(state)
        SpeedCard(state)
        state.crash?.let {
            Card(Modifier.fillMaxWidth().padding(bottom = 12.dp), color = C.Riot) {
                T("The app crashed last time. Say what you were doing and send it; the crash details go along with your note.", color = C.Ink, size = 13.sp)
            }
        }
        Card(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
            Column {
                T("New note for Claude", size = 15.sp)
                // big buttons, three to a row: the kind of note is the first thing to pick (it opens on your last one)
                Column(Modifier.padding(vertical = 8.dp).tagged("category-chips"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (row in NOTE_CATS.chunked(3)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((c, label) in row) Btn(label, { cat = c }, on = cat == c, onColor = catColor[c] ?: C.Yellow, tight = true, tag = "notecat-$c", modifier = Modifier.weight(1f))
                    }
                }
                T(
                    when (cat) {
                        "bug" -> "Something broke or acted wrong."
                        "tuning" -> "Works, but the feel or a number is off."
                        "feature" -> "Something new you want built."
                        "question" -> "You want to know how or why."
                        "discuss" -> "Collect thoughts on one topic. Claude waits until you say \"let's discuss\" it."
                        else -> "An idea for later. It goes in the design doc's wishlist, not into the app."
                    },
                    size = 12.sp, color = C.Dim, weight = FontWeight.Medium,
                )
                if (cat == "discuss") {
                    OutlinedTextField(
                        value = topic, onValueChange = { topic = it.take(40) }, singleLine = true,
                        placeholder = { T("Topic, like \"keys mode\"", color = C.Dim, weight = FontWeight.Medium) },
                        textStyle = TextStyle(color = C.Cream, fontFamily = Archivo, fontSize = 15.sp), colors = fieldColors,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    placeholder = { T("What happened, or what you want. You can start with \"wishlist:\" or \"discuss keys:\" too.", color = C.Dim, weight = FontWeight.Medium) },
                    textStyle = TextStyle(color = C.Cream, fontFamily = Archivo, fontSize = 15.sp), colors = fieldColors,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp).padding(top = 8.dp),
                )
                Wrap(Modifier.padding(top = 10.dp)) {
                    Btn("🎤 Speak", {
                        speak()
                    }, small = true, color = C.Teal)
                    Btn("Send to Claude", { state.saveNote(cat, topic, text, true); text = ""; topic = "" }, small = true, color = C.Pink)
                    Btn("Keep on phone", { state.saveNote(cat, topic, text, false); text = ""; topic = "" }, small = true)
                }
                T(
                    (if (state.notes.hasKey) "Send posts the note straight to GitHub with your key"
                    else "Send opens GitHub with the note filled in; tap Submit there while signed in as hubbub0") +
                        ". It carries the build, your phone, the audio buffer and your last few taps. The repo is public, so anyone can read these notes.",
                    size = 11.sp, color = C.Dim, weight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        NoteKeyCard(state)
        if (state.notes.hasKey) Row(Modifier.padding(bottom = 12.dp)) {
            Btn(if (state.quickSendNow) "Quick notes: send right away" else "Quick notes: wait on the phone", { state.switchQuickSendNow() }, on = state.quickSendNow, onColor = C.Teal, small = true, tag = "notes-sendnow")
        }
        Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            T("Your notes", size = 16.sp, modifier = Modifier.weight(1f))
            val waiting = state.waitingNotes.size
            if (waiting > 0) Btn("Send all ($waiting)", { state.sendAllNotes() }, small = true, color = C.Pink, tag = "notes-sendall")
            Btn(if (state.notes.checking) "Checking…" else "Check for replies", { state.checkNotes() }, small = true, color = C.Teal, enabled = !state.notes.checking)
        }
        if (state.notes.lastCheck.isNotEmpty()) Note(state.notes.lastCheck, modifier = Modifier.padding(bottom = 8.dp))
        if (state.notes.notes.isEmpty()) T("No notes yet.", size = 13.sp, color = C.Dim, weight = FontWeight.Medium)
        for (n in state.notes.notes) {
            Card(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        T(catLabel(n.cat) + if (n.cat == "discuss" && n.topic.isNotEmpty()) ": ${n.topic}" else "", size = 12.sp, color = catColor[n.cat] ?: C.Yellow)
                        Note(shortDate(n.at))
                    }
                    T(n.text.ifEmpty { "(crash report)" }, size = 14.sp, weight = FontWeight.Medium, modifier = Modifier.padding(vertical = 4.dp))
                    T(
                        (if (n.issue > 0) "#${n.issue} · " else "") + n.status.ifEmpty { if (n.sent) "sent" else "on the phone" } + " · ${n.screen} · build ${n.build}",
                        size = 11.sp, color = C.Dim, weight = FontWeight.Medium,
                    )
                    for (r in n.replies) {
                        Card(Modifier.fillMaxWidth().padding(top = 8.dp), color = C.Card2) {
                            T(r, size = 13.sp, weight = FontWeight.Medium)
                        }
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (n.issue == 0) Btn(if (n.sent) "Send again" else "Send", { state.sendNote(n) }, small = true, color = C.Pink)
                        Btn(if (sureDel == n.id) "Really delete?" else "Delete", {
                            if (sureDel == n.id) { sureDel = null; state.notes.remove(n.id) } else { sureDel = n.id }
                        }, small = true, color = if (sureDel == n.id) C.Riot else C.Card2)
                    }
                }
            }
        }
        T(" ", size = 30.sp)
    }
}

/** The start guide: what changed in this build and what to try. Claude rewrites it before each build. */
@Composable
fun Guide(state: AppState) {
    val g = state.guide
    if (g.isEmpty()) return
    Card(Modifier.fillMaxWidth().padding(bottom = 14.dp), color = C.Card2) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                FaqGuy(state.buildName, modifier = Modifier.padding(end = 10.dp))
                Column {
                    T("Build ${state.buildName}", size = 12.sp, color = C.Dim)
                    T(Json.str(g["title"], "What's new"), size = 17.sp)
                }
            }
            for ((k, head) in listOf("thisBuild" to "In this build", "watch" to "Try this", "limits" to "Not in yet")) {
                val items = Json.list(g[k])
                if (items.isEmpty()) continue
                T(head, size = 13.sp, color = C.Yellow, modifier = Modifier.padding(top = 6.dp))
                for (line in items) T("• $line", size = 13.sp, weight = FontWeight.Medium)
            }
            Row(Modifier.padding(top = 10.dp)) {
                Btn(if (state.guideOn) "Shows on each new build" else "Hidden on new builds", { state.switchGuide(!state.guideOn) }, on = state.guideOn, onColor = C.Teal, small = true)
            }
        }
    }
}

/** How the app is running right now, the same numbers every note carries. */
@Composable
fun SpeedCard(state: AppState) {
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        do {
            text = "Screen: ${state.uiPerf}\nSound engine: ${state.audio.loadText()}\nAudio buffer: ${state.audio.bufferFrames} frames at ${state.rate} Hz, dropouts ${state.audio.underruns}" +
                (if (state.audio.outputMs >= 0) "\nOutput: ${fmt(state.audio.outputMs, 0)} ms by Android's clock" else "")
            if (!UiFlags.testing) delay(1000)
        } while (!UiFlags.testing)
    }
    Card(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Column {
            T("How it is running", size = 13.sp, color = C.Dim)
            T(text, size = 13.sp, weight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
            // the Audio check's one home (MM-1): the start guide points here
            Btn("Latency", { state.overlay = com.tideclock.messmachine.Overlay.AudioCheck }, small = true, tag = "audio-check", modifier = Modifier.padding(top = 10.dp))
        }
    }
}

/**
 * Send without the browser (option A): paste a GitHub key that can only write issues on the Mess Machine repo. It
 * stays on this phone. Nothing here can make the key: you make it on GitHub.
 */
@Composable
fun NoteKeyCard(state: AppState) {
    var open by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("Send without the browser", size = 15.sp, modifier = Modifier.weight(1f))
                Btn(if (open) "Hide" else if (state.notes.hasKey) "Key in ✓" else "Set up", { open = !open }, on = state.notes.hasKey, onColor = C.Teal, small = true, tag = "notekey-open")
            }
            if (open) {
                T(
                    "On GitHub, signed in as hubbub0: Settings, Developer settings, Personal access tokens, Fine-grained tokens, Generate new token. " +
                        "Repository access: only hubbub0/Mess-Machine. Permissions: Issues, read and write. Nothing else. Copy the token and paste it here.",
                    size = 12.sp, color = C.Dim, weight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp),
                )
                OutlinedTextField(
                    value = key, onValueChange = { key = it.trim() }, singleLine = true,
                    placeholder = { T("github_pat_…", color = C.Dim, weight = FontWeight.Medium) },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    textStyle = TextStyle(color = C.Cream, fontFamily = Archivo, fontSize = 15.sp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.Teal, unfocusedBorderColor = C.Card2, cursorColor = C.Teal),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).tagged("notekey-field"),
                )
                Wrap(Modifier.padding(top = 8.dp)) {
                    Btn("Save key", { state.notes.saveKey(key); key = ""; state.say("Key saved. Send now posts notes from inside the app.") }, small = true, color = C.Teal, enabled = key.length > 20, tag = "notekey-save")
                    if (state.notes.hasKey) Btn("Remove key", { state.notes.forgetKey(); state.say("Key removed. Send opens GitHub again.") }, small = true, tier = Tier.Danger, tag = "notekey-forget")
                }
                T("The key stays on this phone and can only file issues on that one repo. When it runs out, Send says so.", size = 11.sp, color = C.Dim, weight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}
