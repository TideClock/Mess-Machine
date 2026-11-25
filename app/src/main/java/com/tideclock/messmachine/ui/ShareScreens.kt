package com.tideclock.messmachine.ui

import androidx.compose.foundation.background
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
fun personColor(colour: Int): Color = when (Math.floorMod(colour, 8)) {
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
                        T(if (m.role == "owner") "made it" else "can edit", size = 12.sp, color = C.Dim, weight = FontWeight.Medium)
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
            Wrap(Modifier.padding(top = 6.dp)) {
                Btn("Paste a link", { state.pasteLink() }, small = true, tag = "link-paste")
                Btn(if (state.blocks == null) "People you blocked" else "Hide blocked people", { state.toggleBlocks() }, small = true, on = state.blocks != null, tag = "acct-blocks")
            }
            T("Got an invite link in a message? Copy it, then Paste a link.", size = 12.sp, color = C.Dim, weight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
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

/* ---- filled in by the later slices ---- */
/*extras-begin*/
@Composable
private fun PersonExtras(@Suppress("UNUSED_PARAMETER") state: AppState, @Suppress("UNUSED_PARAMETER") cid: String, @Suppress("UNUSED_PARAMETER") uid: String,
                         @Suppress("UNUSED_PARAMETER") name: String, @Suppress("UNUSED_PARAMETER") mine: Boolean) {}

@Composable
private fun ShareExtras(@Suppress("UNUSED_PARAMETER") state: AppState, @Suppress("UNUSED_PARAMETER") cid: String, @Suppress("UNUSED_PARAMETER") mine: Boolean) {}

@Composable
private fun SharedCard(@Suppress("UNUSED_PARAMETER") state: AppState, @Suppress("UNUSED_PARAMETER") s: CloudStore) {}
/*extras-end*/
