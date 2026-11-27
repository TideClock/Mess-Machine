package com.tideclock.messmachine.cloud

import kotlin.jvm.JvmField

/**
 * A whole Mess Machine server in memory, for tests: the same checks, order and codes as docs/ACCOUNTS-API.md s1,
 * for Slice A (sign-in with an email code, profiles, terms, messes with rev and conflict, quotas, sounds and
 * files with owner paths, sound-urls). `client(email)` is one pretend phone with its own session and its own
 * device files; ask for as many as the test needs, also several for one email (two phones of one account).
 *
 * Nothing happens inside a call: the request waits in a queue until `deliver()` (or `step()`), which runs the
 * server work and then the callback, in the order the calls were made. `setImmediate(true)` answers inside
 * the call instead, for tests that do not care about order.
 *
 * Switches: `setOnline(c, false)` (calls answer net, nothing reaches the server), `dropNextAnswer(c)` (the
 * server does the work, the phone hears net), `holdUploads(c, true)` (a slow upload: that phone's file
 * transfers wait while everything else goes on), `expireToken(c)`, `advance(ms)`.
 *
 * FOR A SUBCLASS (FakeCloudB adds Slice B):
 * - State is in the protected maps below (`users`, `messes`, `files`, `objects`, ...). `FakeMess.ops` is the op
 *   log (`open_mess` already returns every batch after doc_rev) and `FakeMess.ext` is a free map for more.
 * - Each server function is a `protected open fun srvXxx(u: FakeUser, ...)` that returns the answer or throws
 *   with `fail(code)`. Override one and call super to add to it (put_doc and rename_mess change in Slice B).
 * - A phone is `open inner class Client`. Override `newClient(email)` to hand out your own subclass, and write
 *   each new CloudApi method as `call(this, cb) { val u = caller(this); terms(u); srvXxx(u, ...) }`.
 *   `call` does the queue, offline, the lost answer and the one refresh on a dead token.
 * - Helpers: `caller(c)`, `terms(u)`, `memberOf(u, messId)`, `fail(code)`, `nextId()`, `nowMs()`, `iso(ms)`,
 *   `enqueue(c, file, run)` for anything else that must wait for `deliver()` (broadcasts).
 */
open class FakeCloud(clock: Clock) {
    private val realClock = clock
    private var skew = 0L

    /** What server code throws. code "401" is a dead or missing token (the phone refreshes once and retries). */
    class FakeErr(@JvmField val code: String, @JvmField val http: Int) : RuntimeException(code)

    /* ---------- config rows (s1.1); tests may change them ---------- */
    @JvmField var termsVersion = "2026-11"
    @JvmField var soundLimit = 262144000L
    @JvmField var messLimit = 200
    @JvmField var memberLimit = 8
    @JvmField var liveLimit = 4
    @JvmField var messBytes = 1048576
    @JvmField var soundSeconds = 100.0
    @JvmField var soundBytes = 20971520L
    @JvmField var docsLimit = 31457280L
    @JvmField var opsVMax = 1
    @JvmField var opsDayBytes = 20971520L
    @JvmField var pendingLimit = 20
    @JvmField var putsPerMinute = 12
    /** Sign-in settings: the access token's life, the code's life, and the wait between two codes. */
    @JvmField var tokenSeconds = 3600L
    @JvmField var codeSeconds = 600L
    @JvmField var codeWaitSeconds = 60L
    /** Names holding one of these (any case) fail clean_name with bad_name. */
    @JvmField val badWords: MutableSet<String> = LinkedHashSet(listOf("badword"))

    /* ---------- rows ---------- */
    class FakeUser(@JvmField val id: String, @JvmField val email: String) {
        @JvmField var displayName = ""
        @JvmField var nameSet = false
        @JvmField var termsVersion = ""
        @JvmField var banned = false
        @JvmField var admin = false
        @JvmField var soundBytes = 0L            // bytes of ready files
        @JvmField val blocks: MutableSet<String> = LinkedHashSet()   // user ids this person blocked (Slice B)
        @JvmField val ext: MutableMap<String, Any?> = LinkedHashMap()
    }
    /** A row of the sound files table: one per owner and hash. state: pending, ready or deleting. */
    class FakeFile(@JvmField val owner: String, @JvmField val sha256: String, @JvmField var bytes: Long, @JvmField var seconds: Double,
                   @JvmField var rate: Int, @JvmField var kind: String, @JvmField var localKey: String, @JvmField var title: String,
                   @JvmField var bpm: Double, @JvmField var beats: Int, @JvmField var mkey: String) {
        @JvmField var state = "pending"
        @JvmField var blocked = false
        @JvmField var at = 0L
        @JvmField var wasReady = false           // set when it turns `deleting`: its bytes are still counted
        fun path(): String = "$owner/$sha256.wav"
    }
    /** A row of mess_sounds. src: user, vault or gone. Rows stay for the life of the mess. */
    class FakeMessSound(@JvmField val key: String, @JvmField var file: FakeFile, @JvmField var addedRev: Long) {
        @JvmField var src = "user"
    }
    class FakeVersion(@JvmField val id: String, @JvmField val rev: Long, @JvmField val label: String, @JvmField val doc: String,
                      @JvmField val by: String, @JvmField val at: Long)
    class FakeMess(@JvmField val id: String, @JvmField var name: String, @JvmField val owner: String, @JvmField var doc: String) {
        @JvmField var rev = 0L
        @JvmField var docRev = 0L
        @JvmField var opsV = 1
        @JvmField var opsBytes = 0L
        @JvmField var blocked = false
        @JvmField var updatedAt = 0L
        @JvmField var touched = 0L               // a counter, for "newest first" when two times are equal
        @JvmField var shareMode = "off"
        @JvmField var shareToken = ""
        @JvmField var topic = ""
        @JvmField val members: MutableMap<String, String> = LinkedHashMap()        // user id to role (owner, editor)
        @JvmField val sounds: MutableMap<String, FakeMessSound> = LinkedHashMap()  // key to row
        @JvmField val versions: MutableList<FakeVersion> = ArrayList()             // oldest first
        @JvmField val ops: MutableList<Batch> = ArrayList()                        // the op log, oldest first (Slice B fills it)
        @JvmField val putTimes: MutableMap<String, MutableList<Long>> = LinkedHashMap()   // user id to put_doc times
        @JvmField val ext: MutableMap<String, Any?> = LinkedHashMap()
    }
    protected class FakeToken(@JvmField val user: String, @JvmField var expiresMs: Long)
    protected class FakeCode(@JvmField val code: String, @JvmField val at: Long)

