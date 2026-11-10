package com.tideclock.messmachine

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.tideclock.messmachine.engine.Engine
import com.tideclock.messmachine.engine.Json
import com.tideclock.messmachine.engine.Library
import com.tideclock.messmachine.engine.Project
import com.tideclock.messmachine.engine.SoundEdit
import com.tideclock.messmachine.engine.Theory
import com.tideclock.messmachine.engine.Wav
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

enum class Tab(val label: String) { Pads("Pads"), Steps("Steps"), Roll("Roll"), Song("Song"), Sounds("Sounds") }

sealed class Overlay {
    data class Picker(val pad: Int) : Overlay()
    data class Doctor(val key: String) : Overlay()
    data object Files : Overlay()
    data object Tempo : Overlay()
}

data class MessFile(val id: String, val name: String, val bpm: Int, val at: String, val pads: Int, val loops: Int, val songBars: Int)

/**
 * Everything the screens read and every change they make. Edits go through edit { }, which
 * rebuilds what the engine plays, records an undo step and autosaves.
 */
class AppState(private val app: Context) {
    val lib = Library({ path -> app.assets.open(path) }, "sounds/")
    private val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val rate: Int = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
    private val burst: Int = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 256
    val engine = Engine(rate)
    val audio = AudioOut(engine, rate, burst)
    val live = Engine.Live()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val prefs = app.getSharedPreferences("mess", Context.MODE_PRIVATE)
    private val messDir = File(app.filesDir, "messes").also { it.mkdirs() }
    private val currentFile = File(app.filesDir, "current.json")

    var project: Project = loadCurrent()
        private set
    var version by mutableIntStateOf(0)
        private set
    var liveV by mutableIntStateOf(0)
        private set

    var tab by mutableStateOf(Tab.Pads)
    var bank by mutableIntStateOf(project.sel / Project.BANK)
    var keysMode by mutableStateOf(false)
    var chordMode by mutableStateOf(prefs.getString("chord", "off") ?: "off")
    var noteLen by mutableIntStateOf(1)
    var page by mutableIntStateOf(0)
    var brush by mutableIntStateOf(0)
    var overlay by mutableStateOf<Overlay?>(null)
    var toast by mutableStateOf<String?>(null)
    var toastId by mutableIntStateOf(0)
    var busy by mutableStateOf<String?>(null)
    var playOnTap by mutableStateOf(prefs.getBoolean("poc", true))
    var lastChord by mutableStateOf("")
    var follow = true

    var playing by mutableStateOf(false)
        private set
    var heardPat by mutableIntStateOf(-1)
        private set
    var heardStep by mutableIntStateOf(-1)
        private set
    var heardSong by mutableIntStateOf(0)
        private set

    var fileId: String? = prefs.getString("fileId", null)
        private set
    val files = mutableStateListOf<MessFile>()

    private val hist = ArrayList<String>()
    private var hi = -1
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    private val downIds = IntArray(16) { -1 }

    init {
        commitUndo()
        rebuild()
        loadKit()
        refreshFiles()
    }

    /* ---------------- loading and saving the working mess ---------------- */

    private fun loadCurrent(): Project {
        return try {
            if (currentFile.exists()) Project.fromJson(currentFile.readText(), lib::has).project else Project.demo()
        } catch (e: Exception) {
            Project.demo()
        }
    }

    private val saveRun = Runnable { saveNow() }

    private fun scheduleSave() {
        main.removeCallbacks(saveRun)
        main.postDelayed(saveRun, 700)
    }

    fun saveNow() {
        main.removeCallbacks(saveRun)
        val json = project.toJson()
        worker.execute { try { currentFile.writeText(json) } catch (_: Exception) {} }
    }

    /** Decode any pad sounds that are not ready yet, then let the engine hear them. */
    private fun loadKit() {
        val need = project.kit.filter { it.isNotEmpty() && !lib.loaded(it) && lib.has(it) }
        if (need.isEmpty()) return
        worker.execute {
            for (k in need) try { lib.load(k) } catch (_: Exception) {}
            main.post { rebuild(); version++ }
        }
    }

    private fun rebuild() { engine.setSnapshot(Engine.Snapshot.build(project, lib, live)) }

    /** Make a change to the mess. */
    fun edit(commit: Boolean = true, block: Project.() -> Unit) {
        project.block()
        changed(commit)
    }

    private fun changed(commit: Boolean) {
        version++
        rebuild()
        if (commit) commitUndo()
        scheduleSave()
        loadKit()
    }

    /** Changes that should not land in undo: which pad or loop you are looking at. */
    fun view(block: Project.() -> Unit) {
        project.block()
        version++
        rebuild()
        scheduleSave()
    }

