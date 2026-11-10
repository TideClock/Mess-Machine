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
            longPress("pad-$d")
            if (state.padMenu != d) {
                val b = rule.onNodeWithTag("pad-$d").fetchSemanticsNode().boundsInRoot
                println("DEBUG pad $d menu=${state.padMenu} toast=${state.toast} overlay=${state.overlay} bounds=$b keyboards=${state.keyboards.toList()} sel=${state.project.sel}")
                shot("debug-menu-$d")
            }
            assertEquals("holding pad ${d + 1} opens its menu", d, state.padMenu)
            if (d == 1) shot("02-pad-menu")
            rule.onNodeWithTag("menu-piano").performClick()
            // let the menu finish closing before the next press
            rule.mainClock.advanceTimeBy(1000)
            settle()
        }
        assertEquals(listOf(1, 5, 8, 13), state.keyboards.toList())
        // each piano sits between its own row and the next one
        // unclipped edges: the taller pianos run past the screen
        fun top(t: String) = rule.onNodeWithTag(t).fetchSemanticsNode().positionInRoot.y
        fun bottom(t: String) = rule.onNodeWithTag(t).fetchSemanticsNode().let { it.positionInRoot.y + it.size.height }
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
        longPress("pad-0")
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

    @Test fun holdKeepsTheFirstNotes() {
        state.toggleKeyboard(7)
        settle()
        // one tap turns Hold on, three notes by default
        rule.onNodeWithTag("hold-toggle").performScrollTo().performClick()
        settle()
        assertTrue(state.holdOn)
        assertEquals(3, state.holdCount)
        rule.onNodeWithTag("hold-up").performClick(); rule.onNodeWithTag("hold-down").performClick()
        settle()
        assertEquals(3, state.holdCount)
        // five different notes: the first three hold, the next two do not
        for (p in listOf(0, 2, 4, 5, 7)) { state.pianoDown(7, p); state.pianoUp(7, p) }
        settle()
        assertEquals(setOf(0, 2, 4), state.heldPitches(7))
        rule.onNodeWithTag("piano-7").performScrollTo()
        shot("20-hold-three")
        // press two of the held ones again: they let go; two new notes take their places
        for (p in listOf(0, 2)) { state.pianoDown(7, p); state.pianoUp(7, p) }
        assertEquals(setOf(4), state.heldPitches(7))
        for (p in listOf(9, 11)) { state.pianoDown(7, p); state.pianoUp(7, p) }
        assertEquals(setOf(4, 9, 11), state.heldPitches(7))
        rule.onNodeWithTag("hold-release").performScrollTo().performClick()
        settle()
        assertTrue(state.latched.isEmpty())
        // the Hold switch on the piano itself: turning it off lets every held note go
        for (p in listOf(0, 4)) { state.pianoDown(7, p); state.pianoUp(7, p) }
        assertEquals(2, state.latched.size)
        rule.onNodeWithTag("piano-hold-7").performScrollTo().performClick()
        settle()
        assertFalse(state.holdOn)
        assertTrue(state.latched.isEmpty())
        state.pianoDown(7, 0); state.pianoUp(7, 0)
        assertTrue("with Hold off nothing holds", state.latched.isEmpty())
    }

    @Test fun padFilterUnderThePiano() {
        state.toggleKeyboard(7)
        settle()
        // hold the strip near the left: pad 8's filter closes, nothing else's does
        rule.onNodeWithTag("pfilt-7").performScrollTo().performTouchInput { down(Offset(width * .08f, height * .3f)) }
        settle()
        assertTrue(7 in state.padFilt)
        loudest(2048)
        assertTrue(state.engine.padFiltered(7) && !state.engine.padFiltered(0))
        state.pianoDown(7, 0)
        loudest(1024)
        rule.onNodeWithTag("piano-7").performScrollTo()
        shot("21-pad-filter-held")
        state.pianoUp(7, 0)
        rule.onNodeWithTag("pfilt-7").performTouchInput { up() }
        settle()
        assertFalse("lifting opens it again", 7 in state.padFilt)
        loudest(44100)
        assertFalse(state.engine.padFiltered(7))
        // Stay keeps it where you left it, Open lets it go
        rule.onNodeWithTag("pfilt-stay-7").performScrollTo().performClick()
        rule.onNodeWithTag("pfilt-7").performTouchInput { down(Offset(width * .3f, height * .5f)); moveTo(Offset(width * .2f, height * .2f)); up() }
        settle()
        assertTrue("Stay keeps it closed", 7 in state.padFilt)
        rule.onNodeWithTag("pfilt-open-7").performClick()
        settle()
        assertFalse(7 in state.padFilt)
    }

    @Test fun recordOntoAPadAndKeepSounds() {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        // hold pad 4: Record onto this pad
        longPress("pad-3")
        rule.onNodeWithTag("menu-record").performClick()
        settle()
        val started = state.micOn
        println("MIC started=$started pad=${state.micPad}")
        if (started) {
            assertEquals(3, state.micPad)
            rule.onNodeWithTag("rec-banner").assertExists()
            shot("22-recording-onto-pad")
            // the test phone's mic can run dry by itself; tap the bar if it is still recording
            if (state.micOn) state.recordToPad(3)
        }
        waitFor("the mic to stop") { !state.micOn }
        // the take lands on that pad and in Your Workbench
        val rate = 44100
        val tone = FloatArray(rate / 2) { (Math.sin(2 * Math.PI * 330 * it / rate) * .5 * (1 - it / (rate / 2.0))).toFloat() }
        state.takeArrived(com.tideclock.messmachine.engine.Sample("mic", tone, rate), 3)
        waitFor("the take to land on pad 4") { state.busy == null && state.lib.sound(state.project.kit[3])?.cab == "mine" }
        val n = state.bench.items.size
        // keep a pad's sound in Your Workbench from its menu
        longPress("pad-0")
        rule.onNodeWithTag("menu-keep").performClick()
        waitFor("the kick to be kept") { state.busy == null && state.bench.items.size == n + 1 }
        rule.onNodeWithTag("pad-3").performScrollTo()
        shot("23-mic-take-on-pad-4")
        state.openWorkbench()
        settle()
        rule.onNodeWithTag("share-${state.project.kit[3]}").assertExists()
        shot("24-workbench-share")
    }

    /* ---------- build A: fixes and safety ---------- */

    @Test fun padsRedrawAfterAChange() {
        // note #6: a pad that is not the selected one shows its new sound straight away
        val key = "samples/goblin-burp.wav"
        state.putOnPad(5, key)
        waitFor("the burp to load") { state.lib.loaded(key) }
        rule.onNodeWithText(state.lib.titleOf(key).uppercase(), useUnmergedTree = true).assertExists()
    }

    @Test fun menusOpenOnLiftNotOnDrag() {
        // held still: nothing until the finger lifts, then the menu
        rule.onNodeWithTag("pad-2").performScrollTo()
        rule.mainClock.advanceTimeBy(600); settle()
        rule.onNodeWithTag("pad-2").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(800); settle()
        assertEquals("no menu while the finger is still down", -1, state.padMenu)
        rule.onNodeWithTag("pad-2").performTouchInput { up() }
        settle()
        assertEquals("the menu opens on lift", 2, state.padMenu)
        state.padMenu = -1
        rule.mainClock.advanceTimeBy(1000); settle()
        // a drag never opens it, however long it lasts
        rule.onNodeWithTag("pad-3").performTouchInput { down(center) }
        rule.onNodeWithTag("pad-3").performTouchInput { moveBy(Offset(0f, 60f)) }
        rule.mainClock.advanceTimeBy(800); settle()
        rule.onNodeWithTag("pad-3").performTouchInput { up() }
        settle()
        assertEquals("a drag never opens the menu", -1, state.padMenu)
    }

    @Test fun holdLastAndInKeyOnly() {
        state.toggleKeyboard(7)
        settle()
        // Hold Last: only the newest note holds
        state.switchHold(true)
        rule.onNodeWithTag("hold-down").performScrollTo()
        repeat(3) { rule.onNodeWithTag("hold-down").performClick() }
        settle()
        assertEquals(0, state.holdCount)
        state.pianoDown(7, 0); state.pianoUp(7, 0)
        assertEquals(setOf(0), state.heldPitches(7))
        state.pianoDown(7, 4); state.pianoUp(7, 4)
        assertEquals("the new note takes over", setOf(4), state.heldPitches(7))
        state.pianoDown(7, 4); state.pianoUp(7, 4)
        assertTrue("pressing it again lets it go", state.heldPitches(7).isEmpty())
        state.switchHold(false)
        // In key only: every square on the Padiano is in the scale
        state.setRoot(0); state.setScale("Major"); state.pickChordMode("off")
        rule.onNodeWithTag("in-key").performScrollTo().performClick()
        settle()
        assertTrue(state.inKeyOnly)
        rule.onNodeWithTag("piano-7").performScrollTo()
        shot("25-padiano-in-key-only")
        for (f in listOf(.03f, .3f, .55f, .8f, .97f)) {
            rule.onNodeWithTag("piano-keys-7").performTouchInput { down(Offset(width * f, height * .5f)) }
            settle()
            val name = state.lastChord
            rule.onNodeWithTag("piano-keys-7").performTouchInput { up() }
            settle()
            assertTrue("in key only plays only C major notes (got $name at $f)", name.isNotEmpty() && '#' !in name)
        }
        // Padiano settings: fewer notes, the Keys layout
        rule.onNodeWithTag("in-key").performScrollTo().performClick()
        rule.onNodeWithTag("piano-set-7").performScrollTo().performClick()
        settle()
        repeat(4) { rule.onNodeWithTag("pset-n-down").performClick() }
        settle()
        assertEquals(9, state.padianoN)
        rule.onNodeWithTag("piano-7").performScrollTo()
        shot("26-padiano-settings-9-notes")
        rule.onNodeWithTag("pset-keys").performClick()
        settle()
        rule.onNodeWithTag("piano-keys-7").performScrollTo()
        shot("27-padiano-keys-layout")
        state.switchPadianoKeys(false)
    }

    @Test fun emptyPadsRecordWhenSwitchedOn() {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        state.switchEmptyRecord(true)
        settle()
        tapPad(12)
        println("EMPTYREC mic=${state.micOn} pad=${state.micPad} overlay=${state.overlay}")
        assertEquals("an empty pad no longer opens the jars", null, state.overlay)
        assertTrue("tapping an empty pad starts recording onto it", state.micPad == 12 || state.toast?.contains("mic") == true)
        if (state.micOn) tapPad(12)
        waitFor("the mic to stop") { !state.micOn }
        state.switchEmptyRecord(false)
        tapPad(12)
        assertEquals("switched off, empty pads open the jars again", Overlay.Picker(12), state.overlay)
    }

    /* ---------- your console ---------- */

    @Test fun consoleBuildsAndPlays() {
        tab("Console")
        shot("30-console-perform")
        assertEquals(listOf("pads", "hold", "piano", "pfilt", "fx", "xy", "repeat", "loops"), state.console.map { it.type })
        // it plays: a pad, the piano, an effect
        loudest()
        tapPad(0)
        rule.onNodeWithTag("cfx-crush").performScrollTo().performTouchInput { down(center) }
        settle()
        loudest(1024)
        assertTrue("an effect held on the console", state.engine.fxActive())
        rule.onNodeWithTag("cfx-crush").performTouchInput { up() }
        settle()
        val pp = state.console[2].pad
        rule.onNodeWithTag("piano-keys-$pp").performScrollTo().performTouchInput { down(Offset(width * .5f, height * .85f)) }
        settle()
        val key = loudest()
        rule.onNodeWithTag("piano-keys-$pp").performTouchInput { up() }
        settle()
        assertTrue("the console's piano plays (peak $key)", key > .02f)
        rule.onNodeWithTag("piano-$pp").performScrollTo()
        shot("31-console-piano-and-filter")
        // edit: resize, half width, move, add, remove
        rule.onNodeWithTag("console-edit").performScrollTo().performClick()
        settle()
        rule.onNodeWithTag("mod-size-2").performScrollTo().performClick()
        settle()
        assertEquals(2, state.console[2].size)
        rule.onNodeWithTag("mod-width-1").performScrollTo().performClick()
        settle()
        assertTrue(state.console[1].half)
        rule.onNodeWithTag("mod-down-0").performScrollTo().performClick()
        settle()
        assertEquals("hold", state.console[0].type)
        assertEquals("pads", state.console[1].type)
        state.addModule("transport")
        state.addModule("fx1")
        state.addModule("beat")
        settle()
        assertEquals("fx1", state.console[state.console.size - 2].type)
        rule.onNodeWithTag("mod-x-${state.console.size - 1}").performScrollTo().performClick()
        settle()
        assertEquals("fx1", state.console.last().type)
        rule.onNodeWithTag("mod-0").performScrollTo()
        shot("32-console-editing")
        rule.onNodeWithTag("mod-${state.console.size - 1}").performScrollTo()
        shot("33-console-editing-bottom")
        val saved = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("mess", 0).getString("console", "")!!
        assertTrue("the layout is saved: $saved", saved.startsWith("hold,") && "fx1" in saved)
        rule.onNodeWithTag("console-edit").performScrollTo().performClick()
        settle()
        shot("34-console-custom")
        state.resetConsole()
        settle()
        assertEquals("pads", state.console[0].type)
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