    /* ---------- the server's state ---------- */
    @JvmField protected val users: MutableMap<String, FakeUser> = LinkedHashMap()          // by user id
    @JvmField protected val byEmail: MutableMap<String, String> = LinkedHashMap()          // email to user id
    @JvmField protected val messes: MutableMap<String, FakeMess> = LinkedHashMap()         // by mess id
    @JvmField protected val files: MutableMap<String, FakeFile> = LinkedHashMap()          // by "<owner>/<sha256>"
    @JvmField protected val objects: MutableMap<String, ByteArray> = LinkedHashMap()       // storage, by "sounds/<owner>/<sha256>.wav"
    @JvmField protected val tokens: MutableMap<String, FakeToken> = LinkedHashMap()        // by access token
    @JvmField protected val refreshes: MutableMap<String, String> = LinkedHashMap()        // refresh token to user id, single use
    @JvmField protected val codes: MutableMap<String, FakeCode> = LinkedHashMap()          // the newest code per email
    @JvmField protected val clients: MutableList<Client> = ArrayList()
    private var idSeq = 0L
    private var touchSeq = 0L
    private var immediate = false

    protected class Job(@JvmField val c: Client?, @JvmField val file: Boolean, @JvmField val run: () -> Unit)
    private val queue = ArrayList<Job>()

    /* ---------- time and ids ---------- */

    /** The server's time: the clock given at the start plus everything `advance` added. */
    fun nowMs(): Long = realClock.nowMs() + skew
    /** The same time as a Clock, to hand to a CloudStore so phone and server move together. */
    @JvmField val time: Clock = Clock { nowMs() }
    fun advance(ms: Long) { skew += ms }

    /** Ids count up, in uuid form. */
    fun nextId(): String = "00000000-0000-4000-8000-" + (++idSeq).toString(16).padStart(12, '0')

    /** Epoch milliseconds as ISO 8601 UTC, like a timestamptz on the wire. */
    fun iso(ms: Long): String {
        val days = ms.floorDiv(86400000L); val rem = ms.mod(86400000L)
        val z = days + 719468L
        val era = z.floorDiv(146097L); val doe = z - era * 146097L
        val yoe = (doe - doe / 1460L + doe / 36524L - doe / 146096L) / 365L
        val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
        val mp = (5L * doy + 2L) / 153L
        val d = doy - (153L * mp + 2L) / 5L + 1L
        val m = if (mp < 10L) mp + 3L else mp - 9L
        val y = yoe + era * 400L + (if (m <= 2L) 1L else 0L)
        fun p(v: Long, n: Int): String = v.toString().padStart(n, '0')
        return p(y, 4) + "-" + p(m, 2) + "-" + p(d, 2) + "T" + p(rem / 3600000L, 2) + ":" + p(rem / 60000L % 60L, 2) + ":" +
            p(rem / 1000L % 60L, 2) + "." + p(rem % 1000L, 3) + "Z"
    }

    /* ---------- phones ---------- */

    /** One pretend phone for this person, signed out, with its own device files. */
    open fun client(email: String): CloudApi { val c = newClient(email); clients.add(c); return c }
    protected open fun newClient(email: String): Client = Client(email)
    protected fun phone(c: CloudApi): Client = c as Client

    /* ---------- test controls ---------- */

