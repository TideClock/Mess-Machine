package com.tideclock.messmachine

import com.tideclock.messmachine.ui.JavaRandom
import org.junit.Assert.assertEquals
import org.junit.Test

/** The shared module's JavaRandom must give java.util.Random's exact draws (the grit speckle depends on it). */
class JavaRandomTest {
    @Test fun sameDraws() {
        for (seed in listOf(7L, 0L, -3L, 123456789L)) {
            val a = java.util.Random(seed); val b = JavaRandom(seed)
            for (i in 0 until 20000) { val bound = intArrayOf(100, 7, 64, 1000, 1)[i % 5]; assertEquals("seed $seed draw $i", a.nextInt(bound), b.nextInt(bound)) }
        }
    }
}
