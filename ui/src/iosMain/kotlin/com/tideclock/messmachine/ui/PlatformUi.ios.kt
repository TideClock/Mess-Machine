package com.tideclock.messmachine.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.dateWithTimeIntervalSince1970

/*
 * The iPhone's side of PlatformUi (CMP step 6). First versions: permissions and pickers answer through the Swift host
 * (IosHooks, set by the iOS app at start); until it sets them, asking says no and picking picks nothing.
 */

/** Set by the iOS app (Swift) when it starts: the permission sheet, the document picker and the haptic engine. */
object IosHooks {
    var micGranted: () -> Boolean = { false }
    var askMic: ((Boolean) -> Unit) -> Unit = { it(false) }
    var pick: (types: List<String>, multiple: Boolean, done: (List<String>) -> Unit) -> Unit = { _, _, done -> done(emptyList()) }
    var tap: () -> Unit = {}
}

@Composable
actual fun rememberAsk(p: Perm, onResult: (Boolean) -> Unit): Asker = object : Asker {
    override val granted: Boolean get() = p == Perm.NOTIFICATIONS || IosHooks.micGranted()
    override fun ask() { if (p == Perm.MIC) IosHooks.askMic(onResult) else onResult(true) }
}

@Composable
actual fun rememberPicker(multiple: Boolean, onPick: (List<String>) -> Unit): (Array<String>) -> Unit =
    { types -> IosHooks.pick(types.toList(), multiple) { if (it.isNotEmpty()) onPick(it) } }

@Composable
actual fun rememberSpeechAsk(prompt: String, onSaid: (String) -> Unit, onNone: () -> Unit): () -> Unit = onNone

@Composable
actual fun rememberTapBuzz(): () -> Unit = { IosHooks.tap() }

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {}

@Composable
actual fun WindowEffects(lightSkin: Boolean, smoothScreen: Boolean, locked: Boolean, lockPin: Boolean) {}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun screenHeightDp(): Int = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp().value.toInt() }

actual fun Modifier.tagsAsIds(): Modifier = this

actual fun shortDate(ms: Long): String {
    val f = NSDateFormatter()
    f.dateFormat = "MMM d, h:mm a"
    f.locale = NSLocale("en_US_POSIX")
    return f.stringFromDate(NSDate.dateWithTimeIntervalSince1970(ms / 1000.0))
}