    /** Runs everything queued, and whatever those callbacks queue, until nothing is left. Answers how many ran. */
    fun deliver(): Int { var n = 0; while (step()) { n++; if (n > 1000000) throw IllegalStateException("FakeCloud.deliver never ends") }; return n }
    /** Runs the oldest waiting request that is not held. false: nothing could run. */
    fun step(): Boolean {
        for (k in queue.indices) {
            val j = queue[k]
            if (j.file && j.c != null && j.c.holdFiles) continue
            queue.removeAt(k)
            j.run()
            return true
        }
        return false
    }
    /** How many requests wait (held ones included). */
    fun pending(): Int = queue.size
    /** true: calls answer inside the call, with no queue. */
    fun setImmediate(on: Boolean) { immediate = on; if (on) deliver() }
    fun setOnline(c: CloudApi, on: Boolean) { phone(c).online = on }
    /** The next request of this phone reaches the server, but its answer is lost: the phone hears net. */
    fun dropNextAnswer(c: CloudApi) { phone(c).dropNext = true }
    /** A slow upload: while on, this phone's uploads and downloads stay in the queue. */
    fun holdUploads(c: CloudApi, on: Boolean) { phone(c).holdFiles = on }
    /** Kills this phone's access token on the server; its refresh token still works. */
    fun expireToken(c: CloudApi) { val s = phone(c).session; if (s != null) tokens[s.access]?.expiresMs = 0L }
    /** Kills the refresh token too, so the next refresh answers auth. */
    fun killSession(c: CloudApi) { val s = phone(c).session; if (s != null) { tokens.remove(s.access); refreshes.remove(s.refresh) } }
    /** The code last "mailed" to this address, or "". */
    fun lastCode(email: String): String = codes[email.trim().lowercase()]?.code ?: ""
    fun makeAdmin(email: String) { user(email).admin = true }
    fun banUser(email: String, on: Boolean) { user(email).banned = on }
    fun blockMess(messId: String, on: Boolean) { messes[messId]?.blocked = on }
    /** Puts a person on a mess without an invite (Slice A has none). */
    fun addMember(messId: String, email: String) { val m = messes[messId]; if (m != null) m.members[user(email).id] = "editor" }
    /** A fresh signed-in session for this person, with no code step. Give it to setSession or a TokenStore. */
    fun newSession(email: String): Session = mint(user(email))
    fun userId(email: String): String = byEmail[email.trim().lowercase()] ?: ""
    /** A WAV in this phone's own Workbench under a bn: key; prepareUpload finds it. */
    fun putLocalSound(c: CloudApi, key: String, wav: ByteArray, seconds: Double, rate: Int) {
        val p = phone(c); val path = "local/$key.wav"
        p.deviceFiles[path] = wav
        p.localSounds[key] = Prepared(path, Sha256.hex(wav), wav.size.toLong(), seconds, rate)
    }
    /** A device file of this phone (a path from prepareUpload, cachedSound or fetchSound), or null. */
    fun deviceFile(c: CloudApi, path: String): ByteArray? = phone(c).deviceFiles[path]
    /** How many files this phone uploaded and downloaded, and every call it made, by name, in order. */
    fun uploads(c: CloudApi): Int = phone(c).uploads
    fun downloads(c: CloudApi): Int = phone(c).downloads
    fun calls(c: CloudApi): List<String> = phone(c).log
    /** Looking at the server: a stored object ("<owner>/<sha256>.wav"), a mess, a file row's state ("" for none). */
    fun stored(path: String): ByteArray? = objects["sounds/$path"]
    fun storedCount(): Int = objects.size
    fun messDoc(messId: String): String = messes[messId]?.doc ?: ""
    fun messRev(messId: String): Long = messes[messId]?.rev ?: -1L
    fun messName(messId: String): String = messes[messId]?.name ?: ""
    fun messCount(email: String): Int { val id = userId(email); var n = 0; for (m in messes.values) if (m.owner == id) n++; return n }
    fun versionCount(messId: String): Int = messes[messId]?.versions?.size ?: 0
    fun fileState(email: String, sha256: String): String = files[userId(email) + "/" + sha256]?.state ?: ""
    fun soundBytes(email: String): Long = users[userId(email)]?.soundBytes ?: 0L

    /* ---------- helpers for server code ---------- */

    protected fun fail(code: String): Nothing = throw FakeErr(code, 400)
    protected fun fail(code: String, http: Int): Nothing = throw FakeErr(code, http)

    /** The person behind an email, made on first use, as a first sign-in would. */
    protected fun user(email: String): FakeUser {
        val e = email.trim().lowercase()
        val id = byEmail[e]
        if (id != null) return users[id]!!
        val u = FakeUser(nextId(), e)
        u.displayName = "Player " + (1000 + (users.size * 37 + 4821) % 9000)
        users[u.id] = u; byEmail[e] = u.id
        return u
    }

    protected fun mint(u: FakeUser): Session {
        val n = ++idSeq
        val access = "at-" + n; val refresh = "rt-" + n
        val exp = nowMs() + tokenSeconds * 1000L
        tokens[access] = FakeToken(u.id, exp)
        refreshes[refresh] = u.id
        return Session(access, refresh, exp / 1000L, u.id, u.email)
    }

