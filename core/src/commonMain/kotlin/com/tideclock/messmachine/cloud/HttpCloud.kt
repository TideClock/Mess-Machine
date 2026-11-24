package com.tideclock.messmachine.cloud

import com.tideclock.messmachine.engine.Json
import kotlin.jvm.JvmField

/** One request. method: GET, POST or DELETE. body: UTF-8 JSON, or null for none. */
class HttpReq(@JvmField val method: String, @JvmField val url: String,
              @JvmField val headers: Map<String, String>, @JvmField val body: String?)

/** One answer. status 0: no answer at all. */
class HttpRes(@JvmField val status: Int, @JvmField val body: String)

/**
 * Every sign-in, RPC and Edge Function call of CloudApi (all slices) on top of two platform methods, so three
 * platforms do not each write fifty calls: URLs, bodies, parsing, the error envelopes of
 * docs/ACCOUNTS-API.md s1.1 and s1.2, and one refresh and retry on a 401.
 *
 * A platform extends it and writes only `http`, `sendFile` and the CloudApi methods newId, prepareUpload,
 * cachedSound, fetchSound, dropSound (and from Slice C join, track, leave).
 *
 * Headers: `apikey: <publishable key>` on every request. `Authorization: Bearer <access token>` only when
 * signed in; the publishable key never goes in Authorization. `Content-Type: application/json` with a JSON body.
 *
 * Refresh: a 401 on a signed-in call refreshes once and sends the call again. When the platform overrides
 * nowMs(), a call made within EARLY_MS of the token's end refreshes first. Only one refresh runs at a time
 * (refresh tokens are single use); calls that need one while it runs wait for it. Every session the api
 * replaces by itself is reported to the SessionListener: the new one (save it), or null when it is dead.
 */
abstract class HttpCloud(url: String, key: String) : CloudApi {
    companion object {
        /** A call this close to the token's end refreshes first (only when nowMs() is overridden). */
        const val EARLY_MS = 30000L
    }

    /** The project URL with no slash at the end, and the publishable key. */
    @JvmField protected val base: String = trimUrl(url)
    @JvmField protected val apiKey: String = key.trim()
    /** The session every call carries, or null when signed out. */
    @JvmField protected var session: Session? = null
    private var listener: SessionListener? = null
    private var refreshing = false
    private val waiters = ArrayList<Done<Session>>()

    /** RPC queue. Always answers a value (status 0 for no answer), never an err. */
    protected abstract fun http(r: HttpReq, cb: Done<HttpRes>)
    /** File queue, fixed length body: the file at localFile, `bytes` long. Answers like http. */
    protected abstract fun sendFile(r: HttpReq, localFile: String, bytes: Long, cb: Done<HttpRes>)
    /** The time, for refreshing just before the token ends. 0 (the default): unknown, refresh only on a 401. */
    protected open fun nowMs(): Long = 0L

    override fun configured(): Boolean = base.isNotEmpty() && apiKey.isNotEmpty()
    override fun setSession(s: Session?) { session = s }
    override fun setSessionListener(l: SessionListener?) { listener = l }

    /* ---------- sign-in (s1.2) ---------- */

    override fun requestCode(email: String, cb: Done<Boolean>) {
        val b = Body().s("email", email).b("create_user", true)
        http(HttpReq("POST", "$base/auth/v1/otp", headers(null, true), b.end())) { r, _ ->
            val res = r ?: HttpRes(0, "")
            when {
                res.status == 0 -> cb.done(null, CloudErr("net", 0, ""))
                ok(res.status) -> cb.done(true, null)
                res.status == 429 -> cb.done(null, CloudErr("code_wait", 429, short(res.body)))
                else -> cb.done(null, CloudErr("http", res.status, short(res.body)))
            }
        }
    }

    override fun verifyCode(email: String, code: String, cb: Done<Session>) {
        val b = Body().s("type", "email").s("email", email).s("token", code)
        http(HttpReq("POST", "$base/auth/v1/verify", headers(null, true), b.end())) { r, _ ->
            val res = r ?: HttpRes(0, "")
            when {
                res.status == 0 -> cb.done(null, CloudErr("net", 0, ""))
                ok(res.status) -> sessionAnswer(res, cb)
                res.status == 429 -> cb.done(null, CloudErr("code_wait", 429, short(res.body)))
                res.status in 400..499 -> cb.done(null, CloudErr("code_wrong", res.status, short(res.body)))
                else -> cb.done(null, CloudErr("http", res.status, short(res.body)))
            }
        }
    }

