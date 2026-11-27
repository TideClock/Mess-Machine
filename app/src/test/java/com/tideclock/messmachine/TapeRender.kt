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
import androidx.compose.ui.test.swipe
import androidx.core.view.drawToBitmap
import androidx.test.core.app.ApplicationProvider
import com.tideclock.messmachine.engine.Synth
import com.tideclock.messmachine.engine.Engine
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
 * The Evidence Tape video (MM-V2), drawn frame by frame from the real screens with the real engine's sound.
 * Not a test of anything: it only runs when DEMO_SHOT is set (the Demo render workflow sets it).
 * Each frame: play any touches due, render exactly one frame's worth of audio, let the screen
 * catch up, save the picture. Sound and picture come from the same clock, so they cannot drift.
 * Out: build/demo/<shot>/f_00000.jpg ..., audio.wav, touches.csv (frame,type,x,y,note).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class TapeRender {
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
            .putInt("guideSeen", BuildConfig.VERSION_CODE).putString("skin", "specimen").putBoolean("click", false).putInt("countIn", 0)
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
        rule.onNodeWithTag(tag).performTouchInput { moveTo(id, Offset(width * fx, height * fy), 33) }
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
        return b.width > 40f && b.height > 20f && b.left >= 0f && b.right <= 1233f && b.bottom <= 2535f
    }
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

    /* ---------- tape helpers ---------- */

    /** A quick swipe off a pad (HotKeys): up, down, left or right, over n frames. */
    private fun swipe(tag: String, dir: String, n: Int = 4) {
        val dx = when (dir) { "left" -> -1.2f; "right" -> 1.2f; else -> 0f }
        val dy = when (dir) { "up" -> -1.2f; "down" -> 1.2f; else -> 0f }
        fingerDown(0, tag, .5f, .5f, "swipe-$dir"); frame()
        for (i in 1..n) { fingerMove(0, tag, .5f + dx * i / n, .5f + dy * i / n, "swipe-$dir"); frame() }
        fingerUp(0, tag, "swipe-$dir"); frame()
    }
    private fun snap(name: String) {
        state.toast = null; settle(); dumpTags(name)
        grab().let { b -> File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle() }
    }
    private fun step(name: String, body: () -> Unit) {
        try { body() } catch (e: Throwable) {
            println("DEMO stepfail $name $e")
            File(dir, "stepfail-$name.txt").writeText(e.stackTraceToString().take(4000))
            try { snap("stepfail-$name") } catch (_: Throwable) {}
        }
    }
    private fun note(name: String, text: String) { File(dir, "$name.txt").writeText(text + "\n") }

    /* ---------- the tape: MM-V2 "Evidence Tape" ---------- */

    private val harp = "samples/glass-harp.wav"
    private val dance = "loop:new-dance-1"
    private fun analog(name: String) = Synth.ANALOG.presets.first { it.name == name }
    private fun Project.steps(k: String, vararg js: Int) { for (j in js) pat().setStep(k, j, true) }

    /** A new mess at 120 in C harmonic minor, with whatever steps the shot wants under it. */
    private fun mess(block: Project.() -> Unit = {}) {
        state.newMess(); state.setBpm(120)
        state.edit { scale = "Harmonic minor"; root = 0; block() }
        settle()
    }
    private fun Project.fullBeat() {
        steps(kit[0], 0, 4, 8, 12); steps(kit[2], 2, 6, 10, 14); steps(kit[3], 4, 12)
        steps(kit[5], 3, 7, 11, 14); steps(kit[4], 10); steps(kit[7], 0)
    }
    /** Filming starts and the loop starts with it, so bar lines sit on whole seconds. */
    private fun roll() { settle(); action(); state.togglePlay() }
    private fun at(f: Int) { while (frameNo < f) frame() }
    private fun slot(pad: Int, j: Int, hold: Int) = tap("chord-slots-$pad", (j % 6 + .5f) / 6f, (j / 6 + .5f) / 2f, hold = hold, note = "chord $j")
    private fun press(tag: String, n: Int) { fingerDown(0, tag, .5f, .12f, tag); frames(n); fingerUp(0, tag, tag); frame() }
    private var fx0 = .5f; private var fy0 = .5f
    /** A circle on a pad: the finger stays down afterwards, inside the sphere it opened. */
    private fun circle(tag: String, f0: Int) {
        at(f0); fingerDown(0, tag, .63f, .5f, "circle"); frame()
        for (k in 1..20) { val a = k * (2 * Math.PI / 18); fx0 = .5f + .13f * kotlin.math.cos(a).toFloat(); fy0 = .5f + .13f * kotlin.math.sin(a).toFloat(); fingerMove(0, tag, fx0, fy0, "circle"); frame() }
    }
    /** The held finger glides to a new spot (in pad widths from the pad's corner) over n frames. */
    private fun glide(tag: String, x: Float, y: Float, n: Int) {
        val x0 = fx0; val y0 = fy0
        for (i in 1..n) { val t = i / n.toFloat(); val e = t * t * (3 - 2 * t); fx0 = x0 + (x - x0) * e; fy0 = y0 + (y - y0) * e; fingerMove(0, tag, fx0, fy0, "sphere"); frame() }
    }

    /** Exhibit 1, Four Ways In: every swipe off one pad, opened and closed, then all four in a row. */
    @Test fun x1() = shoot("x1") {
        mess { steps(kit[0], 0, 4, 8, 12) }
        roll()
        val p = "pad-5"
        at(105); swipe(p, "up"); at(135); swipe(p, "up")
        at(165); swipe(p, "right"); at(195); swipe(p, "right")
        at(225); swipe(p, "down"); at(255); swipe(p, "down")
        at(285); swipe(p, "left"); at(318); tap("done", hold = 2)
        at(330); swipe(p, "up", 3); at(345); swipe(p, "right", 3); at(360); swipe(p, "down", 3); at(375); swipe(p, "left", 3)
        at(402); tap("done", hold = 2)
        at(420)
    }

    /** Exhibit 2, It Grows Keys: the Padiano out of a pad, then the Haunted chord set. */
    @Test fun x2() = shoot("x2") {
        mess { steps(kit[0], 0, 4, 8, 12); steps(kit[2], 2, 6, 10, 14) }
        state.putOnPad(5, harp); load(harp)
        state.pickChordMode("set:Haunted"); state.toggleChords(); settle()
        roll()
        at(60); swipe("pad-5", "down")
        for ((i, n) in listOf(0, 3, 7, 12, 7, 3, 0).withIndex()) { at(135 + i * 15); key(5, n, hold = 7) }
        at(240); tap("chords-toggle", hold = 3)
        println("DEMO x2 chord=${state.chordMode} scale=${state.project.scale}")
        at(330); slot(5, 0, 24)
        at(360); slot(5, 3, 24); at(390); slot(5, 4, 24); at(420); slot(5, 5, 24); at(450); slot(5, 0, 26)
        at(480)
    }

    /** Exhibit 3, It Takes Sounds Apart: the Sample sheet, a trim, a chop onto pads, the pieces played. */
    @Test fun x3() = shoot("x3") {
        mess { steps(kit[0], 0, 4, 8, 12) }
        state.putOnPad(5, dance); load(dance)
        roll()
        at(60); swipe("pad-5", "left")
        at(113); tapExact("HEAR IT", hold = 3)
        at(128); tap("dtab-trim", hold = 2)
        at(136); drag("sheet", .9f, .203f, .503f, .203f, 16, note = "trim")
        if (state.project.edit(dance).en > .99) {
            // the frame by frame drag did not take: the same drag as one gesture
            println("DEMO x3 trim: frame drag did nothing (en=${state.project.edit(dance).en}), one-call swipe")
            val a = rootPoint("sheet", .9f, .203f); val b = rootPoint("sheet", .503f, .203f)
            for (i in 0..8) log.append("${frameNo + i},${if (i == 0) "down" else "move"},${(a.x + (b.x - a.x) * i / 8f).toInt()},${a.y.toInt()},trim\n")
            rule.onNodeWithTag("sheet").performTouchInput { swipe(Offset(width * .9f, height * .203f), Offset(width * .503f, height * .203f), 280) }
            frames(9); log.append("$frameNo,up,0,0,trim\n")
        }
        println("DEMO x3 trim en=${state.project.edit(dance).en} st=${state.project.edit(dance).st}")
        at(165); tap("dtab-tools", hold = 2)
        at(176); tapExact("4 SLICES", hold = 2)
        at(196); tapExact("CHOP TO PADS", hold = 3)
        println("DEMO x3 kit=${state.project.kit.take(16)} overlay=${state.overlay}")
        frames(4)
        val end = System.currentTimeMillis() + 15000
        while (!state.project.kit.filter { it.isNotEmpty() }.all { state.lib.loaded(it) } && System.currentTimeMillis() < end) { Thread.sleep(20); settle() }
        val seq = listOf(8, 9, 10, 11, 8, 8, 10, 11, 9, 9, 10, 8, 11, 10, 9, 8)
        for ((i, p) in seq.withIndex()) { at(240 + (i * 7.5).toInt()); tap("pad-$p", hold = 2) }
        at(360)
    }

    /** Exhibit 4, The Vials: a pad's own effects poured in, bypassed, and back. */
    @Test fun x4() = shoot("x4") {
        mess { steps(kit[0], 0, 4, 8, 12); steps(kit[5], 3, 6, 11, 14); steps(kit[2], 2, 10) }
        roll()
        at(45); swipe("pad-5", "right")
        at(125); drag("fx-8-5", .5f, .92f, .5f, .3f, 18, note = "vial")
        at(152); drag("fx-9-5", .5f, .92f, .5f, .45f, 18, note = "vial")
        println("DEMO x4 sends=${state.fxSend}")
        at(195); tap("fxstrip-off-5", hold = 3)
        at(232); tap("fxstrip-off-5", hold = 3)
        at(300)
    }

    /** Exhibit 5, Six Kinds Live Inside: one synth pad, all six kinds, the same notes on each. */
    @Test fun x5() = shoot("x5") {
        mess { steps(kit[0], 0, 4, 8, 12) }
        state.newSynth(8, analog("Warm pad"))
        state.tab = Tab.Synth
        roll()
        dumpTags("synth-pad")
        at(6); key(8, 0, 26); at(40); key(8, 7, 26)
        val first = listOf(0, 3, 7, 8, 0, 12)
        for ((i, k) in listOf("analog", "fm", "wave", "grain", "drum", "pluck").withIndex()) {
            val f = 75 + i * 30
            at(f); if (i > 0) { tap("synthkind-$k", hold = 2); println("DEMO x5 kind=${state.synthOn(8)?.kind?.id}") }
            at(f + 4); key(8, first[i], 22)
        }
        for ((i, n) in listOf(0, 3, 7, 12, 8, 7, 3, 0).withIndex()) { at(262 + i * 11); key(8, n, 8) }
        at(360)
    }

    /** Exhibit 6, It Has Eyes: a circle opens the sphere, a push bends the sound, one recorded move keeps playing. */
    @Test fun x6() = shoot("x6") {
        mess { }
        state.putOnPad(5, dance); load(dance)
        state.edit { pat().setStep(dance, 0, true) }
        state.setXy(5, XySetup.PRESETS[2])
        state.selectPad(0, false)
        roll()
        val p = "pad-5"
        circle(p, 90)
        println("DEMO x6 sphere=${state.sphere}")
        at(128); glide(p, 1.45f, .5f, 18); frames(6); glide(p, .5f, .5f, 12); glide(p, -.45f, .5f, 18); frames(4); glide(p, .5f, -.4f, 14); glide(p, 1.3f, .2f, 12)
        at(222); fingerUp(0, p, "sphere"); frame()
        at(230); tap("rec", hold = 3)
        circle(p, 240)
        glide(p, 1.45f, .5f, 10); glide(p, .5f, .5f, 6); glide(p, -.45f, .5f, 10); glide(p, 1.3f, .1f, 8)
        at(297); fingerUp(0, p, "sphere"); frame()
        at(302); println("DEMO x6 motion=" + state.project.pat().motion.map { (k, v) -> "$k=" + v.take(16).joinToString(",") { if (it.isNaN()) "-" else "%.1f".format(it) } })
        for (f in listOf(330, 345, 352, 356, 400, 412)) { at(f); println("DEMO x6 f=$f step=${state.heardStep} rate=${state.engine.padRate(5)} rec=${state.live.rec} playing=${state.playing}") }
        at(480)
    }

    /** Exhibit 7, It Breaks On Command: stutter, echo throw and tape stop on the whole mix. */
    @Test fun x7() = shoot("x7") {
        mess { fullBeat() }
        state.tab = Tab.Perform
        roll()
        at(60); press("fx-stutter", 30)
        at(97); press("fx-echo", 30)
        at(157); press("fx-tape", 52)
        at(300)
    }

    /** Exhibit 8's sound: the loop, untouched, under the inventory close-ups. */
    @Test fun x8() = shoot("x8") {
        mess { fullBeat() }
        roll()
        at(420)
    }

    /** Exhibit 9 and the close: six layers recorded in six bars, then banks, skins, a tape stop and one last hit. */
    @Test fun x9() = shoot("x9") {
        mess { }
        state.putOnPad(5, harp); load(harp); state.setXy(5, XySetup.PRESETS[2])
        state.newSynth(8, analog("Soft keys"))
        state.pickChordMode("set:Haunted")
        state.openPadiano(8)
        state.selectPad(0, false)
        roll()
        at(45); tap("rec", hold = 3)
        for (b in 0..3) { at(120 + b * 15); tap("pad-0", hold = 2) }
        for (b in listOf(1, 3)) { at(180 + b * 15); tap("pad-3", hold = 2) }
        for (i in 0..7) { at(240 + (i * 7.5).toInt()); tap("pad-2", hold = 2) }
        for (s in listOf(0, 3, 6, 10)) { at(300 + (s * 3.75).toInt()); tap("pad-4", hold = 2) }
        at(360); slot(8, 0, 22); at(390); slot(8, 5, 22)
        circle("pad-5", 420)
        glide("pad-5", 1.45f, .5f, 10); glide("pad-5", .5f, .5f, 6); glide("pad-5", -.45f, .5f, 10); glide("pad-5", 1.3f, .1f, 8)
        at(476); fingerUp(0, "pad-5", "sphere"); frame()
        at(480)
        val pr = state.project
        println("DEMO x9 layers " + listOf(0, 3, 2, 4, 8, 5).joinToString(" ") { "${it}:${pr.pat().notes(pr.kit[it]).size}" })
        at(495); tap("rec", hold = 3)
        for ((i, b) in listOf(1, 2, 3, 0).withIndex()) { at(525 + i * 15); tap("bank-$b", hold = 2) }
        at(573); tap("tab-Synth", hold = 2); at(603); tap("tab-Pads", hold = 2)
        for ((i, s) in listOf("classic", "stage", "felt", "rust", "specimen").withIndex()) { at(618 + i * 15); state.pickSkin(s) }
        at(900); state.fxPress("tape", true, 1f)
        at(958); state.stop(); state.fxPress("tape", false)
        at(1035); tap("pad-7", hold = 3)
        at(1140)
    }

    /** A short of its own, It Changes Its Skin: one loop, the five skins, two beats each. */
    @Test fun x10() = shoot("x10") {
        mess { fullBeat() }
        roll()
        for ((i, k) in listOf("classic", "stage", "felt", "rust", "specimen", "classic", "stage", "felt", "rust", "specimen").withIndex()) { at(60 + i * 30); state.pickSkin(k) }
        at(420)
    }

    /** Horror v3, chapter 03: two Padianos at once, bass under one row and Haunted chords under the next. */
    @Test fun x11() = shoot("x11") {
        mess { steps(kit[0], 0, 4, 8, 12); steps(kit[2], 2, 6, 10, 14); steps(kit[3], 4, 12) }
        state.newSynth(1, analog("Mono bass"))
        state.newSynth(5, analog("Soft keys"))
        state.pickChordMode("set:Haunted")
        state.selectPad(0, false)
        roll()
        at(40); swipe("pad-1", "down")
        at(90); swipe("pad-5", "down")
        println("DEMO x11 keyboards=${state.keyboards.toList()}")
        println("DEMO x11 chord=${state.chordMode}")
        // chords are on for every Padiano: the mono bass plays one note of each chord, the keys play all of it
        val prog = listOf(0, 3, 4, 5)
        for ((i, j) in prog.withIndex()) {
            for (e in 0 until 4) { at(150 + i * 60 + e * 15); slot(1, j, 6) }
            at(152 + i * 60); slot(5, j, 40)
        }
        at(420)
    }

    /** Horror v3, chapter 04: Re-pitch moves a loop into the mess's key. */
    @Test fun x12() = shoot("x12") {
        val k = "loop:give-me-throne-1"
        mess { steps(kit[0], 0, 4, 8, 12) }
        state.setScale("Minor"); state.setRoot(0)
        state.putOnPad(5, k); load(k)
        state.edit { pat().setStep(k, 0, true) }
        roll()
        at(30); swipe("pad-5", "left")
        at(75); if (has("repitch")) { show("repitch"); frames(4) }
        at(100); tap("repitch", hold = 3)
        val end = System.currentTimeMillis() + 15000
        while (state.project.edit(k).apKind.isEmpty() && System.currentTimeMillis() < end) { Thread.sleep(20); settle() }
        println("DEMO x12 ap=${state.project.edit(k).ap} kind=${state.project.edit(k).apKind}")
        at(300)
    }

    /** Horror v3, chapter 07: mouth control. No microphone in the renderer, so a stand-in mic feeds a made-up
     *  voice ("wow", vowels ooo to aah and back) into the app's real mouth tracker; the screen says so. */
    private class ScriptMouth : com.tideclock.messmachine.platform.MouthIn {
        override val tracker = com.tideclock.messmachine.engine.MouthTracker(16000)
        @Volatile override var running = false
        override fun start(): Boolean { tracker.reset(); running = true; return true }
        override fun stop() { running = false }
    }
    private var vPhase = 0.0
    /** One frame (1/30 s) of a sung vowel at 16 kHz: harmonics of 140 Hz shaped by two formants. */
    private fun vowel(f1: Double, f2: Double, amp: Double): FloatArray {
        val n = 16000 / 30; val out = FloatArray(n)
        for (i in 0 until n) {
            var v = 0.0
            for (h in 1..28) {
                val f = 140.0 * h
                val g = Math.exp(-Math.pow((f - f1) / 110.0, 2.0)) + .7 * Math.exp(-Math.pow((f - f2) / 160.0, 2.0)) + .02
                v += g * Math.sin(vPhase * h) / h
            }
            vPhase += 2 * Math.PI * 140.0 / 16000; out[i] = (v * amp).toFloat()
        }
        return out
    }
    @Test fun x13() = shoot("x13") {
        mess { steps(kit[0], 0, 4, 8, 12); steps(kit[2], 2, 6, 10, 14) }
        state.putOnPad(5, dance); load(dance)
        state.edit { pat().setStep(dance, 0, true) }
        state.selectPad(5, false)
        org.robolectric.Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val fake = ScriptMouth()
        AppState::class.java.getDeclaredField("mouthMic").apply { isAccessible = true }.set(state, fake)
        state.switchMouthStay(false)
        state.overlay = Overlay.Mouth
        roll()
        at(20); tap("mouth-toggle", hold = 3)
        println("DEMO x13 mouthOn=${state.mouthOn} running=${fake.running}")
        at(30)
        // "wow" at each of these frames: ooo, open to aah, close to ooo, over 15 frames
        val words = listOf(60, 105, 150, 172, 210, 255)
        for (f in 31 until 330) {
            at(f)
            var f1 = 300.0; var f2 = 800.0; var amp = 0.0
            for (w in words) {
                val t = f - w
                if (t in 0..15) { val k = Math.sin(t / 15.0 * Math.PI); f1 = 300 + 450 * k; f2 = 800 + 1400 * k; amp = .35 }
            }
            fake.tracker.feed(vowel(f1, f2, amp), 0, 16000 / 30)
        }
        at(330)
        at(360)
    }

    /** Horror v3, chapter 10: a keyboard plugged in plays at once. The notes are fed by script, as a test keyboard would send them. */
    @Test fun x14() = shoot("x14") {
        mess { steps(kit[0], 0, 4, 8, 12) }
        state.newSynth(5, analog("Brass"))
        state.selectPad(5, false)
        roll()
        at(20); state.say("MIDI: Test keyboard plugged in. Notes 36 to 51 hit pads 1 to 16; the rest play the selected pad as keys. MIDI map, in the menu, teaches it your own layout.")
        for ((i, n) in listOf(36, 38, 42, 38, 36, 36, 38, 42).withIndex()) { at(60 + i * 15); state.midiDown(n, 110); frames(2); state.midiUp(n) }
        val mel = listOf(60, 63, 67, 70, 72, 70, 67, 63)
        for ((i, n) in mel.withIndex()) { at(180 + i * 15); state.midiDown(n, 100); frames(10); state.midiUp(n) }
        at(330)
    }

    /** Not part of the video's motion: still close-ups of the inventory screens for exhibit 8. */
    @Test fun p1() = shoot("p1") {
        mess { fullBeat(); pat().setChance(kit[2], 6, 50) }
        state.newSynth(8, analog("Warm pad"))
        settle(); action(); frames(3)
        step("hold") { state.openSettingsFor("hold-toggle"); frames(6); snap("inv-hold"); state.tray = null; frames(3) }
        step("levels") { state.selectPad(4, false); tap("open-padmenu"); frames(6); snap("inv-padmenu"); state.tray = null; state.padMenu = -1; frames(3) }
        step("levels2") { state.switchLevels(4); frames(6); snap("inv-levels"); state.switchLevels(4); frames(3) }
        step("chance") { state.tab = Tab.Steps; frames(6); snap("inv-steps"); state.stepSheet = Pair(state.project.kit[2], 6); frames(6); snap("inv-stepsheet"); state.stepSheet = null; frames(3); state.tab = Tab.Pads; frames(3) }
        step("lfo") { state.pickEditTab("mod"); state.openTray("edit:5"); frames(6); snap("inv-lfo"); state.tray = null; frames(3) }
        step("song") { state.loopStripOpen = true; frames(6); snap("inv-loops"); if (has("loop-song")) { tap("loop-song"); frames(6); snap("inv-song") }; state.loopStripOpen = false; frames(3) }
        step("stems") { state.overlay = Overlay.Files; frames(6); snap("inv-files"); if (has("stems-loop")) { show("stems-loop"); frames(3); snap("inv-stems") }; state.overlay = null; frames(3) }
        step("midi") { state.switchMidiMap(true); frames(6); snap("inv-midi"); state.switchMidiMap(false); frames(3) }
        step("mouth") { state.overlay = Overlay.Mouth; frames(6); snap("inv-mouth"); state.overlay = null; frames(3) }
        step("skins") { for (s in listOf("classic", "stage", "felt", "rust", "specimen")) { state.pickSkin(s); frames(4); snap("skin-$s") } }
    }

    /** Not part of the video: looks at today's screens and lists their controls, so the shots can find them. */
    @Test fun p0() = shoot("p0") {
        beat(); settle(); action()
        val t0 = System.currentTimeMillis(); frames(30)
        note("speed", "30 frames ${System.currentTimeMillis() - t0} ms")
        note("kit", state.project.kit.withIndex().joinToString("\n") { "${it.index} ${it.value}" } +
            "\nscale=${state.project.scale} root=${state.project.root} chord=${state.chordMode} skin=${state.skin} bpm=${state.project.bpm} rate=${state.rate}")
        snap("pads")
        step("up") {
            swipe("pad-5", "up"); frames(8); snap("swipe-up"); note("state-up", "tray=${state.tray} sel=${state.project.sel}")
            swipe("pad-5", "up"); frames(8); snap("swipe-up-again"); note("state-up2", "tray=${state.tray}")
            state.tray = null; frames(3)
        }
        step("right") {
            swipe("pad-5", "right"); frames(8); snap("swipe-right"); note("state-right", "keyboards=${state.keyboards.toList()} pack=${state.packOf(5)}")
            state.fxSendTo(5, Engine.T_RVB, .6f, false); state.fxSendTo(5, Engine.T_ECHO, .4f, false); frames(4); snap("swipe-right-poured")
            if (has("fxstrip-off-5")) { tap("fxstrip-off-5"); frames(4); snap("bypass-on"); tap("fxstrip-off-5"); frames(4) }
            if (has("pad-5")) { show("pad-5"); swipe("pad-5", "right"); frames(8) }
            snap("swipe-right-again"); note("state-right2", "keyboards=${state.keyboards.toList()}")
            state.keyboards.clear(); frames(3)
        }
        step("down") {
            swipe("pad-5", "down"); frames(8); snap("swipe-down"); note("state-down", "keyboards=${state.keyboards.toList()} pack=${state.packOf(5)} base=${state.padianoBase[5]} n=${state.padianoN}")
            tap("piano-keys-5", .3f, .5f, hold = 6); frames(6); snap("key-played")
            tap("chords-toggle"); frames(6); snap("chords-on"); note("state-chords", "chord=${state.chordMode}")
            state.pickChordMode("set:Haunted"); frames(6); snap("chords-haunted"); note("state-haunted", "chord=${state.chordMode} scale=${state.project.scale} root=${state.project.root}")
            tap("piano-keys-5", .3f, .5f, hold = 6); frames(6); snap("chord-played")
            state.toggleChords(); state.keyboards.clear(); frames(3)
        }
        step("left") {
            swipe("pad-5", "left"); frames(10); snap("swipe-left"); note("state-left", "overlay=${state.overlay}")
            for (t in listOf("Trim", "Shape", "FX", "Tools", "Play")) step("left-$t") { tapExact(t, pick = 0); frames(6); snap("sample-$t") }
        }
        state.overlay = null; frames(3)
        step("sphere") {
            state.tab = Tab.Pads; frames(3)
            val tag = "pad-6"
            fingerDown(0, tag, .8f, .5f, "circle"); frame()
            for (k in 1..22) { val a = k * (2 * Math.PI / 18); fingerMove(0, tag, .5f + .3f * kotlin.math.cos(a).toFloat(), .5f + .3f * kotlin.math.sin(a).toFloat(), "circle"); frame() }
            snap("sphere"); note("state-sphere", "sphere=${state.sphere}")
            fingerMove(0, tag, 1.4f, .2f, "circle"); frames(3); snap("sphere-pushed")
            fingerUp(0, tag, "circle"); frames(3)
        }
        step("throws") { tap("dock-throws"); frames(8); snap("throws"); tap("dock-throws"); frames(4) }
        step("rec") { tap("rec"); frames(4); snap("rec-on"); tap("rec"); frames(3) }
        for (t in Tab.entries) step("tab-${t.label}") { state.tab = t; frames(6); snap("tab-${t.label}") }
        state.tab = Tab.Pads; frames(3)
        for ((n, o) in listOf("settings" to Overlay.Settings, "mouth" to Overlay.Mouth, "files" to Overlay.Files, "tempo" to Overlay.Tempo)) step("ov-$n") { state.overlay = o; frames(6); snap("ov-$n"); state.overlay = null; frames(3) }
        step("bank") { tap("bank-1"); frames(4); snap("bank-2"); tap("bank-0"); frames(3) }
        step("dock") { for (d in listOf("dock-hold", "dock-repeat", "dock-edit", "dock-more", "dock-xy", "dock-mouth")) step(d) { if (has(d)) { tap(d); frames(6); snap(d); tap(d); frames(4) } } }
    }
}
