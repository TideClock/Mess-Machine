package com.tideclock.messmachine.platform

/**
 * The app's settings store (docs/CMP-PLAN.md step 4, the Device interface's prefs). The same calls AppState always made
 * on Android's SharedPreferences, so no call site changes: Android wraps SharedPreferences, iOS wraps NSUserDefaults.
 */
interface Settings {
    fun getBoolean(key: String, def: Boolean): Boolean
    fun getInt(key: String, def: Int): Int
    fun getFloat(key: String, def: Float): Float
    fun getString(key: String, def: String?): String?
    fun getStringSet(key: String, def: Set<String>?): Set<String>?
    fun edit(): Editor

    /** Changes land together on apply (in the background) or commit (now, true when written). */
    interface Editor {
        fun putBoolean(key: String, v: Boolean): Editor
        fun putInt(key: String, v: Int): Editor
        fun putFloat(key: String, v: Float): Editor
        fun putString(key: String, v: String?): Editor
        fun putStringSet(key: String, v: Set<String>?): Editor
        fun remove(key: String): Editor
        fun apply()
        fun commit(): Boolean
    }
}

/** A settings store in memory, for tests and previews. */
class MemorySettings : Settings {
    private val m = HashMap<String, Any?>()
    @Suppress("UNCHECKED_CAST")
    private fun <T> get(k: String, d: T): T = if (k in m) m[k] as T else d
    override fun getBoolean(key: String, def: Boolean) = get(key, def)
    override fun getInt(key: String, def: Int) = get(key, def)
    override fun getFloat(key: String, def: Float) = get(key, def)
    override fun getString(key: String, def: String?) = get(key, def)
    override fun getStringSet(key: String, def: Set<String>?) = get(key, def)
    override fun edit(): Settings.Editor = object : Settings.Editor {
        val ch = LinkedHashMap<String, Any?>(); val gone = HashSet<String>()
        override fun putBoolean(key: String, v: Boolean) = also { ch[key] = v }
        override fun putInt(key: String, v: Int) = also { ch[key] = v }
        override fun putFloat(key: String, v: Float) = also { ch[key] = v }
        override fun putString(key: String, v: String?) = also { if (v == null) gone += key else ch[key] = v }
        override fun putStringSet(key: String, v: Set<String>?) = also { if (v == null) gone += key else ch[key] = v.toSet() }
        override fun remove(key: String) = also { gone += key }
        override fun apply() { commit() }
        override fun commit(): Boolean { gone.forEach { m.remove(it) }; m.putAll(ch); return true }
    }
}
