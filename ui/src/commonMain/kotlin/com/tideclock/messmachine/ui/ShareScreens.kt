package com.tideclock.messmachine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tideclock.messmachine.AppState
import com.tideclock.messmachine.cloud.CloudStore

/*
 * Accounts, Slice B and later (docs/ACCOUNTS-API.md s5): the Share sheet of an online mess (invite, who is on it,
 * leave, remove), the question a link asks ("Join Basement jam?"), and the people part of the Account page (paste a
 * link, people you blocked). None of it is composed without a server, and none of it sits on the play screen.
 */

/** A person's colour: made by the server from their id, eight of them. */
fun personColor(colour: Int): Color = when (colour.mod(8)) {
    0 -> Color(0xFFFF5C8A); 1 -> Color(0xFF35C9B5); 2 -> Color(0xFFFFD23F); 3 -> Color(0xFF7FB2E0)
    4 -> Color(0xFFB58CFF); 5 -> Color(0xFF3DDC84); 6 -> Color(0xFFFF9F45); else -> Color(0xFFE8E0D0)
}

/** The Share sheet (omess-share-<cid>). */
@Composable
fun ShareScreen(state: AppState, cid: String) {
    val s = state.store ?: return
    @Suppress("UNUSED_VARIABLE") val v = state.cloudV
    val row = state.cloudRow(cid)
    val mine = state.cloudMine(cid)
    val me = state.myUserId()
    var removing by remember(cid) { mutableStateOf("") }
    var sureLeave by remember(cid) { mutableStateOf(false) }
    ScrollPane(hPad = 14.dp, vPad = 0.dp) {
        Card(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp)) {
            Column {
                T("THIS MESS", size = 12.sp, color = C.Yellow, spacing = .1.em)
                T(row?.name ?: "A mess", size = 16.sp, lines = 1, modifier = Modifier.padding(top = 4.dp))
                T(if (mine) "You made it." else ((row?.owner_name ?: "").ifEmpty { "Someone else" } + " made it."), size = 12.sp, color = C.Dim, weight = FontWeight.Medium)
            }
        }
        if (state.shareNote.isNotEmpty()) Note(state.shareNote, color = C.Yellow, modifier = Modifier.padding(bottom = 12.dp))
        if (mine) {
            if (s.nameNeeded()) NameCard(state, s, asking = true)
            else Card(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                Column {
                    T("MAKE IT TOGETHER", size = 12.sp, color = C.Yellow, spacing = .1.em)
                    T("An invite link lets one person join and change this mess with you. It works once, for 7 days.", size = 13.sp, weight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                    Btn(if (state.shareBusy) "Making the link" else "Invite to edit", { state.inviteToEdit(cid) }, color = C.Teal, small = true, enabled = !state.shareBusy, tag = "share-invite")
                    // Nov 28: the code to type on the other screen (no clipboard needed), big; the link under it
                    if (state.inviteCode.isNotEmpty()) {
                        T("CODE", size = 11.sp, color = C.Dim, spacing = .1.em, modifier = Modifier.padding(top = 10.dp))
                        T(state.inviteCode, size = 26.sp, color = C.Yellow, family = Mono, modifier = Modifier.tagged("invite-code"))
                    }
                    if (state.inviteLink.isNotEmpty()) T(state.inviteLink, size = 12.sp, color = C.Cream, weight = FontWeight.Normal, family = Mono, modifier = Modifier.padding(top = 8.dp))
                    for (inv in state.shareInvites) {
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            T("Unused invite, good until " + inv.expires_at.take(10), size = 12.sp, color = C.Dim, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Btn("Cancel it", { state.revokeInvite(cid, inv.id) }, small = true, tag = "inv-revoke-${inv.id.take(8)}")
                        }
                    }
                }
            }
        }
        Card(Modifier.fillMaxWidth().padding(bottom = 14.dp).tagged("share-members")) {
            Column {
                T("WHO IS ON IT", size = 12.sp, color = C.Yellow, spacing = .1.em)
                if (state.shareMembers.isEmpty()) T("Looking.", size = 13.sp, color = C.Dim, weight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
                for (m in state.shareMembers) {
                    val uid = m.id.take(8)
                    val name = m.name.ifEmpty { "No name yet" }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp).tagged("mem-$uid"), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).background(personColor(m.colour), CircleShape))
                        T(name + (if (m.id == me) " (you)" else ""), size = 15.sp, lines = 1, modifier = Modifier.weight(1f).padding(start = 8.dp))
                        T((if (m.role == "owner") "made it" else "can edit") + (if (state.here.any { it.id == m.id }) ", here now" else ""), size = 12.sp, color = C.Dim, weight = FontWeight.Medium)
                    }
                    if (mine && m.id != me) {
                        Wrap(Modifier.padding(top = 6.dp)) {
                            Btn(if (removing == m.id) "Keep them" else "Remove", { removing = if (removing == m.id) "" else m.id }, small = true, tag = "mem-remove-$uid")
                            if (removing == m.id) {
                                Btn("Remove", { removing = ""; state.removeMember(cid, m.id, false) }, small = true, tier = Tier.Danger, tag = "mem-remove-go")
                                Btn("Remove and block", { removing = ""; state.removeMember(cid, m.id, true) }, small = true, tier = Tier.Danger, tag = "mem-remove-block")
                            }
                        }
                        if (removing == m.id) T("Remove $name? They keep their own copy. Block also keeps them out of every mess of yours.", size = 12.sp, color = C.Dim,
                            weight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
                    }
                    PersonExtras(state, cid, m.id, name, mine)
                }
                if (!mine) Btn(if (sureLeave) "Really leave?" else "Leave this mess", {
                    if (sureLeave) { sureLeave = false; state.leaveMess(cid) } else sureLeave = true
                }, small = true, tier = if (sureLeave) Tier.Danger else Tier.Normal, tag = "mem-leave", modifier = Modifier.padding(top = 12.dp))
                if (!mine) T("Leaving keeps your own copy on this phone.", size = 12.sp, color = C.Dim, weight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
            }
        }
        ShareExtras(state, cid, mine)
        T(" ", size = 30.sp)
    }
}

