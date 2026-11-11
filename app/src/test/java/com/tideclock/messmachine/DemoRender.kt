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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onFirst
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
    /** DEMO_SHOT lists the shots to film (the test method names, space separated); every other one is skipped. */
    private val want = (System.getenv("DEMO_SHOT") ?: "").split(" ", ",").filter { it.isNotEmpty() }
    @get:Rule val testName = org.junit.rules.TestName()
    private lateinit var shot: String

    @Before fun setUp() {
        shot = testName.methodName
        Assume.assumeTrue("not in DEMO_SHOT: demo renders are skipped in normal builds", shot in want)
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
        // setting up plays previews (a new sound, a new synth): let them ring out unheard before filming
        val junk = FloatArray(1024)
        repeat(10) { state.engine.render(junk, 512) }
        state.engine.panic()
        repeat(320) { state.engine.render(junk, 512) }
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
        val sn = node.fetchSemanticsNode()
        val off = if (sn.root !== rule.onNodeWithTag("topbar").fetchSemanticsNode().root) popupAt else Offset.Zero
        val b = sn.boundsInWindow.translate(off)
        log.append("$frameNo,down,${b.center.x.toInt()},${b.center.y.toInt()},$text\n")
        node.performTouchInput { down(center) }
        frames(hold)
        node.performTouchInput { up() }
        log.append("$frameNo,up,${b.center.x.toInt()},${b.center.y.toInt()},$text\n")
        frame()
    }
    /** Hold a pad down. The finger shifts a little first, the way a player's does, so lifting does not open the pad's menu. */
    private fun holdPad(tag: String, n: Int, note: String = tag) {
        fingerDown(0, tag, .45f, .5f, note); frames(2)
        fingerMove(0, tag, .63f, .58f, note); frame()
        frames(n)
        fingerUp(0, tag, note); frame()
    }
    /** Tap something by its exact words. When several things carry them, the lowest on screen (or the nth from the top). */
    private fun tapExact(text: String, hold: Int = 3, pick: Int = -1, above: Float = Float.MAX_VALUE) {
        val col = rule.onAllNodes(hasText(text), useUnmergedTree = true)
        val nodes = col.fetchSemanticsNodes()
        val order = nodes.indices.filter { nodes[it].boundsInWindow.top < above }.sortedBy { nodes[it].boundsInWindow.top }
        if (order.isEmpty()) throw AssertionError("nothing on screen says \"$text\"")
        val i = if (pick < 0) order.last() else order[pick]
        val n = nodes[i]
        val off = if (n.root !== rule.onNodeWithTag("topbar").fetchSemanticsNode().root) popupAt else Offset.Zero
        val c = n.boundsInWindow.center + off
        log.append("$frameNo,down,${c.x.toInt()},${c.y.toInt()},$text\n")
        col[i].performTouchInput { down(center) }
        frames(hold)
        rule.onAllNodes(hasText(text), useUnmergedTree = true)[i].performTouchInput { up() }
        log.append("$frameNo,up,${c.x.toInt()},${c.y.toInt()},$text\n")
        frame()
    }
    /** Is this tagged thing really on screen (not scrolled off the side or the bottom)? */
    private fun onScreen(tag: String): Boolean {
        val n = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull() ?: return false
        val b = n.boundsInWindow
        return b.width > 40f && b.height > 20f && b.left >= 0f && b.right <= 822f && b.bottom <= 1690f
    }
    /** Pick a loop letter on the Song tab's Paint with row (painted bars carry the same letters lower down). */
    private fun brush(letter: String) { tapExact(letter, above = rule.onNodeWithTag("bar-0").fetchSemanticsNode().boundsInWindow.top); frames(5) }
    private fun waitWhile(limit: Int = 900, cond: () -> Boolean) { var n = 0; while (cond() && n++ < limit) frame() }
    /** Where a note sits on the Padiano once In key only has hidden the out-of-key squares. */
    private fun inKeyPk(pad: Int, semis: Int): Float {
        val base = state.padianoBase[pad] ?: 0
        val pr = state.project
        val notes = (base until base + state.padianoN).filter { pr.scale == "Chromatic" || Theory.inScale(it, pr.scale, pr.root) }
        return (notes.indexOf(semis) + .5f) / notes.size
    }
    /** The small beat most shots play over: kick on the beats, hat between, and (if given) a one bar loop on pad 4. */
    private fun beat(loop: String? = null, bpm: Int = 120) {
        state.newMess(); state.setBpm(bpm)
        if (loop != null) { state.putOnPad(3, loop); load(loop) }
        state.edit {
            for (j in listOf(0, 4, 8, 12)) pat().setStep(kit[0], j, true)
            for (j in listOf(2, 6, 10, 14)) pat().setStep(kit[2], j, true)
            if (loop != null) pat().setStep(loop, 0, true)
        }
    }
    private fun bars(n: Int) { repeat(n) { untilStep(15); untilStep(0) } }
    private fun has(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** Run a shot; if it breaks, keep what was filmed plus a picture and the tag list of where it broke. */
    private fun shoot(name: String, body: () -> Unit) {
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

    /** Not a demo: lists the controls on the screens the Tier 2 and 3 shots use. */
    @Test fun t2() = shoot("t2") {
        state.newMess()
        state.putOnPad(8, harp); load(harp)
        state.selectPad(4, false); state.toggleKeyboard(4)
        settle(); action()
        frames(3)
        fun step(name: String, f: () -> Unit) { try { f(); frames(6); dumpTags(name) } catch (e: Throwable) { println("DEMO t2 $name: $e") } }
        step("fx") { tap("fx-toggle") }
        step("fx-off") { tap("fx-toggle") }
        step("padxy") { tap("piano-xy-4") }
        step("pset") { tap("piano-set-4") }
        step("pset-inkey") { tap("pset-inkey") }
        step("sample-trim") { tap("open-sample"); frames(6); tap("dtab-trim") }
        step("sample-shape") { tap("dtab-shape") }
        step("sample-fx") { tap("dtab-fx") }
        step("sample-tools") { tap("dtab-tools") }
        step("sample-closed") { tap("done") }
        step("topmenu") { tap("menu") }
        step("settings") { tap("settings") }
        step("settings-closed") { tap("done") }
        step("notes") { tap("menu"); frames(6); tap("notes") }
        step("notes-closed") { tap("done") }
        step("learn") { tap("menu"); frames(6); dumpTags("topmenu2"); if (has("learn")) tap("learn") }
        step("learn-closed") { if (has("done")) tap("done") }
        step("desk") { tap("view-desk") }
        step("pads-again") { tap("mode-pads") }
        step("steps") { tap("tab-Steps") }
        step("sounds-jar") { tap("tab-Sounds"); frames(6); tap("mode-jar") }
        step("sounds-fav") { tap("mode-fav") }
        step("packs") { tap("open-packs") }
        step("packs-closed") { if (has("done")) tap("done") }
        step("song") { tap("tab-Song") }
        step("rec") { tap("tab-Pads"); frames(6); tap("rec") }
        step("rec-off") { tap("rec") }
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
        tap("pad-4", hold = 4); secs(1.5)
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
        slideAt(x(120), y, x(152), y, 24, "tempo"); frames(10)
        tap("done"); frames(4)
        mark("fast-steps")
        untilStep(15); untilStep(0); untilStep(15); untilStep(0); untilStep(15); untilStep(0)
        mark("tempo-sheet2")
        tap("bpm"); secs(.5)
        mark("slower")
        slideAt(x(state.project.bpm), y, x(88), y, 30, "tempo"); frames(10)
        tap("done"); frames(4)
        mark("slow-steps")
        untilStep(15); untilStep(0); untilStep(15); untilStep(0)
        tap("play"); secs(.6)
    }

    /**
     * C1: a Qbldy loop, an acid line and a granular cloud, built up one layer at a time. The loop (Ametrine 1)
     * measures as B flat minor, so the mess is set to B flat minor and the acid line is written in it. The cloud's
     * grains come from the glass harp, whose own note is E: Padiano keys +1 and +6 sound F and B flat.
     */
    @Test fun c1() = shoot("c1") {
        val loop = "loop:ametrine-1"
        state.newMess(); state.setBpm(120)
        state.setRoot(10); state.setScale("Minor")
        state.putOnPad(0, loop); state.putOnPad(3, harp); load(loop, harp)
        state.newSynth(2, Synth.GRAIN.presets.first { it.name == "Cloud" })
        state.setGrainSource(2, harp)
        state.newSynth(1, analog("Acid line"))
        state.toggleKeyboard(2)
        state.selectPad(1, false)
        state.tab = Tab.Steps
        settle(); action(); secs(.4)
        tap("play"); untilStep(13)
        mark("loop")
        tap("steps-0", .5f / 16f, .5f, hold = 2, note = "step loop"); untilStep(15); untilStep(0); untilStep(15); untilStep(0); untilStep(12)
        mark("acid")
        tap("tab-Roll"); secs(.4)
        for ((p, st) in listOf(10 to 0, 10 to 4, 5 to 6, 10 to 8, 8 to 10, 5 to 12, 1 to 14)) { rollTap(p, st); frames(4) }
        untilStep(15); untilStep(0); untilStep(15); untilStep(0); untilStep(12)
        mark("cloud")
        tap("tab-Pads"); secs(.4)
        untilStep(0)
        fingerDown(0, "piano-keys-2", pk(2, 1), .5f, "cloud-f"); frames(3)
        fingerDown(1, "piano-keys-2", pk(2, 6), .5f, "cloud-bb")
        untilStep(15); untilStep(0); untilStep(15); untilStep(0); untilStep(15); untilStep(0)
        fingerUp(0, "piano-keys-2", "cloud-f"); fingerUp(1, "piano-keys-2", "cloud-bb"); frame()
        mark("out")
        untilStep(15); untilStep(0)
        tap("play"); secs(1.5)
    }

    private val throne = "loop:give-me-throne-2"

    /** B9: Padiano. Hold a pad, open keys under it, then In key only so no square is a wrong note. */
    @Test fun b9() = shoot("b9") {
        state.newMess()
        state.putOnPad(4, harp); load(harp)
        settle(); action(); secs(.4)
        mark("menu")
        tap("pad-4", hold = 20); secs(.6)
        tap("menu-piano"); secs(.7)
        mark("keys")
        for (n in listOf(0, 3, 7, 12, 10, 7)) { key(4, n, hold = 5); frames(7) }
        secs(.5)
        mark("wrong")
        for (n in listOf(0, 1, 2, 3, 4)) { key(4, n, hold = 4); frames(5) }
        secs(.4)
        mark("settings")
        tap("piano-set-4"); secs(.5)
        tap("pset-inkey"); secs(.7)
        tap("piano-set-4"); secs(.4)
        mark("inkey")
        for (n in listOf(0, 2, 3, 5, 7, 8, 10, 12, 10, 8, 7, 5, 3, 2, 0)) { tap("piano-keys-4", inKeyPk(4, n), .5f, hold = 3, note = "key"); frames(3) }
        secs(1.2)
    }

    /** B10: Granular. Grains of a long sound, Position moved by hand, then the Freeze and Shimmer presets. */
    @Test fun b10() = shoot("b10") {
        state.newMess()
        state.putOnPad(1, harp); load(harp)
        state.newSynth(0, Synth.GRAIN.presets.first { it.name == "Cloud" })
        state.setGrainSource(0, harp)
        state.tab = Tab.Synth
        settle(); action(); secs(.5)
        mark("cloud")
        fingerDown(0, "piano-keys-0", pk(0, 0), .5f, "key"); frames(20)
        mark("position")
        turnAt.remove("knob-pos")
        fingerDown(1, "knob-pos", .5f, .28f, "knob"); frame()
        turn("knob-pos", .5f, 45); turn("knob-pos", -.75f, 60); turn("knob-pos", .4f, 40)
        fingerUp(1, "knob-pos", "knob"); frames(10)
        fingerUp(0, "piano-keys-0", "key"); secs(1.2)
        for (name in listOf("Freeze", "Shimmer")) {
            mark(name.lowercase())
            tap("synth-preset"); secs(.5)
            tapExact(name); secs(.5)
            fingerDown(0, "piano-keys-0", pk(0, 0), .5f, "key"); frames(12)
            fingerDown(1, "piano-keys-0", pk(0, 7), .5f, "key2"); frames(75)
            fingerUp(0, "piano-keys-0", "key"); fingerUp(1, "piano-keys-0", "key2"); secs(1.4)
        }
    }

    /** B11: Bake. The same chords on keys with Bake off, then on. The difference is in the sound. */
    @Test fun b11() = shoot("b11") {
        state.newMess()
        state.putOnPad(4, harp); load(harp)
        state.selectPad(4, false); state.toggleKeyboard(4)
        state.pickChordMode("triad"); state.switchChordPlay(true)
        settle(); action(); secs(.4)
        mark("live")
        for (n in listOf(0, 5, 7)) { key(4, n, hold = 5); secs(1.5) }
        mark("sheet")
        tap("open-sample"); secs(.5)
        tap("bake-it"); frames(6)
        waitWhile { state.bakeState(harp) != "baked" }
        println("DEMO bake state ${state.bakeState(harp)} at frame $frameNo")
        secs(.6)
        tap("done"); secs(.4)
        mark("baked")
        for (n in listOf(0, 5, 7)) { key(4, n, hold = 5); secs(1.5) }
        secs(.8)
    }

    /** B12: the Sample sheet's tools. Trim a loop by dragging, add effects, chop it onto pads and play the slices. */
    @Test fun b12() = shoot("b12") {
        state.newMess(); state.setBpm(120)
        state.putOnPad(4, throne); load(throne)
        state.selectPad(4, false)
        settle(); action(); secs(.4)
        tap("pad-4", hold = 4); secs(2.1)
        mark("trim")
        tap("open-sample"); secs(.5)
        tap("dtab-trim"); secs(.4)
        val y = textBox("HEAR IT").top - 170f
        val left = 28f; val right = 766f
        slideAt(left + 6f, y, left + (right - left) * .25f, y, 18, "trim-start"); secs(1.0)
        slideAt(right - 6f, y, left + (right - left) * .75f, y, 18, "trim-end"); secs(1.3)
        mark("effects")
        tap("dtab-fx"); secs(.4)
        tap("dfx-rvb"); secs(1.3)
        tap("dfx-echo"); secs(1.6)
        mark("chop")
        tap("dtab-tools"); secs(.4)
        tapExact("4 SLICES"); secs(.6)
        tapExact("CHOP TO PADS"); secs(.8)
        if (state.overlay != null) { tap("done"); secs(.4) }
        mark("slices")
        val got = (8 until 16).filter { state.project.kit[it].isNotEmpty() }
        println("DEMO chop pads $got")
        for (d in listOf(0, 1, 2, 3, 0, 0, 2, 3, 1, 0)) { val pad = got.getOrNull(d) ?: continue; tap("pad-$pad", hold = 3, note = "slice"); frames(6) }
        secs(.8)
    }

    /** B13: perform effects over a playing loop: stutter, echo throw, tape stop. */
    @Test fun b13() = shoot("b13") {
        beat(throne)
        settle(); action(); secs(.3)
        tap("play"); untilStep(8)
        mark("fx-open")
        tap("fx-toggle"); frames(8)
        show("fx-tape"); bars(1)
        untilStep(8); mark("stutter")
        fingerDown(0, "fx-stutter", .55f, .5f, "fx"); frames(30); fingerUp(0, "fx-stutter", "fx"); frame()
        println("DEMO b13 step after stutter ${state.heardStep}")
        frames(60); untilStep(12); mark("echo")
        fingerDown(0, "fx-echo", .55f, .5f, "fx"); frames(15); fingerUp(0, "fx-echo", "fx"); frame()
        frames(75); untilStep(8); mark("tape")
        fingerDown(0, "fx-tape", .55f, .5f, "fx"); frames(36); fingerUp(0, "fx-tape", "fx"); frame()
        println("DEMO b13 step after tape ${state.heardStep}")
        mark("back"); frames(80)
        tap("play"); secs(.8)
    }

    /** B14: Song. Loops A, B and C painted into bars and played as a song. */
    @Test fun b14() = shoot("b14") {
        state.newMess(); state.setBpm(132)
        state.newSynth(4, analog("Acid line"))
        val acid = state.project.kit[4]
        state.edit {
            val k = kit[0]; val c = kit[1]; val h = kit[2]; val sn = kit[3]
            for (j in listOf(0, 4, 8, 12)) { pats[0].setStep(k, j, true); pats[1].setStep(k, j, true) }
            for (j in listOf(2, 6, 10, 14)) { pats[0].setStep(h, j, true); pats[1].setStep(h, j, true) }
            for (j in listOf(4, 12)) pats[1].setStep(sn, j, true)
            for ((st, p, l) in listOf(Triple(0, 0, 2), Triple(2, 0, 1), Triple(3, 12, 1), Triple(4, 0, 2), Triple(6, 3, 2), Triple(8, 0, 2), Triple(10, 7, 2), Triple(12, 10, 2), Triple(14, 7, 2))) pats[1].notes(acid).add(Project.Note(st, p, l))
            for (j in listOf(0, 3, 6, 10)) pats[2].setStep(k, j, true)
            for (j in listOf(4, 12, 14, 15)) pats[2].setStep(c, j, true)
            for (j in 0 until 16) pats[2].setStep(h, j, true)
        }
        state.tab = Tab.Song
        settle(); action(); secs(.5)
        mark("paint")
        brush("A"); tap("bar-0"); frames(3); tap("bar-1"); frames(4)
        brush("B"); tap("bar-2"); frames(3); tap("bar-3"); frames(4)
        brush("C"); tap("bar-4"); frames(4)
        brush("B"); tap("bar-5"); frames(8)
        mark("play")
        tapExact("PLAY THE SONG"); frames(8)
        tap("play"); frames(332)
        tap("play"); secs(.8)
    }

    /** B15: resample. The whole loop becomes one sound on a pad, played back and then pitched on its own keys. */
    @Test fun b15() = shoot("b15") {
        beat(throne)
        state.tab = Tab.Steps
        settle(); action(); secs(.3)
        tap("play"); bars(2)
        tap("play"); frames(10)
        mark("resample")
        tapExact("RESAMPLE TO A PAD"); frames(4)
        waitWhile { state.busy != null }; secs(1.2)
        mark("result")
        tap("tab-Pads"); secs(.5)
        tap("pad-8", hold = 4); secs(2.2)
        mark("keys")
        tap("pad-8", hold = 20); secs(.5)
        tap("menu-piano"); secs(.6)
        for (n in listOf(0, 5, 7)) { key(8, n, hold = 5); secs(1.1) }
        secs(1.2)
    }

    /** C3: a latched loop that restarts on the beat, Flow chords on a Padiano, and an XY sweep, all at once. */
    @Test fun c3() = shoot("c3") {
        beat(throne)
        state.setRoot(2); state.setScale("Minor")
        state.edit { pat().setStep(throne, 0, false) }
        state.setEdit(throne, state.project.edit(throne).copy().also { it.held = "beat"; it.beatN = 4 }, true)
        state.newSynth(4, analog("Soft keys"))
        state.toggleKeyboard(4)
        state.pickChordMode("triad"); state.switchChordPlay(true)
        state.changeHoldCount(1)
        state.setLive { nrRate = 2.0; nrStyle = "flow" }
        state.switchDockOpen(true)
        settle(); action(); secs(.3)
        tap("play"); untilStep(10)
        mark("hold")
        tap("dock-hold"); frames(4); untilStep(15)
        tap("pad-3", hold = 3, note = "loop pad"); bars(2); untilStep(10)
        mark("flow")
        tap("dock-repeat"); frames(4)
        for ((i, n) in listOf(2, 10, 5, 0, 2).withIndex()) {
            if (i == 2) { mark("xy"); tap("piano-xy-4"); frames(6) }
            untilStep(0)
            fingerDown(0, "piano-keys-4", pk(4, n), .5f, "chord"); frames(2)
            if (i >= 2) {
                fingerDown(1, "xyp-4", .15f, .75f, "xy"); frame()
                var f = 0
                while (state.heardStep != 15 && f < 200) {
                    val t = Math.abs(((f % 40) / 20f) - 1f)
                    fingerMove(1, "xyp-4", .85f - .7f * t, .25f + .5f * t, "xy"); frame(); f++
                }
                fingerUp(1, "xyp-4", "xy")
            } else untilStep(15)
            fingerUp(0, "piano-keys-4", "chord"); frame()
        }
        mark("out")
        untilStep(0); tap("pad-3", hold = 3, note = "loop pad"); bars(1)
        tap("play"); secs(.8)
    }

    /** C4: record a melody live with the loop running, see it in Roll, then paint the loop into the Song. */
    @Test fun c4() = shoot("c4") {
        beat(null, 110)
        state.newSynth(4, Synth.PLUCK.presets.first { it.name == "Nylon" })
        state.toggleKeyboard(4)
        state.selectPad(4, false)
        settle(); action(); secs(.4)
        mark("rec")
        tap("rec"); frames(6)
        tap("play"); untilStep(13)
        for ((st, n) in listOf(0 to 0, 3 to 3, 6 to 7, 8 to 12, 10 to 10, 12 to 7, 14 to 3)) { untilStep(st); tap("piano-keys-4", pk(4, n), .5f, hold = 2, note = "key") }
        untilStep(15); untilStep(0)
        tap("rec"); frames(4)
        mark("playback"); bars(1)
        mark("roll"); tap("tab-Roll"); bars(1)
        mark("song"); tap("tab-Song"); secs(.5)
        brush("A")
        for (b in 0..3) { tap("bar-$b"); frames(3) }
        tapExact("PLAY THE SONG"); frames(6)
        bars(2)
        tap("play"); secs(.8)
        println("DEMO c4 notes recorded: ${state.project.pat().notes(state.project.kit[4]).size}")
    }

    /** C5: resample a loop to a pad, then make a granular synth whose grains come from that pad. */
    @Test fun c5() = shoot("c5") {
        beat(throne)
        state.tab = Tab.Steps
        settle(); action(); secs(.3)
        tap("play"); bars(2)
        tap("play"); frames(10)
        mark("resample")
        tapExact("RESAMPLE TO A PAD"); frames(4)
        waitWhile { state.busy != null }; secs(1.0)
        mark("hear")
        tap("tab-Pads"); secs(.4)
        tap("pad-8", hold = 4); secs(2.2)
        mark("synth")
        tap("tab-Synth"); secs(.6)
        tap("synth-new-empty"); secs(.7)
        tap("synthkind-grain"); secs(.7)
        mark("source")
        tap("grain-src"); secs(.5)
        tapText("Resample A"); secs(.6)
        mark("grains")
        val sp = state.project.sel
        fingerDown(0, "piano-keys-$sp", pk(sp, 0), .5f, "key"); frames(18)
        turnAt.remove("knob-pos")
        fingerDown(1, "knob-pos", .5f, .28f, "knob"); frame()
        turn("knob-pos", .6f, 60); turn("knob-pos", -.85f, 80); turn("knob-pos", .35f, 35)
        fingerUp(1, "knob-pos", "knob"); frames(8)
        fingerUp(0, "piano-keys-$sp", "key"); secs(1.6)
    }

    /* ---------------- Tier 3 ---------------- */

    /** E1: My Desk. Three saved layouts of the same machine, switched while the loop plays. */
    @Test fun e1() = shoot("e1") {
        beat(throne)
        settle(); action(); secs(.3)
        tap("play"); untilStep(8)
        mark("desk")
        tap("view-desk"); bars(1); dumpTags("desk-perform")
        for (d in listOf(4, 5, 6, 5)) { tap("pad-$d", hold = 3, note = "pad"); frames(8) }
        mark("beats")
        tap("desk-1"); bars(1); dumpTags("desk-beats"); bars(1)
        mark("sampling")
        tap("desk-2"); bars(1); dumpTags("desk-sampling"); bars(1)
        mark("arrange")
        tap("desk-0"); frames(10)
        tap("console-edit"); bars(1); dumpTags("desk-arrange")
        tap("console-edit"); frames(10)
        tap("play"); secs(.6)
    }

    /** E2: Sounds. The jars, the Qbldy Loops and Hits, search, a star, and a sound sent to a pad. */
    @Test fun e2() = shoot("e2") {
        state.newMess()
        state.tab = Tab.Sounds
        settle(); action(); secs(.6)
        mark("jars")
        tap("mode-jar"); secs(.8)
        val jb = rule.onNodeWithTag("jar-chips").fetchSemanticsNode().boundsInWindow
        var sw = 0
        while (!onScreen("jar-loops") && sw++ < 5) { slideAt(700f, jb.center.y, 250f, jb.center.y, 10, "swipe"); frames(8) }
        mark("loops")
        tap("jar-loops"); secs(.8); dumpTags("loops")
        tap("row-loop:ametrine-1", .3f, .5f, hold = 3); secs(2.1)
        tap("row-loop:old-wheezy-1", .3f, .5f, hold = 3); secs(2.1)
        mark("star")
        tap("row-loop:ametrine-1", .73f, .5f, hold = 3); secs(.6)
        mark("hits")
        sw = 0
        while (!onScreen("jar-hits") && sw++ < 5) { slideAt(700f, jb.center.y, 450f, jb.center.y, 8, "swipe"); frames(8) }
        tap("jar-hits"); secs(.6)
        for (k in listOf("hit:ametrine-1", "hit:ametrine-2", "hit:ametrine-3", "hit:old-wheezy-1")) { tap("row-$k", .3f, .5f, hold = 3); secs(.5) }
        mark("search")
        rule.onAllNodes(hasSetTextAction()).onFirst().performTextInput("wheezy"); secs(1.2); dumpTags("search")
        if (has("row-samples/wheezy-organ.wav")) { tap("row-samples/wheezy-organ.wav", .3f, .5f, hold = 3); secs(1.4) }
        rule.onAllNodes(hasSetTextAction()).onFirst().performTextClearance(); frames(8)
        mark("favorites")
        tap("mode-fav"); secs(.9); dumpTags("fav")
        if (has("put-loop:ametrine-1")) { tap("put-loop:ametrine-1"); secs(.8) }
        mark("on-pad")
        tap("tab-Pads"); secs(.5)
        val pad = state.project.kit.indexOf("loop:ametrine-1")
        if (pad in 0..15) { tap("pad-$pad", hold = 4); secs(2.1) }
    }

    /** E3: FM presets: E piano, Bell and Metal from the same four operators. */
    @Test fun e3() = shoot("e3") {
        state.newMess()
        state.newSynth(0, Synth.FM.presets.first { it.name == "E piano" })
        state.tab = Tab.Synth
        settle(); action(); secs(.5)
        fun phrase() {
            for (n in listOf(0, 3, 7, 12)) { key(0, n, hold = 6); frames(3) }
            fingerDown(0, "piano-keys-0", pk(0, 0), .5f, "c"); fingerDown(1, "piano-keys-0", pk(0, 3), .5f, "eb"); fingerDown(2, "piano-keys-0", pk(0, 7), .5f, "g")
            frames(36)
            fingerUp(0, "piano-keys-0", "c"); fingerUp(1, "piano-keys-0", "eb"); fingerUp(2, "piano-keys-0", "g"); secs(.7)
        }
        mark("epiano"); phrase()
        for (name in listOf("Bell", "Metal")) {
            tap("synth-preset"); secs(.5)
            tapExact(name); secs(.4)
            mark(name.lowercase()); phrase()
        }
        secs(.5)
    }

    /** E4: Wavetable, Vowel talk: one oscillator sweeping through vowel shapes so a bass line seems to speak. */
    @Test fun e4() = shoot("e4") {
        state.newMess()
        state.newSynth(0, Synth.WAVE.presets.first { it.name == "Vowel talk" })
        state.tab = Tab.Synth
        settle(); action(); secs(.5)
        mark("talk")
        val line = listOf(0, 0, 3, 5, 7, 5, 3, 0)
        for ((i, n) in line.withIndex()) {
            fingerDown(i % 2, "piano-keys-0", pk(0, n), .5f, "k${i % 2}"); frames(3)
            if (i > 0) fingerUp((i + 1) % 2, "piano-keys-0", "k${(i + 1) % 2}")
            frames(13)
        }
        mark("position")
        turnAt.remove("knob-pos")
        fingerDown(2, "knob-pos", .5f, .28f, "knob"); frame()
        turn("knob-pos", .7f, 50, id = 2); turn("knob-pos", -.5f, 40, id = 2)
        fingerUp(2, "knob-pos", "knob"); frames(6)
        fingerUp((line.size - 1) % 2, "piano-keys-0", "k${(line.size - 1) % 2}"); secs(1.0)
    }

    /** E5: the Latency screen, in Notes for Claude. Screens only: this render has no speaker to measure. */
    @Test fun e5() = shoot("e5") {
        beat(null)
        settle(); action(); secs(.4)
        mark("menu")
        tap("menu"); secs(.6)
        tap("notes"); secs(.9)
        mark("latency")
        var sw = 0
        while (!onScreen("audio-check") && sw++ < 8) { slideAt(400f, 1300f, 400f, 700f, 10, "swipe"); frames(8) }
        tap("audio-check"); secs(1.2); dumpTags("latency")
        if (has("check-pad")) { for (i in 0 until 5) { tap("check-pad", hold = 3); frames(12) } }
        secs(1.2); dumpTags("latency2")
        slideAt(400f, 1400f, 400f, 600f, 16, "swipe"); frames(10)
        secs(1.0)
    }

    /** E6: Notes for Claude: the bug button. A note is written and kept on the phone; nothing is sent. */
    @Test fun e6() = shoot("e6") {
        beat(null)
        settle(); action(); secs(.4)
        mark("menu")
        tap("menu"); secs(.6)
        tap("notes"); secs(1.0); dumpTags("notes")
        mark("write")
        var sw = 0
        while (!onScreen("category-chips") && sw++ < 8) { slideAt(400f, 1300f, 400f, 700f, 10, "swipe"); frames(8) }
        dumpTags("notes-chips")
        val words = "The hat on pad 3 is louder than the kick."
        for (i in 4..words.length step 4) { rule.onAllNodes(hasSetTextAction()).onFirst().performTextClearance(); rule.onAllNodes(hasSetTextAction()).onFirst().performTextInput(words.take(i)); frames(2) }
        rule.onAllNodes(hasSetTextAction()).onFirst().performTextClearance(); rule.onAllNodes(hasSetTextAction()).onFirst().performTextInput(words)
        secs(2.0); dumpTags("notes-written")
    }

    /** E7: Jam key. A tune on the Padiano with nothing recording; the row names its key; Use puts the mess in it. */
    @Test fun e7() = shoot("e7") {
        state.newMess()
        state.newSynth(0, analog("Soft keys"))
        state.selectPad(0, false); state.toggleKeyboard(0)
        settle(); action(); secs(.5)
        mark("on")
        tap("jam-key"); secs(.8)
        mark("play")
        val tune = listOf(4, 7, 11, 9, 7, 6, 4, 2, 4, 7, 9, 11, 12, 11, 7, 4, 6, 9, 11, 7, 4, 2, 6, 4, 7, 11, 12, 11, 9, 7, 6, 4)
        var named = false
        for (n in tune) {
            key(0, n, hold = 7); frames(3)
            if (!named && state.jamKey.isNotEmpty()) { named = true; mark("named") }
        }
        secs(1.2); dumpTags("jam")
        mark("use")
        if (state.jamKey.isNotEmpty() && !state.jamFitsMess()) tap("jam-key-use")
        secs(1.6)
        File(dir, "jam.txt").writeText("key=${state.jamKey} root=${state.project.root} scale=${state.project.scale} secs=${state.jamSecs}\n")
    }
}
