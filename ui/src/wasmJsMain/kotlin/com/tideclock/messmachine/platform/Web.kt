@file:Suppress("unused")
package com.tideclock.messmachine.platform

/*
 * Everything the app asks of the browser, in one place (docs/WEB-CMP.md). Each external below is a one-line call into
 * the page's own helpers (window.mm, written in index.html), so this file holds no JS logic of its own.
 * Bytes cross one 32-bit word at a time: the page keeps a "current" buffer that these calls read and write.
 */

@JsFun("(k) => window.mm.sel(k)") private external fun jsSel(k: String): Int
@JsFun("(i) => window.mm.cur.getInt32(i, true)") private external fun jsI32(i: Int): Int
@JsFun("(i) => window.mm.cur.getUint8(i)") private external fun jsU8(i: Int): Int
@JsFun("(n) => window.mm.fresh(n)") private external fun jsFresh(n: Int)
@JsFun("(i, v) => window.mm.cur.setInt32(i, v, true)") private external fun jsSetI32(i: Int, v: Int)
@JsFun("(i, v) => window.mm.cur.setUint8(i, v)") private external fun jsSetU8(i: Int, v: Int)
@JsFun("(k) => window.mm.keep(k)") private external fun jsKeep(k: String)
@JsFun("(k) => window.mm.has(k)") external fun jsHas(k: String): Boolean
@JsFun("(k) => window.mm.isDir(k)") external fun jsIsDir(k: String): Boolean
@JsFun("(k) => window.mm.del(k)") external fun jsDel(k: String): Boolean
@JsFun("(a, b) => window.mm.rename(a, b)") external fun jsRename(a: String, b: String): Boolean
@JsFun("(k) => window.mm.list(k)") external fun jsList(k: String): String?
@JsFun("(k) => window.mm.mkdirs(k)") external fun jsMkdirs(k: String): Boolean
@JsFun("(k) => window.mm.mtime(k)") external fun jsMtime(k: String): Double
@JsFun("() => performance.now()") external fun jsNow(): Double
@JsFun("() => Date.now()") external fun jsDate(): Double
@JsFun("(k) => { try { return localStorage.getItem(k) } catch (e) { return null } }") external fun lsGet(k: String): String?
@JsFun("(k, v) => { try { localStorage.setItem(k, v) } catch (e) {} }") external fun lsSet(k: String, v: String)
@JsFun("(k) => { try { localStorage.removeItem(k) } catch (e) {} }") external fun lsDel(k: String)
@JsFun("(f, ms) => setTimeout(f, ms)") external fun jsTimeout(f: () -> Unit, ms: Int): Int
@JsFun("(id) => clearTimeout(id)") external fun jsClearTimeout(id: Int)
@JsFun("(f, ms) => setInterval(f, ms)") external fun jsInterval(f: () -> Unit, ms: Int): Int
@JsFun("(id) => clearInterval(id)") external fun jsClearInterval(id: Int)
@JsFun("() => innerWidth") external fun jsW(): Int
@JsFun("() => innerHeight") external fun jsH(): Int
@JsFun("(ms) => { try { navigator.vibrate && navigator.vibrate(ms) } catch (e) {} }") external fun jsBuzz(ms: Int)
@JsFun("(ms) => new Date(ms).toLocaleString('en-US', {month:'short', day:'numeric', hour:'numeric', minute:'2-digit'})") external fun jsShortDate(ms: Double): String
@JsFun("(cb) => window.mm.backPush(cb)") external fun jsBackPush(cb: () -> Unit): Int
@JsFun("(id) => window.mm.backDrop(id)") external fun jsBackDrop(id: Int)
@JsFun("(types, multi, cb) => window.mm.pick(types, multi, cb)") external fun jsPick(types: String, multi: Boolean, cb: (String) -> Unit)
@JsFun("() => window.mm.micOk") external fun jsMicOk(): Boolean
@JsFun("(cb) => window.mm.askMic(cb)") external fun jsAskMic(cb: (Boolean) -> Unit)
@JsFun("(path, mime, name) => window.mm.download(path, mime, name)") external fun jsDownload(path: String, mime: String, name: String)
@JsFun("(t) => window.mm.shareText(t)") external fun jsShareText(t: String)
@JsFun("() => window.mm.info()") external fun jsInfo(): String
@JsFun("() => window.mm.build") external fun jsBuild(): Int
@JsFun("() => window.mm.sha") external fun jsSha(): String
// the sound out: the page's AudioWorklet plays blocks this side renders
@JsFun("(rate) => window.mm.audioOpen(rate)") external fun jsAudioOpen(rate: Int): Int
@JsFun("() => window.mm.audioClose()") external fun jsAudioClose()
@JsFun("() => window.mm.audioFrames()") external fun jsAudioFrames(): Double
@JsFun("(n) => window.mm.blockStart(n)") external fun jsBlockStart(n: Int)
@JsFun("(i, v) => { window.mm.blk[i] = v }") external fun jsBlockSet(i: Int, v: Float)
@JsFun("() => window.mm.blockSend()") external fun jsBlockSend()
@JsFun("() => window.mm.audioInfo()") external fun jsAudioInfo(): String
@JsFun("() => window.mm.underruns") external fun jsUnderruns(): Int
@JsFun("() => window.mm.outMs()") external fun jsOutMs(): Double
// the mic for sampling: the page records, this side pulls floats in blocks
@JsFun("(cb) => window.mm.micStart(cb)") external fun jsMicStart(cb: (Boolean) -> Unit)
@JsFun("() => window.mm.micPull()") external fun jsMicPull(): Int
@JsFun("(i) => window.mm.cur.getFloat32(i * 4, true)") external fun jsF32(i: Int): Float
@JsFun("() => window.mm.micRate()") external fun jsMicRate(): Int
@JsFun("() => window.mm.micStop()") external fun jsMicStop()
// MIDI in, links, the network (notes to GitHub), and where saved files last
@JsFun("(on, say) => window.mm.midiStart(on, say)") external fun jsMidiStart(on: (Int, Int, Int) -> Unit, say: (String) -> Unit): Boolean
@JsFun("(u) => window.mm.open(u)") external fun jsOpen(u: String)
@JsFun("(m, u, a, b, done) => window.mm.http(m, u, a, b, done)") external fun jsHttp(method: String, url: String, auth: String?, body: String?, done: (Int, String) -> Unit)
@JsFun("() => window.mm.store") external fun jsStore(): String
@JsFun("(s) => encodeURIComponent(s)") external fun jsEnc(s: String): String
@JsFun("(ms, f) => new Date(ms).toLocaleString('en-US', f === 1 ? {month:'short', day:'numeric'} : {month:'short', day:'numeric', hour:'numeric', minute:'2-digit'})") external fun jsDateText(ms: Double, f: Int): String

