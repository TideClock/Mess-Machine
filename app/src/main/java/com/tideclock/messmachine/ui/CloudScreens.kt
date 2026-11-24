package com.tideclock.messmachine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tideclock.messmachine.AppState
import com.tideclock.messmachine.MessFile
import com.tideclock.messmachine.Overlay
import com.tideclock.messmachine.cloud.CloudStore
import com.tideclock.messmachine.cloud.MessRow

/*
 * Accounts, Slice A (docs/ACCOUNTS-SPEC.md s5.3 after the rulings): sign in with an email code, the terms, the name
 * asked once, the Account page, and what the Messes sheet gains (the account row, a cloud mark per mess, online
 * messes not on this phone, Get sounds now). None of it is composed unless state.store exists, which needs a server
 * in the build. Nothing here sits on the play screen.
 */

/** The short terms shown before anything goes online. The full page is on the site (acct-terms). */
private const val TERMS_SHORT = "You must be 13 or older. Your messes and sounds stay yours: you let us store them and send them to your phones and to the people you share with.\n\n" +
    "Only put up sounds you made or may use. Not allowed: sexual content, anything sexual involving a minor, hate, harassment or threats, other people's music or voices without their OK, spam, anything against the law.\n\n" +
    "We act on every report within 24 hours. Breaking these rules can take your messes down and remove your account.\n\n" +
    "You can delete your account at any time. The messes on your phone stay yours whatever happens online."

/** The text fields look like the mess name field on the Messes sheet. */
@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.Pink, unfocusedBorderColor = C.Card2, cursorColor = C.Pink)

private fun openPage(ctx: android.content.Context, state: AppState, path: String) {
    if (state.siteHost.isEmpty()) return
    try {
        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://${state.siteHost}/$path/"))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) { state.say("No browser on this phone opened the page.") }
}

/** acct-open: the row at the top of the Messes sheet. */
@Composable
fun AccountRow(state: AppState) {
    val s = state.store ?: return
    @Suppress("UNUSED_VARIABLE") val v = state.cloudV
    val (title, line) = when {
        s.checking() -> "Checking your account" to "One moment."
        s.state == CloudStore.OUT -> "Sign in to save online" to "Your messes on all your phones."
        s.state == CloudStore.TERMS -> "Agree to the terms" to "Nothing goes online until you do."
        else -> (s.me?.display_name?.ifEmpty { null } ?: s.email()) to (if (s.online) "Signed in. Saved messes go online." else "Offline. Saved messes go up later.")
    }
    Card(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp).clickable { state.overlay = Overlay.Account }.tagged("acct-open")) {
        Column {
            T("ACCOUNT", size = 12.sp, color = C.Yellow, spacing = .1.em)
            T(title, size = 16.sp, lines = 1, modifier = Modifier.padding(top = 4.dp))
            T(line, size = 12.sp, color = C.Dim, weight = FontWeight.Medium)
        }
    }
    if (state.cloudNotice.isNotEmpty()) Note(state.cloudNotice, color = C.Yellow, modifier = Modifier.padding(bottom = 12.dp))
}

/** The cloud mark under a mess on this phone (mess-cloud-<id>), the lamp on the open one (sync-state), Get sounds now. */
@Composable
fun CloudMark(state: AppState, f: MessFile) {
    val s = state.store ?: return
    @Suppress("UNUSED_VARIABLE") val v = state.cloudV
    if (s.state == CloudStore.OUT) return
    val mark = s.mark(f.id)
    val (word, col) = when (mark) {
        CloudStore.M_SYNCED -> "Online" to C.Teal
        CloudStore.M_WAITING -> "Waiting to go online" to C.Yellow
        CloudStore.M_SENDING -> "Going online" to C.Yellow
        CloudStore.M_OFFLINE -> "Offline, tries again" to C.Dim
        CloudStore.M_BLOCKED -> "Taken down online" to C.Riot
        CloudStore.M_REFUSED -> "Not saved online" to C.Riot
        else -> "On this phone only" to C.Dim
    }
    val open = f.id == state.fileId
    Row(Modifier.padding(top = 4.dp).tagged("mess-cloud-${f.id}"), verticalAlignment = Alignment.CenterVertically) {
        if (open) {
            // the one lamp, on the open mess: green online, amber changes waiting, grey offline
            val n = s.waiting()
            val lamp = when { !s.online -> C.Dim; n > 0 -> C.Yellow; else -> Color(0xFF3DDC84) }
            Box(Modifier.size(10.dp).background(lamp, CircleShape).tagged("sync-state"))
            T(if (!s.online) "Offline" else if (n > 0) (if (n == 1) "1 change waiting" else "$n changes waiting") else word,
                size = 12.sp, color = col, weight = FontWeight.Medium, lines = 1, modifier = Modifier.padding(start = 6.dp))
        } else T(word, size = 12.sp, color = col, weight = FontWeight.Medium, lines = 1)
    }
    if (open) {
        val miss = s.missing(f.id)
        if (miss > 0) Btn(if (miss == 1) "Get 1 sound now" else "Get $miss sounds now", { state.cloudGetSounds() }, small = true, color = C.Teal, tag = "sounds-get",
            modifier = Modifier.padding(top = 6.dp))
    }
}

