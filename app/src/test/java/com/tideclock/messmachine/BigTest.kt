package com.tideclock.messmachine

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
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
 * The big test. Every screen of the app is built and driven like a finger would, on a phone-sized
 * screen, with the real sound engine rendering behind it. Each flow checks what changed in the mess
 * or what the engine played, and saves a screenshot to app/build/screens for a person to look at.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class BigTest {
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

    private fun tab(label: String) { rule.onNodeWithTag("tab-$label").performClick(); settle() }

    private fun tapPad(d: Int) {
        rule.onNodeWithTag("pad-$d").performScrollTo().performTouchInput { down(center) }
        settle()
        rule.onNodeWithTag("pad-$d").performTouchInput { up() }
        settle()
    }

    /* ---------- pads, keys and the piano keyboards ---------- */

    @Test fun padsPlayAndFourPianos() {
        shot("01-pads")
        loudest()
        rule.onNodeWithTag("pad-0").performTouchInput { down(center) }
        settle()
        val hit = loudest()
        rule.onNodeWithTag("pad-0").performTouchInput { up() }
        settle()
        assertTrue("tapping pad 1 makes sound (peak $hit)", hit > .02f)

        // hold a pad: its menu opens; Piano drops a keyboard right under that pad's row
        state.putOnPad(8, "samples/glass-harp.wav")
        state.putOnPad(13, "samples/wheezy-organ.wav")
        waitFor("the new pads to load") { state.lib.loaded("samples/glass-harp.wav") && state.lib.loaded("samples/wheezy-organ.wav") }
        for (d in listOf(1, 5, 8, 13)) {
            rule.onNodeWithTag("pad-$d").performScrollTo().performTouchInput { down(center); advanceEventTime(900); up() }
            settle()
            assertEquals("holding pad ${d + 1} opens its menu", d, state.padMenu)
            if (d == 1) shot("02-pad-menu")
            rule.onNodeWithTag("menu-piano").performClick()
            settle()
        }
        assertEquals(listOf(1, 5, 8, 13), state.keyboards.toList())
        // each piano sits between its own row and the next one
        fun top(t: String) = rule.onNodeWithTag(t).fetchSemanticsNode().boundsInRoot.top
        fun bottom(t: String) = rule.onNodeWithTag(t).fetchSemanticsNode().boundsInRoot.bottom
        rule.onNodeWithTag("pad-0").performScrollTo()
        assertTrue("piano 1 is under row 1", top("piano-1") >= bottom("pad-1") && bottom("piano-1") <= top("pad-4"))
        rule.onNodeWithTag("pad-4").performScrollTo()
        assertTrue("piano 2 is under row 2", top("piano-5") >= bottom("pad-5") && bottom("piano-5") <= top("pad-8"))
        for (d in 0 until 16) rule.onNodeWithTag("pad-$d").assertExists()

        // play a key on the first piano
        loudest()
        rule.onNodeWithTag("piano-keys-1").performScrollTo().performTouchInput { down(Offset(width * .5f, height * .85f)) }
        settle()
        val key = loudest()
        rule.onNodeWithTag("piano-keys-1").performTouchInput { up() }
        settle()
        assertTrue("a piano key plays its pad (peak $key)", key > .02f)

        // slide across the keys: the note changes without lifting
        rule.onNodeWithTag("piano-keys-5").performScrollTo().performTouchInput {
            down(Offset(width * .1f, height * .85f)); moveTo(Offset(width * .9f, height * .85f)); up()
        }
        settle()

        // scale and chords on the piano: in-scale keys glow, others dim, the root has a dot,
        // and Show chord lights the chord's other notes without playing them
        state.setRoot(0); state.setScale("Major"); state.pickChordMode("triad"); state.switchChordPlay(false)
        settle()
        state.engine.panic()
        loudest(1024)
        rule.onNodeWithTag("piano-keys-5").performScrollTo().performTouchInput { down(Offset(width * .35f, height * .85f)) }
        settle()
        loudest(1024)
        assertEquals("show chord plays one note", 1, state.engine.activeVoices())
        rule.onNodeWithTag("piano-5").performScrollTo()
        shot("03d-piano-scale-and-shown-chord")
        rule.onNodeWithTag("piano-keys-5").performTouchInput { up() }
        settle()
        state.switchChordPlay(true); state.pickChordMode("off")
        rule.onNodeWithTag("beatlight").assertExists()

        rule.onNodeWithTag("pad-0").performScrollTo()
        shot("03-pianos-under-rows-top")
        rule.onNodeWithTag("piano-13").performScrollTo()
        shot("03b-pianos-under-rows-bottom")

        // one piano per row: opening pad 1's piano swaps out pad 2's in the same row
        rule.onNodeWithTag("pad-0").performScrollTo().performTouchInput { down(center); advanceEventTime(900); up() }
        settle()
        rule.onNodeWithTag("menu-piano").performClick()
        settle()
        assertTrue(0 in state.keyboards && 1 !in state.keyboards)

        // the scroll bar moves the page
        rule.onNodeWithTag("scrollbar").performTouchInput { click(Offset(width / 2f, height - 4f)) }
        settle()
        rule.onNodeWithTag("scrollbar").performTouchInput { swipeUp() }
        settle()

        // view size: zoomed out, a pad gets smaller
        val w1 = rule.onNodeWithTag("pad-0").performScrollTo().fetchSemanticsNode().boundsInRoot.width
        state.setViewSize(.8f)
        settle()
        val w2 = rule.onNodeWithTag("pad-0").performScrollTo().fetchSemanticsNode().boundsInRoot.width
        assertTrue("zooming out shrinks the pads ($w1 to $w2)", w2 < w1 * .9f)
        shot("03c-zoomed-out-80")
        state.setViewSize(1f)
        settle()

        // keys mode keeps the grid and plays the scale
        rule.onNodeWithTag("mode-keys").performScrollTo().performClick()
        settle()
        shot("04-keys-mode")
        rule.onNodeWithTag("mode-pads").performClick()
        settle()
    }

    @Test fun pianoChordsStackNotes() {
        state.pickChordMode("triad")
        state.toggleKeyboard(7)
        settle()
        state.pianoDown(7, 0)
        loudest(1024)
        val v = state.engine.activeVoices()
        state.pianoUp(7, 0)
        assertTrue("a triad plays three voices (got $v)", v >= 3)
    }

    /* ---------- steps, roll, song ---------- */

    @Test fun stepsToggleRandomMute() {
        tab("Steps")
        shot("05-steps")
        val k = state.project.kit[1]
        val before = state.project.pat().step(k, 1)
        rule.onNodeWithTag("steps-1").performTouchInput { click(Offset(width * (1.5f / 16f), height / 2f)) }
        settle()
        assertEquals(!before, state.project.pat().step(k, 1))
        rule.onNodeWithTag("random").performClick()
        settle()
        assertTrue(state.project.pat().used())
        rule.onNodeWithTag("mute-0").performClick()
        settle()
        assertTrue(state.project.mutes.contains(state.project.kit[0]))
        rule.onNodeWithTag("looplen").performClick()
        settle()
        assertEquals(32, state.project.pat().len)
        assertEquals("bar one copies into bar two", state.project.pat().step(k, 1), state.project.pat().step(k, 17))
        shot("06-steps-two-bars")
    }

    @Test fun rollDropsAndClearsNotes() {
        state.selectPad(7, false)
        tab("Roll")
        state.pickChordMode("off")
        val k = state.selKey()
        val before = state.project.pat().notes(k).size
        rule.onNodeWithTag("roll").performScrollTo().performTouchInput { click(Offset(width * .6f, height * .5f)) }
        settle()
        assertEquals(before + 1, state.project.pat().notes(k).size)
        shot("07-roll")
        val n = rule.onNodeWithTag("roll-clear").performScrollTo().fetchSemanticsNode()
        println("ROLL-CLEAR bounds ${n.boundsInRoot} enabled ${!n.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)}")
        rule.onNodeWithTag("roll-clear").performClick()
        settle()
        assertEquals(0, state.project.pat().notes(k).size)
    }

    @Test fun songLanePaintsAndPlays() {
        tab("Song")
        shot("08-song-empty")
        rule.onNodeWithTag("rough").performScrollTo().performClick()
        settle()
        assertTrue("rough it in fills the lane", state.project.songLen() >= 2)
        rule.onNodeWithTag("bar-5").performScrollTo().performClick()
        settle()
        assertEquals(state.brush, state.project.song[5])
        state.setSongMode(true)
        state.engine.play()
        loudest(48000)
        state.tick(System.nanoTime())
        assertTrue(state.engine.isPlaying())
        state.stop()
        shot("09-song")
    }

    /* ---------- the jars and Your Workbench ---------- */

    @Test fun soundsSearchAndPutOnPad() {
        tab("Sounds")
        shot("10-sounds")
        rule.onNodeWithTag("search").performTextInput("burp")
        settle()
        rule.onNodeWithTag("put-samples/goblin-burp.wav").performClick()
        settle()
        assertEquals("samples/goblin-burp.wav", state.project.kit[state.project.sel])
        // by instrument type and feel
        rule.onNodeWithTag("search").performTextClearance()
        rule.onNodeWithTag("mode-type").performClick()
        settle()
        rule.onNodeWithTag("type-bass").performScrollTo().performClick()
        settle()
        rule.onNodeWithTag("put-qb:block-of-meat-bass").assertExists()
        rule.onNodeWithTag("feel-dark").performScrollTo().performClick()
        settle()
        shot("10b-sounds-by-type-bass-dark")
        // favorites
        state.toggleFav("samples/sub-drop.wav")
        rule.onNodeWithTag("mode-fav").performClick()
        settle()
        rule.onNodeWithTag("put-samples/sub-drop.wav").assertExists()
        shot("10c-favorites")
        rule.onNodeWithTag("mode-jar").performClick()
        settle()
        rule.onNodeWithTag("jar-mine").performScrollTo().performClick()
        settle()
        shot("11-workbench")
        state.resampleLoop()
        waitFor("the resample") { state.bench.items.isNotEmpty() && state.busy == null }
        assertTrue(state.project.kit.any { it.startsWith("bn:") })
        shot("12-workbench-with-resample")
    }

    /* ---------- live effects ---------- */

    @Test fun fxHoldAndFilterPad() {
        rule.onNodeWithTag("fx-toggle").performClick()
        settle()
        state.togglePlay()
        loudest(4800)
        rule.onNodeWithTag("fx-stutter").performScrollTo().performTouchInput { down(center) }
        settle()
        loudest(512)
        assertTrue("stutter is held", state.engine.fxActive())
        rule.onNodeWithTag("fx-stutter").performTouchInput { up() }
        settle()
        loudest(512)
        rule.onNodeWithTag("xy").performScrollTo().performTouchInput { down(Offset(width * .2f, height * .3f)); moveTo(Offset(width * .6f, height * .4f)) }
        settle()
        loudest(512)
        assertTrue("the filter pad is down", state.engine.fxActive())
        shot("13-fx")
        rule.onNodeWithTag("xy").performTouchInput { up() }
        state.stop()
    }

    /* ---------- the doctor's table ---------- */

    @Test fun doctorChopsAndSaves() {
        state.selectPad(7, false)
        rule.onNodeWithTag("doctor").performScrollTo().performClick()
        settle()
        waitFor("the waveform") { state.lib.loaded(state.project.kit[7]) }
        shot("14-doctor")
        val before = state.project.loadedCount()
        state.chopToPads(state.project.kit[7], listOf(.25, .5, .75))
        settle()
        assertEquals("four slices land on empty pads", before + 4, state.project.loadedCount())
        state.saveEditAsNew(state.project.kit[7])
        waitFor("the saved edit") { state.bench.items.any { it.kind == "take" } && state.busy == null }
    }

    /* ---------- messes, notes, tempo ---------- */

    @Test fun messesNotesTempo() {
        rule.onNodeWithTag("messes").performClick()
        settle()
        shot("15-messes")
        state.saveMess("Test mess", false)
        waitFor("the save") { state.files.any { it.name == "Test mess" } }
        rule.onNodeWithTag("done").performClick()
        settle()
        rule.onNodeWithTag("notes").performClick()
        settle()
        shot("16-notes")
        state.saveNote("bug", "", "The test suite says hi", false)
        settle()
        assertEquals(1, state.notes.notes.size)
        rule.onNodeWithTag("done").performClick()
        settle()
        rule.onNodeWithTag("bpm").performClick()
        settle()
        shot("17-tempo")
        state.setBpm(140)
        assertEquals(140, state.project.bpm)
    }

    /* ---------- recording, undo, import, bottle ---------- */

    @Test fun recordUndoImportBottle() {
        val k = state.project.kit[4]
        state.edit { pat().clear() }
        state.setLive { rec = true }
        state.engine.play()
        loudest(9600)
        state.selectPad(4, false)
        tapPad(4)
        loudest(2048)
        state.tick(System.nanoTime())
        settle()
        assertTrue("a live hit while recording lands in the loop", (0 until 16).any { state.project.pat().step(k, it) })
        state.stop()
        state.setLive { rec = false }
        state.undo()
        settle()
        assertFalse("undo takes the recorded hit back", (0 until 16).any { state.project.pat().step(k, it) })

        val site = """{"name":"Site mess","bpm":120,"v":3,"kit":["samples/basement-kick.wav","samples/wet-clap.wav","a:uploaded1"],
            "pats":[{"len":16,"steps":{"samples/basement-kick.wav":"1000100010001000"},"notes":{}}],"song":"A","swing":20}"""
        state.importText(site)
        settle()
        assertEquals("Site mess", state.project.name)
        assertEquals(120, state.project.bpm)
        assertTrue(state.project.pat().step("samples/basement-kick.wav", 4))

        state.bottleIt()
        waitFor("the bottle") { state.busy == null }
        val started = shadowOf(rule.activity.application).nextStartedActivity
        println("BOTTLE share intent: ${started?.action}")
        shot("18-after-import")
    }

    @Test fun hushStopsEverything() {
        state.togglePlay()
        loudest(4800)
        rule.onNodeWithTag("hush").performClick()
        settle()
        loudest(512)
        assertFalse(state.playing)
        assertEquals(0, state.engine.activeVoices())
    }

    @Test fun everyTabAndPanelOpens() {
        for (t in Tab.entries) {
            tab(t.label)
            val ok = try { rule.onNodeWithTag("screen-${t.label}").assertIsDisplayed(); true } catch (e: AssertionError) { false }
            println("TAB ${t.label} displayed=$ok")
            assertTrue("tab ${t.label} shows its screen", ok)
        }
        tab("Pads")
        tapPad(12)
        assertEquals("tapping an empty pad opens the jars for it", Overlay.Picker(12), state.overlay)
        rule.onNodeWithTag("sheet", useUnmergedTree = true).assertExists()
        shot("19-picker")
        rule.onNodeWithText("DONE").performClick()
        settle()
        assertEquals(null, state.overlay)
        assertTrue(Project.LETTERS.length == 8 && Engine.Live().nrRate == 1.0)
    }
}
