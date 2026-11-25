package com.tideclock.messmachine

import com.tideclock.messmachine.cloud.Clock
import com.tideclock.messmachine.cloud.Presence
import com.tideclock.messmachine.cloud.RealtimeListener
import com.tideclock.messmachine.cloud.Wire
import com.tideclock.messmachine.engine.Json

/*
 * Accounts, Slice C: the live socket to Supabase Realtime (docs/ACCOUNTS-API.md s2). This file is the protocol only:
 * what goes over the socket and what comes back. The socket itself is an RtSocket, so the app hands in OkHttp's
 * websocket (SupabaseApi) and the tests a scripted one. Everything here runs on one thread (the main thread).
 *
 * The socket only ever says "something changed" sooner than the poll would: ops still come through ops_since, in
 * order. With no socket the poll carries everything.
 */

/** One open websocket. */
interface RtSocket {
    fun send(text: String)
    fun close()
}

/** What a websocket reports, on the main thread. */
interface RtEvents {
    fun onOpen()
    fun onText(text: String)
    fun onClosed()
}

fun interface RtConnector { fun connect(url: String, events: RtEvents): RtSocket }

/**
 * Phoenix channels over one socket: join with the access token and presence under my user id, the heartbeat every
 * 25 s, `ops` and `meta` broadcasts, presence state and diffs (clamped), track at most once every 2 s, a refreshed
 * token sent as `access_token`. A channel that goes away is reported with onDrop and forgotten: the caller joins again.
 */
