package com.tideclock.messmachine

import com.tideclock.messmachine.platform.FileStore
import com.tideclock.messmachine.platform.MFile
import java.io.File

/** Files on Android: java.io.File, call for call (CMP step 4). */
object JavaFileStore : FileStore {
    override fun exists(path: String) = File(path).exists()
    override fun isFile(path: String) = File(path).isFile
    override fun isDirectory(path: String) = File(path).isDirectory
    override fun readBytes(path: String) = File(path).readBytes()
    override fun writeBytes(path: String, b: ByteArray) = File(path).writeBytes(b)
    override fun delete(path: String) = File(path).delete()
    override fun rename(from: String, to: String) = File(from).renameTo(File(to))
    override fun list(path: String): List<String>? = File(path).list()?.toList()
    override fun mkdirs(path: String) = File(path).mkdirs()
    override fun lastModified(path: String) = File(path).lastModified()
    override fun length(path: String) = File(path).length()
}

/** Where an MFile meets an Android API that wants a java.io.File (sharing, the Workbench, zips). */
fun MFile.java(): File = File(path)
fun File.m(): MFile = MFile(path)
