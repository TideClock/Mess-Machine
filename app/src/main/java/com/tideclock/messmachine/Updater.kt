package com.tideclock.messmachine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tideclock.messmachine.engine.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Update from inside the app (Tyler, Nov 17): the app reads the `latest` release on GitHub, downloads its APK and
 * hands it to Android's installer itself, so there is no browser, no Downloads folder and no file to open.
 *
 * Android still asks once, the first time, to allow installs from Mess Machine. After that the app asks Android to
 * update itself without the Install tap (Android 12 and up allow that for an app updating itself); if Android asks
 * anyway, its Install sheet opens straight away.
 */
class Updater(private val app: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    /** The newest build on GitHub, 0 until checked. */
    var latest by mutableIntStateOf(0)
        private set
    var status by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var later by mutableStateOf(false)
    private var apkUrl = ""
    private var apkSize = 0L

    val current get() = BuildConfig.VERSION_CODE
    val ready get() = latest > current && apkUrl.isNotEmpty()

    /** For the tests: what a check would find. */
    fun found(build: Int, url: String, size: Long) { latest = build; apkUrl = url; apkSize = size }

    fun check(quiet: Boolean, done: (String) -> Unit = {}) {
        if (busy) return
        if (!quiet) status = "Checking…"
        io.execute {
            val msg = try {
                val r = Json.map(Json.parse(get("https://api.github.com/repos/$REPO/releases/tags/latest")))
                val b = buildOf(Json.str(r["body"], ""))
                val apk = Json.list(r["assets"]).map { Json.map(it) }.firstOrNull { Json.str(it["name"], "") == "MessMachine.apk" }
                main.post {
                    if (b > 0 && apk != null) found(b, Json.str(apk["browser_download_url"], ""), Json.num(apk["size"], 0.0).toLong())
                }
                when {
                    b <= 0 || apk == null -> "GitHub is publishing a build right now. Try again in a minute."
                    b > current -> "Build $b is ready."
                    else -> "You have the newest build ($current)."
                }
            } catch (e: Exception) { "Could not reach GitHub to check." }
            main.post { status = if (quiet) "" else msg; done(msg) }
        }
    }

    /** Step 1 of Update: Android must allow installs from Mess Machine. Returns false after sending you there. */
    fun allowed(): Boolean {
        if (Build.VERSION.SDK_INT < 26 || app.packageManager.canRequestPackageInstalls()) return true
        try {
            app.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
        return false
    }

    /** Download the APK, then hand it to the installer. The app closes when Android swaps it. */
    fun download(before: () -> Unit) {
        if (busy || !ready) return
        busy = true
        val build = latest
        status = "Downloading build $build…"
        io.execute {
            val f = File(app.cacheDir, "update.apk")
            val err = try {
                var u = URL(apkUrl)
                var c: HttpURLConnection
                var hops = 0
                while (true) {
                    c = u.openConnection() as HttpURLConnection
                    c.instanceFollowRedirects = false
                    c.setRequestProperty("User-Agent", "MessMachine-Android")
                    c.connectTimeout = 15000; c.readTimeout = 30000
                    val code = c.responseCode
                    if (code in 300..399 && hops++ < 5) { u = URL(u, c.getHeaderField("Location")); c.disconnect(); continue }
                    if (code !in 200..299) throw Exception("GitHub said $code")
                    break
                }
                val total = c.contentLengthLong.takeIf { it > 0 } ?: apkSize
                c.inputStream.use { inp ->
                    f.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var got = 0L; var shown = -1
                        while (true) {
                            val n = inp.read(buf); if (n < 0) break
                            out.write(buf, 0, n); got += n
                            val pc = if (total > 0) (got * 100 / total).toInt() else -1
                            if (pc != shown && pc % 5 == 0) { shown = pc; main.post { status = "Downloading build $build: $pc%" } }
                        }
                    }
                }
                c.disconnect()
                if (apkSize > 0 && f.length() != apkSize) throw Exception("the download came up short")
                null
            } catch (e: Exception) { "Download failed: ${e.message ?: "no connection"}. Tap Update to try again." }
            main.post {
                if (err != null) { busy = false; status = err; return@post }
                status = "Installing build $build. The app closes and Android puts the new one in."
                before()
                // let the mess save land on disk first
                main.postDelayed({ install(f, build) }, 600)
            }
        }
    }

    private fun install(f: File, build: Int) {
        io.execute {
            val err = try {
                val pi = app.packageManager.packageInstaller
                val p = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                // not pinned to this package: the move to the new app ID installs the new app from here (Nov 24)
                if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                val id = pi.createSession(p)
                pi.openSession(id).use { s ->
                    s.openWrite("MessMachine.apk", 0, f.length()).use { out -> f.inputStream().use { it.copyTo(out) }; s.fsync(out) }
                    val i = Intent(app, UpdateReceiver::class.java).putExtra("build", build)
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    s.commit(PendingIntent.getBroadcast(app, id, i, flags).intentSender)
                }
                app.getSharedPreferences("update", Context.MODE_PRIVATE).edit().putInt("from", current).apply()
                null
            } catch (e: Exception) { "Android would not take the update: ${e.message}" }
            if (err != null) main.post { busy = false; status = err }
        }
    }

    internal fun failed(msg: String) { main.post { busy = false; status = msg } }

    private fun get(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "MessMachine-Android")
        c.connectTimeout = 10000; c.readTimeout = 15000
        try {
            if (c.responseCode !in 200..299) throw Exception("GitHub said ${c.responseCode}")
            return c.inputStream.use { String(it.readBytes()) }
        } finally { c.disconnect() }
    }

    companion object {
        /** The release text starts "Build 92 from 09de2b0. ...". */
        fun buildOf(body: String) = Regex("""Build (\d+)""").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        var live: Updater? = null
    }
}

/** Android's answer to the install: ask you (its Install sheet), or report why it failed. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val ask = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                try { c.startActivity(ask.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                val m = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown"
                Updater.live?.failed("The update did not install ($m). Tap Update to try again.")
            }
        }
    }
}

/** After an in-app update Android closes the app; this puts a tap-to-open notice up so you are one tap back in. */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = c.getSharedPreferences("update", Context.MODE_PRIVATE)
        if (prefs.getInt("from", 0) == 0) return
        prefs.edit().remove("from").apply()
        if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(c, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        try {
            val nm = c.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("updates", "Updates", NotificationManager.IMPORTANCE_HIGH))
            val open = PendingIntent.getActivity(c, 7, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(c, "updates").setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Mess Machine updated to build ${BuildConfig.VERSION_CODE}").setContentText("Tap to open it.")
                .setContentIntent(open).setAutoCancel(true).build()
            NotificationManagerCompat.from(c).notify(7, n)
        } catch (_: SecurityException) {} catch (_: Exception) {}
    }
}
