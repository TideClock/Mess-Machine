package com.tideclock.messmachine.platform

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tideclock.messmachine.MNote
import com.tideclock.messmachine.NOTE_CATS
import com.tideclock.messmachine.NoteBook
import com.tideclock.messmachine.OPENED
import com.tideclock.messmachine.REPO
import com.tideclock.messmachine.catLabel
import com.tideclock.messmachine.engine.Json

/**
 * The BUG button in a browser: Android's NotesBook (app/.../Notes.kt) with the page's fetch for the network. Notes are
 * kept in /files/notes.json, sent as GitHub issues on hubbub0/Mess-Machine with the key when there is one, else on
 * GitHub's filled-in new-issue page; Claude's replies come back from the issue comments.
 */
class WebNotes : NoteBook {
    private val file = "/files/notes.json"
    override val notes = mutableStateListOf<MNote>()
    override var checking by mutableStateOf(false)
        private set
    override var lastCheck by mutableStateOf("")
        private set
    private val trail = ArrayDeque<String>()
    private val t0 = jsDate()

    init { load() }

    override fun trail(what: String) {
        val s = ((jsDate() - t0) / 1000).toLong()
        val stamp = "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
        val last = trail.lastOrNull()
        if (last != null && last.substringAfter(' ').substringBefore(" ×") == what) {
            val k = last.substringAfter(" ×", "1").toIntOrNull() ?: 1
            trail.removeLast()
            trail.addLast("$stamp $what ×${k + 1}")
        } else trail.addLast("$stamp $what")
        while (trail.size > 60) trail.removeFirst()
    }

    override fun trailText(): String = trail.joinToString("\n")