class RealtimeClient(
    private val url: String, private val connector: RtConnector, private val clock: Clock,
    private val token: () -> String, private val userId: () -> String,
) {
    companion object {
        const val BEAT_MS = 25000L
        const val TRACK_MS = 2000L
        /** Clamps every field of a presence payload (contract s2). */
        fun clamp(p: Presence): Presence = Presence(p.u.take(64), p.page.take(12), p.loop.coerceIn(0, 7), p.pad.coerceIn(-1, 63), p.playing, p.rec)
    }

    private class Chan(val topic: String, val l: RealtimeListener) {
        var joinRef = ""
        var joined = false
        var sentJoin = false
        val here = LinkedHashMap<String, Presence>()      // presence key (a user id) to the newest state
        var want: Presence? = null                        // the state to track next
        var lastSent = ""
        var lastTrackAt = 0L
    }

    private val chans = LinkedHashMap<String, Chan>()
    private var sock: RtSocket? = null
    private var open = false
    private var gen = 0
    private var ref = 0
    private var lastBeat = 0L
    private var sentToken = ""

    /** How many channels are joined or joining (the app stops ticking when it is 0). */
    fun channels(): Int = chans.size
    fun connected(): Boolean = open

    fun join(topic: String, l: RealtimeListener) {
        val old = chans[topic]
        if (old != null && old.l === l) return
        chans[topic] = Chan(topic, l)
        if (sock == null) connect() else if (open) sendJoin(chans[topic]!!)
    }

    fun leave(topic: String) {
        val c = chans.remove(topic) ?: return
        if (open && c.sentJoin) frame(c.topic, "phx_leave", LinkedHashMap(), c.joinRef)
        if (chans.isEmpty()) shut()
    }

    fun track(topic: String, state: Presence) {
        val c = chans[topic] ?: return
        c.want = clamp(state)
        flush(c, clock.nowMs())
    }

    /** About once a second: the heartbeat, a refreshed token, presence that waited its 2 s. */
    fun tick() {
        if (chans.isEmpty() || !open) return
        val now = clock.nowMs()
        if (now - lastBeat >= BEAT_MS) { lastBeat = now; frame0("phoenix", "heartbeat", LinkedHashMap(), "") }
        val t = token()
        if (t.isNotEmpty() && t != sentToken) {
            sentToken = t
            for (c in chans.values) if (c.joined) { val p = LinkedHashMap<String, Any?>(); p["access_token"] = t; frame(c.topic, "access_token", p, c.joinRef) }
        }
        for (c in chans.values) flush(c, now)
    }

    /** The app is done with the socket (signed out). */
    fun shut() {
        gen++
        val s = sock
        sock = null; open = false
        try { s?.close() } catch (_: Exception) {}
    }

    private fun connect() {
        val g = ++gen
        open = false
        sock = connector.connect(url, object : RtEvents {
            override fun onOpen() {
                if (g != gen) return
                open = true
                lastBeat = clock.nowMs()
                sentToken = token()
                for (c in ArrayList(chans.values)) sendJoin(c)
            }
            override fun onText(text: String) { if (g == gen) take(text) }
            override fun onClosed() {
                if (g != gen) return
                sock = null; open = false
                gen++
                val all = ArrayList(chans.values)
                chans.clear()
                for (c in all) c.l.onDrop(c.topic)
            }
        })
    }

    private fun sendJoin(c: Chan) {
        c.joinRef = (++ref).toString()
        c.sentJoin = true
        val presence = LinkedHashMap<String, Any?>(); presence["enabled"] = true; presence["key"] = userId()
        val broadcast = LinkedHashMap<String, Any?>(); broadcast["ack"] = false; broadcast["self"] = false
        val config = LinkedHashMap<String, Any?>(); config["broadcast"] = broadcast; config["presence"] = presence; config["private"] = true
        val p = LinkedHashMap<String, Any?>(); p["config"] = config; p["access_token"] = token()
        send("realtime:" + c.topic, "phx_join", p, c.joinRef, c.joinRef)
    }

    private fun flush(c: Chan, now: Long) {
        val w = c.want ?: return
        if (!open || !c.joined || now - c.lastTrackAt < TRACK_MS) return
        val s = LinkedHashMap<String, Any?>()
        s["u"] = w.u; s["page"] = w.page; s["loop"] = w.loop.toDouble(); s["pad"] = w.pad.toDouble(); s["playing"] = w.playing; s["rec"] = w.rec
        val text = Json.write(s)
        c.want = null
        if (text == c.lastSent) return
        c.lastSent = text; c.lastTrackAt = now
        val p = LinkedHashMap<String, Any?>(); p["type"] = "presence"; p["event"] = "track"; p["payload"] = s
        frame(c.topic, "presence", p, c.joinRef)
    }

    private fun frame(topic: String, event: String, payload: Map<String, Any?>, joinRef: String) = send("realtime:$topic", event, payload, (++ref).toString(), joinRef)
    private fun frame0(topic: String, event: String, payload: Map<String, Any?>, joinRef: String) = send(topic, event, payload, (++ref).toString(), joinRef)

    private fun send(topic: String, event: String, payload: Map<String, Any?>, ref: String, joinRef: String) {
        val m = LinkedHashMap<String, Any?>()
        m["topic"] = topic; m["event"] = event; m["payload"] = payload; m["ref"] = ref
        m["join_ref"] = if (joinRef.isEmpty()) null else joinRef
        try { sock?.send(Json.write(m)) } catch (_: Exception) {}
    }

    /** One frame from the server. Anything that is not what the contract lists is ignored. */
    private fun take(text: String) {
        val m = try { Json.map(Json.parse(text)) } catch (_: Exception) { return }
        val wire = Json.str(m["topic"], "")
        if (!wire.startsWith("realtime:")) return
        val c = chans[wire.substring(9)] ?: return
        val payload = Json.map(m["payload"])
        when (Json.str(m["event"], "")) {
            "phx_reply" -> {
                if (Json.str(m["ref"], "") != c.joinRef || c.joined) return
                if (Json.str(payload["status"], "") == "ok") { c.joined = true; c.l.onJoin(c.topic, true, ""); flush(c, clock.nowMs()) }
                else {
                    chans.remove(c.topic)
                    val r = Json.map(payload["response"])
                    c.l.onJoin(c.topic, false, Json.str(r["reason"], Json.str(r["message"], "refused")))
                    if (chans.isEmpty()) shut()
                }
            }
            "broadcast" -> {
                val p = payload["payload"]
                when (Json.str(payload["event"], "")) {
                    "ops" -> { val b = try { Wire.batch(p) } catch (_: Exception) { return }; c.l.onOps(c.topic, b) }
                    "meta" -> { val pm = Json.map(p); c.l.onMeta(c.topic, Json.str(pm["kind"], ""), Json.str(pm["gone"], ""), Json.str(pm["sha256"], "")) }
                }
            }
            "presence_state" -> {
                c.here.clear()
                for ((k, v) in payload) newest(v)?.let { c.here[k] = it }
                c.l.onPresence(c.topic, ArrayList(c.here.values))
            }
            "presence_diff" -> {
                for (k in Json.map(payload["leaves"]).keys) c.here.remove(k)
                for ((k, v) in Json.map(payload["joins"])) newest(v)?.let { c.here[k] = it }
                c.l.onPresence(c.topic, ArrayList(c.here.values))
            }
            "phx_error", "phx_close" -> {
                chans.remove(c.topic)
                c.l.onDrop(c.topic)
                if (chans.isEmpty()) shut()
            }
        }
    }

    /** The last of a key's metas, clamped. null when it carries no user id. */
    private fun newest(v: Any?): Presence? {
        val metas = Json.list(Json.map(v)["metas"])
        if (metas.isEmpty()) return null
        val p = try { clamp(Wire.presence(metas[metas.size - 1])) } catch (_: Exception) { return null }
        return if (p.u.isEmpty()) null else p
    }
}
