package com.tideclock.messmachine

import com.tideclock.messmachine.cloud.Applied
import com.tideclock.messmachine.cloud.Batch
import com.tideclock.messmachine.cloud.Clock
import com.tideclock.messmachine.cloud.CloudApi
import com.tideclock.messmachine.cloud.Done
import com.tideclock.messmachine.cloud.FakeCloudB
import com.tideclock.messmachine.cloud.Grant
import com.tideclock.messmachine.cloud.Joined
import com.tideclock.messmachine.cloud.MySound
import com.tideclock.messmachine.cloud.Opened
import com.tideclock.messmachine.cloud.Presence
import com.tideclock.messmachine.cloud.RealtimeListener
import com.tideclock.messmachine.cloud.Rev
import com.tideclock.messmachine.cloud.Saved
import com.tideclock.messmachine.cloud.Session
import com.tideclock.messmachine.cloud.Sha256
import com.tideclock.messmachine.cloud.Share
import com.tideclock.messmachine.cloud.Shared
import com.tideclock.messmachine.cloud.Since
import com.tideclock.messmachine.cloud.Sound
import com.tideclock.messmachine.cloud.SoundUrl
import com.tideclock.messmachine.cloud.Used
import com.tideclock.messmachine.cloud.Version
import com.tideclock.messmachine.cloud.VersionRow
import com.tideclock.messmachine.engine.Json

/**
 * FakeCloudB plus the server of Slices C to F as the phone sees it (docs/ACCOUNTS-API.md s1.6 to s1.9 and s2), in
 * memory, for the app's tests: the live channel (a topic with a key that turns, `ops` and `meta` broadcasts, presence),
 * share links, fork, versions and restore, reports, delete-account with its fresh sign-in rule, the review account's
 * password sign-in, and the Vault grant and submissions. The real server has its own 1,239 checks; this is only
 * enough of it for the app to be driven end to end.
 */
class FakeCloudAll(clock: Clock) : FakeCloudB(clock) {
    /** false (the default): no live channel at all, the server of Slice B. */
    @JvmField var live = false
    /** true: the socket cannot be reached; joins fail and the poll carries everything. */
    @JvmField var socketsDown = false
    @JvmField var reviewEmail = ""
    @JvmField var reviewPassword = ""
    @JvmField var grant = Grant("2026-11", "You made this sound yourself and you let Mess Machine give it to everyone, for free, for good.", "")

    private class Sub(val c: Client, val topic: String, val l: RealtimeListener) { var state: Presence? = null }
    private val subs = ArrayList<Sub>()
    private var keySeq = 0

    class Report(@JvmField val mess: String, @JvmField val by: String, @JvmField val reason: String, @JvmField val details: String,
                 @JvmField val about: String, @JvmField val viaToken: Boolean)
    @JvmField val reports = ArrayList<Report>()
    class Submission(@JvmField val id: String, @JvmField val user: String, @JvmField val sha256: String, @JvmField val credit: String, @JvmField val note: String) {
        @JvmField var status = "pending"
    }
    @JvmField val submissions = ArrayList<Submission>()
    private val signedInAt = HashMap<Client, Long>()
    private var shareSeq = 0

    /* ---------- test controls ---------- */

    fun topic(messId: String): String = messes[messId]?.topic ?: ""
    fun joinedCount(messId: String): Int { val t = topic(messId); var n = 0; for (s in subs) if (s.topic == t && t.isNotEmpty()) n++; return n }
    /** The socket of one phone dies: every channel it held is dropped. */
    fun dropSockets(c: CloudApi) {
        val cl = phone(unwrap(c))
        val mine = subs.filter { it.c === cl }
        subs.removeAll(mine)
        for (s in mine) { enqueue(null, false) { s.l.onDrop(s.topic) }; pushPresence(s.topic) }
    }
    fun shareToken(messId: String): String = messes[messId]?.shareToken ?: ""
    fun shareMode(messId: String): String = messes[messId]?.shareMode ?: ""
    fun userExists(email: String): Boolean = userId(email).isNotEmpty() && users.containsKey(userId(email))
    fun review(submissionId: String, approved: Boolean) { for (s in submissions) if (s.id == submissionId && s.status == "pending") s.status = if (approved) "approved" else "declined" }
    /** Tests hand in the phone's own CloudApi, which may be a wrapper: the wrapper says which client it stands for. */
    @JvmField var unwrap: (CloudApi) -> CloudApi = { it }