    /** mm_private.caller(): who is calling, or `401` (dead or missing token) or `auth` (no profile, banned). */
    protected fun caller(c: Client): FakeUser {
        val s = c.session ?: fail("401", 401)
        val t = tokens[s.access]
        if (t == null || t.expiresMs <= nowMs()) fail("401", 401)
        val u = users[t.user] ?: fail("auth")
        if (u.banned) fail("auth")
        return u
    }
    /** The caller when there is a live session, else null (calls marked anon). */
    protected fun callerOrNull(c: Client): FakeUser? = try { caller(c) } catch (e: FakeErr) { null }
    /** Calls marked T. */
    protected fun terms(u: FakeUser) { if (u.termsVersion != termsVersion) fail("terms") }
    /** The mess when u is on it (and it is not taken down for them), else not_found. */
    protected fun memberOf(u: FakeUser, messId: String): FakeMess {
        val m = messes[messId] ?: fail("not_found")
        if (!m.members.containsKey(u.id)) fail("not_found")
        if (m.blocked && m.owner != u.id) fail("not_found")
        return m
    }
    protected fun colourOf(id: String): Int = try { id.substring(id.length - 2).toInt(16) % 8 } catch (e: Exception) { 0 }
    protected fun utf8(s: String): Int = s.encodeToByteArray().size
    protected fun isObject(doc: String): Boolean { val t = doc.trim(); return t.startsWith("{") && t.endsWith("}") }
    /** mm_private.clean_name for a mess: trimmed, 1 to max chars, no blocked word, else bad_name. */
    protected fun cleanName(name: String, max: Int): String {
        val n = name.trim()
        if (n.isEmpty() || n.length > max) fail("bad_name")
        val low = n.lowercase()
        for (w in badWords) if (low.contains(w)) fail("bad_name")
        return n
    }
    protected fun touch(m: FakeMess) { m.updatedAt = nowMs(); m.touched = ++touchSeq }
    /** Bytes of every doc and version this person owns (docs_limit). */
    protected fun docBytes(owner: String): Long {
        var n = 0L
        for (m in messes.values) if (m.owner == owner) { n += utf8(m.doc); for (v in m.versions) n += utf8(v.doc) }
        return n
    }
    protected fun pendingBytes(owner: String): Long { var n = 0L; for (f in files.values) if (f.owner == owner && f.state == "pending") n += f.bytes; return n }
    protected fun pendingCount(owner: String): Int { var n = 0; for (f in files.values) if (f.owner == owner && f.state == "pending") n++; return n }
    protected fun meOf(u: FakeUser): Me {
        var owned = 0
        for (m in messes.values) if (m.owner == u.id) owned++
        return Me(u.id, u.displayName, u.nameSet, colourOf(u.id), u.soundBytes, soundLimit, owned, messLimit,
            u.termsVersion == termsVersion, termsVersion, u.admin)
    }
    protected fun soundOf(r: FakeMessSound): Sound {
        val f = r.file
        val gone = r.src == "gone"
        return Sound(r.key, f.sha256, r.src, if (gone) "" else f.owner, "wav", f.bytes, f.seconds, f.rate, f.kind, f.title, f.bpm, f.beats, f.mkey,
            if (f.state == "pending") "pending" else "ready")
    }
    protected fun memberList(m: FakeMess): List<Member> {
        val out = ArrayList<Member>()
        for (pass in 0..1) for ((id, role) in m.members) {
            if ((role == "owner") != (pass == 0)) continue
            out.add(Member(id, users[id]?.displayName ?: "", colourOf(id), role))
        }
        return out
    }
    /** Keeps 30 unlabelled and 20 labelled versions and 2 MB a mess, dropping the oldest first. */
    protected fun trimVersions(m: FakeMess) {
        fun drop(labelled: Boolean, keep: Int) {
            var n = 0
            for (v in m.versions) if (v.label.isNotEmpty() == labelled) n++
            val it = m.versions.iterator()
            while (n > keep && it.hasNext()) { val v = it.next(); if (v.label.isNotEmpty() == labelled) { it.remove(); n-- } }
        }
        drop(false, 30); drop(true, 20)
        var total = 0L
        for (v in m.versions) total += utf8(v.doc)
        while (total > 2097152L && m.versions.size > 1) total -= utf8(m.versions.removeAt(0).doc)
    }

    /* ---------- the queue ---------- */

    /** Anything that must wait for deliver(). file: a transfer that holdUploads can hold back. */
    protected fun enqueue(c: Client?, file: Boolean, run: () -> Unit) {
        if (immediate) run() else queue.add(Job(c, file, run))
    }

    /**
     * One server call from phone c: waits for deliver(), then offline answers net; else `work` runs (throwing
     * FakeErr for an error code; a dead token gets one refresh and one retry, as CloudApi promises), and the
     * answer goes to cb unless dropNextAnswer asked for it to be lost.
     */
    protected fun <T> call(c: Client, cb: Done<T>, work: () -> T) { call(c, false, cb, work) }
    protected fun <T> call(c: Client, file: Boolean, cb: Done<T>, work: () -> T) {
        enqueue(c, file) {
            if (!c.online) cb.done(null, CloudErr("net", 0, ""))
            else {
                var value: T? = null
                var err: CloudErr? = null
                try { value = withRefresh(c, work) } catch (e: FakeErr) { err = if (e.code == "401") CloudErr("auth", 401, "") else CloudErr(e.code, e.http, "") }
                if (c.dropNext) { c.dropNext = false; cb.done(null, CloudErr("net", 0, "")) }
                else if (err != null) cb.done(null, err)
                else cb.done(value, null)
            }
        }
    }
    /** Work on the phone itself (its files): waits for deliver() like a call, but needs no network. */
    protected fun <T> local(c: Client, cb: Done<T>, work: () -> T) {
        enqueue(c, false) {
            var value: T? = null
            var err: CloudErr? = null
            try { value = work() } catch (e: FakeErr) { err = CloudErr(e.code, 0, "") }
            if (err != null) cb.done(null, err) else cb.done(value, null)
        }
    }

