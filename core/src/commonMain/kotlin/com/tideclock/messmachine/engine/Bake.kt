package com.tideclock.messmachine.engine

import kotlin.jvm.JvmStatic
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Bake (SE-3): renders a sound at another pitch with its length kept, ahead of time, so playing it costs what
 * Stretch costs. A phase vocoder stretches the sound in time by the pitch ratio, then a cubic resample plays the
 * stretch back at that ratio: same length, new pitch. Identity phase locking keeps tones clean (Laroche and
 * Dolson), and frames where a hit lands take their phases straight from the sound, so drums stay sharp.
 * Plain Java with no allocation per sample, run on a background thread, never on the audio thread.
 */
object Bake {
    /** Semitones baked either side of the sound's own pitch (25 versions with the original). */
    const val RANGE = 12
    /** Longest sound that bakes: 25 versions of 6 s is about 26 MB of floats at 44.1 kHz. */
    const val MAX_SECONDS = 6.0

    private const val N = 2048
    private const val HOP = N / 4

    /**
     * All the versions, index semis + RANGE; the centre slot stays null (the sound itself).
     * stop was a java.util.function.BooleanSupplier; now a plain function (null for never).
     */
    @JvmStatic
    fun all(x: FloatArray, stop: (() -> Boolean)?): Array<FloatArray?>? {
        val out = arrayOfNulls<FloatArray>(2 * RANGE + 1)
        for (s in -RANGE..RANGE) {
            if (s == 0) continue
            if (stop != null && stop()) return null
            out[s + RANGE] = shift(x, 2.0.pow(s / 12.0))
        }
        return out
    }

    /** x at pitch ratio p, the same length as x. */
    @JvmStatic
    fun shift(x: FloatArray, p: Double): FloatArray {
        val st = stretch(x, p)
        // going up, the resample reads the stretch faster: cut what would fold back above the new Nyquist first
        if (p > 1.02) lowpass(st, .47 / p)
        val y = FloatArray(x.size)
        for (i in y.indices) {
            val t = i * p
            val k = t.toInt()
            val f = t - k
            val a = at(st, k - 1)
            val b = at(st, k)
            val c = at(st, k + 1)
            val d = at(st, k + 2)
            // Catmull-Rom
            y[i] = (b + .5 * f * (c - a + f * (2 * a - 5 * b + 4 * c - d + f * (3 * (b - c) + d - a)))).toFloat()
        }
        return y
    }

    private fun at(a: FloatArray, i: Int): Float { return if (i < 0 || i >= a.size) 0f else a[i] }

