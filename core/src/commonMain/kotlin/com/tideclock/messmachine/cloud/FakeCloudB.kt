package com.tideclock.messmachine.cloud

import com.tideclock.messmachine.engine.Json
import kotlin.jvm.JvmField

/**
 * FakeCloud plus the Slice B server (docs/ACCOUNTS-API.md s1.5), in memory, with the same checks, order and error
 * codes: apply_ops (server order by rev, batch id idempotency, role checks, the rate limit, ops_v_max, snapshot_first,
 * day_limit, live seats, the owner-only name rule), ops_since (gap, 500 batches and 1 MB), save_snapshot (trimming),
 * single-use invites, redeem, members, remove, leave, block, live seats (4). put_doc and rename_mess change as s1.4
 * says from Slice B: put_doc stores a doc.reset batch, rename_mess appends a mess.set name batch.
 *
 * Every answer is a fresh copy (ops are stored as JSON text and parsed for each answer), so no phone can change
 * what another phone or the server holds.
 */
open class FakeCloudB(clock: Clock) : FakeCloud(clock) {

    /** Server tuning the tests may change: config.ops_v_max and ops_day_bytes are in FakeCloud. */
    @JvmField var batchCap = 500
    @JvmField var bytesCap = 262144
    @JvmField var snapRevs = 2000L
    @JvmField var snapBytes = 2097152L
    @JvmField var keepBelow = 500L
    @JvmField var sinceBatches = 500
    @JvmField var sinceBytes = 1048576
    /** apply_ops step 5: batches a person may send to one mess in one second. */
    @JvmField var ratePerSec = 4

    protected class Stored(@JvmField val rev: Long, @JvmField val user: String, @JvmField val batch: String, @JvmField val v: Int,
                           @JvmField val json: String, @JvmField val at: String, @JvmField val bytes: Int)
    protected class BatchRec(@JvmField val mess: String, @JvmField val user: String, @JvmField val rev: Long, @JvmField val at: String)
    protected class FakeInvite(@JvmField val id: String, @JvmField val mess: String, @JvmField val tokenHash: String, @JvmField val expires: Long) {
        @JvmField var usesLeft = 1
        @JvmField var revoked = false
    }
    /** What Slice B adds to a mess row. */
    protected class MB {
        @JvmField val log = ArrayList<Stored>()
        @JvmField var trimmedTo = 0L                         // every batch at or below this rev is gone
        @JvmField val seats: MutableMap<String, Long> = LinkedHashMap()        // user id to seen_at
        @JvmField val sendTimes: MutableMap<String, MutableList<Long>> = LinkedHashMap()
        @JvmField val labelTimes: MutableMap<String, MutableList<Long>> = LinkedHashMap()
        @JvmField var hadOps = false                         // an apply_ops batch was ever stored (rename_mess then goes by op)
    }

    protected val mb: MutableMap<String, MB> = LinkedHashMap()
    protected val batchIds: MutableMap<String, BatchRec> = LinkedHashMap()
    protected val invites: MutableMap<String, FakeInvite> = LinkedHashMap()
    protected val dayBytes: MutableMap<String, Long> = LinkedHashMap()      // "<user>/<day>" to op bytes
    private var tokenSeq = 0L

    protected fun bOf(m: FakeMess): MB = mb.getOrPut(m.id) { MB() }

    /* ---------- test controls and looks ---------- */

    /** Batches stored for this mess (trimmed ones not counted). */
    fun batchCount(messId: String): Int = messes[messId]?.let { bOf(it).log.size } ?: 0
    fun docRev(messId: String): Long = messes[messId]?.docRev ?: -1L
    fun trimmedTo(messId: String): Long = messes[messId]?.let { bOf(it).trimmedTo } ?: 0L
    /** The ops of every stored batch after `from`, as one JSON array per batch, oldest first. */
    fun batchesJson(messId: String, from: Long): List<String> {
        val m = messes[messId] ?: return emptyList()
        val out = ArrayList<String>()
        for (s in bOf(m).log) if (s.rev > from) out.add(s.json)
        return out
    }
    fun batchUser(messId: String, rev: Long): String { val m = messes[messId] ?: return ""; for (s in bOf(m).log) if (s.rev == rev) return s.user; return "" }
    fun memberCount(messId: String): Int = messes[messId]?.members?.size ?: 0
    fun isMember(messId: String, email: String): Boolean = messes[messId]?.members?.containsKey(userId(email)) == true
    fun seats(messId: String): Int = messes[messId]?.let { live(it, "", nowMs()) } ?: 0

