package com.tideclock.messmachine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tideclock.messmachine.engine.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * The BUG button, native edition. Notes are kept on the phone, sent to Claude as GitHub issues on
 * hubbub0/Mess-Machine (labels from-app plus the category), and Claude's replies come back from
 * the issue comments. Categories match the bug-testing skill: Bug, Tuning, Feature, Question,
 * Discuss (Note and discuss, grouped by topic) and Wishlist.
 */
/** A note whose GitHub page was opened, not yet seen on GitHub (Submit may not have been tapped). */
const val OPENED = "opened on GitHub, not seen there yet"

data class MNote(
    val id: String, val cat: String, val topic: String, val text: String, val at: Long, val build: String, val screen: String,
    val extra: String = "", val sent: Boolean = false, val issue: Int = 0, val status: String = "", val replies: List<String> = emptyList(),
    /** A quick note keeps the phone's state and last taps from the moment you spoke, not from when it is sent. */
    val ctx: String = "", val ctxTrail: String = "",
)

val NOTE_CATS = listOf("bug" to "Bug", "tuning" to "Tuning", "feature" to "Feature", "question" to "Question", "discuss" to "Discuss", "wishlist" to "Wishlist")
fun catLabel(c: String) = NOTE_CATS.firstOrNull { it.first == c }?.second ?: "Note"

const val REPO = "hubbub0/Mess-Machine"

class NotesBook(private val app: Context) {
    private val file = File(app.filesDir, "notes.json")
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    val notes = mutableStateListOf<MNote>()
    var checking by mutableStateOf(false)
        private set
    var lastCheck by mutableStateOf("")
        private set

    /** What happened lately, newest last, for the issue body. */
    private val trail = ArrayDeque<String>()
    private val t0 = System.currentTimeMillis()

    init { load() }

    fun trail(what: String) {
        val s = (System.currentTimeMillis() - t0) / 1000
        val stamp = "%d:%02d".format(s / 60, s % 60)
        val last = trail.lastOrNull()
        if (last != null && last.substringAfter(' ').substringBefore(" ×") == what) {
            val n = last.substringAfter(" ×", "1").toIntOrNull() ?: 1
            trail.removeLast()
            trail.addLast("$stamp $what ×${n + 1}")
        } else trail.addLast("$stamp $what")
        while (trail.size > 60) trail.removeFirst()
    }

    fun trailText(): String = trail.joinToString("\n")

    private fun load() {
        try {
            if (!file.exists()) return
            for (o in Json.list(Json.parse(file.readText()))) {
                val m = Json.map(o)
                notes.add(
                    MNote(
                        Json.str(m["id"], ""), Json.str(m["cat"], "bug"), Json.str(m["topic"], ""), Json.str(m["text"], ""),
                        Json.num(m["at"], 0.0).toLong(), Json.str(m["build"], ""), Json.str(m["screen"], ""), Json.str(m["extra"], ""),
                        Json.bool(m["sent"], false), Json.num(m["issue"], 0.0).toInt(), Json.str(m["status"], ""),
                        Json.list(m["replies"]).map { it.toString() }, Json.str(m["ctx"], ""), Json.str(m["ctxTrail"], ""),
                    )
                )
            }
        } catch (_: Exception) {}
    }

    private fun save() {
        val list = notes.map { n ->
            linkedMapOf<String, Any?>(
                "id" to n.id, "cat" to n.cat, "topic" to n.topic, "text" to n.text, "at" to n.at.toDouble(), "build" to n.build,
                "screen" to n.screen, "extra" to n.extra, "sent" to n.sent, "issue" to n.issue.toDouble(), "status" to n.status,
                "replies" to n.replies, "ctx" to n.ctx, "ctxTrail" to n.ctxTrail,
            )
        }
        val json = Json.write(list)
        io.execute { try { file.writeText(json) } catch (_: Exception) {} }
    }

