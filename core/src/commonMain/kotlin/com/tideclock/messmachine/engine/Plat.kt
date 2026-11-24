@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.tideclock.messmachine.engine

import kotlin.concurrent.atomics.AtomicReference
import kotlin.time.TimeSource

/*
 * The few things the engine needs that plain Kotlin does not give every platform the same way. Everything here is
 * common Kotlin (no expect/actual), so the engine builds for the JVM (Android), iOS and the web with no platform code.
 */

private val clockZero = TimeSource.Monotonic.markNow()

/** Monotonic nanoseconds from an arbitrary zero. Only differences mean anything. Allocates nothing. */
fun nanoTime(): Long = clockZero.elapsedNow().inWholeNanoseconds

/** The time now in ISO-8601 (UTC), for the mess's "at"; "" if the platform cannot say. */
@OptIn(kotlin.time.ExperimentalTime::class)
fun isoNow(): String = try { kotlin.time.Clock.System.now().toString() } catch (t: Throwable) { "" }

/** What the engine throws when a file cannot be read or decoded (java.io.IOException on the JVM side of old). */
class EngineIOException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Reads a whole file: a bundled sound ("sounds/...") or a full path on the device ("/..."). null when missing. */
fun interface SoundFiles {
    @Throws(Exception::class)
    fun read(path: String): ByteArray?
}

/**
 * java.util.Random, bit for bit: the same seed gives the same numbers on every platform. Chance steps are rolled
 * with seed 11 and synth noise with 505, so this must never change.
 */
class JavaRandom(seed: Long) {
    private var s = 0L
    private var nextNextGaussian = 0.0
    private var haveNextNextGaussian = false

    init { setSeed(seed) }

    constructor() : this(nanoTime() xor 8682522807148012L * 1181783497276652981L)

    fun setSeed(seed: Long) { s = (seed xor MULT) and MASK; haveNextNextGaussian = false }

    private fun next(bits: Int): Int {
        s = (s * MULT + ADD) and MASK
        return (s ushr (48 - bits)).toInt()
    }

    fun nextInt(): Int = next(32)

    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive" }
        var r = next(31)
        val m = bound - 1
        if ((bound and m) == 0) r = ((bound.toLong() * r.toLong()) shr 31).toInt()
        else {
            var u = r
            while (true) { r = u % bound; if (u - r + m < 0) u = next(31) else break }
        }
        return r
    }

    fun nextLong(): Long = (next(32).toLong() shl 32) + next(32)
    fun nextBoolean(): Boolean = next(1) != 0
    fun nextFloat(): Float = next(24) / (1 shl 24).toFloat()
    fun nextDouble(): Double = ((next(26).toLong() shl 27) + next(27)) * DOUBLE_UNIT

    fun nextGaussian(): Double {
        if (haveNextNextGaussian) { haveNextNextGaussian = false; return nextNextGaussian }
        var v1: Double; var v2: Double; var q: Double
        do {
            v1 = 2 * nextDouble() - 1
            v2 = 2 * nextDouble() - 1
            q = v1 * v1 + v2 * v2
        } while (q >= 1 || q == 0.0)
        val mul = kotlin.math.sqrt(-2 * kotlin.math.ln(q) / q)
        nextNextGaussian = v2 * mul
        haveNextNextGaussian = true
        return v1 * mul
    }

    private companion object {
        const val MULT = 0x5DEECE66DL
        const val ADD = 0xBL
        const val MASK = 0xFFFFFFFFFFFFL
        const val DOUBLE_UNIT = 1.1102230246251565E-16  // 2^-53
    }
}

/**
 * A queue many threads add to and one thread drains (the UI to the audio thread, or back). Lock-free: add never
 * blocks, and poll only touches the consumer's own list. Replaces java.util.concurrent.ConcurrentLinkedQueue.
 * poll and clear must only be called from the one consumer thread.
 */
class ConcurrentQueue<T : Any> {
    private class Node<T>(val v: T, val next: Node<T>?)
    private val inbox = AtomicReference<Node<T>?>(null)
    private val out = ArrayDeque<T>()

    fun add(v: T): Boolean {
        while (true) {
            val h = inbox.load()
            if (inbox.compareAndSet(h, Node(v, h))) return true
        }
    }

    fun poll(): T? {
        if (out.isEmpty()) {
            var n = inbox.exchange(null) ?: return null
            // the inbox is newest first; put it in the order it was added
            val start = out.size
            while (true) { out.add(n.v); n = n.next ?: break }
            out.subList(start, out.size).reverse()
        }
        return out.removeFirstOrNull()
    }

    fun isEmpty(): Boolean = out.isEmpty() && inbox.load() == null
    fun clear() { inbox.store(null); out.clear() }
}

/**
 * A map that any thread can read without locks while another changes it: every change publishes a fresh copy.
 * For maps that are read often and changed rarely (the sound library). Replaces ConcurrentHashMap.
 */
class CowMap<K, V> : AbstractMutableMap<K, V>() {
    private val ref = AtomicReference<Map<K, V>>(LinkedHashMap())

    /** The map as it is right now; later changes do not show in it. */
    fun snapshot(): Map<K, V> = ref.load()

    override val size: Int get() = ref.load().size
    override fun isEmpty(): Boolean = ref.load().isEmpty()
    override fun containsKey(key: K): Boolean = ref.load().containsKey(key)
    override fun containsValue(value: V): Boolean = ref.load().containsValue(value)
    override fun get(key: K): V? = ref.load()[key]

