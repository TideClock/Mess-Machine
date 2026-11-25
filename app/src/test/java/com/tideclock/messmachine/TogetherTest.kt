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
import com.tideclock.messmachine.cloud.FakeCloudB
import com.tideclock.messmachine.cloud.Ops
import com.tideclock.messmachine.cloud.SyncSession
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
 * Accounts, Slice B on the phone: two people in one mess. Two AppStates on one FakeCloudB (the Slice B server in
 * memory, from core/) are Tyler's phone and Mia's phone, each with its own files and settings. Invite, join, edit
 * together, the next-loop hand-off with the real engine, kill and reopen, remove, leave, block.
 * Screenshots go to build/screens as together-*.png, with the big test's.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class TogetherTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val fake = FakeCloudB(Clock { 1_795_000_000_000L })
    private val shownState = mutableStateOf<AppState?>(null)
    private val shown: AppState get() = shownState.value!!
    private val tyler = "tyler@test"
    private val mia = "mia@test"

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
        c1 = fake.client(tyler); c2 = fake.client(mia)
        p1 = phone("t1", c1); p2 = phone("m1", c2)
        p1.wifiOverride = true; p2.wifiOverride = true
        shownState.value = p1
        rule.setContent { val s = shownState.value; if (s != null) key(s) { MessMachineApp(s, ticker = false) } }
        settle()
        waitFor("both starter kits to load") { phones.all { p -> p.project.kit.filter { it.isNotEmpty() }.all { p.lib.loaded(it) } } }
    }

    /* ---------- helpers ---------- */

    /** Every phone that is switched on (a killed phone is taken out). */
    private val phones: MutableList<AppState> by lazy { mutableListOf(p1, p2) }

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

    /** The pretend server answers and every phone's clocks tick, until nothing more happens. */
    private fun pump() {
        for (i in 0 until 40) {
            var n = fake.deliver()
            for (p in phones) p.cloudTick()
            n += fake.deliver()
            settle()
            if (n == 0 && fake.pending() == 0) { if (i > 1) return }
        }
    }

    /** Time passes for the server and the phones. */
    private fun later(ms: Long) { fake.advance(ms); pump() }
    /** A few seconds in half-second steps: sends (400 ms), polls (2 s) and their answers all get their turn. */
    private fun both(seconds: Int = 3) { repeat(seconds * 2) { later(500) } }

    private fun show(p: AppState) { shownState.value = p; settle() }

    private fun shot(name: String) {
        settle()
        val dir = File("build/screens").also { it.mkdirs() }
        val bmp = rule.activity.window.decorView.rootView.drawToBitmap(Bitmap.Config.ARGB_8888)
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SCREEN $name ${bmp.width}x${bmp.height}")
    }

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
    private fun says(text: String) = rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun openMenuItem(tag: String) { tap("menu"); rule.onNodeWithTag(tag).performClick(); settle() }
    private fun openMesses() { shown.overlay = null; settle(); openMenuItem("messes"); pump() }
    private fun openAccount() { shown.overlay = null; settle(); openMenuItem("acct-open") }
    private fun closeSheet() { if (shown.overlay != null) tap("done") }

    /** Every account control on screen has its line in the FAQ (Faq.kt). */
    private val cloudTags = listOf("acct-", "terms-", "name-ask", "cloud-move-", "mess-cloud-", "mess-offcloud-", "omess-", "sync-", "sounds-get", "mess-save",
        "share-", "mem-", "inv-", "invite-", "link-", "block-", "here-", "hist-", "shared-", "report-", "del-", "vault-")
    private fun faqCovers(where: String) {
        val missing = LinkedHashSet<String>()
        var seen = 0
        for (n in rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()) {
            val tag = generateSequence(n) { it.parent }.mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }.firstOrNull() ?: continue
            if (cloudTags.none { tag.startsWith(it) }) continue
            seen++
            if (Faq.lineFor(tag) == null) missing.add(tag)
        }
        assertTrue("account controls on $where", seen > 0)
        assertTrue("controls with no FAQ line on $where: $missing", missing.isEmpty())
    }

    /** The steps after the code: the terms, the name and the messes question, when they come. */
    private fun afterCode(p: AppState, name: String) {
        if (exists("terms-agree")) { tap("terms-agree"); pump() }
        if (exists("name-ask")) { type("name-ask", name); tap("name-ask-ok"); pump() }
        if (exists("cloud-move-later")) { tap("cloud-move-later"); pump() }
        assertEquals("signed in", CloudStore.IN, p.store!!.state)
    }

    /** Signs a phone in by touch. */
    private fun signIn(p: AppState, email: String, name: String) {
        show(p)
        openAccount()
        type("acct-email", email)
        tap("acct-send"); pump()
        type("acct-code", fake.lastCode(email))
        tap("acct-verify"); pump()
        afterCode(p, name)
        closeSheet()
    }

    private fun saveByTouch(p: AppState): String {
        show(p)
        openMesses()
        val before = p.files.size
        val had = p.fileId
        tap("mess-save")
        waitFor("the save") { val id = p.fileId; id != null && (had != null || p.files.size > before) && p.files.any { it.id == id && it.bpm == p.project.bpm } }
        return p.fileId!!
    }

    private fun render(p: AppState, frames: Int): Float {
        val buf = FloatArray(512 * 2)
        var pk = 0f
        repeat(frames / 512) { p.engine.render(buf, 512); for (v in buf) pk = maxOf(pk, abs(v)) }
        return pk
    }
    /** One screen frame of a phone: the playhead is read, the session ticks, the next loop is armed. */
    private fun frame(p: AppState) { p.tick(System.nanoTime()); settle() }

    private fun canon(p: AppState) = Ops.canon(p.project)
    private fun keyOf(p: AppState, pad: Int) = p.project.kit[pad]
    private fun stepOn(p: AppState, pad: Int, j: Int) = p.project.pats[0].step(p.project.kit[pad], j)
    private fun setStep(p: AppState, pad: Int, j: Int, on: Boolean = true) { val k = keyOf(p, pad); p.edit { pats[0].setStep(k, j, on) } }

    private var cid = ""
    private var link = ""

    /** Tyler saves a mess online and makes an invite link by touch. */
    private fun tylerInvites(shots: Boolean = false) {
        signIn(p1, tyler, "Tyler")
        p1.setBpm(118)
        val id = saveByTouch(p1)
        later(6000)
        assertEquals("online", CloudStore.M_SYNCED, p1.store!!.mark(id))
        cid = p1.store!!.cloudId(id)
        openMesses()
        tap("omess-share-${cid.take(8)}")
        pump()
        assertTrue("the Share sheet is open", p1.overlay is Overlay.Share)
        if (shots) { shot("together-01-share-sheet"); faqCovers("the Share sheet") }
        tap("share-invite"); pump()
        link = p1.lastShared
        assertTrue("an invite link went to the phone's share sheet: $link", link.startsWith("messmachine://j/") && link.length > 30)
        assertEquals("the sheet shows the link once", link, p1.inviteLink)
        assertTrue("an unused invite is listed, with Cancel", tagsStarting("inv-revoke-").isNotEmpty())
        if (shots) { shot("together-02-invite-made"); faqCovers("the Share sheet with an invite") }
        closeSheet()
    }

    /** Mia opens the link signed out, signs in, and joins by touch. */
    private fun miaJoins(shots: Boolean = false) {
        show(p2)
        assertTrue("the app knows the link", p2.cloudLink(link))
        pump()
        assertEquals(Overlay.Account, p2.overlay)
        assertTrue("the invite names the mess and who made it", says("JOIN") && says("Tyler invited you"))
        assertTrue("signed out, there is no Join yet", !exists("invite-join"))
        if (shots) shot("together-03-invite-signed-out")
        type("acct-email", mia)
        tap("acct-send"); pump()
        type("acct-code", fake.lastCode(mia))
        tap("acct-verify"); pump()
        // the invite survives signing in and the terms; the name is asked before joining
        if (exists("terms-agree")) { tap("terms-agree"); pump() }
        assertTrue("the invite is still waiting", p2.pendingInvite.isNotEmpty())
        if (exists("name-ask")) { type("name-ask", "Mia"); tap("name-ask-ok"); pump() }
        rule.onNodeWithTag("invite-join").assertExists()
        if (shots) { shot("together-04-join-question"); faqCovers("the Join question") }
        tap("invite-join")
        waitFor("Mia's phone opens the mess together") { p2.fileId != null && p2.sync != null && p2.sync!!.state == SyncSession.LIVE }
        assertEquals("the invite is used up", "", p2.pendingInvite)
        assertEquals("two people are on it", 2, fake.memberCount(cid))
        // Tyler's phone hears of her at its next look (a mess with one person asks every 30 s)
        later(31000); both(2)
        waitFor("Tyler's phone is live too") { p1.sync != null && p1.sync!!.state == SyncSession.LIVE }
        assertEquals("both phones show the same mess", canon(p1), canon(p2))
        assertEquals(118, p2.project.bpm)
    }

    private fun together() { tylerInvites(); miaJoins() }

    /* ---------- the flows ---------- */

    @Test fun togetherInviteJoinAndBothEdit() {
        tylerInvites(shots = true)
        miaJoins(shots = true)
        // different pads: each sees the other's edit
        setStep(p1, 0, 3)
        setStep(p2, 1, 6)
        both(4)
        assertTrue("Mia sees Tyler's step", stepOn(p2, 0, 3))
        assertTrue("Tyler sees Mia's step", stepOn(p1, 1, 6))
        assertTrue("and each keeps their own", stepOn(p1, 0, 3) && stepOn(p2, 1, 6))
        // the same field: the last writer wins on both phones
        p1.setBpm(96)
        both(1)
        p2.setBpm(141)
        both(4)
        assertEquals(141, p1.project.bpm)
        assertEquals(141, p2.project.bpm)
        assertEquals("the same mess on both phones", canon(p1), canon(p2))
        // the Messes sheet says it is edited together, and the people are listed on both phones
        show(p2); openMesses()
        assertTrue(says("Online, edited together"))
        rule.onNodeWithTag("sync-state", useUnmergedTree = true).assertExists()
        shot("together-05-mia-messes")
        faqCovers("Mia's Messes sheet")
        tap("omess-share-${cid.take(8)}"); pump()
        assertEquals(2, p2.shareMembers.size)
        assertTrue("Mia sees Leave, not Invite", exists("mem-leave") && !exists("share-invite"))
        shot("together-06-mia-share-sheet")
        faqCovers("Mia's Share sheet")
        closeSheet()
        // nothing about the other person sits on the play screen
        show(p1); p1.overlay = null; settle()
        for (t in listOf("mem-", "share-", "here-", "sync-", "invite-")) assertTrue("no $t control on the play screen", tagsStarting(t).isEmpty())
    }

    @Test fun togetherHeldEditComesInAtTheNextLoop() {
        together()
        show(p2); p2.overlay = null; settle()
        // Mia plays: Tyler's edit waits for the top of her loop
        p2.togglePlay(); render(p2, 2048); frame(p2)
        assertTrue("Mia's phone is playing", p2.playing)
        setStep(p1, 0, 5)
        both(3)
        assertTrue("it reached Mia's phone and is held", p2.sync!!.held() > 0)
        assertTrue("it is not on her screen yet", !stepOn(p2, 0, 5))
        frame(p2)                                   // arm(): the mess with the edit in is set to go in at the top
        assertTrue("still not on screen before the loop comes round", !stepOn(p2, 0, 5))
        val token = p2.engine.swapToken
        // one loop of 16 steps at 118 is about 2 s; three seconds of audio pass the top once
        render(p2, 48000 * 3)
        assertTrue("the engine took the armed mess at the top of the loop", p2.engine.swapToken != token)
        frame(p2)
        assertTrue("now it is on her screen", stepOn(p2, 0, 5))
        assertEquals(0, p2.sync!!.held())
        // stopped: the next edit comes in at once
        p2.togglePlay(); render(p2, 2048); frame(p2)
        assertTrue(!p2.playing)
        setStep(p1, 0, 9)
        both(3)
        assertTrue("stopped, it is there with no loop to wait for", stepOn(p2, 0, 9))
        assertEquals(canon(p1), canon(p2))
    }

    @Test fun togetherUndoTakesBackOnlyMine() {
        together()
        setStep(p1, 0, 2)
        both(2)
        setStep(p2, 1, 7)
        both(3)
        assertTrue(p1.canUndo && !p1.canRedo)
        p1.undo()
        both(3)
        assertTrue("Tyler's step is gone on both", !stepOn(p1, 0, 2) && !stepOn(p2, 0, 2))
        assertTrue("Mia's step stays on both", stepOn(p1, 1, 7) && stepOn(p2, 1, 7))
        assertEquals(canon(p1), canon(p2))
    }

    @Test fun togetherKilledAndReopenedLosesNothing() {
        together()
        // Mia goes offline, edits, and her app is killed before anything went up
        fake.setOnline(c2, false)
        setStep(p2, 2, 4)
        p2.setBpm(133)
        both(2)
        assertTrue("her changes wait", p2.sync!!.pending() > 0)
        val want = canon(p2)
        val ctx = ctxFor("m1")
        p2.saveNow()
        waitFor("her working mess to reach the disk") {
            try { Ops.canon(Project.fromJson(File(ctx.filesDir, "current.json").readText(), p2.lib::has).project) == want } catch (_: Exception) { false }
        }
        phones.remove(p2)                          // killed: no close, no goodbye
        val again = AppState(ctx, DiskPhone(fake, c2, ctx.filesDir), fake.time)
        again.wifiOverride = true
        phones.add(again)
        waitFor("the reopened phone to load its sounds") { again.project.kit.filter { it.isNotEmpty() }.all { again.lib.loaded(it) } }
        assertEquals("it comes back with her mess", want, canon(again))
        fake.setOnline(c2, true)
        later(11000); both(4)
        waitFor("the reopened phone to be live") { again.sync != null && again.sync!!.state == SyncSession.LIVE && again.sync!!.pending() == 0 }
        both(3)
        assertTrue("Tyler got the step she made offline", stepOn(p1, 2, 4))
        assertEquals(133, p1.project.bpm)
        assertEquals("nothing was lost and nothing came back twice", canon(p1), canon(again))
    }

    @Test fun togetherRemovedMemberKeepsACopy() {
        together()
        setStep(p2, 1, 10)
        both(3)
        val miaLocal = p2.fileId!!
        // Tyler removes Mia, by touch, with the block
        show(p1); openMesses()
        tap("omess-share-${cid.take(8)}"); pump()
        assertEquals(2, p1.shareMembers.size)
        val rm = tagsStarting("mem-remove-").first()
        tap(rm)
        rule.onNodeWithTag("mem-remove-go").assertExists()
        rule.onNodeWithTag("mem-remove-block").assertExists()
        shot("together-07-remove-question")
        faqCovers("the Remove question")
        tap("mem-remove-block"); pump()
        assertTrue("Mia is off the mess", !fake.isMember(cid, mia))
        assertEquals(1, p1.shareMembers.size)
        // Mia's phone finds out at its next look, and keeps the mess as her own copy
        both(4)
        waitFor("Mia's phone to let go") { p2.sync == null }
        assertTrue("she was told: ${p2.cloudNotice}", p2.cloudNotice.contains("stays on this phone"))
        both(2)
        assertEquals("her copy is on her phone only", CloudStore.M_PHONE, p2.store!!.mark(miaLocal))
        assertTrue("with her step in it", stepOn(p2, 1, 10))
        assertNotNull(p2.files.firstOrNull { it.id == miaLocal })
        // blocked: a fresh invite from Tyler does not let her in
        show(p1); p1.shareLoad(cid); pump()
        tap("share-invite"); pump()
        val link2 = p1.lastShared
        assertTrue(link2 != link)
        show(p2)
        p2.cloudLink(link2); pump()
        tap("invite-join"); pump()
        assertTrue("she cannot join: ${p2.inviteNote}", p2.inviteNote.contains("cannot join"))
        assertTrue(!fake.isMember(cid, mia))
        tap("invite-no")
        closeSheet()
        // Tyler unblocks her on the Account page; then the link works
        show(p1); openAccount()
        tap("acct-blocks"); pump()
        assertEquals(1, p1.blocks!!.size)
        shot("together-08-blocked-people")
        faqCovers("the Account page with blocked people")
        tap(tagsStarting("block-undo-").first()); pump()
        assertEquals(0, p1.blocks!!.size)
        closeSheet()
        show(p2)
        p2.cloudLink(link2); pump()
        tap("invite-join")
        waitFor("Mia is back on the mess") { fake.isMember(cid, mia) && p2.sync != null }
    }

    @Test fun togetherLeaveKeepsMyCopy() {
        together()
        val miaLocal = p2.fileId!!
        show(p2); openMesses()
        tap("omess-share-${cid.take(8)}"); pump()
        tap("mem-leave")
        assertTrue("it asks once more", fake.isMember(cid, mia))
        tap("mem-leave"); pump()
        both(2)
        assertTrue("Mia left", !fake.isMember(cid, mia))
        assertNull(p2.sync)
        assertNotNull("her copy stays on her phone", p2.files.firstOrNull { it.id == miaLocal })
        assertEquals(CloudStore.M_PHONE, p2.store!!.mark(miaLocal))
        // her later edits stay hers; Tyler's mess goes on without her
        setStep(p2, 0, 12)
        setStep(p1, 0, 14)
        later(31000); both(3)
        assertTrue(!stepOn(p1, 0, 12) && !stepOn(p2, 0, 14))
        assertEquals(1, fake.memberCount(cid))
    }

    @Test fun togetherPasteALinkAndASoundOfMine() {
        tylerInvites()
        // Mia signs in first, then pastes the link she copied
        signIn(p2, mia, "Mia")
        val clip = ApplicationProvider.getApplicationContext<Context>().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clip.setPrimaryClip(android.content.ClipData.newPlainText("link", "Join my mess: $link"))
        show(p2); openAccount()
        tap("link-paste"); pump()
        rule.onNodeWithTag("invite-join").assertExists()
        tap("invite-join")
        waitFor("Mia's phone opens the mess together") { p2.fileId != null && p2.sync != null && p2.sync!!.state == SyncSession.LIVE }
        later(31000); both(2)
        // Tyler puts a take of his own on a pad: it goes up now, and Mia's phone fetches it
        val wave = FloatArray(22050) { i -> (kotlin.math.sin(i * 2 * Math.PI * 220 / 44100) * .8).toFloat() }
        val k = p1.bench.addSample("mic", "My take", Sample("mic", wave, 44100))
        p1.putOnPad(9, k)
        both(4)
        assertEquals("the pad is on Mia's screen", k, p2.project.kit[9])
        later(6000); both(4)
        waitFor("the take to reach Mia's phone") { p2.lib.loaded(k) }
        assertEquals("it went up once", 1, fake.uploads(c1))
        assertEquals(canon(p1), canon(p2))
    }
}
