package com.tideclock.messmachine.cloud

import com.tideclock.messmachine.engine.Json
import com.tideclock.messmachine.engine.Project
import kotlin.jvm.JvmField

/*
 * Slice B, "two people in one mess": the session for one shared mess (docs/ACCOUNTS-API.md section 4.6).
 * Common Kotlin, one thread: it is driven only by the host's calls and by CloudApi callbacks, and it never
 * runs a callback of its own inside the call that asked for it.
 *
 * The one rule: the mess on screen is `base`, then my batches in `log`, then my unconfirmed work. Other people's
 * batches wait in `log` until the local player's next loop (or go in at once when nothing is playing).
 */

class Peer(@JvmField val id: String, @JvmField val name: String, @JvmField val colour: Int, @JvmField val role: String, @JvmField val page: String,
           @JvmField val loop: Int, @JvmField val pad: Int, @JvmField val playing: Boolean, @JvmField val rec: Boolean)

/** The app side: owns the Project the screens draw. */
interface SyncHost {
    fun project(): Project
    fun replace(p: Project)                              // a new Project (local-only fields carried): swap it in, redraw
    fun patched()                                        // project() was changed in place: redraw, rebuild the engine snapshot
    fun onState(state: Int)
    fun onNotice(kind: Int, n: Int, who: String)
    fun onSound(s: Sound)                                // a sound the mess needs: fetch it (CloudStore rule 6)
    fun onHeld()                                         // someone else's batch entered the log while playing: run arm()
    fun keepCopy(why: Int)                               // called before pending is ever dropped: save the screen's mess as a phone copy
    fun onPeers(peers: List<Peer>)                       // Slice C; self left out
}

