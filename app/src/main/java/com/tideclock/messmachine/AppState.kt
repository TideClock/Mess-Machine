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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.tideclock.messmachine.engine.Bake
import com.tideclock.messmachine.engine.Engine
import com.tideclock.messmachine.engine.Json
import com.tideclock.messmachine.engine.Library
import com.tideclock.messmachine.engine.Project
import com.tideclock.messmachine.engine.Sample
import com.tideclock.messmachine.engine.SoundEdit
import com.tideclock.messmachine.engine.Synth
import com.tideclock.messmachine.engine.Theory
import com.tideclock.messmachine.engine.Wav
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

enum class Tab(val label: String) { Pads("Pads"), Console("Console"), Synth("Synth"), Steps("Steps"), Roll("Roll"), Song("Song"), Sounds("Sounds") }

/**
 * One piece of Your Console. type picks what it is, pad which pad it plays (pianos, filters, a pad
 * row, one effect), size how tall (0 small, 1 medium, 2 large), half = half the width.
 */
data class Module(
    val type: String, val pad: Int = -1, val size: Int = 1, val half: Boolean = false,
    /** Where it sits on the desk grid: 12 columns across, rows of 24 dp. x < 0 = not placed yet. */
    val x: Int = -1, val y: Int = 0, val w: Int = 12, val h: Int = 4, val pinned: Boolean = false,
) {
    fun code() = "$type,$pad,$size,${if (half) 1 else 0},$x,$y,$w,$h,${if (pinned) 1 else 0}"
    companion object {
        fun parse(c: String): Module? = try {
            val f = c.split(",")
            if (f[0] !in CONSOLE_TYPES.map { it.first }) null
            else if (f.size >= 9) Module(f[0], f[1].toInt(), f[2].toInt().coerceIn(0, 2), f[3] == "1", f[4].toInt(), f[5].toInt(), f[6].toInt().coerceIn(1, 12), f[7].toInt().coerceIn(1, 60), f[8] == "1")
            else Module(f[0], f[1].toInt(), f[2].toInt().coerceIn(0, 2), f[3] == "1")
        } catch (_: Exception) { null }
    }
}

/** What can sit on the side dock. */
val DOCK_TYPES = listOf(
    "lock" to "Lock", "hold" to "Hold", "filter" to "Filter + resonance", "repeat" to "Repeat",
    "rec" to "Rec", "beat" to "Beat light", "stutter" to "Stutter", "echo" to "Echo throw", "xy" to "XY setup for the picked pad", "desk" to "Desk layouts",
    "mouth" to "Mouth control",
)

/** Every module you can add, with its name. */
val CONSOLE_TYPES = listOf(
    "transport" to "Transport", "pads" to "Pads (4 x 4)", "row" to "One row of pads", "piano" to "Padiano for a pad",
    "pfilt" to "Filter for a pad", "hold" to "Hold", "fx" to "Effects (all nine)", "fx1" to "One effect", "xy" to "Filter pad (everything)",
    "repeat" to "Repeat + arp", "loops" to "Loops A to H", "scale" to "Scale + chords", "beat" to "Beat light", "xyp" to "XY for a pad",
)

/** The site's perform effects, in the order the panels show them. */
val FX_NAMES = listOf(
    "stutter" to "Stutter", "echo" to "Echo throw", "crush" to "Crush",
    "tape" to "Tape stop", "gate" to "Gate", "dirty" to "Dirty",
    "ring" to "Ring mod", "oct" to "Octave down", "rev" to "Reverse",
)

sealed class Overlay {
    data class Picker(val pad: Int) : Overlay()
    data class Doctor(val key: String) : Overlay()
    data object Files : Overlay()
    data object Settings : Overlay()
    data object Tempo : Overlay()
    data object Notes : Overlay()
    data object AudioCheck : Overlay()
    data object Packs : Overlay()
    data object Learn : Overlay()
    data object Mouth : Overlay()
    data class Xy(val pad: Int) : Overlay()
}

