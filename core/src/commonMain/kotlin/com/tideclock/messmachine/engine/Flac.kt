package com.tideclock.messmachine.engine

import kotlin.jvm.JvmStatic

/**
 * Reads FLAC files to mono floats (MM-8). The bundled sounds ship as FLAC: lossless, so every sample is the
 * same as the WAV it came from, at about half the size. Plain Java, so the engine test runs it too.
 * Follows the FLAC format: constant, verbatim, fixed and LPC subframes, Rice residuals, stereo decorrelation.
 */
object Flac {
    // The InputStream reader is gone (streams became byte arrays): read takes the whole file.

    @JvmStatic
    @Throws(Exception::class)
    fun read(key: String, b: ByteArray): Sample {
        if (b.size < 42 || b[0].toInt() != 'f'.code || b[1].toInt() != 'L'.code || b[2].toInt() != 'a'.code || b[3].toInt() != 'C'.code) throw EngineIOException("Not a FLAC file")
        val r = Bits(b, 4)
        var rate = 0
        var channels = 1
        var depth = 16
        var total = 0L
        var last = false
        while (!last) {
            last = r.u(1) == 1
            val type = r.u(7)
            val len = r.u(24)
            if (type == 0) {
                r.u(16); r.u(16); r.u(24); r.u(24)
                rate = r.u(20)
                channels = r.u(3) + 1
                depth = r.u(5) + 1
                total = (r.u(4).toLong() shl 32) or (r.u(32).toLong() and 0xFFFFFFFFL)
                r.skipBytes(16)
            } else r.skipBytes(len)
        }
        if (rate <= 0) throw EngineIOException("FLAC has no stream info")
        var out = FloatArray(maxOf(0L, total).toInt())
        var at = 0
        val scale = 1.0 / (1L shl (depth - 1)) / channels
        val ch = arrayOfNulls<LongArray>(channels)
        while (r.bytesLeft() > 2 && (total == 0L || at < out.size)) {
            val n = frame(r, channels, depth, ch)
            if (n <= 0) break
            if (total == 0L && at + n > out.size) out = out.copyOf(maxOf(out.size * 2, at + n))
            val m = minOf(n, out.size - at)
            for (k in 0 until m) {
                var sum = 0L
                for (c in 0 until channels) sum += ch[c]!![k]
                out[at + k] = (sum * scale).toFloat()
            }
            at += m
        }
        if (at < out.size) out = out.copyOf(at)
        return Sample(key, out, rate)
    }

    /** Decodes one frame into ch[c][0..n), returns n (the frame's block size). */
    private fun frame(r: Bits, channels: Int, depth: Int, ch: Array<LongArray?>): Int {
        r.align()
        if (r.u(14) != 0x3FFE) throw EngineIOException("FLAC frame sync lost")
        r.u(1); r.u(1)
        val bsCode = r.u(4)
        val rateCode = r.u(4)
        val assign = r.u(4)
        val sizeCode = r.u(3)
        r.u(1)
        // the frame or sample number, coded like UTF-8
        val lead = r.u(8)
        var extra = 0
        while (extra < 7 && ((lead shl extra) and 0x80) != 0) extra++
        for (k in 1 until extra) r.u(8)
        val n: Int
        if (bsCode == 1) n = 192
        else if (bsCode >= 2 && bsCode <= 5) n = 576 shl (bsCode - 2)
        else if (bsCode == 6) n = r.u(8) + 1
        else if (bsCode == 7) n = r.u(16) + 1
        else if (bsCode >= 8) n = 256 shl (bsCode - 8)
        else throw EngineIOException("FLAC reserved block size")
        if (rateCode == 12) r.u(8) else if (rateCode == 13 || rateCode == 14) r.u(16)
        val bits = if (sizeCode == 1) 8 else if (sizeCode == 2) 12 else if (sizeCode == 4) 16 else if (sizeCode == 5) 20 else if (sizeCode == 6) 24 else if (sizeCode == 7) 32 else depth
        r.u(8) // header CRC-8
        val nch = if (assign < 8) assign + 1 else 2
        if (nch != channels) throw EngineIOException("FLAC channel count changed")
        for (c in 0 until nch) {
            if (ch[c] == null || ch[c]!!.size < n) ch[c] = LongArray(maxOf(n, 4608))
            // the side channel carries one extra bit
            val d = bits + (if ((assign == 8 && c == 1) || (assign == 9 && c == 0) || (assign == 10 && c == 1)) 1 else 0)
            subframe(r, d, n, ch[c]!!)
        }
        val c0 = ch[0]!!
        if (assign == 8) { val c1 = ch[1]!!; for (k in 0 until n) c1[k] = c0[k] - c1[k] }
        else if (assign == 9) { val c1 = ch[1]!!; for (k in 0 until n) c0[k] = c0[k] + c1[k] }
        else if (assign == 10) {
            val c1 = ch[1]!!
            for (k in 0 until n) {
                val side = c1[k]
                val mid = (c0[k] shl 1) or (side and 1)
                c0[k] = (mid + side) shr 1
                c1[k] = (mid - side) shr 1
            }
        }
        r.align()
        r.u(16) // frame CRC-16
        return n
    }

