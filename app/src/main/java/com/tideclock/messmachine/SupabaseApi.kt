package com.tideclock.messmachine

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.tideclock.messmachine.cloud.CloudErr
import com.tideclock.messmachine.cloud.Done
import com.tideclock.messmachine.cloud.HttpCloud
import com.tideclock.messmachine.cloud.HttpReq
import com.tideclock.messmachine.cloud.HttpRes
import com.tideclock.messmachine.cloud.Prepared
import com.tideclock.messmachine.engine.Json
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Accounts Slice A on Android (docs/ACCOUNTS-API.md s5): the server calls of HttpCloud over HttpURLConnection.
 * One background thread for the small calls (sign-in, RPC, Edge Functions) and one for files (uploads, downloads,
 * hashing a sound), so a big upload never holds up a save. Every answer is posted back to the main thread, never
 * given inside the call. OkHttp is used for one thing only, the live websocket of Slice C (the end of this file).
 *
 * Sounds that came from the server are kept by their sha256 in files/cloud/sounds/<sha256>.wav. A file only lands
 * there after its hash checked out, so a file that is there is good.
 */
class SupabaseApi(private val app: Context, url: String, key: String) : HttpCloud(url, key) {
    companion object {
        /** null when this build has no server (the URL or the key is blank): then nothing account-related exists. */
        fun orNull(app: Context): SupabaseApi? {
            val u = BuildConfig.CLOUD_URL.trim(); val k = BuildConfig.CLOUD_KEY.trim()
            return if (u.isEmpty() || k.isEmpty()) null else SupabaseApi(app, u, k)
        }
        private const val CONNECT_MS = 15000
        private const val READ_MS = 30000
    }

    private val main = Handler(Looper.getMainLooper())
    private val rpcQueue = Executors.newSingleThreadExecutor { r -> Thread(r, "mm-cloud-rpc").also { it.isDaemon = true } }
    private val fileQueue = Executors.newSingleThreadExecutor { r -> Thread(r, "mm-cloud-files").also { it.isDaemon = true } }
    private val soundDir = File(app.filesDir, "cloud/sounds")
    /** sha256 to a sound file of this phone's own Workbench, learned when it was hashed for an upload. */
    private val localBySha = HashMap<String, String>()
    private val localIndex = File(app.filesDir, "cloud/local-sounds.json")

    init {
        // read on the file thread: nothing is asked for before start() anyway, and the queue keeps the order
        fileQueue.execute {
            try {
                if (localIndex.exists()) for ((k, v) in Json.map(Json.parse(localIndex.readText()))) if (v is String) synchronized(localBySha) { localBySha[k] = v }
            } catch (_: Exception) {}
        }
    }

    override fun nowMs(): Long = System.currentTimeMillis()
    override fun newId(): String = UUID.randomUUID().toString().lowercase()

    private fun <T> post(cb: Done<T>, value: T?, err: CloudErr?) { main.post { cb.done(value, err) } }

    /* ---------- the two queues HttpCloud sends through ---------- */

    override fun http(r: HttpReq, cb: Done<HttpRes>) {
        rpcQueue.execute { val res = send(r, null, 0L); main.post { cb.done(res, null) } }
    }

    override fun sendFile(r: HttpReq, localFile: String, bytes: Long, cb: Done<HttpRes>) {
        fileQueue.execute {
            val res = try { FileInputStream(localFile).use { send(r, it, bytes) } } catch (e: Exception) { HttpRes(0, "") }
            main.post { cb.done(res, null) }
        }
    }