    fun setLive(block: Engine.Live.() -> Unit) {
        live.block()
        liveV++
        rebuild()
    }

    fun say(msg: String) { toast = msg; toastId++ }

    /* ---------------- undo ---------------- */

    private fun commitUndo() {
        val j = project.toJson(false)
        if (hi >= 0 && hist[hi] == j) return
        while (hist.size > hi + 1) hist.removeAt(hist.size - 1)
        hist.add(j)
        if (hist.size > 80) hist.removeAt(0)
        hi = hist.size - 1
        undoFlags()
    }

    private fun undoFlags() { canUndo = hi > 0; canRedo = hi < hist.size - 1 }

    fun undo() { if (hi > 0) { hi--; restore(hist[hi]) } }
    fun redo() { if (hi < hist.size - 1) { hi++; restore(hist[hi]) } }

    private fun restore(j: String) {
        project = Project.fromJson(j, lib::has).project
        version++
        rebuild()
        scheduleSave()
        undoFlags()
    }

    private fun replaceProject(p: Project, id: String?) {
        if (playing) engine.stop()
        project = p
        fileId = id
        prefs.edit().putString("fileId", id).apply()
        bank = p.sel / Project.BANK
        page = 0
        hist.clear(); hi = -1
        commitUndo()
        version++
        rebuild()
        scheduleSave()
        loadKit()
    }

    /* ---------------- the playhead, recorded hits ---------------- */

    /** Called every frame by the UI. */
    fun tick() {
        val p = engine.heard()
        if (playing != p.playing) playing = p.playing
        val st = if (p.playing) p.step else -1
        if (heardStep != st) heardStep = st
        if (heardPat != p.pat) heardPat = p.pat
        if (heardSong != p.songPos) heardSong = p.songPos
        if (p.playing && project.songMode && follow && p.pat >= 0 && p.pat != project.cur) {
            project.cur = p.pat
            version++
        }
        var any = false
        while (true) {
            val ev = engine.pollRec() ?: break
            val P = project.pats[ev.pat]
            if (ev.type == Engine.RecEvent.STEP) P.setStep(ev.key, ev.step, true)
            else {
                val notes = P.notes(ev.key)
                for (q in ev.pitches) {
                    notes.removeAll { it.s == ev.step && it.p == q }
                    notes.add(Project.Note(ev.step, q, ev.len))
                }
            }
            any = true
        }
        if (any) changed(true)
    }

    /* ---------------- transport ---------------- */

    fun togglePlay() {
        if (playing) { engine.pause(); playing = false }
        else {
            if (project.songMode && project.songLen() == 0) view { songMode = false }
            follow = true
            engine.play(); playing = true
        }
    }

    fun stop() { engine.stop(); playing = false }

    fun setBpm(v: Int) = edit { bpm = v.coerceIn(60, 180) }

    private val taps = ArrayList<Long>()
    fun tapTempo() {
        val now = System.currentTimeMillis()
        if (taps.isNotEmpty() && now - taps.last() > 2000) taps.clear()
        taps.add(now)
        if (taps.size > 5) taps.removeAt(0)
        if (taps.size >= 2) {
            val avg = (taps.last() - taps.first()) / (taps.size - 1).toDouble()
            setBpm(Math.round(60000 / avg).toInt())
        }
    }

    /* ---------------- pads and keys ---------------- */

    fun selKey(): String = project.kit[project.sel]

    fun padDown(d: Int) {
        if (keysMode) {
            val pad = project.sel
            if (project.kit[pad].isEmpty()) { say("Pick a pad with a sound first. The keys play whichever pad you picked last."); return }
            val p = Theory.keyNote(d, project.scale, project.root)
            val ps = Theory.chordOf(p, d, chordMode, project.scale, project.root)
            lastChord = if (ps.size > 1) Theory.chordLabel(p, d, chordMode, project.scale, project.root) + " · " + Theory.chordNotes(ps) else Theory.noteName(p)
            downIds[d] = 100 + d
            engine.down(100 + d, pad, ps, true)
            return
        }
        val i = bank * Project.BANK + d
        if (project.sel != i) view { sel = i }
        if (project.kit[i].isEmpty()) { overlay = Overlay.Picker(i); return }
        downIds[d] = d
        engine.down(d, i, intArrayOf(0), false)
    }

    fun padUp(d: Int) {
        val id = downIds[d]
        if (id >= 0) engine.up(id)
        downIds[d] = -1
    }

    fun keyLabel(d: Int): String {
        val p = Theory.keyNote(d, project.scale, project.root)
        return Theory.chordLabel(p, d, chordMode, project.scale, project.root)
    }

    fun keyOffset(d: Int): Int = Theory.keyNote(d, project.scale, project.root)