    private fun <T> withRefresh(c: Client, work: () -> T): T {
        try { return work() } catch (e: FakeErr) {
            if (e.code != "401") throw e
            val s = c.session ?: throw e
            val fresh: Session
            try { fresh = srvRefresh(s.refresh) } catch (e2: FakeErr) {
                c.session = null
                c.listener?.onSession(null)
                throw FakeErr("auth", 401)
            }
            c.session = fresh
            c.listener?.onSession(fresh)
            return work()
        }
    }

    /* ---------- sign-in (s1.2) ---------- */

    protected open fun srvRequestCode(email: String): Boolean {
        val e = email.trim().lowercase()
        if (e.isEmpty() || !e.contains("@")) fail("http", 422)
        val last = codes[e]
        if (last != null && nowMs() - last.at < codeWaitSeconds * 1000L) fail("code_wait", 429)
        codes[e] = FakeCode((100000 + (++idSeq * 7919L) % 900000L).toString(), nowMs())
        return true
    }
    protected open fun srvVerifyCode(email: String, code: String): Session {
        val e = email.trim().lowercase()
        val c = codes[e]
        if (c == null || c.code != code.trim() || nowMs() - c.at > codeSeconds * 1000L) fail("code_wrong", 403)
        codes.remove(e)
        return mint(user(e))
    }
    protected open fun srvRefresh(refreshToken: String): Session {
        val id = refreshes.remove(refreshToken) ?: fail("auth", 400)
        val u = users[id] ?: fail("auth", 400)
        tokens.remove("at-" + refreshToken.substring(3))   // the old access token dies with its refresh token
        return mint(u)
    }
    protected open fun srvSignOut(c: Client) {
        val s = c.session ?: return
        tokens.remove(s.access); refreshes.remove(s.refresh)
    }

    /* ---------- Slice A: account and messes (s1.4) ---------- */

    protected open fun srvMe(u: FakeUser): Me = meOf(u)

    protected open fun srvSetProfile(u: FakeUser, displayName: String): Me {
        val n = displayName.trim()
        if (n.isEmpty() || n.length > 24) fail("bad_input")
        val low = n.lowercase()
        for (w in badWords) if (low.contains(w)) fail("bad_name")
        u.displayName = n; u.nameSet = true
        return meOf(u)
    }

    protected open fun srvAcceptTerms(u: FakeUser, version: String): Boolean {
        if (version != termsVersion) fail("terms_outdated")
        u.termsVersion = version
        return true
    }

    protected open fun srvCreateMess(u: FakeUser, id: String, name: String, doc: String): Rev {
        val old = messes[id]
        if (old != null && old.owner == u.id) return Rev(id, old.rev)     // a safe retry
        if (old != null || id.isEmpty() || !isObject(doc)) fail("bad_input")
        val clean = cleanName(name, 48)
        if (utf8(doc) > messBytes) fail("too_big")
        var owned = 0
        for (m in messes.values) if (m.owner == u.id) owned++
        if (owned >= messLimit) fail("too_many_messes")
        if (docBytes(u.id) + utf8(doc) > docsLimit) fail("docs_full")
        val m = FakeMess(id, clean, u.id, doc)
        m.members[u.id] = "owner"
        touch(m)
        messes[id] = m
        return Rev(id, 0L)
    }

    protected open fun srvMyMesses(u: FakeUser): List<MessRow> {
        val mine = ArrayList<FakeMess>()
        for (m in messes.values) if (m.members.containsKey(u.id) && (!m.blocked || m.owner == u.id)) mine.add(m)
        mine.sortWith(Comparator { a, b -> if (a.updatedAt != b.updatedAt) b.updatedAt.compareTo(a.updatedAt) else b.touched.compareTo(a.touched) })
        val out = ArrayList<MessRow>()
        for (m in mine) out.add(MessRow(m.id, m.name, m.members[u.id] ?: "", users[m.owner]?.displayName ?: "", m.rev, iso(m.updatedAt),
            m.members.size, m.shareMode, utf8(m.doc), m.blocked))
        return out
    }

    protected open fun srvOpenMess(u: FakeUser, messId: String): Opened {
        val m = memberOf(u, messId)
        val ops = ArrayList<Batch>()
        for (b in m.ops) if (b.rev > m.docRev) ops.add(b)
        val sounds = ArrayList<Sound>()
        for (r in m.sounds.values) sounds.add(soundOf(r))
        return Opened(m.id, m.name, m.members[u.id] ?: "", m.owner, m.rev, m.docRev, m.doc, m.opsV, ops, sounds, memberList(m),
            m.shareMode, if (m.owner == u.id) m.shareToken else "", m.topic)
    }

