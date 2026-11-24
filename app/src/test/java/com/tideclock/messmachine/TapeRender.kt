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
        settle(); dumpTags(name)
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
