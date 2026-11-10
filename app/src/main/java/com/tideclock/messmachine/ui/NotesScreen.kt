package com.tideclock.messmachine.ui

import android.content.ActivityNotFoundException
import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tideclock.messmachine.AppState
import com.tideclock.messmachine.NOTE_CATS
import com.tideclock.messmachine.catLabel
import com.tideclock.messmachine.engine.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val catColor = mapOf("bug" to C.Riot, "tuning" to C.Yellow, "feature" to C.Teal, "question" to C.Purple, "discuss" to C.Pink, "wishlist" to C.Lime)

@Composable
fun NotesScreen(state: AppState) {
    var cat by remember { mutableStateOf(if (state.crash != null) "bug" else "bug") }
    var topic by remember { mutableStateOf("") }
    var text by remember { mutableStateOf(if (state.crash != null) "The app crashed. I was " else "") }
    var sureDel by remember { mutableStateOf<String?>(null) }
    val speak = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!said.isNullOrBlank()) text = if (text.isBlank()) said else text.trimEnd() + " " + said
        }
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
                Wrap(Modifier.padding(vertical = 8.dp), gap = 6.dp) {
                    for ((c, label) in NOTE_CATS) Btn(label, { cat = c }, on = cat == c, onColor = catColor[c] ?: C.Yellow, small = true)
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
                        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say your note")
                        try { speak.launch(i) } catch (e: ActivityNotFoundException) { state.say("No speech app on this phone. The keyboard's mic key works too.") }
                    }, small = true, color = C.Teal)
                    Btn("Send to Claude", { state.saveNote(cat, topic, text, true); text = ""; topic = "" }, small = true, color = C.Pink)
                    Btn("Keep on phone", { state.saveNote(cat, topic, text, false); text = ""; topic = "" }, small = true)
                }
                T(
                    "Send opens GitHub with the note filled in, plus the build, your phone, the audio buffer and your last few taps. " +
                        "Tap Submit there while signed in as TideClock. The repo is public, so anyone can read these notes.",
                    size = 11.sp, color = C.Dim, weight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            T("Your notes", size = 16.sp, modifier = Modifier.weight(1f))
            Btn(if (state.notes.checking) "Checking…" else "Check for replies", { state.notes.checkReplies { state.say(it) } }, small = true, color = C.Teal, enabled = !state.notes.checking)
        }
        if (state.notes.lastCheck.isNotEmpty()) Note(state.notes.lastCheck, modifier = Modifier.padding(bottom = 8.dp))
        if (state.notes.notes.isEmpty()) T("No notes yet.", size = 13.sp, color = C.Dim, weight = FontWeight.Medium)
        val fmt = remember { SimpleDateFormat("MMM d, h:mm a", Locale.US) }
        for (n in state.notes.notes) {
            Card(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        T(catLabel(n.cat) + if (n.cat == "discuss" && n.topic.isNotEmpty()) ": ${n.topic}" else "", size = 12.sp, color = catColor[n.cat] ?: C.Yellow)
                        Note(fmt.format(Date(n.at)))
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
            T("Build ${state.buildName}", size = 12.sp, color = C.Dim)
            T(Json.str(g["title"], "What's new"), size = 17.sp, modifier = Modifier.padding(bottom = 6.dp))
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
            text = "Screen: ${state.uiPerf}\nSound engine: ${state.audio.loadText()}\nAudio buffer: ${state.audio.bufferFrames} frames at ${state.rate} Hz, dropouts ${state.audio.underruns}"
            if (!UiFlags.testing) delay(1000)
        } while (!UiFlags.testing)
    }
    Card(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Column {
            T("How it is running", size = 13.sp, color = C.Dim)
            T(text, size = 13.sp, weight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