class SyncSession(private val api: CloudApi, private val cache: CloudCache, private val clock: Clock, private val host: SyncHost,
                  @JvmField val messId: String, @JvmField val me: String) {
    companion object {
        const val OPENING = 0; const val SOLO = 1; const val LIVE = 2; const val WATCH = 3   // WATCH: 4 others already editing
        const val OFFLINE = 4; const val GONE = 6                                            // GONE: removed, deleted or blocked
        const val N_REPLACED = 1; const val N_UNDO_SKIPPED = 2; const val N_JOINED = 3; const val N_LEFT = 4
        const val N_NEWER = 5; const val N_FULL = 6; const val N_KEPT_COPY = 7
        const val W_REFUSED = 1; const val W_GAP = 2; const val W_RESET = 3; const val W_GONE = 4   // keepCopy reasons
        const val SEND_MS = 400L; const val SOLO_SEND_MS = 5000L
        const val POLL_MS = 2000L; const val POLL_JOINED_MS = 10000L; const val SOLO_POLL_MS = 30000L
        const val SNAP_OPS = 50; const val SNAP_MS = 60000L; const val GAP_OPS = 500; const val GAP_MS = 86400000L
        /** Not in the frozen list: how long ops wait for a loop top while Rec runs, and how often a phone without a seat asks again. */
        const val PASS_MS = 10000L; const val WATCH_MS = 10000L
        const val UNDO_MAX = 80
        private val BACKOFF = longArrayOf(1000L, 2000L, 5000L, 10000L)
    }

    @JvmField var state = OPENING
    @JvmField var role = ""
    @JvmField var sawNewer = false
    @JvmField var serverRev = 0L        // highest rev held with no gap below it
    @JvmField var shownRev = 0L         // other people's ops up to here are on screen
    @JvmField var previewRev = 0L       // serverRev when preview() last ran
    /** Slice C: the host sets it while the live socket is joined; the poll is then only the safety net (POLL_JOINED_MS). */
    @JvmField var socketJoined = false

    /** A batch as the server ordered it. mine: its id was one of my own batches (never judged by user id). */
    private class Entry(@JvmField val rev: Long, @JvmField val user: String, @JvmField val id: String, @JvmField val v: Int,
                        @JvmField val ops: MutableList<Op>, @JvmField val mine: Boolean)
    /** A batch of my own: in flight (rev 0) or confirmed at rev. */
    private class Mine(@JvmField val id: String, @JvmField val ops: MutableList<Op>) { @JvmField var rev = 0L }

    private var base = Project()                  // the server's mess at shownRev
    private var shadow = Project()                // the screen's mess as of the last localChanged
    private val log = ArrayList<Entry>()          // server batches above shownRev, in order
    private val sent = ArrayList<Mine>()          // my confirmed batches above the rev of base.json on disk
    private val limbo = ArrayList<Mine>()         // my confirmed batches the log has not shown yet (part of sent)
    private var flight: Mine? = null              // the one batch waiting for its answer
    private var flightBusy = false                // its call is out
    private val waiting = ArrayList<Op>()         // my ops not sent yet
    private val hints = ArrayList<Op>()

    private var gen = 0                           // callbacks of an older open are ignored
    private var closed = true
    private var ready = false                     // base and shadow are set: local changes are taken
    private var opened = false                    // caught up with the server once since open
    private var gone = false
    private var offline = false
    private var seat = true                       // I may edit (a live seat); false is WATCH
    private var resetting = false
    private var resetHad = 0
    private var openBusy = false
    private var openRetryAt = 0L
    private var openTries = 0

    private var diskRev = -1L                     // the rev base.json holds
    private var cacheAt = 0L
    private var docRev = 0L
    private var memberCount = 1
    private var members: List<Member> = emptyList()

    private var playing = false
    private var recording = false
    private var perPass = false
    private var passNow = false
    private var page = ""; private var pLoop = 0; private var pPad = -1

    private var lastSendAt = 0L
    private var firstWaitAt = 0L
    private var retryAt = 0L
    private var tries = 0
    private var pollBusy = false
    private var pollSoon = false
    private var lastPollAt = 0L
    private var lastJoinAt = 0L
    private var snapBusy = false
    private var lastSnapAt = 0L
    private var needSnap = false                  // the server said snapshot_first
    private var noSnap = false                    // the server said client_too_old
    private var versionWanted: String? = null
    private var localMark = ""
    private var localDirty = false                // a never-synced field changed (solo messes upload them)
    private var fullSaid = false

    private var resetWanted = false
    private var newTheirs = false
    private var queueDirty = false
    /**
     * Fingerprints (canon hashes) of the screen at the cache writes that followed a quiet moment, newest last. The app
     * writes its mess file 700 ms after the last change, so the file on disk is always one of these screens unless it
     * holds edits this session never saw. On reopen only such a file is diffed: a file that lags the cache would
     * otherwise send old values back as new work (and undo other people's edits).
     */
    private val marks = ArrayList<String>()
    private var lastChangeAt = 0L
    private var unmarked = false
    private var openDiffed = 0

    private val undoStack = ArrayList<Change>()
    private var openChange: Change? = null
    /** Someone else's last change, by rev: to one field of one sound, to anything inside a scope, and to a scope as a whole. */
    private val tKey = HashMap<String, Long>()
    private val tIn = HashMap<String, Long>()
    private val tCover = HashMap<String, Long>()
    private val revUser = HashMap<Long, String>()
    private var tAll = 0L
    private var tLoops = 0L
    private var tPads = 0L

    /* ================= opening and closing ================= */

    /** Both cache files, then open_mess or ops_since, then join_live. */
    fun open() {
        gen++
        closed = false; ready = false; opened = false; gone = false; offline = false; seat = true; resetting = false
        log.clear(); sent.clear(); limbo.clear(); flight = null; flightBusy = false; waiting.clear(); hints.clear()
        undoStack.clear(); openChange = null
        tKey.clear(); tIn.clear(); tCover.clear(); revUser.clear(); tAll = 0L; tLoops = 0L; tPads = 0L
        pollBusy = false; pollSoon = false; snapBusy = false; needSnap = false; noSnap = false; versionWanted = null
        resetWanted = false; newTheirs = false; queueDirty = false; openBusy = false; openTries = 0; tries = 0
        sawNewer = false; fullSaid = false; localDirty = false
        val now = clock.nowMs()
        lastSnapAt = now; lastSendAt = 0L; retryAt = 0L; lastPollAt = 0L; lastJoinAt = 0L; openRetryAt = 0L
        state = OPENING
        if (loadCache()) {
            // the screen as this phone last knew it: base, my confirmed batches, the one in flight, the ones waiting
            shadow = Ops.clone(base)
            for (m in sent) Ops.apply(shadow, m.ops)
            val f = flight
            if (f != null) Ops.apply(shadow, f.ops)
            Ops.apply(shadow, waiting)
            limbo.addAll(sent)
            // what the mess holds beyond that was made where this session never saw it: it is new work. A file that is
            // one of the screens the cache already holds (it lags the cache) adds nothing.
            val p = host.project()
            openDiffed = 0
            if (!marks.contains(fingerprint(p))) {
                val c = Ops.change(shadow, p, emptyList(), false)
                if (role != "owner") stripName(c)
                if (!c.ops.isEmpty()) { waiting.addAll(c.ops); firstWaitAt = now; openDiffed = c.ops.size }
            }
            ready = true
            rebuild()
            writeQueue(true)
            host.onState(state)
            poll()
        } else {
            host.onState(state)
            fetchOpen()
        }
    }

    /** Flush, snapshot if due, leave_live, write both cache files. Answers that come later are ignored; the next open sorts them out. */
    fun close() {
        if (closed) return
        if (ready && !gone) {
            absorb(); closeChange()
            trySend(clock.nowMs(), true)
            if (opened && !snapBusy && !sawNewer && !noSnap && !offline && serverRev > docRev && (memberCount <= 1 || seat)) snapshot("")
            api.leaveLive(messId, Done { _, _ -> })
            writeQueue(true); writeBase()
        }
        closed = true
        gen++
    }

    /** Flush and write both cache files. */
    fun background() {
        if (closed || gone || !ready) return
        absorb()
        trySend(clock.nowMs(), true)
        writeQueue(true); writeBase()
    }

    private fun num(o: Any?): Long = Json.num(o, 0.0).toLong()

    private fun loadCache(): Boolean {
        val bj = cache.load("$messId.base.json") ?: return false
        try {
            val bm = Json.map(Json.parse(bj))
            val doc = bm["base"]
            if (doc !is Map<*, *>) return false
            base = Project.fromJson(Json.write(doc), null).project
            shownRev = num(bm["rev"]); serverRev = shownRev; diskRev = shownRev
            docRev = num(bm["doc_rev"]); cacheAt = num(bm["at"])
            role = Json.str(bm["role"], ""); memberCount = maxOf(1, num(bm["members"]).toInt())
            val qj = cache.load("$messId.queue.json")
            if (qj != null) {
                val qm = Json.map(Json.parse(qj))
                cacheAt = maxOf(cacheAt, num(qm["at"]))
                for (o in Json.list(qm["sent"])) {
                    val sm = Json.map(o)
                    val m = Mine(Json.str(sm["id"], ""), Ops.fromList(Json.list(sm["ops"])))
                    m.rev = num(sm["rev"])
                    if (m.rev > diskRev && !m.id.isEmpty()) sent.add(m)   // the others are in base already
                }
                val fm = qm["flight"]
                if (fm is Map<*, *>) {
                    val f = Mine(Json.str(Json.map(fm)["id"], ""), Ops.fromList(Json.list(Json.map(fm)["ops"])))
                    var known = f.id.isEmpty() || f.ops.isEmpty()
                    for (m in sent) if (m.id == f.id) known = true
                    if (!known) flight = f
                }
                waiting.addAll(Ops.fromList(Json.list(qm["waiting"])))
                for (o in Json.list(qm["marks"])) if (o is String) marks.add(o)
            }
            return true
        } catch (e: RuntimeException) {
            sent.clear(); flight = null; waiting.clear()
            return false
        }
    }

    /** 64-bit FNV-1a of the canon, as hex. */
    private fun fingerprint(p: Project): String {
        val c = Ops.canon(p)
        var h = -3750763034362895579L
        for (ch in c) { h = h xor ch.code.toLong(); h *= 1099511628211L }
        return h.toULong().toString(16)
    }

    /** mark: also record the screen's fingerprint (at quiet moments, releases, open, close). */
    private fun writeQueue(mark: Boolean = false) {
        queueDirty = false
        if (mark) {
            unmarked = false
            val fp = fingerprint(host.project())
            if (marks.isEmpty() || marks[marks.size - 1] != fp) { marks.remove(fp); marks.add(fp); if (marks.size > 32) marks.removeAt(0) }
        }
        val m: MutableMap<String, Any?> = LinkedHashMap()
        m["v"] = 1.0
        m["at"] = clock.nowMs().toDouble()
        val sl = ArrayList<Any?>()
        for (s in sent) if (s.rev > diskRev) {
            val sm: MutableMap<String, Any?> = LinkedHashMap()
            sm["id"] = s.id; sm["rev"] = s.rev.toDouble(); sm["ops"] = maps(s.ops)
            sl.add(sm)
        }
        m["sent"] = sl
        val f = flight
        if (f == null) m["flight"] = null
        else { val fm: MutableMap<String, Any?> = LinkedHashMap(); fm["id"] = f.id; fm["ops"] = maps(f.ops); m["flight"] = fm }
        m["waiting"] = maps(waiting)
        val ml = ArrayList<Any?>(marks.size); for (x in marks) ml.add(x)
        m["marks"] = ml
        cache.save("$messId.queue.json", Json.write(m))
    }

    private fun maps(ops: List<Op>): ArrayList<Any?> { val l = ArrayList<Any?>(ops.size); for (o in ops) l.add(o.m); return l }

    /** base.json holds the server's mess at shownRev. Written after the queue, so a kill between the two loses nothing. */
    private fun writeBase() {
        val b = StringBuilder()
        b.append("{\"v\":1,\"rev\":").append(shownRev.toString()).append(",\"at\":").append(clock.nowMs().toString())
            .append(",\"doc_rev\":").append(docRev.toString()).append(",\"members\":").append(memberCount)
            .append(",\"role\":").append(Json.write(role)).append(",\"base\":").append(base.toJson(false)).append('}')
        cache.save("$messId.base.json", b.toString())
        diskRev = shownRev
        if (!sent.isEmpty()) sent.removeAll { s -> s.rev <= diskRev }
    }

    private fun fetchOpen() {
        if (openBusy) return
        openBusy = true
        val g = gen
        api.openMess(messId, Done { v, e ->
            if (g != gen || closed) return@Done
            openBusy = false
            if (e == null && v != null) onOpened(v)
            else {
                val code = e?.code ?: "net"
                if (code == "not_found" || code == "blocked") goGone()
                else { offline = true; openRetryAt = clock.nowMs() + BACKOFF[minOf(openTries, BACKOFF.size - 1)]; openTries++; setState() }
            }
        })
    }

    private fun onOpened(o: Opened) {
        role = o.role
        members = o.members
        memberCount = maxOf(1, o.members.size)
        docRev = o.doc_rev
        base = try { Project.fromJson(o.doc, null).project } catch (e: RuntimeException) { Project() }
        log.clear(); sent.clear(); limbo.clear(); flight = null; flightBusy = false; waiting.clear()
        shownRev = o.doc_rev; serverRev = o.doc_rev
        resetWanted = false
        takeBatches(o.ops)
        resetWanted = false                      // a reset below this doc is over
        for (e in log) applyEntry(base, e)
        shownRev = serverRev
        log.clear(); newTheirs = false
        val next = Ops.clone(base)
        if (resetting) Ops.carryLocal(host.project(), next)   // a first open shows the mess as the server has it
        shadow = Ops.clone(next)
        localMark = Ops.localMark(next)
        ready = true; opened = true; offline = false; openTries = 0
        val had = resetHad
        resetting = false; resetHad = 0
        host.replace(next)
        marks.clear()
        writeQueue(true); writeBase()
        for (s in o.sounds) host.onSound(s)
        if (had > 0) host.onNotice(N_KEPT_COPY, had, "")
        joinLive()
        setState()
    }

    private fun joinLive() {
        lastJoinAt = clock.nowMs()
        val g = gen
        api.joinLive(messId, Done { v, e ->
            if (g != gen || closed) return@Done
            if (e == null && v != null) {
                seat = v.live
                if (!seat && memberCount > 1 && !fullSaid) { fullSaid = true; host.onNotice(N_FULL, v.editors, "") }
                if (seat) fullSaid = false
                setState()
            }
        })
    }

    private fun refreshMembers() {
        val g = gen
        api.members(messId, Done { v, e ->
            if (g != gen || closed) return@Done
            if (e == null && v != null) {
                members = v
                for (m in v) if (m.id == me) role = m.role
            }
        })
    }

    private fun nameOf(user: String): String { for (m in members) if (m.id == user) return m.name; return "" }

    /* ================= state ================= */

    private fun setState() {
        val s = if (gone) GONE else if (!ready || !opened) (if (offline) OFFLINE else OPENING) else if (offline) OFFLINE
        else if (memberCount <= 1) SOLO else if (seat) LIVE else WATCH
        if (s != state) { state = s; host.onState(s) }
    }

    /** Removed, deleted or blocked. What was not sent is kept as a phone copy first. */
    private fun goGone() {
        if (gone) return
        if (ready) absorb()
        val n = pending()
        if (n > 0) host.keepCopy(W_GONE)
        flight = null; flightBusy = false; waiting.clear(); limbo.clear(); sent.clear(); log.clear(); hints.clear()
        undoStack.clear(); openChange = null
        gone = true
        gen++
        cache.save("$messId.base.json", null)
        cache.save("$messId.queue.json", null)
        setState()
        if (n > 0) host.onNotice(N_KEPT_COPY, n, "")
    }

    /** A gap or a doc.reset: keep a copy if anything is unsent, drop this phone's copy, open the mess again. */
    private fun doReset() {
        resetWanted = false
        if (ready) absorb()
        val n = pending()
        if (n > 0) host.keepCopy(W_RESET)
        flight = null; flightBusy = false; waiting.clear(); limbo.clear(); sent.clear(); log.clear(); hints.clear()
        undoStack.clear(); openChange = null
        gen++
        pollBusy = false; snapBusy = false; openBusy = false
        ready = false; opened = false; resetting = true; resetHad = n
        setState()
        fetchOpen()
    }

    /* ================= the host's calls ================= */

    /** At least every 100 ms while open: sending, the poll, retries, snapshots. */
    fun tick() {
        if (closed || gone) return
        val now = clock.nowMs()
        if (!ready) { if (!openBusy && now >= openRetryAt) fetchOpen(); return }
        if (unmarked && now - lastChangeAt >= 200L) writeQueue(true)
        if (!pollBusy && (pollSoon || now - lastPollAt >= pollEvery())) poll()
        trySend(now, false)
        trySnapshot(now)
        if (opened && memberCount > 1 && !seat && !offline && now - lastJoinAt >= WATCH_MS) joinLive()
    }

    /** Slice C: the live socket said the mess changed (an `ops` or `meta` broadcast): ask the server at the next tick, not at the next poll. */
    fun nudge() { if (ready && !closed && !gone) pollSoon = true }
    /** How many people are on this mess, as the server last said. */
    fun memberCount(): Int = memberCount

    /** A structural edit is about to be reported by localChanged: Ops.loopLen, loopCopy, loopClear, padSwap or padPut. */
    fun hint(op: Op) { if (ready && !closed && !gone) hints.add(op) }

    /** After every change to host.project(). undoable false: a drag still going, or hits during a take; they fold into the next undoable one. */
    fun localChanged(undoable: Boolean) {
        if (!ready || closed || gone) { hints.clear(); return }
        absorb()
        if (undoable) closeChange()
    }

    private fun stripName(c: Change) {
        c.ops.removeAll { o -> o.t == "mess.set" && o.m["f"] == "name" }
        c.undo.removeAll { o -> o.t == "mess.set" && o.m["f"] == "name" }
    }

    /** Whatever the mess holds that the shadow does not becomes ops waiting to go. */
    private fun absorb() {
        val p = host.project()
        val c = Ops.change(shadow, p, hints, sawNewer)
        if (!hints.isEmpty()) hints.clear()
        if (!c.ops.isEmpty()) {
            if (role != "owner") stripName(c)   // the name is the owner's to set; anyone else's change to it stays on this phone
            if (!c.ops.isEmpty()) {
                if (waiting.isEmpty()) firstWaitAt = clock.nowMs()
                waiting.addAll(c.ops)
                var o = openChange
                if (o == null) { o = Change(ArrayList(), ArrayList(), shownRev); openChange = o }
                o.ops.addAll(c.ops)
                o.undo.addAll(0, c.undo)
                lastChangeAt = clock.nowMs(); unmarked = true
                writeQueue()                           // the cache holds every change before the mess file can
            }
        }
        if (memberCount <= 1) { val mk = Ops.localMark(p); if (mk != localMark) { localMark = mk; localDirty = true } }
    }

    private fun closeChange() {
        val o = openChange ?: return
        openChange = null
        if (o.undo.isEmpty()) return
        val sq = Ops.squash(o.undo)
        undoStack.add(Change(o.ops, sq, o.seenRev))
        if (undoStack.size > UNDO_MAX) undoStack.removeAt(0)
    }

    fun setPlaying(playing: Boolean) {
        this.playing = playing
        if (!playing && ready && !closed && !gone && held() > 0) release(serverRev)
    }
    fun setRecording(on: Boolean) { recording = on }
    /** Rec is running: own ops go out at loop tops only (and when Rec ends, or after PASS_MS at the latest). */
    fun setPerPass(on: Boolean) { if (perPass && !on) passNow = true; perPass = on }
    fun setPresence(page: String, loop: Int, pad: Int) { this.page = page; pLoop = loop; pPad = pad }

    /** Other people's batches waiting for the loop top. */
    fun held(): Int { var n = 0; for (e in log) if (!e.mine) n++; return n }

    /** The mess after a release of everything held now; sets previewRev; null when held() == 0. */
    fun preview(): Project? {
        if (!ready || closed || gone || held() == 0) return null
        val p = screen(true)
        Ops.carryLocal(host.project(), p)
        previewRev = serverRev
        return p
    }

    /** The engine swapped the armed snapshot in: release batches up to that rev only. */
    fun loopTop(previewRev: Long) {
        if (!ready || closed || gone) return
        if (perPass) passNow = true
        var any = false
        for (e in log) if (!e.mine && e.rev <= previewRev) { any = true; break }
        if (any) release(minOf(previewRev, serverRev))
    }

    /** Own ops not confirmed yet. */
    fun pending(): Int { val f = flight; return (if (f == null) 0 else f.ops.size) + waiting.size }

    /** A snapshot now, with a label (once everything waiting has gone). */
    fun saveVersion(label: String) {
        if (!ready || closed || gone) return
        absorb()
        versionWanted = label
        val now = clock.nowMs()
        trySend(now, true)
        trySnapshot(now)
    }

    /** More than one member. */
    fun shared(): Boolean = memberCount > 1

    /* ================= undo: own changes only ================= */

    fun canUndo(): Boolean { val o = openChange; return !undoStack.isEmpty() || (o != null && !o.undo.isEmpty()) }

    /** The rev of someone else's change that an undo op made at seenRev must not run over, or 0. */
    private fun blocked(op: Op, seen: Long): Long {
        if (tAll > seen) return tAll
        val id = Ops.ident(op)
        if (!id.isEmpty()) { val r = tKey[id]; if (r != null && r > seen) return r }
        if (Ops.structural(op)) {
            for (s in Ops.coverScopes(op)) { val r = tIn[s]; if (r != null && r > seen) return r }
            val t = op.t
            if (t == "pad.put" && tLoops > seen) return tLoops
            if (t.startsWith("loop.") && tPads > seen) return tPads
            if (t == "row.put") for (s in Ops.inScopes(op)) { val r = tCover[s]; if (r != null && r > seen) return r }
        } else {
            for (s in Ops.inScopes(op)) { val r = tCover[s]; if (r != null && r > seen) return r }
        }
        return 0L
    }

    /** Takes back my last change. A part someone else has changed since I last saw it is left alone, with a notice. */
    fun undo() {
        if (!ready || closed || gone) return
        absorb(); closeChange()
        if (undoStack.isEmpty()) return
        val c = undoStack.removeAt(undoStack.size - 1)
        val run = ArrayList<Op>()
        var skipped = 0
        var who = ""
        for (op in c.undo) {
            var r = blocked(op, c.seenRev)
            // a phone that saw newer ops never sends a put built from its own copy (S7)
            if (r == 0L && sawNewer && (op.t == "row.put" || op.t == "loop.put" || (op.t == "pad.put" && Json.str(op.m["key"], "") != ""))) r = serverRev
            if (r != 0L) { skipped++; val u = revUser[r]; if (u != null) who = nameOf(u) } else run.add(op)
        }
        if (!run.isEmpty()) {
            Ops.apply(host.project(), run)
            Ops.apply(shadow, run)
            if (waiting.isEmpty()) firstWaitAt = clock.nowMs()
            waiting.addAll(run)
            lastChangeAt = clock.nowMs(); unmarked = true
            writeQueue()
            host.patched()
        }
        if (skipped > 0) host.onNotice(N_UNDO_SKIPPED, skipped, who)
    }

    /* ================= sending ================= */

    private fun sendEvery(): Long = if (memberCount > 1) SEND_MS else SOLO_SEND_MS
    private fun pollEvery(): Long = if (memberCount > 1 || !opened) (if (socketJoined && opened) POLL_JOINED_MS else POLL_MS) else SOLO_POLL_MS

    private fun trySend(now: Long, force: Boolean) {
        if (!ready || !opened || closed || gone || flightBusy) return
        var f = flight
        if (f != null) { if (force || now >= retryAt) fire(f); return }
        if (waiting.isEmpty()) return
        if (!force) {
            if (now < retryAt) return
            if (now - lastSendAt < sendEvery()) return
            if (perPass && !passNow && now - firstWaitAt < PASS_MS) return
        }
        passNow = false
        val sq = Ops.squash(waiting)
        val n = Ops.fit(sq)
        waiting.clear()
        if (n <= 0) return
        f = Mine(api.newId(), ArrayList(sq.subList(0, n)))
        for (i in n until sq.size) waiting.add(sq[i])
        if (!waiting.isEmpty()) firstWaitAt = now
        flight = f
        tries = 0
        fire(f)
    }

    private fun fire(f: Mine) {
        flightBusy = true
        lastSendAt = clock.nowMs()
        writeQueue()                                   // before every apply_ops call
        val g = gen
        api.applyOps(messId, f.id, Ops.toJson(f.ops), Ops.V, Done { v, e ->
            if (g != gen || closed) return@Done
            if (flight !== f) return@Done              // the log already showed it
            flightBusy = false
            if (e == null && v != null) onApplied(f, v.rev) else onRefused(f, e?.code ?: "net")
        })
    }

    private fun onApplied(f: Mine, rev: Long) {
        offline = false; tries = 0
        flight = null
        f.rev = rev
        var lost = false
        if (rev == serverRev + 1) { sent.add(f); log.add(Entry(rev, me, f.id, Ops.V, f.ops, true)); serverRev = rev }
        else if (rev > serverRev) { sent.add(f); limbo.add(f); pollSoon = true }
        else lost = true                               // an old answer for this id: it is in base already, or it is not there at all
        queueDirty = true
        if (lost) rebuild()
        afterLog()
        setState()
    }

    private fun onRefused(f: Mine, code: String) {
        val now = clock.nowMs()
        when (code) {
            "rate_limited" -> retryAt = now + 1000L
            "snapshot_first" -> { needSnap = true; retryAt = now + 5000L; trySnapshot(now) }
            "mess_full_live" -> {
                seat = false; retryAt = now + WATCH_MS
                if (!fullSaid) { fullSaid = true; host.onNotice(N_FULL, 0, "") }
                setState()
            }
            "too_big", "bad_input", "bad_name", "forbidden", "day_limit" -> {
                // the server will never take this batch: keep the screen's mess as a phone copy, then take the batch off the screen
                absorb()
                host.keepCopy(W_REFUSED)
                flight = null
                rebuild()
                writeQueue(true)
                host.onNotice(N_KEPT_COPY, f.ops.size, "")
            }
            "not_found", "blocked" -> goGone()
            else -> { offline = true; retryAt = now + BACKOFF[minOf(tries, BACKOFF.size - 1)]; tries++; setState() }
        }
    }

    /* ================= receiving ================= */

    private fun poll() {
        if (pollBusy || closed || gone || !ready) return
        pollBusy = true; pollSoon = false
        lastPollAt = clock.nowMs()
        val g = gen
        api.opsSince(messId, serverRev, Done { v, e ->
            if (g != gen || closed) return@Done
            pollBusy = false
            if (e == null && v != null) onSince(v)
            else {
                val code = e?.code ?: "net"
                if (code == "gap") doReset()
                else if (code == "not_found" || code == "blocked") goGone()
                else { offline = true; setState() }
            }
        })
    }

    private fun onSince(s: Since) {
        offline = false
        if (s.doc_rev > docRev) docRev = s.doc_rev
        if (s.members > 0 && s.members != memberCount) {
            val was = memberCount
            memberCount = s.members
            if (opened) host.onNotice(if (s.members > was) N_JOINED else N_LEFT, if (s.members > was) s.members - was else was - s.members, "")
            refreshMembers()
            if (was <= 1) joinLive()
        }
        val took = takeBatches(s.ops)
        val caught = serverRev >= s.rev
        if (!caught && took > 0) pollSoon = true
        if (caught) {
            // a batch the server confirmed that its log does not hold: take it off the screen
            var lost = false
            val it = limbo.iterator()
            while (it.hasNext()) { val m = it.next(); if (m.rev <= serverRev) { it.remove(); sent.remove(m); lost = true } }
            if (!opened) { opened = true; firstCatchUp() }
            if (lost) rebuild()
        }
        afterLog()
        setState()
    }

    /** Fills the log in rev order. Stops at a gap or at a batch that came without its ops (the next poll fetches them). */
    private fun takeBatches(list: List<Batch>): Int {
        var took = 0
        for (b in list) {
            if (b.rev <= serverRev) continue
            if (b.rev != serverRev + 1) break
            val raw = b.ops ?: break
            var mine = false
            val f = flight
            if (f != null && f.id == b.batch) {
                // my batch in flight: the log shows it before (or instead of) its answer
                mine = true; flight = null; flightBusy = false; tries = 0
                f.rev = b.rev; sent.add(f); queueDirty = true
            } else {
                for (i in limbo.indices) if (limbo[i].id == b.batch) { mine = true; limbo[i].rev = b.rev; limbo.removeAt(i); break }
            }
            val e = Entry(b.rev, b.user, b.batch, b.v, Ops.fromList(raw), mine)
            if (!mine) noteTheirs(e)
            log.add(e)
            serverRev = b.rev
            took++
        }
        return took
    }

    private fun noteTheirs(e: Entry) {
        newTheirs = true
        revUser[e.rev] = e.user
        if (e.v > Ops.V) {
            // a newer app's batch: kept in its place, skipped when applied, and nothing here knows what it touched
            tAll = e.rev
            if (!sawNewer) { sawNewer = true; host.onNotice(N_NEWER, 0, nameOf(e.user)) }
            return
        }
        for (op in e.ops) {
            val t = op.t
            if (t == "doc.reset") { resetWanted = true; continue }
            if (!Ops.known(op)) continue                // junk in a batch this app should know: dropped by everyone
            val id = Ops.ident(op)
            if (!id.isEmpty()) tKey[id] = e.rev
            for (s in Ops.inScopes(op)) tIn[s] = e.rev
            for (s in Ops.coverScopes(op)) tCover[s] = e.rev
            if (t.startsWith("loop.")) tLoops = e.rev
            if (t == "pad.put" || t == "pad.swap") tPads = e.rev
            if (t == "pad.put") {
                val sm = op.m["sound"]
                val key = Json.str(op.m["key"], "")
                if (sm is Map<*, *> && !key.isEmpty()) {
                    val x: MutableMap<String, Any?> = LinkedHashMap(Json.map(sm))
                    x["key"] = key
                    if (x["src"] !is String) x["src"] = "user"
                    if (x["state"] !is String) x["state"] = "ready"
                    host.onSound(Wire.sound(x))
                }
            }
        }
        if (tKey.size > 40000) {
            var keep = serverRev
            for (c in undoStack) if (c.seenRev < keep) keep = c.seenRev
            val oc = openChange
            if (oc != null && oc.seenRev < keep) keep = oc.seenRev
            tKey.values.removeAll { r -> r <= keep }; tIn.values.removeAll { r -> r <= keep }; tCover.values.removeAll { r -> r <= keep }
            revUser.keys.removeAll { r -> r <= keep }
        }
    }

    /** Reopened from the cache and caught up: the long-gap rule, and how many of my waiting ops ran into someone else's. */
    private fun firstCatchUp() {
        var theirs = 0
        for (e in log) if (!e.mine) theirs += e.ops.size
        val mine = pending()
        val old = cacheAt > 0L && clock.nowMs() - cacheAt > GAP_MS
        if (theirs > 0 && mine > 0 && (theirs > GAP_OPS || mine > GAP_OPS || old)) {
            host.keepCopy(W_GAP)
            flight = null; flightBusy = false; waiting.clear()
            undoStack.clear(); openChange = null
            release(serverRev)
            host.onNotice(N_KEPT_COPY, mine, "")
        } else if (theirs > 0 && mine > 0) {
            var n = 0
            var who = ""
            val f = flight
            if (f != null) for (op in f.ops) { val r = blocked(op, shownRev); if (r != 0L) { n++; val u = revUser[r]; if (u != null) who = nameOf(u) } }
            for (op in waiting) { val r = blocked(op, shownRev); if (r != 0L) { n++; val u = revUser[r]; if (u != null) who = nameOf(u) } }
            if (n > 0) host.onNotice(N_REPLACED, n, who)
        }
        refreshMembers()
        joinLive()
    }

    private fun applyEntry(p: Project, e: Entry) { if (e.v <= Ops.V) Ops.apply(p, e.ops) }

    /** My confirmed batches at the head of the log fold into base at once: they are on screen already. */
    private fun foldMine() {
        while (!log.isEmpty() && log[0].mine) { val e = log.removeAt(0); applyEntry(base, e); shownRev = e.rev }
    }

    private fun afterLog() {
        foldMine()
        if (queueDirty) writeQueue()                   // after every answer
        if (resetWanted) { doReset(); return }
        val fresh = newTheirs
        newTheirs = false
        if (held() > 0) {
            if (!playing) release(serverRev)
            else if (fresh) host.onHeld()
        }
    }

    /** base, then every batch in the log (all) or only mine, then my unconfirmed work. */
    private fun screen(all: Boolean): Project {
        val p = Ops.clone(base)
        for (e in log) if (all || e.mine) applyEntry(p, e)
        for (m in limbo) Ops.apply(p, m.ops)
        val f = flight
        if (f != null) Ops.apply(p, f.ops)
        if (!waiting.isEmpty()) Ops.apply(p, waiting)
        return p
    }

    private fun rebuild() {
        val next = screen(false)
        Ops.carryLocal(host.project(), next)
        shadow = Ops.clone(next)
        localMark = Ops.localMark(next)
        host.replace(next)
    }

    /** Puts other people's batches up to `upTo` on screen. Starts by taking in any change the host has not reported. */
    private fun release(upTo: Long) {
        absorb()
        while (!log.isEmpty() && log[0].rev <= upTo) { val e = log.removeAt(0); applyEntry(base, e); shownRev = e.rev }
        foldMine()
        rebuild()
        writeQueue(true); writeBase()
    }

    /* ================= snapshots ================= */

    private fun lowestId(): Boolean { for (m in members) if (m.id < me) return false; return true }

    private fun trySnapshot(now: Long) {
        if (snapBusy || !ready || !opened || closed || gone || offline || sawNewer || noSnap) return
        if (memberCount > 1 && !seat) return
        val fresh = serverRev > docRev
        val label = versionWanted
        if (label != null) {
            if (flight != null || !waiting.isEmpty() || !limbo.isEmpty()) return     // the version should hold what was just made
            versionWanted = null
            if (label.isEmpty() && !fresh) return
            snapshot(label)
            return
        }
        if (!fresh) return
        val k = if (memberCount > 1 && !lowestId()) 2 else 1
        if (needSnap || serverRev - docRev >= SNAP_OPS * k || now - lastSnapAt >= SNAP_MS * k
            || (memberCount <= 1 && localDirty && now - lastSnapAt >= SOLO_SEND_MS)) snapshot("")
    }

    /** The server's mess at serverRev: base and the whole log, none of my unconfirmed work. */
    private fun snapshot(label: String) {
        val p = Ops.clone(base)
        for (e in log) applyEntry(p, e)
        val solo = memberCount <= 1
        if (solo) Ops.carryLocal(host.project(), p)
        val doc = Ops.uploadDoc(p, !solo)
        val wasDirty = localDirty
        localDirty = false
        snapBusy = true
        val g = gen
        api.saveSnapshot(messId, doc, serverRev, Ops.V, label, Done { v, e ->
            if (g != gen || closed) return@Done
            snapBusy = false
            lastSnapAt = clock.nowMs()
            if (e == null && v != null) { if (v.doc_rev > docRev) docRev = v.doc_rev }
            else {
                val code = e?.code ?: "net"
                if (code == "client_too_old") noSnap = true
                else if (code != "stale") localDirty = localDirty || wasDirty
            }
            if (needSnap) { needSnap = false; retryAt = clock.nowMs() }
        })
    }
}
