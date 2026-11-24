package com.tideclock.messmachine.cloud

import kotlin.jvm.JvmStatic

/** SHA-256 in plain Kotlin: CloudStore compares messes with it, FakeCloud hashes sounds with it, and a platform
 *  with no digest of its own may use it. Lower hex out. */
object Sha256 {
    private val K = intArrayOf(
        1116352408, 1899447441, -1245643825, -373957723, 961987163, 1508970993, -1841331548, -1424204075, -670586216, 310598401, 607225278, 1426881987,
        1925078388, -2132889090, -1680079193, -1046744716, -459576895, -272742522, 264347078, 604807628, 770255983, 1249150122, 1555081692, 1996064986,
        -1740746414, -1473132947, -1341970488, -1084653625, -958395405, -710438585, 113926993, 338241895, 666307205, 773529912, 1294757372, 1396182291,
        1695183700, 1986661051, -2117940946, -1838011259, -1564481375, -1474664885, -1035236496, -949202525, -778901479, -694614492, -200395387, 275423344,
        430227734, 506948616, 659060556, 883997877, 958139571, 1322822218, 1537002063, 1747873779, 1955562222, 2024104815, -2067236844, -1933114872,
        -1866530822, -1538233109, -1090935817, -965641998)

    @JvmStatic fun hex(b: ByteArray): String = hex(b, b.size)

    /** The hash of the first n bytes of b. */
    @JvmStatic fun hex(b: ByteArray, n: Int): String {
        val h = intArrayOf(1779033703, -1150833019, 1013904242, -1521486534, 1359893119, -1694144372, 528734635, 1541459225)
        val w = IntArray(64)
        val block = ByteArray(64)
        var p = 0
        while (p + 64 <= n) { round(b, p, h, w); p += 64 }
        // the tail, the 0x80 byte, zeros, and the length in bits
        val rest = n - p
        for (k in 0 until rest) block[k] = b[p + k]
        block[rest] = 0x80.toByte()
        if (rest >= 56) { round(block, 0, h, w); block.fill(0) }
        val bits = n.toLong() * 8L
        for (k in 0 until 8) block[63 - k] = (bits ushr (8 * k)).toByte()
        round(block, 0, h, w)
        val sb = StringBuilder(64)
        val digits = "0123456789abcdef"
        for (x in h) for (k in 7 downTo 0) sb.append(digits[(x ushr (4 * k)) and 15])
        return sb.toString()
    }

    private fun rotr(x: Int, n: Int): Int = (x ushr n) or (x shl (32 - n))

    private fun round(b: ByteArray, off: Int, h: IntArray, w: IntArray) {
        for (t in 0 until 16) {
            val i = off + t * 4
            w[t] = ((b[i].toInt() and 255) shl 24) or ((b[i + 1].toInt() and 255) shl 16) or ((b[i + 2].toInt() and 255) shl 8) or (b[i + 3].toInt() and 255)
        }
        for (t in 16 until 64) {
            val a = w[t - 15]; val c = w[t - 2]
            val s0 = rotr(a, 7) xor rotr(a, 18) xor (a ushr 3)
            val s1 = rotr(c, 17) xor rotr(c, 19) xor (c ushr 10)
            w[t] = w[t - 16] + s0 + w[t - 7] + s1
        }
        var a = h[0]; var bb = h[1]; var c = h[2]; var d = h[3]; var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
        for (t in 0 until 64) {
            val s1 = rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)
            val ch = (e and f) xor (e.inv() and g)
            val t1 = hh + s1 + ch + K[t] + w[t]
            val s0 = rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)
            val maj = (a and bb) xor (a and c) xor (bb and c)
            val t2 = s0 + maj
            hh = g; g = f; f = e; e = d + t1; d = c; c = bb; bb = a; a = t1 + t2
        }
        h[0] += a; h[1] += bb; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh
    }
}
