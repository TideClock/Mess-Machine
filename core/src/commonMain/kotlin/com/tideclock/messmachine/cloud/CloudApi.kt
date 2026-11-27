package com.tideclock.messmachine.cloud

import kotlin.jvm.JvmField

/*
 * Mess Machine accounts: the client side of docs/ACCOUNTS-API.md section 4.1 and 4.3.
 * Common Kotlin: no java.*, no coroutines. One thread: every method and every callback here runs on one
 * thread (Android: the main thread). Network and file work happens elsewhere and the answer is posted back.
 * A callback is never run inside the call that took it (the one exception is the `unbuilt` default bodies
 * of CloudApi below: an interface has nowhere to post to, so they answer at once).
 */

/** code: a server code of s1.1, or net (no answer), auth (session dead), http (anything else), code_wrong,
 *  code_wait, bad_hash, unbuilt (this slice is not built yet). http: the status, 0 for none. */
class CloudErr(@JvmField val code: String, @JvmField val http: Int, @JvmField val detail: String) {
    override fun toString(): String = "CloudErr($code, $http, $detail)"
}

/** Exactly one of the two is null. Calls with nothing to return pass true. */
fun interface Done<T> { fun done(value: T?, err: CloudErr?) }

fun interface Clock { fun nowMs(): Long }

/** The signed-in session between launches: one string or null. Android seals it with the Keystore. */
interface TokenStore {
    fun load(): String?
    fun save(json: String?)
}

/** Small JSON files by name (index.json, <cloud id>.sounds.json, <mess>.base.json, <mess>.queue.json). null deletes. */
interface CloudCache {
    fun load(name: String): String?
    fun save(name: String, json: String?)
}

/** A refresh replaced the session (save it); null: it is dead (sign out). */
fun interface SessionListener { fun onSession(s: Session?) }

/** Slice C. */
interface RealtimeListener {
    fun onJoin(topic: String, ok: Boolean, reason: String)          // the join was answered
    fun onOps(topic: String, b: Batch)                              // an `ops` broadcast
    fun onMeta(topic: String, kind: String, gone: String, sha256: String)   // a `meta` broadcast
    fun onPresence(topic: String, peers: List<Presence>)            // the whole list, clamped, each time it changes
    fun onDrop(topic: String)                                       // the socket or the channel went away
}

/**
 * One method per server call of docs/ACCOUNTS-API.md section 1, in that order. `doc` and `opsJson` are JSON text.
 * Errors are the call's codes plus net, auth, http. Methods of slices that are not built yet have default
 * bodies that answer `unbuilt`; a builder overrides the ones for their slice.
 * FakeCloud and HttpCloud are the two implementations.
 */
interface CloudApi {
    fun configured(): Boolean                                       // false: no server in this build; call nothing else
    fun newId(): String                                             // a fresh random uuid, lower case (FakeCloud counts up)
    fun setSession(s: Session?)                                     // the token every later call carries; null signs out locally
    fun setSessionListener(l: SessionListener?)                     // the api refreshes once on a 401 and retries; it reports here

    // sign-in (s1.2)
    fun requestCode(email: String, cb: Done<Boolean>)               // mails a code; code_wait when asked too soon
    fun verifyCode(email: String, code: String, cb: Done<Session>)  // code_wrong, code_wait; does not call setSession
    fun refresh(refreshToken: String, cb: Done<Session>)            // auth means the session is dead; net means try again
    fun signOut(cb: Done<Boolean>)                                  // tells the server, then answers true whatever it said
    fun signInPassword(email: String, password: String, cb: Done<Session>) { unbuilt(cb) }   // Slice E, the review account only

    // Slice A: account and messes
    fun me(cb: Done<Me>)
    fun setProfile(displayName: String, cb: Done<Me>)
    fun acceptTerms(version: String, cb: Done<Boolean>)
    fun createMess(id: String, name: String, doc: String, cb: Done<Rev>)       // safe to repeat with the same id
    fun myMesses(cb: Done<List<MessRow>>)
    fun openMess(messId: String, cb: Done<Opened>)
    fun putDoc(messId: String, doc: String, baseRev: Long, label: String, cb: Done<Saved>)   // conflict when baseRev is old
    fun renameMess(messId: String, name: String, cb: Done<Rev>)
    fun deleteMess(messId: String, cb: Done<Boolean>)

