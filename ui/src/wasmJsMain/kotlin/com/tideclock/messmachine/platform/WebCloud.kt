package com.tideclock.messmachine.platform

import com.tideclock.messmachine.RealtimeClient
import com.tideclock.messmachine.RtConnector
import com.tideclock.messmachine.RtEvents
import com.tideclock.messmachine.RtSocket
import com.tideclock.messmachine.cloud.Clock
import com.tideclock.messmachine.cloud.CloudErr
import com.tideclock.messmachine.cloud.Done
import com.tideclock.messmachine.cloud.HttpCloud
import com.tideclock.messmachine.cloud.HttpReq
import com.tideclock.messmachine.cloud.HttpRes
import com.tideclock.messmachine.cloud.Prepared
import com.tideclock.messmachine.cloud.Presence
import com.tideclock.messmachine.cloud.RealtimeListener
import com.tideclock.messmachine.engine.Json

@JsFun("() => window.mm.cloud ? window.mm.cloud.url : ''") private external fun jsCloudUrl(): String
@JsFun("() => window.mm.cloud ? window.mm.cloud.key : ''") private external fun jsCloudKey(): String
@JsFun("() => window.mm.uuid()") private external fun jsUuid(): String
@JsFun("(m, u, h, b, done) => window.mm.cloudHttp(m, u, h, b, done)") private external fun jsCloudHttp(method: String, url: String, headers: String, body: String?, done: (Int, String) -> Unit)
@JsFun("(m, u, h, k, done) => window.mm.cloudSend(m, u, h, k, done)") private external fun jsCloudSend(method: String, url: String, headers: String, key: String, done: (Int, String) -> Unit)
@JsFun("(u, k, max, done) => window.mm.cloudGet(u, k, max, done)") private external fun jsCloudGet(url: String, key: String, max: Double, done: (Int, String) -> Unit)
@JsFun("(k, done) => window.mm.sha256(k, done)") private external fun jsSha256(key: String, done: (String) -> Unit)
@JsFun("(u, open, text, closed) => window.mm.wsOpen(u, open, text, closed)") private external fun jsWsOpen(url: String, open: () -> Unit, text: (String) -> Unit, closed: () -> Unit): Int
@JsFun("(id, t) => window.mm.wsSend(id, t)") private external fun jsWsSend(id: Int, text: String)
@JsFun("(id) => window.mm.wsClose(id)") private external fun jsWsClose(id: Int)

/**
 * Accounts in a browser (CMP step 8): the same HttpCloud calls as Android's SupabaseApi, with fetch for the requests,
 * the page's file store for sounds (under /files/cloud/sounds, by sha256, like Android's files/cloud/sounds), the
 * browser's SHA-256 (crypto.subtle) for hashing, and the browser's WebSocket for the live channel. Every answer comes
 * back from a browser callback, which runs on the page's one thread, the same as Android's main thread.
 *
 * The project URL and publishable key come from the page (window.mm.cloud, read from its mm-cloud meta tag, which the
 * web build fills in from gradle.properties or the MM_CLOUD_URL and MM_CLOUD_KEY secrets). No server there: orNull().
 */
class WebCloud(url: String, key: String) : HttpCloud(url, key) {
    companion object {
        fun orNull(): WebCloud? {
            val u = try { jsCloudUrl().trim() } catch (_: Throwable) { "" }
            val k = try { jsCloudKey().trim() } catch (_: Throwable) { "" }
            return if (u.isEmpty() || k.isEmpty()) null else WebCloud(u, k)
        }
        private const val SOUNDS = "/files/cloud/sounds"
        private const val LOCAL_INDEX = "/files/cloud/local-sounds.json"
    }

    /** sha256 to one of this browser's own Workbench sounds, learned when it was hashed for an upload. */
    private val localBySha = HashMap<String, String>()

    init {
        WebFiles.mkdirs(SOUNDS)
        try {
            if (WebFiles.exists(LOCAL_INDEX)) for ((k, v) in Json.map(Json.parse(WebFiles.readBytes(LOCAL_INDEX).decodeToString()))) if (v is String) localBySha[k] = v
        } catch (_: Throwable) {}
    }

    override fun nowMs(): Long = jsDate().toLong()
    override fun newId(): String = jsUuid().lowercase()

    private fun headers(r: HttpReq): String = Json.write(LinkedHashMap<String, Any?>().also { it.putAll(r.headers) })

    override fun http(r: HttpReq, cb: Done<HttpRes>) {
        jsCloudHttp(r.method, r.url, headers(r), r.body) { status, body -> cb.done(HttpRes(status, body), null) }
    }

    override fun sendFile(r: HttpReq, localFile: String, bytes: Long, cb: Done<HttpRes>) {
        jsCloudSend(r.method, r.url, headers(r), localFile) { status, body -> cb.done(HttpRes(status, body), null) }
    }

    /* ---------- sounds in this browser ---------- */