    protected open fun srvPutDoc(u: FakeUser, messId: String, doc: String, baseRev: Long, label: String): Saved {
        val m = memberOf(u, messId)
        if (m.blocked) fail("blocked")
        if (!isObject(doc) || label.length > 40) fail("bad_input")
        if (utf8(doc) > messBytes) fail("too_big")
        val now = nowMs()
        val times = m.putTimes.getOrPut(u.id) { ArrayList() }
        times.removeAll { now - it >= 60000L }
        if (times.size >= putsPerMinute) fail("rate_limited")
        if (baseRev != m.rev) fail("conflict")
        val version = label.isNotEmpty() || m.versions.isEmpty() || now - m.versions[m.versions.size - 1].at > 600000L
        val grow = utf8(doc).toLong() - utf8(m.doc) + (if (version) utf8(doc) else 0)
        if (docBytes(m.owner) + grow > docsLimit) fail("docs_full")
        times.add(now)
        m.doc = doc; m.rev = m.rev + 1; m.docRev = m.rev
        touch(m)
        var vid = ""
        if (version) { vid = nextId(); m.versions.add(FakeVersion(vid, m.rev, label, doc, u.id, now)); trimVersions(m) }
        return Saved(m.rev, vid)
    }

    /** Slice A: sets the name, rev stays. */
    protected open fun srvRenameMess(u: FakeUser, messId: String, name: String): Rev {
        val m = memberOf(u, messId)
        if (m.owner != u.id) fail("forbidden")
        m.name = cleanName(name, 48)
        touch(m)
        return Rev(m.id, m.rev)
    }

    protected open fun srvDeleteMess(u: FakeUser, messId: String): Boolean {
        val m = memberOf(u, messId)
        if (m.owner != u.id) fail("forbidden")
        messes.remove(messId)
        return true
    }

    /* ---------- Slice A: sounds ---------- */

    protected open fun srvRegisterSound(u: FakeUser, p: Prepared, kind: String, localKey: String, title: String, bpm: Double, beats: Int, mkey: String): Registered {
        var hexOk = p.sha256.length == 64
        for (ch in p.sha256) if (!((ch in '0'..'9') || (ch in 'a'..'f'))) hexOk = false
        if (!hexOk || p.rate < 8000 || p.rate > 96000 || !(kind == "imp" || kind == "mic" || kind == "rs" || kind == "take")) fail("bad_input")
        val id = u.id + "/" + p.sha256
        val have = files[id]
        if (have != null && have.state == "ready") return Registered("ready", have.path(), u.soundBytes, soundLimit)
        if (p.seconds > soundSeconds) fail("too_long")
        if (p.bytes > soundBytes) fail("too_big")
        if (have == null && pendingCount(u.id) >= pendingLimit) fail("too_many_pending")
        val waiting = pendingBytes(u.id) - (if (have != null && have.state == "pending") have.bytes else 0L)
        if (u.soundBytes + waiting + p.bytes > soundLimit) fail("quota")
        val f = FakeFile(u.id, p.sha256, p.bytes, p.seconds, p.rate, kind, localKey, title, bpm, beats, mkey)
        f.at = nowMs()
        files[id] = f
        return Registered("upload", f.path(), u.soundBytes, soundLimit)
    }

    /** The storage insert policy and the bucket's limits. An object that is already there counts as done. */
    protected open fun srvUpload(u: FakeUser, path: String, data: ByteArray): Boolean {
        if (objects.containsKey("sounds/$path")) return true
        var mine: FakeFile? = null
        for (f in files.values) if (f.owner == u.id && f.state == "pending" && f.path() == path) mine = f
        if (mine == null) fail("auth", 403)
        if (data.size.toLong() > soundBytes) fail("too_big", 413)
        objects["sounds/$path"] = data
        return true
    }

    protected open fun srvFinishUpload(u: FakeUser, sha256: String): Used {
        val id = u.id + "/" + sha256
        val f = files[id] ?: fail("not_found")
        if (f.state == "ready") return Used("ready", u.soundBytes)
        if (f.state != "pending") fail("not_found")
        val obj = objects["sounds/" + f.path()] ?: fail("not_uploaded")
        if (obj.size.toLong() != f.bytes) { files.remove(id); fail("too_big") }
        if (u.soundBytes + f.bytes > soundLimit) { files.remove(id); fail("quota") }
        f.state = "ready"
        u.soundBytes += f.bytes
        return Used("ready", u.soundBytes)
    }