    private inline fun change(f: (LinkedHashMap<K, V>) -> Unit) {
        while (true) {
            val old = ref.load()
            val n = LinkedHashMap(old)
            f(n)
            if (ref.compareAndSet(old, n)) return
        }
    }

    override fun put(key: K, value: V): V? { val was = ref.load()[key]; change { it[key] = value }; return was }
    fun putIfAbsent(key: K, value: V): V? {
        while (true) {
            val old = ref.load()
            old[key]?.let { return it }
            val n = LinkedHashMap(old); n[key] = value
            if (ref.compareAndSet(old, n)) return null
        }
    }
    override fun remove(key: K): V? { val was = ref.load()[key]; change { it.remove(key) }; return was }
    override fun clear() { ref.store(LinkedHashMap()) }

    /** A copy of the entries: changing it does not change the map. */
    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() = LinkedHashMap(ref.load()).entries
    override val keys: MutableSet<K> get() = LinkedHashSet(ref.load().keys)
    override val values: MutableCollection<V> get() = ArrayList(ref.load().values)
}

/**
 * A list any thread can read without locks while another changes it: every change publishes a fresh copy, and
 * iterating walks the copy that was current when it started. Replaces CopyOnWriteArrayList.
 */
class CowList<T> : AbstractMutableList<T>() {
    private val ref = AtomicReference<List<T>>(emptyList())

    fun snapshot(): List<T> = ref.load()

    override val size: Int get() = ref.load().size
    override fun get(index: Int): T = ref.load()[index]
    override fun iterator(): MutableIterator<T> = ArrayList(ref.load()).iterator()
    override fun contains(element: T): Boolean = ref.load().contains(element)
    override fun indexOf(element: T): Int = ref.load().indexOf(element)

    private inline fun <R> change(f: (ArrayList<T>) -> R): R {
        while (true) {
            val old = ref.load()
            val n = ArrayList(old)
            val r = f(n)
            if (ref.compareAndSet(old, n)) return r
        }
    }

    override fun add(index: Int, element: T) = change { it.add(index, element) }
    override fun add(element: T): Boolean = change { it.add(element) }
    override fun removeAt(index: Int): T = change { it.removeAt(index) }
    override fun remove(element: T): Boolean = change { it.remove(element) }
    override fun set(index: Int, element: T): T = change { it.set(index, element) }
    override fun clear() { ref.store(emptyList()) }
    fun removeWhere(p: (T) -> Boolean): Boolean = change { it.removeAll(p) }
}

/** A counter any thread can change. Replaces java.util.concurrent.atomic.AtomicInteger. */
class AtomicCounter(initial: Int = 0) {
    private val a = kotlin.concurrent.atomics.AtomicInt(initial)
    fun get(): Int = a.load()
    fun set(v: Int) = a.store(v)
    fun incrementAndGet(): Int = a.addAndFetch(1)
    fun decrementAndGet(): Int = a.addAndFetch(-1)
    fun addAndGet(d: Int): Int = a.addAndFetch(d)
}

/** Java's String.format("%.Nf", v) for N >= 0: shortest digits of the double, rounded half up, like Java does. */
fun fixed(v: Double, decimals: Int): String {
    if (v.isNaN()) return "NaN"
    if (v.isInfinite()) return if (v > 0) "Infinity" else "-Infinity"
    val neg = v < 0 || (v == 0.0 && 1.0 / v < 0)
    // the shortest decimal digits that read back as v (what Double.toString gives on every Kotlin platform)
    var t = (if (neg) -v else v).toString().lowercase()
    var exp = 0
    val e = t.indexOf('e')
    if (e >= 0) { exp = t.substring(e + 1).toInt(); t = t.substring(0, e) }
    val dot = t.indexOf('.')
    val intPart = if (dot >= 0) t.substring(0, dot) else t
    val frac = if (dot >= 0) t.substring(dot + 1) else ""
    var digits = (intPart + frac).trimStart('0')
    var point = intPart.length + exp - ((intPart + frac).length - (intPart + frac).trimStart('0').length)
    if (digits.isEmpty()) { digits = "0"; point = 1 }
    // digits d1 d2 d3 ... with the decimal point after `point` digits (point may be <= 0 or past the end)
    val keep = point + decimals
    val sb = StringBuilder()
    if (keep < 0) {
        sb.append('0')
    } else {
        val padded = if (digits.length < keep) digits + "0".repeat(keep - digits.length) else digits
        val head = padded.substring(0, keep)
        val roundUp = keep < padded.length && padded[keep] >= '5'
        var num = if (head.isEmpty()) "0" else head
        if (roundUp) {
            val c = num.toCharArray()
            var i = c.size - 1
            while (i >= 0) { if (c[i] == '9') { c[i] = '0'; i-- } else { c[i] = c[i] + 1; break } }
            num = (if (i < 0) "1" else "") + c.concatToString()
        }
        sb.append(num)
    }
    var s = sb.toString()
    // place the point: s holds the number times 10^decimals
    if (s.length <= decimals) s = "0".repeat(decimals - s.length + 1) + s
    val ip = s.substring(0, s.length - decimals).trimStart('0').ifEmpty { "0" }
    val fp = s.substring(s.length - decimals)
    val body = if (decimals > 0) "$ip.$fp" else ip
    // Java keeps the sign even when the rounded number is zero ("-0.0")
    return if (neg) "-$body" else body
}

/** Java's Integer.toHexString: the int's bits as unsigned hex, lowercase. */
fun hexOf(v: Int): String = v.toUInt().toString(16)
