package com.tideclock.messmachine

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.drawToBitmap
import androidx.test.core.app.ApplicationProvider
import com.tideclock.messmachine.engine.Synth
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
            .putString("helpSeen", (Tab.entries.map { it.label } + listOf("Desk", "Keys")).joinToString(","))
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
        val bmp = rule.activity.window.decorView.rootView.drawToBitmap(Bitmap.Config.ARGB_8888)
        File(dir, "f_%05d.jpg".format(frameNo)).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 93, it) }
        bmp.recycle()
        frameNo++
    }

    private fun frames(n: Int) { repeat(n) { frame() } }
    private fun secs(s: Double) = frames((s * fps).toInt())

    private fun rootPoint(tag: String, fx: Float, fy: Float): Offset {
        val b = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        return Offset(b.left + b.width * fx, b.top + b.height * fy)
    }

    /** A finger down on a tagged thing (fx, fy: where on it, 0 to 1), held for some frames, then lifted. */
    private fun tap(tag: String, fx: Float = .5f, fy: Float = .5f, hold: Int = 3, note: String = tag) {
        val p = rootPoint(tag, fx, fy)
        log.append("$frameNo,down,${p.x.toInt()},${p.y.toInt()},$note\n")
        rule.onNodeWithTag(tag).performTouchInput { down(Offset(width * fx, height * fy)) }
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

    private fun drum(name: String) = Synth.DRUM.presets.first { it.name == name }

    /** C2: drum synths on four pads, programmed on Steps while the loop plays, sample pads on top. */
    @Test fun c2() {
        Assume.assumeTrue(shot == "c2")
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
        secs(1.4)

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
        finish()
    }
}