    /* ---------- helpers ---------- */

    private fun utf8b(s: String): Int = s.encodeToByteArray().size
    private fun day(ms: Long): Long = ms.floorDiv(86400000L)

    protected fun copyBatch(s: Stored): Batch = Batch(s.rev, s.user, s.batch, s.v, Json.list(Json.parse(s.json)), s.at)

    /** How many seats other than `me` were seen in the last 90 s. */
    protected fun live(m: FakeMess, me: String, now: Long): Int {
        var n = 0
        for ((u, at) in bOf(m).seats) if (u != me && now - at < 90000L && m.members.containsKey(u)) n++
        return n
    }
    /** I hold a seat, or under 4 others hold one: take (or refresh) mine. false: watch only. */
    protected fun takeSeat(m: FakeMess, u: FakeUser, now: Long): Boolean {
        val b = bOf(m)
        val mine = b.seats[u.id]
        if (mine != null && now - mine < 90000L) { b.seats[u.id] = now; return true }
        if (live(m, u.id, now) >= liveLimit) return false
        b.seats[u.id] = now
        return true
    }

    /** Stores one batch at rev + 1 (apply_ops step 10). */
    protected fun store(m: FakeMess, u: String, batch: String, v: Int, json: String, bytes: Int, ms: Long): Long {
        m.rev = m.rev + 1
        if (v > m.opsV) m.opsV = v
        m.opsBytes += bytes
        val at = iso(ms)
        bOf(m).log.add(Stored(m.rev, u, batch, v, json, at, bytes))
        if (batch.isNotEmpty()) batchIds[batch] = BatchRec(m.id, u, m.rev, at)
        touch(m)
        // open_mess hands out every batch after doc_rev from FakeMess.ops
        m.ops.add(copyBatchLater(m.rev, u, batch, v, json, at))
        return m.rev
    }
    private fun copyBatchLater(rev: Long, u: String, batch: String, v: Int, json: String, at: String): Batch = Batch(rev, u, batch, v, Json.list(Json.parse(json)), at)

    protected fun liveInvite(token: String): FakeInvite? {
        val h = Sha256.hex(token.encodeToByteArray())
        for (i in invites.values) if (i.tokenHash == h) {
            val m = messes[i.mess] ?: return null
            if (i.revoked || i.usesLeft <= 0 || nowMs() >= i.expires || m.blocked) return null
            return i
        }
        return null
    }

    /* ---------- Slice B server functions ---------- */

    protected open fun srvOpsSince(u: FakeUser, messId: String, from: Long): Since {
        val m = memberOf(u, messId)
        val b = bOf(m)
        if (from + 1 <= b.trimmedTo) fail("gap")
        val seat = b.seats[u.id]
        if (seat != null) b.seats[u.id] = nowMs()
        val out = ArrayList<Batch>()
        var bytes = 0
        for (s in b.log) {
            if (s.rev <= from) continue
            if (out.size >= sinceBatches || (out.isNotEmpty() && bytes + s.bytes > sinceBytes)) break
            out.add(copyBatch(s)); bytes += s.bytes
        }
        return Since(m.rev, m.docRev, m.members.size, m.topic, out)
    }

    protected open fun srvApplyOps(u: FakeUser, messId: String, batch: String, opsJson: String, v: Int): Applied {
        // 1
        if (batch.isEmpty()) fail("bad_input")
        val parsed = try { Json.parse(opsJson) } catch (e: RuntimeException) { fail("bad_input") }
        if (parsed !is List<*> || parsed.isEmpty() || parsed.size > batchCap || v < 1 || v > opsVMax) fail("bad_input")
        // 2
        val bytes = utf8b(opsJson)
        if (bytes > bytesCap) fail("too_big")
        // 3
        val ts = nowMs()
        val m = memberOf(u, messId)
        if (m.blocked) fail("blocked")
        // 4
        val had = batchIds[batch]
        if (had != null) { if (had.user == u.id && had.mess == m.id) return Applied(had.rev, had.at); fail("bad_input") }
        // 5
        val b = bOf(m)
        val times = b.sendTimes.getOrPut(u.id) { ArrayList() }
        times.removeAll { ts - it >= 1000L }
        if (times.size >= ratePerSec) fail("rate_limited")
        // 6
        val dk = u.id + "/" + day(ts)
        val today = dayBytes[dk] ?: 0L
        if (today + bytes > opsDayBytes) fail("day_limit")
        // 7
        if (!takeSeat(m, u, ts)) fail("mess_full_live")
        // 8
        var newName: String? = null
        for (o in parsed) {
            val om = Json.map(o)
            if (om["t"] == "mess.set" && om["f"] == "name") {
                val nv = om["v"]
                if (nv !is String) fail("bad_input")
                newName = nv
            }
        }
        if (newName != null) {
            for (o in parsed) { val om = Json.map(o); if (om["t"] == "mess.set" && om["f"] == "name" && om["v"] !is String) fail("bad_input") }
            if (m.owner != u.id) fail("forbidden")
            m.name = cleanName(newName, 48)
        }
        // 9
        if (m.rev - m.docRev >= snapRevs || m.opsBytes > snapBytes) fail("snapshot_first")
        // 10
        times.add(ts)
        dayBytes[dk] = today + bytes
        b.hadOps = true
        val rev = store(m, u.id, batch, v, opsJson, bytes, ts)
        return Applied(rev, iso(ts))
    }