    // Slice A: sounds. Files are named by a path on this device; the platform does the file work off the main thread.
    fun prepareUpload(key: String, cb: Done<Prepared>)              // finds the WAV for a bn: key and hashes it; not_found
    fun registerSound(p: Prepared, kind: String, localKey: String, title: String, bpm: Double, beats: Int, mkey: String, cb: Done<Registered>)
    fun upload(path: String, localFile: String, bytes: Long, cb: Done<Boolean>)   // to bucket sounds; "already there" is done
    fun finishUpload(sha256: String, cb: Done<Used>)
    fun mySounds(cb: Done<List<MySound>>)
    fun attachSound(messId: String, key: String, owner: String, sha256: String, cb: Done<Boolean>)
    fun deleteSound(sha256: String, cb: Done<Deleted>)              // one server call; the caller runs the three steps
    fun removeObject(path: String, cb: Done<Boolean>)               // deletes my object from bucket sounds
    fun soundUrls(messId: String, token: String, cb: Done<List<SoundUrl>>)     // exactly one of the two is not ""
    fun cachedSound(sha256: String, cb: Done<String>)               // the device path of verified audio with this hash, or not_found
    fun fetchSound(url: String, sha256: String, bytes: Long, cb: Done<String>)  // download, verify, answer the device path; bad_hash
    fun dropSound(sha256: String)                                   // forget the cached file

    // Slice B: ops and people
    fun opsSince(messId: String, from: Long, cb: Done<Since>) { unbuilt(cb) }       // the poll; gap means open again
    fun applyOps(messId: String, batchId: String, opsJson: String, v: Int, cb: Done<Applied>) { unbuilt(cb) }   // safe to repeat with the same id
    fun saveSnapshot(messId: String, doc: String, docRev: Long, v: Int, label: String, cb: Done<Snapped>) { unbuilt(cb) }
    fun members(messId: String, cb: Done<List<Member>>) { unbuilt(cb) }
    fun makeInvite(messId: String, days: Int, cb: Done<Invite>) { unbuilt(cb) }
    fun listInvites(messId: String, cb: Done<List<InviteRow>>) { unbuilt(cb) }
    fun revokeInvite(inviteId: String, cb: Done<Boolean>) { unbuilt(cb) }
    fun redeemInvite(token: String, cb: Done<Joined>) { unbuilt(cb) }
    fun inviteInfo(token: String, cb: Done<InviteInfo>) { unbuilt(cb) }             // works signed out
    fun removeMember(messId: String, userId: String, block: Boolean, cb: Done<Boolean>) { unbuilt(cb) }
    fun leaveMess(messId: String, cb: Done<Boolean>) { unbuilt(cb) }
    fun blockUser(userId: String, cb: Done<Boolean>) { unbuilt(cb) }
    fun myBlocks(cb: Done<List<Blocked>>) { unbuilt(cb) }
    fun unblockUser(userId: String, cb: Done<Boolean>) { unbuilt(cb) }
    fun joinLive(messId: String, cb: Done<Live>) { unbuilt(cb) }
    fun leaveLive(messId: String, cb: Done<Boolean>) { unbuilt(cb) }

    // Slice C: realtime. One socket for all topics; the caller joins again after onDrop.
    fun join(topic: String, l: RealtimeListener) { l.onJoin(topic, false, "unbuilt") }
    fun track(topic: String, state: Presence) {}
    fun leave(topic: String) {}

    // Slice D
    fun setShare(messId: String, mode: String, fresh: Boolean, cb: Done<Share>) { unbuilt(cb) }
    fun openShared(token: String, cb: Done<Shared>) { unbuilt(cb) }                 // works signed out
    fun forkMess(id: String, from: String, token: String, name: String, doc: String, cb: Done<Rev>) { unbuilt(cb) }
    fun versions(messId: String, cb: Done<List<VersionRow>>) { unbuilt(cb) }
    fun getVersion(versionId: String, cb: Done<Version>) { unbuilt(cb) }
    fun restoreVersion(messId: String, versionId: String, cb: Done<Rev>) { unbuilt(cb) }

    // Slice E and F
    fun reportMess(messId: String, token: String, reason: String, details: String, aboutUser: String, cb: Done<Boolean>) { unbuilt(cb) }
    fun deleteAccount(cb: Done<Boolean>) { unbuilt(cb) }                            // stale_login: ask for a fresh code first
    fun vaultGrant(cb: Done<Grant>) { unbuilt(cb) }
    fun vaultSubmit(sha256: String, creditName: String, grantVersion: String, note: String, cb: Done<Boolean>) { unbuilt(cb) }
}

/** The answer of a call whose slice is not built yet. */
fun <T> unbuilt(cb: Done<T>) { cb.done(null, CloudErr("unbuilt", 0, "")) }
