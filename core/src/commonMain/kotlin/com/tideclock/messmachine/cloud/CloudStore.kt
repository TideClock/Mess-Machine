package com.tideclock.messmachine.cloud

import com.tideclock.messmachine.engine.Json
import kotlin.jvm.JvmField

/** The app side of Slice A. It owns the mess files on the phone; the store never touches them. */
interface StoreHost {
    fun onAccount()                                                  // state, me, usage changed: redraw
    fun onMesses()                                                   // rows or a mark changed: redraw
    fun localDoc(localId: String): String?                           // the mess file as last saved here, or null when gone
    fun needs(localId: String): List<String>                         // the bn: keys it needs (pads, chop bases, granular src)
    fun adopt(row: MessRow, doc: String): String                     // a cloud mess new to this phone: write a file, return its local id
    fun takeServer(localId: String, name: String, doc: String)       // overwrite the file (and the screen if open); nothing local is unsent
    /** A clash. Copy my file to a new local id named copyName (the screen follows the copy if it was open),
     *  write the server's doc and name into localId, return the new local id. */
    fun keepBoth(localId: String, copyName: String, serverName: String, serverDoc: String): String
    fun soundArrived(s: Sound, path: String)                         // add to the Library under s.key ("From shared messes" if not mine)
    fun soundGone(s: Sound)                                          // its maker left: drop the Library entry; the pad reads Missing
    fun notice(kind: Int, text: String)
    /** Added to the contract (it gave the store no way to learn them): what this phone knows about one of its own
     *  bn: sounds, for register_sound. Only kind, title, bpm, beats and mkey are read. null: an import with the key as title. */
    fun soundInfo(key: String): Sound? = null
}

/**
 * Slice A of accounts: the account state, the one mess list's cloud marks, and whole-mess sync with a compare
 * on rev (docs/ACCOUNTS-API.md s4.4). No threads of its own: everything is driven by the host's calls
 * (`tick` at least once a second) and by CloudApi callbacks, all on the host's one thread.
 *
 * The mess goes up exactly as the phone saved it (mutes, solos, mode and the picked loop included: a mess with
 * one member carries them, Review B M4). The sounds list is kept beside the mess, never in it.
 */
