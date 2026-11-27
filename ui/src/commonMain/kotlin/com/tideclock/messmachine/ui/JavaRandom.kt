package com.tideclock.messmachine.ui

/**
 * java.util.Random's exact sequence in common Kotlin (the same 48-bit generator), for art that was drawn with it on
 * Android and has to come out the same speckle on every platform (the grit, CMP step 3).
 */
class JavaRandom(seed: Long) {
    private var s = (seed xor 0x5DEECE66DL) and MASK
    private fun next(bits: Int): Int {
        s = (s * 0x5DEECE66DL + 0xBL) and MASK
        return (s ushr (48 - bits)).toInt()
    }
    fun nextInt(bound: Int): Int {
        require(bound > 0)
        if (bound and -bound == bound) return ((bound.toLong() * next(31).toLong()) shr 31).toInt()
        var bits: Int; var v: Int
        do { bits = next(31); v = bits % bound } while (bits - v + (bound - 1) < 0)
        return v
    }
    private companion object { const val MASK = (1L shl 48) - 1 }
}