    /* ---------- Slice C: the live channel ---------- */

    private fun ensureTopic(m: FakeMess?) {
        if (m == null || !live) return
        if (m.topic.isEmpty()) m.topic = "mess:" + m.id + ":" + (++keySeq).toString(16).padStart(16, '0')
    }
    private fun rotate(m: FakeMess) {
        if (!live) return
        val old = m.topic
        m.topic = "mess:" + m.id + ":" + (++keySeq).toString(16).padStart(16, '0')
        val gone = subs.filter { it.topic == old }
        subs.removeAll(gone)
        for (s in gone) enqueue(null, false) { s.l.onDrop(old) }
    }
    /** still: only to phones still on the channel when it arrives (false for the last word before the key turns). */
    private fun cast(m: FakeMess, still: Boolean = true, send: (Sub) -> Unit) {
        if (!live || m.topic.isEmpty()) return
        for (s in ArrayList(subs)) if (s.topic == m.topic) enqueue(null, false) { if (s.c.online && (!still || subs.contains(s))) send(s) }
    }
    private fun meta(m: FakeMess, kind: String, gone: String, sha: String, still: Boolean = true) = cast(m, still) { s -> s.l.onMeta(s.topic, kind, gone, sha) }
    private fun pushPresence(topic: String) {
        val list = ArrayList<Presence>()
        for (s in subs) if (s.topic == topic) s.state?.let { list.add(it) }
        for (s in ArrayList(subs)) if (s.topic == topic) enqueue(null, false) { if (subs.contains(s)) s.l.onPresence(topic, ArrayList(list)) }
    }

    override fun srvOpenMess(u: FakeUser, messId: String): Opened { ensureTopic(messes[messId]); return super.srvOpenMess(u, messId) }
    override fun srvOpsSince(u: FakeUser, messId: String, from: Long): Since { ensureTopic(messes[messId]); return super.srvOpsSince(u, messId, from) }

    override fun srvApplyOps(u: FakeUser, messId: String, batch: String, opsJson: String, v: Int): Applied {
        val a = super.srvApplyOps(u, messId, batch, opsJson, v)
        val m = messes[messId]
        if (m != null) {
            val big = opsJson.encodeToByteArray().size > 32768
            cast(m) { s -> s.l.onOps(s.topic, Batch(a.rev, u.id, batch, v, if (big) null else Json.list(Json.parse(opsJson)), a.at)) }
        }
        return a
    }
    override fun srvPutDoc(u: FakeUser, messId: String, doc: String, baseRev: Long, label: String): Saved {
        val s = super.srvPutDoc(u, messId, doc, baseRev, label)
        val m = messes[messId]
        if (m != null) cast(m) { x -> x.l.onOps(x.topic, Batch(s.rev, u.id, "", 1, Json.list(Json.parse("[{\"t\":\"doc.reset\"}]")), iso(nowMs()))) }
        return s
    }
    override fun srvRedeemInvite(u: FakeUser, token: String): Joined {
        val j = super.srvRedeemInvite(u, token)
        messes[j.mess_id]?.let { meta(it, "members", "", "") }
        return j
    }
    override fun srvRemoveMember(u: FakeUser, messId: String, user: String, block: Boolean): Boolean {
        val m = messes[messId]
        // the real server sends the meta before the key turns; the checks run first, so a refused call sends nothing
        val ok = super.srvRemoveMember(u, messId, user, block)
        if (m != null) { meta(m, "members", user, "", still = false); rotate(m) }
        return ok
    }
    override fun srvFinishUpload(u: FakeUser, sha256: String): Used {
        val r = super.srvFinishUpload(u, sha256)
        for (m in messes.values) for (ms in m.sounds.values) if (ms.file.sha256 == sha256 && ms.file.owner == u.id) meta(m, "sound", "", sha256)
        return r
    }
    override fun srvAttachSound(u: FakeUser, messId: String, key: String, owner: String, sha256: String): Boolean {
        val r = super.srvAttachSound(u, messId, key, owner, sha256)
        // the real server tells the mess when a listed sound turns ready; here the row is made after the file, so say it now
        messes[messId]?.let { meta(it, "sound", "", sha256) }
        return r
    }
    override fun srvDeleteMess(u: FakeUser, messId: String): Boolean {
        val m = messes[messId]
        val ok = super.srvDeleteMess(u, messId)
        if (m != null) { meta(m, "deleted", "", "", still = false); val t = m.topic; val gone = subs.filter { it.topic == t }; subs.removeAll(gone) }
        return ok
    }

