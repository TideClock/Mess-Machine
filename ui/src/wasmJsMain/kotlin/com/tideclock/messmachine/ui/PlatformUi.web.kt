package com.tideclock.messmachine.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import com.tideclock.messmachine.platform.Web

/*
 * The browser's side of PlatformUi (docs/WEB-CMP.md). The mic and the file picker go through the page (index.html),
 * Back is the browser's Back (one history step per open handler), buzz is navigator.vibrate.
 */

@Composable
actual fun rememberAsk(p: Perm, onResult: (Boolean) -> Unit): Asker = object : Asker {
    override val granted: Boolean get() = p == Perm.NOTIFICATIONS || Web.micGranted()
    override fun ask() { if (p == Perm.MIC) Web.askMic { onResult(it) } else onResult(true) }
}

@Composable
actual fun rememberPicker(multiple: Boolean, onPick: (List<String>) -> Unit): (Array<String>) -> Unit =
    { types -> Web.pick(types.joinToString(","), multiple) { if (it.isNotEmpty()) onPick(it) } }

@Composable
actual fun rememberSpeechAsk(prompt: String, onSaid: (String) -> Unit, onNone: () -> Unit): () -> Unit = onNone

@Composable
actual fun rememberTapBuzz(): () -> Unit = { Web.buzz(12) }

/** The newest open handler gets Back; each holds one browser history step while it is enabled. */
@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    val cb = rememberUpdatedState(onBack)
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose {}
        val id = Web.backPush { cb.value() }
        onDispose { Web.backDrop(id) }
    }
}

@Composable
actual fun WindowEffects(lightSkin: Boolean, smoothScreen: Boolean, locked: Boolean, lockPin: Boolean) {}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun screenHeightDp(): Int = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp().value.toInt() }

actual fun Modifier.tagsAsIds(): Modifier = this

actual fun shortDate(ms: Long): String = Web.shortDate(ms.toDouble())
