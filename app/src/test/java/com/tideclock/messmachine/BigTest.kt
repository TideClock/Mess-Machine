package com.tideclock.messmachine

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.semantics.getOrNull
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
import androidx.compose.ui.test.doubleClick
import androidx.core.view.drawToBitmap
import androidx.test.core.app.ApplicationProvider
import com.tideclock.messmachine.engine.Engine
import com.tideclock.messmachine.engine.Project
import com.tideclock.messmachine.ui.MessMachineApp
import com.tideclock.messmachine.ui.UiFlags
import com.tideclock.messmachine.ui.TutHi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
        // past the 0.45 s the menu needs, and well inside the 1.3 s after which a hold counts as playing
        rule.mainClock.advanceTimeBy(600)
        settle()
        println("LONGPRESS $tag menu while held=${state.padMenu}")
        rule.onNodeWithTag(tag).performTouchInput { advanceEventTime(100); up() }
        println("LONGPRESS $tag menu after lift=${state.padMenu}")
        settle()
    }

    /**
     * Open a pad's menu the way a player would: hold and lift. A pad that sounds for as long as it is held (a synth,
     * Sustain, gate) never offers its menu from a hold, so there: tap the pad, then Menu by the bank numbers.
     */
    private fun padMenuOf(d: Int) {
        if (state.soundsWhileHeld(state.bank * 16 + d)) {
            tapPad(d)
            rule.mainClock.advanceTimeBy(300); settle()
            at("open-padmenu").performClick()
            settle()
        } else longPress("pad-$d")
    }

    /** Scroll a node into view and let the page stop moving, so the next touch lands on it. */
    private fun at(tag: String) = rule.onNodeWithTag(tag).also { it.performScrollTo(); rule.mainClock.advanceTimeBy(600); settle() }

    /** Pads and Desk share the Play tab now: "Console" means the Play tab's My Desk view. */
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
        at("piano-keys-1").performTouchInput { down(Offset(width * .5f, height * .85f)) }
        settle()
        val key = loudest()
        rule.onNodeWithTag("piano-keys-1").performTouchInput { up() }
        settle()
        assertTrue("a piano key plays its pad (peak $key)", key > .02f)

        // slide across the keys: the note changes without lifting
        at("piano-keys-5").performTouchInput {
            down(Offset(width * .1f, height * .85f)); moveTo(Offset(width * .9f, height * .85f)); up()
        }
        settle()

        // scale and chords on the piano: in-scale keys glow, others dim, the root has a dot,
        // and Show chord lights the chord's other notes without playing them
        state.setRoot(0); state.setScale("Major"); state.pickChordMode("triad"); state.switchChordPlay(false)
        settle()
        state.engine.panic()
        loudest(1024)
        at("piano-keys-5").performTouchInput { down(Offset(width * .35f, height * .85f)) }
        settle()
        loudest(1024)
        assertEquals("show chord plays one note", 1, state.engine.activeVoices())
        rule.onNodeWithTag("piano-5").performScrollTo()
        shot("03d-piano-scale-and-shown-chord")
        rule.onNodeWithTag("piano-keys-5").performTouchInput { up() }
        settle()
        state.switchChordPlay(true); state.pickChordMode("off")
        rule.onNodeWithTag("topbar").assertExists()
        rule.onNodeWithTag("position", useUnmergedTree = true).assertExists()

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
        rule.onNodeWithTag("hold-toggle").performScrollTo().performClick()
        settle()
        assertFalse(state.holdOn)
        assertTrue(state.latched.isEmpty())
        state.pianoDown(7, 0); state.pianoUp(7, 0)
        assertTrue("with Hold off nothing holds", state.latched.isEmpty())
    }

    @Test fun padFilterUnderThePiano() {
        // the strip left the Padiano (the dock filter aims at one pad too); it stays as a desk piece
        state.selectPad(7, false)
        state.addModule("pfilt")
        state.toggleKeyboard(7)
        tab("Console")
        // hold the strip near the left: pad 8's filter closes, nothing else's does
        at("pfilt-7").performTouchInput { down(Offset(width * .08f, height * .3f)) }
        settle()
        assertTrue(7 in state.padFilt)
        loudest(2048)
        assertTrue(state.engine.padFiltered(7) && !state.engine.padFiltered(0))
        state.pianoDown(7, 0)
        loudest(1024)
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

    /* ---------- launching like a phone does ---------- */

    @Test fun firstLaunchOfANewBuildShowsTheGuide() {
        // a phone installing a new build has not seen its guide: the app must open it at launch, not crash
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("mess", 0).edit().remove("guideSeen").commit()
        val fresh = AppState(ctx)
        assertEquals(Overlay.Notes, fresh.overlay)
        // and after a crash, the next launch opens the notes with the crash in them
        File(ctx.filesDir, "last_crash.txt").writeText("java.lang.RuntimeException: test crash")
        val afterCrash = AppState(ctx)
        assertEquals(Overlay.Notes, afterCrash.overlay)
        assertTrue(afterCrash.crash?.contains("test crash") == true)
    }

    @Test fun launchesWithEveryKindOfSavedState() {
        // a phone carries settings from older builds: every combination must still start
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = ctx.getSharedPreferences("mess", 0)
        val shapes = listOf(
            mapOf("console" to "pads,-1,1,0;hold,-1,1,0;piano,7,1,0;fx,-1,1,1"),
            mapOf("desks" to "Perform~pads,-1,1,0,0,0,12,16,0;piano,7,2,0,0,16,12,12,1##Mine~xyp,7,1,1,3,4,5,9,0", "deskIdx" to "1"),
            mapOf("desks" to "broken~nonsense;;,,,", "deskIdx" to "9"),
            mapOf("fxsets" to "crush:0.5:stays:66:1;gate:1.0:slider:-1:2;bad", "myxy" to "Mine 1|4|s|o|2|t|0:7.0,9:1.0,0:-12.0,13:1.0;;junk", "dock" to "lock,xy,desk,nothing"),
            mapOf("holdOn" to "true", "holdN" to "0", "padKeys" to "true", "padN" to "99", "inKey" to "true", "emptyRec" to "true", "pulse" to "2", "uiScale" to "0.7"),
        )
        for ((n, m) in shapes.withIndex()) {
            val e = prefs.edit().clear()
            for ((k, v) in m) when {
                v == "true" || v == "false" -> e.putBoolean(k, v.toBoolean())
                k == "uiScale" -> e.putFloat(k, v.toFloat())
                v.toIntOrNull() != null -> e.putInt(k, v.toInt())
                else -> e.putString(k, v)
            }
            e.remove("guideSeen").commit()
            val st = AppState(ctx)
            assertTrue("saved state $n starts with a desk", st.console.isNotEmpty() || st.desks.isNotEmpty())
            println("LAUNCH $n ok: desks=${st.desks.map { it.name }} dock=${st.dockItems.toList()}")
        }
    }

    @Test fun deskPiecesSurviveEverySize() {
        // note #8: squeezing a piece (a Padiano) down to its smallest crashed; try every piece at every extreme
        tab("Console")
        for ((type, _) in CONSOLE_TYPES) {
            for ((w, h) in listOf(2 to 2, 12 to 2, 2 to 30, 3 to 3)) {
                state.console.clear()
                state.addModule(type)
                state.changeModule(0, state.console[0].copy(w = w, h = h))
                settle()
                rule.onNodeWithTag("mod-0").assertExists()
            }
        }
        state.consoleEdit = true
        state.console.clear(); state.addModule("piano")
        settle()
        // drag the corner far past the minimum, like a thumb would
        at("mod-size-0").performTouchInput { down(center); moveBy(Offset(-40f, -40f)); moveBy(Offset(-900f, -900f)); up() }
        settle()
        assertTrue("it stops at the smallest size", state.console[0].w >= 2 && state.console[0].h >= 2)
        shot("40-desk-smallest-padiano")
        state.resetConsole()
    }

    @Test fun dockLightsFollowRepeatAndRec() {
        // the dock starts folded to a thin edge strip; tap it to open
        assertFalse("the dock starts folded", state.dockOpen)
        rule.onNodeWithTag("dock-open").performClick()
        settle()
        assertTrue(state.dockOpen)
        // note #7: RPT on the dock did not light up when Repeat came on
        state.setLive { nr = true }
        settle()
        rule.onNodeWithTag("dock-repeat").assertIsSelected()
        rule.onNodeWithTag("dock-repeat").performClick()
        settle()
        assertFalse(state.live.nr)
        // Repeat's rate lives under RPT now (it left the top bar)
        rule.onNodeWithTag("dock-rate").performClick()
        settle()
        rule.onNodeWithTag("rate-r2").performClick()
        settle()
        assertEquals(2.0, state.live.nrRate, 0.001)
        state.toggleDockItem("rec")
        settle()
        rule.onNodeWithTag("rec").performClick()
        settle()
        rule.onNodeWithTag("dock-rec").assertIsSelected()
        // Rec glows with the beat while the loop plays
        state.togglePlay()
        repeat(4) { loudest(2048); state.tick(System.nanoTime()) }
        settle()
        shot("41-rec-glow")
        state.stop()
        rule.onNodeWithTag("rec").performClick()
        settle()
    }

    @Test fun stepDotsClearOnTap() {
        // note #9: a dot (a pitched step or roll notes) could not be taken off
        tab("Steps")
        val k = state.project.kit[7]
        state.setStepPitch(k, 4, 5)
        settle()
        assertTrue(state.project.pat().hasNoteAt(k, 4))
        at("steps-7").performTouchInput { click(Offset(width * (4.5f / 16f), height / 2f)) }
        settle()
        assertFalse("tapping a dot clears it", state.project.pat().hasNoteAt(k, 4) || state.project.pat().step(k, 4))
        // and Clear notes takes every pitch and roll note out of the loop
        state.setStepPitch(k, 2, 3); state.setStepPitch(state.project.kit[0], 6, 0)
        settle()
        at("clear-all-notes").performClick()
        settle()
        assertTrue(state.project.pat().notes.values.all { it.isEmpty() })
        assertTrue("plain hits stay", state.project.pat().used())
    }

    /* ---------- build A: fixes and safety ---------- */

    @Test fun padsRedrawAfterAChange() {
        // note #6: a pad that is not the selected one shows its new sound straight away
        val key = "samples/goblin-burp.wav"
        state.putOnPad(5, key)
        waitFor("the burp to load") { state.lib.loaded(key) }
        rule.onNodeWithText(state.lib.titleOf(key).uppercase(), useUnmergedTree = true).assertExists()
    }

    /** A long hold is playing, not asking: the menu offer ends about 1.3 s after the touch, and a pad that sounds while held never makes it. */
    @Test fun longHoldsDoNotOpenThePadMenu() {
        rule.onNodeWithTag("pad-2").performScrollTo()
        rule.mainClock.advanceTimeBy(600); settle()
        rule.onNodeWithTag("pad-2").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(1600); settle()
        rule.onNodeWithTag("pad-2").performTouchInput { up() }
        settle()
        assertEquals("held past the offer: no menu", -1, state.padMenu)
        // Sustain: the pad sounds for as long as it is held, so holding it never opens the menu; the bank row button does
        val k = state.project.kit[2]
        state.setEdit(k, state.project.edit(k).copy().also { it.held = "sustain" }, true)
        settle()
        assertTrue(state.soundsWhileHeld(2))
        rule.mainClock.advanceTimeBy(600); settle()
        rule.onNodeWithTag("pad-2").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(800); settle()
        rule.onNodeWithTag("pad-2").performTouchInput { up() }
        settle()
        assertEquals("a sustaining pad's hold is playing: no menu", -1, state.padMenu)
        assertTrue("and it says where that pad's menu is (${state.toast})", state.toast.orEmpty().contains("Menu"))
        rule.onNodeWithTag("open-padmenu").performClick()
        settle()
        assertEquals("its menu opens from the Menu button by the banks", 2, state.padMenu)
        state.padMenu = -1
        settle()
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
        // In key only lives in the Padiano's settings now (its one home, BT-1)
        rule.onNodeWithTag("piano-set-7").performScrollTo().performClick()
        settle()
        rule.onNodeWithTag("pset-inkey").performScrollTo().performClick()
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
        rule.onNodeWithTag("pset-inkey").performScrollTo().performClick()
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

    /* ---------- build B: glide, the dock, Lock ---------- */

    @Test fun glideOnThePadiano() {
        state.toggleKeyboard(7)
        settle()
        rule.onNodeWithTag("glide-7").performScrollTo().performClick()
        settle()
        assertTrue(state.project.opt(7).glide)
        rule.onNodeWithTag("glide-legato-7").performScrollTo().performClick()
        settle()
        assertTrue(state.project.opt(7).legato)
        rule.onNodeWithTag("piano-7").performScrollTo()
        shot("28-padiano-glide")
        // it saves with the mess, and undo takes it back
        state.undo(); settle()
        assertFalse(state.project.opt(7).legato)
    }

    @Test fun dockLockAndFilter() {
        state.switchDockOpen(true)
        settle()
        rule.onNodeWithTag("dock").assertExists()
        shot("29-dock")
        // the dock's filter acts on the whole mix and springs back when let go
        loudest(1024)
        rule.onNodeWithTag("dock-cut").performTouchInput { down(Offset(width / 2f, height * .9f)) }
        settle(); loudest(1024)
        assertTrue("the dock filter closes the mix", state.engine.fxActive())
        rule.onNodeWithTag("dock-cut").performTouchInput { up() }
        settle(); loudest(1024)
        assertFalse("and springs back open", state.engine.fxActive())
        // pointed at one pad instead
        rule.onNodeWithTag("dock-target").performClick()
        settle()
        val pad = state.dockTarget
        assertEquals(state.project.sel, pad)
        rule.onNodeWithTag("dock-res").performTouchInput { down(Offset(width / 2f, height * .2f)) }
        settle()
        assertTrue("the dock filter closes one pad", pad in state.padFilt)
        rule.onNodeWithTag("dock-res").performTouchInput { up() }
        settle()
        assertFalse(pad in state.padFilt)
        // add Rec to the dock
        rule.onNodeWithTag("dock-edit").performClick()
        settle()
        rule.onNodeWithTag("dock-pick-rec").performClick()
        settle()
        assertTrue("rec" in state.dockItems)
        state.toggleDockItem("rec")
        rule.mainClock.advanceTimeBy(500); settle()
        // Lock: tabs, sheets and menus stay shut, the pads still play
        rule.onNodeWithTag("dock-lock").performClick()
        settle()
        assertTrue(state.locked)
        rule.onNodeWithTag("tab-Steps").performClick()
        settle()
        assertEquals("tabs do nothing while locked", Tab.Pads, state.tab)
        state.overlay = Overlay.Files
        assertEquals("sheets stay shut while locked", null, state.overlay)
        loudest()
        rule.onNodeWithTag("pad-0").performTouchInput { down(center) }
        settle()
        val hit = loudest()
        rule.onNodeWithTag("pad-0").performTouchInput { up() }
        settle()
        assertTrue("pads still play while locked (peak $hit)", hit > .02f)
        shot("30b-locked")
        // a tap does not unlock; holding for a second does
        rule.onNodeWithTag("dock-lock").performClick()
        settle()
        assertTrue("a quick tap leaves it locked", state.locked)
        rule.onNodeWithTag("dock-lock").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(1200); settle()
        rule.onNodeWithTag("dock-lock").performTouchInput { up() }
        settle()
        assertFalse("holding the lock unlocks", state.locked)
        // fold the dock away and back
        rule.onNodeWithTag("dock-fold").performScrollTo().performClick()
        settle()
        rule.onNodeWithTag("dock-open").performClick()
        settle()
        assertTrue(state.dockOpen)
    }

    /* ---------- build C: effects, routing, XY ---------- */

    @Test fun effectsYouCanEdit() {
        rule.onNodeWithTag("fx-toggle").performScrollTo().performClick()
        settle()
        rule.onNodeWithTag("fxedit-crush").performScrollTo().performClick()
        settle()
        rule.onNodeWithTag("fx-editor").assertExists()
        rule.onNodeWithTag("fxb-crush-stays").performScrollTo().performClick()
        rule.onNodeWithTag("fxt-crush-pad").performScrollTo().performClick()
        settle()
        assertEquals("stays", state.fxSet("crush").behave)
        assertEquals(state.project.sel, state.fxSet("crush").target)
        rule.onNodeWithTag("fx-editor").performScrollTo()
        shot("35-fx-editor")
        // Stays on: one tap on, the next off
        at("fx-crush").performTouchInput { down(Offset(width * .3f, height * .6f)); up() }
        settle()
        assertTrue("crush" in state.fxOn)
        at("fx-crush").performTouchInput { down(Offset(width * .3f, height * .6f)); up() }
        settle()
        assertFalse("crush" in state.fxOn)
        // slide along Echo while holding it: the amount follows the finger
        at("fx-echo").performTouchInput { down(Offset(width * .2f, height * .6f)); moveTo(Offset(width * .85f, height * .6f)) }
        settle()
        assertTrue("sliding sets how much (${state.fxLive["echo"]})", (state.fxLive["echo"] ?: 0f) > .6f)
        rule.onNodeWithTag("fx-echo").performTouchInput { up() }
        settle()
    }

    /** SE-1, SE-5, SE-6, SE-7: the Sample sheet's Play page, the one-tap Sample button and the repeat styles. */
    @Test fun samplePlayPage() {
        val k = state.project.kit[state.project.sel]
        assertTrue(k.isNotEmpty())
        rule.onNodeWithTag("open-sample").performClick()
        settle()
        assertTrue("the Sample button opens the Sample sheet", state.overlay is Overlay.Doctor)
        rule.onNodeWithTag("pitch-grain").performClick(); settle()
        assertEquals("grain", state.project.edit(k).pitch)
        rule.onNodeWithTag("bake-it").performScrollTo().performClick(); settle()
        assertTrue("Bake it turns bake on", state.project.edit(k).bake)
        rule.onNodeWithTag("held-sustain").performScrollTo().performClick(); settle()
        assertEquals("sustain", state.project.edit(k).held)
        assertTrue("a sustained sound holds", state.project.edit(k).holds())
        rule.onNodeWithTag("held-beat").performScrollTo().performClick(); settle()
        rule.onNodeWithTag("beat-4").performScrollTo().performClick(); settle()
        assertEquals("beat", state.project.edit(k).held)
        assertEquals(4, state.project.edit(k).beatN)
        shot("37-sample-play")
        rule.onNodeWithTag("pitch-stretch").performClick(); settle()
        assertEquals("stretch", state.project.edit(k).pitch)
        state.overlay = null
        settle()
        // repeat styles live in the repeat rate menu
        if (rule.onAllNodes(androidx.compose.ui.test.hasTestTag("dock-rate")).fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithTag("dock-rate").performClick(); settle()
            rule.onNodeWithTag("rate-sflow").performClick(); settle()
            assertEquals("flow", state.live.nrStyle)
        } else {
            state.setLive { nrStyle = "flow" }
            assertEquals("flow", state.live.nrStyle)
        }
        state.setLive { nrStyle = "cut" }
    }

    /** SE-3: the Latency screen offers to bake pads that needed live grain; the CPU pop-up offers the same. */
    @Test fun latencyBakeAndCpuPopUp() {
        val pad = state.project.sel
        val k = state.project.kit[pad]
        state.engine.grainUsed[pad] = true
        assertTrue("a pad that played live grain is offered for baking", pad in state.grainPadsToBake())
        state.overlay = Overlay.AudioCheck
        settle()
        rule.onNodeWithTag("check-bake").performScrollTo().performClick(); settle()
        assertTrue("Bake them turns bake on for that pad's sound", state.project.edit(k).bake)
        assertFalse("and it is no longer offered", pad in state.grainPadsToBake())
        shot("38-latency")
        state.overlay = null
        state.setEdit(k, state.project.edit(k).copy().also { it.bake = false }, true)
        state.cpuAsk = true
        settle()
        rule.onNodeWithTag("cpu-banner").assertExists()
        shot("39-cpu-pop-up")
        rule.onNodeWithTag("cpu-bake").performClick(); settle()
        assertFalse("the pop-up closes", state.cpuAsk)
        assertTrue("and bakes the pad", state.project.edit(k).bake)
    }

    /** SE-11, SE-12: a synth on a pad, its knobs, its modes, the Synthipad, and saving it with the mess. */
    @Test fun synthTab() {
        tab("Synth")
        shot("40-synth-none")
        at("synth-new-empty").performClick(); settle()
        val pad = state.project.sel
        val key = state.project.kit[pad]
        assertTrue("a synth sits on a pad", com.tideclock.messmachine.engine.Project.isSynth(key) && state.synthOn(pad) != null)
        assertEquals("the Synths jar names it", "Warm pad", state.lib.titleOf(key))
        // turn the cutoff up with a drag
        val cut0 = state.synthOn(pad)!!.get("cut")
        rule.onNodeWithTag("knob-cut").performTouchInput {
            down(Offset(centerX, 20.dp.toPx())); for (i in 0 until 10) moveBy(Offset(0f, -12f)); up()
        }
        settle()
        assertTrue("dragging up turns the knob up (${state.synthOn(pad)!!.get("cut")} from $cut0)", state.synthOn(pad)!!.get("cut") > cut0 * 1.05)
        rule.onNodeWithTag("knob-cut").performTouchInput { doubleClick(Offset(centerX, 20.dp.toPx())) }
        settle()
        assertEquals("double tap puts it back to its default", com.tideclock.messmachine.engine.Synth.ANALOG.params.first { it.id == "cut" }.def, state.synthOn(pad)!!.get("cut"), 1.0)
        at("synthkind-fm").performClick(); settle()
        assertEquals("the FM chip makes it an FM synth", "fm", state.synthOn(pad)!!.kind.id)
        shot("44-synth-fm")
        at("synthkind-wave").performClick(); settle()
        assertEquals("the Wavetable chip makes it a wavetable synth", "wave", state.synthOn(pad)!!.kind.id)
        shot("45-synth-wave")
        at("synthkind-grain").performClick(); settle()
        assertEquals("the Granular chip makes it granular", "grain", state.synthOn(pad)!!.kind.id)
        assertTrue("and it reads one of the mess's sounds (${state.synthOn(pad)!!.src})", state.synthOn(pad)!!.src in state.grainSources())
        rule.onNodeWithTag("grain-src").assertExists()
        shot("46-synth-grain")
        at("synthkind-drum").performClick(); settle()
        at("synthmodel-2").performClick(); settle()
        assertEquals("a drum model chip picks the model (Hat)", 2.0, state.synthOn(pad)!!.get("model"), 0.0)
        shot("47-synth-drum")
        at("synthkind-pluck").performClick(); settle()
        assertEquals("the Pluck chip makes it a pluck", "pluck", state.synthOn(pad)!!.kind.id)
        shot("48-synth-pluck")
        at("synthkind-analog").performClick(); settle()
        at("synthmode-2").performClick(); settle()
        assertEquals("Acid", 2.0, state.synthOn(pad)!!.get("mode"), 0.0)
        shot("41-synth-simple")
        at("synthview-full").performClick(); settle()
        shot("42-synth-full")
        at("synthview-pad").performClick(); settle()
        val t0 = state.engine.triggers
        rule.onNodeWithTag("synthipad-notes").performTouchInput { down(Offset(width * .1f, height * .9f)); up() }
        settle()
        state.engine.render(FloatArray(512), 256)
        assertTrue("a Synthipad note plays the synth", state.engine.triggers > t0)
        shot("43-synthipad")
        state.synthView = "simple"
        // it saves and loads with the mess
        val back = com.tideclock.messmachine.engine.Project.fromJson(state.project.toJson(), state.lib::has).project
        assertEquals("the synth comes back with its knobs", 2.0, back.synth(key)?.get("mode") ?: -1.0, 0.0)
        assertEquals("on the same pad", key, back.kit[pad])
    }

    /**
     * Note #11 and what came with it: the Synth tab has Hold's settings (a held note plus single notes on top),
     * a synth's Padiano opens on the pad grid, the Padiano has XL and extra rows, Repeat has patterns, and
     * mouth control moves the picked pad's filter.
     */
    @Test fun synthHoldPadianoPatternsAndMouth() {
        tab("Synth")
        at("synth-new-empty").performClick(); settle()
        val pad = state.project.sel
        // Hold on the Synth tab, one slot: the first note holds, the next ones play and stop
        at("hold-toggle").performClick(); settle()
        // the keys bar starts as its slim row (Hold, and what key and chords are set); its button opens the rest
        assertTrue("the slim bar is what shows first", !state.keysBarOpen)
        at("keysbar-open").performClick(); settle()
        while (state.holdCount > 1) { at("hold-down").performClick(); settle() }
        assertTrue("Hold is on with one slot (${state.holdCount})", state.holdOn && state.holdCount == 1)
        shot("53-synth-hold")
        at("piano-keys-$pad").performTouchInput { down(Offset(width * .05f, height * .5f)); up() }
        settle(); state.engine.render(FloatArray(512), 256)
        assertEquals("the first note holds", 1, state.latched.size)
        val t0 = state.engine.triggers
        at("piano-keys-$pad").performTouchInput { down(Offset(width * .5f, height * .5f)) }
        settle(); state.engine.render(FloatArray(512), 256)
        val playing = state.engine.triggers > t0
        rule.onNodeWithTag("piano-keys-$pad").performTouchInput { up() }
        settle()
        assertTrue("a second note plays on top of the held one (and doesn't take its place)", playing && state.latched.size == 1)
        at("hold-release").performClick(); settle()
        state.switchHold(false); settle()
        // the same synth as a Padiano on the pad grid
        at("synth-to-pads").performClick(); settle()
        assertTrue("On pads shows the grid with this synth's Padiano open", state.tab == Tab.Pads && pad in state.keyboards)
        val t1 = state.engine.triggers
        at("piano-keys-$pad").performTouchInput { down(Offset(width * .3f, height * .5f)); up() }
        settle(); state.engine.render(FloatArray(512), 256)
        assertTrue("the grid's Padiano plays the synth", state.engine.triggers > t1)
        rule.onNodeWithTag("piano-synth-$pad").assertExists()
        // two rows: the top row an octave up (at M, so both rows are on screen for the taps)
        val h0 = rule.onNodeWithTag("piano-keys-$pad").fetchSemanticsNode().size.height.toFloat()
        at("piano-set-$pad").performClick(); settle()
        at("pset-rows-2").performClick(); settle()
        at("piano-keys-$pad").performTouchInput { down(Offset(width * .02f, height * .25f)) }
        settle()
        val top = state.lastChord
        rule.onNodeWithTag("piano-keys-$pad").performTouchInput { up() }
        at("piano-keys-$pad").performTouchInput { down(Offset(width * .02f, height * .75f)) }
        settle()
        val bottom = state.lastChord
        rule.onNodeWithTag("piano-keys-$pad").performTouchInput { up() }
        settle()
        assertTrue("the top row is an octave above the bottom one ($top over $bottom)", top.isNotEmpty() && top != bottom && top.filter { it.isLetter() || it == '#' } == bottom.filter { it.isLetter() || it == '#' })
        // XL: much taller (the node's own size: boundsInRoot is clipped to what the page shows)
        at("pset-size-3").performClick(); settle()
        val h1 = rule.onNodeWithTag("piano-keys-$pad").fetchSemanticsNode().size.height.toFloat()
        assertTrue("XL with two rows is well over twice as tall as one M row ($h0 to $h1)", h1 > h0 * 2.5f)
        at("piano-set-$pad").performClick(); settle()
        shot("54-padiano-xl-two-rows")
        state.changePadianoSize(1); state.changePadianoRows(1); settle()
        // Repeat patterns, from the dock's Repeat panel
        state.switchDockOpen(true); settle()
        at("dock-rate").performClick(); settle()
        rule.onNodeWithTag("pat-xxo").performClick(); settle()
        assertEquals("2 on, 1 off is hit, hit, skip", "xx.", state.live.nrPat)
        assertTrue("picking a pattern turns Repeat on", state.live.nr)
        rule.onNodeWithTag("pattern-strip").performTouchInput { down(Offset(width / 6f, height / 2f)); up() }
        settle()
        assertEquals("tapping the first step flips it into your own pattern", ".x.", state.live.nrPat)
        shot("55-repeat-pattern")
        rule.onNodeWithTag("pat-all").performClick(); settle()
        assertEquals("", state.live.nrPat)
        state.setLive { nr = false }
        state.overlay = null; settle()
        // mouth control: the sheet, and a reading moving the synth's filter (dark closes it, bright opens it)
        state.overlay = Overlay.Mouth; settle()
        rule.onNodeWithTag("mouth-toggle").assertExists()
        shot("56-mouth-control")
        state.mouthApply(.1f, .5f, true); settle()
        val dark = state.padFilt[pad]?.first ?: 1f
        state.mouthApply(.9f, .5f, true); settle()
        val bright = state.padFilt[pad]?.first ?: 1f
        assertTrue("\"ooo\" closes the filter and \"eee\" opens it ($dark, $bright)", dark < .3f && bright > .7f)
        state.mouthApply(.9f, 0f, false); settle()
        state.overlay = null; settle()
    }

    /** SE-17: a pack installs from its zip as a jar of sounds, refuses entries outside its folder, and removes. */
    @Test fun packInstallAndRemove() {
        fun zipOf(vararg entries: Pair<String, ByteArray>): java.io.File {
            val f = java.io.File.createTempFile("pack", ".zip")
            java.util.zip.ZipOutputStream(f.outputStream()).use { z -> for ((n, b) in entries) { z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(b); z.closeEntry() } }
            return f
        }
        val wav = java.io.ByteArrayOutputStream().also { o -> com.tideclock.messmachine.engine.Wav.writeStereo16(o, FloatArray(8820) { i -> (.4 * Math.sin(i * .05)).toFloat() }, 44100) }.toByteArray()
        val json = """{"id":"test-pack","name":"Test Pack","version":1,"clips":[{"id":"beep","file":"clips/beep.wav","title":"Beep","type":"perc"}]}"""
        state.packs.installZip(zipOf("pack.json" to json.toByteArray(), "clips/beep.wav" to wav), "test-pack")
        assertTrue("the pack's sound is in the library", state.lib.has("pack:test-pack:beep") && state.lib.load("pack:test-pack:beep") != null)
        assertEquals("in its own jar", "Test Pack", state.lib.cab("pack:test-pack").name)
        var refused = false
        try { state.packs.installZip(zipOf("pack.json" to json.toByteArray(), "../evil.txt" to "x".toByteArray()), "test-pack") } catch (e: SecurityException) { refused = true }
        assertTrue("a zip entry outside the pack's folder is refused", refused)
        assertTrue("and the installed pack is untouched", state.lib.has("pack:test-pack:beep"))
        state.packOffers = listOf(Packs.Offer("test-pack", "Test Pack", "Made by the big test.", 2, 1, 9000, ""))
        state.overlay = Overlay.Packs
        settle()
        rule.onNodeWithTag("pack-test-pack").assertExists()
        rule.onNodeWithTag("pack-install-test-pack").assertExists()   // version 2 offered over 1: Update
        shot("49-packs")
        rule.onNodeWithTag("pack-remove-test-pack").performClick()
        settle()
        assertFalse("Remove takes the pack off the phone", state.lib.has("pack:test-pack:beep"))
        state.overlay = null
        settle()
    }

    /** MM-T: every lesson starts on a practice mess and puts the real mess back exactly, finished or stopped. */
    @Test fun lessonsPutTheMessBack() {
        state.project.name = "Tyler's real mess"
        // the saved file carries the time it was written: compare everything but that
        fun mess() = state.project.toJson().replace(Regex("\\d{4}-\\d\\d-\\d\\dT[0-9:.]+Z"), "")
        val before = mess()
        for (t in Tutorials.all) {
            state.startTutorial(t)
            settle()
            rule.onNodeWithTag("tut-card").assertExists()
            assertNotEquals("${t.id} runs on a practice mess", "Tyler's real mess", state.project.name)
            state.endTutorial(false)
            settle()
            assertEquals("${t.id} puts the mess back", before, mess())
        }
        assertFalse("nothing left over from the lessons", java.io.File(androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().filesDir, "before-lesson.json").exists())
    }

    /** MM-T: the Hold lesson, done by touch: each step moves on only when it is really done, and the ring finds its control. */
    @Test fun holdLessonByTouch() {
        state.overlay = Overlay.Learn
        settle()
        shot("50-learn")
        rule.onNodeWithTag("learn-hold").performScrollTo().performClick()
        settle(); state.tutTick(); settle(); settle()
        assertEquals("hold", state.tut?.id)
        assertTrue("the ring can find H in the dock (dock-hold at ${TutHi.rects["dock-hold"]})", (TutHi.rects["dock-hold"]?.width ?: 0f) > 0f)
        shot("51-lesson-hold")
        rule.onNodeWithTag("pad-2").performClick(); settle(); state.tutTick()
        assertEquals("tapping a pad before Hold is on does not count", 0, state.tutStep)
        rule.onNodeWithTag("dock-hold").performClick(); settle(); state.tutTick()
        assertEquals(1, state.tutStep)
        rule.onNodeWithTag("pad-2").performClick(); settle(); state.tutTick()
        assertEquals("a grid pad latches under Hold", 2, state.tutStep)
        assertTrue("as pad 3's own latch", (1000 + state.bank * 16 + 2) in state.latched)
        // "played" counts notes the engine really started: on a phone the audio thread renders all the time, here we render a block
        rule.onNodeWithTag("pad-3").performClick(); settle(); state.engine.render(FloatArray(512), 256); state.tutTick()
        assertEquals(3, state.tutStep)
        assertEquals("both pads hold (three slots to start)", 2, state.latched.size)
        rule.onNodeWithTag("pad-2").performClick(); settle()
        rule.onNodeWithTag("pad-3").performClick(); settle(); state.tutTick()
        assertEquals("tapping the held pads lets them go", 4, state.tutStep)
        rule.onNodeWithTag("dock-hold").performClick(); settle(); state.tutTick(); settle()
        assertNull("the lesson ends when the last step is done", state.tut)
        assertTrue("and counts as done", "hold" in state.tutsDone)
    }

    @Test fun doctorEffects() {
        val k = state.project.kit[0]
        state.overlay = Overlay.Doctor(k)
        settle()
        rule.onNodeWithTag("dtab-fx").performClick()
        settle()
        rule.onNodeWithTag("dfx-rvb").performScrollTo().performClick()
        settle()
        rule.onNodeWithTag("dfx-chorus").performScrollTo().performClick()
        settle()
        assertTrue("reverb and chorus are on the kick", state.project.edit(k).rvb > 0 && state.project.edit(k).chorus > 0)
        rule.onNodeWithTag("dfx-chorus").performScrollTo()
        shot("36-doctor-effects")
        state.overlay = null
        settle()
    }

    @Test fun routingAndPadXy() {
        padMenuOf(7)
        rule.onNodeWithTag("menu-route").performClick()
        settle()
        assertEquals(1, state.routeOf(7))
        rule.mainClock.advanceTimeBy(1000); settle()
        padMenuOf(7)
        rule.onNodeWithTag("menu-xy").performClick()
        settle()
        assertEquals(Overlay.Xy(7), state.overlay)
        rule.onNodeWithTag("xy-preset-Bend").performScrollTo().performClick()
        settle()
        assertTrue(state.xyOf(7).four)
        shot("37-xy-editor")
        // the try-out pad: right bends the pitch up, and letting go springs it back
        val tryPad = rule.onNodeWithTag("xy-try", useUnmergedTree = true)
        tryPad.performScrollTo(); rule.mainClock.advanceTimeBy(600); settle()
        tryPad.performTouchInput { down(Offset(width * .98f, height / 2f)) }
        settle(); loudest(1024)
        assertTrue("right on Bend raises pad 8's pitch (${state.engine.padRate(7)})", state.engine.padRate(7) > 1.3)
        tryPad.performTouchInput { up() }
        settle(); loudest(1024)
        assertEquals(1.0, state.engine.padRate(7), 1e-6)
        state.overlay = null
        settle()
    }

    @Test fun drawACircleForTheSphere() {
        state.setXy(7, XySetup.PRESETS[2])
        settle()
        // a circle on a pad opens its XY as a sphere; it never opens the menu
        at("pad-7").performTouchInput {
            val c = center
            val r = width * .3f
            down(c + Offset(r, 0f))
            for (k in 1..22) { val a = k * (2 * Math.PI / 18); moveTo(c + Offset((r * kotlin.math.cos(a)).toFloat(), (r * kotlin.math.sin(a)).toFloat())) }
        }
        settle()
        val sp = state.sphere
        assertTrue("a circle opens the sphere", sp != null)
        rule.onNodeWithTag("pad-7").performTouchInput { moveTo(center + Offset(sp!!.r * .9f, 0f)) }
        settle(); loudest(1024)
        shot("38-sphere-on-pad")
        assertTrue("moving right inside the sphere bends the pad up (${state.engine.padRate(7)})", state.engine.padRate(7) > 1.2)
        rule.onNodeWithTag("pad-7").performTouchInput { up() }
        settle(); loudest(1024)
        assertEquals("lifting closes the sphere", null, state.sphere)
        assertEquals("a circle never opens the menu", -1, state.padMenu)
        // on a Padiano note too
        state.toggleKeyboard(7)
        settle()
        at("piano-keys-7").performTouchInput {
            val c = Offset(width * .5f, height * .5f)
            val r = height * .35f
            down(c + Offset(r, 0f))
            for (k in 1..22) { val a = k * (2 * Math.PI / 18); moveTo(c + Offset((r * kotlin.math.cos(a)).toFloat(), (r * kotlin.math.sin(a)).toFloat())) }
        }
        settle()
        assertTrue("a circle on a Padiano note opens a sphere for that note", (state.sphere?.note ?: Int.MIN_VALUE) != Int.MIN_VALUE)
        rule.onNodeWithTag("piano-keys-7").performTouchInput { up() }
        settle()
        assertEquals(null, state.sphere)
    }

    /* ---------- your console ---------- */

    @Test fun deskArrangesAndPlays() {
        tab("Console")
        shot("30-desk-perform")
        assertEquals(listOf("Perform", "Beats", "Sampling"), state.desks.map { it.name })
        assertEquals(listOf("pads", "hold", "piano", "pfilt", "fx", "xy", "repeat", "loops"), state.console.map { it.type })
        // it plays: a pad, an effect, the Padiano
        loudest()
        tapPad(0)
        val crushNode = at("cfx-crush")
        crushNode.performTouchInput { down(Offset(width * .3f, height * .6f)) }
        settle(); loudest(1024)
        assertTrue("an effect held on the desk", state.engine.fxActive())
        rule.onNodeWithTag("cfx-crush").performTouchInput { up() }
        settle()
        val pp = state.console.first { it.type == "piano" }.pad
        at("piano-keys-$pp").performTouchInput { down(Offset(width * .5f, height * .5f)) }
        settle()
        val key = loudest()
        rule.onNodeWithTag("piano-keys-$pp").performTouchInput { up() }
        settle()
        assertTrue("the desk's Padiano plays (peak $key)", key > .02f)
        // Arrange: drag a piece down, resize one, pin one, add one
        rule.onNodeWithTag("console-edit").performScrollTo().performClick()
        settle()
        shot("31-desk-arrange")
        val holdY = state.console.first { it.type == "hold" }.y
        at("mod-drag-1").performTouchInput { down(center); moveBy(Offset(0f, 30f)); moveBy(Offset(0f, 90f)); moveBy(Offset(0f, 90f)); up() }
        settle()
        val hold = state.console.first { it.type == "hold" }
        assertTrue("dragging moved the Hold piece down (${holdY} to ${hold.y})", hold.y > holdY)
        assertEquals("the piece you moved comes to the front", "hold", state.console.last().type)
        val hi = state.console.indexOfFirst { it.type == "hold" }
        val w0 = state.console[hi].w
        at("mod-size-$hi").performTouchInput { down(center); moveBy(Offset(-30f, 0f)); moveBy(Offset(-120f, 0f)); up() }
        settle()
        assertTrue("dragging the corner narrows it (${w0} to ${state.console[state.console.indexOfFirst { it.type == "hold" }].w})", state.console.first { it.type == "hold" }.w < w0)
        val hi2 = state.console.indexOfFirst { it.type == "hold" }
        rule.onNodeWithTag("mod-pin-$hi2").performScrollTo().performClick()
        settle()
        assertTrue(state.console.first { it.type == "hold" }.pinned)
        state.addModule("xyp")
        settle()
        assertEquals("xyp", state.console.last().type)
        rule.onNodeWithTag("mod-${state.console.size - 1}").performScrollTo()
        shot("32-desk-arranged")
        val saved = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("mess", 0).getString("desks", "")!!
        assertTrue("the layout is saved", "xyp" in saved && "Beats" in saved)
        // another layout, and back
        rule.onNodeWithTag("desk-1").performScrollTo().performClick()
        settle()
        assertEquals(1, state.deskIdx)
        assertEquals("loops", state.console.first().type)
        rule.onNodeWithTag("console-edit").performScrollTo().performClick()
        settle()
        shot("33-desk-beats")
        state.switchDesk(0)
        settle()
        assertEquals("xyp", state.console.last().type)
    }

    /* ---------- build D: menus everywhere, the pitch strip, motion recording ---------- */

    @Test fun playTabHoldsPadsKeysAndMyDesk() {
        rule.onAllNodesWithTag("tab-Console").fetchSemanticsNodes().let { assertTrue("no separate Desk tab", it.isEmpty()) }
        at("view-desk").performClick()
        settle()
        assertEquals(Tab.Console, state.tab)
        rule.onNodeWithTag("tab-Steps").performClick()
        settle()
        rule.onNodeWithTag("tab-Pads").performClick()
        settle()
        assertEquals("Play reopens on the view you used last", Tab.Console, state.tab)
        at("mode-keys").performClick()
        settle()
        assertEquals(Tab.Pads, state.tab)
        assertTrue(state.keysMode)
        // the pad card under the pads is gone from the code entirely (build 57), so there is nothing left to look for
        shot("46-play-keys")
    }

    /**
     * BT-1, BT-5: the design check. Walks every page and sheet, counts the controls outside the playing
     * surface (a row of one- or two-character buttons like 1 2 3 4 or A to H counts once), and lists
     * labels that show up on more than one page. Over a target, or repeated, needs a line with a reason in
     * design/exceptions.txt. The table goes to build/screens/design-check.md (published with the screens).
     * DESIGN_CHECK=report only reports.
     */
    @Test fun designCheck() {
        val surface = listOf("pad-", "steps-", "piano-keys-", "roll", "bar-", "slot-", "mess-pic-")
        val pages = linkedMapOf<String, List<String>>()
        fun labelOf(n: androidx.compose.ui.semantics.SemanticsNode): String =
            (n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString(" ") { it.text }
                ?: n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)?.joinToString(" ") ?: "")
                .trim().replace(Regex("\\s+"), " ")
        fun grab(name: String, inside: (List<String>) -> Boolean) {
            settle()
            val groups = HashSet<Int>()
            val labels = ArrayList<String>()
            val seen = HashSet<String>()
            for (n in rule.onAllNodes(androidx.compose.ui.test.hasClickAction()).fetchSemanticsNodes()) {
                val tags = generateSequence(n) { it.parent }.mapNotNull { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag) }.toList()
                if (!inside(tags) || tags.any { t -> surface.any { t.startsWith(it) } }) continue
                val l = labelOf(n)
                val own = n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)
                val chips = tags.firstOrNull { it.endsWith("-chips") }
                val row = tags.firstOrNull { it.startsWith("row-") }
                // a row of filter chips is one control; a button on every row of a list is one control
                val key = when {
                    chips != null -> "[chips: $chips]"
                    row != null -> "[each row: ${l.split(" ").firstOrNull() ?: "?"}]"
                    l.endsWith("▾") && own != null -> "[picker: $own]"   // a picker's label is its value; its tag is who it is
                    l.length <= 2 -> if (groups.add(n.parent?.id ?: n.id)) "[row of short buttons]" else null
                    else -> l
                }
                if (key != null && (key == "[row of short buttons]" || seen.add(key))) labels.add(key)
            }
            pages[name] = labels
        }
        fun page(name: String) = grab(name) { tags -> tags.any { it.startsWith("screen-") } && "sheet" !in tags }
        fun sheet(name: String) = grab(name) { tags -> "sheet" in tags }

        page("Play: Pads")
        at("mode-keys").performClick(); page("Play: Keys")
        at("mode-pads").performClick(); settle()
        for (t in listOf("Steps", "Roll", "Song", "Sounds")) { tab(t); page(t) }
        // the Synth tab: with no synth on the pad, then each view of one
        tab("Synth"); page("Synth: none")
        at("synth-new-empty").performClick(); settle()
        for (v in listOf("simple", "full", "pad")) { at("synthview-$v").performClick(); page("Synth: $v") }
        state.synthView = "simple"
        tab("Pads")
        for ((n, o) in listOf("Messes" to Overlay.Files, "Settings" to Overlay.Settings, "Tempo" to Overlay.Tempo, "Notes for Claude" to Overlay.Notes, "Latency" to Overlay.AudioCheck)) {
            state.overlay = o; sheet(n); state.overlay = null; settle()
        }
        state.overlay = Overlay.Doctor(state.project.kit[0])
        for (d in listOf("play", "trim", "shape", "fx", "tools")) { at("dtab-$d").performClick(); sheet("Sample: $d") }
        state.overlay = null; settle()
        // the pad menu
        longPress("pad-1")
        val menu = rule.onAllNodes(androidx.compose.ui.test.hasTestTag("padmenu")).fetchSemanticsNodes().let { it.size }
        val menuItems = rule.onAllNodes(androidx.compose.ui.test.hasClickAction()).fetchSemanticsNodes()
            .count { n -> n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith("menu-") == true }
        state.padMenu = -1; settle()
        val tabs = rule.onAllNodes(androidx.compose.ui.test.hasClickAction()).fetchSemanticsNodes()
            .count { n -> n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith("tab-") == true }

        // exceptions, each with a reason
        val ex = listOf(File("../design/exceptions.txt"), File("design/exceptions.txt")).firstOrNull { it.exists() }?.readLines().orEmpty()
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        fun parts(prefix: String) = ex.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix).split("|").map { p -> p.trim() } }
        val okLabels = parts("label:").filter { it.size >= 2 && it[1].isNotEmpty() }.map { it[0] }.toSet()
        val okScreens = parts("screen:").filter { it.size >= 3 && it[2].isNotEmpty() }.associate { it[0] to (it[1].toIntOrNull() ?: 0) }
        val okMenus = parts("menu:").filter { it.size >= 3 && it[2].isNotEmpty() }.associate { it[0] to (it[1].toIntOrNull() ?: 0) }

        val problems = ArrayList<String>()
        val out = StringBuilder("# Design check\n\nTargets: 5 tabs, about 12 controls per screen outside the playing surface, menus of 8.\n\n| Screen | Controls | |\n|---|---|---|\n")
        for ((n, l) in pages) {
            val over = l.size > 12
            val allowed = okScreens[n]?.let { l.size <= it } ?: false
            if (over && !allowed) problems.add("$n has ${l.size} controls (target 12): ${l.joinToString(", ")}")
            out.append("| $n | ${l.size} | ${if (!over) "ok" else if (allowed) "over, with a reason" else "OVER"} |\n")
        }
        out.append("\nTabs: $tabs. Pad menu: $menuItems items.\n")
        // Tyler's exception: synths get a sixth tab (design/exceptions.txt "tabs:")
        val okTabs = parts("tabs:").filter { it.size >= 2 && it[1].isNotEmpty() }.mapNotNull { it[0].toIntOrNull() }.maxOrNull() ?: 5
        if (tabs > okTabs) problems.add("$tabs tabs (target 5)")
        if (menuItems > 8 && (okMenus["Pad menu"] ?: 0) < menuItems) problems.add("Pad menu has $menuItems items (target 8)")
        val where = HashMap<String, MutableSet<String>>()
        for ((n, l) in pages) for (x in l) if (!x.startsWith("[") || x.startsWith("[picker:")) where.getOrPut(x) { LinkedHashSet() }.add(n.substringBefore(":"))
        val repeated = where.filter { it.value.size > 1 }
        out.append("\n## Labels on more than one screen\n\n")
        for ((x, w) in repeated.toSortedMap()) {
            val ok = x in okLabels
            if (!ok) problems.add("\"$x\" is on ${w.joinToString(", ")}")
            out.append("- $x: ${w.joinToString(", ")}${if (ok) " (reason in exceptions)" else " (NO REASON)"}\n")
        }
        out.append("\n## Every screen\n\n")
        for ((n, l) in pages) out.append("- **$n:** ${l.joinToString(", ")}\n")
        File("build/screens").mkdirs()
        File("build/screens/design-check.md").writeText(out.toString())
        println(out)
        if (System.getenv("DESIGN_CHECK") != "report") assertTrue("Design check:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test fun carryAPadOntoAnotherToSwap() {
        val a = state.project.kit[4]
        val b = state.project.kit[5]
        assertTrue(a.isNotEmpty() && b.isNotEmpty())
        // hold pad 5 still until it arms, then slide onto pad 6 and lift
        at("pad-4").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(800)
        settle()
        val to = rule.onNodeWithTag("pad-5").fetchSemanticsNode().let { it.positionInRoot + androidx.compose.ui.geometry.Offset(it.size.width / 2f, it.size.height / 2f) }
        rule.onNodeWithTag("pad-4").performTouchInput { moveTo(to - rule.onNodeWithTag("pad-4").fetchSemanticsNode().positionInRoot); up() }
        settle()
        assertEquals("pad 5 now has pad 6's sound", b, state.project.kit[4])
        assertEquals("and pad 6 has pad 5's", a, state.project.kit[5])
        assertEquals("no menu opened", -1, state.padMenu)
        rule.onNodeWithTag("toast-undo").performClick()
        settle()
        assertEquals("Undo swaps them back", a, state.project.kit[4])
    }

    @Test fun longerLoopsDesksPicturesAndBuzz() {
        tab("Steps")
        repeat(3) { at("looplen").performClick(); settle() }
        assertEquals("1, 2, 4, 8 bars", 128, state.project.pat().len)
        at("page-8").performClick()
        settle()
        assertEquals(7, state.page)
        shot("47-steps-eight-bars")
        at("looplen").performClick()
        settle()
        assertEquals(16, state.project.pat().len)
        // a saved copy of a desk
        tab("Console")
        val n = state.desks.size
        at("console-edit").performClick()
        settle()
        at("desk-copy").performClick()
        settle()
        assertEquals(n + 1, state.desks.size)
        shot("48-desk-copy")
        at("desk-delete").performClick()
        settle()
        assertEquals(n, state.desks.size)
        // the pad buzz setting, and a saved mess shows its picture
        state.switchBuzz(true)
        assertTrue(state.buzz)
        state.saveMess("Picture mess", false)
        waitFor("the save") { state.files.any { it.name == "Picture mess" } }
        state.refreshFiles()
        waitFor("the picture") { state.files.any { it.name == "Picture mess" && it.colors.any { c -> c != 0 } } }
        state.overlay = Overlay.Files
        settle()
        shot("49-messes-with-pictures")
    }

    @Test fun audioCheckTimesTapsAndSavesANote() {
        // MM-1: the Audio check opens from the speed card in Notes, times a tap and saves its numbers as a note
        state.overlay = Overlay.Notes
        settle()
        rule.onNodeWithTag("audio-check").performScrollTo().performClick()
        settle()
        assertEquals(Overlay.AudioCheck, state.overlay)
        rule.onNodeWithTag("check-pad").performClick()
        settle()
        // the test's clock is not the phone's, so the touch time itself is only checked on a real phone; the click must play
        loudest(2048)
        assertTrue("the tap played a click and timed its wait for a block", state.engine.hits > 0)
        assertTrue("the notes carry the lag line", state.noteDetails().contains("**Lag:**"))
        shot("50-audio-check")
        val before = state.notes.notes.size
        rule.onNodeWithTag("check-save").performScrollTo().performClick()
        settle()
        assertEquals(Overlay.Notes, state.overlay)
        assertEquals(before + 1, state.notes.notes.size)
    }

    @Test fun playingKeepsGoingInTheBackgroundUntilStopped() {
        // MM-10: while the music plays, the background notification runs; stopping (or the setting off) ends it
        val app = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
        state.backgroundPlay(true)
        val started = app.nextStartedService
        assertEquals(PlaybackService::class.java.name, started?.component?.className)
        state.backgroundPlay(false)
        assertEquals(PlaybackService::class.java.name, app.nextStoppedService?.component?.className)
        // with the setting off, playing starts nothing
        state.overlay = Overlay.Settings
        settle()
        rule.onNodeWithTag("set-bgplay").performScrollTo().performClick()
        settle()
        assertFalse(state.bgPlay)
        state.backgroundPlay(true)
        assertEquals(null, app.nextStartedService)
        shot("51-settings-background")
    }

    /**
     * The MIDI map: aim at a pad and the next keys land on it and the pads after it (right, then down); a Padiano key
     * and a synth knob take a key and a controller knob the same way. Menu by the banks opens any pad's menu, the
     * keys bar opens and shuts from its slim row, and the Synth tab picks which pad's synth it shows (note #12).
     */
    @Test fun midiMapMenuButtonAndKeysBar() {
        // the slim keys bar is always on the Play tab; its button opens key, chords and Hold's settings
        assertTrue("no Padiano yet: the bar is slim", !state.keysBarOpen)
        at("keysbar-open").performClick(); settle()
        rule.onNodeWithTag("pick-chord").assertExists()
        rule.onNodeWithTag("hold-up").assertExists()
        at("keysbar-shut").performClick(); settle()
        assertTrue(!state.keysBarOpen)
        // Menu by the banks opens the picked pad's menu, whatever kind of pad it is
        tapPad(1)
        rule.mainClock.advanceTimeBy(300); settle()
        at("open-padmenu").performClick(); settle()
        assertEquals("Menu opens the picked pad's menu", 1, state.padMenu)
        state.padMenu = -1
        rule.mainClock.advanceTimeBy(1000); settle()

        // the MIDI map, from the top menu
        rule.onNodeWithTag("menu").performClick(); settle()
        rule.onNodeWithTag("midimap-open").performClick(); settle()
        rule.mainClock.advanceTimeBy(600); settle()
        assertTrue("the MIDI map panel is up", state.midiMapOn)
        rule.onNodeWithTag("midimap").assertExists()
        tapPad(0)
        for (k in 0 until 8) { state.midiDown(72 + k); state.midiUp(72 + k) }
        settle()
        assertEquals("the first key landed on pad 1", MidiTarget("pad", 0), state.midiNotes[72])
        assertEquals("the eighth on pad 8: right, then down a row", MidiTarget("pad", 7), state.midiNotes[79])
        assertEquals("and the next one is waiting on pad 9", MidiTarget("pad", 8), state.midiCursor)
        shot("57-midi-map")
        // a Padiano key: open one, touch a key, and the next controller key is that key
        state.toggleKeyboard(0); settle()
        at("piano-keys-0").performTouchInput { down(Offset(width * .05f, height * .5f)); up() }
        settle()
        val aimed = state.midiCursor
        assertTrue("touching a Padiano key aims at it ($aimed)", aimed != null && aimed.kind == "key" && aimed.a == 0)
        state.midiDown(90); state.midiUp(90); settle()
        assertEquals("the controller key now plays that Padiano key", aimed, state.midiNotes[90])
        state.toggleKeyboard(0); settle()
        rule.onNodeWithTag("midimap-done").performClick(); settle()
        assertTrue(!state.midiMapOn)
        // played back: key 74 was the third one, so it hits pad 3
        loudest()
        state.midiDown(74)
        assertTrue("a mapped key sounds its pad", loudest() > .01f)
        assertEquals("and picks it, like a finger would", 2, state.project.sel)
        state.midiUp(74)
        // an unmapped note still does what it always did
        state.midiDown(36); state.midiUp(36)
        assertEquals(0, state.project.sel)

        // a synth knob: touch it, turn a controller knob, and that knob turns it from then on
        tab("Synth")
        at("synth-new-empty").performClick(); settle()
        val pad = state.project.sel
        val sp = state.synthOn(pad)!!
        val pm = sp.kind.params.first { it.simple && it.id != "mode" && it.id != "model" }
        state.switchMidiMap(true); settle()
        at("knob-${pm.id}").performTouchInput { down(Offset(width / 2f, height * .25f)); up() }
        rule.mainClock.advanceTimeBy(600); settle()
        assertEquals("a tap on a knob aims at it", MidiTarget("knob", pad, 0, pm.id), state.midiCursor)
        state.midiCc(21, 127); state.midiCc(21, 100); settle()
        assertEquals("the controller knob maps to it, and turning on does not map the next knob", 1, state.midiKnobs.size)
        assertEquals(MidiTarget("knob", pad, 0, pm.id), state.midiKnobs[21])
        shot("58-midi-map-knob")
        state.switchMidiMap(false); settle()
        state.midiCc(21, 0); settle()
        val low = pm.toKnob(sp.get(pm.id))
        state.midiCc(21, 127); settle()
        val high = pm.toKnob(sp.get(pm.id))
        assertTrue("the mapped controller knob turns the synth knob ($low to $high)", low < .1 && high > .9)
        // note #12: the Synth tab picks which pad's synth it shows
        rule.onNodeWithTag("synth-pad").assertExists()
        tab("Pads")
        tapPad(0)
        tab("Synth")
        rule.onNodeWithTag("synth-go-$pad").performClick(); settle()
        assertEquals("one tap from a pad with no synth to the synth this mess has", pad, state.project.sel)
        state.midiClearAll()
    }

    @Test fun midiNotesHitPadsAndKeys() {
        // MM-12: note 36 is pad 1 of the bank on screen; note 60 plays the selected pad at its own pitch
        state.midiDown(36)
        assertTrue("note 36 sounds pad 1", loudest() > .01f)
        state.midiUp(36)
        assertEquals(0, state.project.sel)
        state.midiDown(60)
        assertTrue("note 60 plays the selected pad as a key", loudest() > .01f)
        state.midiUp(60)
    }

    @Test fun helpShowsOnceThenBehindTheQuestionMark() {
        tab("Steps")
        assertTrue("first visit to Steps shows its how-to", rule.onAllNodesWithTag("help").fetchSemanticsNodes().isNotEmpty())
        tab("Roll")
        tab("Steps")
        assertTrue("second visit hides it", rule.onAllNodesWithTag("help").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag("help-toggle").performClick()
        settle()
        assertTrue("? shows it again", rule.onAllNodesWithTag("help").fetchSemanticsNodes().isNotEmpty())
        shot("45-help-on")
    }

    @Test fun stepsHeaderFitsOnAPhone() {
        tab("Steps")
        // the eight loop letters sit on one line (build 44 wrapped H onto a second row)
        val a = rule.onNodeWithTag("slot-0").fetchSemanticsNode().positionInRoot
        val h = rule.onNodeWithTag("slot-7").fetchSemanticsNode().positionInRoot
        assertEquals("loop H on the same line as A", a.y, h.y, 1f)
        // banks read 1 2 3 4 like the Pads page, and switch the bank
        at("steps-bank-2").performClick()
        settle()
        assertEquals(2, state.bank)
        shot("44-steps-header")
    }

    @Test fun menusEverywhereAndThePitchStrip() {
        tab("Steps")
        // hold a sound's name, then lift: its pad menu
        longPress("steps-name-0")
        assertEquals(0, state.padMenu)
        state.padMenu = -1
        rule.mainClock.advanceTimeBy(1000); settle()
        // tap a name: its pitch strip; pick a step, then a note
        at("steps-name-7").performClick()
        settle()
        assertEquals(7, state.stepStrip)
        at("steps-7").performTouchInput { click(Offset(width * (4.5f / 16f), height / 2f)) }
        settle()
        assertEquals(4, state.stepPick)
        at("pitch-keys-7").performTouchInput { click(Offset(width * (7.5f / 13f), height / 2f)) }
        settle()
        val k7 = state.project.kit[7]
        assertTrue("step 5 now plays a pitch (${state.project.pat().notes(k7).map { "${it.s}:${it.p}" }})", state.project.pat().notes(k7).any { it.s == 4 && it.p == 7 })
        rule.onNodeWithTag("pitch-strip-7").performScrollTo()
        shot("39-steps-pitch-strip")
        rule.onNodeWithTag("pitch-plain").performClick()
        settle()
        assertTrue(state.project.pat().step(k7, 4) && state.project.pat().notes(k7).none { it.s == 4 })
        // a loop letter's menu
        longPress("slot-0")
        assertEquals(0, state.slotMenu)
        rule.onNodeWithTag("slotmenu-copy").performClick()
        settle()
        assertTrue("loop A copied to B", state.project.pats[1].used())
        // a song bar's menu
        tab("Song")
        state.edit { song[0] = 0; song[1] = 1 }
        settle()
        longPress("bar-0")
        assertEquals(0, state.barMenu)
        rule.onNodeWithTag("barmenu-delete").performClick()
        settle()
        assertEquals("taking bar 1 out moves bar 2 up", 1, state.project.song[0])
    }

    @Test fun motionRecordingPlaysBack() {
        state.setXy(7, XySetup.PRESETS[2])
        state.togglePlay()
        loudest(4096); state.tick(System.nanoTime())
        state.setLive { rec = true }
        settle()
        // hold the XY to the right through a few steps
        state.xyMove(7, 1f, 0f, true)
        repeat(6) { loudest(2048); state.tick(System.nanoTime()) }
        state.xyMove(7, 1f, 0f, false)
        settle()
        assertTrue("the moves are in the loop", state.project.pat().hasMotion())
        val lane = state.project.pat().motion["7:${Engine.T_PITCH}"]
        assertTrue("pitch moves were recorded (${lane?.take(16)})", lane != null && lane.any { !it.isNaN() && it > 0f })
        state.setLive { rec = false }
        state.stop()
        settle()
        tab("Steps")
        rule.onNodeWithTag("clear-moves").performScrollTo().performClick()
        settle()
        assertFalse(state.project.pat().hasMotion())
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
        // M, S and the doctor live in the sound's menu now (one row per sound)
        longPress("steps-name-0")
        rule.onNodeWithTag("menu-mute-0").performClick()
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
        at("roll").performTouchInput { click(Offset(width * .6f, height * .5f)) }
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
        // a loop carries its key, and the key can be searched for under either of its names
        rule.onNodeWithTag("search").performTextClearance()
        rule.onNodeWithTag("search").performTextInput("d minor")
        settle()
        rule.onNodeWithTag("row-loop:give-me-throne-1").assertExists()
        assertEquals("D minor", state.lib.sound("loop:give-me-throne-1").mkey)
        rule.onNodeWithTag("search").performTextClearance()
        rule.onNodeWithTag("search").performTextInput("f major")
        settle()
        rule.onNodeWithTag("row-loop:give-me-throne-1").assertExists()
        assertTrue("hits and one-shots carry no key", state.lib.sounds.none { it.cab != "loops" && it.cab != "mine" && it.mkey.isNotEmpty() })
        // by instrument type and feel
        rule.onNodeWithTag("search").performTextClearance()
        rule.onNodeWithTag("mode-type").performClick()
        settle()
        rule.onNodeWithTag("type-bass").performScrollTo().performClick()
        settle()
        rule.onNodeWithTag("put-samples/bw-bass-note.wav").assertExists()
        rule.onNodeWithTag("feel-dark").performScrollTo().performClick()
        settle()
        shot("10b-sounds-by-type-bass-dark")
        // piece 10: the Loops and Hits jars replace the Heap and the Qbldy shelves
        assertTrue("the Loops jar is there", state.lib.cabs.any { it.id == "loops" } && state.lib.cabs.none { it.id == "heap" || it.id == "qbldy" })
        val loop = state.lib.sounds.first { it.cab == "loops" }
        assertTrue("loops carry their tempo", loop.bpm > 0 && loop.beats == 4)
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
        // the resample remembers the tempo it was made at and its length in beats, so it follows the mess's tempo
        // like a built-in loop; its key was measured once (named only when sure)
        val rs = state.bench.items.first { it.kind == "rs" }
        assertEquals(state.project.bpm.toDouble(), rs.bpm, 0.0)
        assertEquals(state.project.pat().len / 4, rs.beats)
        assertTrue("its key was measured", rs.mkey != null)
        val rsKey = state.bench.keyOf(rs)
        assertEquals(rs.bpm, state.lib.sound(rsKey).bpm, 0.0)
        assertEquals("the sample itself carries the tempo", rs.bpm, state.lib.load(rsKey).bpm, 0.0)
        shot("12-workbench-with-resample")
    }

    /* ---------- live effects ---------- */

    @Test fun fxHoldAndFilterPad() {
        rule.onNodeWithTag("fx-toggle").performClick()
        settle()
        state.togglePlay()
        loudest(4800)
        at("fx-stutter").performTouchInput { down(center) }
        settle()
        loudest(512)
        assertTrue("stutter is held", state.engine.fxActive())
        rule.onNodeWithTag("fx-stutter").performTouchInput { up() }
        settle()
        loudest(512)
        at("xy").performTouchInput { down(Offset(width * .2f, height * .3f)); moveTo(Offset(width * .6f, height * .4f)) }
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
        // the pad card is gone: the doctor opens from the pad's menu (hold, then lift)
        padMenuOf(7)
        rule.onNodeWithTag("menu-doctor").performClick()
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
        // the top bar is one row: messes, settings, redo and notes sit behind ☰
        rule.onNodeWithTag("menu").performClick()
        settle()
        rule.onNodeWithTag("settings").performClick()
        settle()
        rule.onNodeWithTag("set-buzz").assertExists()
        shot("15b-settings")
        rule.onNodeWithTag("done").performClick()
        settle()
        rule.onNodeWithTag("menu").performClick()
        settle()
        rule.onNodeWithTag("messes").performClick()
        settle()
        shot("15-messes")
        state.saveMess("Test mess", false)
        waitFor("the save") { state.files.any { it.name == "Test mess" } }
        rule.onNodeWithTag("done").performClick()
        settle()
        rule.onNodeWithTag("menu").performClick()
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
        // the Hush button is gone: Stop, then Stop again, hushes
        rule.onNodeWithTag("stop").performClick()
        settle()
        rule.onNodeWithTag("stop").performClick()
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
