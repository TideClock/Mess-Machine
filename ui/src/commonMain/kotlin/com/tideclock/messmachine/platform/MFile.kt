package com.tideclock.messmachine.platform

/**
 * A file by path, with the java.io.File calls AppState uses (CMP step 4, the Files interface). AppState imports it as
 * `File`, so its file code reads exactly as before; the work goes to [store], which each platform sets once at start
 * (Android: java.io.File underneath; iOS: NSFileManager).
 */
class MFile(val path: String) {
    constructor(parent: MFile, child: String) : this(parent.path.trimEnd('/') + "/" + child)

    val name: String get() = path.substringAfterLast('/')
    val parentFile: MFile? get() = path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { MFile(it) }
    val isFile: Boolean get() = store.isFile(path)
    val isDirectory: Boolean get() = store.isDirectory(path)
    fun exists(): Boolean = store.exists(path)
    fun readText(): String = store.readBytes(path).decodeToString()
    fun writeText(text: String) = store.writeBytes(path, text.encodeToByteArray())
    fun readBytes(): ByteArray = store.readBytes(path)
    fun writeBytes(b: ByteArray) = store.writeBytes(path, b)
    fun delete(): Boolean = store.delete(path)
    fun renameTo(dest: MFile): Boolean = store.rename(path, dest.path)
    /** As kotlin.io's copyTo: fails when [dest] is there and [overwrite] is false. */
    fun copyTo(dest: MFile, overwrite: Boolean = false): MFile {
        if (dest.exists() && !overwrite) throw IllegalStateException("${dest.path} is already there")
        store.writeBytes(dest.path, readBytes()); return dest
    }
    fun listFiles(): Array<MFile>? = store.list(path)?.map { MFile(this, it) }?.toTypedArray()
    fun mkdirs(): Boolean = store.mkdirs(path)
    fun lastModified(): Long = store.lastModified(path)
    fun length(): Long = store.length(path)

    override fun equals(other: Any?) = other is MFile && other.path == path
    override fun hashCode() = path.hashCode()
    override fun toString() = path

    companion object {
        lateinit var store: FileStore
    }
}

/** What a platform does with files by path. Errors throw, as java.io does. */
interface FileStore {
    fun exists(path: String): Boolean
    fun isFile(path: String): Boolean
    fun isDirectory(path: String): Boolean
    fun readBytes(path: String): ByteArray
    fun writeBytes(path: String, b: ByteArray)
    fun delete(path: String): Boolean
    fun rename(from: String, to: String): Boolean
    /** names in the folder, or null when it is not a folder */
    fun list(path: String): List<String>?
    fun mkdirs(path: String): Boolean
    fun lastModified(path: String): Long
    fun length(path: String): Long
}