    /* ---------- Slice D: share links and History ---------- */

    private fun newToken(): String = ("shr" + (++shareSeq).toString(36) + "y".repeat(32)).substring(0, 32)

    private fun srvSetShare(u: FakeUser, messId: String, mode: String, fresh: Boolean): Share {
        val m = ownerOf(u, messId)
        if (m.blocked) fail("blocked")
        if (mode != "off" && mode != "view" && mode != "copy") fail("bad_input")
        if (mode == "off") m.shareToken = ""
        else if (m.shareMode == "off" || m.shareToken.isEmpty() || fresh) m.shareToken = newToken()
        m.shareMode = mode
        return Share(m.shareMode, m.shareToken)
    }
    private fun sharedMess(token: String): FakeMess {
        if (token.isEmpty()) fail("not_found")
        for (m in messes.values) if (m.shareToken == token && m.shareMode != "off" && !m.blocked && users[m.owner]?.banned != true) return m
        fail("not_found")
    }
    private fun srvOpenShared(token: String): Shared {
        val m = sharedMess(token)
        val ops = ArrayList<Batch>()
        for (b in m.ops) if (b.rev > m.docRev) ops.add(Batch(b.rev, "", b.batch, b.v, b.ops, b.at))
        val sounds = ArrayList<Sound>()
        for (r in m.sounds.values) { val s = soundOf(r); sounds.add(Sound(s.key, s.sha256, s.src, "", s.fmt, s.bytes, s.seconds, s.rate, s.kind, s.title, s.bpm, s.beats, s.mkey, s.state)) }
        return Shared(m.name, users[m.owner]?.displayName ?: "", m.shareMode, m.rev, m.docRev, m.doc, ops, sounds)
    }
    private fun srvForkMess(u: FakeUser, id: String, from: String, token: String, name: String, doc: String): Rev {
        val src: FakeMess = if (from.isNotEmpty()) memberOf(u, from) else { val m = sharedMess(token); if (m.shareMode != "copy") fail("no_copy"); m }
        val r = srvCreateMess(u, id, name, doc)
        val made = messes[id]
        if (made != null && made !== src) for ((k, row) in src.sounds) if (!made.sounds.containsKey(k)) made.sounds[k] = FakeMessSound(k, row.file, 0L).also { it.src = row.src }
        return r
    }
    private fun srvVersions(u: FakeUser, messId: String): List<VersionRow> {
        val m = memberOf(u, messId)
        val out = ArrayList<VersionRow>()
        for (v in m.versions.asReversed()) out.add(VersionRow(v.id, v.rev, v.label, users[v.by]?.displayName ?: "", iso(v.at), v.doc.encodeToByteArray().size))
        return out
    }
    private fun srvGetVersion(u: FakeUser, versionId: String): Version {
        for (m in messes.values) if (m.members.containsKey(u.id)) for (v in m.versions) if (v.id == versionId) return Version(v.id, v.rev, v.label, v.doc)
        fail("not_found")
    }
    private fun srvRestoreVersion(u: FakeUser, messId: String, versionId: String): Rev {
        val m = memberOf(u, messId)
        if (m.blocked) fail("blocked")
        val v = m.versions.firstOrNull { it.id == versionId } ?: fail("not_found")
        if (m.docRev != m.rev) fail("snapshot_first")
        val now = nowMs()
        m.versions.add(FakeVersion(nextId(), m.rev, "Before restore", m.doc, u.id, now))
        trimVersions(m)
        m.doc = v.doc
        m.rev = m.rev + 1; m.docRev = m.rev
        val at = iso(now)
        val reset = "[{\"t\":\"doc.reset\"}]"
        bOf(m).log.add(Stored(m.rev, u.id, "", 1, reset, at, 18))
        m.ops.add(Batch(m.rev, u.id, "", 1, Json.list(Json.parse(reset)), at))
        touch(m)
        cast(m) { x -> x.l.onOps(x.topic, Batch(m.rev, u.id, "", 1, Json.list(Json.parse(reset)), at)) }
        return Rev(m.id, m.rev)
    }
    override fun srvSoundUrls(u: FakeUser?, messId: String, token: String): List<SoundUrl> {
        if (messId.isEmpty() && token.isNotEmpty()) {
            val m = sharedMess(token)
            val out = ArrayList<SoundUrl>()
            val exp = nowMs() + 600000L
            for (r in m.sounds.values) {
                val f = r.file
                if (r.src == "gone" || f.state != "ready" || f.blocked || users[f.owner]?.banned == true) continue
                out.add(SoundUrl(r.key, f.sha256, f.bytes, "fake://sounds/" + f.path() + "?exp=" + exp))
            }
            return out
        }
        return super.srvSoundUrls(u, messId, token)
    }

