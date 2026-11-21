package com.tideclock.messmachine

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
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
import org.junit.Assert.assertNotNull
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
     * Open a pad's menu the way a player does since build 84: tap the pad, then Menu on the bottom dock. An empty pad
     * is picked through the menu's target instead (a tap on it would record or open the jars).
     */
    /**
     * Edit (Menu in Classic), then the pad. In the new layouts a loaded pad opens the Edit tray: drawer picks which of
     * its five drawers (sound, fx, keys, mod, more) to open, so the item asked for is on screen.
     */
    private fun padMenuOf(d: Int, drawer: String? = null) {
        if (state.tray != null) { state.tray = null; settle() }
        if (state.project.kit[state.bank * 16 + d].isEmpty()) { state.selectPad(state.bank * 16 + d, false); settle() }
        else { tapPad(d); rule.mainClock.advanceTimeBy(300); settle() }
        menuThenTap("pad-$d")
        if (drawer != null && state.tray?.startsWith("edit:") == true) { at("edit-tab-$drawer").performClick(); settle() }
    }

    /** Pad d's menu (Classic) or its Edit tray (the new layouts) is open. */
    private fun editOpen(d: Int) = state.padMenu == state.bank * 16 + d || state.tray == "edit:${state.bank * 16 + d}"

    /** Menu, then tap: arm Menu on the bottom bar, then tap a control through the clear layer (Nov 18). */
    private fun menuThenTap(tag: String) {
        at("open-padmenu").performClick(); settle()
        assertTrue("Menu is armed", state.menuArmed)
        val r = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val layer = rule.onNodeWithTag("menu-layer").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithTag("menu-layer").performTouchInput { click(r.center - layer.topLeft) }
        settle()
    }

    /**
     * Scroll a node into view and let the page stop moving, so the next touch lands on it. The bottom dock does not
     * scroll, so for what is on it there is nothing to scroll.
     */
    private fun at(tag: String) = rule.onNodeWithTag(tag).also { try { it.performScrollTo() } catch (_: AssertionError) {}; rule.mainClock.advanceTimeBy(600); settle() }

    /** Pads and Desk share the Play tab now: "Console" means the Play tab's My Desk view. */
    private fun tab(label: String) {
        if (label == "Console") { state.pickPlayLayout(true); rule.onNodeWithTag("tab-Pads").performClick(); settle(); return }
        if (label == "Pads") state.pickPlayLayout(false)
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

        // a pad's menu (Menu on the bottom dock): Padiano drops a keyboard right under that pad's row
        state.putOnPad(8, "samples/glass-harp.wav")
        state.putOnPad(13, "samples/wheezy-organ.wav")
        waitFor("the new pads to load") { state.lib.loaded("samples/glass-harp.wav") && state.lib.loaded("samples/wheezy-organ.wav") }
        for (d in listOf(1, 5, 8, 13)) {
            padMenuOf(d, "keys")
            if (!editOpen(d)) {
                val b = rule.onNodeWithTag("pad-$d").fetchSemanticsNode().boundsInRoot
                println("DEBUG pad $d menu=${state.padMenu} toast=${state.toast} overlay=${state.overlay} bounds=$b keyboards=${state.keyboards.toList()} sel=${state.project.sel}")
                shot("debug-menu-$d")
            }
            assertTrue("Edit opens pad ${d + 1}'s tray", editOpen(d))
            if (d == 1) shot("02-pad-menu")
            rule.onNodeWithTag("menu-piano").performClick()
            // let the menu finish closing before the next press
            rule.mainClock.advanceTimeBy(1000)
            settle()
        }
        // the tray stays open after Padiano (it is not a menu): close it so the keys under it can be played
        state.tray = null; settle()
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
        padMenuOf(0, "keys")
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

        // Nov 18: the view row is gone; the Padiano's Full size took Keys mode's place
        rule.onAllNodesWithTag("mode-keys").fetchSemanticsNodes().let { assertTrue("no Keys view", it.isEmpty()) }
        rule.onAllNodesWithTag("mode-step").fetchSemanticsNodes().let { assertTrue("no Step view", it.isEmpty()) }
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
        // Hold's count and Let go are in the key panel over the bottom dock
        at("keysbar-open").performClick(); settle()
        // one tap turns Hold on, three notes by default
        at("hold-toggle").performClick()
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
        at("hold-release").performClick()
        settle()
        assertTrue(state.latched.isEmpty())
        // the Hold switch on the piano itself: turning it off lets every held note go
        for (p in listOf(0, 4)) { state.pianoDown(7, p); state.pianoUp(7, p) }
        assertEquals(2, state.latched.size)
        at("hold-toggle").performClick()
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
        padMenuOf(3)
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
        padMenuOf(0)
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

    @Test fun aBrandNewInstallOpensTheStartCard() {
        // build 105: nothing saved yet and no guide ever seen: three steps to try, not the build notes
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        for (n in listOf("current.json", "current.json.bak", "current.json.new")) File(ctx.filesDir, n).delete()
        ctx.getSharedPreferences("mess", 0).edit().remove("guideSeen").commit()
        val fresh = AppState(ctx)
        assertEquals(Overlay.Start, fresh.overlay)
    }

    @Test fun startCardAndSavedFaders() {
        state.overlay = Overlay.Start
        settle()
        rule.onNodeWithTag("start-learn").assertExists()
        shot("86-start-card")
        rule.onNodeWithTag("start-go").performClick(); settle()
        assertEquals(null, state.overlay)
        // build 105: an FX fader lands in the mess once the hand settles, and Undo puts it back
        val t = com.tideclock.messmachine.engine.Engine.T_RVB
        state.fxSendTo(0, t, .7f, false)
        ShadowLooper.idleMainLooper(600, java.util.concurrent.TimeUnit.MILLISECONDS); settle()
        assertEquals(.7, state.project.opt(0).fxHome(t), .001)
        state.undo(); settle()
        assertEquals(0.0, state.project.opt(0).fxHome(t), .001)
        assertEquals(null, state.fxSend["0:$t"])
    }

    @Test fun stepStrengthsAndSixteenLevels() {
        // build 108: a lit step cycles loud, quiet, off; 16 Levels puts one sound on every pad, soft to hard
        val k = state.project.kit[0]
        state.edit { pat().clear() }
        state.toggleStep(k, 2)
        assertTrue(state.project.pat().step(k, 2)); assertEquals(1, state.project.pat().strength(k, 2))
        state.toggleStep(k, 2); assertEquals("second tap: accent", 2, state.project.pat().strength(k, 2))
        state.toggleStep(k, 2); assertEquals("third tap: ghost", 0, state.project.pat().strength(k, 2))
        assertTrue(state.project.pat().step(k, 2))
        state.toggleStep(k, 2); assertFalse("fourth tap: off", state.project.pat().step(k, 2))
        state.toggleStep(k, 4); state.toggleStep(k, 4); state.toggleStep(k, 6); state.toggleStep(k, 6); state.toggleStep(k, 6)
        tab("Steps"); settle()
        shot("89-step-strengths")
        tab("Pads"); settle()
        state.switchLevels(0); settle()
        assertEquals(0, state.levelsPad)
        rule.onNodeWithTag("levels-banner").assertExists()
        state.padDown(15); loudest(1024); state.padUp(15)
        assertTrue("a level pad plays the levels sound", state.engine.hits > 0)
        shot("90-sixteen-levels")
        rule.onNodeWithTag("levels-banner").performClick(); settle()
        assertEquals(-1, state.levelsPad)
        // a MIDI key's velocity reaches the pad
        state.midiDown(36, 20); loudest(1024); state.midiUp(36)
    }

    @Test fun skinsChangeColoursNotButtons() {
        // build 5 of the overhaul: a colour skin is separate from the Look (the button skin)
        assertEquals("classic", state.skin)
        state.overlay = Overlay.Settings; settle()
        at("skin-stage").performClick(); settle()
        assertEquals("stage", state.skin)
        assertEquals("Stage is pure black", androidx.compose.ui.graphics.Color(0xFF000000), com.tideclock.messmachine.ui.C.Paper)
        assertEquals("the Look did not change", "classic", state.look)
        state.overlay = null; settle()
        shot("91-skin-stage-pads")
        tab("Steps"); settle(); shot("92-skin-stage-steps")
        tab("Pads"); settle()
        state.overlay = Overlay.Settings; settle()
        at("skin-specimen").performClick(); settle()
        at("look-dark").performClick(); settle()
        state.overlay = null; settle()
        assertEquals("specimen", state.skin); assertEquals("dark", state.look)
        shot("93-skin-specimen-pro-dark")
        // the skin is kept for the next launch
        assertEquals("specimen", ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("mess", 0).getString("skin", null))
        // the daylight skin: dark words on paper, and words on a pad still read
        state.pickSkin("felt"); settle()
        assertTrue("Felt and Paper is light", com.tideclock.messmachine.ui.C.Paper.luminance() > .6f)
        assertTrue("words on paper are dark", com.tideclock.messmachine.ui.ink(com.tideclock.messmachine.ui.C.Paper).luminance() < .2f)
        shot("96-skin-felt"); tab("Steps"); settle(); shot("97-skin-felt-steps"); tab("Pads"); settle()
        state.pickSkin("rust"); settle(); shot("98-skin-rust")
        state.pickSkin("nonsense"); settle()
        assertEquals("an unknown skin falls back to Classic", "classic", state.skin)
    }

    @Test fun holdAStepForItsChance() {
        // build 6 of the overhaul: hold a step for its sheet: how often, which time round, how hard
        val k = state.project.kit[0]
        state.edit { pat().clear() }
        tab("Steps"); settle()
        at("steps-0").performTouchInput { down(Offset(width * 3.5f / 16f, height / 2f)) }
        rule.mainClock.advanceTimeBy(700); settle()
        rule.onNodeWithTag("steps-0").performTouchInput { advanceEventTime(100); up() }
        settle()
        assertEquals("holding step 4 opens its sheet", k to 3, state.stepSheet)
        assertTrue("and turns it on", state.project.pat().step(k, 3))
        at("chance-50").performClick(); settle()
        assertEquals(50, state.project.pat().chance(k, 3))
        at("chance-101").performClick(); settle()
        assertEquals(101, state.project.pat().chance(k, 3))
        at("strength-2").performClick(); settle()
        assertEquals(2, state.project.pat().strength(k, 3))
        shot("94-step-sheet")
        state.undo(); settle()
        assertEquals("undo takes back the strength", 1, state.project.pat().strength(k, 3))
        at("stepsheet-done").performClick(); settle()
        assertEquals(null, state.stepSheet)
        rule.onNodeWithTag("stepsheet").assertDoesNotExist()
    }

    @Test fun stemsAndMidiForADaw() {
        // build 7 of the overhaul: each sound alone in a zip, and the notes as a MIDI file
        val k0 = state.project.kit[0]; val k1 = state.project.kit[1]
        state.edit { pat().clear(); pat().setStep(k0, 0, true); pat().setStep(k1, 4, true) }
        state.overlay = Overlay.Files; settle()
        val dir = java.io.File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "shared")
        dir.listFiles()?.forEach { it.delete() }
        at("midi-loop").performClick(); settle()
        val mid = dir.listFiles()?.firstOrNull { it.name.endsWith(".mid") }
        assertTrue("a MIDI file is made", mid != null && mid.length() > 30)
        at("stems-loop").performClick(); settle()
        waitFor("the stems", 40000) { state.busy == null && dir.listFiles()?.any { it.name.endsWith("-stems.zip") } == true }
        val zip = dir.listFiles()!!.first { it.name.endsWith("-stems.zip") }
        val names = java.util.zip.ZipFile(zip).use { z -> z.entries().toList().map { it.name } }
        assertEquals("one WAV per sound in the loop: $names", 2, names.size)
        assertTrue(names.all { it.endsWith(".wav") })
        shot("95-stems-and-midi")
    }

    @Test fun midiMapInTheNewLayout() {
        // the MIDI map, one at a time, in the new layout (build84Pack runs it in Classic)
        assertEquals("new", state.layout)
        state.switchMidiMap(true); settle()
        at("midimap-one").performClick(); settle()
        assertFalse("One at a time is picked", state.midiRun)
        tapPad(0)
        assertEquals("tapping a pad aims the map at it (map on: ${state.midiMapOn}, overlay ${state.overlay})", "pad", state.midiCursor?.kind)
        state.midiDown(80); state.midiUp(80); settle()
        assertTrue("one at a time: the map waits for the next tap (map on: ${state.midiMapOn}, cursor ${state.midiCursor}, says ${state.midiLast})", state.midiCursor == null)
        state.switchMidiMap(false); settle()
    }

    @Test fun theEditTrayHasEveryDrawer() {
        // the overhaul's last build: Edit, then a pad, opens its tray with five drawers
        assertEquals("new", state.layout)
        padMenuOf(2)
        assertEquals("Edit then a loaded pad opens its tray", "edit:2", state.tray)
        assertEquals("not the old menu", -1, state.padMenu)
        val want = mapOf("sound" to "menu-hear", "fx" to "fxstrip-2", "keys" to "menu-levels", "mod" to "lfostrip-2", "more" to "menu-route")
        for ((drawer, tag) in want) {
            at("edit-tab-$drawer").performClick(); settle()
            assertEquals(drawer, state.editTab)
            assertTrue("the $drawer drawer shows $tag", rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty())
            shot("99-edit-$drawer")
        }
        at("menu-solo-2").performClick(); settle()
        assertTrue("Solo from the More drawer", state.project.solos.contains(state.project.kit[2]))
        assertEquals("and the tray stays open", "edit:2", state.tray)
        at("menu-solo-2").performClick(); settle()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; settle()
        assertEquals("Back closes the tray", null, state.tray)
        assertFalse("and the app is still there", rule.activity.isFinishing)
        // Classic keeps the menu
        state.pickLayout("classic"); settle()
        padMenuOf(2)
        assertEquals(2, state.padMenu)
        state.padMenu = -1; settle()
    }

    @Test fun stepLocksAndTheMasterBus() {
        // the overhaul's last build: a filter for one step only, and the master bus on the Tempo sheet
        val k = state.project.kit[0]
        state.edit { pat().clear() }
        state.openStepSheet(k, 2)
        tab("Steps"); settle()
        at("lock-${com.tideclock.messmachine.engine.Engine.T_FILTER}-75").performClick(); settle()
        assertEquals(.75f, state.project.pat().lock(k, com.tideclock.messmachine.engine.Engine.T_FILTER, 2), .001f)
        shot("100-step-lock")
        at("lock-${com.tideclock.messmachine.engine.Engine.T_FILTER}-off").performClick(); settle()
        assertTrue(state.project.pat().lock(k, com.tideclock.messmachine.engine.Engine.T_FILTER, 2).isNaN())
        state.stepSheet = null
        tab("Pads"); settle()
        state.overlay = Overlay.Tempo; settle()
        at("master-bus").assertExists()
        state.setMaster(squash = .9, level = 1.1); settle()
        assertEquals(.9, state.project.squash, 1e-9); assertEquals(1.1, state.project.level, 1e-9)
        shot("101-master-bus")
        at("master-reset").performClick(); settle()
        assertEquals(.5, state.project.squash, 1e-9)
        state.overlay = null; settle()
    }

    @Test fun newLayoutEditRepeatAndPerformRow() {
        // build 107: Edit (was Menu) and Repeat on the bottom bar, no side dock, the perform row swipes up
        assertEquals("new", state.layout)
        rule.onNodeWithTag("dock-open").assertDoesNotExist()
        rule.onNodeWithTag("fx-toggle").assertDoesNotExist()
        rule.onNodeWithTag("repeat-toggle").performClick(); settle()
        assertTrue("Repeat on the bar turns repeat on", state.live.nr)
        rule.onNodeWithTag("repeat-toggle").performClick(); settle()
        assertFalse(state.live.nr)
        rule.onNodeWithTag("perform-handle").performClick(); settle()
        assertTrue(state.performOpen)
        rule.onNodeWithTag("performrow").assertExists()
        rule.onNodeWithTag("dock-hold").assertDoesNotExist()
        // the throws joined the row: add them, then they open the perform effects
        if ("throws" !in state.dockItems) state.toggleDockItem("throws")
        settle()
        rule.onNodeWithTag("dock-throws").performScrollTo().performClick(); settle()
        assertTrue(state.showFx)
        // marks: an LFO and a resting FX fader on pad 1 show as dots on the pad
        state.setPadOpt(0) { lfoOn = true }
        state.fxSendTo(0, com.tideclock.messmachine.engine.Engine.T_RVB, .5f, false)
        ShadowLooper.idleMainLooper(600, java.util.concurrent.TimeUnit.MILLISECONDS); settle()
        shot("87-new-layout-perform-row")
        // Edit, then tap Repeat, opens Repeat's settings (rate, style, pattern)
        assertEquals("repeat", state.settingsKind("repeat-toggle"))
        // the perform row folds down again, and Classic puts the side dock back
        rule.onNodeWithTag("perform-handle").performClick(); settle()
        assertFalse(state.performOpen)
        state.pickLayout("classic"); settle()
        rule.onNodeWithTag("dock-open").assertExists()
        rule.onNodeWithTag("fx-toggle").assertExists()
    }

    @Test fun padsCarryTheirFadersAndLanes() {
        // build 106: swapping two pads swaps their FX faders and lanes with their sounds; clearing a pad drops them
        val t = com.tideclock.messmachine.engine.Engine.T_RVB
        state.fxSendTo(1, t, .8f, false)
        ShadowLooper.idleMainLooper(600, java.util.concurrent.TimeUnit.MILLISECONDS); settle()
        state.edit { pat().setMotion(1, t, 0, .4f) }
        val k1 = state.project.kit[1]
        state.swapPads(1, 3); settle()
        assertEquals(k1, state.project.kit[3])
        assertEquals(.8f, state.fxSend["3:$t"] ?: 0f, .001f)
        assertEquals(null, state.fxSend["1:$t"])
        assertTrue("the lane moved with the pad", state.project.pat().motion.containsKey("3:$t") && !state.project.pat().motion.containsKey("1:$t"))
        state.clearPad(3); settle()
        assertEquals(null, state.fxSend["3:$t"])
        assertFalse(state.project.pat().motion.containsKey("3:$t"))
    }

    @Test fun firstLaunchOfANewBuildShowsTheGuide() {
        // a phone installing a new build has not seen its guide: the app must open it at launch, not crash
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        // someone updating already has a working mess (a brand new install gets the start card instead)
        File(ctx.filesDir, "current.json").writeText(com.tideclock.messmachine.engine.Project.demo().toJson())
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
        state.pickLayout("classic"); settle()  // build 107: this flow drives the side dock and the FX button
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

    /**
     * Build 84 (Tyler, Nov 16): Menu is the one way to a pad's menu, so a hold is always playing. No hold, short or
     * long, on any kind of pad opens it; Menu on the bottom dock opens the picked pad's menu every time.
     */
    @Test fun holdsAlwaysPlayAndMenuOpensTheMenu() {
        for (ms in listOf(500L, 900L, 1600L)) {
            rule.onNodeWithTag("pad-2").performScrollTo()
            rule.mainClock.advanceTimeBy(600); settle()
            val t0 = state.engine.triggers
            rule.onNodeWithTag("pad-2").performTouchInput { down(center) }
            rule.mainClock.advanceTimeBy(ms); settle(); state.engine.render(FloatArray(512), 256)
            rule.onNodeWithTag("pad-2").performTouchInput { up() }
            settle()
            assertEquals("a $ms ms hold opens no menu", -1, state.padMenu)
            assertTrue("and it played", state.engine.triggers > t0)
        }
        val k = state.project.kit[2]
        state.setEdit(k, state.project.edit(k).copy().also { it.held = "sustain" }, true)
        settle()
        rule.onNodeWithTag("pad-2").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(800); settle()
        rule.onNodeWithTag("pad-2").performTouchInput { up() }
        settle()
        assertEquals("a sustaining pad's hold is playing", -1, state.padMenu)
        rule.onNodeWithTag("open-padmenu").performClick(); settle()
        rule.onNodeWithTag("open-padmenu").performClick(); settle()
        assertTrue("Edit twice opens the last thing touched: the pad", editOpen(2))
        state.padMenu = -1; state.tray = null
        settle()
        // the bottom dock stays put while the page scrolls: scroll to the bottom and Menu is still on screen
        state.toggleKeyboard(5); settle()
        rule.onNodeWithTag("pad-15").performScrollTo(); rule.mainClock.advanceTimeBy(600); settle()
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val menu = rule.onNodeWithTag("open-padmenu").fetchSemanticsNode().boundsInRoot
        assertTrue("Menu is on screen after scrolling down ($menu in $root)", menu.top >= root.top && menu.bottom <= root.bottom)
        shot("60-bottom-dock-scrolled")
    }

    @Test fun holdLastAndInKeyOnly() {
        state.toggleKeyboard(7)
        settle()
        // Hold Last: only the newest note holds
        state.switchHold(true)
        state.switchKeysBar(true); settle()
        repeat(3) { at("hold-down").performClick() }
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

    /** Note #14: an empty pad's slash. The top left corner records onto it, the rest (and the middle) opens the jars. */
    @Test fun emptyPadSlashRecordsOrPicks() {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        at("pad-12").performTouchInput { down(Offset(width * .18f, height * .18f)) }
        settle()
        rule.onNodeWithTag("pad-12").performTouchInput { up() }
        settle()
        println("EMPTYREC mic=${state.micOn} pad=${state.micPad} overlay=${state.overlay}")
        assertEquals("the top corner does not open the jars", null, state.overlay)
        assertTrue("the top corner starts recording onto the pad", state.micPad == 12 || state.toast?.contains("mic") == true)
        if (state.micOn) tapPad(12)
        waitFor("the mic to stop") { !state.micOn }
        tapPad(12)
        assertEquals("the middle opens the jars", Overlay.Picker(12), state.overlay)
        state.overlay = null; settle()
        at("pad-12").performTouchInput { down(Offset(width * .85f, height * .85f)); up() }
        settle()
        assertEquals("the bottom corner opens the jars", Overlay.Picker(12), state.overlay)
        state.overlay = null; settle()
    }

    /* ---------- build B: glide, the dock, Lock ---------- */

    @Test fun glideOnThePadiano() {
        state.toggleKeyboard(7)
        settle()
        rule.onNodeWithTag("glide-7").performScrollTo().performClick()
        settle()
        assertTrue(state.project.opt(7).glide)
        // a tap never unrolls anything (Nov 18): Glide's settings wait in the tray
        rule.onAllNodesWithTag("glide-legato-7").fetchSemanticsNodes().let { assertTrue("no settings unrolled", it.isEmpty()) }
        menuThenTap("glide-7")
        assertEquals("glide:7", state.tray)
        rule.onNodeWithTag("glide-legato-7").performClick()
        settle()
        assertTrue(state.project.opt(7).legato)
        rule.onNodeWithTag("piano-7").performScrollTo()
        shot("28-padiano-glide")
        // it saves with the mess, and undo takes it back
        state.undo(); settle()
        assertFalse(state.project.opt(7).legato)
    }

    @Test fun dockLockAndFilter() {
        state.pickLayout("classic"); settle()  // build 107: this flow drives the side dock and the FX button
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
        at("dock-fold").performClick()
        settle()
        assertFalse("Fold folds the dock (the pitch wheel made it taller)", state.dockOpen)
        rule.onNodeWithTag("dock-open").performClick()
        settle()
        assertTrue(state.dockOpen)
    }

    /* ---------- build C: effects, routing, XY ---------- */

    @Test fun effectsYouCanEdit() {
        state.pickLayout("classic"); settle()  // build 107: this flow drives the side dock and the FX button
        at("fx-toggle").performClick()
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
        state.pickLayout("classic"); settle()  // build 107: this flow drives the side dock and the FX button
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
        state.pickLayout("classic"); settle()  // build 107: this flow drives the side dock and the FX button
        tab("Synth")
        at("synth-new-empty").performClick(); settle()
        val pad = state.project.sel
        // Hold on the Synth tab, one slot: the first note holds, the next ones play and stop
        at("hold-toggle").performClick(); settle()
        // Hold and the key button sit on the bottom dock under the Synth tab too; the key button opens Hold's count
        if (!state.keysBarOpen) { at("keysbar-open").performClick(); settle() }
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
        // Nov 18 (note #20): rows run straight up, so the top row starts past the bottom row's last key, not an octave up
        assertTrue("the top row starts higher than the bottom one ($top over $bottom)", top.isNotEmpty() && top != bottom)
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
        state.pickLayout("classic"); settle()  // build 107: this flow drives the side dock and the FX button
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
        padMenuOf(7, "more")
        rule.onNodeWithTag("menu-route").performClick()
        settle()
        assertEquals(1, state.routeOf(7))
        rule.mainClock.advanceTimeBy(1000); settle()
        padMenuOf(7, "mod")
        at("menu-xy").performClick()
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
        // My Desk is a Play layout in Settings, next to View size (Nov 18)
        state.overlay = Overlay.Settings; settle()
        at("layout-desk").performClick()
        state.overlay = null; settle()
        assertEquals(Tab.Console, state.tab)
        rule.onNodeWithTag("tab-Steps").performClick()
        settle()
        rule.onNodeWithTag("tab-Pads").performClick()
        settle()
        assertEquals("Play reopens on the layout you picked", Tab.Console, state.tab)
        state.overlay = Overlay.Settings; settle()
        at("layout-pads").performClick()
        state.overlay = null; settle()
        assertEquals(Tab.Pads, state.tab)
        shot("46-play-pads-layout")
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
        at("loop-open").performClick(); page("Play: loops"); at("loop-open").performClick(); settle()
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
        padMenuOf(1)
        val menu = rule.onAllNodes(androidx.compose.ui.test.hasTestTag("padmenu")).fetchSemanticsNodes().let { it.size }
        val menuItems = rule.onAllNodes(androidx.compose.ui.test.hasClickAction()).fetchSemanticsNodes()
            .count { n -> n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith("menu-") == true }
        state.padMenu = -1; state.tray = null; settle()
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

    /** Build 100 (Nov 18): the finer time grid shows on Steps, and Auto shows the whole loop. */
    @Test fun build100FinerGrid() {
        val k = state.project.kit[1]
        state.edit { pats[cur].addOff(k, 8); pats[cur].addOff(k, 16 * com.tideclock.messmachine.engine.Project.TICKS / 16 * 5 + 16) }
        assertEquals(1, state.project.pat().offsIn(k, 0))
        tab("Steps"); settle()
        shot("86-finer-grid-marks")
        state.overlay = Overlay.Settings; settle()
        at("set-recexact").performClick(); settle()
        assertTrue(state.live.recExact)
        state.overlay = null; settle()
        state.toggleLen(); settle()
        at("moves-open").performClick(); settle()
        state.newLane(1, com.tideclock.messmachine.engine.Engine.T_RVB); settle()
        at("moves-lane-canvas").performTouchInput { down(Offset(width * .9f, height * .2f)); up() }; settle()
        val lane = state.project.pat().motion["1:${com.tideclock.messmachine.engine.Engine.T_RVB}"]!!
        assertTrue("a touch near the end of a 2-bar lane lands in bar 2", !lane[28].isNaN() || !lane[29].isNaN())
        shot("87-auto-whole-loop")
        tab("Pads")
    }

    /** Build 99 (Nov 18): the Pro looks, dark and light, with an icon on every bar control. */
    @Test fun build99ProLooks() {
        state.pickLayout("classic"); settle()
        state.overlay = Overlay.Settings; settle()
        at("look-dark").performClick(); settle()
        state.overlay = null; settle()
        assertEquals("dark", state.look)
        // every control on the bars is an icon tile now (its icon carries the control's name)
        for (n in listOf("Play", "Stop", "Rec", "Undo", "What the FAQ", "Settings", "Menu", "Pack", "Hold", "Chords", "FX", "Notes (bug)", "Play tab", "Synth", "Steps", "Roll", "Song", "Sounds"))
            assertTrue("$n has its icon", rule.onAllNodesWithContentDescription(n).fetchSemanticsNodes().isNotEmpty())
        // the new layout's bar: Edit has its icon too
        state.pickLayout("new"); settle()
        assertTrue("Edit has its icon", rule.onAllNodesWithContentDescription("Edit").fetchSemanticsNodes().isNotEmpty())
        state.pickLayout("classic"); settle()
        shot("84-pro-dark")
        at("tab-Steps").performClick(); settle()
        assertEquals("the tabs still work as icons", Tab.Steps, state.tab)
        tab("Pads")
        state.pickLook("light"); settle()
        shot("85-pro-light")
        state.pickLook("classic"); settle()
        assertTrue("Classic has no bar icons", rule.onAllNodesWithContentDescription("Undo").fetchSemanticsNodes().isEmpty())
    }

    /** Build 97 (Nov 18): Menu, then tap; Menu twice; the settings tray; Auto from a fader; flick behind a setting. */
    @Test fun build97MenuThenTap() {
        state.pickLayout("classic"); settle()  // build 107: this flow drives the side dock and the FX button
        // Menu, then a dock button: its tray
        state.switchDockOpen(true); settle()
        menuThenTap("dock-pitch")
        assertEquals("wheel", state.tray)
        at("tray").assertExists()
        shot("82-settings-tray-wheel")
        at("tray-wheel-stay").performClick(); settle()
        assertTrue(state.wheelStay)
        at("tray-done").performClick(); settle()
        assertNull(state.tray)
        state.switchDockOpen(false); settle()
        // Menu, then the tempo: the tempo sheet
        menuThenTap("bpm")
        assertEquals(Overlay.Tempo, state.overlay)
        state.overlay = null; settle()
        // Menu, then something with no settings: Menu lets go and says so
        menuThenTap("tab-Steps")
        assertFalse(state.menuArmed)
        assertEquals(Tab.Pads, state.tab)
        // Menu twice after touching the tempo: the tempo again
        rule.onNodeWithTag("bpm").performClick(); settle(); state.overlay = null; settle()
        at("open-padmenu").performClick(); settle(); at("open-padmenu").performClick(); settle()
        assertEquals("Menu twice opens what was touched last", Overlay.Tempo, state.overlay)
        state.overlay = null; settle()
        // the FX tray goes straight to a fader's Auto lane
        state.toggleKeyboard(1); state.togglePackStrip(1, "fx"); settle()
        menuThenTap("fx-${com.tideclock.messmachine.engine.Engine.T_RVB}-1")
        assertEquals("fx:1", state.tray)
        at("fxauto-${com.tideclock.messmachine.engine.Engine.T_RVB}").performClick(); settle()
        assertEquals(Tab.Steps, state.tab)
        assertTrue(state.movesOpen)
        assertEquals("1:${com.tideclock.messmachine.engine.Engine.T_RVB}", state.moveLane)
        shot("83-auto-from-a-fader")
        tab("Pads")
        // flick is off until Settings turns it on
        assertFalse(state.flickOn)
        state.overlay = Overlay.Settings; settle()
        at("set-flick").performClick(); settle()
        assertTrue(state.flickOn)
        state.overlay = null; settle()
    }

    /** Build 96 (Nov 18): loops from every page, Stay on the wheel, XY min, the FX fader picker, Auto, the Play layout. */
    @Test fun build96LoopsWheelXyFaders() {
        // loops from the top bar: stopped, a pick switches at once; playing, it waits for the bar line
        at("loop-open").performClick(); settle()
        at("loopstrip").assertExists()
        at("loop-2").performClick(); settle()
        assertEquals(2, state.project.cur)
        state.togglePlay(); state.engine.render(FloatArray(512), 256); state.tick(System.nanoTime()); settle()
        assertTrue(state.playing)
        at("loop-0").performClick(); settle()
        assertEquals("playing: the pick waits", 0, state.pendingSlot)
        assertEquals(2, state.project.cur)
        shot("80-loop-strip-waiting")
        state.togglePlay(); state.engine.render(FloatArray(512), 256); state.tick(System.nanoTime()); settle()
        assertEquals("stopped: the waiting loop takes over", 0, state.project.cur)
        at("loop-open").performClick(); settle()

        // the wheel's Stay settles on the nearest note in the key (C major: 1.4 semitones up lands on D, 2)
        state.setScale("Major"); state.setRoot(0); settle()
        if (!state.wheelStay) state.switchWheelStay()
        state.pitchWheel(1.4f / state.wheelRange, false)
        assertEquals(2f / state.wheelRange, state.wheelAt, 1e-4f)
        state.switchWheelStay()
        assertEquals("Spring goes home", 0f, state.wheelAt, 1e-4f)

        // XY min and max: a direction can start above zero
        val d = com.tideclock.messmachine.XyDir(com.tideclock.messmachine.engine.Engine.T_RVB, 1f, .4f)
        val x = com.tideclock.messmachine.XySetup(dirs = listOf(d, com.tideclock.messmachine.XyDir(), com.tideclock.messmachine.XyDir(), com.tideclock.messmachine.XyDir()))
        assertEquals(.4f, x.values(0f, 0f)[com.tideclock.messmachine.engine.Engine.T_RVB]!!, 1e-4f)
        assertEquals(.7f, x.values(.5f, 0f)[com.tideclock.messmachine.engine.Engine.T_RVB]!!, 1e-4f)
        assertEquals("min survives saving", .4f, com.tideclock.messmachine.XySetup.parse(x.code())!!.dirs[0].from, 1e-4f)

        // the FX strip shows only the faders you pick
        state.toggleKeyboard(1); state.togglePackStrip(1, "fx"); settle()
        at("fx-${com.tideclock.messmachine.engine.Engine.T_DIRT}-1").assertExists()
        state.toggleFxShown("Dirt"); settle()
        rule.onAllNodesWithTag("fx-${com.tideclock.messmachine.engine.Engine.T_DIRT}-1").fetchSemanticsNodes().let { assertTrue("Dirt hidden", it.isEmpty()) }
        state.toggleFxShown("Dirt")
        // Wobble lives in the LFO strip now
        state.togglePackStrip(1, "lfo"); settle()
        state.openTray("lfo:1"); settle()
        at("lfo-wobble-1").performClick(); settle()
        state.openTray(null); settle()
        assertTrue(state.project.opt(1).lfoOn && state.project.opt(1).lfoTarget == com.tideclock.messmachine.engine.Engine.T_PITCH)
        shot("81-fx-picker-and-wobble")
        state.toggleKeyboard(1); settle()

        // Moves is called Auto
        tab("Steps"); at("moves-open").assertExists()
        assertTrue(rule.onAllNodesWithText("Auto", ignoreCase = true).fetchSemanticsNodes().isNotEmpty())
        tab("Pads")
    }

    /**
     * Build 84 (notes 14, 16, 18, 19 and Tyler's Nov 16 to 17 answers): the lesson that skipped its first step, chord
     * sets as fixed slots and chords that keep their root, the quarter note, Steps drag to paint, the empty pad's
     * slash, the MIDI map's one-at-a-time and list, and the note box's categories and key.
     */
    @Test fun build84Pack() {
        state.pickLayout("classic"); settle()
        // note #19: with Repeat already on, the Repeat lesson starts on step 1, and Back waits instead of bouncing
        state.setLive { nr = true }; state.switchHold(true); state.pickChordMode("triad"); settle()
        val rpt = com.tideclock.messmachine.Tutorials.all.first { it.id == "repeat" }
        state.startTutorial(rpt); settle(); state.tutTick()
        assertEquals("the lesson starts on its first step", 0, state.tutStep)
        assertFalse("Repeat is off for the lesson", state.live.nr)
        state.setLive { nr = true }; state.tutTick()
        assertEquals(1, state.tutStep)
        state.tutBack(); state.tutTick(); state.tutTick()
        assertEquals("Back stays on step 1 until you do it again", 0, state.tutStep)
        state.setLive { nr = false }; state.tutTick(); state.setLive { nr = true }; state.tutTick()
        assertEquals(1, state.tutStep)
        state.endTutorial(false); settle()
        assertTrue("Repeat and chords come back after the lesson", state.live.nr && state.chordMode == "triad")
        state.setLive { nr = false }; state.switchHold(false)

        // note #18: chords keep the root on the bottom (G4 was D4 G4 B4), and a set never drifts across octaves
        state.setScale("Major"); settle()
        assertEquals(listOf(7, 11, 14), state.chordFor(7).toList())
        state.pickChordMode("set:Sunday Pop"); settle()
        assertEquals("the low C plays C", listOf(0, 4, 7), state.chordFor(0).toList())
        assertEquals("the next C up plays C again (was G7)", listOf(12, 16, 19), state.chordFor(12).toList())
        state.toggleKeyboard(0); settle()
        at("chord-slots-0").assertExists()
        shot("61-chord-set-slots")
        val t0 = state.engine.triggers
        at("chord-slots-0").performTouchInput { down(Offset(width * .3f, height * .25f)); up() }
        settle(); state.engine.render(FloatArray(512), 256)
        assertTrue("a slot plays its chord (${state.lastChord})", state.engine.triggers > t0 && state.lastChord.contains(" · "))
        at("piano-up-0").performClick(); settle()
        state.slotDown(0, 0, 1); state.slotUp(0, 0)
        assertTrue("an octave up, slot 1 is C an octave up (${state.lastChord})", state.lastChord.startsWith("C ·") && state.lastChord.contains("C5"))
        state.toggleKeyboard(0); state.pickChordMode("off"); settle()
        // the Roll reaches C1 to C6, two octaves at a time
        tab("Roll")
        at("roll-octup").performClick(); settle()
        assertEquals(0, state.rollLo)
        at("roll-range").assertExists()
        shot("62-roll-octave-up")
        state.rollLo = -12
        tab("Pads")

        // note #18: Repeat has a quarter note
        state.setLive { nrRate = 4.0 }; settle()
        assertEquals("1/4", com.tideclock.messmachine.ui.rateName(state.live.nrRate))
        state.setLive { nrRate = 1.0 }

        // note #14: the empty pad's slash. Below it opens the jars, above it records
        state.clearPad(15); settle()
        at("pad-15").performTouchInput { down(Offset(width * .8f, height * .8f)); up() }
        settle()
        assertTrue("below the slash picks a sound", state.overlay is Overlay.Picker)
        shot("63-empty-pad-picker")
        state.overlay = null; settle()

        // note #16: drag along a row of steps to paint it, one undo for the whole drag
        tab("Steps")
        val k = state.project.kit[0]
        for (j in 0 until 16) if (state.project.pat().step(k, j)) state.paintStep(k, j, false, true)
        settle()
        at("steps-0").performTouchInput { down(Offset(width * .02f, height * .5f)); moveTo(Offset(width * .3f, height * .5f)); moveTo(Offset(width * .55f, height * .5f)); up() }
        settle()
        val painted = (0 until 16).count { state.project.pat().step(k, it) }
        assertTrue("a drag paints the steps it crosses ($painted)", painted >= 8)
        shot("64-steps-painted")
        state.undo(); settle()
        assertEquals("one undo takes the whole drag back", 0, (0 until 16).count { state.project.pat().step(k, it) })
        tab("Pads")

        // note #19: the MIDI map one at a time, and the list
        state.switchMidiMap(true); settle()
        at("midimap-one").performClick(); settle()
        tapPad(0)
        state.midiDown(80); state.midiUp(80); settle()
        assertTrue("one at a time: the map waits for the next tap", state.midiCursor == null)
        at("midimap-list").performClick(); settle()
        at("midimap-x-n80").assertExists()
        shot("65-midi-map-list")
        at("midimap-x-n80").performClick(); settle()
        assertFalse(state.midiNotes.containsKey(80))
        at("midimap-run").performClick(); state.switchMidiMap(false); settle()

        // note #16: the note box opens on the last kind sent, with big buttons, and the key card
        rule.onNodeWithTag("menu").performClick(); settle(); rule.onNodeWithTag("notes-open").performClick(); settle()
        at("notecat-feature").performClick(); settle()
        at("notekey-open").performClick(); settle()
        at("notekey-field").assertExists()
        shot("66-notes-categories-and-key")
        state.saveNote("feature", "", "Build 84 test note", false); settle()
        assertEquals("feature", state.noteCat)
        state.overlay = null; settle()
    }

    /** The FannyPack, the FX strip, Steps row tools and the pitch wheel (the build after 84). */
    @Test fun fannyPackFxStripRowToolsAndWheel() {
        state.pickLayout("classic"); settle()  // build 107: this flow drives the side dock and the FX button
        val T_RVB = com.tideclock.messmachine.engine.Engine.T_RVB
        // Pack on the bottom dock opens the picked pad's FannyPack under its row, with Keys in it
        tapPad(1); rule.mainClock.advanceTimeBy(300); settle()
        at("pack-toggle").performClick(); settle()
        assertTrue("the pack is open", 1 in state.keyboards)
        assertEquals(listOf("keys"), state.packOf(1))
        at("piano-keys-1").assertExists()
        // + packs the FX strip; a fader sets the pad's reverb send and it stays there
        at("pack-add-1").performClick(); settle()
        rule.onNodeWithTag("pack-pick-fx").performClick(); settle()
        rule.mainClock.advanceTimeBy(600); settle()
        assertEquals(listOf("keys", "fx"), state.packOf(1))
        at("fx-$T_RVB-1").performTouchInput { down(Offset(width / 2f, height * .2f)); up() }
        settle()
        assertTrue("the reverb send stays up (${state.fxSend["1:$T_RVB"]})", (state.fxSend["1:$T_RVB"] ?: 0f) > .6f)
        // the LFO strip: a square wave at 1/8 triplet through a high pass
        at("pack-add-1").performClick(); settle()
        rule.onNodeWithTag("pack-pick-lfo").performClick(); settle()
        rule.mainClock.advanceTimeBy(600); settle()
        assertEquals(listOf("keys", "fx", "lfo"), state.packOf(1))
        at("lfo-more-1").performClick(); settle()
        assertEquals("the wave button opens the LFO's tray", "lfo:1", state.tray)
        at("lfo-wave-4-1").performClick(); settle()
        at("lfo-type-2-1").performClick(); settle()
        at("tray-done").performClick(); settle()
        at("lfo-rate-1").performTouchInput { down(Offset(width * (4.5f / 7f), height / 2f)); up() }; settle()
        at("lfo-trip-1").performClick(); settle()
        val lo = state.project.opt(1)
        assertTrue("the LFO is on, square, 1/8 triplet, the pad filter high pass (${lo.lfoCode()})", lo.lfoOn && lo.lfoWave == 4 && kotlin.math.abs(lo.lfoRate - 4.0 / 3) < 1e-6 && lo.ftype == 2)
        state.engine.play(); for (i in 0 until 40) state.engine.render(FloatArray(512), 256)
        assertTrue("the square wave is moving (${state.engine.lfoValue(1)})", kotlin.math.abs(state.engine.lfoValue(1)) > .99)
        state.engine.stop(); state.engine.render(FloatArray(512), 256)
        at("lfostrip-1").assertExists()
        shot("67b-lfo-strip")
        // tap Keys on the zipper: that strip hides, the FX strip stays
        at("pack-show-keys-1").performClick(); settle()
        rule.onNodeWithTag("piano-keys-1").assertDoesNotExist()
        at("fxstrip-1").assertExists()
        shot("67-fannypack-fx-strip")
        // the pull zips it shut; the pad keeps what is packed for next time
        at("pack-pull-1").performClick(); settle()
        assertFalse(1 in state.keyboards)
        assertEquals(listOf("keys", "fx", "lfo"), state.packOf(1))
        state.fxSendsOff(1)
        state.setPadOpt(1) { lfoOn = false }
        // Repeat's feel: the same note, triplet
        state.switchDockOpen(true); settle()
        at("dock-rate").performClick(); settle()
        rule.onNodeWithTag("feel-trip").performClick(); settle()
        assertEquals("1/16T", com.tideclock.messmachine.ui.rateName(state.live.nrRate))
        state.setLive { nrRate = 1.0 }
        state.switchDockOpen(false); settle()

        // Steps row tools: every 4, nudge, copy and paste, spread
        tab("Steps")
        val k0 = state.project.kit[0]
        state.openStepStrip(0); settle()
        at("row-every4").performClick(); settle()
        assertEquals(listOf(0, 4, 8, 12), (0 until 16).filter { state.project.pat().step(k0, it) })
        at("row-right").performClick(); settle()
        assertEquals(listOf(1, 5, 9, 13), (0 until 16).filter { state.project.pat().step(k0, it) })
        at("row-copy").performClick(); settle()
        shot("68-steps-row-tools")
        val k1 = state.project.kit[1]
        state.openStepStrip(1); settle()
        at("row-paste").performClick(); settle()
        assertEquals(listOf(1, 5, 9, 13), (0 until 16).filter { state.project.pat().step(k1, it) })
        at("row-spread").performClick(); settle()
        assertEquals("3 hits spread over the bar", 3, (0 until 16).count { state.project.pat().step(k1, it) })
        state.openStepStrip(1); settle()
        tab("Pads")

        // the pitch wheel on the dock: bends the picked pad while held, springs back on lift
        state.switchDockOpen(true); settle()
        assertTrue("the wheel joined the dock", "pitch" in state.dockItems)
        at("dock-pitch").performTouchInput { down(Offset(width / 2f, height * .05f)) }
        settle(); state.engine.render(FloatArray(512), 256)
        assertTrue("held near the top it bends up (${state.wheelAt})", state.wheelAt > .8f)
        assertTrue("the picked pad plays higher (${state.engine.padRate(state.project.sel)})", state.engine.padRate(state.project.sel) > 1.05)
        shot("69-pitch-wheel")
        rule.onNodeWithTag("dock-pitch").performTouchInput { up() }
        settle(); state.engine.render(FloatArray(512), 256)
        assertEquals("it springs back", 0f, state.wheelAt)
        state.switchDockOpen(false); settle()
    }

    /** MIDI round two (buttons, pages, Shift, knobs for more than synths, pitch bend) and Step mode on the pads. */
    @Test fun midiRoundTwoAndStepMode() {
        // a controller key taught Play presses Play
        state.switchMidiMap(true); settle()
        state.midiAimAct("play"); state.midiDown(90); state.midiUp(90)
        assertEquals(com.tideclock.messmachine.MidiTarget("act", 0, 0, "play"), state.midiNotes[90])
        // page B: the same key does something else there
        state.midiPageTo(1); state.midiAim(com.tideclock.messmachine.MidiTarget("pad", 3)); state.midiDown(72); state.midiUp(72)
        assertEquals(com.tideclock.messmachine.MidiTarget("pad", 3), state.midiNotes[2 * 128 + 72])
        state.midiPageTo(0)
        // Shift: teach it, then with it held a key learns a second job
        state.midiAimAct("shift"); state.midiDown(91); state.midiUp(91)
        state.midiDown(91)
        assertTrue("Shift works while mapping", state.midiShift)
        state.midiAim(com.tideclock.messmachine.MidiTarget("pad", 5)); state.midiDown(72); state.midiUp(72)
        state.midiUp(91)
        assertFalse(state.midiShift)
        assertEquals(com.tideclock.messmachine.MidiTarget("pad", 5), state.midiNotes[128 + 72])
        // a knob on the pitch wheel
        state.pitchWheel(.5f, true); state.pitchWheel(0f, false)
        state.midiCc(20, 100)
        assertEquals(com.tideclock.messmachine.MidiTarget("wheel", 0), state.midiKnobs[20])
        at("midimap-acts").assertExists()
        shot("70-midi-map-round-two")
        state.switchMidiMap(false); settle()
        // played: Play, Shift + key, the knob, the controller's own pitch bend
        state.midiDown(90); state.midiUp(90); settle()
        assertTrue("the key pressed Play", state.playing)
        state.stop(); settle()
        state.midiDown(91); state.midiDown(72); state.midiUp(72); state.midiUp(91); settle()
        assertEquals("Shift + key played pad 6", 5, state.project.sel)
        state.midiCc(20, 127)
        assertTrue("the knob bends (${state.wheelAt})", state.wheelAt > .9f)
        state.midiCc(20, 64)
        assertEquals(0f, state.wheelAt)
        state.midiBend(.5f)
        assertEquals(.5f, state.wheelAt, .01f)
        state.midiBend(0f)

        // Step mode was retired on Nov 18 (the Steps tab does it better)
    }

    /**
     * What the FAQ, the speed guide and Moves. The FAQ part also walks the pages and fails if a control you can tap
     * has no line in Faq.kt, so the FAQ cannot go stale.
     */
    @Test fun faqSpeedGuideAndMoves() {
        state.pickLayout("classic"); settle()  // build 107: this flow drives the side dock and the FX button
        // What the FAQ: tap ?, then a control: it is explained, not pressed
        rule.onNodeWithTag("help-toggle").performClick(); settle()
        assertTrue(state.faqOn)
        val play = rule.onNodeWithTag("play").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithTag("faq-layer").performTouchInput { click(play.center - rule.onNodeWithTag("faq-layer").fetchSemanticsNode().boundsInRoot.topLeft) }
        settle()
        assertEquals("the tap explains Play", "play", state.faqTag)
        assertFalse("and does not press it", state.playing)
        rule.onNodeWithTag("faq-text").assertExists()
        shot("72-what-the-faq")
        rule.onNodeWithTag("faq-done").performClick(); settle()
        assertFalse(state.faqOn)

        // every control on every page has a line
        val missing = LinkedHashSet<String>()
        fun sweep(where: String) {
            settle()
            for (n in rule.onAllNodes(androidx.compose.ui.test.hasClickAction()).fetchSemanticsNodes()) {
                val tag = generateSequence(n) { it.parent }.mapNotNull { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag) }.firstOrNull() ?: continue
                if (com.tideclock.messmachine.Faq.lineFor(tag) == null) missing.add("$tag ($where)")
            }
        }
        sweep("Play")
        at("loop-open").performClick(); sweep("loops"); at("loop-open").performClick(); settle()
        state.toggleKeyboard(1); state.togglePackStrip(1, "fx"); state.togglePackStrip(1, "lfo"); state.togglePackStrip(1, "xy"); settle()
        sweep("FannyPack")
        state.toggleKeyboard(1); settle()
        state.switchKeysBar(true); sweep("key panel"); state.switchKeysBar(false)
        state.switchDockOpen(true); sweep("dock"); state.switchDockOpen(false)
        for (t in listOf("Steps", "Roll", "Song", "Sounds", "Synth")) { tab(t); sweep(t) }
        tab("Steps"); at("moves-open").performClick(); sweep("Auto")
        tab("Pads")
        state.switchMidiMap(true); sweep("MIDI map"); state.switchMidiMap(false)
        println("FAQ MISSING: $missing")
        assertTrue("controls with no FAQ line: $missing", missing.isEmpty())

        // the speed guide: a card that moves its ring stop to stop
        state.startSpeedGuide(); settle()
        rule.onNodeWithTag("speed-card").assertExists()
        shot("73-speed-guide")
        rule.onNodeWithTag("speed-next").performClick(); settle()
        assertEquals(1, state.speedStop)
        rule.onNodeWithTag("speed-done").performClick(); settle()
        assertEquals(-1, state.speedStop)

        // Moves: a lane for pad 1's filter, a ramp stamped every beat, smoothed, cleared
        tab("Steps")
        state.newLane(0, com.tideclock.messmachine.engine.Engine.T_FILTER); settle()
        at("moves-shape-1").performClick(); settle()
        at("moves-stamp").performClick(); settle()
        val ln = state.project.pat().motion["0:${com.tideclock.messmachine.engine.Engine.T_FILTER}"]
        assertTrue("the ramp is on the lane", ln != null && ln[0] == 0f && ln[3] == 1f)
        at("moves-lane-canvas").assertExists()
        shot("74-moves-lane")
        at("moves-smooth").performClick(); settle()
        at("moves-clear").performClick(); settle()
        assertFalse(state.project.pat().hasMotion())
    }

    /** Menu's Move took over from hold-and-slide (build 84): Move, then tap the pad it goes to. */
    @Test fun moveAPadOntoAnotherToSwap() {
        val a = state.project.kit[4]
        val b = state.project.kit[5]
        assertTrue(a.isNotEmpty() && b.isNotEmpty())
        padMenuOf(4, "more")
        rule.onNodeWithTag("menu-move").performClick(); settle()
        assertEquals(4, state.swapFrom)
        tapPad(5)
        assertEquals("pad 5 now has pad 6's sound", b, state.project.kit[4])
        assertEquals("and pad 6 has pad 5's", a, state.project.kit[5])
        assertEquals("the move is done", -1, state.swapFrom)
        state.undo(); settle()
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

    @Test fun quickNoteAndInAppUpdate() {
        // note #20: tap the bug, talk, tap it again: the note waits on the phone with the moment it was made
        state.padDown(0); state.padUp(0); settle()
        state.fakeVoice = "the piano overlaps bars"
        val before = state.notes.notes.size
        rule.onNodeWithTag("notes").performClick(); settle()
        assertNotNull("listening", state.quickText)
        rule.onNodeWithTag("quick-note").assertExists()
        assertNull("the bug button listens instead of opening the note box", state.overlay)
        shot("76-quick-note-listening")
        state.padDown(1); state.padUp(1)
        rule.onNodeWithTag("notes").performClick(); settle()
        assertNull("the second tap stops listening", state.quickText)
        assertEquals(before + 1, state.notes.notes.size)
        val n = state.notes.notes.first()
        assertEquals("the piano overlaps bars", n.text)
        assertFalse("kept on the phone, not sent", n.sent)
        assertTrue("it carries the phone's state from when it was spoken", n.ctx.contains("**Device:**"))
        assertFalse("and the taps up to then, not the ones after", n.ctxTrail.lines().lastOrNull().orEmpty().contains("pad 2"))
        // spoken prefixes still pick the kind of note
        state.fakeVoice = "wishlist: a glowing bug"
        rule.onNodeWithTag("notes").performClick(); settle()
        rule.onNodeWithTag("quick-save").performClick(); settle()
        assertEquals("wishlist", state.notes.notes.first().cat)
        // Drop throws it away
        state.fakeVoice = "never mind"
        rule.onNodeWithTag("notes").performClick(); settle()
        rule.onNodeWithTag("quick-drop").performClick(); settle()
        assertEquals(before + 2, state.notes.notes.size)
        // the notes list is in the ☰ menu now, with Send all for the waiting ones
        rule.onNodeWithTag("menu").performClick(); settle()
        rule.onNodeWithTag("notes-open").performClick(); settle()
        assertEquals(Overlay.Notes, state.overlay)
        rule.onNodeWithTag("notes-sendall").performScrollTo().assertExists()
        shot("77-notes-send-all")
        state.overlay = null; settle()

        // the update bar: GitHub has a newer build
        assertEquals(92, com.tideclock.messmachine.Updater.buildOf("Build 92 from 09de2b0. Passed the big test and the phone test."))
        assertEquals(0, com.tideclock.messmachine.Updater.buildOf("Mess Machine, latest build"))
        rule.onNodeWithTag("update-bar").assertDoesNotExist()
        state.updater.found(BuildConfig.VERSION_CODE + 1, "https://example.invalid/MessMachine.apk", 1000)
        settle()
        rule.onNodeWithTag("update-bar").assertExists()
        rule.onNodeWithTag("update-go").assertExists()
        shot("78-update-bar")
        rule.onNodeWithTag("update-later").performClick(); settle()
        rule.onNodeWithTag("update-bar").assertDoesNotExist()
        state.overlay = Overlay.Settings; settle()
        rule.onNodeWithTag("update-go").assertExists()
        shot("79-settings-update")
        state.overlay = null; settle()
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
        // the bottom dock is always under the Play tab; its key button opens key, chords and Hold's settings
        assertTrue("no Padiano yet: the panel is shut", !state.keysBarOpen)
        at("keysbar-open").performClick(); settle()
        rule.onNodeWithTag("pick-chord").assertExists()
        rule.onNodeWithTag("hold-up").assertExists()
        at("keysbar-open").performClick(); settle()
        assertTrue(!state.keysBarOpen)
        // Chords on the dock: one tap between chords and single notes, and it remembers which chords
        state.pickChordMode("seventh"); settle()
        at("chords-toggle").performClick(); settle()
        assertEquals("off", state.chordMode)
        at("chords-toggle").performClick(); settle()
        assertEquals("back to 7ths", "seventh", state.chordMode)
        state.pickChordMode("off"); settle()
        // build 80's bug: with the dock open, Sample and Menu squeezed the bank numbers down to dots. Menu is on the
        // bottom dock now, and every bank number keeps its room
        state.switchDockOpen(true); settle()
        val bankDp = rule.onNodeWithTag("bank-0").fetchSemanticsNode().size.width / rule.density.density
        assertTrue("bank buttons keep their numbers with the dock open ($bankDp dp wide)", bankDp >= 34f)
        shot("59-bank-row-dock-open")
        state.switchDockOpen(false); settle()
        // Menu on the bottom dock opens the picked pad's menu, whatever kind of pad it is
        tapPad(1)
        rule.mainClock.advanceTimeBy(300); settle()
        at("open-padmenu").performClick(); settle()
        at("open-padmenu").performClick(); settle()
        assertTrue("Edit twice opens the pad just tapped", editOpen(1))
        state.padMenu = -1; state.tray = null
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
        // a controller key on an empty pad does nothing: it never opens the jars like a finger would
        state.midiDown(36 + 12); state.midiUp(36 + 12); settle()
        assertEquals("a MIDI key on empty pad 13 opens no sheet", null, state.overlay)

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
        // the text diet (note #22): playing pages show no explanations unless Page help is on, and no sentence at all
        for (t in listOf("Pads", "Steps", "Roll", "Song", "Synth")) {
            tab(t)
            assertTrue("no how-to on $t", rule.onAllNodesWithTag("help").fetchSemanticsNodes().isEmpty())
            val texts = rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Text)).fetchSemanticsNodes()
                .flatMap { n -> n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text).orEmpty().map { it.text } }
            val long = texts.filter { it.trim().endsWith(".") && it.trim().split(Regex("\\s+")).size > 12 }
            assertTrue("no sentence over 12 words on $t: $long", long.isEmpty())
        }
        tab("Steps")
        // the ? is What the FAQ now; page help switches on from its card (and Settings)
        rule.onNodeWithTag("help-toggle").performClick(); settle()
        rule.onNodeWithTag("faq-help").performClick(); settle()
        rule.onNodeWithTag("faq-done").performClick(); settle()
        assertTrue("page help shows it again", rule.onAllNodesWithTag("help").fetchSemanticsNodes().isNotEmpty())
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
        state.pickLayout("classic"); settle()  // build 107: this flow drives the side dock and the FX button
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
        // the bug button sits by the tabs now, on every page (note #16)
        rule.onNodeWithTag("menu").performClick(); settle(); rule.onNodeWithTag("notes-open").performClick()
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
        // build 105: a second hit later in the same take; one Undo takes the whole take back, not just the last hit
        loudest(9600)
        tapPad(4)
        loudest(2048)
        state.tick(System.nanoTime())
        settle()
        state.stop()
        state.setLive { rec = false }
        state.undo()
        settle()
        assertFalse("one undo takes the whole take back", (0 until 16).any { state.project.pat().step(k, it) })

        val site = """{"name":"Site mess","bpm":120,"v":3,"kit":["samples/basement-kick.wav","samples/wet-clap.wav","a:uploaded1"],
            "pats":[{"len":16,"steps":{"samples/basement-kick.wav":"1000100010001000"},"notes":{}}],"song":"A","swing":20}"""
        state.importText(site)
        settle()
        assertEquals("Site mess", state.project.name)
        assertEquals(120, state.project.bpm)
        assertTrue(state.project.pat().step("samples/basement-kick.wav", 4))

        // build 105: Bottle it puts the loop on a free pad, as its help line and lesson say
        val padsBefore = state.project.kit.count { it.isNotEmpty() }
        state.bottleIt()
        waitFor("the bottle") { state.busy == null }
        settle()
        assertEquals("Bottle it makes the loop a sound on a free pad", padsBefore + 1, state.project.kit.count { it.isNotEmpty() })
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
        // Play opens on the layout picked last (My Desk, after the loop above): back to the pads themselves
        state.pickPlayLayout(false); settle()
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
