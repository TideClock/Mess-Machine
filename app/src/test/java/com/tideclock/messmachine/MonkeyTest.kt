package com.tideclock.messmachine

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.drawToBitmap
import androidx.test.core.app.ApplicationProvider
import com.tideclock.messmachine.engine.Engine
import com.tideclock.messmachine.engine.Project
import com.tideclock.messmachine.ui.MessMachineApp
import com.tideclock.messmachine.ui.UiFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import kotlin.math.abs

/**
 * The random run, in its own class so a failure here cannot take the big test down with it.
 * From the big test: Every screen of the app is built and driven like a finger would, on a phone-sized
 * screen, with the real sound engine rendering behind it. Each flow checks what changed in the mess
 * or what the engine played, and saves a screenshot to app/build/screens for a person to look at.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class MonkeyTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var state: AppState

    @Before fun setUp() {
        UiFlags.testing = true
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("mess", 0).edit().putInt("guideSeen", BuildConfig.VERSION_CODE).commit()
        state = AppState(ctx)
        rule.setContent { MessMachineApp(state, ticker = false) }
        settle()
        waitFor("the starter kit to load") { state.project.kit.filter { it.isNotEmpty() }.all { state.lib.loaded(it) } }
    }

    /* ---------- helpers ---------- */

    private fun settle() {
        repeat(3) { ShadowLooper.idleMainLooper(); rule.waitForIdle() }
    }

    private fun waitFor(what: String, ms: Long = 15000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!ok()) {
            if (System.currentTimeMillis() > end) throw AssertionError("Timed out waiting for $what")
            Thread.sleep(20)
            settle()
        }
        settle()
    }

    /** Run the engine like the audio thread would and report the loudest sample. */
    private fun loudest(frames: Int = 8192): Float {
        val buf = FloatArray(512 * 2)
        var pk = 0f
        repeat(frames / 512) { state.engine.render(buf, 512); for (v in buf) pk = maxOf(pk, abs(v)) }
        return pk
    }

    private fun shot(name: String) {
        settle()
        val dir = File("build/screens").also { it.mkdirs() }
        val bmp = rule.activity.window.decorView.rootView.drawToBitmap(Bitmap.Config.ARGB_8888)
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SCREEN $name ${bmp.width}x${bmp.height}")
    }

    /** Hold a finger on something long enough for its menu, moving the test clock so the timer fires. */
    private fun longPress(tag: String) {
        rule.onNodeWithTag(tag).performScrollTo()
        // a touch while the page is still gliding only stops the glide, like on a phone: let it land first
        rule.mainClock.advanceTimeBy(600)
        settle()
        rule.onNodeWithTag(tag).performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(800)
        settle()
        println("LONGPRESS $tag menu while held=${state.padMenu}")
        rule.onNodeWithTag(tag).performTouchInput { advanceEventTime(800); up() }
        println("LONGPRESS $tag menu after lift=${state.padMenu}")
        settle()
    }

    /** Scroll a node into view and let the page stop moving, so the next touch lands on it. */
    private fun at(tag: String) = rule.onNodeWithTag(tag).also { it.performScrollTo(); rule.mainClock.advanceTimeBy(600); settle() }

    /** Pads and Desk share the Play tab: "Console" means the Play tab's My Desk view. */
    private fun tab(label: String) {
        if (label == "Console") { rule.onNodeWithTag("tab-Pads").performClick(); settle(); at("view-desk").performClick(); settle(); return }
        rule.onNodeWithTag("tab-$label").performClick(); settle()
    }

    private fun tapPad(d: Int) {
        at("pad-$d").performTouchInput { down(center) }
        settle()
        rule.onNodeWithTag("pad-$d").performTouchInput { up() }
        settle()
    }

    @Test fun monkeyRunsEverywhere() {
        // a long seeded run of random taps, holds, slides and circles on every page, with the loop
        // starting and stopping, sheets opening and Lock going on and off; any crash fails the test
        val rnd = java.util.Random(20261110)
        val tabs = Tab.entries.map { it.label }
        fun mainRoot(): androidx.compose.ui.test.SemanticsNodeInteraction {
            val roots = rule.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes()
            val big = roots.indices.maxByOrNull { roots[it].size.width * roots[it].size.height } ?: 0
            return rule.onAllNodes(androidx.compose.ui.test.isRoot())[big]
        }
        var actions = 0
        repeat(320) { n ->
            if (n % 30 == 0) {
                state.padMenu = -1; state.slotMenu = -1; state.barMenu = -1; state.fxEditing = null
                if (state.locked) state.unlock()
                state.overlay = null
                state.sphere = null
            }
            // text boxes (the jar search, mess names, notes) only get taps: a long press or slide on one opens
            // Android's magnifier, which the test phone cannot draw
            val textHere = state.tab == Tab.Sounds || state.overlay != null
            if (n % 50 == 0) println("MONKEY step $n on ${state.tab} overlay=${state.overlay}")
            when (rnd.nextInt(14)) {
                0 -> tab(tabs[rnd.nextInt(tabs.size)])
                1 -> state.togglePlay()
                2 -> if (rnd.nextBoolean()) state.undo() else state.redo()
                3 -> state.setLive { nr = rnd.nextBoolean(); rec = rnd.nextInt(4) == 0 }
                4 -> if (rnd.nextInt(5) == 0) state.hush()
                5 -> state.toggleKeyboard(rnd.nextInt(8))
                else -> {
                    val x = rnd.nextFloat(); val y = rnd.nextFloat(); val x2 = rnd.nextFloat(); val y2 = rnd.nextFloat()
                    val kind = if (textHere) 0 else rnd.nextInt(5)
                    mainRoot().performTouchInput {
                        val a = Offset(x * width, y * height)
                        when (kind) {
                            0, 1 -> click(a)
                            2 -> { down(a); advanceEventTime(700); up() }
                            3 -> swipe(a, Offset(x2 * width, y2 * height), 300L)
                            else -> {
                                val r = 40f
                                down(a + Offset(r, 0f))
                                for (k in 1..22) { val t = k * (2 * Math.PI / 18); moveTo(a + Offset((r * kotlin.math.cos(t)).toFloat(), (r * kotlin.math.sin(t)).toFloat())) }
                                up()
                            }
                        }
                    }
                }
            }
            settle()
            val pk = loudest(512)
            assertTrue("the sound stays sane after action $n (peak $pk)", !pk.isNaN() && pk < 8f)
            state.tick(System.nanoTime())
            actions++
        }
        println("MONKEY $actions actions, tab=${state.tab}, desk=${state.console.size} pieces, pads=${state.project.kit.count { it.isNotEmpty() }}")
        shot("42-after-monkey")
    }

}