    /* ---------- Slice E: reports, delete account, the review account ---------- */

    private fun srvReportMess(u: FakeUser?, messId: String, token: String, reason: String, details: String, about: String): Boolean {
        if (reason !in listOf("sexual", "hate", "harassment", "copyright", "spam", "other") || details.length > 500) fail("bad_input")
        if (token.isNotEmpty()) {
            val m = sharedMess(token)
            if (about.isNotEmpty()) fail("bad_input")
            reports.add(Report(m.id, u?.id ?: "", reason, details, "", true))
            return true
        }
        if (u == null) fail("not_found")
        val m = memberOf(u, messId)
        if (about.isEmpty()) { if (m.owner == u.id) fail("bad_input") }
        else if (about == u.id || !m.members.containsKey(about)) fail("bad_input")
        var today = 0
        for (r in reports) if (r.by == u.id) today++
        if (today >= 5) fail("rate_limited")
        reports.add(Report(m.id, u.id, reason, details, about, false))
        return true
    }
    private fun srvDeleteAccount(c: Client, u: FakeUser): Boolean {
        val at = signedInAt[c] ?: 0L
        if (nowMs() - at > 600000L) fail("stale_login")
        for (m in ArrayList(messes.values)) {
            if (m.owner == u.id) { meta(m, "deleted", "", ""); messes.remove(m.id) }
            else if (m.members.containsKey(u.id)) { dropMember(m, u.id); meta(m, "members", u.id, "") }
        }
        for (k in ArrayList(files.keys)) if (files[k]?.owner == u.id) { objects.remove("sounds/" + files[k]!!.path()); files.remove(k) }
        submissions.removeAll { it.user == u.id && it.status == "pending" }
        users.remove(u.id)
        byEmail.remove(u.email)
        for (k in ArrayList(tokens.keys)) if (tokens[k]?.user == u.id) tokens.remove(k)
        for (k in ArrayList(refreshes.keys)) if (refreshes[k] == u.id) refreshes.remove(k)
        return true
    }

    /* ---------- Slice F: the Vault ---------- */

    private fun srvVaultGrant(): Grant = Grant(grant.version, grant.body, Sha256.hex(grant.body.encodeToByteArray()))
    private fun srvVaultSubmit(u: FakeUser, sha256: String, credit: String, grantVersion: String, note: String): Boolean {
        val f = files[u.id + "/" + sha256]
        if (f == null || f.state != "ready") fail("not_ready")
        if (grantVersion != grant.version) fail("grant_outdated")
        val name = try { cleanName(credit, 40) } catch (e: FakeErr) { fail("bad_name") }
        for (s in submissions) if (s.sha256 == sha256 && (s.status == "pending" || s.status == "approved")) fail("already_submitted")
        var pending = 0
        for (s in submissions) if (s.user == u.id && s.status == "pending") pending++
        if (pending >= 10) fail("too_many_pending")
        if (note.length > 500) fail("bad_input")
        submissions.add(Submission(nextId(), u.id, sha256, name, note))
        return true
    }
    override fun srvMySounds(u: FakeUser): List<MySound> {
        val out = ArrayList<MySound>()
        for (s in super.srvMySounds(u)) {
            var st = ""
            for (x in submissions) if (x.user == u.id && x.sha256 == s.sha256) st = x.status
            out.add(MySound(s.sha256, s.title, s.kind, s.bytes, s.seconds, s.state, s.messes, st))
        }
        return out
    }

