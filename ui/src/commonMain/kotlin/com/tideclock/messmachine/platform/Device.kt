package com.tideclock.messmachine.platform

/**
 * The phone itself, for AppState (CMP step 4, the Device interface): assets, the speaker's native rate, the clock,
 * background play, the share sheet, the mic permission, the screen size, the network and the clipboard. Android's
 * version is AndroidDevice; iOS writes its own in Swift or Kotlin.
 */
interface Device {
    /** a file bundled with the app (sounds/..., guide.json) */
    fun readAsset(path: String): ByteArray
    /** the speaker's own rate and burst, or null when the phone does not say */
    val outputRate: Int?
    val outputBurst: Int?
    /** milliseconds since boot, on the same clock touch events use */
    fun uptimeMillis(): Long
    /** the wall clock in milliseconds (Java's currentTimeMillis) */
    fun nowMillis(): Long
    /** a nanosecond clock for timing, the same one the sound thread uses */
    fun nanoTime(): Long
    /** true under the big test (Robolectric), where randomness is seeded */
    val isTestRun: Boolean
    /** start or stop playing with the screen off and a Stop control; false when the phone refused */
    fun backgroundPlay(on: Boolean): Boolean
    /** the phone's share sheet with a file; [subject] also names it for mail */
    fun shareFile(f: MFile, mime: String, title: String, subject: Boolean)
    /** the share sheet with a line of text; throws when nothing on the phone takes it */
    fun shareText(text: String, title: String)
    fun hasMicPermission(): Boolean
    /** the screen in dp, width then height */
    fun screenDp(): Pair<Int, Int>
    /** on Wi-Fi, wired or another network that does not count data; throws when the phone will not say */
    fun unmeteredNetwork(): Boolean
    /** the text on the clipboard, or "" */
    fun clipboardText(): String
    /** the bytes of a file the person picked (the platform's address for it), or null */
    fun readUri(uri: String): ByteArray?
    /** a picked file's own name, or "" */
    fun displayName(uri: String): String
    /** this build's number, its name ("0.1.204"), its commit, and the web host for links */
    val buildCode: Int
    val buildVersion: String
    val gitSha: String
    val siteHost: String
    /** the phone in a line for the notes ("samsung SM-F956U, Android 17 (API 37)") */
    fun describe(): String
    /** the speaker check: play [click] and hear it through the mic; the time in ms and a line about it */
    fun speakerTest(engine: com.tideclock.messmachine.engine.Engine, rate: Int, click: com.tideclock.messmachine.engine.Sample): Pair<Double, String>
    /** write a zip of named files (the stems) */
    fun writeZip(f: MFile, entries: List<Pair<String, ByteArray>>)
    /** run [f] on a thread of its own */
    fun thread(name: String, f: () -> Unit)
    /** a second worker at low priority, so its jobs (baking) never hold up sound or loading */
    fun lowWorker(name: String): Worker
    /** where the account keeps its sign-in, and its cached lists */
    fun cloudTokens(): com.tideclock.messmachine.cloud.TokenStore
    fun cloudCache(): com.tideclock.messmachine.cloud.CloudCache
}
