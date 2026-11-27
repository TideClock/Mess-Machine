package com.tideclock.messmachine

import com.tideclock.messmachine.platform.fmt
import com.tideclock.messmachine.platform.jround
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** The shared module's stand-ins for Math.round and "%.Nf".format must say what Java said (CMP step 4h). */
class JavaMathTest {
    @Test fun sameAsJava() {
        val r = java.util.Random(3)
        val xs = listOf(0.0, 0.5, 1.5, 2.5, -0.5, -1.5, 0.125, 2.675, 59.999, 120.04, 1e-3, 33.3333, -2.25) + List(20000) { (r.nextDouble() - .3) * 400 }
        for (x in xs) {
            assertEquals("round $x", Math.round(x), jround(x))
            assertEquals("round $x f", Math.round(x.toFloat()), jround(x.toFloat()))
            for (d in 0..2) assertEquals("fmt $x $d", String.format(Locale.US, "%.${d}f", x), fmt(x, d))
        }
    }
}