    /* ---------- one phone ---------- */

    override fun newClient(email: String): Client = ClientAll(email)

    inner class ClientAll(email: String) : ClientB(email) {
        override fun verifyCode(email: String, code: String, cb: Done<Session>) {
            super.verifyCode(email, code, Done { s, e -> if (s != null) signedInAt[this] = nowMs(); cb.done(s, e) })
        }
        override fun signInPassword(email: String, password: String, cb: Done<Session>) {
            log.add("signInPassword")
            call(this, cb) {
                if (reviewEmail.isEmpty() || email.trim().lowercase() != reviewEmail || password != reviewPassword) fail("code_wrong", 400)
                signedInAt[this] = nowMs()
                newSession(reviewEmail)
            }
        }
        // Slice C
        override fun join(topic: String, l: RealtimeListener) {
            log.add("join")
            enqueue(this, false) {
                var ok = live && !socketsDown && online
                val u = if (ok) callerOrNull(this) else null
                var m: FakeMess? = null
                if (u != null) for (x in messes.values) if (x.topic == topic && x.members.containsKey(u.id)) m = x
                if (u == null || m == null) ok = false
                if (!ok) l.onJoin(topic, false, "refused")
                else {
                    subs.removeAll { it.c === this && it.topic == topic }
                    subs.add(Sub(this, topic, l))
                    l.onJoin(topic, true, "")
                    pushPresence(topic)
                }
            }
        }
        override fun track(topic: String, state: Presence) {
            log.add("track")
            for (s in subs) if (s.c === this && s.topic == topic) { s.state = RealtimeClient.clamp(state); pushPresence(topic) }
        }
        override fun leave(topic: String) {
            log.add("leave")
            if (subs.removeAll { it.c === this && it.topic == topic }) pushPresence(topic)
        }
        // Slice D
        override fun setShare(messId: String, mode: String, fresh: Boolean, cb: Done<Share>) {
            log.add("setShare"); call(this, cb) { val u = caller(this); terms(u); srvSetShare(u, messId, mode, fresh) }
        }
        override fun openShared(token: String, cb: Done<Shared>) { log.add("openShared"); call(this, cb) { srvOpenShared(token) } }
        override fun forkMess(id: String, from: String, token: String, name: String, doc: String, cb: Done<Rev>) {
            log.add("forkMess"); call(this, cb) { val u = caller(this); terms(u); srvForkMess(u, id, from, token, name, doc) }
        }
        override fun versions(messId: String, cb: Done<List<VersionRow>>) { log.add("versions"); call(this, cb) { srvVersions(caller(this), messId) } }
        override fun getVersion(versionId: String, cb: Done<Version>) { log.add("getVersion"); call(this, cb) { srvGetVersion(caller(this), versionId) } }
        override fun restoreVersion(messId: String, versionId: String, cb: Done<Rev>) {
            log.add("restoreVersion"); call(this, cb) { val u = caller(this); terms(u); srvRestoreVersion(u, messId, versionId) }
        }
        override fun soundUrls(messId: String, token: String, cb: Done<List<SoundUrl>>) {
            if (token.isEmpty()) { super.soundUrls(messId, token, cb); return }
            log.add("soundUrls"); call(this, cb) { srvSoundUrls(callerOrNull(this), messId, token) }
        }
        // Slice E and F
        override fun reportMess(messId: String, token: String, reason: String, details: String, aboutUser: String, cb: Done<Boolean>) {
            log.add("reportMess"); call(this, cb) { srvReportMess(callerOrNull(this), messId, token, reason, details, aboutUser) }
        }
        override fun deleteAccount(cb: Done<Boolean>) { log.add("deleteAccount"); call(this, cb) { srvDeleteAccount(this, caller(this)) } }
        override fun vaultGrant(cb: Done<Grant>) { log.add("vaultGrant"); call(this, cb) { srvVaultGrant() } }
        override fun vaultSubmit(sha256: String, creditName: String, grantVersion: String, note: String, cb: Done<Boolean>) {
            log.add("vaultSubmit"); call(this, cb) { val u = caller(this); terms(u); srvVaultSubmit(u, sha256, creditName, grantVersion, note) }
        }
    }
}
