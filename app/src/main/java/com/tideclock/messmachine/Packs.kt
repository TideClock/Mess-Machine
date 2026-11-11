package com.tideclock.messmachine

import com.tideclock.messmachine.engine.Json
import com.tideclock.messmachine.engine.Library
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Sound packs (SE-17): clips Tyler keeps in the Vault, sent to the app. A pack is a zip on the `packs` release
 * (pack.json plus clips/*.flac), listed in that release's packs.json. Installed packs live in the app's own
 * storage and show up as a jar in Sounds; their keys ("pack:<id>:<clip>") save in messes like any sound.
 */
class Packs(private val root: File, private val lib: Library) {

    /** One pack on offer: from packs.json on the release. */
    data class Offer(val id: String, val name: String, val blurb: String, val version: Int, val clips: Int, val bytes: Long, val url: String)

    private val dir = File(root, "packs").also { it.mkdirs() }

    /** Installed packs: id to version. */
    val installed: MutableMap<String, Int> = LinkedHashMap()

    /** Register every pack already on the phone (at start). */
    fun loadInstalled() {
        for (d in dir.listFiles().orEmpty().filter { it.isDirectory }) try { register(d) } catch (_: Exception) {}
    }

    /** What the packs release offers right now. Network: call off the main thread. */
    fun fetchOffers(): List<Offer> {
        val rel = Json.map(Json.parse(get("https://api.github.com/repos/$REPO/releases/tags/packs")))
        val assets = Json.list(rel["assets"]).map { Json.map(it) }
        val index = assets.firstOrNull { Json.str(it["name"], "") == "packs.json" } ?: return emptyList()
        val list = Json.list(Json.parse(get(Json.str(index["browser_download_url"], ""), json = false)))
        return list.map { Json.map(it) }.mapNotNull { m ->
            val file = Json.str(m["file"], "")
            val asset = assets.firstOrNull { Json.str(it["name"], "") == file } ?: return@mapNotNull null
            Offer(Json.str(m["id"], ""), Json.str(m["name"], ""), Json.str(m["blurb"], ""), Json.num(m["version"], 1.0).toInt(),
                Json.num(m["clips"], 0.0).toInt(), Json.num(asset["size"], 0.0).toLong(), Json.str(asset["browser_download_url"], ""))
        }.filter { it.id.isNotEmpty() && SAFE.matches(it.id) }
    }

    /** Download a pack and install it. Network: call off the main thread. */
    fun install(o: Offer) {
        val tmp = File(root, "pack-${o.id}.zip")
        try {
            val c = URL(o.url).openConnection() as HttpURLConnection
            c.setRequestProperty("User-Agent", "MessMachine-Android")
            c.connectTimeout = 15000
            c.readTimeout = 30000
            try {
                if (c.responseCode !in 200..299) throw RuntimeException("GitHub said ${c.responseCode}")
                c.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
            } finally { c.disconnect() }
            installZip(tmp, o.id)
        } finally { tmp.delete() }
    }

    /** Unpack a pack's zip into its own folder and register it. Every entry must stay inside that folder. */
    fun installZip(zip: File, id: String) {
        require(SAFE.matches(id)) { "bad pack id" }
        val into = File(dir, id)
        val part = File(dir, "$id.part")
        part.deleteRecursively()
        part.mkdirs()
        val base = part.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val out = File(part, e.name)
                if (!out.canonicalPath.startsWith(base)) throw SecurityException("pack entry outside its folder: ${e.name}")
                if (e.isDirectory) { out.mkdirs(); continue }
                out.parentFile?.mkdirs()
                out.outputStream().use { z.copyTo(it) }
            }
        }
        if (!File(part, "pack.json").exists()) { part.deleteRecursively(); throw IllegalArgumentException("not a pack: no pack.json") }
        remove(id)
        part.renameTo(into)
        register(into)
    }

    /** Take a pack off the phone. Messes that use its sounds keep their keys; those pads go quiet until it is back. */
    fun remove(id: String) {
        lib.removeCab("pack:$id")
        installed.remove(id)
        File(dir, id).deleteRecursively()
    }

    private fun register(d: File) {
        val m = Json.map(Json.parse(File(d, "pack.json").readText()))
        val id = Json.str(m["id"], d.name)
        if (!SAFE.matches(id)) return
        val name = Json.str(m["name"], id)
        val cab = "pack:$id"
        lib.addCab(cab, name, 0xFFFF7A1A.toInt())
        for (o in Json.list(m["clips"])) {
            val c = Json.map(o)
            val f = File(d, Json.str(c["file"], ""))
            if (!f.exists() || !f.canonicalPath.startsWith(d.canonicalPath)) continue
            val key = "pack:$id:" + Json.str(c["id"], f.nameWithoutExtension)
            val s = Library.Sound(key, Json.str(c["title"], f.nameWithoutExtension), cab, f.absolutePath, 0.0, 0.0, Json.str(c["group"], ""), name)
            s.type = Json.str(c["type"], "chop")
            s.bpm = Json.num(c["bpm"], 0.0)
            s.beats = Json.num(c["beats"], 0.0).toInt()
            for (t in Json.list(c["tags"])) if (t is String) s.tags.add(t)
            lib.addUser(s, null)
        }
        installed[id] = Json.num(m["version"], 1.0).toInt()
    }

    private fun get(u: String, json: Boolean = true): String {
        val c = URL(u).openConnection() as HttpURLConnection
        if (json) c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "MessMachine-Android")
        c.connectTimeout = 10000
        c.readTimeout = 15000
        try {
            if (c.responseCode == 404) throw RuntimeException("No packs have been sent yet.")
            if (c.responseCode !in 200..299) throw RuntimeException("GitHub said ${c.responseCode}")
            return c.inputStream.use { String(it.readBytes()) }
        } finally { c.disconnect() }
    }

    companion object {
        private val SAFE = Regex("[a-z0-9][a-z0-9-]{0,40}")
    }
}
