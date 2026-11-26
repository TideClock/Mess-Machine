package com.tideclock.messmachine.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
actual fun rememberAsk(p: Perm, onResult: (Boolean) -> Unit): Asker {
    val ctx = LocalContext.current
    val name = if (p == Perm.MIC) Manifest.permission.RECORD_AUDIO else "android.permission.POST_NOTIFICATIONS"
    val l = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onResult(it) }
    return object : Asker {
        // Android 12 and below show notifications without asking
        override val granted: Boolean get() = (p == Perm.NOTIFICATIONS && android.os.Build.VERSION.SDK_INT < 33) ||
            ContextCompat.checkSelfPermission(ctx, name) == PackageManager.PERMISSION_GRANTED
        override fun ask() = l.launch(name)
    }
}

@Composable
actual fun rememberPicker(multiple: Boolean, onPick: (List<String>) -> Unit): (Array<String>) -> Unit {
    if (multiple) {
        val l = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> onPick(uris.map { it.toString() }) }
        return { types -> l.launch(types) }
    }
    val l = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) onPick(listOf(uri.toString())) }
    return { types -> l.launch(types) }
}

@Composable
actual fun rememberSpeechAsk(prompt: String, onSaid: (String) -> Unit, onNone: () -> Unit): () -> Unit {
    val l = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!said.isNullOrBlank()) onSaid(said)
        }
    }
    return {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        try { l.launch(i) } catch (e: ActivityNotFoundException) { onNone() }
    }
}

@Composable
actual fun rememberTapBuzz(): () -> Unit {
    val view = LocalView.current
    return { view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP) }
}

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) = androidx.activity.compose.BackHandler(enabled, onBack)

@Composable
actual fun WindowEffects(lightSkin: Boolean, smoothScreen: Boolean, locked: Boolean, lockPin: Boolean) {
    // build 8 of the overhaul: on a light skin the phone's status and navigation bars switch to dark icons
    val rootView = LocalView.current
    SideEffect {
        val w = (rootView.context as? Activity)?.window
        if (w != null) androidx.core.view.WindowCompat.getInsetsController(w, rootView).apply {
            isAppearanceLightStatusBars = lightSkin; isAppearanceLightNavigationBars = lightSkin
        }
    }
    // Nov 21 (lag): 60 frames a second unless Smooth screen is on (Settings, Playing)
    LaunchedEffect(smoothScreen) {
        val act = rootView.context as? Activity
        if (act != null) try { setScreenRate(act, smoothScreen) } catch (_: Throwable) {}
    }
    // Lock: the screen is pinned if you chose that
    val act = LocalContext.current as? Activity
    var pinned by remember { mutableStateOf(false) }
    LaunchedEffect(locked) {
        try {
            if (locked && lockPin && !pinned) { act?.startLockTask(); pinned = true }
            else if (!locked && pinned) { act?.stopLockTask(); pinned = false }
        } catch (_: Exception) { pinned = false }
    }
}

/** Ask for 60 frames a second (or the fastest the screen has) at the screen's own size. A hint: the phone may still choose. */
fun setScreenRate(act: Activity, smooth: Boolean) {
    val w = act.window
    val lp = w.attributes
    if (android.os.Build.VERSION.SDK_INT >= 30) {
        val d = act.display
        val cur = d?.mode
        val same = d?.supportedModes?.filter { cur == null || (it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight) }.orEmpty()
        val want = if (smooth) same.maxByOrNull { it.refreshRate } else same.minByOrNull { kotlin.math.abs(it.refreshRate - 60f) }
        if (want != null) lp.preferredDisplayModeId = want.modeId
    }
    lp.preferredRefreshRate = if (smooth) 0f else 60f
    w.attributes = lp
}

@Composable
actual fun screenHeightDp(): Int = LocalConfiguration.current.screenHeightDp

@OptIn(ExperimentalComposeUiApi::class)
actual fun Modifier.tagsAsIds(): Modifier = this.semantics { testTagsAsResourceId = true }

private val dateFmt = SimpleDateFormat("MMM d, h:mm a", Locale.US)
actual fun shortDate(ms: Long): String = synchronized(dateFmt) { dateFmt.format(Date(ms)) }