    /** One request, start to end. status 0: no answer (no connection, a timeout, a dropped socket). */
    private fun send(r: HttpReq, file: InputStream?, bytes: Long): HttpRes {
        var c: HttpURLConnection? = null
        return try {
            c = URL(r.url).openConnection() as HttpURLConnection
            c.requestMethod = r.method
            c.connectTimeout = CONNECT_MS
            c.readTimeout = READ_MS
            c.useCaches = false
            for ((k, v) in r.headers) c.setRequestProperty(k, v)
            val body = r.body?.toByteArray(Charsets.UTF_8)
            if (file != null) {
                c.doOutput = true
                c.setFixedLengthStreamingMode(bytes)
                c.outputStream.use { out ->
                    val buf = ByteArray(65536)
                    var left = bytes
                    while (left > 0) {
                        val n = file.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) break
                        out.write(buf, 0, n); left -= n
                    }
                }
            } else if (body != null) {
                c.doOutput = true
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val status = c.responseCode
            val stream = if (status >= 400) c.errorStream else c.inputStream
            val text = stream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            HttpRes(status, text)
        } catch (e: Exception) {
            HttpRes(0, "")
        } finally {
            try { c?.disconnect() } catch (_: Exception) {}
        }
    }

    /* ---------- sounds on this phone ---------- */

    /** A bn: key is a Workbench sound: files/bench/<id>.wav. Hashes it on the file thread. */
    override fun prepareUpload(key: String, cb: Done<Prepared>) {
        fileQueue.execute {
            val f = if (key.startsWith("bn:")) File(app.filesDir, "bench/" + key.substring(3) + ".wav") else null
            if (f == null || !f.isFile) { post(cb, null, CloudErr("not_found", 0, key)); return@execute }
            try {
                val sha = sha256(f, f.length())
                val (rate, seconds) = wavInfo(f)
                synchronized(localBySha) { localBySha[sha] = f.absolutePath; saveLocalIndex() }
                post(cb, Prepared(f.absolutePath, sha, f.length(), seconds, rate), null)
            } catch (e: Exception) { post(cb, null, CloudErr("not_found", 0, e.message ?: "")) }
        }
    }

    override fun cachedSound(sha256: String, cb: Done<String>) {
        fileQueue.execute {
            val f = File(soundDir, "$sha256.wav")
            val mine = synchronized(localBySha) { localBySha[sha256] }?.let { File(it) }?.takeIf { it.isFile }
            when {
                f.isFile && f.length() > 0 -> post(cb, f.absolutePath, null)
                mine != null -> post(cb, mine.absolutePath, null)
                else -> post(cb, null, CloudErr("not_found", 0, ""))
            }
        }
    }

