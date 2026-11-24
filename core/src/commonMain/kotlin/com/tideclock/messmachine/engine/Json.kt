package com.tideclock.messmachine.engine

import kotlin.jvm.JvmStatic

/**
 * A small JSON reader and writer, so the engine and the project model have no Android
 * dependencies and can be tested on a plain JVM.
 * Objects become LinkedHashMap, arrays ArrayList, numbers Double, plus String, Boolean and null.
 */
class Json private constructor(private val s: String) {
    private var i = 0

    // Kotlin note: Java's charAt and substring throw past the end; Kotlin/JS would not, so these check like Java does
    private fun at(k: Int): Char {
        if (k < 0 || k >= s.length) throw IndexOutOfBoundsException("index $k, length ${s.length}")
        return s[k]
    }

    private fun ws() {
        while (i < s.length) {
            val c = s[i]
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++
            else break
        }
    }

    private fun value(): Any? {
        if (i >= s.length) throw IllegalArgumentException("Unexpected end of JSON")
        val c = s[i]
        when (c) {
            '{' -> return `object`()
            '[' -> return array()
            '"' -> return string()
            't' -> { expect("true"); return true }
            'f' -> { expect("false"); return false }
            'n' -> { expect("null"); return null }
            else -> return number()
        }
    }

    private fun expect(w: String) {
        if (!s.startsWith(w, i)) throw IllegalArgumentException("Bad JSON at $i")
        i += w.length
    }

    private fun `object`(): MutableMap<String, Any?> {
        val m: MutableMap<String, Any?> = LinkedHashMap()
        i++
        ws()
        if (i < s.length && s[i] == '}') { i++; return m }
        while (true) {
            ws()
            val k = string()
            ws()
            if (at(i) != ':') throw IllegalArgumentException("Expected : at $i")
            i++
            ws()
            m[k] = value()
            ws()
            val c = at(i++)
            if (c == '}') return m
            if (c != ',') throw IllegalArgumentException("Expected , at " + (i - 1))
        }
    }

    private fun array(): MutableList<Any?> {
        val a: MutableList<Any?> = ArrayList()
        i++
        ws()
        if (i < s.length && s[i] == ']') { i++; return a }
        while (true) {
            ws()
            a.add(value())
            ws()
            val c = at(i++)
            if (c == ']') return a
            if (c != ',') throw IllegalArgumentException("Expected , at " + (i - 1))
        }
    }

    private fun string(): String {
        if (at(i) != '"') throw IllegalArgumentException("Expected string at $i")
        i++
        val b = StringBuilder()
        while (true) {
            val c = at(i++)
            if (c == '"') return b.toString()
            if (c == '\\') {
                val e = at(i++)
                when (e) {
                    'n' -> b.append('\n')
                    't' -> b.append('\t')
                    'r' -> b.append('\r')
                    'b' -> b.append('\b')
                    'f' -> b.append('\u000C')
                    'u' -> {
                        if (i + 4 > s.length) throw IndexOutOfBoundsException("index ${i + 4}, length ${s.length}")
                        b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4
                    }
                    else -> b.append(e)
                }
            } else b.append(c)
        }
    }

    private fun number(): Double {
        val a = i
        while (i < s.length) {
            val c = s[i]
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') i++
            else break
        }
        if (a == i) throw IllegalArgumentException("Bad JSON at $i")
        return s.substring(a, i).toDouble()
    }

    companion object {
        @JvmStatic
        fun parse(text: String): Any? {
            val j = Json(text)
            j.ws()
            val v = j.value()
            j.ws()
            if (j.i != j.s.length) throw IllegalArgumentException("Extra text after JSON at " + j.i)
            return v
        }

        /* ---------- writing ---------- */

        @JvmStatic
        fun write(v: Any?): String {
            val b = StringBuilder()
            write(b, v)
            return b.toString()
        }

        @Suppress("UNCHECKED_CAST")
        private fun write(b: StringBuilder, v: Any?) {
            if (v == null) b.append("null")
            else if (v is String) quote(b, v)
            else if (v is Boolean) b.append(v.toString())
            else if (v is Number) {
                val d = v.toDouble()
                if (d == kotlin.math.round(d) && kotlin.math.abs(d) < 1e15) b.append(d.toLong())
                else b.append(d)
            } else if (v is Map<*, *>) {
                b.append('{')
                var first = true
                for (e in (v as Map<String, Any?>).entries) {
                    if (!first) b.append(',')
                    first = false
                    quote(b, e.key)
                    b.append(':')
                    write(b, e.value)
                }
                b.append('}')
            } else if (v is List<*>) {
                b.append('[')
                var first = true
                for (o in v as List<Any?>) {
                    if (!first) b.append(',')
                    first = false
                    write(b, o)
                }
                b.append(']')
            } else quote(b, v.toString())
        }

        private fun quote(b: StringBuilder, s: String) {
            b.append('"')
            for (k in 0 until s.length) {
                val c = s[k]
                when (c) {
                    '"' -> b.append("\\\"")
                    '\\' -> b.append("\\\\")
                    '\n' -> b.append("\\n")
                    '\r' -> b.append("\\r")
                    '\t' -> b.append("\\t")
                    else ->
                        if (c.code < 0x20) b.append("\\u" + hexOf(c.code).padStart(4, '0'))
                        else b.append(c)
                }
            }
            b.append('"')
        }

        /* ---------- helpers for reading loose maps ---------- */

        @JvmStatic fun num(o: Any?, def: Double): Double { return if (o is Number) o.toDouble() else def }
        @JvmStatic fun str(o: Any?, def: String): String { return if (o is String) o else def }
        @JvmStatic fun bool(o: Any?, def: Boolean): Boolean { return if (o is Boolean) o else def }

        @JvmStatic
        fun map(o: Any?): MutableMap<String, Any?> { return if (o is Map<*, *>) cast(o) else LinkedHashMap() }

        @JvmStatic
        fun list(o: Any?): MutableList<Any?> { return if (o is List<*>) cast(o) else ArrayList() }

        // Kotlin note: Java's unchecked (Map<String, Object>) cast hands back any Map as it is, read-only Kotlin maps
        // included; `as MutableMap` would refuse those on the JVM, so the cast goes through an erased generic
        @Suppress("UNCHECKED_CAST")
        private fun <T> cast(o: Any?): T = o as T
    }
}
