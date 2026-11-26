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
}