    protected open fun srvAttachSound(u: FakeUser, messId: String, key: String, owner: String, sha256: String): Boolean {
        val m = memberOf(u, messId)
        if (m.blocked) fail("blocked")
        if (!key.startsWith("bn:") || key.length > 80) fail("bad_input")
        val f = files["$owner/$sha256"]
        var okFile = f != null && f.state != "deleting" && f.owner == u.id
        if (f != null && !okFile && f.state != "deleting") {
            for (x in messes.values) if (x.members.containsKey(u.id)) for (r in x.sounds.values) if (r.file === f && r.src != "gone") okFile = true
        }
        if (f == null || !okFile) fail("not_found")
        val row = m.sounds[key]
        if (row == null) m.sounds[key] = FakeMessSound(key, f, m.rev)
        else { row.file = f; row.src = "user"; row.addedRev = m.rev }
        return true
    }

    protected open fun srvMySounds(u: FakeUser): List<MySound> {
        val mine = ArrayList<FakeFile>()
        for (f in files.values) if (f.owner == u.id) mine.add(f)
        mine.sortWith(Comparator { a, b -> b.bytes.compareTo(a.bytes) })
        val out = ArrayList<MySound>()
        for (f in mine) out.add(MySound(f.sha256, f.title, f.kind, f.bytes, f.seconds, f.state, listedBy(f), ""))
        return out
    }
    protected fun listedBy(f: FakeFile): Int {
        var n = 0
        for (m in messes.values) for (r in m.sounds.values) if (r.file === f) { n++; break }
        return n
    }

    /** Called twice: first the row turns `deleting` and its mess rows `gone`; once the object is removed, `deleted`. */
    protected open fun srvDeleteSound(u: FakeUser, sha256: String): Deleted {
        val id = u.id + "/" + sha256
        val f = files[id] ?: fail("not_found")
        val n = listedBy(f)
        if (f.state != "deleting") {
            f.wasReady = f.state == "ready"
            f.state = "deleting"
            for (m in messes.values) for (r in m.sounds.values) if (r.file === f) r.src = "gone"
            return Deleted("deleting", f.path(), u.soundBytes, n)
        }
        if (objects.containsKey("sounds/" + f.path())) return Deleted("deleting", f.path(), u.soundBytes, n)
        files.remove(id)
        if (f.wasReady) u.soundBytes -= f.bytes
        return Deleted("deleted", f.path(), u.soundBytes, n)
    }

    protected open fun srvRemoveObject(u: FakeUser, path: String): Boolean {
        if (!path.startsWith(u.id + "/")) fail("auth", 403)
        objects.remove("sounds/$path")
        return true
    }

    /** Edge Function sound-urls with {"mess"}: ready rows with src user or vault, no banned owner, no blocked file. */
    protected open fun srvSoundUrls(u: FakeUser?, messId: String, token: String): List<SoundUrl> {
        if (messId.isEmpty() == token.isEmpty()) fail("bad_input")
        if (messId.isEmpty()) fail("not_found")      // {"token"}: share links are Slice D
        if (u == null) fail("auth", 401)
        val m = memberOf(u, messId)
        val out = ArrayList<SoundUrl>()
        val exp = nowMs() + 600000L
        for (r in m.sounds.values) {
            val f = r.file
            if (r.src == "gone" || f.state != "ready" || f.blocked || users[f.owner]?.banned == true) continue
            out.add(SoundUrl(r.key, f.sha256, f.bytes, "fake://sounds/" + f.path() + "?exp=" + exp))
        }
        return out
    }

    /** The GET of a signed URL: the bytes, or 400 once it is over 10 minutes old, or 404. */
    protected open fun srvDownload(url: String): ByteArray {
        val q = url.indexOf("?exp=")
        if (!url.startsWith("fake://") || q < 0) fail("http", 400)
        val exp = url.substring(q + 5).toLongOrNull() ?: 0L
        if (nowMs() > exp) fail("http", 400)
        return objects[url.substring(7, q)] ?: fail("http", 404)
    }

    /* ---------- one phone ---------- */