    protected open fun srvSaveSnapshot(u: FakeUser, messId: String, doc: String, docRev: Long, v: Int, label: String): Snapped {
        val m = memberOf(u, messId)
        if (m.blocked) fail("blocked")
        if (v < 1 || v > opsVMax || !isObject(doc) || label.length > 40) fail("bad_input")
        if (v < m.opsV) fail("client_too_old")
        if (docRev > m.rev) fail("ahead")
        if (!(docRev > m.docRev || (docRev == m.docRev && label.isNotEmpty()))) fail("stale")
        if (utf8b(doc) > messBytes) fail("too_big")
        val now = nowMs()
        val version = label.isNotEmpty() || m.versions.isEmpty() || now - m.versions[m.versions.size - 1].at > 600000L
        val grow = utf8b(doc).toLong() - utf8b(m.doc) + (if (version) utf8b(doc) else 0)
        if (docBytes(m.owner) + grow > docsLimit) fail("docs_full")
        val b = bOf(m)
        if (label.isNotEmpty()) {
            val lt = b.labelTimes.getOrPut(u.id) { ArrayList() }
            lt.removeAll { now - it >= 60000L }
            if (lt.size >= 6) fail("rate_limited")
            lt.add(now)
        }
        m.doc = doc; m.docRev = docRev; m.opsBytes = 0L
        val cut = docRev - keepBelow
        if (cut > b.trimmedTo) {
            b.log.removeAll { it.rev < cut }
            m.ops.removeAll { it.rev < cut }
            b.trimmedTo = cut - 1
        }
        var vid = ""
        if (version) { vid = nextId(); m.versions.add(FakeVersion(vid, docRev, label, doc, u.id, now)); trimVersions(m) }
        return Snapped(docRev, vid)
    }

    protected open fun srvMembers(u: FakeUser, messId: String): List<Member> = memberList(memberOf(u, messId))

    protected fun ownerOf(u: FakeUser, messId: String): FakeMess {
        val m = memberOf(u, messId)
        if (m.owner != u.id) fail("forbidden")
        return m
    }

    protected open fun srvMakeInvite(u: FakeUser, messId: String, days: Int): Invite {
        val m = ownerOf(u, messId)
        if (m.blocked) fail("blocked")
        if (days < 1 || days > 30) fail("bad_input")
        val now = nowMs()
        var liveN = 0
        for (i in invites.values) if (i.mess == m.id && !i.revoked && i.usesLeft > 0 && now < i.expires) liveN++
        if (liveN >= 20) fail("too_many_invites")
        val tok = ("tok" + (++tokenSeq).toString(36) + "x".repeat(32)).substring(0, 32)
        val inv = FakeInvite(nextId(), m.id, Sha256.hex(tok.encodeToByteArray()), now + days * 86400000L)
        invites[inv.id] = inv
        return Invite(inv.id, tok, iso(inv.expires))
    }

    protected open fun srvListInvites(u: FakeUser, messId: String): List<InviteRow> {
        val m = ownerOf(u, messId)
        val out = ArrayList<InviteRow>()
        for (i in invites.values) if (i.mess == m.id) out.add(InviteRow(i.id, iso(i.expires), i.usesLeft, i.revoked))
        return out
    }

    protected open fun srvRevokeInvite(u: FakeUser, inviteId: String): Boolean {
        val i = invites[inviteId] ?: fail("not_found")
        val m = messes[i.mess] ?: fail("not_found")
        if (m.owner != u.id) fail("not_found")
        i.revoked = true
        return true
    }