/** here-chip: who else has this mess open right now. Small, in the mess's header on the Messes sheet. */
@Composable
fun HereChips(state: AppState) {
    val here = state.here
    if (here.isEmpty()) return
    Wrap(Modifier.padding(top = 6.dp, bottom = 2.dp).tagged("here-chip"), gap = 6.dp) {
        for (p in here) Row(Modifier.background(C.Card2).padding(horizontal = 8.dp, vertical = 4.dp).tagged("here-${p.id.take(8)}"), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(personColor(p.colour), CircleShape))
            T(p.name.ifEmpty { "Someone" } + (if (p.playing) " is playing" else " is here"), size = 12.sp, weight = FontWeight.Medium, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

/** What a link asks before anything happens: "Join Basement jam?" (and, from Slice D, a shared mess to look at). */
@Composable
fun LinkCards(state: AppState, s: CloudStore) {
    if (state.pendingInvite.isNotEmpty()) InviteCard(state, s)
    SharedCard(state, s)
}

@Composable
private fun InviteCard(state: AppState, s: CloudStore) {
    val token = state.pendingInvite
    LaunchedEffect(token) { if (state.inviteInfo == null) state.loadInvite() }
    val info = state.inviteInfo
    val signedIn = s.state == CloudStore.IN
    Card(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp)) {
        Column {
            T(if (info == null) "AN INVITE" else ("JOIN " + info.mess_name.ifEmpty { "this mess" } + "?").uppercase(), size = 12.sp, color = C.Yellow, spacing = .1.em)
            if (info != null) T(info.owner_name.ifEmpty { "Someone" } + " invited you to make this mess together. You can both change it, and you hear each other's changes at the next loop.",
                size = 13.sp, weight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
            else if (state.inviteNote.isEmpty()) T("Looking it up.", size = 13.sp, color = C.Dim, weight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
            if (state.inviteNote.isNotEmpty()) T(state.inviteNote, size = 13.sp, color = C.Riot, weight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            if (!signedIn) T("Sign in below to join. The invite waits.", size = 12.sp, color = C.Dim, weight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
            Wrap(Modifier.padding(top = 8.dp)) {
                if (signedIn) Btn(if (state.inviteBusy) "Joining" else "Join", { state.joinInvite() }, color = C.Teal, small = true, enabled = info != null && !state.inviteBusy, tag = "invite-join")
                Btn("No thanks", { state.dropInvite() }, small = true, tag = "invite-no")
            }
        }
    }
}

/** The people part of the Account page: paste a link, people you blocked. */
@Composable
fun AccountPeople(state: AppState) {
    Card(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Column {
            T("PEOPLE", size = 12.sp, color = C.Yellow, spacing = .1.em)
            // Nov 28 (Tyler: "it forces you to have it in your clipboard"): type the code or paste the link into a box
            var typed by remember { mutableStateOf("") }
            OutlinedTextField(
                value = typed, onValueChange = { typed = it.take(300) }, singleLine = true,
                placeholder = { T("Invite code or link", color = C.Dim, weight = FontWeight.Medium) },
                textStyle = TextStyle(color = C.Cream, fontFamily = Archivo, fontSize = 16.sp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.Pink, unfocusedBorderColor = C.Card2, cursorColor = C.Pink),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).tagged("link-field"),
            )
            Wrap(Modifier.padding(top = 6.dp)) {
                Btn("Join", { if (state.openLinkText(typed)) typed = "" }, small = true, color = C.Teal, enabled = typed.isNotBlank(), tag = "link-open")
                Btn("Paste a link", { state.pasteLink() }, small = true, tag = "link-paste")
                Btn(if (state.blocks == null) "People you blocked" else "Hide blocked people", { state.toggleBlocks() }, small = true, on = state.blocks != null, tag = "acct-blocks")
            }
            val list = state.blocks
            if (list != null) {
                if (list.isEmpty()) T("You have blocked nobody.", size = 13.sp, weight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
                for (b in list) Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    T(b.name.ifEmpty { "No name" }, size = 15.sp, lines = 1, modifier = Modifier.weight(1f))
                    Btn("Unblock", { state.unblock(b.id) }, small = true, tag = "block-undo-${b.id.take(8)}")
                }
            }
        }
    }
}

/* ---------------- Slice E on a person: report (and block) ---------------- */

@Composable
private fun PersonExtras(state: AppState, cid: String, uid: String, name: String, @Suppress("UNUSED_PARAMETER") mine: Boolean) {
    if (uid == state.myUserId()) return
    Wrap(Modifier.padding(top = 6.dp)) {
        Btn(if (state.reportFor == uid) "Not now" else "Report", { state.openReport(uid) }, small = true, tier = Tier.Quiet, tag = "report-user-${uid.take(8)}")
    }
    if (state.reportFor == uid) ReportCard(state, cid, "", "Report $name", person = true)
}

/** The reasons, then Send. For a person there is also "Send and block". */
@Composable
private fun ReportCard(state: AppState, cid: String, token: String, title: String, person: Boolean) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).background(C.Black.copy(alpha = .35f)).padding(10.dp)) {
        T(title.uppercase(), size = 12.sp, color = C.Yellow, spacing = .1.em)
        T("What is wrong? We look at every report within 24 hours.", size = 12.sp, color = C.Dim, weight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
        Wrap {
            for ((id, word) in state.reportReasons) Btn(word, { state.reportReason = id }, small = true, on = state.reportReason == id, onColor = C.Yellow, tag = "report-r-$id")
        }
        Wrap(Modifier.padding(top = 8.dp)) {
            Btn("Send report", { state.sendReport(cid, token, false) }, small = true, color = C.Teal, enabled = state.reportReason.isNotEmpty(), tag = "report-send")
            if (person) Btn("Send and block", { state.sendReport(cid, token, true) }, small = true, tier = Tier.Danger, enabled = state.reportReason.isNotEmpty(), tag = "report-send-block")
            Btn("Never mind", { state.closeReport() }, small = true, tag = "report-no")
        }
    }
}

/* ---------------- Slice D on the Share sheet: the link, History; Slice E: report this mess ---------------- */

@Composable
private fun ShareExtras(state: AppState, cid: String, mine: Boolean) {
    var sureRestore by remember(cid) { mutableStateOf("") }
    if (mine) Card(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Column {
            T("A LINK TO LISTEN", size = 12.sp, color = C.Yellow, spacing = .1.em)
            val link = state.shareLink
            val mode = link?.share_mode ?: "off"
            T(when (mode) { "view" -> "Anyone with the link can listen. They cannot change it or copy it."; "copy" -> "Anyone with the link can listen and make their own copy."; else -> "Off. Nobody without an invite can open this mess." },
                size = 13.sp, weight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            Wrap {
                Btn("Off", { state.setShare(cid, "off", false) }, small = true, on = mode == "off", enabled = link != null, tag = "share-link-off")
                Btn("Listen", { state.setShare(cid, "view", false) }, small = true, on = mode == "view", onColor = C.Teal, enabled = link != null, tag = "share-link-view")
                Btn("Listen and copy", { state.setShare(cid, "copy", false) }, small = true, on = mode == "copy", onColor = C.Teal, enabled = link != null, tag = "share-link-copy")
            }
            if (link != null && mode != "off" && link.share_token.isNotEmpty()) {
                T(state.shareUrl(link.share_token), size = 12.sp, color = C.Cream, weight = FontWeight.Normal, family = Mono, modifier = Modifier.padding(top = 8.dp))
                Wrap(Modifier.padding(top = 8.dp)) {
                    Btn("Send the link", { state.sendShareLink(cid) }, small = true, color = C.Teal, tag = "share-link-send")
                    Btn("New link", { state.setShare(cid, mode, true) }, small = true, tag = "share-link-new")
                }
            }
        }
    }
    Card(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Column {
            T("HISTORY", size = 12.sp, color = C.Yellow, spacing = .1.em)
            val list = state.versions
            Btn(if (list == null) "Show saved versions" else "Hide them", { state.toggleHistory(cid) }, small = true, on = list != null, tag = "hist-open", modifier = Modifier.padding(top = 6.dp))
            if (list != null) {
                if (list.isEmpty()) T("No saved versions yet. Save makes one.", size = 13.sp, weight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
                list.forEachIndexed { n, ver ->
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp).tagged("hist-$n"), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            T(ver.label.ifEmpty { "Kept by itself" }, size = 14.sp, lines = 1)
                            T(ver.at.take(16).replace('T', ' ') + (if (ver.by_name.isNotEmpty()) ", " + ver.by_name else ""), size = 12.sp, color = C.Dim, weight = FontWeight.Medium, lines = 1)
                        }
                        Btn(if (sureRestore == ver.id) "Really?" else "Restore", {
                            if (sureRestore == ver.id) { sureRestore = ""; state.restoreVersion(cid, ver.id) } else sureRestore = ver.id
                        }, small = true, tier = if (sureRestore == ver.id) Tier.Danger else Tier.Normal, tag = "hist-restore-$n")
                    }
                }
                if (list.isNotEmpty()) T("Restore puts the mess back for everyone on it. What it is now is kept here as Before restore.", size = 12.sp, color = C.Dim,
                    weight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
    if (!mine) Card(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Column {
            Btn(if (state.reportFor == "") "Not now" else "Report this mess", { state.openReport("") }, small = true, tier = Tier.Quiet, tag = "report-mess")
            if (state.reportFor == "") ReportCard(state, cid, "", "Report this mess", person = false)
        }
    }
}

/** A share link's answer: the mess it points at, Listen, and (a copy link, signed in) Make it mine. */
@Composable
private fun SharedCard(state: AppState, s: CloudStore) {
    val token = state.pendingShare
    if (token.isEmpty()) return
    LaunchedEffect(token) { if (state.sharedMess == null) state.loadShared() }
    val m = state.sharedMess
    Card(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp)) {
        Column {
            T(if (m == null) "A SHARED MESS" else m.name.ifEmpty { "A mess" }.uppercase(), size = 12.sp, color = C.Yellow, spacing = .1.em)
            if (m != null) T("By " + m.owner_name.ifEmpty { "someone" } + ". " + (if (m.mode == "copy") "You can listen and make your own copy." else "You can listen."),
                size = 13.sp, weight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
            else if (state.sharedNote.isEmpty()) T("Looking it up.", size = 13.sp, color = C.Dim, weight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
            if (state.sharedNote.isNotEmpty()) T(state.sharedNote, size = 13.sp, color = C.Riot, weight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            Wrap(Modifier.padding(top = 8.dp)) {
                if (m != null) Btn("Listen", { state.openShared() }, color = C.Teal, small = true, tag = "shared-open")
                if (m != null && m.mode == "copy") Btn(if (state.sharedBusy) "Copying" else "Make it mine", { state.makeSharedMine() }, small = true, enabled = !state.sharedBusy, tag = "shared-mine")
                Btn("No thanks", { state.dropShared() }, small = true, tag = "shared-no")
            }
            if (m != null && m.mode == "copy" && s.state != CloudStore.IN) T("Make it mine needs you signed in (below).", size = 12.sp, color = C.Dim, weight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
            if (m != null) {
                Btn(if (state.reportFor == "") "Not now" else "Report this mess", { state.openReport("") }, small = true, tier = Tier.Quiet, tag = "report-mess", modifier = Modifier.padding(top = 10.dp))
                if (state.reportFor == "") ReportCard(state, "", token, "Report this mess", person = false)
            }
        }
    }
}

/* ---------------- Slice E on the Account sheet: delete the account ---------------- */

/** acct-delete, then what goes and what stays, a fresh code, and the last tap. */
@Composable
fun DeleteAccountCard(state: AppState, s: CloudStore) {
    var code by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp)) {
        Column {
            T("DELETE YOUR ACCOUNT", size = 12.sp, color = C.Riot, spacing = .1.em)
            T("This goes, for good:", size = 13.sp, modifier = Modifier.padding(top = 6.dp))
            T("Your account and your name.\nEvery mess you made online. The people on them lose them too.\nYour sounds online.\nSounds you sent to the Vault that nobody has listened to yet.",
                size = 13.sp, weight = FontWeight.Medium, modifier = Modifier.padding(top = 2.dp))
            T("This stays:", size = 13.sp, modifier = Modifier.padding(top = 8.dp))
            T("The messes and sounds on this phone.\nCopies other people made of your messes.\nSounds the Vault already took in.",
                size = 13.sp, weight = FontWeight.Medium, modifier = Modifier.padding(top = 2.dp))
            if (state.delStep == 1) {
                T("To be sure it is you, we mail a fresh code to " + s.email() + ".", size = 12.sp, color = C.Dim, weight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
                Wrap {
                    Btn("Send me a code", { state.deleteSendCode() }, small = true, enabled = !state.delBusy, tag = "del-send")
                    Btn("Keep my account", { state.deleteCancel() }, small = true, color = C.Teal, tag = "del-no")
                }
            } else {
                androidx.compose.material3.OutlinedTextField(
                    value = code, onValueChange = { t -> code = t.filter { it.isDigit() }.take(10) }, singleLine = true,
                    placeholder = { T("123456", color = C.Dim, weight = FontWeight.Medium) },
                    textStyle = androidx.compose.ui.text.TextStyle(color = C.Cream, fontFamily = Mono, fontSize = 20.sp),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(focusedBorderColor = C.Pink, unfocusedBorderColor = C.Card2, cursorColor = C.Pink),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).tagged("del-code"),
                )
                Wrap {
                    Btn(if (state.delBusy) "Deleting" else "Delete it all", { state.deleteGo(code) }, small = true, tier = Tier.Danger, enabled = !state.delBusy && code.length >= 6, tag = "del-go")
                    Btn("Keep my account", { state.deleteCancel() }, small = true, color = C.Teal, tag = "del-no")
                }
            }
            if (state.delNote.isNotEmpty()) T(state.delNote, size = 13.sp, color = C.Riot, weight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/* ---------------- Slice F: give a Workbench sound to the Vault ---------------- */

@Composable
fun VaultScreen(state: AppState, key: String) {
    val s = state.store ?: return
    var credit by remember(key) { mutableStateOf(s.me?.display_name ?: "") }
    var agreed by remember(key) { mutableStateOf(false) }
    val g = state.vaultGrant
    val line = state.vaultLine(key)
    ScrollPane(hPad = 14.dp, vPad = 0.dp) {
        Card(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp)) {
            Column {
                T("GIVE IT TO THE VAULT", size = 12.sp, color = C.Yellow, spacing = .1.em)
                T(state.lib.titleOf(key), size = 16.sp, lines = 1, modifier = Modifier.padding(top = 4.dp))
                T("The Vault is the sounds everyone gets with Mess Machine. We listen to every sound before it goes in.", size = 13.sp, weight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
                if (line != null) T(line, size = 13.sp, color = C.Teal, modifier = Modifier.padding(top = 8.dp))
            }
        }
        if (line == null || line.startsWith("Vault: not") || line.startsWith("Vault: taken")) Card(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
            Column {
                T("THE WORDS YOU AGREE TO", size = 12.sp, color = C.Yellow, spacing = .1.em)
                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).background(C.Black.copy(alpha = .35f)).tagged("vault-grant").padding(10.dp)) {
                    T(g?.body ?: "Fetching the words.", size = 13.sp, weight = FontWeight.Medium)
                }
                T("CREDIT IT TO", size = 12.sp, color = C.Yellow, spacing = .1.em)
                androidx.compose.material3.OutlinedTextField(
                    value = credit, onValueChange = { credit = it.take(40) }, singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = C.Cream, fontFamily = Archivo, fontSize = 16.sp),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(focusedBorderColor = C.Pink, unfocusedBorderColor = C.Card2, cursorColor = C.Pink),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).tagged("vault-credit"),
                )
                Wrap(Modifier.padding(top = 4.dp)) {
                    Btn(if (agreed) "I agree: yes" else "I agree", { agreed = !agreed }, small = true, on = agreed, onColor = C.Teal, enabled = g != null, tag = "vault-agree")
                    Btn(if (state.vaultBusy) "Sending" else "Send it", { state.vaultSend(key, credit) }, small = true, color = C.Teal,
                        enabled = agreed && g != null && credit.isNotBlank() && !state.vaultBusy, tag = "vault-send")
                }
                if (state.vaultNote.isNotEmpty()) T(state.vaultNote, size = 13.sp, color = C.Riot, weight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            }
        }
        T(" ", size = 30.sp)
    }
}