    override fun signInPassword(email: String, password: String, cb: Done<Session>) {
        val b = Body().s("email", email).s("password", password)
        http(HttpReq("POST", "$base/auth/v1/token?grant_type=password", headers(null, true), b.end())) { r, _ ->
            val res = r ?: HttpRes(0, "")
            when {
                res.status == 0 -> cb.done(null, CloudErr("net", 0, ""))
                ok(res.status) -> sessionAnswer(res, cb)
                res.status == 400 -> cb.done(null, CloudErr("code_wrong", 400, short(res.body)))
                res.status == 429 -> cb.done(null, CloudErr("code_wait", 429, short(res.body)))
                else -> cb.done(null, CloudErr("http", res.status, short(res.body)))
            }
        }
    }

    /** With the current session's own refresh token this also replaces the session here and tells the listener. */
    override fun refresh(refreshToken: String, cb: Done<Session>) {
        val s = session
        if (s != null && s.refresh == refreshToken) refreshShared(cb) else refreshCall(refreshToken, cb)
    }

    override fun signOut(cb: Done<Boolean>) {
        val s = session
        http(HttpReq("POST", "$base/auth/v1/logout?scope=local", headers(s?.access, false), null)) { _, _ -> cb.done(true, null) }
    }

    private fun refreshCall(refreshToken: String, cb: Done<Session>) {
        val b = Body().s("refresh_token", refreshToken)
        http(HttpReq("POST", "$base/auth/v1/token?grant_type=refresh_token", headers(null, true), b.end())) { r, _ ->
            val res = r ?: HttpRes(0, "")
            when {
                res.status == 0 -> cb.done(null, CloudErr("net", 0, ""))
                ok(res.status) -> sessionAnswer(res, cb)
                res.status == 400 || res.status == 401 || res.status == 403 -> cb.done(null, CloudErr("auth", res.status, short(res.body)))
                else -> cb.done(null, CloudErr("http", res.status, short(res.body)))
            }
        }
    }

    private fun sessionAnswer(res: HttpRes, cb: Done<Session>) {
        val j = parse(res.body)
        val s = if (j is Map<*, *>) Wire.session(j) else null
        if (s == null || s.access.isEmpty()) cb.done(null, CloudErr("http", res.status, short(res.body))) else cb.done(s, null)
    }

    /** One refresh at a time for the current session; everyone who asks while it runs gets the same answer. */
    private fun refreshShared(cb: Done<Session>) {
        val s = session
        if (s == null) { cb.done(null, CloudErr("auth", 401, "")); return }   // not reached: every caller checks first
        waiters.add(cb)
        if (refreshing) return
        refreshing = true
        refreshCall(s.refresh) { fresh, err ->
            refreshing = false
            val w = ArrayList(waiters); waiters.clear()
            if (fresh != null) {
                if (session != null) { session = fresh; listener?.onSession(fresh) }   // signed out meanwhile: stay out
            } else if (err != null && err.code == "auth") {
                if (session != null) { session = null; listener?.onSession(null) }
            }
            for (x in w) x.done(fresh, err)
        }
    }

    /* ---------- the envelope ---------- */

    private fun trimUrl(u: String): String { var x = u.trim(); while (x.endsWith("/")) x = x.substring(0, x.length - 1); return x }
    private fun ok(status: Int): Boolean = status in 200..299
    private fun short(body: String): String = if (body.length > 200) body.substring(0, 200) else body
    private fun parse(body: String): Any? = try { if (body.isBlank()) null else Json.parse(body) } catch (e: Exception) { BAD }
    private val BAD = Any()

