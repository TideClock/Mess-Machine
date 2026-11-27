package com.tideclock.messmachine

import android.content.Context
import com.tideclock.messmachine.cloud.CloudCache
import com.tideclock.messmachine.cloud.TokenStore
import java.io.File

/**
 * The signed-in session between launches: one JSON string in the private prefs file "cloud".
 * Slice A keeps it plain in MODE_PRIVATE prefs, which only this app can read (and which is left out of backups,
 * see res/xml/backup_rules.xml). Sealing it with an Android Keystore key, as docs/ACCOUNTS-SPEC.md s5.2 asks, is
 * still to do; the token is short lived and the refresh token is single use, so this is acceptable for now.
 */
class AndroidTokens(app: Context) : TokenStore {
    private val prefs = app.getSharedPreferences("cloud", Context.MODE_PRIVATE)
    override fun load(): String? = prefs.getString("session", null)
    // commit, not apply: refresh tokens are single use, so the new one must be on disk before anything else happens
    override fun save(json: String?) { prefs.edit().apply { if (json == null) remove("session") else putString("session", json) }.commit() }
}

/** The store's small JSON files (index.json, list.json, known.json, <cloud id>.sounds.json) in files/cloud/. */
class AndroidCache(app: Context) : CloudCache {
    private val dir = File(app.filesDir, "cloud").also { it.mkdirs() }
    private fun file(name: String) = File(dir, name.replace('/', '_'))
    override fun load(name: String): String? = try { file(name).takeIf { it.isFile }?.readText() } catch (_: Exception) { null }
    override fun save(name: String, json: String?) {
        val f = file(name)
        try {
            if (json == null) { f.delete(); return }
            // whole or not at all, like the mess files
            val tmp = File(f.path + ".new")
            tmp.writeText(json)
            if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
        } catch (_: Exception) {}
    }
}
