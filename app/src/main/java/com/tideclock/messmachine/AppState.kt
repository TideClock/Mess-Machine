package com.tideclock.messmachine

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.tideclock.messmachine.engine.Engine
import com.tideclock.messmachine.engine.Json
import com.tideclock.messmachine.engine.Library
import com.tideclock.messmachine.engine.Project
import com.tideclock.messmachine.engine.Sample
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
    data object Notes : Overlay()
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
    /** Built before the mess loads, so pads that use your own sounds find them. */
    val bench = Workbench(app, lib)

    var project: Project = loadCurrent()
        private set
    var version by mutableIntStateOf(0)
        private set
    var liveV by mutableIntStateOf(0)
        private set

    val notes = NotesBook(app)
    var favs by mutableStateOf(prefs.getStringSet("favs", emptySet())!!.toSet())
        private set
    /** Piano keyboards dropped below the pads, by pad number, oldest first. Up to four. */
    val keyboards = mutableStateListOf<Int>()
    var showFx by mutableStateOf(false)
    /** Which jar the Sounds tab shows. */
    var soundsCab by mutableStateOf("thumps")
    /** How the jars are browsed: by instrument type, by jar, or just favorites. */
    var soundsMode by mutableStateOf("type")
    var soundsType by mutableStateOf("")
    var soundsTags by mutableStateOf(setOf<String>())

    fun openWorkbench() { soundsMode = "jar"; soundsCab = "mine"; tab = Tab.Sounds }
    var xyHP by mutableStateOf(false)
        private set
    var micOn by mutableStateOf(false)
        private set
    private val tabState = mutableStateOf(Tab.Pads)
    var tab: Tab
        get() = tabState.value
        set(v) { if (v != tabState.value) notes.trail("tab ${v.label}"); tabState.value = v }
    var bank by mutableIntStateOf(project.sel / Project.BANK)
    var keysMode by mutableStateOf(false)
    var chordMode by mutableStateOf(prefs.getString("chord", "off") ?: "off")
    var noteLen by mutableIntStateOf(2)
    var page by mutableIntStateOf(0)
    var brush by mutableIntStateOf(0)
    private val overlayState = mutableStateOf<Overlay?>(null)
    var overlay: Overlay?
        get() = overlayState.value
        set(v) { if (v != overlayState.value) notes.trail(if (v == null) "close panel" else "open ${overlayName(v)}"); overlayState.value = v }
    /** A crash from last time, waiting to become a bug note. */
    var crash by mutableStateOf<String?>(null)
    var guide: Map<String, Any?> = emptyMap()
        private set
    var guideOn by mutableStateOf(prefs.getBoolean("guideOn", true))
        private set
    var toast by mutableStateOf<String?>(null)
    var toastId by mutableIntStateOf(0)
    var busy by mutableStateOf<String?>(null)
    var playOnTap by mutableStateOf(prefs.getBoolean("poc", true))
    var lastChord by mutableStateOf("")
    /** Bumped when the selected pad changes from the grid, so only the pads redraw. */
    var selV by mutableIntStateOf(0)
        private set
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
        try {
            val f = File(app.filesDir, "last_crash.txt")
            if (f.exists()) { crash = f.readText(); f.delete() }
        } catch (_: Exception) {}
        try { guide = Json.map(Json.parse(app.assets.open("guide.json").use { String(it.readBytes()) })) } catch (_: Exception) {}
        val seen = prefs.getInt("guideSeen", 0)
        if (crash != null) overlay = Overlay.Notes
        else if (guideOn && seen != BuildConfig.VERSION_CODE && guide.isNotEmpty()) overlay = Overlay.Notes
        prefs.edit().putInt("guideSeen", BuildConfig.VERSION_CODE).apply()
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
        notes.trail("live rec=${live.rec} click=${live.click} repeat=${live.nr} ${live.arp} ${live.nrRate}")
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

    fun undo() { notes.trail("undo"); if (hi > 0) { hi--; restore(hist[hi]) } }
    fun redo() { notes.trail("redo"); if (hi < hist.size - 1) { hi++; restore(hist[hi]) } }

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
    private var lastFrame = 0L
    private var winStart = 0L
    private var frames = 0
    private var slow = 0
    private var worst = 0L
    /** Screen smoothness over the last 10 seconds: frames, slow frames (over 33 ms), worst gap. */
    var uiPerf = "measuring"
        private set

    fun tick(now: Long) {
        if (lastFrame != 0L) {
            val dt = now - lastFrame
            frames++
            if (dt > 33_000_000L) slow++
            if (dt > worst) worst = dt
        }
        lastFrame = now
        if (winStart == 0L) winStart = now
        if (now - winStart > 10_000_000_000L) {
            val secs = (now - winStart) / 1e9
            uiPerf = "%.0f fps, %d slow frames, worst %d ms".format(frames / secs, slow, worst / 1_000_000)
            winStart = now; frames = 0; slow = 0; worst = 0
        }
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
        notes.trail(if (playing) "pause" else "play")
        if (playing) { engine.pause(); playing = false }
        else {
            if (project.songMode && project.songLen() == 0) view { songMode = false }
            follow = true
            engine.play(); playing = true
        }
    }

    fun stop() { notes.trail("stop"); engine.stop(); playing = false }

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
            notes.trail("key ${Theory.noteName(p)}")
            engine.down(100 + d, pad, ps, true)
            return
        }
        val i = bank * Project.BANK + d
        if (project.sel != i) { project.sel = i; selV++; scheduleSave() }
        if (project.kit[i].isEmpty()) { overlay = Overlay.Picker(i); return }
        downIds[d] = d
        notes.trail("pad ${i + 1}")
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

    fun pickChordMode(m: String) {
        if (m.startsWith("set:")) {
            val set = Theory.CHORDSETS[m.substring(4)]
            if (set != null && !Theory.setFits(set, project.scale)) {
                edit { scale = set.home }
                say("${m.substring(4)} was written in ${set.home.lowercase()}, so the scale moved to ${Theory.NN[project.root]} ${set.home.lowercase()}.")
            }
        }
        notes.trail("chords $m")
        chordMode = m
        prefs.edit().putString("chord", m).apply()
        version++
    }

    fun switchPlayOnTap(v: Boolean) { playOnTap = v; prefs.edit().putBoolean("poc", v).apply() }

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
        bench.item(key)?.let { b -> if (b.kind == "chop" && !project.edits.containsKey(key)) project.setEdit(key, SoundEdit().also { it.st = b.st; it.en = b.en }) }
        notes.trail("put ${lib.titleOf(key)} on pad ${pad + 1}")
        edit { this.assign(pad, key) }
        bank = pad / Project.BANK
        preview(key)
        say("${lib.titleOf(key)} is on pad ${pad + 1}.")
    }

    fun clearPad(pad: Int) {
        keyboards.remove(pad)
        notes.trail("clear pad ${pad + 1}")
        val t = lib.titleOf(project.kit[pad])
        edit { this.clearPad(pad) }
        say("Pad ${pad + 1} is empty again${if (t.isNotEmpty()) " ($t is back in its jar)" else ""}.")
    }

    fun setEdit(key: String, e: SoundEdit, commit: Boolean) = edit(commit) { this.setEdit(key, e) }

    /* ---------------- steps ---------------- */

    fun toggleStep(key: String, j: Int) {
        notes.trail("step ${lib.titleOf(key)} ${j + 1}")
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
        notes.trail("roll ${Theory.noteName(p)} step ${st + 1}")
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
        notes.trail("loop ${Project.LETTERS[i]}")
        if (playing && project.songMode) follow = false
        view { cur = i }
        page = 0
    }

    /** Like the site: one bar to two copies bar one into bar two; two to one drops bar two. */
    fun toggleLen() {
        val P = project.pat()
        val L = Project.LETTERS[project.cur]
        if (P.len == 16) {
            edit {
                P.len = 32
                for (a in P.steps.values) for (j in 0 until 16) a[j + 16] = a[j]
                for (l in P.notes.values) { val copy = l.map { Project.Note(it.s + 16, it.p, it.l) }; l.addAll(copy) }
            }
            say("Loop $L is two bars now. Bar one got copied into bar two.")
        } else {
            edit {
                P.len = 16
                for (a in P.steps.values) for (j in 16 until 32) a[j] = false
                for (l in P.notes.values) { l.removeAll { it.s >= 16 }; for (n in l) n.l = minOf(n.l, 16 - n.s) }
            }
            page = 0
            say("Loop $L is back to one bar.")
        }
    }

    /** Clear the roll notes for the picked pad in this loop. */
    fun clearNotes() {
        val k = selKey()
        if (k.isEmpty()) return
        edit { pat().notes.remove(k) }
    }

    /** The site's Hush: everything quiet now, the loop pauses where it is. */
    fun hush() {
        notes.trail("hush")
        if (playing) { engine.pause(); playing = false }
        engine.panic()
        for (d in 0 until 16) downIds[d] = -1
        say("Hushed. Everything is quiet. Play picks up where it paused.")
    }

    /** The site's Bottle it: the loop to four bars (or the song), as a WAV. */
    fun bottleIt() {
        if (project.songMode && project.songLen() > 0) bounce(true, 1)
        else bounce(false, if (project.pat().len == 32) 2 else 4)
    }

    fun clearSlot() = edit { pat().clear() }

    /** The site's Random drums: fresh steps for every pad in this bank, busier on the first row. */
    fun randomDrums() {
        val rows = (0 until Project.BANK).map { bank * Project.BANK + it }.filter { project.kit[it].isNotEmpty() }
        if (rows.isEmpty()) { say("Bank ${bank + 1} is empty."); return }
        notes.trail("random drums")
        edit {
            val P = pat()
            rows.forEachIndexed { n, i ->
                val k = kit[i]
                for (j in 0 until P.len) P.setStep(k, j, Math.random() < (if (n == 0) .3 else .14))
            }
        }
    }

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
        if (used.isEmpty()) { say("Make a loop first."); return }
        // like the site's Rough it in: every used loop twice, in order
        edit {
            java.util.Arrays.fill(song, -1)
            for (i in 0 until minOf(Project.MAXSONG, used.size * 2)) song[i] = used[i / 2]
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
        notes.trail("save mess")
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
        notes.trail("open mess")
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
        notes.trail("import mess")
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
        notes.trail("bounce ${if (song) "song" else "loop x$loops"}")
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

    /* ---------------- notes for Claude ---------------- */

    val buildName: String get() = "${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})"

    fun screenName(): String = tab.label + (overlay?.let { " > " + overlayName(it) } ?: "")

    private fun overlayName(o: Overlay): String = when (o) {
        is Overlay.Picker -> "sound picker (pad ${o.pad + 1})"
        is Overlay.Doctor -> "doctor (${lib.titleOf(o.key)})"
        Overlay.Files -> "messes"
        Overlay.Tempo -> "tempo"
        Overlay.Notes -> "notes"
    }

    fun switchGuide(v: Boolean) { guideOn = v; prefs.edit().putBoolean("guideOn", v).apply() }

    /** Device, audio and mess facts that go under every note. */
    fun noteDetails(): String {
        val p = project
        val loops = (0 until Project.NSLOTS).count { p.pats[it].used() }
        return "**Device:** ${NotesBook.device()}\n" +
            "**Audio:** ${rate} Hz, buffer ${audio.bufferFrames} frames, burst ${burst}, underruns ${audio.underruns}, engine load ${audio.loadText()}\n" +
            "**Screen:** $uiPerf\n" +
            "**Mess:** \"${p.name.ifEmpty { "Untitled" }}\", ${p.bpm} BPM, swing ${p.swing}%, ${p.loadedCount()} pads, $loops loops, " +
            (if (p.songMode) "song mode (${p.songLen()} bars)" else "loop ${Project.LETTERS[p.cur]}") +
            ", keys ${if (keysMode) "on" else "off"}, chords $chordMode, repeat ${if (live.nr) "on (${live.arp})" else "off"}\n"
    }

    fun saveNote(cat: String, topic: String, text: String, send: Boolean) {
        val (c, tp, tx) = notes.parsePrefix(text, cat, topic)
        if (tx.isBlank() && crash == null) { say("Write or say something first."); return }
        val n = MNote(NotesBook.newId(), c, tp.trim(), tx, System.currentTimeMillis(), buildName, screenName(), extra = crash ?: "")
        crash = null
        notes.add(n)
        notes.trail("note ${catLabel(c)}")
        if (send) notes.send(n, noteDetails()) else say("Saved on the phone. Send it when you are ready.")
    }

    fun sendNote(n: MNote) = notes.send(n, noteDetails())

    /* ---------------- favorites ---------------- */

    fun toggleFav(key: String) {
        favs = if (key in favs) favs - key else favs + key
        prefs.edit().putStringSet("favs", favs).apply()
    }

    /* ---------------- piano keyboards under the pads ---------------- */

    /** Which pad's hold menu is open, or -1. */
    var padMenu by mutableIntStateOf(-1)

    /** One piano per row of pads: it drops right under the row its pad sits in. */
    fun toggleKeyboard(pad: Int) {
        if (pad in keyboards) { keyboards.remove(pad); return }
        if (project.kit[pad].isEmpty()) { say("Put a sound on pad ${pad + 1} first."); return }
        val b = pad / Project.BANK
        val row = (pad % Project.BANK) / 4
        keyboards.removeAll { it / Project.BANK == b && (it % Project.BANK) / 4 == row }
        keyboards.add(pad)
        if (bank != b) bank = b
        notes.trail("piano pad ${pad + 1}")
    }

    /** View size, so more fits on the screen. 1 = normal. */
    var uiScale by mutableFloatStateOf(prefs.getFloat("uiScale", 1f))
        private set

    fun setViewSize(v: Float) {
        uiScale = v.coerceIn(.6f, 1.3f)
        prefs.edit().putFloat("uiScale", uiScale).apply()
    }

    private fun pianoId(pad: Int, p: Int) = 1000 + pad * 32 + (p + 12)

    /** Chords can play, or just show their notes on the piano so you can play them yourself. */
    var chordPlay by mutableStateOf(prefs.getBoolean("chordPlay", true))
        private set

    fun switchChordPlay(v: Boolean) { chordPlay = v; prefs.edit().putBoolean("chordPlay", v).apply() }

    /** The notes the chord mode builds on a key, folded into the piano's two octaves. */
    fun chordFor(p: Int): IntArray {
        val strip = Theory.stripNotes(project.scale, project.root)
        val idx = strip.indexOf(p)
        return if (chordMode.startsWith("set:") && idx < 0) intArrayOf(p) else Theory.chordOf(p, idx.coerceAtLeast(0), chordMode, project.scale, project.root)
    }

    fun pianoDown(pad: Int, p: Int) {
        if (project.kit[pad].isEmpty()) return
        val ps = chordFor(p)
        val idx = Theory.stripNotes(project.scale, project.root).indexOf(p).coerceAtLeast(0)
        lastChord = if (ps.size > 1) Theory.chordLabel(p, idx, chordMode, project.scale, project.root) + " · " + Theory.chordNotes(ps) +
            (if (!chordPlay) " (showing)" else "") else Theory.noteName(p)
        notes.trail("piano ${pad + 1} ${Theory.noteName(p)}")
        engine.down(pianoId(pad, p), pad, if (chordPlay) ps else intArrayOf(p), true)
    }

    fun pianoUp(pad: Int, p: Int) = engine.up(pianoId(pad, p))

    /* ---------------- perform FX ---------------- */

    fun fx(name: String, on: Boolean) {
        if (on && (name == "stutter" || name == "tape") && !playing) { say("Stutter and tape stop work while the loop plays."); return }
        if (on) notes.trail("fx $name")
        engine.fx(name, on)
    }

    fun xy(x: Float, y: Float, on: Boolean) = engine.xy(x, y, on)

    fun switchXyHP(v: Boolean) { xyHP = v; engine.xyHighPass(v) }

    /* ---------------- your Workbench ---------------- */

    /** Empty pads, the current bank first. */
    fun freePads(): List<Int> {
        val order = (0 until Project.BANK).map { bank * Project.BANK + it } + (0 until Project.NPADS).filter { it / Project.BANK != bank }
        return order.filter { project.kit[it].isEmpty() }
    }

    private fun placeNew(key: String, msg: String) {
        val pad = freePads().firstOrNull() ?: project.sel
        putOnPad(pad, key)
        say(msg + " It is on pad ${pad + 1} and in Your Workbench.")
    }

    fun importSounds(uris: List<Uri>) {
        if (uris.isEmpty()) return
        busy = "Bringing it in…"
        notes.trail("import ${uris.size} sound(s)")
        worker.execute {
            var ok = 0
            val keys = ArrayList<String>()
            var err = ""
            for (u in uris.take(16)) {
                try {
                    val s = bench.decode(app, u)
                    val name = queryName(u).substringBeforeLast('.').ifEmpty { "Import ${bench.count("imp") + 1}" }
                    keys.add(bench.addSample("imp", name, s)); ok++
                } catch (e: Exception) { err = e.message ?: "unknown" }
            }
            main.post {
                busy = null
                for ((i, k) in keys.withIndex()) {
                    val pad = freePads().firstOrNull() ?: project.sel
                    edit { this.assign(pad, k) }
                    if (i == 0) bank = pad / Project.BANK
                }
                say(when {
                    ok == 0 -> "That would not open as sound ($err)."
                    ok == 1 -> "Imported. It is on a pad and in Your Workbench."
                    else -> "$ok sounds imported onto pads."
                })
            }
        }
    }

    private fun queryName(u: Uri): String = try {
        app.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else "" } ?: ""
    } catch (_: Exception) { "" }

    fun toggleMic() {
        if (micOn) { bench.stopMic(); return }
        micOn = true
        notes.trail("mic on")
        bench.startMic { take ->
            main.post {
                micOn = false
                if (take == null) { say("Nothing came through the mic."); return@post }
                busy = "Saving the take…"
                worker.execute {
                    val key = bench.addSample("mic", "Mic take ${bench.count("mic") + 1}", take)
                    main.post { busy = null; placeNew(key, "Got it, %.1f seconds.".format(take.seconds())) }
                }
            }
        }
    }

    /** The site's resample: the current loop becomes one sound. */
    fun resampleLoop() {
        if (!project.pat().used()) { say("Loop ${Project.LETTERS[project.cur]} is empty."); return }
        busy = "Resampling…"
        val p = project.copy()
        val letter = Project.LETTERS[p.cur]
        notes.trail("resample $letter")
        worker.execute {
            try {
                for (k in p.kit) if (k.isNotEmpty()) try { lib.load(k) } catch (_: Exception) {}
                val lr = Engine.bounce(p, lib, 44100, false, 1, true)
                val key = bench.addSample("rs", "Resample $letter ${bench.count("rs") + 1}", Sample.fromStereo("rs", lr, 44100))
                main.post { busy = null; placeNew(key, "Loop $letter is now one sound.") }
            } catch (e: Exception) { main.post { busy = null; say("The resample broke: ${e.message}") } }
        }
    }

    /** Bake the doctor's settings into a new sound. */
    fun saveEditAsNew(key: String) {
        val s = lib.get(key) ?: return
        val e = project.edit(key).copy()
        busy = "Saving…"
        worker.execute {
            val r = Engine.renderOne("take", s, e)
            val k = bench.addSample("take", lib.titleOf(key).take(30) + " edit", r)
            main.post { busy = null; placeNew(k, "Saved as a new sound.") }
        }
    }

    fun shareSoundWav(key: String) {
        val s = lib.get(key) ?: return
        val e = project.edit(key).copy()
        worker.execute {
            val r = Engine.renderOne("take", s, e)
            val f = File(File(app.cacheDir, "shared").also { it.mkdirs() }, lib.titleOf(key).lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "sound" } + ".wav")
            f.outputStream().buffered().use { Wav.writeMono16(it, r.data, r.rate) }
            main.post { shareFile(f, "audio/wav", "Share the sound") }
        }
    }

    /** Re-pitch so the trimmed sound fills a length at the current tempo. */
    fun fitToTempo(key: String, bars: Double) {
        val s = lib.get(key) ?: return
        val e = project.edit(key).copy()
        val len = (e.en - e.st) * s.seconds()
        val target = bars * 16 * 60.0 / project.bpm / 4
        val semis = 12 * Math.log(len / target) / Math.log(2.0)
        if (Math.abs(semis) > 24.5) { say("That would take more than two octaves of re-pitching. Trim closer to the length first."); return }
        e.tune = Math.round(semis).toDouble()
        e.fine = Math.round((semis - e.tune) * 100).toDouble()
        setEdit(key, e, true)
        say("Re-pitched ${if (semis > 0) "up" else "down"} ${"%.2f".format(Math.abs(semis))} semitones to fill it at ${project.bpm} BPM.")
    }

    /** Slice a sound at the cuts and put each slice on an empty pad. */
    fun chopToPads(key: String, cuts: List<Double>) {
        val e = project.edit(key)
        val bounds = listOf(e.st) + cuts.filter { it > e.st && it < e.en }.sorted() + listOf(e.en)
        if (bounds.size < 3) { say("No cuts yet. Pick Equal or Auto, or tap out some cuts."); return }
        val base = lib.chopOf(key) ?: key
        val root = lib.titleOf(key).replace(Regex(" chop \\d+$"), "").take(22)
        val free = freePads()
        var placed = 0
        val keys = ArrayList<String>()
        for (i in 0 until bounds.size - 1) keys.add(bench.addChop(base, "$root chop ${i + 1}", bounds[i], bounds[i + 1]))
        edit {
            for ((i, k) in keys.withIndex()) {
                setEdit(k, e.copy().also { it.st = bounds[i]; it.en = bounds[i + 1] })
                if (i < free.size) { kit[free[i]] = k; placed++ }
            }
        }
        if (placed > 0) bank = free[0] / Project.BANK
        notes.trail("chop ${keys.size}")
        say("${keys.size} slices in Your Workbench" + if (placed < keys.size) ", $placed on pads (no more empty pads)." else ", all on pads.")
    }

    fun removeFromBench(key: String) {
        val pads = (0 until Project.NPADS).filter { project.kit[it] == key || lib.chopOf(project.kit[it]) == key }
        edit { for (p in pads) this.clearPad(p) }
        bench.remove(key)
        version++
        say("Tossed from Your Workbench.")
    }

    private fun shareFile(f: File, mime: String, title: String) {
        val uri = FileProvider.getUriForFile(app, app.packageName + ".files", f)
        val send = Intent(Intent.ACTION_SEND).apply { type = mime; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        app.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}