    private fun load() {
        try {
            if (!WebFiles.isFile(file)) return
            for (o in Json.list(Json.parse(WebFiles.readBytes(file).decodeToString()))) {
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
        try { WebFiles.writeBytes(file, Json.write(list).encodeToByteArray()) } catch (_: Exception) {}
    }

    override fun add(n: MNote) { notes.add(0, n); save() }
    override fun remove(id: String) { notes.removeAll { it.id == id }; save() }
    private fun replace(n: MNote) { val i = notes.indexOfFirst { it.id == n.id }; if (i >= 0) notes[i] = n; save() }

    override fun parsePrefix(text: String, cat: String, topic: String): Triple<String, String, String> {
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

    private fun key() = lsGet("mess.notes.key").orEmpty()
    override var hasKey by mutableStateOf(key().isNotEmpty())
        private set
    override fun saveKey(k: String) { lsSet("mess.notes.key", k.trim()); hasKey = k.isNotBlank() }
    override fun forgetKey() { lsDel("mess.notes.key"); hasKey = false }

    override fun send(n: MNote, details: String, done: (String) -> Unit) {
        val k = key()
        if (k.isNotEmpty()) { post(n, details, k, done); return }
        openPage(n, details)
    }

    private fun late(n: MNote) = jsDate() - n.at > 30 * 60 * 1000.0
    private fun day(t: Long) = jsDateText(t.toDouble(), 1)
    private fun stamp(t: Long) = jsDateText(t.toDouble(), 0)
    private fun titleOf(n: MNote) = (if (n.cat == "discuss") "[Discuss: ${n.topic.ifEmpty { "general" }}] " else "[${catLabel(n.cat)}] ") +
        (if (late(n)) "(written ${day(n.at)}) " else "") +
        n.text.lineSequence().firstOrNull().orEmpty().take(70).ifEmpty { "(no text)" }

    private fun bodyOf(n: MNote, details: String, trailLines: Int, extraChars: Int): String = buildString {
        append(n.text.ifEmpty { "(no text)" })
        if (late(n)) append("\n\n**Written ${stamp(n.at)} on build ${n.build}, sent ${stamp(jsDate().toLong())}.**")
        append("\n\n---\n")
        append("**Category:** ${catLabel(n.cat)}")
        if (n.cat == "discuss") append(" · **Topic:** ${n.topic.ifEmpty { "general" }}")
        append("\n**Screen:** ${n.screen} · **Build:** ${n.build}\n")
        append(n.ctx.ifEmpty { details })
        if (n.extra.isNotEmpty() && extraChars > 0) {
            append("\n<details><summary>Crash</summary>\n\n```\n"); append(n.extra.take(extraChars)); append("\n```\n</details>\n")
        }
        val tl = n.ctxTrail.ifEmpty { trailText() }.lines().filter { it.isNotBlank() }.takeLast(trailLines)
        if (tl.isNotEmpty() && trailLines > 0) {
            append("\n<details><summary>Last ${tl.size} actions</summary>\n\n```\n"); append(tl.joinToString("\n")); append("\n```\n</details>\n")
        }
        append("\n<!-- mm-note:${n.id} -->\n")
    }

    private fun post(n: MNote, details: String, key: String, done: (String) -> Unit) {
        val body = Json.write(mapOf("title" to titleOf(n), "body" to bodyOf(n, details, 60, 8000), "labels" to listOf("from-app", n.cat)))
        replace(n.copy(status = "sending"))
        jsHttp("POST", "https://api.github.com/repos/$REPO/issues", key, body) { code, text ->
            val msg = when {
                code in 200..299 -> {
                    val num = try { Json.num(Json.map(Json.parse(text))["number"], 0.0).toInt() } catch (_: Exception) { 0 }
                    replace(n.copy(sent = true, issue = num, status = "open")); "Sent to Claude as note #$num."
                }
                code == 0 -> { replace(n.copy(sent = false, status = "waiting to send")); "No connection. The note waits and goes out with the next Send or reply check." }
                code == 401 || code == 403 -> { replace(n.copy(sent = false, status = "")); "GitHub refused the key (it may have run out). Paste a new one under Send without the browser, or send this one with the page." }
                else -> { replace(n.copy(sent = false, status = "")); "GitHub said $code. The note is kept; tap Send to try again." }
            }
            done(msg)
        }
    }

    override fun sendWaiting(details: (MNote) -> String, done: (String) -> Unit) {
        val k = key()
        if (k.isEmpty()) return
        for (n in notes.filter { it.status == "waiting to send" && it.issue == 0 }) post(n, details(n), k, done)
    }

    private fun issueUrl(title: String, labels: String, body: String) =
        "https://github.com/$REPO/issues/new?title=" + jsEnc(title) + "&labels=" + jsEnc(labels) + "&body=" + jsEnc(body)

    private fun openPage(n: MNote, details: String) {
        val title = titleOf(n)
        var lines = 40
        var extra = 3000
        var u = issueUrl(title, "from-app,${n.cat}", bodyOf(n, details, lines, extra))
        while (u.length > 7600 && (lines > 0 || extra > 0)) {
            if (lines > 0) lines -= 10 else extra -= 1000
            u = issueUrl(title, "from-app,${n.cat}", bodyOf(n, details, lines.coerceAtLeast(0), extra.coerceAtLeast(0)))
        }
        jsOpen(u)
        replace(n.copy(sent = true, status = if (n.status.isEmpty() || n.status == "sent") OPENED else n.status))
    }

    override fun openBundle(waiting: List<MNote>, details: String): Int {
        if (waiting.isEmpty()) return 0
        val list = waiting.sortedByDescending { it.at }
        fun bodyFor(k: Int): String = buildString {
            append("$k notes from the app, newest first.\n")
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
        fun url(k: Int) = issueUrl("[Notes] $k notes from the app (${day(list.first().at)})",
            (listOf("from-app") + list.take(k).map { it.cat }.distinct()).joinToString(","), bodyFor(k))
        var k = list.size
        while (k > 1 && url(k).length > 7600) k--
        jsOpen(url(k))
        for (n in list.take(k)) replace(n.copy(sent = true, status = OPENED))
        return k
    }

    /** Read Claude's replies from the issues (public repo, no sign-in needed to read). */
    override fun checkReplies(done: (String) -> Unit) {
        if (checking) return
        checking = true
        fun end(msg: String) { checking = false; lastCheck = msg; done(msg) }
        jsHttp("GET", "https://api.github.com/repos/$REPO/issues?labels=from-app&state=all&per_page=100", null, null) { code, text ->
            if (code !in 200..299) { end(if (code == 0) "Could not reach GitHub from this page." else "Could not reach GitHub: GitHub said $code"); return@jsHttp }
            val issues = try { Json.list(Json.parse(text)) } catch (_: Exception) { ArrayList() }
            var matched = 0
            var newReplies = 0
            val todo = ArrayList<Pair<MNote, MutableMap<String, Any?>>>()
            for (o in issues) {
                val m = Json.map(o)
                val ids = Regex("mm-note:([A-Za-z0-9]+)").findAll(Json.str(m["body"], "")).map { it.groupValues[1] }.toList()
                for (id in ids) { val n = notes.firstOrNull { it.id == id } ?: continue; matched++; todo += n to m }
            }
            if (todo.isEmpty()) { end(if (matched == 0) "None of your notes are on GitHub yet. Send one, then tap Submit on the page that opens." else "No new replies."); return@jsHttp }
            var left = todo.size
            for ((n, m) in todo) {
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
                val num = Json.num(m["number"], 0.0).toInt()
                fun apply(replies: List<String>) {
                    val cur = notes.firstOrNull { it.id == n.id } ?: n
                    if (replies.size > cur.replies.size) newReplies += replies.size - cur.replies.size
                    replace(cur.copy(issue = num, status = status, replies = replies, sent = true))
                    if (--left == 0) end(if (newReplies > 0) "$newReplies new repl${if (newReplies == 1) "y" else "ies"}." else "No new replies.")
                }
                if (Json.num(m["comments"], 0.0) > 0) jsHttp("GET", Json.str(m["comments_url"], ""), null, null) { c2, t2 ->
                    val r = if (c2 in 200..299) try {
                        Json.list(Json.parse(t2)).map { c -> val cm = Json.map(c); Json.str(Json.map(cm["user"])["login"], "") + ": " + Json.str(cm["body"], "") }
                    } catch (_: Exception) { n.replies } else n.replies
                    apply(r)
                } else apply(emptyList())
            }
        }
    }
}
