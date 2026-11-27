package com.tideclock.messmachine

import android.content.SharedPreferences
import com.tideclock.messmachine.platform.Settings

/** The settings store on Android: SharedPreferences, call for call (CMP step 4). */
class AndroidSettings(private val p: SharedPreferences) : Settings {
    override fun getBoolean(key: String, def: Boolean) = p.getBoolean(key, def)
    override fun getInt(key: String, def: Int) = p.getInt(key, def)
    override fun getFloat(key: String, def: Float) = p.getFloat(key, def)
    override fun getString(key: String, def: String?) = p.getString(key, def)
    override fun getStringSet(key: String, def: Set<String>?): Set<String>? = p.getStringSet(key, def)
    override fun edit(): Settings.Editor = Ed(p.edit())

    private class Ed(val e: SharedPreferences.Editor) : Settings.Editor {
        override fun putBoolean(key: String, v: Boolean) = also { e.putBoolean(key, v) }
        override fun putInt(key: String, v: Int) = also { e.putInt(key, v) }
        override fun putFloat(key: String, v: Float) = also { e.putFloat(key, v) }
        override fun putString(key: String, v: String?) = also { e.putString(key, v) }
        override fun putStringSet(key: String, v: Set<String>?) = also { e.putStringSet(key, v) }
        override fun remove(key: String) = also { e.remove(key) }
        override fun apply() = e.apply()
        override fun commit() = e.commit()
    }
}
