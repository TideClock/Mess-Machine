package com.tideclock.messmachine

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.view.drawToBitmap
import androidx.test.core.app.ApplicationProvider
import com.tideclock.messmachine.ui.MessMachineApp
import com.tideclock.messmachine.ui.UiFlags
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * The usability audit (2026-11-14, Tyler: "a full bug test and usability test as well as design test"). It walks
 * every page, sheet and menu at three screen widths (a small phone, the usual phone, a Fold opened) and measures
 * what a finger and an eye run into:
 *
 *  - buttons too small to hit (outside the playing surfaces, which are sized on purpose)
 *  - text that is cut off, and text under 10 sp
 *  - controls that run off the right edge
 *
 * The table goes to build/screens/usability-<width>.md with a picture of every page (audit-<width>-<page>.png),
 * published with the test screens.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class AuditTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var state: AppState

    /** Smaller than this in either direction and a thumb misses it. The app's small buttons are 34 dp tall. */
    private val smallDp = 32f
    private val tinySp = 10f
    private val surface = listOf("pad-", "steps-", "piano-keys-", "roll", "bar-", "slot-", "mess-pic-")

    @Before fun setUp() {
        UiFlags.testing = true
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("mess", 0).edit().putInt("guideSeen", BuildConfig.VERSION_CODE).commit()
        state = AppState(ctx)
        rule.setContent { MessMachineApp(state, ticker = false) }
        settle()
        val end = System.currentTimeMillis() + 15000
        while (!state.project.kit.filter { it.isNotEmpty() }.all { state.lib.loaded(it) }) {
            if (System.currentTimeMillis() > end) throw AssertionError("Timed out waiting for the starter kit to load")
            Thread.sleep(20); settle()
        }
    }

    private fun settle() {
        repeat(3) { ShadowLooper.idleMainLooper(); rule.waitForIdle() }
    }

    private fun step() { rule.mainClock.advanceTimeBy(700); settle() }

    private fun shot(name: String) {
        settle()
        val dir = File("build/screens").also { it.mkdirs() }
        val bmp = rule.activity.window.decorView.rootView.drawToBitmap(Bitmap.Config.ARGB_8888)
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun tagsOf(n: SemanticsNode): List<String> =
        generateSequence(n) { it.parent }.mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }.toList()

    private fun labelOf(n: SemanticsNode): String =
        (n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
            ?: n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ") ?: "")
            .trim().replace(Regex("\\s+"), " ")

    /** finding -> the pages it showed on, in the order found. */
    private val found = LinkedHashMap<String, LinkedHashSet<String>>()
    private val pages = ArrayList<String>()
    private val controls = LinkedHashMap<String, Int>()

    /**
     * Measure what is on screen now. where: "page" (everything not on a sheet), "sheet", or a tag whose
     * children count (a menu, the MIDI map panel).
     */
    private fun audit(width: String, page: String, where: String = "page") {
        step()
        val d = rule.density.density
        val screenW = rule.activity.window.decorView.width.toFloat()
        fun inside(tags: List<String>) = when (where) {
            "page" -> "sheet" !in tags && "padmenu" !in tags && "topmenu" !in tags
            else -> where in tags
        }
        fun add(kind: String, what: String) { found.getOrPut("$kind | $what") { LinkedHashSet() }.add(page) }
        var n = 0
        for (node in rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()) {
            val tags = tagsOf(node)
            if (!inside(tags)) continue
            val b = node.boundsInRoot
            if (b.width <= 0f || b.height <= 0f) continue
            n++
            if (tags.any { t -> surface.any { t.startsWith(it) } }) continue
            val w = node.size.width / d
            val h = node.size.height / d
            val name = labelOf(node).ifEmpty { tags.firstOrNull() ?: "?" }.take(40)
            if (w < smallDp || h < smallDp) add("small button", "$name (${w.toInt()} x ${h.toInt()} dp)")
            val right = node.positionInRoot.x + node.size.width
            if (right > screenW + 2f && b.width > 0f) add("off the right edge", "$name (by ${((right - screenW) / d).toInt()} dp)")
        }
        controls[page] = n
        for (node in rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()) {
            val tags = tagsOf(node)
            if (!inside(tags)) continue
            val b = node.boundsInRoot
            if (b.width <= 0f || b.height <= 0f) continue
            val res = ArrayList<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(res)
            val r = res.firstOrNull() ?: continue
            val txt = r.layoutInput.text.text.trim().replace(Regex("\\s+"), " ").take(48)
            if (txt.isEmpty()) continue
            if (r.hasVisualOverflow) add("cut off text", "\"$txt\"")
            val fs = r.layoutInput.style.fontSize
            if (fs.isSp && fs.value < tinySp) add("tiny text", "\"$txt\" (${"%.1f".format(fs.value)} sp)")
        }
        pages.add(page)
        shot("audit-$width-${page.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')}")
    }

    private fun go(tag: String) {
        rule.onNodeWithTag(tag).performScrollTo()
        step()
        rule.onNodeWithTag(tag).performClick()
        settle()
    }

    private fun walk(width: String) {
        // the Play tab
        audit(width, "Play pads")
        go("keysbar-open"); audit(width, "Play keys bar open"); go("keysbar-shut")
        state.toggleKeyboard(0); settle(); audit(width, "Play Padiano")
        go("piano-set-0"); audit(width, "Play Padiano settings"); go("piano-set-0")
        state.toggleKeyboard(0); settle()
        state.keysMode = true; settle(); audit(width, "Play keys mode"); state.keysMode = false; settle()
        state.showFx = true; settle(); audit(width, "Play FX"); state.showFx = false; settle()
        state.switchDockOpen(true); settle(); audit(width, "Play dock open"); state.switchDockOpen(false); settle()
        state.padMenu = 0; settle(); audit(width, "Pad menu", "padmenu"); state.padMenu = -1; step()
        state.tab = Tab.Console; settle(); audit(width, "My Desk")
        // the other tabs
        for (t in listOf(Tab.Steps, Tab.Roll, Tab.Song, Tab.Sounds)) { state.tab = t; settle(); audit(width, t.label) }
        state.tab = Tab.Synth; settle(); audit(width, "Synth none")
        go("synth-new-empty")
        for (v in listOf("simple", "full", "pad")) { state.synthView = v; settle(); audit(width, "Synth $v") }
        state.synthView = "simple"
        go("keysbar-open"); audit(width, "Synth keys bar open"); go("keysbar-shut")
        // the MIDI map over the Synth tab and the Play tab
        state.switchMidiMap(true); settle(); audit(width, "MIDI map on Synth"); audit(width, "MIDI map panel", "midimap")
        state.tab = Tab.Pads; settle(); audit(width, "MIDI map on Play")
        state.switchMidiMap(false); settle()
        // every sheet
        val sheets = listOf(
            "Messes" to Overlay.Files, "Settings" to Overlay.Settings, "Tempo" to Overlay.Tempo, "Notes" to Overlay.Notes,
            "Latency" to Overlay.AudioCheck, "Packs" to Overlay.Packs, "Learn" to Overlay.Learn, "Mouth" to Overlay.Mouth,
            "XY setup" to Overlay.Xy(0), "Sound picker" to Overlay.Picker(12),
        )
        for ((name, o) in sheets) { state.overlay = o; settle(); audit(width, "Sheet $name", "sheet"); state.overlay = null; settle() }
        state.overlay = Overlay.Doctor(state.project.kit[0]); settle()
        for (dt in listOf("play", "trim", "shape", "fx", "tools")) { go("dtab-$dt"); audit(width, "Sheet Sample $dt", "sheet") }
        state.overlay = null; settle()
        // the top menu
        rule.onNodeWithTag("menu").performClick(); settle(); audit(width, "Top menu", "topmenu")

        val out = StringBuilder("# Usability audit at $width\n\n")
        out.append("Buttons under ${smallDp.toInt()} dp in either direction (outside the playing surfaces), text that is cut off, text under ${tinySp.toInt()} sp, and controls that run off the right edge.\n\n")
        out.append("${pages.size} pages, ${found.size} findings.\n\n| Kind | What | Where |\n|---|---|---|\n")
        for ((k, where) in found.toSortedMap()) {
            val parts = k.split(" | ", limit = 2)
            val w = if (where.size > 6) "${where.size} pages, first on ${where.first()}" else where.joinToString(", ")
            out.append("| ${parts[0]} | ${parts.getOrElse(1) { "" }.replace("|", "/")} | $w |\n")
        }
        out.append("\n## Controls on screen, per page\n\n")
        for ((p, c) in controls) out.append("- $p: $c\n")
        File("build/screens").mkdirs()
        File("build/screens/usability-$width.md").writeText(out.toString())
        println(out)
        assertTrue("the audit walked the whole app (${pages.size} pages)", pages.size >= 35)
    }

    @Test fun auditPhone() = walk("w411")

    @Config(qualifiers = "w360dp-h740dp-xhdpi")
    @Test fun auditSmallPhone() = walk("w360")

    @Config(qualifiers = "w673dp-h841dp-xhdpi")
    @Test fun auditFoldOpen() = walk("w673")
}
