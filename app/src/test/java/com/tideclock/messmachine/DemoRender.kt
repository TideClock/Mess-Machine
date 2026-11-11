package com.tideclock.messmachine

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import com.tideclock.messmachine.engine.Project
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.drawToBitmap
import androidx.test.core.app.ApplicationProvider
import com.tideclock.messmachine.engine.Synth
import com.tideclock.messmachine.engine.Theory
import com.tideclock.messmachine.ui.MessMachineApp
import com.tideclock.messmachine.ui.UiFlags
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Demo videos, drawn frame by frame from the real screens with the real engine's sound.
 * Not a test of anything: it only runs when DEMO_SHOT is set (the Demo render workflow sets it).
 * Each frame: play any touches due, render exactly one frame's worth of audio, let the screen
 * catch up, save the picture. Sound and picture come from the same clock, so they cannot drift.
 * Out: build/demo/<shot>/f_00000.jpg ..., audio.wav, touches.csv (frame,type,x,y,note).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class DemoRender {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var state: AppState
    private val fps = 30
    private var frameNo = 0
    private var written = 0L
    private val pcm = ByteArrayOutputStream()
    private val log = StringBuilder("frame,type,x,y,note\n")
    private lateinit var dir: File
    private val shot: String = System.getenv("DEMO_SHOT") ?: ""

    @Before fun setUp() {
        Assume.assumeTrue("DEMO_SHOT not set: demo renders are skipped in normal builds", shot.isNotEmpty())
        UiFlags.testing = true
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("mess", 0).edit()
            .putInt("guideSeen", BuildConfig.VERSION_CODE)
            .putString("helpSeen", (Tab.entries.map { it.label } + listOf("Desk", "Keys") +
                listOf("Sample", "Tempo + feel", "Settings", "Messes", "Latency", "Packs", "Notes for Claude").map { "sheet:$it" } +
                (1..64).flatMap { listOf("sheet:XY for pad $it", "sheet:Pick a sound for pad $it") }).joinToString(","))
            .commit()
        state = AppState(ctx)
        rule.setContent { MessMachineApp(state, ticker = false) }
        settle()
        val end = System.currentTimeMillis() + 20000
        while (!state.project.kit.filter { it.isNotEmpty() }.all { state.lib.loaded(it) }) {
            if (System.currentTimeMillis() > end) throw AssertionError("the starter kit did not load")
            Thread.sleep(20); settle()
        }
        dir = File("build/demo/$shot").also { it.deleteRecursively(); it.mkdirs() }
    }

    private fun settle() { repeat(2) { ShadowLooper.idleMainLooper(); rule.waitForIdle() } }

    /** Filming starts here: from now on the screen's clock only moves one video frame at a time. */
    private fun action() {
        state.toast = null
        settle()
        rule.mainClock.autoAdvance = false
    }

    private fun mark(note: String) { log.append("$frameNo,mark,0,0,$note\n"); println("DEMO mark $frameNo $note") }

    /** One video frame: its audio, the screen catching up, the picture. */
    private fun frame() {
        val target = (frameNo + 1).toLong() * state.rate / fps
        val buf = FloatArray(512 * 2)
        while (written < target) {
            val n = minOf(512L, target - written).toInt()
            state.engine.render(buf, n)
            val bb = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n * 2) bb.putShort((buf[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            pcm.write(bb.array())
            written += n
        }
        state.tick(frameNo * 1_000_000_000L / fps)
        val ms = (frameNo + 1) * 1000L / fps - frameNo * 1000L / fps
        rule.mainClock.advanceTimeBy(ms, ignoreFrameDuration = true)
        settle()
        val bmp = grab()
        File(dir, "f_%05d.jpg".format(frameNo)).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 93, it) }
        bmp.recycle()
        frameNo++
    }

    /** The screen, with any open menus drawn on top (menus are separate windows, so the page alone misses them). */
    private fun grab(): Bitmap {
        val main = rule.activity.window.decorView.rootView
        val bmp = main.drawToBitmap(Bitmap.Config.ARGB_8888)
        try {
            val g = Class.forName("android.view.WindowManagerGlobal")
            val inst = g.getMethod("getInstance").invoke(null)
            val fv = g.getDeclaredField("mViews").also { it.isAccessible = true }
            val fp = g.getDeclaredField("mParams").also { it.isAccessible = true }
            @Suppress("UNCHECKED_CAST") val views = fv.get(inst) as java.util.ArrayList<android.view.View>
            @Suppress("UNCHECKED_CAST") val params = fp.get(inst) as java.util.ArrayList<android.view.WindowManager.LayoutParams>
            val c = android.graphics.Canvas(bmp)
            for ((i, v) in views.withIndex()) {
                if (v === main || v.width <= 0 || v.height <= 0) continue
                val loc = IntArray(2); v.getLocationOnScreen(loc)
                val lp = params.getOrNull(i)
                val x = if (loc[0] != 0 || loc[1] != 0) loc[0] else lp?.x ?: 0
                val y = if (loc[0] != 0 || loc[1] != 0) loc[1] else lp?.y ?: 0
                if (popupNote != views.size * 10000 + x + y) { popupNote = views.size * 10000 + x + y; println("DEMO popup frame=$frameNo n=${views.size} size=${v.width}x${v.height} loc=${loc[0]},${loc[1]} lp=${lp?.x},${lp?.y} grav=${lp?.gravity}") }
                popupAt = Offset(x.toFloat(), y.toFloat())
                c.save(); c.translate(x.toFloat(), y.toFloat()); v.draw(c); c.restore()
            }
        } catch (e: Throwable) { if (popupNote != -1) { popupNote = -1; println("DEMO popup grab failed: $e") } }
        return bmp
    }
    private var popupNote = 0
    /** Where the open menu's window sits on the screen (menu rows report positions inside their own window). */
    private var popupAt = Offset.Zero

    /** Every tagged thing on screen with where it is: how a new shot finds its controls. */
    private fun dumpTags(name: String) {
        val sb = StringBuilder()
        for (n in rule.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes()) {
            val tag = n.config.getOrNull(SemanticsProperties.TestTag)
            val txt = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("|") { it.text }
            if (tag == null && txt == null) continue
            val b = n.boundsInWindow
            sb.append("${tag ?: "-"}\t${txt ?: ""}\t${b.left.toInt()},${b.top.toInt()},${b.right.toInt()},${b.bottom.toInt()}\n")
        }
        File(dir, "tags-$name.txt").writeText(sb.toString())
    }

    private fun show(tag: String) { rule.onNodeWithTag(tag).performScrollTo(); frames(2) }

    /** Finger number id goes down on a tagged thing and stays down (several fingers can be down at once). */
    private fun fingerDown(id: Int, tag: String, fx: Float, fy: Float, note: String = "$tag#$id") {
        val p = rootPoint(tag, fx, fy)
        log.append("$frameNo,down,${p.x.toInt()},${p.y.toInt()},$note\n")
        rule.onNodeWithTag(tag).performTouchInput { down(id, Offset(width * fx, height * fy)) }
    }
    private fun fingerMove(id: Int, tag: String, fx: Float, fy: Float, note: String = "$tag#$id") {
        val p = rootPoint(tag, fx, fy)
        log.append("$frameNo,move,${p.x.toInt()},${p.y.toInt()},$note\n")
        rule.onNodeWithTag(tag).performTouchInput { moveTo(id, Offset(width * fx, height * fy), 0) }
    }
    private fun fingerUp(id: Int, tag: String, note: String = "$tag#$id") {
        log.append("$frameNo,up,0,0,$note\n")
        rule.onNodeWithTag(tag).performTouchInput { up(id) }
    }
    /** One finger slides across a tagged thing over some frames, from one spot to another. */
    private fun drag(tag: String, x0: Float, y0: Float, x1: Float, y1: Float, n: Int, id: Int = 0, lift: Boolean = true, note: String = "$tag#$id", down: Boolean = true) {
        if (down) { fingerDown(id, tag, x0, y0, note); frame() }
        for (i in 1..n) { val t = i / n.toFloat(); fingerMove(id, tag, x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, note); frame() }
        if (lift) { fingerUp(id, tag, note); frame() }
    }
    /** Tap something found by the words on it (menu rows have no tags). */
    private fun tapText(text: String, hold: Int = 3) {
        val node = rule.onNodeWithText(text, substring = true, ignoreCase = true, useUnmergedTree = true)
        val b = node.fetchSemanticsNode().boundsInWindow
        log.append("$frameNo,down,${b.center.x.toInt()},${b.center.y.toInt()},$text\n")
        node.performTouchInput { down(center) }
        frames(hold)
        node.performTouchInput { up() }
        log.append("$frameNo,up,${b.center.x.toInt()},${b.center.y.toInt()},$text\n")
        frame()
    }
    private fun has(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** Run a shot; if it breaks, keep what was filmed plus a picture and the tag list of where it broke. */
    private fun shoot(name: String, body: () -> Unit) {
        Assume.assumeTrue(shot == name)
        try { body() } catch (e: Throwable) {
            println("DEMO FAILED $name at frame $frameNo: $e")
            File(dir, "fail.txt").writeText("frame $frameNo\n" + e.stackTraceToString())
            try { dumpTags("fail"); grab().let { b -> File(dir, "fail.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } } } catch (_: Throwable) {}
        }
        finish()
    }

    private fun frames(n: Int) { repeat(n) { frame() } }
    private fun secs(s: Double) = frames((s * fps).toInt())

    private fun rootPoint(tag: String, fx: Float, fy: Float): Offset {
        val n = rule.onNodeWithTag(tag).fetchSemanticsNode()
        val b = n.boundsInWindow
        val off = if (n.root !== rule.onNodeWithTag("topbar").fetchSemanticsNode().root) popupAt else Offset.Zero
        return Offset(off.x + b.left + b.width * fx, off.y + b.top + b.height * fy)
    }

    /** Wait (before filming) until these sounds are loaded. */
    private fun load(vararg keys: String) {
        val end = System.currentTimeMillis() + 20000
        while (!keys.all { state.lib.loaded(it) }) {
            if (System.currentTimeMillis() > end) throw AssertionError("sounds did not load: ${keys.filter { !state.lib.loaded(it) }}")
            Thread.sleep(20); settle()
        }
        settle()
    }

    /* fingers placed by screen position (pixels), for sliders, the roll and anything without its own tag */
    private fun downAt(id: Int, x: Float, y: Float, note: String) {
        log.append("$frameNo,down,${x.toInt()},${y.toInt()},$note\n")
        rule.onNodeWithTag("pulse").performTouchInput { down(id, Offset(x, y)) }
    }
    private fun moveAt(id: Int, x: Float, y: Float, note: String) {
        log.append("$frameNo,move,${x.toInt()},${y.toInt()},$note\n")
        rule.onNodeWithTag("pulse").performTouchInput { moveTo(id, Offset(x, y), 0) }
    }
    private fun upAt(id: Int, note: String) {
        log.append("$frameNo,up,0,0,$note\n")
        rule.onNodeWithTag("pulse").performTouchInput { up(id) }
    }
    private fun tapAt(x: Float, y: Float, hold: Int = 3, note: String = "tap") { downAt(0, x, y, note); frames(hold); upAt(0, note); frame() }
    private fun slideAt(x0: Float, y0: Float, x1: Float, y1: Float, n: Int, note: String = "slide") {
        downAt(0, x0, y0, note); frames(3)
        for (i in 1..n) { val t = i / n.toFloat(); moveAt(0, x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, note); frame() }
        frames(3); upAt(0, note); frame()
    }
    private fun textBox(text: String) = rule.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInWindow

    /** Where a note sits on a pad's Padiano (squares, one row): semitones above the sound's own pitch. */
    private fun pk(pad: Int, semis: Int): Float = (semis - (state.padianoBase[pad] ?: 0) + .5f) / state.padianoN
    private fun key(pad: Int, semis: Int, hold: Int = 4) = tap("piano-keys-$pad", pk(pad, semis), .5f, hold = hold, note = "key $semis")

    /** Tap a square of the piano roll: pitch (semitones from the sound's own, 12 at the top row) and step. */
    private fun rollTap(pitch: Int, step: Int) {
        val n = rule.onNodeWithTag("roll").fetchSemanticsNode()
        val d = rule.density.density
        val kw = 46f * d; val rh = 30f * d
        val cw = (n.size.width - kw) / 16f
        tapAt(n.positionInWindow.x + kw + (step + .5f) * cw, n.positionInWindow.y + (Theory.ROLL_HI - pitch + .5f) * rh, hold = 2, note = "roll $pitch $step")
    }

    /** A second finger turns a knob: right is up. units = how far in knob travel (1 = the whole range). */
    private fun turn(knob: String, units: Float, n: Int, id: Int = 1, each: (Int) -> Unit = {}) {
        val w = rule.onNodeWithTag(knob).fetchSemanticsNode().boundsInWindow.width
        val span = 200f * rule.density.density
        val from = turnAt[knob] ?: .5f
        val to = from + units * span / w
        for (i in 1..n) { fingerMove(id, knob, from + (to - from) * i / n, .28f, "knob"); each(i); frame() }
        turnAt[knob] = to
    }
    private val turnAt = HashMap<String, Float>()


    /** A finger down on a tagged thing (fx, fy: where on it, 0 to 1), held for some frames, then lifted. */
    private fun tap(tag: String, fx: Float = .5f, fy: Float = .5f, hold: Int = 3, note: String = tag) {
        val p = rootPoint(tag, fx, fy)
        log.append("$frameNo,down,${p.x.toInt()},${p.y.toInt()},$note\n")
        rule.onNodeWithTag(tag, useUnmergedTree = false).performTouchInput { down(Offset(width * fx, height * fy)) }
        frames(hold)
        rule.onNodeWithTag(tag).performTouchInput { up() }
        log.append("$frameNo,up,${p.x.toInt()},${p.y.toInt()},$note\n")
        frame()
    }

    /** Run frames until the loop is about to reach this step (so the next tap lands on a beat). */
    private fun untilStep(step: Int, limit: Int = 400) {
        var n = 0
        while (state.heardStep != step && n++ < limit) frame()
    }

    private fun finish() {
        val data = pcm.toByteArray()
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(state.rate).putInt(state.rate * 4).putShort(4).putShort(16)
        h.put("data".toByteArray()).putInt(data.size)
        File(dir, "audio.wav").outputStream().use { it.write(h.array()); it.write(data) }
        File(dir, "touches.csv").writeText(log.toString())
        File(dir, "info.txt").writeText("shot=$shot fps=$fps rate=${state.rate} frames=$frameNo bpm=${state.project.bpm}\n")
        println("DEMO done $shot frames=$frameNo audio=${data.size / 4} samples")
    }

    /** Not a demo: checks that menus get filmed and lists the controls on the screens the shots use. */
    @Test fun t1() = shoot("t1") {
        state.newMess()
        state.putOnPad(8, "samples/glass-harp.wav")
        settle(); Thread.sleep(600); settle()
        action()
        frames(3); dumpTags("pads")
        tap("pad-4", hold = 20); frames(4); dumpTags("padmenu")
        if (has("menu-piano")) tap("menu-piano") else println("DEMO no menu-piano")
        frames(6); dumpTags("padiano")
        if (has("dock-open")) { tap("dock-open"); frames(6); dumpTags("dock") }
        if (has("dock-rate")) { tap("dock-rate"); frames(6); dumpTags("dockrate"); if (has("rate-sring")) tap("rate-sring"); frames(4) }
        if (has("pick-chord")) { tap("pick-chord"); frames(6); dumpTags("chordmenu"); tapText("Triads from"); frames(4) }
        if (has("open-sample")) { tap("open-sample"); frames(6); dumpTags("sample"); tap("held-sustain"); frames(3); tap("held-beat"); frames(4); dumpTags("sample-beat"); tap("done"); frames(4) }
        tap("bpm"); frames(6); dumpTags("tempo"); tap("done"); frames(3)
        tap("tab-Roll"); frames(6); dumpTags("roll")
        tap("tab-Synth"); frames(6); dumpTags("synth-none")
        state.newSynth(9, Synth.ANALOG.presets.first { it.name == "Acid line" }); frames(4); dumpTags("synth-simple")
        tap("synthview-full"); frames(4); dumpTags("synth-full")
        tap("synthview-pad"); frames(4); dumpTags("synthipad")
        tap("synth-preset"); frames(6); dumpTags("presetmenu")
        tap("tab-Sounds"); frames(6); dumpTags("sounds")
        tap("tab-Song"); frames(6); dumpTags("song")
    }

    private fun drum(name: String) = Synth.DRUM.presets.first { it.name == name }

    /** C2: drum synths on four pads, programmed on Steps while the loop plays, sample pads on top. */
    @Test fun c2() = shoot("c2") {
        state.newMess()
        state.setBpm(120)
        state.newSynth(3, drum("Clap"))
        state.newSynth(2, drum("Closed hat"))
        state.newSynth(1, drum("Snare"))
        state.newSynth(0, drum("Deep kick"))
        settle()
        action()

        // the kick on the Synth tab
        tap("tab-Synth"); secs(.4)
        mark("synth-tab")
        for (n in listOf(0, 0, 3, 0)) { key(0, n, hold = 3); frames(6) }
        secs(.5)

        // Steps: start the loop, then build the beat one row at a time
        tap("tab-Steps"); secs(.5)
        mark("steps")
        tap("play"); secs(.3)
        fun step(row: Int, j: Int) { tap("steps-$row", (j + .5f) / 16f, .5f, hold = 2, note = "step $row $j"); frames(4) }
        for (j in listOf(0, 4, 8, 12)) step(0, j)
        mark("kick-in"); secs(1.6)
        for (j in listOf(4, 12)) step(1, j)
        mark("snare-in"); secs(1.4)
        for (j in listOf(2, 6, 10, 14)) step(2, j)
        mark("hat-in"); secs(1.4)
        for (j in listOf(12, 15)) step(3, j)
        mark("clap-in"); secs(4.0)

        // sample pads on top, on the beat
        tap("tab-Pads"); secs(.4)
        mark("pads")
        for (bar in 0 until 2) for ((s, pad) in listOf(0 to 4, 4 to 5, 8 to 6, 10 to 7, 12 to 5)) {
            untilStep(s); tap("pad-$pad", hold = 3, note = "pad $pad")
        }
        untilStep(0)
        tap("play"); secs(1.0)
    }

    private fun analog(name: String) = Synth.ANALOG.presets.first { it.name == name }
    private val harp = "samples/glass-harp.wav"

    /** B1: Stretch vs Grain. The same long sound up the keys: Stretch gets shorter as it goes up, Grain keeps its length. */
    @Test fun b1() = shoot("b1") {
        state.newMess()
        state.putOnPad(0, harp); load(harp)
        state.selectPad(0, false); state.toggleKeyboard(0)
        settle(); action(); secs(.4)
        for (mode in listOf("stretch", "grain")) {
            mark("$mode-sheet")
            tap("open-sample"); secs(.5)
            tap("pitch-$mode"); secs(.9)
            tap("done"); secs(.4)
            mark("$mode-play")
            val st = mode == "stretch"
            for ((n, wait) in listOf(0 to 2.3, 7 to (if (st) 1.6 else 2.3), 12 to (if (st) 1.25 else 2.3))) {
                mark("$mode-note-$n"); key(0, n); secs(wait)
            }
        }
        secs(.3)
    }

    /** B2: Sustain. A short sound, then the same sound held like a pad for as long as the finger stays down. */
    @Test fun b2() = shoot("b2") {
        val ding = "samples/elevator-ding.wav"
        state.newMess()
        state.putOnPad(4, ding); load(ding)
        state.selectPad(4, false)
        settle(); action(); secs(.3)
        mark("short")
        tap("pad-4", hold = 20); secs(1.2)
        mark("sheet")
        tap("open-sample"); secs(.5)
        tap("held-sustain"); secs(1.3)
        tap("done"); secs(.4)
        mark("held")
        tap("pad-4", hold = 105)
        mark("released"); secs(1.2)
        mark("held2")
        tap("pad-4", hold = 50); secs(1.0)
    }

    /** B3: Hold, On the beat. A latched loop starts again every beat, then every bar, in time with the drums. */
    @Test fun b3() = shoot("b3") {
        val loop = "loop:old-wheezy-1"
        state.newMess(); state.setBpm(120)
        state.putOnPad(4, loop); load(loop)
        state.edit { for (j in listOf(0, 4, 8, 12)) pat().setStep(kit[0], j, true); for (j in listOf(2, 6, 10, 14)) pat().setStep(kit[2], j, true) }
        state.selectPad(4, false); state.toggleKeyboard(4)
        settle(); action(); secs(.3)
        mark("sheet1")
        tap("open-sample"); secs(.5)
        tap("held-beat"); secs(.6)
        tap("beat-1"); secs(.5)
        tap("done"); secs(.3)
        tap("play"); secs(.2)
        tap("hold-toggle"); frames(4)
        untilStep(15); mark("every-beat")
        key(4, 0); secs(4.0)
        key(4, 0); secs(.3)
        mark("sheet2")
        tap("open-sample"); secs(.4)
        tap("beat-4"); frames(8)
        tap("done"); frames(6)
        untilStep(15); mark("every-bar")
        key(4, 0); secs(4.2)
        key(4, 0); secs(.3)
        tap("play"); secs(.6)
    }

    /** B4: Repeat styles. The same held chord with Repeat on: Cut, then Ring, then Flow. */
    @Test fun b4() = shoot("b4") {
        val organ = "samples/wheezy-organ.wav"
        state.newMess()
        state.putOnPad(4, organ); load(organ)
        state.selectPad(4, false); state.toggleKeyboard(4)
        state.pickChordMode("triad"); state.switchChordPlay(true)
        state.switchDockOpen(true)
        state.setLive { nrRate = 2.0 }
        settle(); action(); secs(.4)
        tap("dock-repeat"); secs(.4)
        mark("cut")
        key(4, 0, hold = 75); secs(.9)
        tap("dock-rate"); secs(.5); tap("rate-sring"); secs(.4)
        mark("ring")
        key(4, 0, hold = 75); secs(1.6)
        tap("dock-rate"); secs(.5); tap("rate-sflow"); secs(.4)
        mark("flow")
        key(4, 0, hold = 90); secs(1.6)
    }

    /** B5: the Synth tab. One preset from each kind, with a knob turned while the note sounds. */
    @Test fun b5() = shoot("b5") {
        state.newMess()
        state.putOnPad(1, harp); load(harp)
        state.newSynth(0, analog("Warm pad"))
        state.tab = Tab.Synth
        settle(); action(); secs(.5)
        val kinds = listOf("analog" to "knob-cut", "fm" to "knob-bright", "wave" to "knob-pos", "grain" to "knob-pos", "drum" to "knob-decay", "pluck" to "knob-bright")
        for ((kind, knob) in kinds) {
            if (kind != "analog") { tap("synthkind-$kind"); frames(8) }
            mark(kind)
            turnAt.remove(knob)
            val oneShot = kind == "drum" || kind == "pluck"
            val notes = listOf(0, 7, 3, 12, 7, 0)
            var hit = 0
            fun pluck(i: Int) {
                if (!oneShot) return
                if (i % 12 == 1) fingerDown(0, "piano-keys-0", pk(0, notes[hit % notes.size]), .5f, "key")
                if (i % 12 == 4) { fingerUp(0, "piano-keys-0", "key"); hit++ }
            }
            if (!oneShot) { fingerDown(0, "piano-keys-0", pk(0, 0), .5f, "key"); frames(10) }
            fingerDown(1, knob, .5f, .28f, "knob"); frame()
            turn(knob, .4f, 24) { pluck(it) }
            turn(knob, -.7f, 36) { pluck(it + 24) }
            turn(knob, .35f, 24) { pluck(it + 60) }
            fingerUp(1, knob, "knob"); frames(4)
            if (!oneShot) fingerUp(0, "piano-keys-0", "key")
            frames(if (kind == "pluck") 30 else 10)
        }
    }

    /** B6: Acid. An acid line in the piano roll; notes that touch slide into each other. */
    @Test fun b6() = shoot("b6") {
        state.newMess(); state.setBpm(126)
        state.newSynth(0, analog("Acid line"))
        val k = state.project.kit[0]
        state.edit { for ((st, p, l) in listOf(Triple(0, 0, 1), Triple(2, 0, 1), Triple(3, 12, 1), Triple(4, 0, 2), Triple(6, 3, 2))) pat().notes(k).add(Project.Note(st, p, l)) }
        state.selectPad(0, false)
        settle(); action(); secs(.3)
        tap("tab-Roll"); secs(.5)
        mark("roll")
        tap("play"); untilStep(15); untilStep(0)
        untilStep(14)
        mark("add-notes")
        for ((p, st) in listOf(0 to 8, 7 to 10, 10 to 12, 7 to 14)) { rollTap(p, st); frames(5) }
        mark("slides")
        untilStep(15); untilStep(0); untilStep(15); untilStep(0); untilStep(15); untilStep(0)
        mark("synth")
        tap("tab-Synth"); secs(.5)
        fingerDown(1, "knob-cut", .5f, .28f, "knob"); frame()
        turnAt.remove("knob-cut")
        turn("knob-cut", .35f, 40); turn("knob-cut", -.45f, 50); turn("knob-cut", .2f, 25)
        fingerUp(1, "knob-cut", "knob"); frames(4)
        untilStep(0)
        tap("play"); secs(.6)
    }

    /** B7: Synthipad. A grid of in-key notes with the filter square swept by another finger. */
    @Test fun b7() = shoot("b7") {
        state.newMess()
        state.newSynth(0, analog("Brass"))
        state.tab = Tab.Synth
        settle(); action(); secs(.4)
        tap("synthview-pad"); secs(.6)
        mark("grid")
        fun cx(c: Int) = (c + .5f) / 4f
        fun cy(r: Int) = (r + .5f) / 4f
        val g = "synthipad-notes"; val xy = "synthipad-xy"
        // a few single notes: every square is in key
        for ((r, c) in listOf(3 to 0, 2 to 0, 2 to 3, 1 to 1, 1 to 3, 2 to 3)) { tap(g, cx(c), cy(r), hold = 5, note = "note"); frames(3) }
        secs(.3)
        mark("chord-sweep")
        fingerDown(0, g, cx(3), cy(2), "c4"); frames(2)
        fingerDown(1, g, cx(1), cy(1), "eb4"); frames(2)
        fingerDown(2, g, cx(3), cy(1), "g4"); frames(8)
        fingerDown(3, xy, .12f, .8f, "xy"); frame()
        for (i in 1..45) { val t = i / 45f; fingerMove(3, xy, .12f + .78f * t, .8f - .6f * t, "xy"); frame() }
        for (i in 1..45) { val t = i / 45f; fingerMove(3, xy, .9f - .7f * t, .2f + .1f * Math.sin(t * 6.28).toFloat(), "xy"); frame() }
        for (i in 1..30) { val t = i / 30f; fingerMove(3, xy, .2f + .45f * t, .3f + .3f * t, "xy"); frame() }
        fingerUp(0, g, "c4"); fingerUp(1, g, "eb4"); fingerUp(2, g, "g4"); frames(10)
        mark("bass-sweep")
        fingerDown(0, g, cx(0), cy(3), "c3"); frames(6)
        for (i in 1..50) { val t = i / 50f; fingerMove(3, xy, .65f - .5f * t + .15f * Math.sin(t * 12.56).toFloat(), .6f - .45f * t, "xy"); frame() }
        for (i in 1..40) { val t = i / 40f; fingerMove(3, xy, .15f + .75f * t, .15f + .2f * t, "xy"); frame() }
        fingerUp(0, g, "c3"); frames(4)
        fingerUp(3, xy, "xy"); secs(.8)
    }

    /** B8: loops fit the tempo. A Qbldy loop plays with the drums; the tempo moves and the loop follows. */
    @Test fun b8() = shoot("b8") {
        val loop = "loop:ametrine-1"
        state.newMess(); state.setBpm(120)
        state.putOnPad(3, loop); load(loop)
        state.edit {
            for (j in listOf(0, 4, 8, 12)) pat().setStep(kit[0], j, true)
            for (j in listOf(2, 6, 10, 14)) pat().setStep(kit[2], j, true)
            pat().setStep(loop, 0, true)
        }
        state.tab = Tab.Steps
        settle(); action(); secs(.4)
        mark("play-120")
        tap("play"); untilStep(15); untilStep(0); untilStep(15); untilStep(0)
        mark("tempo-sheet")
        tap("bpm"); secs(.6)
        val a = textBox("TEMPO"); val b = textBox("SWING")
        val y = (a.bottom + b.top) / 2f
        val left = a.left + 20f; val w = (rule.onNodeWithTag("sheet").fetchSemanticsNode().boundsInWindow.right - 28f - 28f) - a.left - 40f
        fun x(bpm: Int) = left + w * (bpm - 60) / 120f
        mark("faster")
        slideAt(x(120), y, x(150), y, 24, "tempo")
        untilStep(15); untilStep(0); untilStep(15); untilStep(0); untilStep(15); untilStep(0)
        mark("slower")
        slideAt(x(state.project.bpm), y, x(86), y, 30, "tempo")
        untilStep(15); untilStep(0); untilStep(15); untilStep(0)
        mark("done")
        tap("done"); untilStep(15); untilStep(0)
        tap("play"); secs(.6)
    }

    /** C1: an acid line, a Qbldy loop and a granular cloud, built up one layer at a time. */
    @Test fun c1() = shoot("c1") {
        val loop = "loop:ametrine-1"
        state.newMess(); state.setBpm(120)
        state.putOnPad(0, loop); state.putOnPad(3, harp); load(loop, harp)
        state.newSynth(2, Synth.GRAIN.presets.first { it.name == "Cloud" })
        state.setGrainSource(2, harp)
        state.newSynth(1, analog("Acid line"))
        state.selectPad(1, false)
        state.tab = Tab.Steps
        settle(); action(); secs(.4)
        tap("play"); untilStep(13)
        mark("loop")
        tap("steps-0", .5f / 16f, .5f, hold = 2, note = "step loop"); untilStep(15); untilStep(0); untilStep(15); untilStep(0); untilStep(12)
        mark("acid")
        tap("tab-Roll"); secs(.4)
        for ((p, st) in listOf(0 to 0, 0 to 4, 3 to 6, 0 to 8, 7 to 10, 10 to 12, 7 to 14)) { rollTap(p, st); frames(4) }
        untilStep(15); untilStep(0); untilStep(15); untilStep(0); untilStep(12)
        mark("cloud")
        tap("tab-Pads"); secs(.4)
        untilStep(0)
        fingerDown(0, "pad-2", .5f, .5f, "cloud"); 
        untilStep(15); untilStep(0); untilStep(15); untilStep(0); untilStep(15); untilStep(0)
        fingerUp(0, "pad-2", "cloud"); frame()
        mark("out")
        untilStep(15); untilStep(0)
        tap("play"); secs(1.5)
    }
}
