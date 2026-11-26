package com.tideclock.messmachine.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * What the screens need from the phone itself (CMP step 6): asking for a permission, picking files, speech to text,
 * the tap buzz, Back, the window (bar icons, frame rate, screen pinning), and a date. Each has an Android version
 * (androidMain, the same calls the screens made before) and an iPhone version (iosMain).
 */
enum class Perm { MIC, NOTIFICATIONS }

/** A permission: whether it is granted now, and asking for it (the answer goes to the callback given when made). */
interface Asker {
    val granted: Boolean
    fun ask()
}

@Composable
expect fun rememberAsk(p: Perm, onResult: (Boolean) -> Unit): Asker

/** Pick files of these types (one, or several when [multiple]); the callback gets the platform's address for each. */
@Composable
expect fun rememberPicker(multiple: Boolean, onPick: (List<String>) -> Unit): (Array<String>) -> Unit

/** The phone's own speech to text: [onSaid] gets the words, [onNone] runs when the phone has no speech app. */
@Composable
expect fun rememberSpeechAsk(prompt: String, onSaid: (String) -> Unit, onNone: () -> Unit): () -> Unit

/** A short tick of the vibration motor, as a key tap. */
@Composable
expect fun rememberTapBuzz(): () -> Unit

/** Back (the gesture or key) does [onBack] while [enabled]; the newest one registered wins. */
@Composable
expect fun BackHandler(enabled: Boolean = true, onBack: () -> Unit)

/** The window: dark bar icons on a light skin, 60 frames a second unless smooth, and pinning while locked. */
@Composable
expect fun WindowEffects(lightSkin: Boolean, smoothScreen: Boolean, locked: Boolean, lockPin: Boolean)

/** The screen's height in dp. */
@Composable
expect fun screenHeightDp(): Int

/** Test tags readable from outside the app (the phone test on the emulator finds controls by them). */
expect fun Modifier.tagsAsIds(): Modifier

/** A note's time, like "Nov 26, 9:05 PM". */
expect fun shortDate(ms: Long): String