    /** One pretend phone: its own session, its own device files, its own switches. */
    open inner class Client(@JvmField val email: String) : CloudApi {
        @JvmField var session: Session? = null
        @JvmField var listener: SessionListener? = null
        @JvmField var online = true
        @JvmField var dropNext = false
        @JvmField var holdFiles = false
        /** Device files by path; the Workbench's sounds by key; the download cache by sha256. */
        @JvmField val deviceFiles: MutableMap<String, ByteArray> = LinkedHashMap()
        @JvmField val localSounds: MutableMap<String, Prepared> = LinkedHashMap()
        @JvmField val cacheBySha: MutableMap<String, String> = LinkedHashMap()
        @JvmField var uploads = 0
        @JvmField var downloads = 0
        /** Every call by name, in order (createMess, putDoc, upload, ...). */
        @JvmField val log: MutableList<String> = ArrayList()

        override fun configured(): Boolean = true
        override fun newId(): String = nextId()
        override fun setSession(s: Session?) { session = s }
        override fun setSessionListener(l: SessionListener?) { listener = l }

        override fun requestCode(email: String, cb: Done<Boolean>) { log.add("requestCode"); call(this, cb) { srvRequestCode(email) } }
        override fun verifyCode(email: String, code: String, cb: Done<Session>) { log.add("verifyCode"); call(this, cb) { srvVerifyCode(email, code) } }
        override fun refresh(refreshToken: String, cb: Done<Session>) {
            log.add("refresh")
            call(this, cb) {
                val mine = session?.refresh == refreshToken
                val fresh = srvRefresh(refreshToken)
                if (mine) session = fresh     // like HttpCloud: the phone's own token is replaced at once
                fresh
            }
        }
        override fun signOut(cb: Done<Boolean>) {
            log.add("signOut")
            val s = session
            enqueue(this, false) { if (online && s != null) { tokens.remove(s.access); refreshes.remove(s.refresh) }; cb.done(true, null) }
        }

        override fun me(cb: Done<Me>) { log.add("me"); call(this, cb) { srvMe(caller(this)) } }
        override fun setProfile(displayName: String, cb: Done<Me>) { log.add("setProfile"); call(this, cb) { srvSetProfile(caller(this), displayName) } }
        override fun acceptTerms(version: String, cb: Done<Boolean>) { log.add("acceptTerms"); call(this, cb) { srvAcceptTerms(caller(this), version) } }
        override fun createMess(id: String, name: String, doc: String, cb: Done<Rev>) {
            log.add("createMess"); call(this, cb) { val u = caller(this); terms(u); srvCreateMess(u, id, name, doc) }
        }
        override fun myMesses(cb: Done<List<MessRow>>) { log.add("myMesses"); call(this, cb) { srvMyMesses(caller(this)) } }
        override fun openMess(messId: String, cb: Done<Opened>) { log.add("openMess"); call(this, cb) { srvOpenMess(caller(this), messId) } }
        override fun putDoc(messId: String, doc: String, baseRev: Long, label: String, cb: Done<Saved>) {
            log.add("putDoc"); call(this, cb) { val u = caller(this); terms(u); srvPutDoc(u, messId, doc, baseRev, label) }
        }
        override fun renameMess(messId: String, name: String, cb: Done<Rev>) {
            log.add("renameMess"); call(this, cb) { val u = caller(this); terms(u); srvRenameMess(u, messId, name) }
        }
        override fun deleteMess(messId: String, cb: Done<Boolean>) { log.add("deleteMess"); call(this, cb) { srvDeleteMess(caller(this), messId) } }

        override fun prepareUpload(key: String, cb: Done<Prepared>) { log.add("prepareUpload"); local(this, cb) { localSounds[key] ?: fail("not_found") } }
        override fun registerSound(p: Prepared, kind: String, localKey: String, title: String, bpm: Double, beats: Int, mkey: String, cb: Done<Registered>) {
            log.add("registerSound"); call(this, cb) { val u = caller(this); terms(u); srvRegisterSound(u, p, kind, localKey, title, bpm, beats, mkey) }
        }
        override fun upload(path: String, localFile: String, bytes: Long, cb: Done<Boolean>) {
            log.add("upload")
            call(this, true, cb) {
                val u = caller(this)
                val data = deviceFiles[localFile] ?: fail("http", 400)
                val ok = srvUpload(u, path, data)
                uploads++
                ok
            }
        }
        override fun finishUpload(sha256: String, cb: Done<Used>) { log.add("finishUpload"); call(this, cb) { srvFinishUpload(caller(this), sha256) } }
        override fun mySounds(cb: Done<List<MySound>>) { log.add("mySounds"); call(this, cb) { srvMySounds(caller(this)) } }
        override fun attachSound(messId: String, key: String, owner: String, sha256: String, cb: Done<Boolean>) {
            log.add("attachSound"); call(this, cb) { val u = caller(this); terms(u); srvAttachSound(u, messId, key, owner, sha256) }
        }
        override fun deleteSound(sha256: String, cb: Done<Deleted>) { log.add("deleteSound"); call(this, cb) { srvDeleteSound(caller(this), sha256) } }
        override fun removeObject(path: String, cb: Done<Boolean>) { log.add("removeObject"); call(this, cb) { srvRemoveObject(caller(this), path) } }
        override fun soundUrls(messId: String, token: String, cb: Done<List<SoundUrl>>) {
            log.add("soundUrls"); call(this, cb) { srvSoundUrls(if (messId.isEmpty()) callerOrNull(this) else caller(this), messId, token) }
        }
        override fun cachedSound(sha256: String, cb: Done<String>) {
            log.add("cachedSound")
            local(this, cb) {
                var path = cacheBySha[sha256]
                if (path == null) for (p in localSounds.values) if (p.sha256 == sha256) path = p.path
                path ?: fail("not_found")
            }
        }
        override fun fetchSound(url: String, sha256: String, bytes: Long, cb: Done<String>) {
            log.add("fetchSound")
            call(this, true, cb) {
                val all = srvDownload(url)
                downloads++
                val n = if (bytes < all.size.toLong()) bytes.toInt() else all.size   // the reader stops after the listed bytes
                if (Sha256.hex(all, n) != sha256) fail("bad_hash", 0)
                val path = "cache/$sha256.wav"
                deviceFiles[path] = all.copyOf(n)
                cacheBySha[sha256] = path
                path
            }
        }
        override fun dropSound(sha256: String) { log.add("dropSound"); val p = cacheBySha.remove(sha256); if (p != null) deviceFiles.remove(p) }
    }
}