    protected open fun srvInviteInfo(u: FakeUser?, token: String): InviteInfo {
        val i = liveInvite(token) ?: fail("invite_invalid")
        val m = messes[i.mess] ?: fail("invite_invalid")
        if (u != null && u.blocks.contains(m.owner)) fail("invite_invalid")
        return InviteInfo(m.name, users[m.owner]?.displayName ?: "")
    }

    protected open fun srvRedeemInvite(u: FakeUser, token: String): Joined {
        // the real server: a current member who sends a used or expired link again gets the answer (a retry after a lost answer)
        val h = Sha256.hex(token.encodeToByteArray())
        for (x in invites.values) if (x.tokenHash == h) { val mm = messes[x.mess]; if (mm != null && mm.members.containsKey(u.id)) return Joined(mm.id, mm.members[u.id] ?: "editor") }
        val i = liveInvite(token) ?: fail("invite_invalid")
        val m = messes[i.mess] ?: fail("invite_invalid")
        if (u.blocks.contains(m.owner)) fail("invite_invalid")
        if (users[m.owner]?.blocks?.contains(u.id) == true) fail("blocked")
        if (m.members.containsKey(u.id)) return Joined(m.id, m.members[u.id] ?: "editor")
        if (m.members.size >= memberLimit) fail("mess_full")
        if (i.usesLeft <= 0) fail("invite_invalid")
        i.usesLeft--
        m.members[u.id] = "editor"
        touch(m)
        return Joined(m.id, "editor")
    }

    protected fun dropMember(m: FakeMess, user: String) {
        m.members.remove(user)
        bOf(m).seats.remove(user)
        touch(m)
    }

    protected open fun srvRemoveMember(u: FakeUser, messId: String, user: String, block: Boolean): Boolean {
        val m = ownerOf(u, messId)
        if (!m.members.containsKey(user)) fail("not_found")
        if (user == m.owner) fail("bad_input")
        dropMember(m, user)
        if (block) srvBlockUser(u, user)
        return true
    }

    protected open fun srvLeaveMess(u: FakeUser, messId: String): Boolean {
        val m = memberOf(u, messId)
        if (m.owner == u.id) fail("owner_cannot_leave")
        dropMember(m, u.id)
        return true
    }

    protected open fun srvBlockUser(u: FakeUser, user: String): Boolean {
        if (user == u.id) fail("bad_input")
        val them = users[user] ?: fail("not_found")
        u.blocks.add(them.id)
        for (m in messes.values) {
            if (m.owner == u.id && m.members.containsKey(them.id)) dropMember(m, them.id)
            if (m.owner == them.id && m.members.containsKey(u.id)) dropMember(m, u.id)
        }
        return true
    }

    protected open fun srvMyBlocks(u: FakeUser): List<Blocked> {
        val out = ArrayList<Blocked>()
        for (id in u.blocks) out.add(Blocked(id, users[id]?.displayName ?: ""))
        return out
    }

    protected open fun srvUnblockUser(u: FakeUser, user: String): Boolean { u.blocks.remove(user); return true }

    protected open fun srvJoinLive(u: FakeUser, messId: String): Live {
        val m = memberOf(u, messId)
        val now = nowMs()
        val ok = takeSeat(m, u, now)
        return Live(ok, live(m, "", now))
    }

    protected open fun srvLeaveLive(u: FakeUser, messId: String): Boolean {
        val m = messes[messId] ?: return true
        bOf(m).seats.remove(u.id)
        return true
    }

    /* ---------- Slice A calls that change in Slice B ---------- */

    override fun srvOpenMess(u: FakeUser, messId: String): Opened {
        val o = super.srvOpenMess(u, messId)
        val m = messes[messId]!!
        val ops = ArrayList<Batch>()
        for (s in bOf(m).log) if (s.rev > m.docRev) ops.add(copyBatch(s))
        return Opened(o.id, o.name, o.role, o.owner_id, o.rev, o.doc_rev, o.doc, m.opsV, ops, o.sounds, o.members, o.share_mode, o.share_token, o.topic)
    }