    /** A bn: key is a Workbench sound: /files/bench/<id>.wav (WebBench). */
    override fun prepareUpload(key: String, cb: Done<Prepared>) {
        val path = if (key.startsWith("bn:")) "/files/bench/" + key.substring(3) + ".wav" else ""
        if (path.isEmpty() || !WebFiles.isFile(path)) { cb.done(null, CloudErr("not_found", 0, key)); return }
        val bytes = WebFiles.length(path)
        jsSha256(path) { sha ->
            if (sha.isEmpty()) { cb.done(null, CloudErr("not_found", 0, key)); return@jsSha256 }
            val (rate, seconds) = try { wavInfo(path) } catch (_: Throwable) { 44100 to 0.0 }
            localBySha[sha] = path; saveLocalIndex()
            cb.done(Prepared(path, sha, bytes, seconds, rate), null)
        }
    }

    override fun cachedSound(sha256: String, cb: Done<String>) {
        val f = "$SOUNDS/$sha256.wav"
        val mine = localBySha[sha256]?.takeIf { WebFiles.isFile(it) }
        when {
            WebFiles.isFile(f) && WebFiles.length(f) > 0 -> cb.done(f, null)
            mine != null -> cb.done(mine, null)
            else -> cb.done(null, CloudErr("not_found", 0, ""))
        }
    }

    /** Downloads a signed URL into the file store, stops after the listed bytes, checks the hash, then answers the path. */
    override fun fetchSound(url: String, sha256: String, bytes: Long, cb: Done<String>) {
        val part = "$SOUNDS/$sha256.part"
        val done = "$SOUNDS/$sha256.wav"
        jsCloudGet(url, part, if (bytes > 0) bytes.toDouble() else -1.0) { status, sha ->
            when {
                status == 0 -> { WebFiles.delete(part); cb.done(null, CloudErr("net", 0, "")) }
                status !in 200..299 -> { WebFiles.delete(part); cb.done(null, CloudErr("http", status, "")) }
                sha != sha256 -> { WebFiles.delete(part); cb.done(null, CloudErr("bad_hash", 0, "")) }
                else -> { WebFiles.delete(done); WebFiles.rename(part, done); cb.done(done, null) }
            }
        }
    }

    override fun dropSound(sha256: String) { WebFiles.delete("$SOUNDS/$sha256.wav") }

    /* ---------- Slice C: the live socket (docs/ACCOUNTS-API.md s2), the shared RealtimeClient ---------- */

    private val rtUrl: String = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/realtime/v1/websocket?apikey=" + apiKey + "&vsn=1.0.0"
    private val rt by lazy {
        RealtimeClient(rtUrl, RtConnector { u, events -> openSocket(u, events) }, Clock { jsDate().toLong() },
            { session?.access ?: "" }, { session?.userId ?: "" })
    }
    private var rtTimer = -1

    private fun openSocket(url: String, events: RtEvents): RtSocket {
        val id = jsWsOpen(url, { events.onOpen() }, { t -> events.onText(t) }, { events.onClosed() })
        return object : RtSocket {
            override fun send(text: String) { jsWsSend(id, text) }
            override fun close() { jsWsClose(id) }
        }
    }

    override fun join(topic: String, l: RealtimeListener) {
        rt.join(topic, l)
        if (rtTimer < 0) rtTimer = jsInterval({
            if (rt.channels() == 0) { jsClearInterval(rtTimer); rtTimer = -1 } else rt.tick()
        }, 1000)
    }
    override fun track(topic: String, state: Presence) = rt.track(topic, state)
    override fun leave(topic: String) = rt.leave(topic)

    /* ---------- helpers ---------- */

    private fun saveLocalIndex() {
        try {
            val m = LinkedHashMap<String, Any?>(); m.putAll(localBySha)
            WebFiles.mkdirs("/files/cloud")
            WebFiles.writeBytes(LOCAL_INDEX, Json.write(m).encodeToByteArray())
        } catch (_: Throwable) {}
    }

    /** The sample rate and the length in seconds, from a WAV file's fmt and data chunks (Android's SupabaseApi.wavInfo). */
    private fun wavInfo(path: String): Pair<Int, Double> {
        val all = WebFiles.readBytes(path)
        val head = if (all.size > 4096) all.copyOf(4096) else all
        fun u32(i: Int) = (head[i].toLong() and 0xff) or ((head[i + 1].toLong() and 0xff) shl 8) or ((head[i + 2].toLong() and 0xff) shl 16) or ((head[i + 3].toLong() and 0xff) shl 24)
        fun u16(i: Int) = (head[i].toInt() and 0xff) or ((head[i + 1].toInt() and 0xff) shl 8)
        var rate = 44100; var block = 2; var data = all.size.toLong() - 44
        var i = 12
        while (i + 8 <= head.size) {
            val id = head.decodeToString(i, i + 4)
            val size = u32(i + 4)
            if (id == "fmt " && i + 24 <= head.size) { rate = u32(i + 12).toInt(); block = maxOf(1, u16(i + 20)) }
            if (id == "data") { data = minOf(size, all.size.toLong() - i - 8); break }
            i += 8 + size.toInt() + (size.toInt() and 1)
        }
        return rate to (if (rate > 0) data.toDouble() / block / rate else 0.0)
    }
}
