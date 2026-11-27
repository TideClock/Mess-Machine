package com.tideclock.messmachine.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSEC_PER_MSEC
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_time
import platform.posix.memcpy

/*
 * The plain parts of the iPhone's Platform, in Kotlin (CMP step 7): settings, the main thread, a worker, and files.
 * The iOS chat adds the rest (sound out, mic, pickers, Device) in Swift or Kotlin and bundles them in a Platform.
 */

/** Settings in NSUserDefaults, under one prefix so the app's keys stay together. */
class IosSettings(private val prefix: String = "mess.") : Settings {
    private val d = NSUserDefaults.standardUserDefaults
    private fun k(key: String) = prefix + key
    private fun has(key: String) = d.objectForKey(k(key)) != null
    override fun getBoolean(key: String, def: Boolean) = if (has(key)) d.boolForKey(k(key)) else def
    override fun getInt(key: String, def: Int) = if (has(key)) d.integerForKey(k(key)).toInt() else def
    override fun getFloat(key: String, def: Float) = if (has(key)) d.floatForKey(k(key)) else def
    override fun getString(key: String, def: String?) = d.stringForKey(k(key)) ?: def
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, def: Set<String>?): Set<String>? = (d.arrayForKey(k(key)) as List<String>?)?.toSet() ?: def
    override fun edit(): Settings.Editor = object : Settings.Editor {
        val ch = ArrayList<() -> Unit>()
        override fun putBoolean(key: String, v: Boolean) = also { ch += { d.setBool(v, k(key)) } }
        override fun putInt(key: String, v: Int) = also { ch += { d.setInteger(v.toLong(), k(key)) } }
        override fun putFloat(key: String, v: Float) = also { ch += { d.setFloat(v, k(key)) } }
        override fun putString(key: String, v: String?) = also { ch += { if (v == null) d.removeObjectForKey(k(key)) else d.setObject(v, k(key)) } }
        override fun putStringSet(key: String, v: Set<String>?) = also { ch += { if (v == null) d.removeObjectForKey(k(key)) else d.setObject(v.toList(), k(key)) } }
        override fun remove(key: String) = also { ch += { d.removeObjectForKey(k(key)) } }
        override fun apply() { commit() }
        override fun commit(): Boolean { ch.forEach { it() }; return true }
    }
}

/** The main queue. A cancelled function is skipped when its turn comes. Only used on the main thread. */
class IosMainThread : MainThread {
    private val cancelled = HashMap<Any, Int>()
    private val pending = HashMap<Any, Int>()
    private fun run(f: () -> Unit) {
        val p = (pending[f] ?: 1) - 1
        if (p <= 0) pending.remove(f) else pending[f] = p
        val c = cancelled[f] ?: 0
        if (c > 0) { if (c == 1) cancelled.remove(f) else cancelled[f] = c - 1 } else f()
    }
    override fun post(f: () -> Unit) { pending[f] = (pending[f] ?: 0) + 1; dispatch_async(dispatch_get_main_queue()) { run(f) } }
    override fun postDelayed(f: () -> Unit, ms: Long) {
        pending[f] = (pending[f] ?: 0) + 1
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, ms * NSEC_PER_MSEC.toLong()), dispatch_get_main_queue()) { run(f) }
    }
    override fun cancel(f: () -> Unit) { val p = pending[f] ?: 0; if (p > 0) cancelled[f] = p }
}

/** One serial background queue. */
class IosWorker(name: String = "mess-worker") : Worker {
    private val q = dispatch_queue_create(name, null)
    override fun execute(f: () -> Unit) { dispatch_async(q) { f() } }
}

/** Files through NSFileManager and NSData. */
@OptIn(ExperimentalForeignApi::class)
object IosFileStore : FileStore {
    private val fm get() = NSFileManager.defaultManager
    override fun exists(path: String) = fm.fileExistsAtPath(path)
    override fun isFile(path: String) = exists(path) && !isDirectory(path)
    override fun isDirectory(path: String) = fm.contentsOfDirectoryAtPath(path, null) != null
    override fun readBytes(path: String): ByteArray {
        val data = NSData.dataWithContentsOfFile(path) ?: throw IllegalStateException("cannot read $path")
        val n = data.length.toInt()
        val out = ByteArray(n)
        if (n > 0) out.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
        return out
    }
    override fun writeBytes(path: String, b: ByteArray) {
        val data = if (b.isEmpty()) NSData() else b.usePinned { NSData.create(bytes = it.addressOf(0), length = b.size.toULong()) }
        if (!data.writeToFile(path, atomically = false)) throw IllegalStateException("cannot write $path")
    }
    override fun delete(path: String) = fm.removeItemAtPath(path, null)
    override fun rename(from: String, to: String) = fm.moveItemAtPath(from, to, null)
    @Suppress("UNCHECKED_CAST")
    override fun list(path: String): List<String>? = fm.contentsOfDirectoryAtPath(path, null) as List<String>?
    override fun mkdirs(path: String) = fm.createDirectoryAtPath(path, true, null, null)
    override fun lastModified(path: String): Long =
        (((fm.attributesOfItemAtPath(path, null)?.get(NSFileModificationDate) as NSDate?)?.timeIntervalSince1970 ?: 0.0) * 1000).toLong()
    override fun length(path: String): Long = (fm.attributesOfItemAtPath(path, null)?.get(NSFileSize) as NSNumber?)?.longLongValue ?: 0L
}