/** A saved mess in the list. colors and grid draw its little picture: bank 1's pads, and its first loop with something in it. */
data class MessFile(
    val id: String, val name: String, val bpm: Int, val at: String, val pads: Int, val loops: Int, val songBars: Int,
    val colors: List<Int> = emptyList(), val grid: List<String> = emptyList(),
)

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
    val audio = AudioOut(engine, rate, burst, app)
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
    /** ? in the top bar: show the how-to text on every page. Each page also shows it once, the first time. */
    var helpOn by mutableStateOf(prefs.getBoolean("helpOn", false))
        private set
    val helpSeen = mutableStateListOf<String>().also { it.addAll((prefs.getString("helpSeen", "") ?: "").split(",").filter { x -> x.isNotEmpty() }) }
    fun switchHelp(v: Boolean = !helpOn) { helpOn = v; prefs.edit().putBoolean("helpOn", v).apply() }
    /** Is the how-to text showing on this page or sheet right now. */
    fun helpShows(page: String): Boolean = helpOn || page !in helpSeen
    fun markHelpSeen(page: String) {
        if (page in helpSeen) return
        helpSeen.add(page); prefs.edit().putString("helpSeen", helpSeen.joinToString(",")).apply()
    }
    /** The Play tab shows the pad grid (Pads or Keys) or My Desk; it opens on the one you used last. */
    var playDesk by mutableStateOf(prefs.getBoolean("playDesk", false))
        private set
    private val tabState = mutableStateOf(Tab.Pads)
    var tab: Tab
        get() = tabState.value
        set(v) {
            if (v != tabState.value) { notes.trail("tab ${v.label}"); markHelpSeen(tabState.value.label) }
            if (v == Tab.Pads || v == Tab.Console) { playDesk = v == Tab.Console; prefs.edit().putBoolean("playDesk", playDesk).apply() }
            tabState.value = v
        }
    /** The Play tab in the tab bar: back to whichever view of it you had. */
    fun openPlay() { tab = if (playDesk) Tab.Console else Tab.Pads }
    var bank by mutableIntStateOf(project.sel / Project.BANK)
    var keysMode by mutableStateOf(false)
    var chordMode by mutableStateOf(prefs.getString("chord", "off") ?: "off")
    var noteLen by mutableIntStateOf(2)
    var page by mutableIntStateOf(0)
    var brush by mutableIntStateOf(0)
    /**
     * Lock: the page stays put (no scrolling, tabs, menus, sheets); playing still works. Declared before
     * the overlay because opening the start guide at launch checks it (build 41 crashed on launch).
     */
    var locked by mutableStateOf(false)
        private set
    private val overlayState = mutableStateOf<Overlay?>(null)
    var overlay: Overlay?
        get() = overlayState.value
        set(v) { if (v != null && locked) return; if (v != overlayState.value) notes.trail(if (v == null) "close panel" else "open ${overlayName(v)}"); overlayState.value = v }
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
    /** A short buzz when a pad is hit (Settings, off to start). */
    var buzz by mutableStateOf(prefs.getBoolean("buzz", false))
        private set
    fun switchBuzz(v: Boolean) { buzz = v; prefs.edit().putBoolean("buzz", v).apply() }
    /** Keep playing when you leave the app (MM-10). Off: leaving pauses the music. */
    var bgPlay by mutableStateOf(prefs.getBoolean("bgPlay", true))
        private set
    fun switchBgPlay(v: Boolean) { bgPlay = v; prefs.edit().putBoolean("bgPlay", v).apply(); backgroundPlay(playing) }
    private var fgOn = false
    /** The background-play notification runs while the music plays, so leaving the app never cuts it off. */
    fun backgroundPlay(on: Boolean) {
        val want = on && bgPlay
        if (want == fgOn) return
        fgOn = want
        val i = android.content.Intent(app, PlaybackService::class.java)
        try { if (want) androidx.core.content.ContextCompat.startForegroundService(app, i) else app.stopService(i) } catch (_: Exception) { fgOn = false }
    }
    /** What the notification says: the loop or the song, and the tempo. */
    fun playingLine(): String = (if (project.songMode) "Song, ${project.songLen()} bars" else "Loop ${Project.LETTERS[project.cur]}") + " at ${project.bpm} BPM"
    /** Stop pressed in the notification: stop the music, and the sound output too if the app is not on screen. */
    fun stopFromNotification() {
        engine.stop(); playing = false; fgOn = false
        notes.trail("stop from the notification")
        if (!onScreen) audio.stop()
    }
    /** Set by MainActivity: whether the app is on screen. */
    var onScreen = false
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

    /* ---------------- tutorials (MM-T) ---------------- */

    /** The lesson running now, and its step. */
    var tut by mutableStateOf<Tutorial?>(null)
        private set
    var tutStep by mutableIntStateOf(0)
        private set
    private val tutCtx = TutCtx()
    /** Lessons finished at least once. */
    var tutsDone by mutableStateOf(prefs.getStringSet("tutsDone", emptySet())!!.toSet())
        private set
    private var tutBackup: String? = null
    private var tutBackupFile: String? = null
    private val tutFile get() = File(app.filesDir, "before-lesson.json")

    /** Start a lesson on a practice mess. Your mess is kept in a file until the lesson ends (even if the app is closed). */
    fun startTutorial(t: Tutorial) {
        if (tut == null) {
            val j = project.toJson()
            tutBackup = j
            tutBackupFile = fileId
            try { tutFile.writeText(j); File(app.filesDir, "before-lesson.id").writeText(fileId.orEmpty()) } catch (_: Exception) {}
        }
        if (playing) togglePlay()
        if (holdOn) switchHold()
        keyboards.clear()
        keysMode = false
        overlay = null
        padMenu = -1
        tab = Tab.Pads
        synthView = "simple"
        // lessons point at H, RPT and the rate in the dock, so it opens for them (just this once, not saved)
        dockOpen = true
        replaceProjectQuiet(Project.starter())
        t.setup(this)
        tut = t
        tutStep = 0
        markStep()
        notes.trail("lesson ${t.id}")
    }

    /** Check the current step; called a few times a second while a lesson runs. */
    fun tutTick() {
        val t = tut ?: return
        val s = t.steps.getOrNull(tutStep) ?: return
        if (try { s.done(this, tutCtx) } catch (_: Exception) { false }) {
            if (tutStep + 1 >= t.steps.size) endTutorial(true) else { tutStep++; markStep() }
        }
    }

    fun tutBack() { if (tutStep > 0) { tutStep--; markStep() } }
    fun tutSkip() { val t = tut ?: return; if (tutStep + 1 >= t.steps.size) endTutorial(true) else { tutStep++; markStep() } }

    /** End the lesson and put your own mess back exactly as it was. */
    fun endTutorial(finished: Boolean) {
        val t = tut ?: return
        tut = null
        if (playing) togglePlay()
        if (holdOn) switchHold()
        keyboards.clear()
        keysMode = false
        overlay = null
        restoreAfterLesson()
        if (finished) {
            tutsDone = tutsDone + t.id
            prefs.edit().putStringSet("tutsDone", tutsDone).apply()
            say("${t.title}: done. Your mess is back.")
        } else say("Lesson stopped. Your mess is back.")
    }

    private fun restoreAfterLesson() {
        val j = tutBackup ?: (if (tutFile.exists()) tutFile.readText() else null) ?: return
        val id = tutBackupFile ?: File(app.filesDir, "before-lesson.id").takeIf { it.exists() }?.readText()?.ifEmpty { null }
        replaceProject(Project.fromJson(j, lib::has).project, id)
        tutBackup = null; tutBackupFile = null
        tutFile.delete(); File(app.filesDir, "before-lesson.id").delete()
    }

    private fun markStep() {
        tutCtx.trig0 = engine.triggers
        tutCtx.v0 = version
        tutCtx.bpm0 = project.bpm
        tutCtx.kit0 = project.kit.count { it.isNotEmpty() }
        tutCtx.con0 = console.size
        tutCtx.fav0 = favs.size
    }

    /** The practice mess goes in without touching your file choice. */
    private fun replaceProjectQuiet(p: Project) {
        if (playing) engine.stop()
        project = p
        bank = 0; page = 0
        hist.clear(); hi = -1
        commitUndo()
        version++
        rebuild()
        loadKit()
    }

    /* ---------------- packs (SE-17) ---------------- */

    val packs = Packs(app.filesDir, lib)
    /** What the packs release offers, once fetched; null until asked. */
    var packOffers by mutableStateOf<List<Packs.Offer>?>(null)
    var packNote by mutableStateOf("")
    var packBusy by mutableStateOf("")

    fun refreshPacks() {
        packNote = "Looking for packs…"
        worker.execute {
            try { val l = packs.fetchOffers(); main.post { packOffers = l; packNote = if (l.isEmpty()) "No packs have been sent yet." else "" } }
            catch (e: Exception) { main.post { packNote = "Could not reach the packs: ${e.message ?: "no connection"}" } }
        }
    }

    fun installPack(o: Packs.Offer) {
        if (packBusy.isNotEmpty()) return
        packBusy = o.id
        notes.trail("install pack ${o.id} v${o.version}")
        worker.execute {
            try { packs.install(o); main.post { packBusy = ""; version++; say("${o.name} is in Sounds, in its own jar") } }
            catch (e: Exception) { main.post { packBusy = ""; say("${o.name} did not install: ${e.message ?: "unknown"}") } }
        }
    }

    fun removePack(id: String) {
        packs.remove(id)
        version++
        rebuild()
    }

    init {
        try { packs.loadInstalled() } catch (_: Exception) {}
        // the app closed in the middle of a lesson: put the mess from before it back
        if (tutFile.exists()) main.post { try { restoreAfterLesson(); say("Your mess from before the lesson is back.") } catch (_: Exception) {} }
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
        // a lesson's practice mess is never saved over yours
        if (tut != null) return
        val json = project.toJson()
        worker.execute { try { currentFile.writeText(json) } catch (_: Exception) {} }
    }

    /** Decode any pad sounds that are not ready yet, then let the engine hear them. */
    private fun loadKit() {
        // SE-15: granular synths' sources load like pad sounds
        val need = (project.kit.toList() + project.synths.values.map { it.src }).filter { it.isNotEmpty() && !lib.loaded(it) && lib.has(it) }.distinct()
        if (need.isEmpty()) return
        worker.execute {
            for (k in need) try { lib.load(k) } catch (_: Exception) {}
            main.post { rebuild(); version++ }
        }
    }

    private fun rebuild() {
        // SE-11: the mess's synths show in the Synths jar under their names, so every screen can name them
        for ((k, sp) in project.synths) lib.addSynth(k, sp.name)
        // the wavetables are made once, off the audio thread, as soon as a mess has a wavetable synth
        if (!tablesMade && project.synths.values.any { it.kind == Synth.WAVE }) { tablesMade = true; worker.execute { Synth.warm() } }
        engine.setSnapshot(Engine.Snapshot.build(project, lib, live))
        syncBakes()
    }

    /* ---------------- synths (SE-11) ---------------- */

    private var tablesMade = false

    /** Simple, Full or Synthipad, for this session. */
    var synthView by mutableStateOf("simple")

    /** The synth on a pad, or null. */
    fun synthOn(pad: Int): Synth.Patch? = project.synth(project.kit.getOrNull(pad))

    /** A granular synth's first source: the sound on this pad, else the first sound in the mess, else a long one we ship. */
    private fun defaultGrainSource(pad: Int): String {
        val here = project.kit.getOrNull(pad).orEmpty()
        if (here.isNotEmpty() && !Project.isSynth(here)) return here
        return project.kit.firstOrNull { it.isNotEmpty() && !Project.isSynth(it) } ?: listOf("samples/glass-harp.wav", "samples/tape-wobble-chord.wav").firstOrNull { lib.has(it) }.orEmpty()
    }

    /** The sounds a granular synth can read: every sound on a pad in this mess. */
    fun grainSources(): List<String> = project.kit.filter { it.isNotEmpty() && !Project.isSynth(it) }.distinct()

    /** Point a granular synth at another sound. */
    fun setGrainSource(pad: Int, key: String) {
        val sp = synthOn(pad) ?: return
        edit { sp.src = key }
    }

    /** Put a new synth on a pad (replacing what was there) from a preset, and select it. */
    fun newSynth(pad: Int, preset: Synth.Patch) {
        if (pad !in 0 until Project.NPADS) return
        val key = project.newSynthKey()
        val old = project.kit[pad]
        val made = preset.copy().also { if (it.kind == Synth.GRAIN && it.src.isEmpty()) it.src = defaultGrainSource(pad) }
        edit {
            synths[key] = made
            kit[pad] = key
            if (old.isNotEmpty() && Project.isSynth(old) && kit.none { it == old }) synths.remove(old)
            sel = pad
        }
        notes.trail("synth on pad ${pad + 1}: ${preset.name}")
    }

    /** Turn a knob. Drags pass commit = false until the finger lifts, so one drag is one undo. */
    fun setSynth(pad: Int, id: String, value: Double, commit: Boolean) {
        val sp = synthOn(pad) ?: return
        val old = sp.get(id)
        sp.set(id, value)
        if (old != sp.get(id) || commit) changed(commit)
    }

    /** Load a preset's knobs into a pad's synth (the synth keeps its key, so steps and notes stay). */
    fun loadSynthPreset(pad: Int, preset: Synth.Patch) {
        val k = project.kit.getOrNull(pad) ?: return
        if (!Project.isSynth(k)) return
        val keep = project.synths[k]?.src.orEmpty()
        val made = preset.copy().also { if (it.kind == Synth.GRAIN && it.src.isEmpty()) it.src = keep.ifEmpty { defaultGrainSource(pad) } }
        edit { synths[k] = made }
        notes.trail("synth preset ${preset.name}")
    }

    /** The first empty pad in the bank on screen, or -1. */
    fun emptyPadInBank(): Int = (bank * Project.BANK until (bank + 1) * Project.BANK).firstOrNull { project.kit[it].isEmpty() } ?: -1

    /* ---------------- bake (SE-3) ---------------- */

    // its own low-priority thread, so a bake never holds up loading a sound or the audio
    private val baker = Executors.newSingleThreadExecutor { r -> Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "mess-bake") }
    /** Sounds being baked right now. */
    var baking by mutableStateOf<Set<String>>(emptySet())
        private set

    /** "off", "baking", "baked", "too long" or "loading", for the Sample sheet and the Latency screen. */
    fun bakeState(key: String): String {
        if (!project.edit(key).bake) return "off"
        val s = lib.get(key) ?: return "loading"
        return when { key in baking -> "baking"; s.baked != null -> "baked"; s.seconds() > Bake.MAX_SECONDS -> "too long"; bakedBytes() + bakeBytes(s) > BAKE_BUDGET -> "full"; else -> "baking" }
    }

    /** Memory all bakes together may take: 96 MB, a third or less of what Android gives the app on most phones. */
    private val BAKE_BUDGET = 96L shl 20
    private fun bakeBytes(s: Sample): Long = s.data.size.toLong() * 4 * 2 * Bake.RANGE
    private fun bakedBytes(): Long = project.kit.toSet().sumOf { k -> lib.get(k)?.takeIf { it.baked != null }?.let { bakeBytes(it) } ?: 0L }

    /** Bake what is asked for and not baked yet; drop bakes nobody wants, so their memory comes back. */
    private fun syncBakes() {
        for (k in project.kit.toSet()) {
            if (k.isEmpty()) continue
            val s = lib.get(k) ?: continue
            if (!project.edit(k).bake) { if (s.baked != null && k !in baking) s.baked = null; continue }
            if (s.baked != null || k in baking || s.seconds() > Bake.MAX_SECONDS || bakedBytes() + bakeBytes(s) > BAKE_BUDGET) continue
            baking = baking + k
            baker.execute {
                val t0 = System.nanoTime()
                val out = try { Bake.all(s.data, null) } catch (_: Throwable) { null }
                val ms = (System.nanoTime() - t0) / 1_000_000
                main.post {
                    baking = baking - k
                    if (out != null && project.edit(k).bake) { s.baked = out; notes.trail("baked ${lib.titleOf(k)} in $ms ms") }
                    version++
                }
            }
        }
    }

    /** Pads that needed live grain this session and are not baked: what the Latency screen and the CPU pop-up offer. */
    fun grainPadsToBake(): List<Int> = (0 until Project.NPADS).filter { i ->
        val k = project.kit[i]
        k.isNotEmpty() && engine.grainUsed.getOrElse(i) { false } && !project.edit(k).bake && (lib.get(k)?.seconds() ?: 99.0) <= Bake.MAX_SECONDS
    }

    /** Turn bake on for these pads' sounds. */
    fun bakePads(pads: List<Int>) {
        val keys = pads.map { project.kit[it] }.filter { it.isNotEmpty() }.toSet()
        if (keys.isEmpty()) return
        edit { for (k in keys) setEdit(k, edit(k).copy().also { it.bake = true }) }
        say(if (keys.size == 1) "Baking ${lib.titleOf(keys.first())}" else "Baking ${keys.size} sounds")
    }

    /** SE-3: asked once a session when the phone stays busy and live grain is part of why. */
    var cpuAsk by mutableStateOf(false)
    private var cpuAsked = false
    private var busySince = 0L

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

    fun say(msg: String) { toast = msg; toastUndo = false; toastId++ }
    /** The message after a clear carries an Undo button for its few seconds on screen. */
    var toastUndo by mutableStateOf(false)
    fun sayUndo(msg: String) { toast = msg; toastUndo = true; toastId++ }

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
            if (dt > 50_000_000L) audio.lastSlowFrameNanos = System.nanoTime()
            if (dt > worst) worst = dt
        }
        lastFrame = now
        mouthTick()
        if (winStart == 0L) winStart = now
        if (now - winStart > 10_000_000_000L) {
            val secs = (now - winStart) / 1e9
            uiPerf = "%.0f fps, %d slow frames, worst %d ms".format(frames / secs, slow, worst / 1_000_000)
            // the CPU pop-up: over 70% of each block's time for 20 seconds, with live grain playing and pads to bake
            if (audio.load() > .7 && engine.grainVoices() > 0) { if (busySince == 0L) busySince = now } else busySince = 0L
            if (!cpuAsked && busySince != 0L && now - busySince >= 20_000_000_000L && grainPadsToBake().isNotEmpty()) { cpuAsked = true; cpuAsk = true; notes.trail("cpu pop-up at ${(audio.load() * 100).toInt()}%") }
            winStart = now; frames = 0; slow = 0; worst = 0
        }
        val p = engine.heard()
        if (playing != p.playing) playing = p.playing
        backgroundPlay(playing)
        val st = if (p.playing) p.step else -1
        if (heardStep != st) { heardStep = st; if (recHold.isNotEmpty() && st != recStep) { writeMoves(); rebuild() } }
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

    /** Stop; pressed again while already stopped it hushes everything (the Hush button is gone). */
    fun stop() {
        if (!playing) { engine.stop(); hush("Stopped and hushed: held notes, effects and filters all let go."); return }
        notes.trail("stop"); engine.stop(); playing = false
    }

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

    /**
     * Does this pad's sound last as long as the finger stays down (a synth, Sustain, gate, a looped sound)?
     * On such a pad a long press is playing, not asking for the menu, so holding it never opens the menu:
     * the bank row's button reads Menu for it instead.
     */
    fun soundsWhileHeld(pad: Int): Boolean {
        val k = project.kit.getOrNull(pad).orEmpty()
        if (k.isEmpty()) return false
        return Project.isSynth(k) || project.edit(k).holds()
    }

    fun padDown(d: Int) {
        if (keysMode) {
            val pad = project.sel
            if (project.kit[pad].isEmpty()) { say("Pick a pad with a sound first. The keys play whichever pad you picked last."); return }
            val p = Theory.keyNote(d, project.scale, project.root)
            val ps = Theory.chordOf(p, d, chordMode, project.scale, project.root)
            lastChord = if (ps.size > 1) Theory.chordLabel(p, d, chordMode, project.scale, project.root) + " · " + Theory.chordNotes(ps) else Theory.noteName(p)
            val kid = 2000 + pad * 32 + (p + 12)
            downIds[d] = kid
            notes.trail("key ${Theory.noteName(p)}")
            melodicDown(kid, pad, ps)
            return
        }
        val i = bank * Project.BANK + d
        if (project.sel != i) { project.sel = i; selV++; scheduleSave() }
        if (project.kit[i].isEmpty()) {
            if (emptyRecord) {
                // an empty pad is a record button: tap to start, tap to stop (or hold to record)
                if (!micOn || micPad == i) { askRecord(i); holdRecPad = if (recordHold && micOn) i else -1 }
                return
            }
            overlay = Overlay.Picker(i); return
        }
        notes.trail("pad ${i + 1}")
        // Hold latches grid pads too, under their own ids (1000 + pad), so a latch survives a bank switch
        if (holdOn) { val id = 1000 + i; downIds[d] = id; melodicDown(id, i, intArrayOf(0), mel = false); return }
        downIds[d] = d
        engine.down(d, i, intArrayOf(0), false)
    }

    fun padUp(d: Int) {
        val i = bank * Project.BANK + d
        if (recordHold && holdRecPad == i && micOn && micPad == i) { holdRecPad = -1; bench.stopMic() }
        val id = downIds[d]
        if (id >= 1000) melodicUp(id) else if (id >= 0) engine.up(id)
        downIds[d] = -1
    }

    fun keyLabel(d: Int): String {
        val p = Theory.keyNote(d, project.scale, project.root)
        return Theory.chordLabel(p, d, chordMode, project.scale, project.root)
    }

    fun keyOffset(d: Int): Int = Theory.keyNote(d, project.scale, project.root)

    // ---- MIDI in (MM-12): notes 36 to 51 are pads 1 to 16 of this bank, the rest play the selected pad as keys ----
    private val midiHeld = HashMap<Int, Int>()
    fun midiDown(note: Int) {
        if (note in 36..51) { padDown(note - 36); return }
        val pad = project.sel
        if (project.kit[pad].isEmpty()) { say("MIDI: pick a pad with a sound first; keys play the selected pad."); return }
        val p = (note - 60).coerceIn(-36, 36)
        val kid = 3000 + note
        midiHeld[note] = kid
        melodicDown(kid, pad, intArrayOf(p))
    }
    fun midiUp(note: Int) {
        if (note in 36..51) { padUp(note - 36); return }
        midiHeld.remove(note)?.let { melodicUp(it) }
    }

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
        sayUndo("Pad ${pad + 1} is empty again${if (t.isNotEmpty()) " ($t is back in its jar)" else ""}.")
    }

    fun setEdit(key: String, e: SoundEdit, commit: Boolean) = edit(commit) { this.setEdit(key, e) }

    /* ---------------- steps ---------------- */

    fun toggleStep(key: String, j: Int) {
        notes.trail("step ${lib.titleOf(key)} ${j + 1}")
        val P = project.pat()
        // a step with a pitch or roll notes on it (a dot) clears on tap, note #9
        if (P.hasNoteAt(key, j)) {
            edit { P.notes(key).removeAll { it.s == j }; P.setStep(key, j, false) }
            return
        }
        val on = !P.step(key, j)
        edit { P.setStep(key, j, on) }
        if (on && playOnTap && !playing) preview(key)
    }

    /** Every pitch and roll note in the current loop, for every sound (plain hits stay). */
    fun clearAllNotes() {
        notes.trail("clear all notes")
        edit { for (l in pat().notes.values) l.clear() }
        sayUndo("Pitches and roll notes cleared from loop ${Project.LETTERS[project.cur]}.")
    }

    /** One sound's hits and notes in the current loop. */
    fun clearSoundInLoop(pad: Int) {
        val k = project.kit[pad]
        padMenu = -1
        edit { pat().forget(k) }
        sayUndo("Pad ${pad + 1} is out of loop ${Project.LETTERS[project.cur]}.")
    }

    fun toggleMute(key: String) = edit { if (!mutes.remove(key)) mutes.add(key) }

    /** Where each pad of the bank sits on screen, for carrying a sound onto another pad. */
    val padRects = HashMap<Int, androidx.compose.ui.geometry.Rect>()

    /** Swap two pads' sounds (a held pad carried onto another). The pads keep their own XY, glide and route. */
    fun swapPads(a: Int, b: Int) {
        if (a == b || a !in 0 until Project.NPADS || b !in 0 until Project.NPADS) return
        notes.trail("swap pads ${a + 1} ${b + 1}")
        edit { val t = kit[a]; kit[a] = kit[b]; kit[b] = t }
        if (project.sel == a) selectPad(b, false)
        sayUndo("Pads ${a + 1} and ${b + 1} swapped sounds.")
    }
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

    /** Loop length button: 1, 2, 4, 8 bars, then back to 1. */
    fun toggleLen() = setLen(when (project.pat().len) { 16 -> 32; 32 -> 64; 64 -> 128; else -> 16 })

    /**
     * Loops of 1, 2, 4 or 8 bars. Longer copies what is there to fill the new bars (like the site's
     * one to two); shorter drops the bars past the end.
     */
    fun setLen(n: Int) {
        val P = project.pat()
        val L = Project.LETTERS[project.cur]
        val old = P.len
        if (n == old || n !in listOf(16, 32, 64, 128)) return
        edit {
            if (n > old) {
                for (a in P.steps.values) for (j in old until n) a[j] = a[j % old]
                for (l in P.notes.values) {
                    val base = l.filter { it.s < old }
                    for (rep in 1 until n / old) l.addAll(base.map { Project.Note(it.s + rep * old, it.p, it.l) })
                }
                for (a in P.motion.values) for (j in old until n) a[j] = a[j % old]
            } else {
                for (a in P.steps.values) for (j in n until a.size) a[j] = false
                for (l in P.notes.values) { l.removeAll { it.s >= n }; for (x in l) x.l = minOf(x.l, n - x.s) }
                for (a in P.motion.values) for (j in n until a.size) a[j] = Float.NaN
            }
            P.len = n
        }
        page = page.coerceIn(0, n / 16 - 1)
        val bars = n / 16
        say(if (n > old) "Loop $L is $bars bars now. What was there got copied into the new bars." else "Loop $L is $bars bar${if (bars == 1) "" else "s"} now.")
    }

    /** Clear the roll notes for the picked pad in this loop. */
    fun clearNotes() {
        val k = selKey()
        if (k.isEmpty()) return
        edit { pat().notes.remove(k) }
    }

    /** The site's Hush: everything quiet now, the loop pauses where it is. */
    fun hush(msg: String = "Hushed. Everything is quiet. Play picks up where it paused.") {
        notes.trail("hush")
        if (playing) { engine.pause(); playing = false }
        engine.panic()
        latched.clear()
        fxOn.clear(); fxLive.clear(); xyLive.clear(); sphere = null
        for (p in padFilt.keys.toList()) openPadFilter(p)
        for (d in 0 until 16) downIds[d] = -1
        say(msg)
    }

    /** The site's Bottle it: the loop to four bars (or the song), as a WAV. */
    fun bottleIt() {
        if (project.songMode && project.songLen() > 0) bounce(true, 1)
        else bounce(false, maxOf(1, 4 / (project.pat().len / 16)))
    }

    fun clearSlot() { edit { pat().clear() }; sayUndo("Loop ${Project.LETTERS[project.cur]} cleared.") }

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

    /** Song bar menu: clear it, or put a bar in or take one out (the rest shuffle along). */
    var barMenu by mutableIntStateOf(-1)
    fun clearBar(i: Int) = edit { song[i] = -1 }
    fun insertBar(i: Int) = edit { for (k in song.size - 1 downTo i + 1) song[k] = song[k - 1]; song[i] = -1 }
    fun deleteBar(i: Int) { edit { for (k in i until song.size - 1) song[k] = song[k + 1]; song[song.size - 1] = -1 }; sayUndo("Bar ${i + 1} deleted.") }

    /** Loop letter menu. */
    var slotMenu by mutableIntStateOf(-1)
    fun copySlotToNextEmpty(i: Int) {
        val to = (0 until Project.NSLOTS).firstOrNull { it != i && !project.pats[it].used() } ?: run { say("Every loop is in use."); return }
        edit { pats[to] = pats[i].copy() }
        say("Loop ${Project.LETTERS[i]} copied to ${Project.LETTERS[to]}.")
    }
    fun clearSlotAt(i: Int) { edit { pats[i].clear() }; sayUndo("Loop ${Project.LETTERS[i]} cleared.") }

    /** Steps: the pitch strip under one sound's row, and the step it is setting. */
    var stepStrip by mutableIntStateOf(-1)
    var stepPick by mutableIntStateOf(-1)
    fun openStepStrip(pad: Int) { stepStrip = if (stepStrip == pad) -1 else pad; stepPick = -1; selectPad(pad, false) }

    /** Give one step a pitch (it becomes a one-step roll note), or make it a plain hit again. */
    fun setStepPitch(key: String, j: Int, p: Int?) {
        edit {
            val pt = pat()
            pt.notes(key).removeAll { it.s == j }
            if (p == null) pt.setStep(key, j, true)
            else { pt.setStep(key, j, false); pt.notes(key).add(Project.Note(j, p, 1)) }
        }
        if (p != null) preview(key, p)
        notes.trail("step ${j + 1} pitch ${p?.let { Theory.noteName(it) } ?: "plain"}")
    }

    fun paintSong(i: Int) = edit { song[i] = if (song[i] == brush) -1 else brush }

    fun clearSong() { edit { java.util.Arrays.fill(song, -1); songMode = false }; sayUndo("Song cleared.") }

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
                    // the picture: bank 1's 16 pad colours, and up to 8 rows of its first loop with steps in it
                    val kit = Json.list(m["kit"]).map { (it as? String) ?: "" }
                    val colors = (0 until 16).map { k -> kit.getOrNull(k)?.takeIf { it.isNotEmpty() }?.let { lib.colorOf(it) } ?: 0 }
                    val first = pats.map { Json.map(it) }.firstOrNull { pm -> Json.map(pm["steps"]).values.any { (it as? String)?.contains('1') == true } }
                    val grid = first?.let { pm -> Json.map(pm["steps"]).values.mapNotNull { it as? String }.filter { it.contains('1') }.take(8).map { it.take(16) } } ?: emptyList()
                    MessFile(
                        f.name.removeSuffix(".json"), Json.str(m["name"], "Untitled mess"), Json.num(m["bpm"], 112.0).toInt(),
                        Json.str(m["at"], ""), kit.count { it.isNotEmpty() }, loops,
                        Json.str(m["song"], "").trimEnd('-').length, colors, grid,
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
        is Overlay.Xy -> "XY setup (pad ${o.pad + 1})"
        is Overlay.Doctor -> "doctor (${lib.titleOf(o.key)})"
        Overlay.Files -> "messes"
        Overlay.Settings -> "settings"
        Overlay.Tempo -> "tempo"
        Overlay.Notes -> "notes"
        Overlay.Packs -> "packs"
        Overlay.Learn -> "learn"
        Overlay.Mouth -> "mouth control"
        Overlay.AudioCheck -> "latency"
    }

    // ---- Audio check (MM-1): where the time goes between a tap and the sound ----
    private val touchLags = IntArray(32)
    private var touchN = 0
    /** Result of the speaker test in ms (median of the clicks heard), or -1. */
    var speakerMs by mutableStateOf(-1.0)
    var speakerNote by mutableStateOf("")

    /** A pad or the check's tap pad was touched: how long the touch took to reach the app. */
    fun noteTouch(eventUptimeMs: Long) {
        val d = android.os.SystemClock.uptimeMillis() - eventUptimeMs
        if (d in 0..1000) { touchLags[touchN and 31] = d.toInt(); touchN++ }
    }

    /** Middle of the last touches, in ms, or -1 before any. */
    fun touchMs(): Int {
        val n = minOf(touchN, 32)
        if (n == 0) return -1
        return touchLags.copyOf(n).sorted()[n / 2]
    }

    fun lagText(): String {
        val out = if (audio.outputMs >= 0) "%.0f ms".format(audio.outputMs) else "not known yet"
        val wait = if (engine.hits > 0) "%.1f ms".format(engine.lastHitWaitNanos / 1e6) else "no hits yet"
        val touch = touchMs().let { if (it >= 0) "$it ms (${minOf(touchN, 32)} taps)" else "no taps yet" }
        val spk = if (speakerMs >= 0) "%.0f ms".format(speakerMs) else "not run"
        val buf = "%.1f".format(audio.ms(audio.bufferFrames))
        return "output $out by Android's clock, buffer $buf ms, last hit waited $wait for a block, touch to app $touch, speaker test $spk, buffer grown ${audio.grown} and given back ${audio.shrunk}, steady CPU ${audio.cpuHint}"
    }

    /** A short click for the tap pad and the speaker test: 3 ms of 2 kHz, loud enough for the mic. */
    val clickSample: com.tideclock.messmachine.engine.Sample by lazy {
        val n = rate * 3 / 1000
        val d = FloatArray(n) { k -> (Math.sin(2 * Math.PI * 2000.0 * k / rate) * .9 * (1 - k.toDouble() / n)).toFloat() }
        com.tideclock.messmachine.engine.Sample("check-click", d, rate)
    }

    fun checkClick() { engine.preview(clickSample, null, 0, false) }

    fun runSpeakerTest() {
        if (speakerNote == "Listening...") return
        speakerNote = "Listening..."
        Thread({
            val r = SpeakerTest.run(engine, rate, clickSample)
            main.post {
                speakerMs = r.first
                speakerNote = r.second
                notes.trail("speaker test ${r.second}")
            }
        }, "speaker-test").start()
    }

    fun audioCheckSummary(): String = "Audio check on build $buildName. ${lagText()}. Long blocks: ${audio.longBlocksText()}. Engine ${audio.loadText()}, ${audio.underruns} underruns."

    fun switchGuide(v: Boolean) { guideOn = v; prefs.edit().putBoolean("guideOn", v).apply() }

    /**
     * Device, audio and mess facts that go under a note. The repo is public (MM-18): the mess summary goes only
     * with bug notes and crashes, and never the mess's name. Device and audio stay, since lag depends on them.
     */
    fun noteDetails(cat: String = "bug", crashed: Boolean = false): String {
        val p = project
        val loops = (0 until Project.NSLOTS).count { p.pats[it].used() }
        return "**Device:** ${NotesBook.device()}\n" +
            "**Audio:** ${rate} Hz, buffer ${audio.bufferFrames} frames, burst ${burst}, underruns ${audio.underruns}, engine load ${audio.loadText()}\n" +
            "**Lag:** ${lagText()}\n" +
            "**Long audio blocks:** ${audio.longBlocksText()}\n" +
            "**Screen:** $uiPerf\n" +
            (if (cat != "bug" && !crashed) "" else "**Mess:** ${p.bpm} BPM, swing ${p.swing}%, ${p.loadedCount()} pads, $loops loops, " +
            (if (p.songMode) "song mode (${p.songLen()} bars)" else "loop ${Project.LETTERS[p.cur]}") +
            ", keys ${if (keysMode) "on" else "off"}, chords $chordMode, repeat ${if (live.nr) "on (${live.arp})" else "off"}\n")
    }

    fun saveNote(cat: String, topic: String, text: String, send: Boolean) {
        val (c, tp, tx) = notes.parsePrefix(text, cat, topic)
        if (tx.isBlank() && crash == null) { say("Write or say something first."); return }
        val n = MNote(NotesBook.newId(), c, tp.trim(), tx, System.currentTimeMillis(), buildName, screenName(), extra = crash ?: "")
        crash = null
        notes.add(n)
        notes.trail("note ${catLabel(c)}")
        if (send) notes.send(n, noteDetails(n.cat, n.extra.isNotEmpty())) else say("Saved on the phone. Send it when you are ready.")
    }

    fun sendNote(n: MNote) = notes.send(n, noteDetails(n.cat, n.extra.isNotEmpty()))

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

    /** From the Synth tab: this pad's Padiano opens under its row on the pad grid, and the grid shows (note #11). */
    fun padianoOnGrid(pad: Int) {
        if (project.kit[pad].isEmpty()) return
        keysMode = false
        if (pad !in keyboards) toggleKeyboard(pad) else bank = pad / Project.BANK
        tab = Tab.Pads
    }

    /** View size, so more fits on the screen. 1 = normal. */
    var uiScale by mutableFloatStateOf(prefs.getFloat("uiScale", 1f))
        private set

    fun setViewSize(v: Float) {
        uiScale = v.coerceIn(.6f, 1.3f)
        prefs.edit().putFloat("uiScale", uiScale).apply()
    }

    /** Padiano note ids: 10000 + pad * 200 + (pitch + 96), so pitch -96..103 never collide (extra rows reach high). */
    fun pianoId(pad: Int, p: Int) = 10000 + pad * 200 + (p + 96)

    /* ---------------- Padiano settings ---------------- */

    /** Squares (one row of small squares, the default) or the Keys piano. */
    var padianoKeys by mutableStateOf(prefs.getBoolean("padKeys", false))
        private set
    /** How many semitones the Squares show, 7 to 36. */
    var padianoN by mutableIntStateOf(prefs.getInt("padN", 13).coerceIn(7, 36))
        private set
    /** Height: 0 small, 1 medium, 2 large, 3 extra large (taller, and the squares a bit wider). */
    var padianoSize by mutableIntStateOf(prefs.getInt("padSize", 1).coerceIn(0, 3))
        private set
    /** Rows of notes on each Padiano, 1 to 3: each row up is the same notes an octave higher. */
    var padianoRows by mutableIntStateOf(prefs.getInt("padRows", 1).coerceIn(1, 3))
        private set
    /** Lowest note shown on each pad's Padiano (moved an octave at a time with ◀ ▶). */
    val padianoBase = mutableStateMapOf<Int, Int>()

    fun switchPadianoKeys(v: Boolean) { padianoKeys = v; prefs.edit().putBoolean("padKeys", v).apply() }
    fun changePadianoN(n: Int) { padianoN = n.coerceIn(7, 36); prefs.edit().putInt("padN", padianoN).apply() }
    fun changePadianoSize(n: Int) { padianoSize = n.coerceIn(0, 3); prefs.edit().putInt("padSize", padianoSize).apply() }
    fun changePadianoRows(n: Int) { padianoRows = n.coerceIn(1, 3); prefs.edit().putInt("padRows", padianoRows).apply(); notes.trail("padiano rows $padianoRows") }
    fun shiftPadiano(pad: Int, by: Int) { padianoBase[pad] = ((padianoBase[pad] ?: 0) + by).coerceIn(-36, 24) }

    /** Chords can play, or just show their notes on the piano so you can play them yourself. */
    var chordPlay by mutableStateOf(prefs.getBoolean("chordPlay", true))
        private set

    fun switchChordPlay(v: Boolean) { chordPlay = v; prefs.edit().putBoolean("chordPlay", v).apply() }

    /** The notes the chord mode builds on a key, folded into the piano's two octaves. */
    fun chordFor(p: Int): IntArray {
        // notes past the roll's two octaves take the chord of the same note folded in, moved by octaves
        val f = Theory.foldP(p)
        if (f != p) return chordFor(f).map { it + (p - f) }.toIntArray()
        val strip = Theory.stripNotes(project.scale, project.root)
        val idx = strip.indexOf(p)
        return if (chordMode.startsWith("set:") && idx < 0) intArrayOf(p) else Theory.chordOf(p, idx.coerceAtLeast(0), chordMode, project.scale, project.root)
    }

    /* ---------------- mouth control: the mic as a hands-free XY ---------------- */

    val mouthMic = MouthMic()
    /** Mouth control is listening. */
    var mouthOn by mutableStateOf(false)
        private set
    /** What the vowel moves (dark "ooo" to bright "eee"), and what loudness moves; -1 is nothing. */
    var mouthVowelTo by mutableIntStateOf(prefs.getInt("mouthX", Engine.T_FILTER))
        private set
    var mouthLevelTo by mutableIntStateOf(prefs.getInt("mouthY", -1))
        private set
    /** When you go quiet: true keeps the last place (like Stay), false lets go (springs back). */
    var mouthStay by mutableStateOf(prefs.getBoolean("mouthStay", true))
        private set
    /** The gate in dB: quieter than this is not you. */
    var mouthGate by mutableFloatStateOf(prefs.getFloat("mouthGate", -46f))
        private set
    /** What the meter shows: vowel 0..1, level 0..1, and whether you are making a sound. */
    var mouthNow by mutableStateOf(Triple(0f, 0f, false))
        private set
    /** You are making a sound right now (its own state, so the dock doesn't redraw for every wobble of the meter). */
    var mouthHeard by mutableStateOf(false)
        private set
    private var mouthPadNow = -1
    private var mouthLast = 0f to 0f

    /** The mouth's own two-way XY: X is the vowel, Y the loudness. Filter (and anything set to turn down) closes as
     * you go dark or quiet, so "ooo" is a closed filter and "eee" an open one, the way a wah sounds. */
    private fun mouthSetup(): XySetup {
        fun dir(t: Int) = if (t < 0) XyDir() else XyDir(t, xyTarget(t).def)
        return XySetup("Mouth", false, listOf(dir(mouthVowelTo), dir(mouthLevelTo), XyDir(), XyDir()), spring = !mouthStay)
    }
    private fun mouthAmount(t: Int, v: Float): Float = if (t == Engine.T_FILTER || xyTarget(t).def < 0) 1f - v else v

    fun switchMouth(v: Boolean = !mouthOn) {
        if (v == mouthOn) return
        if (!v) {
            mouthMic.stop()
            mouthOn = false
            mouthHeard = false
            mouthRelease()
            notes.trail("mouth off")
            return
        }
        if (micOn) { say("The mic is recording onto a pad. Stop that first."); return }
        val ok = androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!ok) { askMicFor = MIC_FOR_MOUTH; return }
        mouthMic.tracker.gateDb = mouthGate
        if (!mouthMic.start()) { say("The mic would not open. Another app may be using it."); return }
        mouthOn = true
        notes.trail("mouth on, vowel moves ${xyTarget(mouthVowelTo).name}, loudness moves ${xyTarget(mouthLevelTo).name}")
        if (project.kit[project.sel].isEmpty()) say("Mouth control moves the pad you picked. Pick a pad with a sound.")
    }

    fun pickMouthVowel(t: Int) { mouthRelease(); mouthVowelTo = t; prefs.edit().putInt("mouthX", t).apply() }
    fun pickMouthLevel(t: Int) { mouthRelease(); mouthLevelTo = t; prefs.edit().putInt("mouthY", t).apply() }
    fun switchMouthStay(v: Boolean) { mouthStay = v; prefs.edit().putBoolean("mouthStay", v).apply(); if (!v) mouthRelease() }
    fun changeMouthGate(db: Float) { mouthGate = db.coerceIn(-70f, -20f); mouthMic.tracker.gateDb = mouthGate; prefs.edit().putFloat("mouthGate", mouthGate).apply() }

    /** Let go of the pad the mouth was moving (it springs back, or keeps the last place with Stay). */
    private fun mouthRelease() {
        val pad = mouthPadNow
        if (pad < 0) return
        mouthPadNow = -1
        xyMove(pad, mouthLast.first, mouthLast.second, false, setup = mouthSetup().copy(spring = true))
    }

    /** Every frame while listening: the mouth moves the picked pad (and Rec, while playing, records it). */
    private fun mouthTick() {
        if (!mouthOn) return
        val t = mouthMic.tracker
        if (!mouthMic.running) { mouthOn = false; mouthRelease(); say("The mic stopped."); return }
        mouthApply(t.vowel, t.level, t.on)
    }

    /** One reading of the mouth (vowel 0..1, level 0..1, making a sound or not) moves the picked pad. Tests call it too. */
    fun mouthApply(vw: Float, lv: Float, heard: Boolean) {
        if (overlay == Overlay.Mouth && (mouthNow.first != vw || mouthNow.second != lv || mouthNow.third != heard)) mouthNow = Triple(vw, lv, heard)
        if (mouthHeard != heard) mouthHeard = heard
        val pad = project.sel
        if (mouthPadNow >= 0 && mouthPadNow != pad) mouthRelease()
        if (project.kit.getOrNull(pad).isNullOrEmpty()) return
        if (heard) {
            val fx = if (mouthVowelTo < 0) 0f else mouthAmount(mouthVowelTo, vw)
            val fy = if (mouthLevelTo < 0) 0f else mouthAmount(mouthLevelTo, lv)
            mouthLast = fx to fy
            mouthPadNow = pad
            xyMove(pad, fx, fy, true, setup = mouthSetup())
        } else if (mouthPadNow >= 0 && !mouthStay) mouthRelease()
    }

    /* ---------------- repeat patterns: which repeats play ---------------- */

    /** Your own pattern, kept for next time (x plays, . rests), 2 to 16 long. */
    var ownPattern by mutableStateOf(prefs.getString("nrPatOwn", "xx.x..x.")!!.filter { it == 'x' || it == '.' }.take(16).ifEmpty { "xx." })
        private set

    init { live.nrPat = prefs.getString("nrPat", "")!!.filter { it == 'x' || it == '.' }.take(16) }

    /** Pick repeat's pattern ("" plays every repeat). Turns Repeat on, since a pattern is for hearing. */
    fun setRepeatPattern(p: String) {
        val clean = p.filter { it == 'x' || it == '.' }.take(16)
        prefs.edit().putString("nrPat", clean).apply()
        notes.trail("repeat pattern ${clean.ifEmpty { "all" }}")
        setLive { nrPat = clean; if (clean.isNotEmpty()) nr = true }
    }

    /** Change your own pattern: tap a step to flip it, or make it longer or shorter. It plays as you change it. */
    fun editOwnPattern(p: String) {
        val clean = p.filter { it == 'x' || it == '.' }.take(16)
        if (clean.length < 2) return
        ownPattern = clean
        prefs.edit().putString("nrPatOwn", clean).apply()
        setRepeatPattern(clean)
    }

    /* ---------------- hold: the first N notes keep sounding ---------------- */

    /** Hold on or off: one tap, from the hold bar or any piano. */
    var holdOn by mutableStateOf(prefs.getBoolean("holdOn", false))
        private set
    /** How many notes hold after you lift your finger, 1 to 8, or 0 = Last: only the newest note holds. */
    var holdCount by mutableIntStateOf(prefs.getInt("holdN", 3).coerceIn(0, 8))
        private set
    /** Notes that can hold right now: 0 when Hold is off, 1 in Last mode. */
    val holdSlots: Int get() = if (!holdOn) 0 else if (holdCount == 0) 1 else holdCount
    val holdLast: Boolean get() = holdOn && holdCount == 0
    /** Notes holding right now, as engine ids (piano ids carry their pad and pitch). */
    val latched = mutableStateListOf<Int>()
    private val swallowUp = HashSet<Int>()

    fun changeHoldCount(n: Int) {
        holdCount = n.coerceIn(0, 8)
        prefs.edit().putInt("holdN", holdCount).apply()
        while (latched.size > holdSlots) { engine.unlatch(latched.removeAt(0)) }
    }

    fun switchHold(v: Boolean = !holdOn) {
        holdOn = v
        prefs.edit().putBoolean("holdOn", v).apply()
        notes.trail("hold ${if (v) "on" else "off"}")
        if (!v) releaseHolds()
    }

    fun releaseHolds() {
        for (id in latched) engine.unlatch(id)
        latched.clear()
    }

    /** Pitches holding on one pad's piano, for drawing. */
    fun heldPitches(pad: Int): Set<Int> = latched.filter { it >= 10000 && (it - 10000) / 200 == pad }.map { (it - 10000) % 200 - 96 }.toSet()

    /** A melodic press with hold rules: a held note pressed again lets go; a free slot holds; otherwise it plays normally. */
    private fun melodicDown(id: Int, pad: Int, ps: IntArray, mel: Boolean = true) {
        if (id in latched) {
            latched.remove(id)
            engine.unlatch(id)
            swallowUp.add(id)
            return
        }
        if (holdLast) {
            // Last: the newest note takes over the hold
            for (o in latched) engine.unlatch(o)
            latched.clear()
            latched.add(id)
            engine.down(id, pad, ps, mel, true)
            return
        }
        val latch = latched.size < holdSlots
        if (latch) latched.add(id)
        engine.down(id, pad, ps, mel, latch)
    }

    private fun melodicUp(id: Int) {
        if (swallowUp.remove(id)) return
        engine.up(id)
    }

    fun pianoDown(pad: Int, p: Int) {
        if (project.kit[pad].isEmpty()) return
        val ps = chordFor(p)
        val idx = Theory.stripNotes(project.scale, project.root).indexOf(Theory.foldP(p)).coerceAtLeast(0)
        lastChord = if (ps.size > 1) Theory.chordLabel(p, idx, chordMode, project.scale, project.root) + " · " + Theory.chordNotes(ps) +
            (if (!chordPlay) " (showing)" else "") else Theory.noteName(p)
        notes.trail("piano ${pad + 1} ${Theory.noteName(p)}")
        melodicDown(pianoId(pad, p), pad, if (chordPlay) ps else intArrayOf(p))
    }

    fun pianoUp(pad: Int, p: Int) = melodicUp(pianoId(pad, p))

    /* ---------------- perform FX ---------------- */

    fun fx(name: String, on: Boolean) = fxPress(name, on)

    fun xy(x: Float, y: Float, on: Boolean) = engine.xy(x, y, on)

    /* ---------------- M(ess)(y) Desk: layouts you arrange freely ---------------- */

    /** One saved desk layout. */
    data class Desk(val name: String, val mods: List<Module>)

    /** Every saved layout; the one showing is deskIdx, and its pieces are in console. */
    val desks = mutableStateListOf<Desk>()
    var deskIdx by mutableIntStateOf(0)
        private set

    /** The pieces on the desk showing now, back to front (declared after project and lib: the presets read them). */
    val console = mutableStateListOf<Module>().also { l ->
        val saved = prefs.getString("desks", null)
        if (saved != null) {
            for (part in saved.split("##")) {
                val name = part.substringBefore("~")
                val mods = part.substringAfter("~", "").split(";").mapNotNull { Module.parse(it) }
                desks.add(Desk(name, layoutStack(mods)))
            }
        }
        if (desks.isEmpty()) {
            // the first desk keeps the console you already built
            val old = prefs.getString("console", null)?.split(";")?.mapNotNull { Module.parse(it) }
            desks.add(Desk("Perform", layoutStack(if (old.isNullOrEmpty()) performPreset() else old)))
            desks.add(Desk("Beats", layoutStack(beatsPreset())))
            desks.add(Desk("Sampling", layoutStack(samplingPreset())))
        }
        deskIdx = prefs.getInt("deskIdx", 0).coerceIn(0, desks.size - 1)
        l.addAll(desks[deskIdx].mods)
    }
    var consoleEdit by mutableStateOf(false)

    /** A first melodic pad for the presets' Padiano and filter. */
    private fun firstTonalPad(): Int {
        val tonal = setOf("bass", "keys", "lead", "pad")
        val i = (0 until Project.NPADS).firstOrNull { project.kit[it].isNotEmpty() && lib.sound(project.kit[it])?.type in tonal }
        return i ?: (0 until 8).lastOrNull { project.kit[it].isNotEmpty() } ?: 0
    }

    /** The Perform layout: pads, Hold, a Padiano with its filter, the effects, the filter pad, repeat, loops. */
    fun performPreset(): List<Module> {
        val t = firstTonalPad()
        return listOf(
            Module("pads"), Module("hold"), Module("piano", t), Module("pfilt", t),
            Module("fx"), Module("xy", size = 0, half = true), Module("repeat", half = true), Module("loops"),
        )
    }

    fun beatsPreset(): List<Module> = listOf(
        Module("loops"), Module("pads"), Module("repeat", half = true), Module("beat", half = true),
        Module("fx1", 0, half = true), Module("fx1", 3, half = true), Module("xy", size = 0),
    )

    fun samplingPreset(): List<Module> {
        val t = firstTonalPad()
        return listOf(Module("pads"), Module("piano", t), Module("xyp", t, half = true), Module("pfilt", t, half = true), Module("hold"))
    }

    /** How tall a piece starts, in desk rows (24 dp each). */
    fun defaultH(m: Module, w: Int): Int = when (m.type) {
        "pads" -> if (w >= 9) 16 else 10
        "row" -> 4
        "piano" -> 12
        "pfilt" -> 4
        "hold" -> if (w >= 9) 4 else 6
        "fx" -> if (w >= 9) 10 else 14
        "fx1" -> listOf(3, 4, 6)[m.size]
        "xy", "xyp" -> listOf(8, 10, 13)[m.size]
        "repeat", "loops", "beat" -> 2
        "scale" -> 4
        "transport" -> 3
        else -> 4
    }

    /** Pieces without a place get stacked top to bottom, half-width ones in pairs. */
    private fun layoutStack(mods: List<Module>): List<Module> {
        var y = mods.filter { it.x >= 0 }.maxOfOrNull { it.y + it.h } ?: 0
        val out = ArrayList<Module>()
        var halfAt = -1
        var halfH = 0
        for (m in mods) {
            if (m.x >= 0) { out.add(m); continue }
            val w = if (m.half) 6 else 12
            val h = defaultH(m, w)
            if (m.half && halfAt >= 0) {
                out.add(m.copy(x = 6, y = halfAt, w = w, h = h))
                y = halfAt + maxOf(halfH, h); halfAt = -1
            } else if (m.half) {
                out.add(m.copy(x = 0, y = y, w = w, h = h)); halfAt = y; halfH = h; y += h
            } else {
                if (halfAt >= 0) halfAt = -1
                out.add(m.copy(x = 0, y = y, w = w, h = h)); y += h
            }
        }
        return out
    }

    private fun saveConsole() {
        if (deskIdx in desks.indices) desks[deskIdx] = desks[deskIdx].copy(mods = console.toList())
        prefs.edit().putString("desks", desks.joinToString("##") { d -> d.name.replace("~", " ").replace("#", " ") + "~" + d.mods.joinToString(";") { it.code() } })
            .putInt("deskIdx", deskIdx).apply()
    }

    fun switchDesk(i: Int) {
        if (i !in desks.indices) return
        saveConsole()
        deskIdx = i
        console.clear(); console.addAll(desks[i].mods)
        saveConsole()
        notes.trail("desk ${desks[i].name}")
    }

    /** From the dock: the next layout, and the Desk page if you are somewhere else (and not locked). */
    fun nextDesk() { switchDesk((deskIdx + 1) % desks.size); if (!locked) tab = Tab.Console }

    /** Keep the desk you have as a new saved desk, so you can change this one and still go back. */
    fun copyDesk() {
        saveConsole()
        val d = desks[deskIdx]
        desks.add(Desk((d.name + " copy").take(24), console.toList()))
        switchDesk(desks.size - 1)
        say("Saved a copy: ${desks[deskIdx].name}. Rename it while arranging.")
    }

    fun renameDesk(name: String) {
        if (deskIdx !in desks.indices) return
        val n = name.replace("~", " ").replace("#", " ").replace(";", " ").trim().take(24)
        if (n.isEmpty()) return
        desks[deskIdx] = desks[deskIdx].copy(name = n)
        saveConsole()
    }

    /** Throw a saved desk away (the last one always stays). */
    fun deleteDesk() {
        if (desks.size <= 1) return
        val gone = desks[deskIdx].name
        desks.removeAt(deskIdx)
        deskIdx = (deskIdx - 1).coerceAtLeast(0)
        console.clear(); console.addAll(desks[deskIdx].mods)
        saveConsole()
        say("Desk $gone is gone.")
    }

    fun newDesk() {
        saveConsole()
        desks.add(Desk("Desk ${desks.size + 1}", layoutStack(performPreset())))
        switchDesk(desks.size - 1)
    }

    fun addModule(type: String) {
        val pad = when (type) {
            "piano", "pfilt", "xyp" -> project.sel.takeIf { project.kit[it].isNotEmpty() } ?: firstTonalPad()
            "row", "fx1" -> 0
            else -> -1
        }
        val m = Module(type, pad)
        val y = console.maxOfOrNull { it.y + it.h } ?: 0
        console.add(m.copy(x = 0, y = y, w = 12, h = defaultH(m, 12)))
        saveConsole()
        notes.trail("desk add $type")
    }

    fun changeModule(i: Int, m: Module) { if (i in console.indices) { console[i] = m; saveConsole() } }

    /** Arrange mode: drop a piece at a grid spot (it comes to the front). */
    fun placeModule(i: Int, x: Int, y: Int) {
        if (i !in console.indices) return
        val m = console.removeAt(i)
        console.add(m.copy(x = x.coerceIn(0, 12 - m.w), y = y.coerceAtLeast(0)))
        saveConsole()
    }

    fun resizeModule(i: Int, w: Int, h: Int) {
        if (i !in console.indices) return
        val m = console[i]
        console[i] = m.copy(w = w.coerceIn(2, 12 - m.x), h = h.coerceIn(2, 40))
        saveConsole()
    }

    fun pinModule(i: Int) { if (i in console.indices) changeModule(i, console[i].copy(pinned = !console[i].pinned)) }

    fun moveModule(i: Int, by: Int) {
        val j = i + by
        if (i !in console.indices || j !in console.indices) return
        val m = console.removeAt(i)
        console.add(j, m)
        saveConsole()
    }

    fun removeModule(i: Int) { if (i in console.indices) { console.removeAt(i); saveConsole() } }

    fun resetConsole() {
        val name = desks.getOrNull(deskIdx)?.name ?: "Perform"
        val mods = when (name) { "Beats" -> beatsPreset(); "Sampling" -> samplingPreset(); else -> performPreset() }
        console.clear(); console.addAll(layoutStack(mods)); saveConsole()
        say("$name is back to how it started.")
    }

    /* ---------------- motion recording ---------------- */

    /** XY values held by a finger while Rec is on, written into the loop on every step. */
    private val recHold = HashMap<Pair<Int, Int>, Float>()
    private var recStep = -1

    private fun recordMoves(pad: Int, vals: Map<Int, Float>, used: Set<Int>, on: Boolean) {
        if (!live.rec || !playing || heardPat < 0) return
        for (t in used) recHold[pad to t] = vals[t] ?: 0f
        writeMoves()
        if (!on) {
            for (t in used) recHold.remove(pad to t)
            changed(true)
        }
    }

    private fun writeMoves() {
        if (recHold.isEmpty() || heardStep < 0 || heardPat < 0) return
        val P = project.pats[heardPat]
        for ((k, v) in recHold) P.setMotion(k.first, k.second, heardStep, v)
        recStep = heardStep
    }

    fun clearMoves() { edit { pat().motion.clear() }; sayUndo("XY moves cleared from loop ${Project.LETTERS[project.cur]}.") }


    /* ---------------- a live filter on one pad ---------------- */

    /** Where each pad's filter sits (cut 0..1, res 0..1) while it is closed; absent = open. */
    val padFilt = mutableStateMapOf<Int, Pair<Float, Float>>()
    /** Pads whose filter stays put after the finger lifts. */
    val padFiltStay = mutableStateListOf<Int>()

    fun padFilter(pad: Int, cut: Float, res: Float, on: Boolean) {
        if (on) {
            padFilt[pad] = cut to res
            engine.padFilter(pad, cut, res, true)
        } else if (pad !in padFiltStay) {
            padFilt.remove(pad)
            engine.padFilter(pad, 1f, 0f, false)
        }
    }

    fun switchFiltStay(pad: Int) {
        if (pad in padFiltStay) { padFiltStay.remove(pad); padFilt.remove(pad); engine.padFilter(pad, 1f, 0f, false) }
        else padFiltStay.add(pad)
    }

    fun openPadFilter(pad: Int) { padFiltStay.remove(pad); padFilt.remove(pad); engine.padFilter(pad, 1f, 0f, false) }

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

    /** Lock also pins the screen through Android (it asks each time). */
    var lockPin by mutableStateOf(prefs.getBoolean("lockPin", true))
        private set

    fun lock() {
        overlay = null
        padMenu = -1
        consoleEdit = false
        locked = true
        notes.trail("lock")
        say("Locked. Playing still works. Hold the lock for a second to unlock.")
    }

    fun unlock() { locked = false; notes.trail("unlock") }
    fun switchLockPin(v: Boolean) { lockPin = v; prefs.edit().putBoolean("lockPin", v).apply() }

    /* ---------------- the side dock ---------------- */

    /** What sits on the dock, top to bottom. */
    val dockItems = mutableStateListOf<String>().also { l ->
        l.addAll((prefs.getString("dock", null) ?: "lock,hold,filter,repeat").split(",").filter { x -> DOCK_TYPES.any { it.first == x } })
    }
    var dockOpen by mutableStateOf(prefs.getBoolean("dockOpen2", false))
        private set
    /** The dock's filter acts on the whole mix (-1) or on one pad. */
    var dockTarget by mutableIntStateOf(-1)
    var dockStay by mutableStateOf(false)
    var dockCut by mutableFloatStateOf(1f)
    var dockRes by mutableFloatStateOf(0f)

    fun switchDockOpen(v: Boolean) { dockOpen = v; prefs.edit().putBoolean("dockOpen2", v).apply() }
    fun toggleDockItem(t: String) {
        if (!dockItems.remove(t)) dockItems.add(t)
        prefs.edit().putString("dock", dockItems.joinToString(",")).apply()
    }

    /** The dock's filter sliders moved (on) or were let go (off: springs open unless Stay). */
    fun dockFilter(on: Boolean) {
        if (!on && !dockStay) { dockCut = 1f; dockRes = 0f }
        val live = on || dockStay
        if (dockTarget < 0) engine.xy(dockCut, 1f - dockRes, live && (dockCut < .995f || dockRes > .005f))
        else if (live) padFilter(dockTarget, dockCut, dockRes, true) else openPadFilter(dockTarget)
    }

    fun cycleDockTarget() {
        val t = dockTarget
        if (t >= 0) { if (t !in padFiltStay) openPadFilter(t) } else engine.xy(1f, 1f, false)
        dockTarget = if (t < 0 && project.kit[project.sel].isNotEmpty()) project.sel else -1
        dockCut = 1f; dockRes = 0f
    }

    /* ---------------- perform effects you can edit ---------------- */

    /** How one perform effect behaves: amount 0..1, held / stays / slider, where it acts, and the gate's length in steps. */
    data class FxSet(val amount: Float = 1f, val behave: String = "held", val target: Int = -1, val steps: Int = 1)

    val fxSets = mutableStateMapOf<String, FxSet>().also { m ->
        for (part in (prefs.getString("fxsets", "") ?: "").split(";")) {
            val f = part.split(":")
            if (f.size == 5) try { m[f[0]] = FxSet(f[1].toFloat(), f[2], f[3].toInt(), f[4].toInt()) } catch (_: Exception) {}
        }
    }
    /** Effects switched on and staying on (behave = stays or slider), and the one being edited. */
    val fxOn = mutableStateListOf<String>()
    var fxEditing by mutableStateOf<String?>(null)
    /** Live amount while a finger slides along an effect. */
    val fxLive = mutableStateMapOf<String, Float>()

    fun fxSet(name: String): FxSet = fxSets[name] ?: FxSet()

    fun changeFx(name: String, s: FxSet) {
        val was = fxSet(name)
        if (name in fxOn && was.target != s.target) { sendFx(name, was, false, 0f); fxOn.remove(name) }
        fxSets[name] = s
        prefs.edit().putString("fxsets", fxSets.entries.joinToString(";") { (k, v) -> "$k:${v.amount}:${v.behave}:${v.target}:${v.steps}" }).apply()
        if (name == "gate") engine.gateLength(s.steps)
    }

    private fun sendFx(name: String, s: FxSet, on: Boolean, amt: Float) {
        if (s.target < 0) {
            engine.fxAmount(name, amt)
            engine.fx(name, on)
        } else engine.fxTo(name, s.target, if (on) amt.coerceAtLeast(.01f) else 0f)
    }

    /** A finger on an effect: on = down/up, amount = where the finger is (null = the set amount). */
    fun fxPress(name: String, on: Boolean, amount: Float? = null) {
        val s = fxSet(name)
        if (on && (name == "stutter" || name == "tape") && !playing && s.target < 0) { say("Stutter and tape stop work while the loop plays."); return }
        val amt = amount ?: s.amount
        when (s.behave) {
            "stays" -> if (on) {
                if (name in fxOn) { fxOn.remove(name); sendFx(name, s, false, 0f) }
                else { fxOn.add(name); sendFx(name, s, true, amt); notes.trail("fx $name on") }
            }
            "slider" -> {
                if (amount != null) {
                    fxLive[name] = amount
                    if (amount > .02f) { if (name !in fxOn) fxOn.add(name); sendFx(name, s, true, amount) }
                    else { fxOn.remove(name); sendFx(name, s, false, 0f) }
                }
            }
            else -> {
                if (on) notes.trail("fx $name")
                sendFx(name, s, on, amt)
                if (!on) fxLive.remove(name)
            }
        }
    }

    /** The finger slid along a held effect: change how much it does, live. */
    fun fxSlide(name: String, amount: Float) {
        val s = fxSet(name)
        fxLive[name] = amount
        if (s.behave == "slider") { fxPress(name, true, amount); return }
        sendFx(name, s, true, amount)
    }

    fun fxTargetName(t: Int): String = when { t < 0 -> "Mix"; t >= 65 -> "Bus ${t - 64}"; else -> "Pad ${t + 1}" }

    /* ---------------- routing ---------------- */

    fun routeOf(pad: Int): Int = project.opt(pad).route
    fun cycleRoute(pad: Int) { padMenu = -1; setPadOpt(pad) { route = (route + 1) % 5 }; say("Pad ${pad + 1} goes to " + (if (routeOf(pad) == 0) "the mix." else "bus ${routeOf(pad)}.")) }

    /* ---------------- XY: a pad's own control surface ---------------- */

    fun xyOf(pad: Int): XySetup = XySetup.parse(project.opt(pad).xy) ?: XySetup.PRESETS[0]
    fun setXy(pad: Int, x: XySetup) = setPadOpt(pad) { xy = x.code() }

    /** Your own saved setups. */
    val myXy = mutableStateListOf<XySetup>().also { l -> (prefs.getString("myxy", "") ?: "").split(";;").mapNotNull { XySetup.parse(it) }.forEach { l.add(it) } }
    fun saveMyXy(x: XySetup) {
        val named = x.copy(name = "Mine ${myXy.size + 1}")
        myXy.add(named)
        prefs.edit().putString("myxy", myXy.joinToString(";;") { it.code() }).apply()
        say("Saved as ${named.name}.")
    }
    fun allXy(): List<XySetup> = XySetup.PRESETS + myXy

    /** Next setup in the list, for the dock's XY button. */
    fun cycleXy(pad: Int) {
        val all = allXy()
        val i = all.indexOfFirst { it.name == xyOf(pad).name }
        setXy(pad, all[(i + 1) % all.size])
    }

    private val xyUsed = HashMap<Int, Set<Int>>()

    /**
     * A finger on a pad's XY: fx, fy are two-way 0..1 from the bottom left, or four-way -1..1 from the
     * middle. on = false: the finger lifted (spring back unless the setup stays). note >= 0 moves one
     * Padiano note's pitch and level instead of the whole pad's.
     */
    fun xyMove(pad: Int, fx: Float, fy: Float, on: Boolean, note: Int = Int.MIN_VALUE, setup: XySetup? = null) {
        val x = setup ?: xyOf(pad)
        val vals = if (on || !x.spring) x.values(fx, fy) else emptyMap()
        val used = x.targets() + (xyUsed[pad] ?: emptySet())
        for (t in used) {
            var v = vals[t] ?: 0f
            when (t) {
                Engine.T_FILTER, Engine.T_RES -> {}
                Engine.T_PITCH -> {
                    if (x.steps && v != 0f) v = snapToScale(v)
                    if (note != Int.MIN_VALUE) engine.noteMod(pianoId(pad, note), v, 1f + (vals[Engine.T_VOL] ?: 0f))
                    else engine.mod(pad, t, v)
                }
                Engine.T_VOL -> if (note == Int.MIN_VALUE) engine.mod(pad, t, v)
                else -> engine.mod(pad, t, v)
            }
        }
        val cut = vals[Engine.T_FILTER] ?: 0f
        val res = vals[Engine.T_RES] ?: 0f
        if (cut > 0f || res > 0f) { padFilt[pad] = (1f - cut).coerceIn(0f, 1f) to res.coerceIn(0f, 1f); engine.padFilter(pad, (1f - cut).coerceIn(0f, 1f), res.coerceIn(0f, 1f), true) }
        else if (Engine.T_FILTER in used || Engine.T_RES in used) { if (pad !in padFiltStay) { padFilt.remove(pad); engine.padFilter(pad, 1f, 0f, false) } }
        recordMoves(pad, vals, used, on)
        xyUsed[pad] = if (on || !x.spring) x.targets() else emptySet()
        xyLive[pad] = if (on) fx to fy else if (x.spring) null else fx to fy
    }

    /** Where each pad's XY finger is, for drawing. */
    val xyLive = mutableStateMapOf<Int, Pair<Float, Float>?>()

    /** Round a bend to the nearest step of the scale (counted from the root). */
    private fun snapToScale(semis: Float): Float {
        if (project.scale == "Chromatic") return Math.round(semis).toFloat()
        var best = 0; var bd = 99f
        for (c in -36..36) if (Theory.inScale(c + project.root, project.scale, project.root)) { val d = kotlin.math.abs(c - semis); if (d < bd) { bd = d; best = c } }
        return best.toFloat()
    }

    /** A circle drawn on a pad or a Padiano key: the sphere it opened, in screen pixels. */
    data class Sphere(val pad: Int, val note: Int, val cx: Float, val cy: Float, val r: Float)
    var sphere by mutableStateOf<Sphere?>(null)

    /* ---------------- per-pad settings: glide, routing, XY ---------------- */

    fun setPadOpt(pad: Int, commit: Boolean = true, f: com.tideclock.messmachine.engine.PadOpts.() -> Unit) {
        val o = project.opt(pad).copy()
        o.f()
        edit(commit) { setOpt(pad, o) }
    }

    /** Settings for playing: empty pads record, hold-to-record, the beat glow, in key only. */
    var emptyRecord by mutableStateOf(prefs.getBoolean("emptyRec", false))
        private set
    var recordHold by mutableStateOf(prefs.getBoolean("recHold", false))
        private set
    private var holdRecPad = -1
    /** The beat glow behind the whole screen: 0 off, 1 soft, 2 full. */
    var pulseLevel by mutableIntStateOf(prefs.getInt("pulse", 1))
        private set
    /** Pianos show only the scale's notes. */
    var inKeyOnly by mutableStateOf(prefs.getBoolean("inKey", false))
        private set

    fun switchEmptyRecord(v: Boolean) { emptyRecord = v; prefs.edit().putBoolean("emptyRec", v).apply(); notes.trail("empty pads record ${if (v) "on" else "off"}") }
    fun switchRecordHold(v: Boolean) { recordHold = v; prefs.edit().putBoolean("recHold", v).apply() }
    fun setPulse(v: Int) { pulseLevel = v.coerceIn(0, 2); prefs.edit().putInt("pulse", pulseLevel).apply() }
    fun switchInKey(v: Boolean) { inKeyOnly = v; prefs.edit().putBoolean("inKey", v).apply(); notes.trail("in key only ${if (v) "on" else "off"}") }

    /** The pad a mic take lands on, or -1 for the first empty pad. */
    var micPad by mutableIntStateOf(-1)
        private set
    /** A pad asked to record but the mic needs permission first; the screen asks, then calls recordToPad. */
    var askMicFor by mutableIntStateOf(-1)

    /** From the pad menu: record now if the mic is allowed, or ask first (the screen asks, then records). */
    fun askRecord(pad: Int) {
        padMenu = -1
        if (micOn) { bench.stopMic(); return }
        val ok = androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (ok) recordToPad(pad) else askMicFor = pad
    }

    /** Record from the mic straight onto one pad (it replaces what was there; undo brings it back). */
    fun recordToPad(pad: Int) {
        padMenu = -1
        if (micOn) { bench.stopMic(); return }
        micPad = pad
        startMic()
    }

    fun toggleMic() {
        if (micOn) { bench.stopMic(); return }
        micPad = -1
        startMic()
    }

    private fun startMic() {
        // one mic at a time: recording onto a pad stops mouth control
        if (mouthOn) switchMouth(false)
        micOn = true
        val pad = micPad
        notes.trail(if (pad >= 0) "record onto pad ${pad + 1}" else "mic on")
        bench.startMic { take -> main.post { takeArrived(take, pad) } }
    }

    /** A mic take is in: keep it in Your Workbench and put it on its pad (or the first empty one). */
    fun takeArrived(take: Sample?, pad: Int) {
        micOn = false
        micPad = -1
        if (take == null) { say("Nothing came through the mic."); return }
        busy = "Saving the take…"
        worker.execute {
            // a take remembers the tempo the mess was at (shown with the sound; it does not make the take follow tempo)
            val key = bench.addSample("mic", "Mic take ${bench.count("mic") + 1}", take, project.bpm.toDouble(), 0)
            main.post {
                busy = null
                if (pad >= 0) {
                    putOnPad(pad, key)
                    say("Got it, %.1f seconds. It is on pad ${pad + 1} and in Your Workbench.".format(take.seconds())) // format-ok: only a pad number goes in
                } else placeNew(key, "Got it, %.1f seconds.".format(take.seconds()))
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
                // the resample knows its tempo and its length in beats, so it follows the mess's tempo like a built-in loop
                val steps = p.pat().len
                val key = bench.addSample("rs", "Resample $letter ${bench.count("rs") + 1}", Sample.fromStereo("rs", lr, 44100), p.bpm.toDouble(), if (steps % 4 == 0) steps / 4 else 0)
                main.post { busy = null; placeNew(key, "Loop $letter is now one sound.") }
            } catch (e: Exception) { main.post { busy = null; say("The resample broke: ${e.message}") } }
        }
    }

    /** Keep a pad's sound, as you have it set, in Your Workbench (and leave the pad as it is). */
    fun keepInBench(pad: Int) {
        padMenu = -1
        val key = project.kit[pad]
        val s = lib.get(key) ?: run { say("Nothing on pad ${pad + 1} to keep."); return }
        val e = project.edit(key).copy()
        busy = "Saving…"
        notes.trail("keep pad ${pad + 1}")
        worker.execute {
            val r = Engine.renderOne("take", s, e)
            val k = bench.addSample("take", lib.titleOf(key).take(30), r)
            main.post { busy = null; version++; say("Kept in Your Workbench as \"${lib.titleOf(k)}\". Share it from there or from the pad menu.") }
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