    fun setChordMode(m: String) {
        chordMode = m
        prefs.edit().putString("chord", m).apply()
        version++
    }

    fun setPlayOnTap(v: Boolean) { playOnTap = v; prefs.edit().putBoolean("poc", v).apply() }

    /** Hear a sound on its own, with its doctor settings. */
    fun preview(key: String, semis: Int = 0) {
        if (key.isEmpty()) return
        val s = lib.get(key)
        if (s != null) { engine.preview(s, project.edit(key), semis); return }
        worker.execute {
            val loaded = try { lib.load(key) } catch (_: Exception) { null }
            if (loaded != null) main.post { engine.preview(loaded, project.edit(key), semis) }
        }
    }

    /** A chord or note from the roll: play it like a held key for a moment. */
    fun audition(pitches: IntArray) {
        if (selKey().isEmpty()) return
        engine.down(300, project.sel, pitches, true)
        main.postDelayed({ engine.up(300) }, 320)
    }

    /* ---------------- sounds on pads ---------------- */

    fun putOnPad(pad: Int, key: String) {
        edit { this.assign(pad, key) }
        bank = pad / Project.BANK
        preview(key)
        say("${lib.titleOf(key)} is on pad ${pad + 1}.")
    }

    fun clearPad(pad: Int) {
        val t = lib.titleOf(project.kit[pad])
        edit { this.clearPad(pad) }
        say("Pad ${pad + 1} is empty again${if (t.isNotEmpty()) " ($t is back in its jar)" else ""}.")
    }

    fun setEdit(key: String, e: SoundEdit, commit: Boolean) = edit(commit) { this.setEdit(key, e) }

    /* ---------------- steps ---------------- */

    fun toggleStep(key: String, j: Int) {
        val P = project.pat()
        val on = !P.step(key, j)
        edit { P.setStep(key, j, on) }
        if (on && playOnTap && !playing) preview(key)
    }

    fun toggleMute(key: String) = edit { if (!mutes.remove(key)) mutes.add(key) }
    fun toggleSolo(key: String) = edit { if (!solos.remove(key)) solos.add(key) }

    fun selectPad(i: Int, hear: Boolean) {
        view { sel = i }
        if (hear) preview(project.kit[i])
    }

    /* ---------------- roll ---------------- */

    fun rollTap(p: Int, st: Int) {
        val k = selKey()
        if (k.isEmpty()) { say("Pick a pad with a sound on it first."); return }
        val P = project.pat()
        val notes = P.notes(k)
        val hit = notes.firstOrNull { it.p == p && st >= it.s && st < it.s + it.l }
        if (hit != null) { edit { notes.remove(hit) }; return }
        val strip = Theory.stripNotes(project.scale, project.root)
        val idx = strip.indexOf(p).coerceAtLeast(0)
        val ps = Theory.chordOf(p, idx, chordMode, project.scale, project.root)
        val l = noteLen.coerceAtMost(P.len - st).coerceAtLeast(1)
        edit {
            for (q in ps) {
                notes.removeAll { it.s == st && it.p == q }
                notes.add(Project.Note(st, q, l))
            }
        }
        if (!playing) audition(ps)
    }

    fun setScale(name: String) = edit { scale = name }
    fun setRoot(r: Int) = edit { root = r }

    /* ---------------- loops and the song ---------------- */

    fun selectSlot(i: Int) {
        if (playing && project.songMode) follow = false
        view { cur = i }
        page = 0
    }

    fun toggleLen() = edit { pat().len = if (pat().len == 32) 16 else 32 }

    fun clearSlot() = edit { pat().clear() }

    fun copySlotTo(to: Int) {
        if (to == project.cur) return
        val from = project.cur
        edit { pats[to] = pats[from].copy() }
        say("Loop ${Project.LETTERS[from]} copied onto ${Project.LETTERS[to]}.")
    }

    fun setSongMode(on: Boolean) {
        if (on && project.songLen() == 0) { say("Paint some loops into the song lane first."); return }
        view { songMode = on }
    }

    fun paintSong(i: Int) = edit { song[i] = if (song[i] == brush) -1 else brush }

    fun clearSong() = edit { java.util.Arrays.fill(song, -1); songMode = false }

    /** Chain every loop that has something in it, in order. */
    fun songFromLoops() {
        val used = (0 until Project.NSLOTS).filter { project.pats[it].used() }
        if (used.isEmpty()) { say("All eight loops are empty."); return }
        edit {
            java.util.Arrays.fill(song, -1)
            var k = 0
            for (u in used) { song[k++] = u; if (pats[u].len == 16 && k < Project.MAXSONG) song[k++] = u }
        }
    }

    /* ---------------- files ---------------- */