    private val FIXED = arrayOf(intArrayOf(), intArrayOf(1), intArrayOf(2, -1), intArrayOf(3, -3, 1), intArrayOf(4, -6, 4, -1))

    private fun subframe(r: Bits, depth0: Int, n: Int, s: LongArray) {
        var depth = depth0
        r.u(1)
        val type = r.u(6)
        var wasted = 0
        if (r.u(1) == 1) { wasted = 1; while (r.u(1) == 0) wasted++ }
        depth -= wasted
        if (type == 0) {
            val v = r.s(depth)
            for (k in 0 until n) s[k] = v
        } else if (type == 1) {
            for (k in 0 until n) s[k] = r.s(depth)
        } else if (type >= 8 && type <= 12) {
            val order = type - 8
            for (k in 0 until order) s[k] = r.s(depth)
            residual(r, n, order, s)
            val c = FIXED[order]
            for (k in order until n) {
                var sum = 0L
                for (j in 0 until order) sum += c[j].toLong() * s[k - 1 - j]
                s[k] += sum
            }
        } else if (type >= 32) {
            val order = (type and 31) + 1
            for (k in 0 until order) s[k] = r.s(depth)
            val precision = r.u(4) + 1
            val shift = r.s(5).toInt()
            val coef = LongArray(order)
            for (j in 0 until order) coef[j] = r.s(precision)
            residual(r, n, order, s)
            for (k in order until n) {
                var sum = 0L
                for (j in 0 until order) sum += coef[j] * s[k - 1 - j]
                s[k] += sum shr shift
            }
        } else throw EngineIOException("FLAC reserved subframe type " + type)
        if (wasted > 0) for (k in 0 until n) s[k] = s[k] shl wasted
    }

    /** Rice-coded residuals land in s[order..n) and are added to the prediction afterwards. */
    private fun residual(r: Bits, n: Int, order: Int, s: LongArray) {
        val method = r.u(2)
        if (method > 1) throw EngineIOException("FLAC reserved residual coding")
        val pbits = if (method == 0) 4 else 5
        val escape = if (method == 0) 15 else 31
        val porder = r.u(4)
        val parts = 1 shl porder
        var k = order
        for (p in 0 until parts) {
            val count = (n shr porder) - (if (p == 0) order else 0)
            val param = r.u(pbits)
            if (param == escape) {
                val raw = r.u(5)
                for (i in 0 until count) s[k++] = if (raw == 0) 0L else r.s(raw)
            } else {
                for (i in 0 until count) {
                    val q = r.unary()
                    val v = (q shl param) or (if (param == 0) 0L else (r.u(param).toLong() and 0xFFFFFFFFL))
                    s[k++] = (v ushr 1) xor -(v and 1)
                }
            }
        }
    }

    /** Big-endian bit reader over the whole file. */
    private class Bits(private val b: ByteArray, byteStart: Int) {
        private var pos: Long = byteStart * 8L // in bits

        fun bytesLeft(): Int { return (b.size - (pos + 7) / 8).toInt() }

        fun align() { pos = (pos + 7) and 7L.inv() }

        fun skipBytes(n: Int) { align(); pos += n * 8L }

        /** Unsigned, up to 32 bits (returned in an int; 32-bit values are read with & 0xFFFFFFFFL). */
        fun u(n: Int): Int {
            if (n == 0) return 0
            if (pos + n > b.size * 8L) throw EngineIOException("FLAC ended early")
            var v = 0L
            var k = 0
            while (k < n) {
                val byteAt = (pos ushr 3).toInt()
                val off = (pos and 7).toInt()
                val take = minOf(8 - off, n - k)
                val bitsHere = ((b[byteAt].toInt() and 0xFF) ushr (8 - off - take)) and ((1 shl take) - 1)
                v = (v shl take) or bitsHere.toLong()
                pos += take
                k += take
            }
            return v.toInt()
        }

        /** Signed, two's complement, up to 32 bits. */
        fun s(n: Int): Long {
            if (n == 0) return 0
            val v = u(n).toLong() and 0xFFFFFFFFL
            return (v shl (64 - n)) shr (64 - n)
        }

        /** Counts zero bits up to the next one bit. */
        fun unary(): Long {
            var q = 0L
            while (true) {
                val byteAt = (pos ushr 3).toInt()
                if (byteAt >= b.size) throw EngineIOException("FLAC ended early")
                val off = (pos and 7).toInt()
                val rest = (b[byteAt].toInt() shl off) and 0xFF
                if (rest == 0) { q += 8 - off; pos += 8 - off; continue }
                val z = rest.countLeadingZeroBits() - 24
                q += z
                pos += z + 1
                return q
            }
        }
    }
}
