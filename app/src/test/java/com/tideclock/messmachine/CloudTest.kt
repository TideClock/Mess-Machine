package com.tideclock.messmachine

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
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
import com.tideclock.messmachine.cloud.CloudApi
import com.tideclock.messmachine.cloud.CloudStore
import com.tideclock.messmachine.cloud.Clock
import com.tideclock.messmachine.cloud.Done
import com.tideclock.messmachine.cloud.FakeCloud
import com.tideclock.messmachine.cloud.Prepared
import com.tideclock.messmachine.engine.Project
import com.tideclock.messmachine.engine.Sample
import com.tideclock.messmachine.engine.Wav
import com.tideclock.messmachine.ui.MessMachineApp
import com.tideclock.messmachine.ui.UiFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import kotlin.math.abs

/**
 * Accounts, Slice A on the phone: the real app, driven by touch, against FakeCloud (the whole server in memory, from
 * core/). Two AppStates on one FakeCloud are two phones of one person, each with its own files and settings.
 * Screenshots go to build/screens as cloud-*.png, with the big test's.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class CloudTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val fake = FakeCloud(Clock { 1_795_000_000_000L })
    private val shownState = mutableStateOf<AppState?>(null)
    private val shown: AppState get() = shownState.value!!
    private val email = "tyler@test"

    /** One phone: its own files and its own settings, on the test's one Android. */
    private class Phone(base: Context, private val name: String) : ContextWrapper(base) {
        private val root = File(base.filesDir.parentFile, "phone-$name")
        private val files = File(root, "files").also { it.mkdirs() }
        private val cache = File(root, "cache").also { it.mkdirs() }
        override fun getFilesDir(): File = files
        override fun getCacheDir(): File = cache
        override fun getSharedPreferences(n: String?, mode: Int): SharedPreferences = super.getSharedPreferences("$name-$n", mode)
        override fun getApplicationContext(): Context = this
    }

    /**
     * A FakeCloud phone whose sound files are real files: an upload reads the Workbench WAV, a download lands in
     * files/cloud/sounds/, so the app's own file code is what runs.
     */
    private class DiskPhone(private val fake: FakeCloud, val c: CloudApi, private val dir: File) : CloudApi by c {
        override fun prepareUpload(key: String, cb: Done<Prepared>) {
            val f = File(dir, "bench/" + key.removePrefix("bn:") + ".wav")
            if (key.startsWith("bn:") && f.isFile) {
                val bytes = f.readBytes()
                val w = Wav.read(key, bytes)
                fake.putLocalSound(c, key, bytes, w.data.size.toDouble() / w.rate, w.rate)
            }
            c.prepareUpload(key, cb)
        }
        override fun cachedSound(sha256: String, cb: Done<String>) { c.cachedSound(sha256) { p, e -> if (p == null) cb.done(null, e) else cb.done(real(p, sha256), null) } }
        override fun fetchSound(url: String, sha256: String, bytes: Long, cb: Done<String>) {
            c.fetchSound(url, sha256, bytes) { p, e -> if (p == null) cb.done(null, e) else cb.done(real(p, sha256), null) }
        }
        private fun real(p: String, sha: String): String {
            val b = fake.deviceFile(c, p) ?: return p
            val out = File(dir, "cloud/sounds/$sha.wav")
            out.parentFile!!.mkdirs(); out.writeBytes(b)
            return out.absolutePath
        }
    }

    private lateinit var c1: CloudApi
    private lateinit var c2: CloudApi
    private lateinit var p1: AppState
    private lateinit var p2: AppState

    /** A phone's own Android: the start guide already seen, the click off (as the big test's flows). */
    private fun ctxFor(name: String): Context {
        val ctx = Phone(ApplicationProvider.getApplicationContext(), name)
        ctx.getSharedPreferences("mess", 0).edit().putInt("guideSeen", BuildConfig.VERSION_CODE).putBoolean("click", false).putInt("countIn", 0).commit()
        return ctx
    }

    private fun phone(name: String, c: CloudApi?): AppState {
        val ctx = ctxFor(name)
        val api = if (c == null) null else DiskPhone(fake, c, ctx.filesDir)
        return AppState(ctx, api, fake.time)
    }

    @Before fun setUp() {
        UiFlags.testing = true
        c1 = fake.client(email); c2 = fake.client(email)
        p1 = phone("one", c1); p2 = phone("two", c2)
        // phone 1 is on Wi-Fi, phone 2 on mobile data (so its sounds wait for "Get sounds now")
        p1.wifiOverride = true; p2.wifiOverride = false
        shownState.value = p1
        rule.setContent { val s = shownState.value; if (s != null) key(s) { MessMachineApp(s, ticker = false) } }
        settle()
        waitFor("both starter kits to load") { listOf(p1, p2).all { p -> p.project.kit.filter { it.isNotEmpty() }.all { p.lib.loaded(it) } } }
    }

    /* ---------- helpers ---------- */

    private fun settle() { repeat(3) { ShadowLooper.idleMainLooper(); rule.waitForIdle() } }

    private fun waitFor(what: String, ms: Long = 15000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!ok()) {
            if (System.currentTimeMillis() > end) throw AssertionError("Timed out waiting for $what")
            Thread.sleep(20)
            pump()
        }
        settle()
    }

    /** The pretend server answers, both phones' clocks tick, until nothing more happens. */
    private fun pump() {
        for (i in 0 until 40) {
            var n = fake.deliver()
            p1.cloudTick(); p2.cloudTick()
            n += fake.deliver()
            settle()
            if (n == 0 && fake.pending() == 0) { if (i > 1) return }
        }
    }

    /** Time passes for the server and both phones (the store pushes 5 s after a save). */
    private fun later(ms: Long) { fake.advance(ms); pump() }

    private fun show(p: AppState) { shownState.value = p; settle() }

    private fun shot(name: String) {
        settle()
        val dir = File("build/screens").also { it.mkdirs() }
        val bmp = rule.activity.window.decorView.rootView.drawToBitmap(Bitmap.Config.ARGB_8888)
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SCREEN $name ${bmp.width}x${bmp.height}")
    }

    /** A finger on a control: the toast out of the way, scrolled into view, then a tap. */
    private fun tap(tag: String) {
        shown.toast = null; settle()
        try { rule.onNodeWithTag(tag).performScrollTo() } catch (_: AssertionError) {}
        rule.mainClock.advanceTimeBy(600); settle()
        rule.onNodeWithTag(tag).performClick()
        settle()
    }

    private fun type(tag: String, text: String) {
        try { rule.onNodeWithTag(tag).performScrollTo() } catch (_: AssertionError) {}
        rule.onNodeWithTag(tag).performTextClearance()
        rule.onNodeWithTag(tag).performTextInput(text)
        settle()
    }

    private fun exists(tag: String): Boolean = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun tagsStarting(prefix: String): List<String> = rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("tag starts $prefix") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }, useUnmergedTree = true).fetchSemanticsNodes().mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }

    private fun openMenuItem(tag: String) { tap("menu"); rule.onNodeWithTag(tag).performClick(); settle() }
    private fun openMesses() { shown.overlay = null; settle(); openMenuItem("messes"); pump() }
    private fun openAccount() { shown.overlay = null; settle(); openMenuItem("acct-open") }
    private fun closeSheet() { if (shown.overlay != null) tap("done") }

    /** Every control on screen has its line in the FAQ (Faq.kt), as the big test asks of every page. */
    private fun faqCovers(where: String) {
        val missing = LinkedHashSet<String>()
        for (n in rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()) {
            val tag = generateSequence(n) { it.parent }.mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }.firstOrNull() ?: continue
            if (Faq.lineFor(tag) == null) missing.add(tag)
        }
        assertTrue("controls with no FAQ line on $where: $missing", missing.isEmpty())
    }

    /** Signs a phone in by touch: email, code, then the terms, the name and the messes question when they come. */
    private fun signIn(p: AppState, name: String = "Tyler") {
        show(p)
        openAccount()
        type("acct-email", email)
        tap("acct-send"); pump()
        type("acct-code", fake.lastCode(email))
        tap("acct-verify"); pump()
        if (exists("terms-agree")) { tap("terms-agree"); pump() }
        if (exists("name-ask")) { type("name-ask", name); tap("name-ask-ok"); pump() }
        if (exists("cloud-move-later")) { tap("cloud-move-later"); pump() }
        assertEquals("signed in", CloudStore.IN, p.store!!.state)
        closeSheet()
    }

    /** Saves the open mess by touch, on the Messes sheet, and answers its local id. */
    private fun saveByTouch(p: AppState): String {
        show(p)
        openMesses()
        val before = p.files.size
        val had = p.fileId
        tap("mess-save")
        // the file is written off the main thread; the list is read again after it, and saved() goes with that
        waitFor("the save") { val id = p.fileId; id != null && (had != null || p.files.size > before) && p.files.any { it.id == id && it.bpm == p.project.bpm } }
        return p.fileId!!
    }

    private fun loudest(p: AppState, frames: Int = 8192): Float {
        val buf = FloatArray(512 * 2)
        var pk = 0f
        repeat(frames / 512) { p.engine.render(buf, 512); for (v in buf) pk = maxOf(pk, abs(v)) }
        return pk
    }

    private fun markShows(id: String, word: String) =
        rule.onNode(hasTestTag("mess-cloud-$id") and hasAnyDescendant(hasText(word)), useUnmergedTree = true).assertExists()

    /* ---------- the flows ---------- */

    @Test fun cloudSignInWithCodeTermsAndName() {
        show(p1)
        openMesses()
        rule.onNodeWithTag("acct-open").assertExists()
        rule.onNodeWithText("Sign in to save online").assertExists()
        shot("cloud-01-messes-signed-out")
        faqCovers("the Messes sheet, signed out")
        tap("acct-open")
        assertEquals(Overlay.Account, p1.overlay)
        type("acct-email", email)
        tap("acct-send"); pump()
        rule.onNodeWithTag("acct-code").assertExists()
        // a wrong code says so plainly
        type("acct-code", "000000")
        tap("acct-verify"); pump()
        rule.onNodeWithText("That code is wrong or too old. Check it, or send a new code.").assertExists()
        shot("cloud-02-wrong-code")
        // a new code wakes up after a minute; asked for too soon, the server says wait
        rule.mainClock.advanceTimeBy(61_000); settle()
        tap("acct-resend"); pump()
        rule.onNodeWithText("Too many tries. Wait a minute, then try again.").assertExists()
        fake.advance(61_000)
        tap("acct-resend"); pump()
        type("acct-code", fake.lastCode(email))
        tap("acct-verify"); pump()
        // a new account: the terms first, and nothing goes up before I agree
        assertEquals(CloudStore.TERMS, p1.store!!.state)
        rule.onNodeWithTag("terms-text").assertExists()
        shot("cloud-03-terms")
        faqCovers("the terms")
        tap("terms-agree"); pump()
        assertEquals(CloudStore.IN, p1.store!!.state)
        // the name, asked once
        rule.onNodeWithTag("name-ask").assertExists()
        shot("cloud-04-name")
        type("name-ask", "Tyler")
        tap("name-ask-ok"); pump()
        assertEquals("Tyler", p1.store!!.me!!.display_name)
        assertTrue(p1.store!!.me!!.name_set)
        // the Account page: who, mobile data, sign out
        rule.onNodeWithTag("acct-signout").assertExists()
        assertNull("usage is cut from Slice A (the rulings)", if (exists("acct-usage")) "acct-usage" else null)
        assertTrue(!p1.cloudData)
        tap("acct-data")
        assertTrue("Use mobile data is on", p1.cloudData)
        shot("cloud-05-account")
        faqCovers("the Account page")
        // the Messes sheet now shows the account by name
        closeSheet(); openMesses()
        rule.onNodeWithText("Tyler").assertExists()
        // sign out: back to "Sign in", the phone's messes stay
        openAccount()
        tap("acct-signout"); pump()
        assertEquals(CloudStore.OUT, p1.store!!.state)
        rule.onNodeWithTag("acct-email").assertExists()
    }

    @Test fun cloudSaveOnlineThenOpenOnPhone2() {
        signIn(p1)
        p1.setBpm(127)
        val id = saveByTouch(p1)
        assertEquals("waiting to go online", CloudStore.M_WAITING, p1.store!!.mark(id))
        later(6000)
        assertEquals("online", CloudStore.M_SYNCED, p1.store!!.mark(id))
        markShows(id, "Online")
        rule.onNodeWithTag("sync-state", useUnmergedTree = true).assertExists()
        shot("cloud-06-saved-online")
        faqCovers("the Messes sheet, signed in")
        val cid = p1.store!!.cloudId(id)
        assertTrue("the mess has a cloud id", cid.isNotEmpty())
        assertEquals("the server holds the mess as saved", p1.project.toJson(false), Project.fromJson(fake.messDoc(cid), p1.lib::has).project.toJson(false))

        // phone 2, same account: the mess is in its list as online, not on this phone, and opens identical
        signIn(p2)
        show(p2)
        openMesses()
        rule.onNodeWithTag("omess-${cid.take(8)}").assertExists()
        shot("cloud-07-phone2-online-list")
        faqCovers("phone 2's Messes sheet")
        tap("omess-${cid.take(8)}")
        waitFor("phone 2 opens it") { p2.fileId != null && p2.project.bpm == 127 }
        assertEquals("the same mess on both phones", p1.project.toJson(false), p2.project.toJson(false))
        assertEquals(id.let { p1.store!!.cloudId(it) }, p2.store!!.cloudId(p2.fileId!!))
        shot("cloud-08-phone2-opened")
    }

    @Test fun cloudUserSoundMadeOnPhone1PlaysOnPhone2() {
        signIn(p1)
        // a mic take of my own on pad 1
        val wave = FloatArray(22050) { i -> (kotlin.math.sin(i * 2 * Math.PI * 220 / 44100) * .8).toFloat() }
        val key = p1.bench.addSample("mic", "My take", Sample("mic", wave, 44100))
        p1.putOnPad(0, key)
        val id = saveByTouch(p1)
        later(6000)
        assertEquals(CloudStore.M_SYNCED, p1.store!!.mark(id))
        assertEquals("the WAV went up once", 1, fake.uploads(c1))
        val cid = p1.store!!.cloudId(id)

        // phone 2 is on mobile data: the mess comes, its sound waits for "Get 1 sound now"
        signIn(p2)
        show(p2); openMesses()
        tap("omess-${cid.take(8)}")
        waitFor("phone 2 opens it") { p2.fileId != null }
        val local = p2.fileId!!
        assertEquals("one sound to come", 1, p2.store!!.missing(local))
        assertTrue("pad 1 reads Missing for now", !p2.lib.has(key))
        openMesses()
        rule.onNodeWithTag("sounds-get").assertExists()
        rule.onNodeWithText("GET 1 SOUND NOW", substring = true).assertExists()
        shot("cloud-09-get-sounds")
        tap("sounds-get")
        waitFor("the sound to arrive and load") { p2.lib.loaded(key) }
        assertEquals(0, p2.store!!.missing(local))
        assertEquals("it downloaded once", 1, fake.downloads(c2))
        // it is in phone 2's Workbench, the same file
        val f1 = File(p1Ctx().filesDir, "bench/${key.removePrefix("bn:")}.wav")
        val f2 = File(p2Ctx().filesDir, "bench/${key.removePrefix("bn:")}.wav")
        assertTrue("phone 2's Workbench has it", f2.isFile)
        assertTrue("byte for byte", f1.readBytes().contentEquals(f2.readBytes()))
        assertNotNull(p2.bench.item(key))
        // and pad 1 plays it
        closeSheet()
        loudest(p2)
        rule.onNodeWithTag("pad-0").performTouchInput { down(center) }
        settle()
        val hit = loudest(p2)
        rule.onNodeWithTag("pad-0").performTouchInput { up() }
        settle()
        assertTrue("pad 1 on phone 2 plays the take from phone 1 (peak $hit)", hit > .02f)
        shot("cloud-10-phone2-plays-it")
    }

    private fun p1Ctx() = Phone(ApplicationProvider.getApplicationContext(), "one")
    private fun p2Ctx() = Phone(ApplicationProvider.getApplicationContext(), "two")

    @Test fun cloudClashKeepsBoth() {
        signIn(p1)
        val id1 = saveByTouch(p1)
        later(6000)
        val cid = p1.store!!.cloudId(id1)
        val name = p1.files.first { it.id == id1 }.name
        signIn(p2)
        show(p2); openMesses()
        tap("omess-${cid.take(8)}")
        waitFor("phone 2 opens it") { p2.fileId != null }
        val id2 = p2.fileId!!
        // phone 2 looks at its list now, then both change the mess before either hears of the other
        openMesses()
        p1.setBpm(131)
        saveByTouch(p1)
        show(p2)
        p2.setBpm(93)
        tap("mess-save")
        waitFor("phone 2's save") { p2.files.any { it.id == id2 && it.bpm == 93 } }
        later(6000)
        waitFor("phone 2 keeps both") { p2.files.size == 2 }
        val copy = p2.files.first { it.id != id2 }
        assertEquals("$name (this phone)", copy.name)
        assertEquals("the copy is what phone 2 made", 93, copy.bpm)
        assertEquals("the original now has phone 1's change", 131, p2.files.first { it.id == id2 }.bpm)
        assertEquals("the screen follows the copy", copy.id, p2.fileId)
        assertEquals(93, p2.project.bpm)
        assertTrue("phone 2 said so: ${p2.cloudNotice}", p2.cloudNotice.contains("Yours is kept as $name (this phone)"))
        later(6000)
        assertEquals("the copy went online as its own mess", 2, fake.messCount(email))
        assertEquals(CloudStore.M_SYNCED, p2.store!!.mark(copy.id))
        openMesses()
        shot("cloud-11-kept-both")
    }

    @Test fun cloudOfflineThenBack() {
        signIn(p1)
        fake.setOnline(c1, false)
        val id = saveByTouch(p1)
        later(6000)
        assertEquals("offline", CloudStore.M_OFFLINE, p1.store!!.mark(id))
        markShows(id, "Offline")
        shot("cloud-12-offline")
        assertEquals(0, fake.messCount(email))
        // back online: it goes up by itself on the next try
        fake.setOnline(c1, true)
        later(2000); later(5000); later(10000)
        assertEquals("online again", CloudStore.M_SYNCED, p1.store!!.mark(id))
        assertEquals(1, fake.messCount(email))
        markShows(id, "Online")
        shot("cloud-13-back-online")
    }

    @Test fun noServerMeansNoAccountUi() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        // a build with no server, and a server object with no URL: both give no cloud at all
        val plain = AppState(ctxFor("plain"))
        val blank = AppState(ctxFor("blank"), SupabaseApi(base, "", ""))
        assertNull(plain.cloud); assertNull(plain.store)
        assertNull(blank.cloud); assertNull(blank.store)
        show(blank)
        tap("menu")
        assertTrue("no Account in the menu", !exists("acct-open"))
        rule.onNodeWithTag("messes").performClick(); settle()
        tap("mess-save")
        waitFor("the save") { blank.fileId != null && blank.files.isNotEmpty() }
        for (p in listOf("acct-", "mess-cloud-", "mess-offcloud-", "omess-", "sync-state", "sounds-get", "terms-", "cloud-move-"))
            assertTrue("no $p control without a server", tagsStarting(p).isEmpty())
        shot("cloud-14-no-server")
        plain.cloudTick(); blank.cloudTick(); blank.cloudBackground()
    }
}