    fun refreshFiles() {
        worker.execute {
            val list = (messDir.listFiles() ?: emptyArray()).filter { it.name.endsWith(".json") }.mapNotNull { f ->
                try {
                    val m = Json.map(Json.parse(f.readText()))
                    val pats = Json.list(m["pats"])
                    val loops = pats.count { x ->
                        val pm = Json.map(x)
                        Json.map(pm["steps"]).values.any { (it as? String)?.contains('1') == true } ||
                            Json.map(pm["notes"]).values.any { (it as? String)?.isNotEmpty() == true }
                    }
                    MessFile(
                        f.name.removeSuffix(".json"), Json.str(m["name"], "Untitled mess"), Json.num(m["bpm"], 112.0).toInt(),
                        Json.str(m["at"], ""), Json.list(m["kit"]).count { (it as? String)?.isNotEmpty() == true }, loops,
                        Json.str(m["song"], "").trimEnd('-').length
                    )
                } catch (_: Exception) { null }
            }.sortedByDescending { it.at }
            main.post { files.clear(); files.addAll(list) }
        }
    }

    fun saveMess(name: String, asNew: Boolean) {
        val nm = name.trim().ifEmpty { project.name.ifEmpty { "Untitled mess" } }
        project.name = nm
        val id = if (asNew || fileId == null) System.currentTimeMillis().toString(36) else fileId!!
        val json = project.toJson()
        fileId = id
        prefs.edit().putString("fileId", id).apply()
        version++
        scheduleSave()
        worker.execute {
            File(messDir, "$id.json").writeText(json)
            main.post { refreshFiles(); say("Saved \"$nm\".") }
        }
    }

    fun openMess(id: String) {
        worker.execute {
            try {
                val p = Project.fromJson(File(messDir, "$id.json").readText(), lib::has).project
                main.post { replaceProject(p, id); overlay = null; say("Loaded \"${p.name.ifEmpty { "Untitled mess" }}\".") }
            } catch (e: Exception) { main.post { say("That mess would not open.") } }
        }
    }

    fun deleteMess(id: String) {
        worker.execute {
            File(messDir, "$id.json").delete()
            main.post { if (fileId == id) fileId = null; refreshFiles() }
        }
    }

    fun newMess() {
        replaceProject(Project.starter(), null)
        overlay = null
        say("A clean mess. The default eight pads are loaded.")
    }

    /** A mess in the site's JSON, from a file or a share. */
    fun importText(text: String) {
        try {
            val l = Project.fromJson(text.trim(), lib::has)
            replaceProject(l.project, null)
            overlay = null
            say("Imported \"${l.project.name.ifEmpty { "Untitled mess" }}\"." +
                if (l.missing > 0) " ${l.missing} pad${if (l.missing > 1) "s" else ""} used sounds that only live on the site, so they are empty here." else "")
        } catch (e: Exception) {
            say("That does not look like a Mess Machine file.")
        }
    }

    fun importUri(uri: Uri) {
        worker.execute {
            val text = try { app.contentResolver.openInputStream(uri)?.use { String(it.readBytes()) } } catch (_: Exception) { null }
            main.post { if (text != null) importText(text) else say("Could not read that file.") }
        }
    }

    private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "mess" }

    private fun share(file: File, mime: String, title: String) {
        val uri = FileProvider.getUriForFile(app, app.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        app.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private fun sharedDir() = File(app.cacheDir, "shared").also { it.mkdirs() }

    fun shareMess() {
        val name = project.name.ifEmpty { "Untitled mess" }
        val f = File(sharedDir(), slug(name) + ".json")
        f.writeText(project.toJson())
        share(f, "application/json", "Share \"$name\"")
    }

    fun bounce(song: Boolean, loops: Int) {
        if (song && project.songLen() == 0) { say("The song lane is empty."); return }
        if (!song && !project.pat().used()) { say("Loop ${Project.LETTERS[project.cur]} is empty."); return }
        busy = "Bouncing…"
        val p = project.copy()
        val name = project.name.ifEmpty { "Untitled mess" }
        worker.execute {
            try {
                for (k in p.kit) if (k.isNotEmpty()) try { lib.load(k) } catch (_: Exception) {}
                val w = Engine.bounce(p, lib, 44100, song, loops)
                val f = File(sharedDir(), slug(name) + (if (song) "-song" else "-loop-" + Project.LETTERS[p.cur].lowercase()) + ".wav")
                FileOutputStream(f).buffered().use { Wav.writeStereo16(it, w, 44100) }
                main.post { busy = null; share(f, "audio/wav", "Share the bounce") }
            } catch (e: Exception) {
                main.post { busy = null; say("The bounce failed: ${e.message}") }
            }
        }
    }
}