    /** x stretched in time by r (length about x.length * r), pitch kept. */
    @JvmStatic
    fun stretch(x: FloatArray, r: Double): FloatArray {
        val outLen = ceil(x.size * r).toInt() + N
        val y = FloatArray(outLen + N)
        val win = DoubleArray(N)
        for (i in 0 until N) win[i] = .5 - .5 * cos(2 * PI * i / N)
        val H = N / 2 + 1
        val re = DoubleArray(N)
        val im = DoubleArray(N)
        val mag = DoubleArray(H)
        val ph = DoubleArray(H)
        val prevPh = DoubleArray(H)
        val outPh = DoubleArray(H)
        val prevMag = DoubleArray(H)
        val peak = BooleanArray(H)
        val owner = IntArray(H)
        val ha = HOP / r                       // analysis hop
        var norm = 0.0                         // window overlap sum for the synthesis hop
        run { var i = 0; while (i < N) { norm += win[i] * win[i]; i += HOP } }
        norm = 1 / maxOf(1e-9, norm * 1.0)
        var first = true
        var fluxAvg = 0.0
        val frames = ceil((x.size + N / 2.0) / ha).toInt() + 1
        for (fi in 0 until frames) {
            val ap = fi * ha - N / 2.0         // analysis position, fractional
            val a0 = floor(ap).toInt()
            val af = ap - a0
            for (i in 0 until N) {
                val j = a0 + i
                val v = (1 - af) * at(x, j) + af * at(x, j + 1)
                re[i] = v * win[i]
                im[i] = 0.0
            }
            fftshiftZero(re)
            fft(re, im, false)
            var flux = 0.0
            var tot = 0.0
            for (k in 0 until H) {
                mag[k] = hypot(re[k], im[k])
                ph[k] = atan2(im[k], re[k])
                val d = mag[k] - prevMag[k]
                if (d > 0) flux += d
                tot += mag[k]
            }
            // a hit: energy rising much faster than usual; reset to the sound's own phases so the attack stays put
            val hit = !first && tot > 1e-6 && flux > .35 * tot && flux > 3 * fluxAvg
            fluxAvg += (flux - fluxAvg) * .2
            if (first || hit) {
                ph.copyInto(outPh, 0, 0, H)
            } else {
                // peaks: local maxima; every other bin follows its nearest peak's phase rotation
                for (k in 0 until H) peak[k] = k > 1 && k < H - 2 && mag[k] > mag[k - 1] && mag[k] >= mag[k + 1] && mag[k] > mag[k - 2] && mag[k] >= mag[k + 2]
                var last = -1
                for (k in 0 until H) {
                    if (peak[k]) {
                        val omega = 2 * PI * k / N
                        var dp = ph[k] - prevPh[k] - omega * ha
                        dp -= 2 * PI * round(dp / (2 * PI))
                        val inst = omega + dp / ha
                        outPh[k] += inst * HOP
                        // fill the region since the last peak: bins up to the midpoint go to the last peak, the rest to this one
                        val from = if (last < 0) 0 else (last + k) / 2 + 1
                        for (q in from until k) owner[q] = k
                        if (last >= 0) for (q in last + 1..(last + k) / 2) owner[q] = last
                        owner[k] = k
                        last = k
                    }
                }
                if (last < 0) {
                    // no peaks at all (silence or noise): plain phase vocoder for every bin
                    for (k in 0 until H) {
                        val omega = 2 * PI * k / N
                        var dp = ph[k] - prevPh[k] - omega * ha
                        dp -= 2 * PI * round(dp / (2 * PI))
                        outPh[k] += (omega + dp / ha) * HOP
                    }
                } else {
                    for (q in last + 1 until H) owner[q] = last
                    for (k in 0 until H) {
                        val o = owner[k]
                        if (o != k) outPh[k] = outPh[o] + (ph[k] - ph[o])
                    }
                }
            }
            first = false
            ph.copyInto(prevPh, 0, 0, H)
            mag.copyInto(prevMag, 0, 0, H)
            for (k in 0 until H) {
                outPh[k] = wrap(outPh[k])
                re[k] = mag[k] * cos(outPh[k])
                im[k] = mag[k] * sin(outPh[k])
            }
            for (k in H until N) { re[k] = re[N - k]; im[k] = -im[N - k] }
            fft(re, im, true)
            fftshiftZero(re)
            val sp = fi * HOP - N / 2
            for (i in 0 until N) {
                val j = sp + i
                if (j >= 0 && j < y.size) y[j] += (re[i] * win[i] * norm).toFloat()
            }
        }
        val keep = jround(x.size * r).toInt()
        return y.copyOf(minOf(y.size, keep))
    }

    private fun wrap(a: Double): Double { return a - 2 * PI * round(a / (2 * PI)) }

    /** Centre the frame on sample 0 so phases measure from the frame's middle (steadier peaks). */
    private fun fftshiftZero(a: DoubleArray) {
        val h = a.size / 2
        for (i in 0 until h) { val t = a[i]; a[i] = a[i + h]; a[i + h] = t }
    }

    /** In-place radix-2 complex FFT; inverse scales by 1/n. */
    // public (was package-private): MouthTracker and Synth use it
    @JvmStatic
    fun fft(re: DoubleArray, im: DoubleArray, inv: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = 2 * PI / len * (if (inv) 1 else -1)
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val u = i + k
                    val v = i + k + len / 2
                    val xr = re[v] * cr - im[v] * ci
                    val xi = re[v] * ci + im[v] * cr
                    re[v] = re[u] - xr; im[v] = im[u] - xi
                    re[u] += xr; im[u] += xi
                    val t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t
                }
                i += len
            }
            len = len shl 1
        }
        if (inv) for (i in 0 until n) { re[i] /= n; im[i] /= n }
    }

    /** A 63-tap windowed-sinc lowpass at fc (a fraction of the sample rate), in place. */
    private fun lowpass(y: FloatArray, fc: Double) {
        val M = 63
        val h = M / 2
        val c = DoubleArray(M)
        var s = 0.0
        for (i in 0 until M) {
            val n = i - h
            val sinc = if (n == 0) 2 * fc else sin(2 * PI * fc * n) / (PI * n)
            c[i] = sinc * (.42 - .5 * cos(2 * PI * i / (M - 1)) + .08 * cos(4 * PI * i / (M - 1)))
            s += c[i]
        }
        for (i in 0 until M) c[i] /= s
        val src = y.copyOf()
        for (i in y.indices) {
            var acc = 0.0
            for (k in 0 until M) acc += c[k] * at(src, i + k - h)
            y[i] = acc.toFloat()
        }
    }

    /** Java's Math.round(double): roundToLong, but NaN gives 0 instead of throwing. */
    private fun jround(d: Double): Long = if (d.isNaN()) 0L else d.roundToLong()
}
