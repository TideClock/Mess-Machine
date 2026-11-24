package com.tideclock.messmachine.engine

import kotlin.jvm.JvmStatic
import kotlin.math.roundToInt

/** Reads PCM or float WAV files to mono floats, writes 16-bit stereo WAV. */
object Wav {
    // Streams became byte arrays: read takes the whole file, the writers return the whole file (the JVM caller
    // wraps its own stream). readAll is gone: nothing reads a stream here any more.

    @JvmStatic
    @Throws(Exception::class)
    fun read(key: String, b: ByteArray): Sample {
        if (b.size < 12 || !tag(b, 0, "RIFF") || !tag(b, 8, "WAVE")) throw EngineIOException("Not a WAV file")
        var p = 12
        var fmt = 1
        var ch = 1
        var rate = 44100
        var bits = 16
        var dataAt = -1
        var dataLen = 0
        while (p + 8 <= b.size) {
            val len = le32(b, p + 4)
            if (tag(b, p, "fmt ")) {
                fmt = le16(b, p + 8); ch = le16(b, p + 10); rate = le32(b, p + 12); bits = le16(b, p + 22)
                if (fmt == 0xFFFE && len >= 26) fmt = le16(b, p + 32) // WAVE_FORMAT_EXTENSIBLE sub format
            } else if (tag(b, p, "data")) {
                dataAt = p + 8
                dataLen = minOf(if (len < 0) Int.MAX_VALUE else len, b.size - dataAt)
                break
            }
            p += 8 + len + (len and 1)
        }
        if (dataAt < 0) throw EngineIOException("WAV has no data")
        if (ch < 1) ch = 1
        val bps = bits / 8
        val frames = dataLen / (bps * ch)
        val out = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until ch) {
                val o = dataAt + (f * ch + c) * bps
                val v: Float
                if (fmt == 3 && bits == 32) v = Float.fromBits(le32(b, o))
                else if (bits == 16) v = le16(b, o).toShort() / 32768f
                else if (bits == 24) v = (((b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or (b[o + 2].toInt() shl 16))) / 8388608f
                else if (bits == 32) v = le32(b, o) / 2147483648f
                else if (bits == 8) v = ((b[o].toInt() and 0xff) - 128) / 128f
                else throw EngineIOException("Unsupported WAV: " + bits + " bit")
                sum += v
            }
            out[f] = sum / ch
        }
        return Sample(key, out, rate)
    }

    /** Interleaved stereo floats to a 16-bit WAV. */
    @JvmStatic
    fun writeStereo16(lr: FloatArray, rate: Int): ByteArray {
        val frames = lr.size / 2
        val dataLen = frames * 4
        // the header and then every sample, as the stream got them (an odd last float is written too, as before)
        val h = ByteArray(44 + lr.size * 2)
        put(h, 0, "RIFF"); le32(h, 4, 36 + dataLen); put(h, 8, "WAVE"); put(h, 12, "fmt ")
        le32(h, 16, 16); le16(h, 20, 1); le16(h, 22, 2); le32(h, 24, rate); le32(h, 28, rate * 4); le16(h, 32, 4); le16(h, 34, 16)
        put(h, 36, "data"); le32(h, 40, dataLen)
        var n = 44
        for (v in lr) {
            val c = maxOf(-1f, minOf(1f, v))
            val s = jround(c * 32767f)
            h[n++] = s.toByte()
            h[n++] = (s shr 8).toByte()
        }
        return h
    }

    /** Mono floats to a 16-bit mono WAV. */
    @JvmStatic
    fun writeMono16(d: FloatArray, rate: Int): ByteArray {
        val dataLen = d.size * 2
        val h = ByteArray(44 + d.size * 2)
        put(h, 0, "RIFF"); le32(h, 4, 36 + dataLen); put(h, 8, "WAVE"); put(h, 12, "fmt ")
        le32(h, 16, 16); le16(h, 20, 1); le16(h, 22, 1); le32(h, 24, rate); le32(h, 28, rate * 2); le16(h, 32, 2); le16(h, 34, 16)
        put(h, 36, "data"); le32(h, 40, dataLen)
        var n = 44
        for (v in d) {
            val s = jround(maxOf(-1f, minOf(1f, v)) * 32767f)
            h[n++] = s.toByte()
            h[n++] = (s shr 8).toByte()
        }
        return h
    }

    /** Java's Math.round(float): roundToInt, but NaN gives 0 instead of throwing. */
    private fun jround(f: Float): Int = if (f.isNaN()) 0 else f.roundToInt()

    private fun tag(b: ByteArray, p: Int, t: String): Boolean {
        for (k in 0 until 4) if (b[p + k].toInt() != t[k].code) return false
        return true
    }
    private fun le16(b: ByteArray, p: Int): Int { return (b[p].toInt() and 0xff) or ((b[p + 1].toInt() and 0xff) shl 8) }
    private fun le32(b: ByteArray, p: Int): Int { return (b[p].toInt() and 0xff) or ((b[p + 1].toInt() and 0xff) shl 8) or ((b[p + 2].toInt() and 0xff) shl 16) or ((b[p + 3].toInt() and 0xff) shl 24) }
    private fun put(b: ByteArray, p: Int, t: String) { for (k in 0 until 4) b[p + k] = t[k].code.toByte() }
    private fun le16(b: ByteArray, p: Int, v: Int) { b[p] = v.toByte(); b[p + 1] = (v shr 8).toByte() }
    private fun le32(b: ByteArray, p: Int, v: Int) { b[p] = v.toByte(); b[p + 1] = (v shr 8).toByte(); b[p + 2] = (v shr 16).toByte(); b[p + 3] = (v shr 24).toByte() }
}
