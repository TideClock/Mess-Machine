package com.tideclock.messmachine

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.tideclock.messmachine.platform.Device
import com.tideclock.messmachine.platform.MFile

/** The phone for AppState on Android: the same calls AppState made itself before CMP step 4. */
class AndroidDevice(private val app: Context) : Device {
    private val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    override fun readAsset(path: String): ByteArray = app.assets.open(path).use { it.readBytes() }
    override val outputRate: Int? get() = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
    override val outputBurst: Int? get() = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()
    override fun uptimeMillis() = android.os.SystemClock.uptimeMillis()
    override val isTestRun: Boolean get() = android.os.Build.FINGERPRINT == "robolectric"

    override fun backgroundPlay(on: Boolean): Boolean {
        val i = Intent(app, PlaybackService::class.java)
        return try { if (on) ContextCompat.startForegroundService(app, i) else app.stopService(i); true } catch (_: Exception) { false }
    }

    override fun shareFile(f: MFile, mime: String, title: String, subject: Boolean) {
        val uri = FileProvider.getUriForFile(app, app.packageName + ".files", f.java())
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            if (subject) putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        app.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    override fun shareText(text: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
        app.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun hasMicPermission() =
        ContextCompat.checkSelfPermission(app, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED

    override fun screenDp(): Pair<Int, Int> {
        val dm = app.resources.displayMetrics
        return (dm.widthPixels / dm.density).toInt() to (dm.heightPixels / dm.density).toInt()
    }

    override fun unmeteredNetwork(): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) ||
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    override fun clipboardText(): String =
        (app.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(app)?.toString() ?: ""
}