class CloudStore(private val api: CloudApi, private val tokens: TokenStore, private val cache: CloudCache,
                 private val clock: Clock, private val host: StoreHost) {
    companion object {
        const val OUT = 0; const val TERMS = 1; const val IN = 2                       // account state
        const val M_PHONE = 0; const val M_SYNCED = 1; const val M_WAITING = 2; const val M_SENDING = 3   // a mess's cloud mark
        const val M_OFFLINE = 4; const val M_BLOCKED = 5; const val M_REFUSED = 6
        const val N_KEPT_BOTH = 1; const val N_SIGNED_OUT = 2; const val N_QUOTA = 3; const val N_TOO_BIG = 4
        const val N_TOO_LONG = 5; const val N_UPDATE = 6; const val N_FULL = 7
        const val SAVE_MS = 5000L; const val PULL_MS = 60000L
        /** A mess that keeps changing still goes up this long after its first unsent change. */
        const val MAX_WAIT_MS = 30000L
        /** The token is refreshed this long before it ends. */
        const val EARLY_MS = 300000L
        const val UPDATE_TEXT = "Update to open this mess"
        private val RETRY = longArrayOf(1000L, 2000L, 5000L, 10000L)
    }

    @JvmField var state = OUT
    @JvmField var me: Me? = null
    /** The last my_messes answer (kept in list.json for offline). */
    @JvmField var rows: List<MessRow> = emptyList()
    /** The host sets it: on Wi-Fi, or acct-data is on. false: sounds wait (up and down). */
    @JvmField var dataOk = false
    /** The address a code was last sent to, "" for none: the "code sent" step. */
    @JvmField var codeSentTo = ""
    /** The host sets it while the Messes sheet is open: the list is pulled again every PULL_MS. */
    @JvmField var listOpen = false
    /** false after a call got no answer, true again once one did. */
    @JvmField var online = true

    /** One phone mess and its cloud mess. The first group is kept in index.json. */
    private class Link(var local: String) {
        var cloud = ""; var rev = -1L          // rev -1: the cloud id is picked, the mess is not made yet
        var dirty = false; var off = false
        var name = ""                          // the name this phone wants
        var sname = ""                         // the server's name as last seen
        var role = "owner"
        var tried = false                      // a create_mess was sent with no answer yet
        var del = false                        // kept off the cloud: my cloud mess still has to be deleted
        var snd = false                        // some of its sounds still have to go up
        val maybe = ArrayList<String>()        // hashes of docs sent with no answer: the server may hold one of them
        // not kept
        var ver = 0; var job = 0; var seq = 0
        var busy = false; var pushing = false
        var dueAt = 0L; var firstAt = 0L; var retryAt = 0L; var sndAt = 0L; var tries = 0; var err = 0
        var list: MutableList<Sound>? = null
        var fetching = false; var again = false; var force = false
        fun made(): Boolean = rev >= 0L && cloud.isNotEmpty()
    }

    /** One push of one mess, from its sounds to its doc. */
    private class Push(val l: Link, val doc: String, val ver: Int, val job: Int, val gen: Int, val docDirty: Boolean) {
        val keys = ArrayList<String>()
        val attach = ArrayList<String>()
        val made = LinkedHashMap<String, Sound>()
        var created = false; var needPut = false; var clashes = 0; var attached = 0
    }

    private class Known(val sha: String, val owner: String, var here: Boolean)

    private val links = ArrayList<Link>()
    private var session: Session? = null
    private var gen = 0                                  // goes up at every sign-in and sign-out; old answers are dropped
    private var seq = 0                                  // counts refreshes
    private val later = ArrayList<() -> Unit>()          // answers the store makes itself, run at the next tick
    private val have = HashSet<String>()                 // "key|sha256" on this phone and handed to the host, this run
    private val known = LinkedHashMap<String, Known>()   // key to hash and owner, from every sounds list seen (known.json)
    private val skip = HashSet<String>()                 // keys the server refused this run
    private val updSaid = HashSet<String>()
    private var tokBusy = false; private var tokRetryAt = 0L; private var tokTries = 0
    private var meBusy = false; private var meRetryAt = 0L; private var meTries = 0
    private var lastPull = 0L
    private var started = false

    /* ---------- account ---------- */

    /** Loads the token, setSession, me, my_messes. Safe with nothing stored. */
    fun start() {
        if (started || !api.configured()) return
        started = true
        api.setSessionListener { s -> sessionFromApi(s) }
        loadIndex(); loadKnown()
        val s = readSession(tokens.load())
        if (s == null) { state = OUT; host.onAccount(); return }
        session = s
        api.setSession(s)
        loadList()
        val m = me
        state = if (m != null && m.terms_ok) IN else TERMS
        host.onAccount(); host.onMesses()
        for (l in ArrayList(links)) if (l.made() && !l.off) { loadSounds(l); afterOpen(l) {} }
        refresh()
    }

    /** Signed in, with me() not answered yet: state is TERMS and me is null. Show "checking", not the terms. */
    fun checking(): Boolean = session != null && me == null
    /** The app should ask for a name (at the first share or join). */
    fun nameNeeded(): Boolean { val m = me; return m != null && !m.name_set }
    fun userId(): String = session?.userId ?: ""
    fun email(): String = session?.email ?: ""

    fun requestCode(email: String, cb: Done<Boolean>) {
        val e = email.trim()
        api.requestCode(e) { ok, err ->
            if (ok != null) { codeSentTo = e; online = true; host.onAccount() } else if (err != null && err.code == "net") online = false
            cb.done(ok, err)
        }
    }

    /** Saves the session, then me(); state becomes TERMS or IN. */
    fun verify(email: String, code: String, cb: Done<Me>) {
        api.verifyCode(email.trim(), code.trim()) { s, err -> if (s == null) cb.done(null, err) else signedIn(s, cb) }
    }

    private fun signedIn(s: Session, cb: Done<Me>) {
        gen++
        tokens.save(Wire.sessionJson(s))          // before anything else: refresh tokens are single use
        session = s
        api.setSession(s)
        codeSentTo = ""; online = true
        state = TERMS; me = null
        meBusy = true
        val g = gen
        api.me { m, err ->
            if (g == gen) {
                meBusy = false
                if (m != null) { gotMe(m); cb.done(m, null); if (state == IN) refreshList() }
                else { meFailed(err); host.onAccount(); cb.done(null, err) }
            }
        }
    }

    private fun gotMe(m: Me) {
        me = m
        meTries = 0
        state = if (m.terms_ok) IN else TERMS
        online = true
        saveList()
        host.onAccount()
    }

    private fun meFailed(err: CloudErr?) {
        if (err != null && err.code == "net") online = false
        meRetryAt = clock.nowMs() + RETRY[minOf(meTries, RETRY.size - 1)]
        meTries++
    }

    private fun fetchMe(then: (Boolean) -> Unit) {
        if (session == null) { then(false); return }
        meBusy = true
        val g = gen
        api.me { m, err ->
            if (g == gen) {
                meBusy = false
                if (m != null) { gotMe(m); then(true) } else { meFailed(err); then(false) }
            }
        }
    }

    /** accept_terms(me.terms_version); nothing uploads before it. */
    fun acceptTerms(cb: Done<Boolean>) {
        val m = me
        if (m == null || session == null) { later.add { cb.done(null, CloudErr("auth", 0, "")) }; return }
        val g = gen
        api.acceptTerms(m.terms_version) { ok, err ->
            if (g == gen) {
                if (ok != null) {
                    me = Me(m.id, m.display_name, m.name_set, m.colour, m.sound_bytes, m.sound_limit, m.messes, m.mess_limit, true, m.terms_version, m.is_admin)
                    state = IN
                    saveList()
                    host.onAccount()
                    cb.done(true, null)
                    refresh()
                } else {
                    if (err != null && err.code == "terms_outdated") fetchMe {}
                    cb.done(null, err)
                }
            }
        }
    }

    fun setName(name: String, cb: Done<Me>) {
        val g = gen
        api.setProfile(name) { m, err ->
            if (g == gen) {
                if (m != null) { me = m; saveList(); host.onAccount() }
                cb.done(m, err)
            }
        }
    }

    /** Also terms-decline. Phone files and links stay. */
    fun signOut() {
        if (session != null) api.signOut { _, _ -> }
        signedOut(false)
    }

    private fun signedOut(say: Boolean) {
        gen++
        session = null
        api.setSession(null)
        tokens.save(null)
        cache.save("list.json", null)
        state = OUT; me = null; rows = emptyList(); codeSentTo = ""
        tokBusy = false; meBusy = false
        for (l in links) { l.busy = false; l.pushing = false; l.fetching = false; l.job++ }
        host.onAccount(); host.onMesses()
        if (say) host.notice(N_SIGNED_OUT, "You were signed out. Sign in again to save online.")
    }

    /** The api replaced the session by itself (a refresh), or found it dead. */
    private fun sessionFromApi(s: Session?) {
        if (session == null) return
        if (s != null) { tokens.save(Wire.sessionJson(s)); session = s } else signedOut(true)
    }

    private fun readSession(text: String?): Session? {
        if (text == null || text.isEmpty()) return null
        return try { val s = Wire.session(Json.parse(text)); if (s.access.isEmpty() || s.refresh.isEmpty()) null else s } catch (e: Exception) { null }
    }

    /* ---------- the clock ---------- */

    /** At least once a second: refresh 5 min early, retries (1, 2, 5, 10 s), queues. */
    fun tick() {
        if (later.isNotEmpty()) { val run = ArrayList(later); later.clear(); for (r in run) r() }
        val s = session ?: return
        val now = clock.nowMs()
        if (!tokBusy && s.expiresAt > 0L && now >= s.expiresAt * 1000L - EARLY_MS && now >= tokRetryAt) {
            tokBusy = true
            val g = gen
            api.refresh(s.refresh) { fresh, err ->
                if (g == gen) {
                    tokBusy = false
                    if (fresh != null) { tokens.save(Wire.sessionJson(fresh)); session = fresh; api.setSession(fresh); tokTries = 0 }
                    else if (err != null && err.code == "auth") signedOut(true)
                    else { tokRetryAt = clock.nowMs() + RETRY[minOf(tokTries, RETRY.size - 1)]; tokTries++ }
                }
            }
        }
        if (session == null) return
        if (me == null && !meBusy && now >= meRetryAt) fetchMe { ok -> if (ok && state == IN) refreshList() }
        if (state != IN) return
        if (listOpen && now - lastPull >= PULL_MS) refresh()
        pump(now)
    }

    /** Pushes whatever waits now. */
    fun background() {
        if (state != IN) return
        val now = clock.nowMs()
        for (l in links) if (work(l, now + MAX_WAIT_MS)) { l.dueAt = now; l.retryAt = 0L; l.sndAt = 0L; if (l.err == M_OFFLINE) l.err = 0 }
        pump(now)
    }

    /** my_messes and me, then the pull rule. */
    fun refresh() {
        if (session == null) return
        lastPull = clock.nowMs()
        fetchMe { ok -> if (ok && state == IN) refreshList() }
    }

    private fun refreshList() {
        val g = gen
        val mine = ++seq
        api.myMesses { r, err ->
            if (g == gen) {
                if (r != null) { rows = r; online = true; saveList(); pull(mine); host.onMesses() }
                else if (err != null && err.code == "net") online = false
            }
        }
    }

    /* ---------- the list ---------- */

    private fun find(localId: String): Link? { for (l in links) if (l.local == localId) return l; return null }
    private fun byCloud(cloudId: String): Link? { for (l in links) if (l.cloud == cloudId && l.made()) return l; return null }

    /** "" when not online. */
    fun cloudId(localId: String): String { val l = find(localId); return if (l != null && l.made() && !l.off) l.cloud else "" }
    /** "" when not on this phone. */
    fun localId(cloudId: String): String { val l = byCloud(cloudId); return if (l != null && !l.off) l.local else "" }

    /** M_* of one mess. */
    fun mark(localId: String): Int {
        val l = find(localId)
        if (l == null || l.off || session == null) return M_PHONE
        if (l.err != 0) return l.err
        if (l.pushing) return M_SENDING
        if (l.dirty || !l.made() || l.snd || renameDue(l)) return M_WAITING
        return M_SYNCED
    }

    /** How many messes have changes not sent yet. */
    fun waiting(): Int {
        var n = 0
        for (l in links) if (!l.off && (l.dirty || l.pushing || renameDue(l))) n++
        return n
    }

    /** Its sounds not on this phone yet. */
    fun missing(localId: String): Int { val l = find(localId); return if (l == null) 0 else missingOf(l) }

    /** The sounds list kept beside this mess (the last one the server gave), empty when it is not online. */
    fun sounds(localId: String): List<Sound> { val l = find(localId); return if (l == null || !l.made()) emptyList() else ArrayList(loadSounds(l)) }

    private fun missingOf(l: Link): Int {
        if (!l.made()) return 0
        var n = 0
        for (s in loadSounds(l)) if (s.src != "gone" && !arrived(s)) n++
        return n
    }
    private fun renameDue(l: Link): Boolean = l.made() && l.role == "owner" && l.name.isNotEmpty() && l.name != l.sname

    /** Call after every local save: signed in and not kept off means it goes online. */
    fun saved(localId: String, name: String) {
        var l = find(localId)
        if (l == null) {
            if (session == null) return
            l = Link(localId)
            l.cloud = api.newId()
            l.seq = seq
            links.add(l)
        }
        if (l.off) return
        if (l.cloud.isEmpty()) { l.cloud = api.newId(); l.rev = -1L }
        if (name.isNotEmpty()) l.name = name
        val now = clock.nowMs()
        l.dirty = true
        l.ver++
        if (l.firstAt == 0L) l.firstAt = now
        l.dueAt = minOf(now + SAVE_MS, l.firstAt + MAX_WAIT_MS)
        if (l.err == M_REFUSED || l.err == M_BLOCKED) l.err = 0
        saveIndex()
        host.onMesses()
    }

    /** "Keep off the cloud": true unlinks (and deletes my cloud mess), false links again. */
    fun keepOff(localId: String, off: Boolean) {
        var l = find(localId)
        val now = clock.nowMs()
        if (off) {
            if (l == null) { l = Link(localId); links.add(l) }
            l.off = true; l.dirty = false; l.job++; l.busy = false; l.pushing = false; l.err = 0; l.firstAt = 0L
            if (l.made()) { l.del = true; l.retryAt = 0L } else { l.cloud = ""; l.rev = -1L; l.tried = false; l.maybe.clear() }
        } else {
            if (l == null || !l.off) return
            l.off = false
            l.del = false; l.job++; l.busy = false
            if (!l.made()) { l.cloud = api.newId(); l.rev = -1L; l.tried = false; l.maybe.clear(); l.sname = ""; l.seq = seq }
            l.dirty = true; l.ver++; l.firstAt = now; l.dueAt = now
        }
        saveIndex()
        host.onMesses()
        if (state == IN) pump(now)
    }

    fun rename(localId: String, name: String, cb: Done<Boolean>) {
        val l = find(localId)
        if (l == null || l.off) { later.add { cb.done(true, null) }; return }
        l.name = name
        saveIndex()
        if (!l.made() || state != IN) { later.add { cb.done(true, null) }; return }      // it will be made with this name
        if (l.role != "owner") { l.name = l.sname; saveIndex(); later.add { cb.done(null, CloudErr("forbidden", 0, "")) }; return }
        val g = gen
        api.renameMess(l.cloud, name) { r, err ->
            if (g == gen) {
                if (r != null) { if (l.name == name) l.sname = name; online = true; rowNamed(l.cloud, name); saveIndex(); host.onMesses(); cb.done(true, null) }
                else {
                    val code = err?.code ?: ""
                    if (code == "net") online = false
                    else if (code != "http" && code != "auth" && code != "rate_limited" && code != "terms") { l.name = l.sname; saveIndex() }   // refused: the server's name stays
                    cb.done(null, err)
                }
            }
        }
    }

    /** delete_mess when mine; the phone file stays, kept off the cloud so the next save does not put it back. */
    fun delete(localId: String, cb: Done<Boolean>) {
        val l = find(localId)
        if (l == null || !l.made()) { later.add { cb.done(true, null) }; return }
        deleteCloud(l.cloud, cb)
    }

    /** The same for an online mess by its cloud id, on this phone or not. */
    fun deleteCloud(cloudId: String, cb: Done<Boolean>) {
        if (session == null) { later.add { cb.done(null, CloudErr("auth", 0, "")) }; return }
        val g = gen
        api.deleteMess(cloudId) { ok, err ->
            if (g == gen) {
                if (ok != null || (err != null && err.code == "not_found")) {
                    val l = byCloud(cloudId)
                    if (l != null) unlink(l)
                    dropRow(cloudId)
                    val m = me
                    if (ok != null && m != null && m.messes > 0) me = Me(m.id, m.display_name, m.name_set, m.colour, m.sound_bytes, m.sound_limit, m.messes - 1, m.mess_limit, m.terms_ok, m.terms_version, m.is_admin)
                    online = true
                    saveIndex(); saveList()
                    host.onAccount(); host.onMesses()
                    cb.done(true, null)
                } else { if (err != null && err.code == "net") online = false; cb.done(null, err) }
            }
        }
    }

    /** Answers the local id: open_mess, then adopt or takeServer, then sounds. Offline, a mess already here answers its phone copy. */
    fun open(cloudId: String, cb: Done<String>) {
        if (session == null) { later.add { cb.done(null, CloudErr("auth", 0, "")) }; return }
        val g = gen
        api.openMess(cloudId) { o, err -> if (g == gen) opened(cloudId, o, err, cb) }
    }

    private fun opened(cloudId: String, o: Opened?, err: CloudErr?, cb: Done<String>) {
        var l = byCloud(cloudId)
        if (o == null) {
            val code = err?.code ?: ""
            if (code == "not_found" && l != null) { unlink(l); dropRow(cloudId); saveIndex(); saveList(); host.onMesses() }
            if (code == "net") online = false
            if (code == "net" && l != null && !l.off && host.localDoc(l.local) != null) cb.done(l.local, null) else cb.done(null, err)
            return
        }
        online = true
        if (o.doc_rev < o.rev) {       // a newer app is editing it by ops: leave it alone
            host.notice(N_UPDATE, UPDATE_TEXT)
            cb.done(null, CloudErr("client_too_old", 0, UPDATE_TEXT))
            return
        }
        val mine = if (l == null) null else host.localDoc(l.local)
        if (l != null && (mine == null || l.off)) { links.remove(l); l = null }      // its file is gone: take it in again
        if (l == null) {
            val local = host.adopt(rowOf(o), o.doc)
            l = Link(local)
            l.cloud = o.id; l.rev = o.rev; l.name = o.name; l.sname = o.name; l.role = o.role; l.seq = seq
            links.add(l)
            setSounds(l, o.sounds)
        } else if (l.busy) {
            // a push or pull of it is running: that one decides
        } else if (l.dirty && o.rev > l.rev && mine != null) {
            if (settle(l, o, mine) == 0) { l.dirty = false; l.firstAt = 0L }
        } else {
            take(l, o)
        }
        saveIndex()
        host.onMesses()
        val local = l.local
        afterOpen(l) { cb.done(local, null) }
    }

    /** "Get 3 sounds now": fetch once whatever dataOk says. */
    fun getSounds(localId: String) {
        val l = find(localId)
        if (l == null || !l.made() || l.off || session == null) return
        l.force = true
        if (l.busy) { afterOpen(l) {}; return }
        pullOne(l)
    }

    /* ---------- files the store keeps ---------- */

    private fun loadIndex() {
        links.clear()
        val text = cache.load("index.json") ?: return
        try {
            val now = clock.nowMs()
            for (o in Json.list(Json.map(Json.parse(text))["links"])) {
                val m = Json.map(o)
                val l = Link(Json.str(m["local"], ""))
                if (l.local.isEmpty()) continue
                l.cloud = Json.str(m["cloud"], ""); l.rev = Json.num(m["rev"], -1.0).toLong()
                l.dirty = Json.bool(m["dirty"], false); l.off = Json.bool(m["off"], false)
                l.name = Json.str(m["name"], ""); l.sname = Json.str(m["sname"], ""); l.role = Json.str(m["role"], "owner")
                l.tried = Json.bool(m["tried"], false); l.del = Json.bool(m["del"], false); l.snd = Json.bool(m["snd"], false)
                for (x in Json.list(m["maybe"])) if (x is String) l.maybe.add(x)
                if (l.dirty) { l.ver = 1; l.firstAt = now; l.dueAt = now }
                links.add(l)
            }
        } catch (e: Exception) { links.clear() }
    }

    private fun saveIndex() {
        val out = ArrayList<Any?>()
        for (l in links) {
            val m = LinkedHashMap<String, Any?>()
            m["local"] = l.local; m["cloud"] = l.cloud; m["rev"] = l.rev; m["dirty"] = l.dirty; m["off"] = l.off
            m["name"] = l.name; m["sname"] = l.sname; m["role"] = l.role
            if (l.tried) m["tried"] = true
            if (l.del) m["del"] = true
            if (l.snd) m["snd"] = true
            if (l.maybe.isNotEmpty()) m["maybe"] = ArrayList<Any?>(l.maybe)
            out.add(m)
        }
        val top = LinkedHashMap<String, Any?>()
        top["v"] = 1; top["links"] = out
        cache.save("index.json", Json.write(top))
    }

    private fun loadList() {
        val text = cache.load("list.json") ?: return
        try {
            val m = Json.map(Json.parse(text))
            val mm = m["me"]
            me = if (mm is Map<*, *>) Wire.me(mm) else null
            rows = Wire.list(m["rows"]) { Wire.messRow(it) }
        } catch (e: Exception) { me = null; rows = emptyList() }
    }

    private fun saveList() {
        val top = LinkedHashMap<String, Any?>()
        top["v"] = 1
        val m = me
        if (m != null) top["me"] = Wire.meMap(m)
        val out = ArrayList<Any?>()
        for (r in rows) out.add(Wire.messRowMap(r))
        top["rows"] = out
        cache.save("list.json", Json.write(top))
    }

    private fun loadKnown() {
        known.clear()
        val text = cache.load("known.json") ?: return
        try {
            for ((k, v) in Json.map(Json.map(Json.parse(text))["sounds"])) {
                val m = Json.map(v)
                known[k] = Known(Json.str(m["sha256"], ""), Json.str(m["owner"], ""), Json.bool(m["here"], false))
            }
        } catch (e: Exception) { known.clear() }
    }

    private fun saveKnown() {
        val out = LinkedHashMap<String, Any?>()
        for ((k, v) in known) {
            val m = LinkedHashMap<String, Any?>()
            m["sha256"] = v.sha; m["owner"] = v.owner; m["here"] = v.here
            out[k] = m
        }
        val top = LinkedHashMap<String, Any?>()
        top["v"] = 1; top["sounds"] = out
        cache.save("known.json", Json.write(top))
    }

    private fun loadSounds(l: Link): MutableList<Sound> {
        val have = l.list
        if (have != null) return have
        val out = ArrayList<Sound>()
        val text = if (l.cloud.isEmpty()) null else cache.load(l.cloud + ".sounds.json")
        if (text != null) try { for (o in Json.list(Json.parse(text))) out.add(Wire.sound(o)) } catch (e: Exception) { out.clear() }
        l.list = out
        return out
    }

    private fun setSounds(l: Link, list: List<Sound>) {
        l.list = ArrayList(list)
        writeSounds(l)
    }

    private fun writeSounds(l: Link) {
        val out = ArrayList<Any?>()
        for (s in loadSounds(l)) out.add(Wire.soundMap(s))
        cache.save(l.cloud + ".sounds.json", Json.write(out))
    }

    private fun rowOf(o: Opened): MessRow {
        for (r in rows) if (r.id == o.id) return r
        var owner = ""
        for (m in o.members) if (m.id == o.owner_id) owner = m.name
        return MessRow(o.id, o.name, o.role, owner, o.rev, "", o.members.size, o.share_mode, o.doc.length, false)
    }
    private fun dropRow(cloudId: String) {
        val out = ArrayList<MessRow>()
        for (r in rows) if (r.id != cloudId) out.add(r)
        rows = out
    }
    private fun rowNamed(cloudId: String, name: String) {
        val out = ArrayList<MessRow>()
        for (r in rows) out.add(if (r.id != cloudId) r else MessRow(r.id, name, r.role, r.owner_name, r.rev, r.updated_at, r.members, r.share_mode, r.doc_bytes, r.blocked))
        rows = out
    }
    /** After a push: the row shows the new rev and name, and a mess just made joins the top of the list. */
    private fun rowPushed(l: Link, docLen: Int) {
        val out = ArrayList<MessRow>()
        var found = false
        for (r in rows) {
            if (r.id != l.cloud) out.add(r)
            else { found = true; out.add(MessRow(r.id, l.sname, r.role, r.owner_name, l.rev, r.updated_at, r.members, r.share_mode, docLen, r.blocked)) }
        }
        if (!found) out.add(0, MessRow(l.cloud, l.sname, l.role, me?.display_name ?: "", l.rev, "", 1, "off", docLen, false))
        rows = out
        saveList()
    }

    /** The link goes; the phone file stays, kept off the cloud so the next save does not make it again. */
    private fun unlink(l: Link) {
        if (l.cloud.isNotEmpty()) cache.save(l.cloud + ".sounds.json", null)
        l.job++
        l.cloud = ""; l.rev = -1L; l.dirty = false; l.off = true; l.tried = false; l.del = false; l.snd = false
        l.maybe.clear(); l.list = null; l.err = 0; l.busy = false; l.pushing = false; l.fetching = false; l.firstAt = 0L
    }

    /* ---------- push ---------- */

    private fun work(l: Link, now: Long): Boolean {
        if (l.del) return true
        if (l.off) return false
        if (l.dirty) return now >= l.dueAt
        return renameDue(l) || (l.snd && dataOk && now >= l.sndAt)
    }

    private fun pump(now: Long) {
        if (state != IN) return
        for (l in ArrayList(links)) {
            if (l.busy || now < l.retryAt || !work(l, now)) continue
            if (l.del) removeCloud(l)
            else if (l.err != M_BLOCKED && l.err != M_REFUSED) push(l)
        }
    }

    /** "Keep off the cloud" for a mess that was online: delete my cloud mess, again until it is gone. */
    private fun removeCloud(l: Link) {
        l.busy = true
        val g = gen; val j = ++l.job
        api.deleteMess(l.cloud) { ok, err ->
            if (g == gen && j == l.job) {
                l.busy = false
                val code = err?.code ?: ""
                if (ok != null || code == "not_found" || code == "forbidden") {
                    val id = l.cloud
                    unlink(l)
                    dropRow(id)
                    online = true
                    saveIndex(); saveList()
                    host.onMesses()
                } else {
                    if (code == "net") online = false
                    l.retryAt = clock.nowMs() + RETRY[minOf(l.tries, RETRY.size - 1)]; l.tries++
                }
            }
        }
    }

    private fun live(p: Push): Boolean = p.gen == gen && p.job == p.l.job

    private fun push(l: Link) {
        val doc = host.localDoc(l.local)
        if (doc == null) {                       // the phone file is gone: nothing to send, the cloud mess stays as it is
            links.remove(l)
            saveIndex(); host.onMesses()
            return
        }
        if (l.name.isEmpty()) l.name = nameIn(doc)
        val p = Push(l, doc, l.ver, ++l.job, gen, l.dirty)
        l.busy = true; l.pushing = true; l.snd = false
        if (l.err == M_OFFLINE) l.err = 0
        for (k in host.needs(l.local)) if (k.startsWith("bn:") && !p.keys.contains(k)) p.keys.add(k)
        host.onMesses()
        soundStep(p, 0)
    }

    private fun nameIn(doc: String): String {
        val n = try { Json.str(Json.map(Json.parse(doc))["name"], "") } catch (e: Exception) { "" }
        return if (n.isEmpty()) "Untitled mess" else n
    }

    private fun listed(l: Link, key: String): Sound? { for (s in loadSounds(l)) if (s.key == key) return s; return null }

    /** First the sounds: one key after the other. A sound whose listed owner is not me is only attached, never uploaded. */
    private fun soundStep(p: Push, i: Int) {
        if (!live(p)) return
        if (i >= p.keys.size) { docStep(p); return }
        val l = p.l
        val key = p.keys[i]
        val on = if (l.made()) listed(l, key) else null
        if (skip.contains(key) || (on != null && on.src != "gone")) { soundStep(p, i + 1); return }    // refused before, or this mess lists it already
        if (known[key] != null) { p.attach.add(key); soundStep(p, i + 1); return }                      // the file is on the server: attach, no upload
        if (!dataOk && !l.force) { l.snd = true; l.sndAt = 0L; soundStep(p, i + 1); return }           // sounds wait for Wi-Fi
        api.prepareUpload(key) { pr, _ ->
            if (live(p)) { if (pr == null) soundStep(p, i + 1) else register(p, i, key, pr) }           // not on this phone: nothing to send
        }
    }

    private fun register(p: Push, i: Int, key: String, pr: Prepared) {
        val info = host.soundInfo(key)
        var kind = info?.kind ?: ""
        if (!(kind == "imp" || kind == "mic" || kind == "rs" || kind == "take")) kind = "imp"
        var title = info?.title ?: ""
        if (title.isEmpty()) title = key.substring(3)
        val my = me?.id ?: ""
        val s = Sound(key, pr.sha256, "user", my, "wav", pr.bytes, pr.seconds, pr.rate, kind, title, info?.bpm ?: 0.0, info?.beats ?: 0, info?.mkey ?: "", "ready")
        api.registerSound(pr, kind, key, title, s.bpm, s.beats, s.mkey) { r, err ->
            if (live(p)) {
                if (r == null) soundErr(p, i, key, err)
                else {
                    usage(r.used)
                    if (r.state == "ready") uploaded(p, i, s)
                    else api.upload(r.path, pr.path, pr.bytes) { ok, err2 ->
                        if (live(p)) {
                            if (ok == null) soundErr(p, i, key, err2)
                            else api.finishUpload(pr.sha256) { u, err3 ->
                                if (live(p)) { if (u == null) soundErr(p, i, key, err3) else { usage(u.used); uploaded(p, i, s) } }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun uploaded(p: Push, i: Int, s: Sound) {
        known[s.key] = Known(s.sha256, s.owner, true)
        saveKnown()
        p.made[s.key] = s
        p.attach.add(s.key)
        soundStep(p, i + 1)
    }

    /** A sound the server refused gives a notice and stays on this phone; the doc still saves. */
    private fun soundErr(p: Push, i: Int, key: String, err: CloudErr?) {
        val code = err?.code ?: ""
        when (code) {
            "quota" -> { skip.add(key); host.notice(N_QUOTA, "Your online sound space is full. This sound stays on this phone.") }
            "too_long" -> { skip.add(key); host.notice(N_TOO_LONG, "That sound is over 100 seconds. It stays on this phone.") }
            "too_big" -> { skip.add(key); host.notice(N_TOO_BIG, "That sound is over 20 MB. It stays on this phone.") }
            "too_many_pending", "not_uploaded", "not_found" -> { p.l.snd = true; p.l.sndAt = clock.nowMs() + 60000L }
            "net", "http", "auth", "terms", "rate_limited" -> { pushFail(p, err); return }
            else -> skip.add(key)
        }
        soundStep(p, i + 1)
    }

    private fun usage(used: Long) {
        val m = me ?: return
        if (m.sound_bytes == used) return
        me = Me(m.id, m.display_name, m.name_set, m.colour, used, m.sound_limit, m.messes, m.mess_limit, m.terms_ok, m.terms_version, m.is_admin)
        host.onAccount()
    }

    /** Not linked: createMess. Then the attaches (they need the mess), then putDoc. */
    private fun docStep(p: Push) {
        val l = p.l
        if (l.made()) { attachStep(p, 0); return }
        val again = l.tried
        l.tried = true
        saveIndex()
        api.createMess(l.cloud, l.name, p.doc) { r, err ->
            if (live(p)) {
                if (r == null) { if (err != null && err.code == "net") lost(l, p.doc); pushFail(p, err) }
                else {
                    // an earlier try may have made it with an older doc: then this doc still has to go
                    // (maybe is empty when the app was killed before any answer: what was sent then is not known)
                    val same = sig(p.doc)
                    var other = l.maybe.isEmpty() || r.rev != 0L
                    for (x in l.maybe) if (x != same) other = true
                    p.needPut = again && other
                    l.maybe.clear()
                    l.rev = r.rev; l.sname = l.name; l.role = "owner"; l.tried = false; l.seq = seq
                    p.created = true
                    val m = me
                    if (m != null) me = Me(m.id, m.display_name, m.name_set, m.colour, m.sound_bytes, m.sound_limit, m.messes + 1, m.mess_limit, m.terms_ok, m.terms_version, m.is_admin)
                    if (!p.needPut) docDone(p)
                    saveIndex()
                    attachStep(p, 0)
                }
            }
        }
    }

    /** A doc went out and no answer came: the server may hold it. Its hash is kept so a later clash can tell. */
    private fun lost(l: Link, doc: String) {
        val s = sig(doc)
        if (!l.maybe.contains(s)) l.maybe.add(s)
        while (l.maybe.size > 16) l.maybe.removeAt(1)
        saveIndex()
    }

    private fun attachStep(p: Push, i: Int) {
        if (!live(p)) return
        if (i >= p.attach.size) { putStep(p); return }
        val l = p.l
        val key = p.attach[i]
        val k = known[key]
        if (k == null) { attachStep(p, i + 1); return }
        api.attachSound(l.cloud, key, k.owner, k.sha) { ok, err ->
            if (live(p)) {
                val code = err?.code ?: ""
                if (ok != null) {
                    val list = loadSounds(l)
                    val it = list.iterator()
                    while (it.hasNext()) if (it.next().key == key) it.remove()
                    list.add(soundFor(p, key, k))
                    writeSounds(l)
                    p.attached++
                    attachStep(p, i + 1)
                } else if (code == "not_found") attachLost(p, i, key, k)
                else if (code == "net" || code == "http" || code == "auth" || code == "terms" || code == "rate_limited" || code == "blocked") pushFail(p, err)
                else attachStep(p, i + 1)
            }
        }
    }

    /** attach_sound said not_found: either the mess is gone, or the sound's maker left. open_mess tells which. */
    private fun attachLost(p: Push, i: Int, key: String, k: Known) {
        val l = p.l
        api.openMess(l.cloud) { o, err ->
            if (live(p)) {
                if (o == null) pushFail(p, err)
                else {
                    val s = soundFor(p, key, k)
                    known.remove(key)
                    saveKnown()
                    if (k.owner != (me?.id ?: "")) {
                        // its maker left: drop the cache file and the Library entry before anything plays
                        api.dropSound(k.sha)
                        have.remove(key + "|" + k.sha)
                        host.soundGone(s)
                    } else { l.snd = true; l.sndAt = clock.nowMs() + 1000L }      // my own row went: send the file again if it is here
                    attachStep(p, i + 1)
                }
            }
        }
    }

    private fun soundFor(p: Push, key: String, k: Known): Sound {
        val made = p.made[key]
        if (made != null) return made
        for (x in links) { val list = x.list; if (list != null) for (s in list) if (s.key == key && s.sha256 == k.sha && s.src != "gone") return s }
        return Sound(key, k.sha, "user", k.owner, "wav", 0L, 0.0, 0, "", "", 0.0, 0, "", "ready")
    }

    private fun putStep(p: Push) {
        if (!live(p)) return
        val l = p.l
        if (p.created && !p.needPut) { renameStep(p); return }
        // sounds that went up after their doc: the doc goes again so the rev moves and the other phones look
        if (!p.created && !p.docDirty && p.attached == 0) { renameStep(p); return }
        api.putDoc(l.cloud, p.doc, l.rev, "Saved") { s, err ->
            if (live(p)) {
                if (s != null) { l.rev = s.rev; l.maybe.clear(); docDone(p); saveIndex(); renameStep(p) }
                else if (err != null && err.code == "conflict") clash(p)
                else { if (err != null && err.code == "net") lost(l, p.doc); pushFail(p, err) }
            }
        }
    }

    private fun docDone(p: Push) {
        val l = p.l
        if (l.ver == p.ver) { l.dirty = false; l.firstAt = 0L }
    }

    private fun renameStep(p: Push) {
        if (!live(p)) return
        val l = p.l
        if (!renameDue(l)) { if (l.role != "owner") l.name = l.sname; pushDone(p); return }
        val name = l.name
        api.renameMess(l.cloud, name) { r, err ->
            if (live(p)) {
                val code = err?.code ?: ""
                if (r != null) { l.sname = name; pushDone(p) }
                else if (code == "net" || code == "http" || code == "auth" || code == "terms" || code == "rate_limited") pushFail(p, err)
                else if (code == "not_found") pushFail(p, err)
                else { l.name = l.sname; pushDone(p) }       // refused: the server's name stays
            }
        }
    }

    private fun pushDone(p: Push) {
        val l = p.l
        l.busy = false; l.pushing = false; l.tries = 0; l.retryAt = 0L; l.err = 0; l.force = false
        online = true
        if (l.dirty) l.dueAt = minOf(l.dueAt, clock.nowMs() + SAVE_MS)
        saveIndex()
        rowPushed(l, p.doc.length)
        host.onAccount()
        host.onMesses()
    }

    private fun pushFail(p: Push, err: CloudErr?) {
        val l = p.l
        val now = clock.nowMs()
        val code = err?.code ?: ""
        l.busy = false; l.pushing = false
        when (code) {
            "net" -> { online = false; l.err = M_OFFLINE; l.retryAt = now + RETRY[minOf(l.tries, RETRY.size - 1)]; l.tries++ }
            "http" -> { l.err = M_OFFLINE; l.retryAt = now + (if (l.tries < RETRY.size) RETRY[l.tries] else 60000L); l.tries++ }
            "auth" -> { l.err = M_OFFLINE; l.retryAt = now + 30000L }
            "rate_limited" -> l.retryAt = now + 10000L
            "terms" -> { state = TERMS; l.retryAt = now + 1000L; fetchMe {}; host.onAccount() }
            "blocked" -> l.err = M_BLOCKED
            "not_found" -> { val id = l.cloud; unlink(l); dropRow(id); saveList() }
            "too_many_messes" -> { l.err = M_REFUSED; host.notice(N_FULL, "You have " + (me?.mess_limit ?: 200) + " messes online. Delete one to save another.") }
            "docs_full" -> { l.err = M_REFUSED; host.notice(N_FULL, "Your online space for messes is full. Delete one to save another.") }
            "too_big" -> { l.err = M_REFUSED; host.notice(N_FULL, "This mess is too big to save online.") }
            "bad_name" -> { l.err = M_REFUSED; host.notice(N_FULL, "That name cannot be used online. Rename the mess and save again.") }
            else -> l.err = M_REFUSED
        }
        saveIndex()
        host.onMesses()
    }

    /* ---------- clash: keep both ---------- */

    private fun clash(p: Push) {
        val l = p.l
        api.openMess(l.cloud) { o, err ->
            if (live(p)) {
                if (o == null) pushFail(p, err)
                else if (o.doc_rev < o.rev) updateNeeded(p)
                else {
                    val how = settle(l, o, p.doc)
                    if (how == 1 && p.clashes == 0) { p.clashes++; putStep(p) }      // the server held my own lost save: send the newer doc on top
                    else if (how == 2) { saveIndex(); host.onMesses(); afterOpen(l) {}; pump(clock.nowMs()) }
                    else { docDone(p); saveIndex(); renameStep(p) }
                }
            }
        }
    }

    /**
     * My doc against a newer server doc. 0: they are the same mess (rev taken, nothing else to do).
     * 1: the server holds an older save of this phone whose answer was lost (rev taken, my doc must still go).
     * 2: someone else's change: kept both, as rule 3 says. The copy is linked and goes up as a new mess.
     */
    private fun settle(l: Link, o: Opened, mine: String): Int {
        val server = sig(o.doc)
        if (server == sig(mine)) { l.rev = o.rev; l.maybe.clear(); setSounds(l, o.sounds); return 0 }
        if (l.maybe.contains(server)) { l.rev = o.rev; l.maybe.clear(); setSounds(l, o.sounds); return 1 }
        val name = if (l.name.isNotEmpty()) l.name else o.name
        val copyName = copyName(name)
        val now = clock.nowMs()
        val copy = host.keepBoth(l.local, copyName, o.name, o.doc)
        l.job++
        l.rev = o.rev; l.dirty = false; l.name = o.name; l.sname = o.name; l.role = o.role; l.maybe.clear()
        l.busy = false; l.pushing = false; l.tries = 0; l.retryAt = 0L; l.err = 0; l.firstAt = 0L
        setSounds(l, o.sounds)
        if (copy.isNotEmpty() && find(copy) == null) {
            val c = Link(copy)
            c.cloud = api.newId(); c.name = copyName; c.dirty = true; c.ver = 1; c.firstAt = now; c.dueAt = now; c.seq = seq
            links.add(c)
        }
        saveIndex()
        host.notice(N_KEPT_BOTH, o.name + " was changed on another phone. Yours is kept as " + copyName + ".")
        return 2
    }

    private fun copyName(name: String): String {
        val tail = " (this phone)"
        val n = if (name.length + tail.length > 48) name.substring(0, 48 - tail.length).trim() else name
        return n + tail
    }

    /** The cloud mess is being edited by a newer app (it has ops). This phone's mess goes up as its own new mess instead. */
    private fun updateNeeded(p: Push) {
        val l = p.l
        if (l.cloud.isNotEmpty()) cache.save(l.cloud + ".sounds.json", null)
        l.job++
        l.cloud = api.newId(); l.rev = -1L; l.tried = false; l.maybe.clear(); l.list = null; l.seq = seq
        l.name = copyName(if (l.name.isNotEmpty()) l.name else l.sname); l.sname = ""
        l.busy = false; l.pushing = false; l.dirty = true; l.dueAt = clock.nowMs()
        saveIndex()
        host.notice(N_UPDATE, UPDATE_TEXT)
        host.onMesses()
    }

    /** A doc's hash with its keys in order, so the server's own spelling of the same JSON compares equal. */
    private fun sig(doc: String): String {
        return try {
            val sb = StringBuilder()
            canon(sb, Json.parse(doc))
            Sha256.hex(sb.toString().encodeToByteArray())
        } catch (e: Exception) { "raw:" + Sha256.hex(doc.encodeToByteArray()) }
    }

    private fun canon(sb: StringBuilder, v: Any?) {
        if (v is Map<*, *>) {
            val keys = ArrayList<String>()
            for (k in v.keys) keys.add(k.toString())
            keys.sort()
            sb.append('{')
            for (k in keys) { sb.append(Json.write(k)).append(':'); canon(sb, v[k]); sb.append(',') }
            sb.append('}')
        } else if (v is List<*>) {
            sb.append('[')
            for (x in v) { canon(sb, x); sb.append(',') }
            sb.append(']')
        } else sb.append(Json.write(v))
    }

    /* ---------- pull ---------- */

    /** For a linked row with row.rev > link.rev: not dirty, openMess then takeServer; dirty, push (which clashes and keeps both). */
    private fun pull(refreshSeq: Int) {
        val now = clock.nowMs()
        val byId = HashMap<String, MessRow>()
        for (r in rows) byId[r.id] = r
        var changed = false
        for (l in ArrayList(links)) {
            if (l.off || !l.made() || l.del) continue
            val row = byId[l.cloud]
            if (row == null) {
                // deleted from another phone, or another account is signed in now: open_mess says not_found and unlinks it.
                // A mess made after this list was asked for is left alone
                if (l.seq < refreshSeq && !l.busy) pullOne(l)
                continue
            }
            l.role = row.role
            if (row.blocked) l.err = M_BLOCKED else if (l.err == M_BLOCKED) l.err = 0
            if (row.rev > l.rev) {
                if (l.dirty) { l.dueAt = now; l.retryAt = 0L }
                else if (!l.busy) pullOne(l)
            } else if (!l.busy && !l.dirty && row.name != l.sname && l.name == l.sname) {
                val d = host.localDoc(l.local)                 // only the name changed: the server's name wins
                l.name = row.name; l.sname = row.name
                changed = true
                if (d != null) host.takeServer(l.local, row.name, d)
            } else if (!l.busy && missingOf(l) > 0) pullOne(l)   // a sound that was still arriving may be ready now
        }
        if (changed) saveIndex()
        pump(now)
    }

    private fun pullOne(l: Link) {
        l.busy = true
        val g = gen; val j = ++l.job
        api.openMess(l.cloud) { o, err ->
            if (g == gen && j == l.job) {
                l.busy = false
                if (o == null) {
                    val code = err?.code ?: ""
                    if (code == "net") online = false
                    if (code == "not_found") { val id = l.cloud; unlink(l); dropRow(id); saveIndex(); saveList(); host.onMesses() }
                } else if (o.doc_rev < o.rev) {
                    if (updSaid.add(l.cloud)) host.notice(N_UPDATE, UPDATE_TEXT)
                } else {
                    online = true
                    if (l.dirty) { l.dueAt = clock.nowMs() } else take(l, o)
                    saveIndex()
                    host.onMesses()
                    afterOpen(l) {}
                }
            }
        }
    }

    /** Nothing local is unsent: the server's doc and name replace the phone's when they are newer. */
    private fun take(l: Link, o: Opened) {
        if (!l.dirty && (o.rev > l.rev || o.name != l.sname)) {
            host.takeServer(l.local, o.name, o.doc)
            l.rev = o.rev; l.name = o.name; l.sname = o.name
        }
        l.role = o.role
        setSounds(l, o.sounds)
    }

    /* ---------- sounds coming down (rule 6) ---------- */

    private fun arrived(s: Sound): Boolean {
        if (have.contains(s.key + "|" + s.sha256)) return true
        val k = known[s.key]
        return k != null && k.here && k.sha == s.sha256
    }

    /**
     * After an open: a `gone` sound is dropped (cache file and Library entry) before anything plays; the others
     * are looked up in the cache; then `then` runs; then, when dataOk (or "Get sounds now"), the rest download.
     */
    private fun afterOpen(l: Link, then: () -> Unit) {
        val list = ArrayList(loadSounds(l))
        var touched = false
        for (s in list) {
            val k = known[s.key]
            if (s.src == "gone") {
                api.dropSound(s.sha256)
                have.remove(s.key + "|" + s.sha256)
                if (k != null && k.sha == s.sha256) { known.remove(s.key); touched = true }
                host.soundGone(s)
            } else if (s.sha256.isNotEmpty() && (k == null || k.sha != s.sha256 || k.owner != s.owner)) {
                known[s.key] = Known(s.sha256, s.owner, false)
                touched = true
            }
        }
        if (touched) saveKnown()
        val want = ArrayList<Sound>()
        for (s in list) if (s.src != "gone" && s.state == "ready" && !arrived(s)) want.add(s)
        lookup(l, want, 0, ArrayList(), gen, then)
    }

    private fun lookup(l: Link, want: List<Sound>, i: Int, need: ArrayList<Sound>, g: Int, then: () -> Unit) {
        if (g != gen) return
        if (i >= want.size) {
            then()
            if (need.isNotEmpty() && (dataOk || l.force) && l.made()) fetch(l, need) else l.force = false
            return
        }
        val s = want[i]
        if (arrived(s)) { lookup(l, want, i + 1, need, g, then); return }
        api.cachedSound(s.sha256) { path, _ ->
            if (g == gen) {
                if (path != null) { have.add(s.key + "|" + s.sha256); host.soundArrived(s, path) } else need.add(s)
                lookup(l, want, i + 1, need, g, then)
            }
        }
    }

    private fun fetch(l: Link, need: List<Sound>) {
        if (l.fetching) { l.again = true; return }
        l.fetching = true
        val g = gen
        val cloud = l.cloud
        api.soundUrls(cloud, "") { urls, err ->
            if (g == gen && l.cloud == cloud) {
                if (urls == null) { l.fetching = false; l.force = false; if (err != null && err.code == "net") online = false }
                else {
                    val by = HashMap<String, SoundUrl>()
                    for (u in urls) by[u.sha256] = u
                    fetchOne(l, need, 0, by, g)
                }
            }
        }
    }

    private fun fetchOne(l: Link, need: List<Sound>, i: Int, by: Map<String, SoundUrl>, g: Int) {
        if (g != gen) return
        if (i >= need.size) {
            l.fetching = false; l.force = false
            host.onMesses()
            if (l.again) { l.again = false; afterOpen(l) {} }
            return
        }
        val s = need[i]
        val u = by[s.sha256]
        if (u == null || arrived(s)) { fetchOne(l, need, i + 1, by, g); return }
        api.fetchSound(u.url, s.sha256, u.bytes) { path, err ->
            if (g == gen) {
                if (path != null) { have.add(s.key + "|" + s.sha256); host.soundArrived(s, path); fetchOne(l, need, i + 1, by, g) }
                else if (err != null && err.code == "net") { online = false; l.fetching = false; l.force = false; host.onMesses() }
                else fetchOne(l, need, i + 1, by, g)       // bad_hash or a dead link: it stays missing
            }
        }
    }
}