    /** Downloads a signed URL to the cache, stops after the listed bytes, checks the hash, then answers the path. */
    override fun fetchSound(url: String, sha256: String, bytes: Long, cb: Done<String>) {
        fileQueue.execute {
            soundDir.mkdirs()
            val part = File(soundDir, "$sha256.part")
            val done = File(soundDir, "$sha256.wav")
            var c: HttpURLConnection? = null
            try {
                c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = CONNECT_MS; c.readTimeout = READ_MS; c.useCaches = false
                val status = c.responseCode
                if (status !in 200..299) { post(cb, null, CloudErr("http", status, "")); return@execute }
                val md = MessageDigest.getInstance("SHA-256")
                var left = if (bytes > 0) bytes else Long.MAX_VALUE
                c.inputStream.use { inp ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(65536)
                        while (left > 0) {
                            val n = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (n < 0) break
                            md.update(buf, 0, n); out.write(buf, 0, n); left -= n
                        }
                    }
                }
                if (hex(md.digest()) != sha256) { part.delete(); post(cb, null, CloudErr("bad_hash", 0, "")); return@execute }
                done.delete()
                if (!part.renameTo(done)) { part.copyTo(done, overwrite = true); part.delete() }
                post(cb, done.absolutePath, null)
            } catch (e: Exception) {
                part.delete()
                post(cb, null, CloudErr("net", 0, e.message ?: ""))
            } finally {
                try { c?.disconnect() } catch (_: Exception) {}
            }
        }
    }

    override fun dropSound(sha256: String) {
        fileQueue.execute { File(soundDir, "$sha256.wav").delete() }
    }

    /* ---------- Slice C: the live socket (docs/ACCOUNTS-API.md s2) ---------- */

    // wss://<project host>/realtime/v1/websocket?apikey=<publishable key>&vsn=1.0.0
    private val rtUrl: String = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/realtime/v1/websocket?apikey=" + apiKey + "&vsn=1.0.0"
    private val okClient by lazy { okhttp3.OkHttpClient.Builder().connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build() }
    private val rt by lazy {
        RealtimeClient(rtUrl, RtConnector { url, events -> openSocket(url, events) }, com.tideclock.messmachine.cloud.Clock { System.currentTimeMillis() },
            { session?.access ?: "" }, { session?.userId ?: "" })
    }
    private var rtTicking = false
    private val rtTick = object : Runnable {
        override fun run() {
            if (rt.channels() == 0) { rtTicking = false; return }
            rt.tick()
            main.postDelayed(this, 1000)
        }
    }

    /** OkHttp's websocket as an RtSocket: its callbacks come on OkHttp's threads and are posted to the main thread. */
    private fun openSocket(url: String, events: RtEvents): RtSocket {
        val ws = okClient.newWebSocket(okhttp3.Request.Builder().url(url).build(), object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) { main.post { events.onOpen() } }
            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) { main.post { events.onText(text) } }
            override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) { try { webSocket.close(1000, null) } catch (_: Exception) {} }
            override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) { main.post { events.onClosed() } }
            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) { main.post { events.onClosed() } }
        })
        return object : RtSocket {
            override fun send(text: String) { ws.send(text) }
            override fun close() { try { ws.close(1000, null) } catch (_: Exception) {} }
        }
    }

    override fun join(topic: String, l: com.tideclock.messmachine.cloud.RealtimeListener) {
        rt.join(topic, l)
        if (!rtTicking) { rtTicking = true; main.postDelayed(rtTick, 1000) }
    }
    override fun track(topic: String, state: com.tideclock.messmachine.cloud.Presence) = rt.track(topic, state)
    override fun leave(topic: String) = rt.leave(topic)

    /* ---------- helpers (file thread) ---------- */

    private fun saveLocalIndex() {
        try {
            localIndex.parentFile?.mkdirs()
            val m = LinkedHashMap<String, Any?>(); m.putAll(localBySha)
            localIndex.writeText(Json.write(m))
        } catch (_: Exception) {}
    }

    private fun sha256(f: File, max: Long): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { inp ->
            val buf = ByteArray(65536)
            var left = max
            while (left > 0) {
                val n = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                md.update(buf, 0, n); left -= n
            }
        }
        return hex(md.digest())
    }

    private fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) { val v = x.toInt() and 0xff; sb.append("0123456789abcdef"[v shr 4]).append("0123456789abcdef"[v and 15]) }
        return sb.toString()
    }

    /** The sample rate and the length in seconds, from a WAV file's fmt and data chunks. */
    private fun wavInfo(f: File): Pair<Int, Double> {
        val head = ByteArray(minOf(f.length(), 4096L).toInt())
        FileInputStream(f).use { var off = 0; while (off < head.size) { val n = it.read(head, off, head.size - off); if (n < 0) break; off += n } }
        fun u32(i: Int) = (head[i].toLong() and 0xff) or ((head[i + 1].toLong() and 0xff) shl 8) or ((head[i + 2].toLong() and 0xff) shl 16) or ((head[i + 3].toLong() and 0xff) shl 24)
        fun u16(i: Int) = (head[i].toInt() and 0xff) or ((head[i + 1].toInt() and 0xff) shl 8)
        var rate = 44100; var block = 2; var data = f.length() - 44
        var i = 12
        while (i + 8 <= head.size) {
            val id = String(head, i, 4, Charsets.US_ASCII)
            val size = u32(i + 4)
            if (id == "fmt " && i + 24 <= head.size) { rate = u32(i + 12).toInt(); block = maxOf(1, u16(i + 20)) }
            if (id == "data") { data = minOf(size, f.length() - i - 8); break }
            i += 8 + size.toInt() + (size.toInt() and 1)
        }
        return rate to (if (rate > 0) data.toDouble() / block / rate else 0.0)
    }
}