/** mess-offcloud-<id>: "Keep off the cloud" (asks once more, since its online copy goes), or "Put online". */
@Composable
fun CloudRowButton(state: AppState, f: MessFile, sure: Boolean, onSure: (Boolean) -> Unit) {
    val s = state.store ?: return
    @Suppress("UNUSED_VARIABLE") val v = state.cloudV
    if (s.state == CloudStore.OUT) return
    val online = s.mark(f.id) != CloudStore.M_PHONE
    if (online) Btn(if (sure) "Really? It goes offline" else "Keep off the cloud", {
        if (sure) { onSure(false); state.keepOffCloud(f.id, true) } else onSure(true)
    }, small = true, color = if (sure) C.Riot else C.Card2, tag = "mess-offcloud-${f.id}")
    else Btn("Put online", { state.keepOffCloud(f.id, false) }, small = true, tag = "mess-offcloud-${f.id}")
}

/** Online messes of yours that are not on this phone: tap one to bring it here (omess-<cid>). */
@Composable
fun OnlineOnly(state: AppState) {
    val s = state.store ?: return
    @Suppress("UNUSED_VARIABLE") val v = state.cloudV
    if (s.state != CloudStore.IN) return
    val rows = state.cloudOnlyRows()
    if (rows.isEmpty()) return
    var sureDel by remember { mutableStateOf<String?>(null) }
    Heading("Online, not on this phone", size = 16.sp, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
    for (r in rows) OnlineRow(state, r, sureDel == r.id) { sureDel = if (it) r.id else null }
}

@Composable
private fun OnlineRow(state: AppState, r: MessRow, sure: Boolean, onSure: (Boolean) -> Unit) {
    val cid = r.id.take(8)
    val mine = r.role == "owner"
    Card(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Column {
            // the name and its line are the tap target; the buttons under them are their own
            Column(Modifier.fillMaxWidth().clickable { state.openCloudMess(r.id) }.tagged("omess-$cid")) {
                T(r.name, size = 16.sp, lines = 1)
                T((if (!mine && r.owner_name.isNotEmpty()) "${r.owner_name}'s · " else "") + (if (r.updated_at.length >= 10) "Saved ${r.updated_at.substring(0, 10)} · " else "") + "Tap to open it here",
                    size = 12.sp, color = C.Dim, weight = FontWeight.Medium)
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Btn("Duplicate", { state.dupCloudMess(r.id) }, small = true, tag = "omess-dup-$cid")
                if (mine) Btn(if (sure) "Really delete online?" else "Delete online", {
                    if (sure) { onSure(false); state.deleteCloudMess(r.id) } else onSure(true)
                }, small = true, color = if (sure) C.Riot else C.Card2, tag = "omess-del-$cid")
            }
        }
    }
}

/** The Account sheet: sign in, the terms, the name and the messes question once, then the Account page. */
@Composable
fun AccountScreen(state: AppState) {
    val s = state.store ?: return
    @Suppress("UNUSED_VARIABLE") val v = state.cloudV
    ScrollPane(hPad = 14.dp, vPad = 0.dp) {
        when {
            s.checking() -> Card(Modifier.fillMaxWidth().padding(top = 4.dp)) { T("Checking your account…", size = 15.sp) }
            s.state == CloudStore.OUT -> SignIn(state, s)
            s.state == CloudStore.TERMS -> Terms(state, s)
            !state.nameAsked && s.nameNeeded() -> NameCard(state, s, asking = true)
            !state.moveAsked && phoneOnly(state, s) > 0 -> MoveAsk(state, phoneOnly(state, s))
            else -> AccountPage(state, s)
        }
        T(" ", size = 30.sp)
    }
}

private fun phoneOnly(state: AppState, s: CloudStore): Int = state.files.count { s.mark(it.id) == CloudStore.M_PHONE }

@Composable
private fun SignIn(state: AppState, s: CloudStore) {
    var email by remember { mutableStateOf(s.codeSentTo.ifEmpty { s.email() }) }
    var code by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var sentAt by remember { mutableLongStateOf(0L) }
    var canResend by remember(sentAt) { mutableStateOf(false) }
    LaunchedEffect(sentAt) { if (sentAt != 0L) { kotlinx.coroutines.delay(60_000); canResend = true } }
    fun send() {
        busy = true; err = ""
        s.requestCode(email) { ok, e -> busy = false; if (ok == null) err = state.cloudErrText(e) else { sentAt = System.currentTimeMillis(); code = "" } }
    }
    Card(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp)) {
        Column {
            T("SAVE YOUR MESSES ONLINE", size = 12.sp, color = C.Yellow, spacing = .1.em)
            T("Sign in with your email. We mail you a code. No password.", size = 13.sp, weight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
            OutlinedTextField(
                value = email, onValueChange = { email = it.trim().take(120); err = "" }, singleLine = true,
                placeholder = { T("you@example.com", color = C.Dim, weight = FontWeight.Medium) },
                textStyle = TextStyle(color = C.Cream, fontFamily = Archivo, fontSize = 16.sp), colors = fieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).tagged("acct-email"),
            )
            if (s.codeSentTo.isEmpty() || s.codeSentTo != email.trim()) {
                Btn("Send me a code", { send() }, color = C.Teal, small = true, enabled = !busy && email.contains('@'), tag = "acct-send")
            } else {
                T("We mailed a code to ${s.codeSentTo}.", size = 13.sp, weight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
                OutlinedTextField(
                    value = code, onValueChange = { t -> code = t.filter { it.isDigit() }.take(10); err = "" }, singleLine = true,
                    placeholder = { T("123456", color = C.Dim, weight = FontWeight.Medium) },
                    textStyle = TextStyle(color = C.Cream, fontFamily = Mono, fontSize = 20.sp), colors = fieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).tagged("acct-code"),
                )
                Wrap(Modifier) {
                    Btn("Sign in", {
                        busy = true; err = ""
                        s.verify(s.codeSentTo, code) { m, e -> busy = false; if (m == null) err = state.cloudErrText(e) }
                    }, color = C.Teal, small = true, enabled = !busy && code.length >= 6, tag = "acct-verify")
                    Btn("Send a new code", { send() }, small = true, enabled = !busy && canResend, tag = "acct-resend")
                }
            }
            if (err.isNotEmpty()) T(err, size = 13.sp, color = C.Riot, weight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun Terms(state: AppState, s: CloudStore) {
    val ctx = LocalContext.current
    var err by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp)) {
        Column {
            T("THE TERMS", size = 12.sp, color = C.Yellow, spacing = .1.em)
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).heightIn(max = 280.dp).background(C.Black.copy(alpha = .35f)).verticalScroll(rememberScrollState())
                .tagged("terms-text").padding(10.dp)) {
                T(TERMS_SHORT, size = 13.sp, weight = FontWeight.Medium)
            }
            if (state.siteHost.isNotEmpty()) Btn("Read the full terms", { openPage(ctx, state, "terms") }, small = true, tag = "acct-terms", modifier = Modifier.padding(bottom = 8.dp))
            Wrap(Modifier) {
                Btn("I agree", {
                    busy = true; err = ""
                    s.acceptTerms { ok, e -> busy = false; if (ok == null) err = state.cloudErrText(e) }
                }, color = C.Teal, small = true, enabled = !busy && s.me != null, tag = "terms-agree")
                Btn("Not now", { s.signOut(); state.say("Signed out. Your messes on this phone stay.") }, small = true, tag = "terms-decline")
            }
            T("Not now signs you out. Nothing goes online before you agree.", size = 12.sp, color = C.Dim, weight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
            if (err.isNotEmpty()) T(err, size = 13.sp, color = C.Riot, weight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** The name others see: asked once after the first sign-in (name-ask), and on the Account page (acct-name). */
@Composable
private fun NameCard(state: AppState, s: CloudStore, asking: Boolean) {
    var name by remember { mutableStateOf(s.me?.display_name ?: "") }
    var err by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    if (asking) DisposableEffect(Unit) { onDispose { state.markNameAsked() } }
    Card(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp)) {
        Column {
            T(if (asking) "WHAT SHOULD OTHERS CALL YOU?" else "YOUR NAME", size = 12.sp, color = C.Yellow, spacing = .1.em)
            if (asking) T("They see it when you share a mess. You can change it later here.", size = 13.sp, weight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(24); err = "" }, singleLine = true,
                textStyle = TextStyle(color = C.Cream, fontFamily = Archivo, fontSize = 16.sp), colors = fieldColors(),
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).tagged(if (asking) "name-ask" else "acct-name"),
            )
            Wrap(Modifier) {
                Btn(if (asking) "That's me" else "Save name", {
                    busy = true; err = ""
                    s.setName(name.trim()) { m, e -> busy = false; if (m == null) err = state.cloudErrText(e) else { state.markNameAsked(); if (!asking) state.say("Name saved.") } }
                }, color = C.Teal, small = true, enabled = !busy && name.isNotBlank(), tag = "name-ask-ok")
            }
            if (err.isNotEmpty()) T(err, size = 13.sp, color = C.Riot, weight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun MoveAsk(state: AppState, n: Int) {
    DisposableEffect(Unit) { onDispose { state.markMoveAsked() } }
    Card(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp)) {
        Column {
            T(if (n == 1) "PUT YOUR MESS ONLINE?" else "PUT YOUR $n MESSES ONLINE?", size = 12.sp, color = C.Yellow, spacing = .1.em)
            T("Then they open on your other phones too. From now on, every mess you save goes online.", size = 13.sp, weight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            Wrap(Modifier) {
                Btn("All", { state.cloudMoveAll() }, color = C.Teal, small = true, tag = "cloud-move-all")
                Btn("Pick", { state.markMoveAsked(); state.refreshFiles(); state.overlay = Overlay.Files }, small = true, tag = "cloud-move-pick")
                Btn("Not now", { state.markMoveAsked() }, small = true, tag = "cloud-move-later")
            }
        }
    }
}

@Composable
private fun AccountPage(state: AppState, s: CloudStore) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp)) {
        Column {
            T("SIGNED IN", size = 12.sp, color = C.Yellow, spacing = .1.em)
            T(s.email(), size = 16.sp, lines = 1, modifier = Modifier.padding(top = 4.dp))
            T(if (s.online) "Every mess you save goes online." else "Offline. Saved messes go up when the phone is back online.",
                size = 12.sp, color = C.Dim, weight = FontWeight.Medium)
        }
    }
    NameCard(state, s, asking = false)
    Card(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Column {
            T("SOUNDS", size = 12.sp, color = C.Yellow, spacing = .1.em)
            Btn(if (state.cloudData) "Use mobile data for sounds: on" else "Use mobile data for sounds: off", { state.switchCloudData(!state.cloudData) },
                on = state.cloudData, onColor = C.Teal, small = true, tag = "acct-data", modifier = Modifier.padding(top = 6.dp))
            T("Off: your own sounds go up and come down on Wi-Fi only.", size = 12.sp, color = C.Dim, weight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
        }
    }
    if (state.siteHost.isNotEmpty()) Card(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Wrap(Modifier) {
            Btn("Terms", { openPage(ctx, state, "terms") }, small = true, tag = "acct-terms")
            Btn("Privacy", { openPage(ctx, state, "privacy") }, small = true, tag = "acct-privacy")
        }
    }
    Card(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Column {
            Btn("Sign out", { s.signOut(); state.say("Signed out. Your messes on this phone stay.") }, small = true, tier = Tier.Danger, tag = "acct-signout")
            T("Your messes on this phone stay.", size = 12.sp, color = C.Dim, weight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
