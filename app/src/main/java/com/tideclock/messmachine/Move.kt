package com.tideclock.messmachine

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Moving to the new app ID (Nov 24 2026, Tyler: the app becomes app.shotgunstudio.messmachine). Android keeps each app's
 * files to itself, so the old app (com.tideclock.messmachine) hands everything over through this provider: one zip of
 * its files folder (messes, Workbench, packs, notes, cloud) and its settings. Only an app signed with the same key may
 * read it (the MOVE permission is signature level). The new app takes it once, on its first launch.
 */
object Move {
    const val OLD = "com.tideclock.messmachine"
    const val AUTHORITY = "$OLD.move"
    private const val DONE = "moved-from-old"

    /** The new app, the old app still installed, and nothing moved yet. */
    fun needed(c: Context): Boolean {
        if (c.packageName == OLD) return false
        if (c.getSharedPreferences("move", Context.MODE_PRIVATE).getBoolean(DONE, false)) return false
        return try { c.packageManager.getPackageInfo(OLD, 0); true } catch (_: PackageManager.NameNotFoundException) { false }
    }

    /** Copy the old app's files and settings into this one. Returns what to tell the user. Runs off the main thread. */
    fun run(c: Context): String {
        val prefsDir = File(c.applicationInfo.dataDir, "shared_prefs")
        var files = 0
        val text = try {
            c.contentResolver.openInputStream(Uri.parse("content://$AUTHORITY/all.zip"))!!.use { raw ->
                ZipInputStream(raw.buffered()).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        val name = e.name
                        // only plain paths inside the two folders: nothing can be written outside them
                        if (name.contains("..") || e.isDirectory) continue
                        val out = when {
                            name.startsWith("files/") -> File(c.filesDir, name.removePrefix("files/"))
                            name.startsWith("prefs/") && name.endsWith(".xml") -> File(prefsDir, name.removePrefix("prefs/"))
                            else -> null
                        } ?: continue
                        out.parentFile?.mkdirs()
                        out.outputStream().use { z.copyTo(it) }
                        files++
                    }
                }
            }
            "Your messes, sounds and settings moved over from the old app ($files files). You can uninstall the old Mess Machine now."
        } catch (e: Exception) {
            "The old app's messes did not move over (${e.message ?: "no answer"}). Update the old app once, then open this one again."
        }
        if (files > 0) c.getSharedPreferences("move", Context.MODE_PRIVATE).edit().putBoolean(DONE, true).commit()
        return text
    }
}

/** The old app's side: everything in one zip, streamed through a pipe, for the new app signed with the same key. */
class MoveProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val c = context!!
        val pipe = ParcelFileDescriptor.createPipe()
        val write = pipe[1]
        Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(write).use { os ->
                    ZipOutputStream(os.buffered()).use { z ->
                        fun add(root: File, prefix: String) {
                            root.walkTopDown().filter { it.isFile }.forEach { f ->
                                val rel = f.relativeTo(root).path
                                // the update download and crash notes stay behind
                                if (rel.endsWith(".apk") || rel == "last_crash.txt") return@forEach
                                z.putNextEntry(ZipEntry(prefix + rel))
                                f.inputStream().use { it.copyTo(z) }
                                z.closeEntry()
                            }
                        }
                        add(c.filesDir, "files/")
                        add(File(c.applicationInfo.dataDir, "shared_prefs"), "prefs/")
                    }
                }
            } catch (_: Exception) {}
        }, "mess-move").start()
        return pipe[0]
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri) = "application/zip"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?) = 0
}
