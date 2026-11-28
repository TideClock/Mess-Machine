package com.tideclock.messmachine.cloud

import com.tideclock.messmachine.engine.Json
import kotlin.jvm.JvmStatic

/**
 * One parser per class of Shapes.kt, built on the engine's Json. Each takes a parsed JSON value (a Map from
 * Json.parse) and ignores unknown fields. A missing or null text is "", number 0, bool false, list empty.
 * The few writers at the bottom are for what the phone keeps on disk (the session, list.json, the sounds lists).
 */
object Wire {
    private fun s(m: Map<String, Any?>, k: String): String = Json.str(m[k], "")
    private fun l(m: Map<String, Any?>, k: String): Long = Json.num(m[k], 0.0).toLong()
    private fun i(m: Map<String, Any?>, k: String): Int = Json.num(m[k], 0.0).toInt()
    private fun d(m: Map<String, Any?>, k: String): Double = Json.num(m[k], 0.0)
    private fun b(m: Map<String, Any?>, k: String): Boolean = Json.bool(m[k], false)
    /** A whole mess held as JSON text; "" when it is missing or not an object. */
    private fun doc(m: Map<String, Any?>, k: String): String { val o = m[k]; return if (o is Map<*, *>) Json.write(o) else "" }

    /** Parses a JSON array with one of the parsers below: `Wire.list(j) { Wire.messRow(it) }`. */
    @JvmStatic fun <T> list(j: Any?, one: (Any?) -> T): List<T> {
        val out = ArrayList<T>()
        for (o in Json.list(j)) out.add(one(o))
        return out
    }

    /** SessionJson of s1.2 (and the form sessionJson below writes): access_token, refresh_token, expires_at, user. */
    @JvmStatic fun session(j: Any?): Session {
        val m = Json.map(j); val u = Json.map(m["user"])
        return Session(s(m, "access_token"), s(m, "refresh_token"), l(m, "expires_at"), s(u, "id"), s(u, "email"))
    }
    @JvmStatic fun me(j: Any?): Me {
        val m = Json.map(j)
        return Me(s(m, "id"), s(m, "display_name"), b(m, "name_set"), i(m, "colour"), l(m, "sound_bytes"), l(m, "sound_limit"),
            i(m, "messes"), i(m, "mess_limit"), b(m, "terms_ok"), s(m, "terms_version"), b(m, "is_admin"))
    }
    @JvmStatic fun messRow(j: Any?): MessRow {
        val m = Json.map(j)
        return MessRow(s(m, "id"), s(m, "name"), s(m, "role"), s(m, "owner_name"), l(m, "rev"), s(m, "updated_at"),
            i(m, "members"), s(m, "share_mode"), i(m, "doc_bytes"), b(m, "blocked"))
    }
    @JvmStatic fun batch(j: Any?): Batch {
        val m = Json.map(j); val o = m["ops"]
        return Batch(l(m, "rev"), s(m, "user"), s(m, "batch"), i(m, "v"), if (o is List<*>) Json.list(o) else null, s(m, "at"))
    }
    @JvmStatic fun member(j: Any?): Member {
        val m = Json.map(j)
        return Member(s(m, "id"), s(m, "name"), i(m, "colour"), s(m, "role"))
    }
    @JvmStatic fun sound(j: Any?): Sound {
        val m = Json.map(j)
        return Sound(s(m, "key"), s(m, "sha256"), s(m, "src"), s(m, "owner"), s(m, "fmt"), l(m, "bytes"), d(m, "seconds"),
            i(m, "rate"), s(m, "kind"), s(m, "title"), d(m, "bpm"), i(m, "beats"), s(m, "mkey"), s(m, "state"))
    }
    @JvmStatic fun opened(j: Any?): Opened {
        val m = Json.map(j)
        return Opened(s(m, "id"), s(m, "name"), s(m, "role"), s(m, "owner_id"), l(m, "rev"), l(m, "doc_rev"), doc(m, "doc"),
            i(m, "ops_v"), list(m["ops"]) { batch(it) }, list(m["sounds"]) { sound(it) }, list(m["members"]) { member(it) },
            s(m, "share_mode"), s(m, "share_token"), s(m, "topic"))
    }
    @JvmStatic fun rev(j: Any?): Rev { val m = Json.map(j); return Rev(s(m, "id"), l(m, "rev")) }
    @JvmStatic fun saved(j: Any?): Saved { val m = Json.map(j); return Saved(l(m, "rev"), s(m, "version_id")) }
    @JvmStatic fun registered(j: Any?): Registered {
        val m = Json.map(j)
        return Registered(s(m, "state"), s(m, "path"), l(m, "used"), l(m, "limit"))
    }
    @JvmStatic fun used(j: Any?): Used { val m = Json.map(j); return Used(s(m, "state"), l(m, "used")) }
    @JvmStatic fun mySound(j: Any?): MySound {
        val m = Json.map(j)
        return MySound(s(m, "sha256"), s(m, "title"), s(m, "kind"), l(m, "bytes"), d(m, "seconds"), s(m, "state"), i(m, "messes"), s(m, "vault"))
    }
    @JvmStatic fun deleted(j: Any?): Deleted {
        val m = Json.map(j)
        return Deleted(s(m, "state"), s(m, "path"), l(m, "used"), i(m, "messes"))
    }
    @JvmStatic fun soundUrl(j: Any?): SoundUrl {
        val m = Json.map(j)
        return SoundUrl(s(m, "key"), s(m, "sha256"), l(m, "bytes"), s(m, "url"))
    }