    fun add(n: MNote) { notes.add(0, n); save() }

    fun remove(id: String) { notes.removeAll { it.id == id }; save() }

    private fun replace(n: MNote) {
        val i = notes.indexOfFirst { it.id == n.id }
        if (i >= 0) notes[i] = n
        save()
    }

    /** Voice or typed prefixes pick the category: "wishlist: ...", "discuss jumping: ...", "tuning: ...". */
    fun parsePrefix(text: String, cat: String, topic: String): Triple<String, String, String> {
        val t = text.trim()
        val low = t.lowercase()
        for ((c, _) in NOTE_CATS) {
            if (low.startsWith("$c:") || low.startsWith("$c ")) {
                val rest = t.substring(c.length).trimStart(':', ' ', ',', '.')
                if (c == "discuss" && rest.contains(':')) return Triple(c, rest.substringBefore(':').trim(), rest.substringAfter(':').trim())
                return Triple(c, if (c == "discuss") topic else "", rest)
            }
        }
        return Triple(cat, topic, t)
    }

    private val keyPrefs = app.getSharedPreferences("notes-key", Context.MODE_PRIVATE)

    /**
     * Option A (note #16, Tyler's pick Nov 16): a GitHub key that can only write issues on this one repo, pasted in
     * once. With it, Send posts the note from inside the app; without it, Send opens the filled-in GitHub page.
     */
    var hasKey by mutableStateOf(keyPrefs.getString("key", "").orEmpty().isNotEmpty())
        private set

    fun saveKey(k: String) { keyPrefs.edit().putString("key", k.trim()).apply(); hasKey = k.isNotBlank() }

    fun forgetKey() { keyPrefs.edit().remove("key").apply(); hasKey = false }

    /** Send: post it with the key when there is one, else open GitHub's new-issue page (you tap Submit there). */
    fun send(n: MNote, details: String, done: (String) -> Unit = {}) {
        val key = keyPrefs.getString("key", "").orEmpty()
        if (key.isNotEmpty()) { post(n, details, key, done); return }
        openPage(n, details)
    }

    private fun titleOf(n: MNote) = (if (n.cat == "discuss") "[Discuss: ${n.topic.ifEmpty { "general" }}] " else "[${catLabel(n.cat)}] ") +
        (if (late(n)) "(written ${day(n.at)}) " else "") +
        n.text.lineSequence().firstOrNull().orEmpty().take(70).ifEmpty { "(no text)" }