    /** apikey always; the bearer token only when there is one; the content type only with a JSON body. */
    private fun headers(access: String?, json: Boolean): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["apikey"] = apiKey
        if (access != null && access.isNotEmpty()) h["Authorization"] = "Bearer $access"
        if (json) h["Content-Type"] = "application/json"
        return h
    }

    private fun early(s: Session): Boolean {
        val now = nowMs()
        return now > 0L && s.expiresAt > 0L && s.expiresAt * 1000L - now < EARLY_MS
    }

    /**
     * Sends one signed request (when there is a session) and hands back the raw answer, after the one
     * refresh and retry the contract allows. `send` builds and sends the request for a given access token.
     */
    private fun signed(send: (String?, Done<HttpRes>) -> Unit, cb: Done<HttpRes>) {
        val s = session
        if (s != null && early(s)) {
            refreshShared { fresh, err ->
                if (fresh != null) attempt(send, fresh.access, true, cb)
                else if (err != null && err.code == "net") cb.done(HttpRes(0, ""), null)
                else if (err != null && err.code == "auth") cb.done(HttpRes(401, ""), null)
                else attempt(send, s.access, false, cb)   // the refresh hit a server fault: the old token may still do
            }
            return
        }
        attempt(send, s?.access, false, cb)
    }

    private fun attempt(send: (String?, Done<HttpRes>) -> Unit, access: String?, retried: Boolean, cb: Done<HttpRes>) {
        val answered = Done<HttpRes> { r, _ ->
            val res = r ?: HttpRes(0, "")
            val now = session
            if (res.status != 401 || retried || access == null || now == null) cb.done(res, null)
            else if (now.access != access) attempt(send, now.access, true, cb)   // someone else already refreshed
            else refreshShared { fresh, err ->
                if (fresh != null) attempt(send, fresh.access, true, cb)
                else if (err != null && err.code == "net") cb.done(HttpRes(0, ""), null)
                else cb.done(res, null)
            }
        }
        send(access, answered)
    }

    /** An RPC answer as a value or a CloudErr, by the RPC envelope of s1.1. */
    private fun <T> rpcAnswer(res: HttpRes, cb: Done<T>, read: (Any?) -> T) {
        if (res.status == 0) { cb.done(null, CloudErr("net", 0, "")); return }
        val j = parse(res.body)
        if (ok(res.status)) {
            if (j === BAD) { cb.done(null, CloudErr("http", res.status, short(res.body))); return }
            val v: T = try { read(j) } catch (e: Exception) { cb.done(null, CloudErr("http", res.status, short(res.body))); return }
            cb.done(v, null)
            return
        }
        val m = if (j is Map<*, *>) Json.map(j) else LinkedHashMap()
        val code = Json.str(m["code"], "")
        val msg = Json.str(m["message"], "")
        when {
            res.status == 401 -> cb.done(null, CloudErr("auth", 401, short(res.body)))
            res.status == 403 || code == "42501" -> cb.done(null, CloudErr("auth", res.status, short(res.body)))
            code == "P0001" && msg.isNotEmpty() -> cb.done(null, CloudErr(msg, res.status, ""))
            else -> cb.done(null, CloudErr("http", res.status, short(res.body)))
        }
    }

    /** `POST <url>/rest/v1/rpc/<name>` with a JSON object of the named arguments. */
    protected fun <T> rpc(name: String, body: Body, cb: Done<T>, read: (Any?) -> T) {
        val text = body.end()
        signed({ access, done -> http(HttpReq("POST", "$base/rest/v1/rpc/$name", headers(access, true), text), done) }) { res, _ ->
            rpcAnswer(res ?: HttpRes(0, ""), cb, read)
        }
    }

    /** `POST <url>/functions/v1/<name>`: errors are HTTP 400 (401 for auth) and {"error": "<code>"}. */
    protected fun <T> fn(name: String, body: Body, cb: Done<T>, read: (Any?) -> T) {
        val text = body.end()
        signed({ access, done -> http(HttpReq("POST", "$base/functions/v1/$name", headers(access, true), text), done) }) { r, _ ->
            fnAnswer(r ?: HttpRes(0, ""), cb, read)
        }
    }

    private fun <T> fnAnswer(res: HttpRes, cb: Done<T>, read: (Any?) -> T) {
        if (res.status == 0) { cb.done(null, CloudErr("net", 0, "")); return }
        val j = parse(res.body)
        if (ok(res.status)) {
            if (j === BAD) { cb.done(null, CloudErr("http", res.status, short(res.body))); return }
            val v: T = try { read(j) } catch (e: Exception) { cb.done(null, CloudErr("http", res.status, short(res.body))); return }
            cb.done(v, null)
            return
        }
        val e = if (j is Map<*, *>) Json.map(j)["error"] else null
        when {
            e is String && e.isNotEmpty() -> cb.done(null, CloudErr(e, res.status, ""))
            res.status == 401 -> cb.done(null, CloudErr("auth", 401, short(res.body)))
            else -> cb.done(null, CloudErr("http", res.status, short(res.body)))
        }
    }

    /** Builds a JSON object body in the order the fields are added. raw() takes JSON text as it is (a mess, a list of ops). */
    protected class Body {
        private val sb = StringBuilder("{")
        private fun k(name: String): StringBuilder { if (sb.length > 1) sb.append(','); sb.append(Json.write(name)).append(':'); return sb }
        fun s(name: String, v: String): Body { k(name).append(Json.write(v)); return this }
        /** A uuid or text that may be absent: "" goes as null. */
        fun sn(name: String, v: String): Body { if (v.isEmpty()) k(name).append("null") else k(name).append(Json.write(v)); return this }
        fun n(name: String, v: Long): Body { k(name).append(v); return this }
        fun n(name: String, v: Int): Body { k(name).append(v); return this }
        fun d(name: String, v: Double): Body { k(name).append(Json.write(v)); return this }
        fun b(name: String, v: Boolean): Body { k(name).append(if (v) "true" else "false"); return this }
        fun raw(name: String, json: String): Body { k(name).append(if (json.isBlank()) "null" else json); return this }
        fun end(): String = sb.toString() + "}"
    }

    private val yes: (Any?) -> Boolean = { true }

    /* ---------- Slice A: account and messes ---------- */

    override fun me(cb: Done<Me>) = rpc("me", Body(), cb) { Wire.me(it) }
    override fun setProfile(displayName: String, cb: Done<Me>) = rpc("set_profile", Body().s("p_display_name", displayName), cb) { Wire.me(it) }
    override fun acceptTerms(version: String, cb: Done<Boolean>) = rpc("accept_terms", Body().s("p_version", version), cb, yes)
    override fun createMess(id: String, name: String, doc: String, cb: Done<Rev>) =
        rpc("create_mess", Body().s("p_id", id).s("p_name", name).raw("p_doc", doc), cb) { val r = Wire.rev(it); Rev(if (r.id.isEmpty()) id else r.id, r.rev) }
    override fun myMesses(cb: Done<List<MessRow>>) = rpc("my_messes", Body(), cb) { Wire.list(it) { x -> Wire.messRow(x) } }
    override fun openMess(messId: String, cb: Done<Opened>) = rpc("open_mess", Body().s("p_mess", messId), cb) { Wire.opened(it) }
    override fun putDoc(messId: String, doc: String, baseRev: Long, label: String, cb: Done<Saved>) =
        rpc("put_doc", Body().s("p_mess", messId).raw("p_doc", doc).n("p_base_rev", baseRev).s("p_label", label), cb) { Wire.saved(it) }
    override fun renameMess(messId: String, name: String, cb: Done<Rev>) =
        rpc("rename_mess", Body().s("p_mess", messId).s("p_name", name), cb) { Rev(messId, Wire.rev(it).rev) }
    override fun deleteMess(messId: String, cb: Done<Boolean>) = rpc("delete_mess", Body().s("p_mess", messId), cb, yes)

    /* ---------- Slice A: sounds ---------- */

    override fun registerSound(p: Prepared, kind: String, localKey: String, title: String, bpm: Double, beats: Int, mkey: String, cb: Done<Registered>) =
        rpc("register_sound", Body().s("p_sha256", p.sha256).n("p_bytes", p.bytes).d("p_seconds", p.seconds).n("p_rate", p.rate)
            .s("p_kind", kind).s("p_local_key", localKey).s("p_title", title).d("p_bpm", bpm).n("p_beats", beats).s("p_mkey", mkey), cb) { Wire.registered(it) }

    /** `POST <url>/storage/v1/object/sounds/<path>`, audio/wav, x-upsert false. "Already exists" counts as done. */
    override fun upload(path: String, localFile: String, bytes: Long, cb: Done<Boolean>) {
        signed({ access, done ->
            val h = LinkedHashMap<String, String>()
            h["apikey"] = apiKey
            if (access != null && access.isNotEmpty()) h["Authorization"] = "Bearer $access"
            h["Content-Type"] = "audio/wav"
            h["x-upsert"] = "false"
            sendFile(HttpReq("POST", "$base/storage/v1/object/sounds/$path", h, null), localFile, bytes, done)
        }) { r, _ ->
            val res = r ?: HttpRes(0, "")
            val dup = (res.status == 400 || res.status == 409) && (res.body.contains("Duplicate") || res.body.contains("already exists"))
            when {
                res.status == 0 -> cb.done(null, CloudErr("net", 0, ""))
                ok(res.status) || dup -> cb.done(true, null)
                res.status == 401 || res.status == 403 -> cb.done(null, CloudErr("auth", res.status, short(res.body)))
                res.status == 413 -> cb.done(null, CloudErr("too_big", 413, short(res.body)))
                else -> cb.done(null, CloudErr("http", res.status, short(res.body)))
            }
        }
    }

    override fun finishUpload(sha256: String, cb: Done<Used>) = rpc("finish_upload", Body().s("p_sha256", sha256), cb) { Wire.used(it) }
    override fun mySounds(cb: Done<List<MySound>>) = rpc("my_sounds", Body(), cb) { Wire.list(it) { x -> Wire.mySound(x) } }
    override fun attachSound(messId: String, key: String, owner: String, sha256: String, cb: Done<Boolean>) =
        rpc("attach_sound", Body().s("p_mess", messId).s("p_key", key).s("p_owner", owner).s("p_sha256", sha256), cb, yes)
    override fun deleteSound(sha256: String, cb: Done<Deleted>) = rpc("delete_sound", Body().s("p_sha256", sha256), cb) { Wire.deleted(it) }

    /** `DELETE <url>/storage/v1/object/sounds/<path>`. 200 and 404 both count as gone. */
    override fun removeObject(path: String, cb: Done<Boolean>) {
        signed({ access, done -> http(HttpReq("DELETE", "$base/storage/v1/object/sounds/$path", headers(access, false), null), done) }) { r, _ ->
            val res = r ?: HttpRes(0, "")
            when {
                res.status == 0 -> cb.done(null, CloudErr("net", 0, ""))
                ok(res.status) || res.status == 404 -> cb.done(true, null)
                res.status == 401 || res.status == 403 -> cb.done(null, CloudErr("auth", res.status, short(res.body)))
                else -> cb.done(null, CloudErr("http", res.status, short(res.body)))
            }
        }
    }

    /** Edge Function sound-urls with exactly one of {"mess"} and {"token"}. */
    override fun soundUrls(messId: String, token: String, cb: Done<List<SoundUrl>>) {
        val b = if (messId.isNotEmpty()) Body().s("mess", messId) else Body().s("token", token)
        fn("sound-urls", b, cb) { Wire.list(Json.map(it)["sounds"]) { x -> Wire.soundUrl(x) } }
    }

    /* ---------- Slice B: ops and people ---------- */

    override fun opsSince(messId: String, from: Long, cb: Done<Since>) =
        rpc("ops_since", Body().s("p_mess", messId).n("p_from", from), cb) { Wire.since(it) }
    override fun applyOps(messId: String, batchId: String, opsJson: String, v: Int, cb: Done<Applied>) =
        rpc("apply_ops", Body().s("p_mess", messId).s("p_batch", batchId).raw("p_ops", opsJson).n("p_v", v), cb) { Wire.applied(it) }
    override fun saveSnapshot(messId: String, doc: String, docRev: Long, v: Int, label: String, cb: Done<Snapped>) =
        rpc("save_snapshot", Body().s("p_mess", messId).raw("p_doc", doc).n("p_doc_rev", docRev).n("p_v", v).s("p_label", label), cb) { Wire.snapped(it) }
    override fun members(messId: String, cb: Done<List<Member>>) = rpc("members", Body().s("p_mess", messId), cb) { Wire.list(it) { x -> Wire.member(x) } }
    override fun makeInvite(messId: String, days: Int, cb: Done<Invite>) =
        rpc("make_invite", Body().s("p_mess", messId).n("p_days", days), cb) { Wire.invite(it) }
    override fun listInvites(messId: String, cb: Done<List<InviteRow>>) =
        rpc("list_invites", Body().s("p_mess", messId), cb) { Wire.list(it) { x -> Wire.inviteRow(x) } }
    override fun revokeInvite(inviteId: String, cb: Done<Boolean>) = rpc("revoke_invite", Body().s("p_invite", inviteId), cb, yes)
    override fun redeemInvite(token: String, cb: Done<Joined>) = rpc("redeem_invite", Body().s("p_token", token), cb) { Wire.joined(it) }
    override fun inviteInfo(token: String, cb: Done<InviteInfo>) = rpc("invite_info", Body().s("p_token", token), cb) { Wire.inviteInfo(it) }
    override fun removeMember(messId: String, userId: String, block: Boolean, cb: Done<Boolean>) =
        rpc("remove_member", Body().s("p_mess", messId).s("p_user", userId).b("p_block", block), cb, yes)
    override fun leaveMess(messId: String, cb: Done<Boolean>) = rpc("leave_mess", Body().s("p_mess", messId), cb, yes)
    override fun blockUser(userId: String, cb: Done<Boolean>) = rpc("block_user", Body().s("p_user", userId), cb, yes)
    override fun myBlocks(cb: Done<List<Blocked>>) = rpc("my_blocks", Body(), cb) { Wire.list(it) { x -> Wire.blocked(x) } }
    override fun unblockUser(userId: String, cb: Done<Boolean>) = rpc("unblock_user", Body().s("p_user", userId), cb, yes)
    override fun joinLive(messId: String, cb: Done<Live>) = rpc("join_live", Body().s("p_mess", messId), cb) { Wire.live(it) }
    override fun leaveLive(messId: String, cb: Done<Boolean>) = rpc("leave_live", Body().s("p_mess", messId), cb, yes)

    /* ---------- Slice D ---------- */

    override fun setShare(messId: String, mode: String, fresh: Boolean, cb: Done<Share>) =
        rpc("set_share", Body().s("p_mess", messId).s("p_mode", mode).b("p_new", fresh), cb) { Wire.share(it) }
    override fun openShared(token: String, cb: Done<Shared>) = rpc("open_shared", Body().s("p_token", token), cb) { Wire.shared(it) }
    override fun forkMess(id: String, from: String, token: String, name: String, doc: String, cb: Done<Rev>) =
        rpc("fork_mess", Body().s("p_id", id).sn("p_from", from).sn("p_token", token).s("p_name", name).raw("p_doc", doc), cb) {
            val r = Wire.rev(it); Rev(if (r.id.isEmpty()) id else r.id, r.rev)
        }
    override fun versions(messId: String, cb: Done<List<VersionRow>>) =
        rpc("versions", Body().s("p_mess", messId), cb) { Wire.list(it) { x -> Wire.versionRow(x) } }
    override fun getVersion(versionId: String, cb: Done<Version>) = rpc("get_version", Body().s("p_version", versionId), cb) { Wire.version(it) }
    override fun restoreVersion(messId: String, versionId: String, cb: Done<Rev>) =
        rpc("restore_version", Body().s("p_mess", messId).s("p_version", versionId), cb) { Rev(messId, Wire.rev(it).rev) }

    /* ---------- Slice E and F ---------- */

    override fun reportMess(messId: String, token: String, reason: String, details: String, aboutUser: String, cb: Done<Boolean>) =
        rpc("report_mess", Body().sn("p_mess", messId).sn("p_token", token).s("p_reason", reason).s("p_details", details).sn("p_user", aboutUser), cb, yes)
    override fun deleteAccount(cb: Done<Boolean>) = fn("delete-account", Body(), cb, yes)
    override fun vaultGrant(cb: Done<Grant>) = rpc("vault_grant", Body(), cb) { Wire.grant(it) }
    override fun vaultSubmit(sha256: String, creditName: String, grantVersion: String, note: String, cb: Done<Boolean>) =
        rpc("vault_submit", Body().s("p_sha256", sha256).s("p_credit_name", creditName).s("p_grant_version", grantVersion).s("p_note", note), cb, yes)
}
