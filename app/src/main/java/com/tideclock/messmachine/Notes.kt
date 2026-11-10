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
 * TideClock/Mess-Machine (labels from-app plus the category), and Claude's replies come back from
 * the issue comments. Categories match the bug-testing skill: Bug, Tuning, Feature, Question,
 * Discuss (Note and discuss, grouped by topic) and Wishlist.
 */
data class MNote(
    val id: String, val cat: String, val topic: String, val text: String, val at: Long, val build: String, val screen: String,
    val extra: String = "", val sent: Boolean = false, val issue: Int = 0, val status: String = "", val replies: List<String> = emptyList(),
)

val NOTE_CATS = listOf("bug" to "Bug", "tuning" to "Tuning", "feature" to "Feature", "question" to "Question", "discuss" to "Discuss", "wishlist" to "Wishlist")
fun catLabel(c: String) = NOTE_CATS.firstOrNull { it.first == c }?.second ?: "Note"

const val REPO = "TideClock/Mess-Machine"

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
                        Json.list(m["replies"]).map { it.toString() },
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
                "replies" to n.replies,
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

    /** Open GitHub's new-issue page, filled in. You tap Submit there; nothing is sent without you. */
    fun send(n: MNote, details: String) {
        val title = (if (n.cat == "discuss") "[Discuss: ${n.topic.ifEmpty { "general" }}] " else "[${catLabel(n.cat)}] ") +
            n.text.lineSequence().firstOrNull().orEmpty().take(70).ifEmpty { "(no text)" }
        fun body(trailLines: Int, extraChars: Int): String = buildString {
            append(n.text.ifEmpty { "(no text)" })
            append("\n\n---\n")
            append("**Category:** ${catLabel(n.cat)}")
            if (n.cat == "discuss") append(" · **Topic:** ${n.topic.ifEmpty { "general" }}")
            append("\n**Screen:** ${n.screen} · **Build:** ${n.build}\n")
            append(details)
            if (n.extra.isNotEmpty() && extraChars > 0) {
                append("\n<details><summary>Crash</summary>\n\n```\n")
                append(n.extra.take(extraChars))
                append("\n```\n</details>\n")
            }
            val tl = trailText().lines().takeLast(trailLines)
            if (tl.isNotEmpty() && trailLines > 0) {
                append("\n<details><summary>Last ${tl.size} actions</summary>\n\n```\n")
                append(tl.joinToString("\n"))
                append("\n```\n</details>\n")
            }
            append("\n<!-- mm-note:${n.id} -->\n")
        }
        fun url(b: String) = "https://github.com/$REPO/issues/new?title=" + enc(title) + "&labels=" + enc("from-app,${n.cat}") + "&body=" + enc(b)
        var lines = 40
        var extra = 3000
        var u = url(body(lines, extra))
        while (u.length > 7600 && (lines > 0 || extra > 0)) {
            if (lines > 0) lines -= 10 else extra -= 1000
            u = url(body(lines.coerceAtLeast(0), extra.coerceAtLeast(0)))
        }
        app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        replace(n.copy(sent = true, status = if (n.status.isEmpty()) "sent" else n.status))
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
                    val id = Regex("mm-note:([A-Za-z0-9]+)").find(body)?.groupValues?.get(1) ?: continue
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