    // Slice B
    @JvmStatic fun since(j: Any?): Since {
        val m = Json.map(j)
        return Since(l(m, "rev"), l(m, "doc_rev"), i(m, "members"), s(m, "topic"), list(m["ops"]) { batch(it) })
    }
    @JvmStatic fun applied(j: Any?): Applied { val m = Json.map(j); return Applied(l(m, "rev"), s(m, "at")) }
    @JvmStatic fun snapped(j: Any?): Snapped { val m = Json.map(j); return Snapped(l(m, "doc_rev"), s(m, "version_id")) }
    @JvmStatic fun invite(j: Any?): Invite { val m = Json.map(j); return Invite(s(m, "id"), s(m, "token"), s(m, "expires_at"), (m["code"] as? String) ?: "") }
    @JvmStatic fun inviteRow(j: Any?): InviteRow {
        val m = Json.map(j)
        return InviteRow(s(m, "id"), s(m, "expires_at"), i(m, "uses_left"), b(m, "revoked"))
    }
    @JvmStatic fun inviteInfo(j: Any?): InviteInfo { val m = Json.map(j); return InviteInfo(s(m, "mess_name"), s(m, "owner_name")) }
    @JvmStatic fun joined(j: Any?): Joined { val m = Json.map(j); return Joined(s(m, "mess_id"), s(m, "role")) }
    @JvmStatic fun blocked(j: Any?): Blocked { val m = Json.map(j); return Blocked(s(m, "id"), s(m, "name")) }
    @JvmStatic fun live(j: Any?): Live { val m = Json.map(j); return Live(b(m, "live"), i(m, "editors")) }

    // Slice C, D, F
    /** Clamped as s2 says: page at most 12 chars, loop 0 to 7, pad -1 to 63. */
    @JvmStatic fun presence(j: Any?): Presence {
        val m = Json.map(j)
        var page = s(m, "page"); if (page.length > 12) page = page.substring(0, 12)
        return Presence(s(m, "u"), page, minOf(7, maxOf(0, i(m, "loop"))), minOf(63, maxOf(-1, Json.num(m["pad"], -1.0).toInt())),
            b(m, "playing"), b(m, "rec"))
    }
    @JvmStatic fun share(j: Any?): Share { val m = Json.map(j); return Share(s(m, "share_mode"), s(m, "share_token")) }
    @JvmStatic fun shared(j: Any?): Shared {
        val m = Json.map(j)
        return Shared(s(m, "name"), s(m, "owner_name"), s(m, "mode"), l(m, "rev"), l(m, "doc_rev"), doc(m, "doc"),
            list(m["ops"]) { batch(it) }, list(m["sounds"]) { sound(it) })
    }
    @JvmStatic fun versionRow(j: Any?): VersionRow {
        val m = Json.map(j)
        return VersionRow(s(m, "id"), l(m, "rev"), s(m, "label"), s(m, "by_name"), s(m, "at"), i(m, "bytes"))
    }
    @JvmStatic fun version(j: Any?): Version { val m = Json.map(j); return Version(s(m, "id"), l(m, "rev"), s(m, "label"), doc(m, "doc")) }
    @JvmStatic fun grant(j: Any?): Grant { val m = Json.map(j); return Grant(s(m, "version"), s(m, "body"), s(m, "body_sha256")) }

    /* ---------- writers, for what the phone keeps ---------- */

    /** The session as one string for TokenStore; Wire.session(Json.parse(it)) reads it back. */
    @JvmStatic fun sessionJson(x: Session): String {
        val m = LinkedHashMap<String, Any?>(); val u = LinkedHashMap<String, Any?>()
        m["access_token"] = x.access; m["refresh_token"] = x.refresh; m["expires_at"] = x.expiresAt
        u["id"] = x.userId; u["email"] = x.email; m["user"] = u
        return Json.write(m)
    }
    @JvmStatic fun meMap(x: Me): MutableMap<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["id"] = x.id; m["display_name"] = x.display_name; m["name_set"] = x.name_set; m["colour"] = x.colour
        m["sound_bytes"] = x.sound_bytes; m["sound_limit"] = x.sound_limit; m["messes"] = x.messes; m["mess_limit"] = x.mess_limit
        m["terms_ok"] = x.terms_ok; m["terms_version"] = x.terms_version; m["is_admin"] = x.is_admin
        return m
    }
    @JvmStatic fun messRowMap(x: MessRow): MutableMap<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["id"] = x.id; m["name"] = x.name; m["role"] = x.role; m["owner_name"] = x.owner_name; m["rev"] = x.rev
        m["updated_at"] = x.updated_at; m["members"] = x.members; m["share_mode"] = x.share_mode
        m["doc_bytes"] = x.doc_bytes; m["blocked"] = x.blocked
        return m
    }
    @JvmStatic fun soundMap(x: Sound): MutableMap<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["key"] = x.key; m["sha256"] = x.sha256; m["src"] = x.src; m["owner"] = if (x.owner.isEmpty()) null else x.owner
        m["fmt"] = x.fmt; m["bytes"] = x.bytes; m["seconds"] = x.seconds; m["rate"] = x.rate; m["kind"] = x.kind
        m["title"] = x.title; m["bpm"] = x.bpm; m["beats"] = x.beats; m["mkey"] = x.mkey; m["state"] = x.state
        return m
    }
    @JvmStatic fun memberMap(x: Member): MutableMap<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["id"] = x.id; m["name"] = x.name; m["colour"] = x.colour; m["role"] = x.role
        return m
    }
}