    /** From Slice B put_doc also stores and sends the batch [{"t":"doc.reset"}] at the new rev. */
    override fun srvPutDoc(u: FakeUser, messId: String, doc: String, baseRev: Long, label: String): Saved {
        val s = super.srvPutDoc(u, messId, doc, baseRev, label)
        val m = messes[messId]!!
        val at = iso(nowMs())
        bOf(m).log.add(Stored(m.rev, u.id, "", 1, "[{\"t\":\"doc.reset\"}]", at, 18))
        m.ops.add(copyBatchLater(m.rev, u.id, "", 1, "[{\"t\":\"doc.reset\"}]", at))
        return s
    }

    /** From Slice B rename_mess appends the batch [{"t":"mess.set","f":"name","v":name}] through apply_ops's code, as the real
     *  server does only once the mess has had an apply_ops batch; a mess only ever saved whole keeps its rev (Slice A). */
    override fun srvRenameMess(u: FakeUser, messId: String, name: String): Rev {
        val m = memberOf(u, messId)
        if (m.owner != u.id) fail("forbidden")
        if (!bOf(m).hadOps) return super.srvRenameMess(u, messId, name)
        val clean = cleanName(name, 48)
        m.name = clean
        val json = Json.write(listOf<Any?>(mapOf<String, Any?>("t" to "mess.set", "f" to "name", "v" to clean)))
        store(m, u.id, nextId(), 1, json, utf8b(json), nowMs())
        return Rev(m.id, m.rev)
    }

    /* ---------- one phone ---------- */

    override fun newClient(email: String): Client = ClientB(email)

    open inner class ClientB(email: String) : Client(email) {
        override fun opsSince(messId: String, from: Long, cb: Done<Since>) { log.add("opsSince"); call(this, cb) { srvOpsSince(caller(this), messId, from) } }
        override fun applyOps(messId: String, batchId: String, opsJson: String, v: Int, cb: Done<Applied>) {
            log.add("applyOps"); call(this, cb) { val u = caller(this); terms(u); srvApplyOps(u, messId, batchId, opsJson, v) }
        }
        override fun saveSnapshot(messId: String, doc: String, docRev: Long, v: Int, label: String, cb: Done<Snapped>) {
            log.add("saveSnapshot"); call(this, cb) { val u = caller(this); terms(u); srvSaveSnapshot(u, messId, doc, docRev, v, label) }
        }
        override fun members(messId: String, cb: Done<List<Member>>) { log.add("members"); call(this, cb) { srvMembers(caller(this), messId) } }
        override fun makeInvite(messId: String, days: Int, cb: Done<Invite>) {
            log.add("makeInvite"); call(this, cb) { val u = caller(this); terms(u); srvMakeInvite(u, messId, days) }
        }
        override fun listInvites(messId: String, cb: Done<List<InviteRow>>) { log.add("listInvites"); call(this, cb) { srvListInvites(caller(this), messId) } }
        override fun revokeInvite(inviteId: String, cb: Done<Boolean>) { log.add("revokeInvite"); call(this, cb) { srvRevokeInvite(caller(this), inviteId) } }
        override fun redeemInvite(token: String, cb: Done<Joined>) {
            log.add("redeemInvite"); call(this, cb) { val u = caller(this); terms(u); srvRedeemInvite(u, token) }
        }
        override fun inviteInfo(token: String, cb: Done<InviteInfo>) { log.add("inviteInfo"); call(this, cb) { srvInviteInfo(callerOrNull(this), token) } }
        override fun removeMember(messId: String, userId: String, block: Boolean, cb: Done<Boolean>) {
            log.add("removeMember"); call(this, cb) { srvRemoveMember(caller(this), messId, userId, block) }
        }
        override fun leaveMess(messId: String, cb: Done<Boolean>) { log.add("leaveMess"); call(this, cb) { srvLeaveMess(caller(this), messId) } }
        override fun blockUser(userId: String, cb: Done<Boolean>) { log.add("blockUser"); call(this, cb) { srvBlockUser(caller(this), userId) } }
        override fun myBlocks(cb: Done<List<Blocked>>) { log.add("myBlocks"); call(this, cb) { srvMyBlocks(caller(this)) } }
        override fun unblockUser(userId: String, cb: Done<Boolean>) { log.add("unblockUser"); call(this, cb) { srvUnblockUser(caller(this), userId) } }
        override fun joinLive(messId: String, cb: Done<Live>) {
            log.add("joinLive"); call(this, cb) { val u = caller(this); terms(u); srvJoinLive(u, messId) }
        }
        override fun leaveLive(messId: String, cb: Done<Boolean>) { log.add("leaveLive"); call(this, cb) { srvLeaveLive(caller(this), messId) } }
    }
}