/** Small helpers the platform classes share. */
object Web {
    /** Bytes the page holds under [key] (a fetched asset, a picked file or a saved file), or null. */
    fun read(key: String): ByteArray? {
        val n = jsSel(key)
        if (n < 0) return null
        val out = ByteArray(n)
        var i = 0
        while (i + 4 <= n) {
            val v = jsI32(i)
            out[i] = v.toByte(); out[i + 1] = (v shr 8).toByte(); out[i + 2] = (v shr 16).toByte(); out[i + 3] = (v shr 24).toByte()
            i += 4
        }
        while (i < n) { out[i] = jsU8(i).toByte(); i++ }
        return out
    }
    /** Keep [b] under [key] in the page (saved files persist in IndexedDB). */
    fun write(key: String, b: ByteArray) {
        val n = b.size
        jsFresh(n)
        var i = 0
        while (i + 4 <= n) {
            jsSetI32(i, (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8) or ((b[i + 2].toInt() and 0xff) shl 16) or (b[i + 3].toInt() shl 24))
            i += 4
        }
        while (i < n) { jsSetU8(i, b[i].toInt() and 0xff); i++ }
        jsKeep(key)
    }
    fun micGranted() = jsMicOk()
    fun askMic(done: (Boolean) -> Unit) = jsAskMic(done)
    fun pick(types: String, multiple: Boolean, done: (List<String>) -> Unit) =
        jsPick(types, multiple) { s -> done(if (s.isEmpty()) emptyList() else s.split('\n')) }
    fun buzz(ms: Int) = jsBuzz(ms)
    fun backPush(cb: () -> Unit): Int = jsBackPush(cb)
    fun backDrop(id: Int) = jsBackDrop(id)
    fun shortDate(ms: Double) = jsShortDate(ms)
}