    /** Nov 21 fix: a note sent long after it was written says so, so an old note can't pass for a new one. */
    private fun late(n: MNote) = System.currentTimeMillis() - n.at > 30 * 60 * 1000L
    private fun day(t: Long) = java.text.SimpleDateFormat("MMM d", java.util.Locale.US).format(java.util.Date(t))
    private fun stamp(t: Long) = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.US).format(java.util.Date(t))

    private fun bodyOf(n: MNote, details: String, trailLines: Int, extraChars: Int): String = buildString {
        append(n.text.ifEmpty { "(no text)" })
        if (late(n)) append("\n\n**Written ${stamp(n.at)} on build ${n.build}, sent ${stamp(System.currentTimeMillis())}.**")
        append("\n\n---\n")
        append("**Category:** ${catLabel(n.cat)}")
        if (n.cat == "discuss") append(" · **Topic:** ${n.topic.ifEmpty { "general" }}")
        append("\n**Screen:** ${n.screen} · **Build:** ${n.build}\n")
        append(n.ctx.ifEmpty { details })
        if (n.extra.isNotEmpty() && extraChars > 0) {
            append("\n<details><summary>Crash</summary>\n\n```\n")
            append(n.extra.take(extraChars))
            append("\n```\n</details>\n")
        }
        val tl = n.ctxTrail.ifEmpty { trailText() }.lines().filter { it.isNotBlank() }.takeLast(trailLines)
        if (tl.isNotEmpty() && trailLines > 0) {
            append("\n<details><summary>Last ${tl.size} actions</summary>\n\n```\n")
            append(tl.joinToString("\n"))
            append("\n```\n</details>\n")
        }
        append("\n<!-- mm-note:${n.id} -->\n")
    }

    /** Post the note as an issue with the key. Offline, it waits and goes with the next Send or reply check. */
    private fun post(n: MNote, details: String, key: String, done: (String) -> Unit) {
        val title = titleOf(n)
        val body = bodyOf(n, details, 60, 8000)
        replace(n.copy(status = "sending"))
        io.execute {
            val msg = try {
                val c = URL("https://api.github.com/repos/$REPO/issues").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("Authorization", "Bearer $key")
                c.setRequestProperty("User-Agent", "MessMachine-Android")
                c.setRequestProperty("Content-Type", "application/json")
                c.connectTimeout = 10000
                c.readTimeout = 20000
                try {
                    c.outputStream.use { it.write(Json.write(mapOf("title" to title, "body" to body, "labels" to listOf("from-app", n.cat))).toByteArray()) }
                    val code = c.responseCode
                    if (code in 200..299) {
                        val num = Json.num(Json.map(Json.parse(c.inputStream.use { String(it.readBytes()) }))["number"], 0.0).toInt()
                        main.post { replace(n.copy(sent = true, issue = num, status = "open")) }
                        "Sent to Claude as note #$num."
                    } else {
                        main.post { replace(n.copy(sent = false, status = "")) }
                        if (code == 401 || code == 403) "GitHub refused the key (it may have run out). Paste a new one under Send without the browser, or send this one with the page."
                        else "GitHub said $code. The note is kept; tap Send to try again."
                    }
                } finally { c.disconnect() }
            } catch (e: Exception) {
                main.post { replace(n.copy(sent = false, status = "waiting to send")) }
                "No connection. The note waits and goes out with the next Send or reply check."
            }
            main.post { done(msg) }
        }
    }

    /** Notes that waited for a connection go out now (with the key only). */
    fun sendWaiting(details: (MNote) -> String, done: (String) -> Unit) {
        val key = keyPrefs.getString("key", "").orEmpty()
        if (key.isEmpty()) return
        for (n in notes.filter { it.status == "waiting to send" && it.issue == 0 }) post(n, details(n), key, done)
    }

    /** Open GitHub's new-issue page, filled in. You tap Submit there; nothing is sent without you. */
    private fun openPage(n: MNote, details: String) {
        val title = titleOf(n)
        fun body(trailLines: Int, extraChars: Int): String = bodyOf(n, details, trailLines, extraChars)
        fun url(b: String) = "https://github.com/$REPO/issues/new?title=" + enc(title) + "&labels=" + enc("from-app,${n.cat}") + "&body=" + enc(b)
        var lines = 40
        var extra = 3000
        var u = url(body(lines, extra))
        while (u.length > 7600 && (lines > 0 || extra > 0)) {
            if (lines > 0) lines -= 10 else extra -= 1000
            u = url(body(lines.coerceAtLeast(0), extra.coerceAtLeast(0)))
        }
        app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        // Nov 21 fix: opening the page is not sending it; Check for replies marks it sent once GitHub has it
        replace(n.copy(sent = true, status = if (n.status.isEmpty() || n.status == "sent") OPENED else n.status))
    }

    /**
     * Send all without the key (Nov 21 fix): every waiting note on ONE GitHub page, newest first, each with when it
     * was written, so one Submit sends them all. Notes that don't fit the page wait for the next Send all.
     */
    fun openBundle(waiting: List<MNote>, details: String): Int {
        if (waiting.isEmpty()) return 0
        val list = waiting.sortedByDescending { it.at }
        fun bodyFor(k: Int): String = buildString {
            append("${k} notes from the app, newest first.\n")
            for (n in list.take(k)) {
                append("\n### ")
                append(if (n.cat == "discuss") "[Discuss: ${n.topic.ifEmpty { "general" }}]" else "[${catLabel(n.cat)}]")
                append(" ${stamp(n.at)} · ${n.screen} · build ${n.build}\n\n")
                append(n.text.ifEmpty { "(no text)" })
                append("\n<!-- mm-note:${n.id} -->\n")
            }
            append("\n---\n")
            append(details.lines().take(6).joinToString("\n"))
        }
        fun url(k: Int) = "https://github.com/$REPO/issues/new?title=" + enc("[Notes] ${k} notes from the app (${day(list.first().at)})") +
            "&labels=" + enc((listOf("from-app") + list.take(k).map { it.cat }.distinct()).joinToString(",")) + "&body=" + enc(bodyFor(k))
        var k = list.size
        while (k > 1 && url(k).length > 7600) k--
        app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url(k))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        for (n in list.take(k)) replace(n.copy(sent = true, status = OPENED))
        return k
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Read Claude's replies from the issues. Public repo, so no sign-in needed to read. */
    fun checkReplies(done: (String) -> Unit) {
        if (checking) return
        checking = true
        io.execute {
            val msg = try {
                val issues = Json.list(Json.parse(get("https://api.github.com/repos/$REPO/issues?labels=from-app&state=all&per_page=100")))
                var matched = 0
                var newReplies = 0
                val updates = ArrayList<MNote>()
                for (o in issues) {
                    val m = Json.map(o)
                    val body = Json.str(m["body"], "")
                    // a bundle (Send all without the key) carries several notes: each one finds its issue
                    val ids = Regex("mm-note:([A-Za-z0-9]+)").findAll(body).map { it.groupValues[1] }.toList()
                    for (id in ids) {
                    val n = notes.firstOrNull { it.id == id } ?: continue
                    matched++
                    val labels = Json.list(m["labels"]).map { Json.str(Json.map(it)["name"], "") }
                    val closed = Json.str(m["state"], "") == "closed"
                    val status = when {
                        "fixed" in labels -> "fixed"
                        "wishlist" in labels -> "on the wishlist"
                        "wontfix" in labels -> "won't fix"
                        "discuss" in labels && !closed -> "waiting to discuss"
                        closed -> "closed"
                        else -> "open"
                    }
                    val replies = if (Json.num(m["comments"], 0.0) > 0)
                        Json.list(Json.parse(get(Json.str(m["comments_url"], "")))).map { c ->
                            val cm = Json.map(c)
                            Json.str(Json.map(cm["user"])["login"], "") + ": " + Json.str(cm["body"], "")
                        } else emptyList()
                    if (replies.size > n.replies.size) newReplies += replies.size - n.replies.size
                    updates.add(n.copy(issue = Json.num(m["number"], 0.0).toInt(), status = status, replies = replies, sent = true))
                    }
                }
                main.post { for (u in updates) replace(u) }
                when {
                    matched == 0 -> "None of your notes are on GitHub yet. Send one, then tap Submit on the page that opens."
                    newReplies > 0 -> "$newReplies new repl${if (newReplies == 1) "y" else "ies"}."
                    else -> "No new replies."
                }
            } catch (e: Exception) {
                "Could not reach GitHub: ${e.message ?: "no connection"}"
            }
            main.post { checking = false; lastCheck = msg; done(msg) }
        }
    }

    private fun get(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "MessMachine-Android")
        c.connectTimeout = 10000
        c.readTimeout = 15000
        try {
            if (c.responseCode !in 200..299) throw RuntimeException("GitHub said ${c.responseCode}")
            return c.inputStream.use { String(it.readBytes()) }
        } finally { c.disconnect() }
    }

    companion object {
        fun device(): String = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        fun newId(): String = java.lang.Long.toString(System.currentTimeMillis(), 36) + (100..999).random()
    }
}
