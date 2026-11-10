package com.tideclock.messmachine

import com.tideclock.messmachine.engine.Engine

/** One thing an XY direction can move, with the amount it reaches at the edge by default and its range. */
data class XyTarget(val id: Int, val name: String, val def: Float, val lo: Float, val hi: Float, val unit: String)

/** Everything an XY direction can move. Filter closes as it grows; pitch is in semitones. */
val XY_TARGETS = listOf(
    XyTarget(-1, "Nothing", 0f, 0f, 1f, ""),
    XyTarget(Engine.T_PITCH, "Pitch", 7f, -24f, 24f, "st"),
    XyTarget(Engine.T_FILTER, "Filter", 1f, 0f, 1f, ""),
    XyTarget(Engine.T_RES, "Resonance", 1f, 0f, 1f, ""),
    XyTarget(Engine.T_VOL, "Volume", -1f, -1f, 1f, ""),
    XyTarget(Engine.T_PAN, "Pan", 1f, -1f, 1f, ""),
    XyTarget(Engine.T_ATT, "Attack", .5f, 0f, 1f, "s"),
    XyTarget(Engine.T_REL, "Release", 1f, 0f, 2f, "s"),
    XyTarget(Engine.T_START, "Start point", .8f, 0f, .95f, ""),
    XyTarget(Engine.T_RVB, "Reverb", 1f, 0f, 1f, ""),
    XyTarget(Engine.T_ECHO, "Echo", 1f, 0f, 1f, ""),
    XyTarget(Engine.T_DIRT, "Dirty", 1f, 0f, 1f, ""),
    XyTarget(Engine.T_CRUSH, "Crush", 1f, 0f, 1f, ""),
    XyTarget(Engine.T_CHORUS, "Chorus", 1f, 0f, 1f, ""),
    XyTarget(Engine.T_WOB, "Wobble", 1f, 0f, 1f, ""),
    XyTarget(Engine.T_GLIDE, "Glide time", .5f, 0f, 1f, "s"),
    XyTarget(Engine.T_ROLL, "Repeat roll", 1f, 0f, 1f, ""),
    XyTarget(Engine.T_GATE, "Gate", 1f, 0f, 1f, ""),
    XyTarget(Engine.T_RING, "Ring mod", 1f, 0f, 1f, ""),
    XyTarget(Engine.T_STUT, "Stutter", 1f, 0f, 1f, ""),
    XyTarget(Engine.T_TAPE, "Tape stop", 1f, 0f, 1f, ""),
)

fun xyTarget(id: Int) = XY_TARGETS.firstOrNull { it.id == id } ?: XY_TARGETS[0]

/** One direction: what it moves and how far at the edge. */
data class XyDir(val target: Int = -1, val to: Float = 0f)

/**
 * A pad's XY setup. Two-way: zero at the bottom left, dirs[0] is X and dirs[1] is Y. Four-way: zero in
 * the middle, dirs are right, up, left, down.
 */
data class XySetup(
    val name: String = "Sweep",
    val four: Boolean = false,
    val dirs: List<XyDir> = listOf(XyDir(Engine.T_FILTER, 1f), XyDir(Engine.T_RES, 1f), XyDir(), XyDir()),
    val spring: Boolean = true,
    val ring: Boolean = false,
    val size: Int = 1,
    val steps: Boolean = true,
) {
    fun code(): String = listOf(name.replace("|", " ").replace(";", " "), if (four) "4" else "2", if (spring) "s" else "k", if (ring) "o" else "q", "$size", if (steps) "t" else "f",
        dirs.joinToString(",") { "${it.target}:${it.to}" }).joinToString("|")

    /** Turn a finger position into what each target gets. fx, fy: two-way 0..1 from bottom left; four-way -1..1 from the middle. */
    fun values(fx: Float, fy: Float): Map<Int, Float> {
        val out = HashMap<Int, Float>()
        fun add(d: XyDir, amt: Float) { if (d.target >= 0 && amt > 0f) out[d.target] = (out[d.target] ?: 0f) + d.to * amt.coerceIn(0f, 1f) }
        if (!four) { add(dirs[0], fx); add(dirs[1], fy) }
        else {
            val dz = .12f
            fun z(v: Float) = if (kotlin.math.abs(v) < dz) 0f else (kotlin.math.abs(v) - dz) / (1 - dz)
            if (fx > 0) add(dirs[0], z(fx)) else add(dirs[2], z(fx))
            if (fy > 0) add(dirs[1], z(fy)) else add(dirs[3], z(fy))
        }
        return out
    }

    /** Every target this setup can move. */
    fun targets(): Set<Int> = dirs.take(if (four) 4 else 2).map { it.target }.filter { it >= 0 }.toSet()

    companion object {
        fun parse(s: String): XySetup? = try {
            val f = s.split("|")
            val dirs = f[6].split(",").map { d -> d.split(":").let { XyDir(it[0].toInt(), it[1].toFloat()) } }.toMutableList()
            while (dirs.size < 4) dirs.add(XyDir())
            XySetup(f[0], f[1] == "4", dirs.take(4), f[2] == "s", f[3] == "o", f[4].toInt().coerceIn(0, 2), f[5] == "t")
        } catch (_: Exception) { null }

        val PRESETS = listOf(
            XySetup("Sweep", false, listOf(XyDir(Engine.T_FILTER, 1f), XyDir(Engine.T_RES, 1f), XyDir(), XyDir())),
            XySetup("Dub", true, listOf(XyDir(Engine.T_ECHO, 1f), XyDir(Engine.T_RVB, 1f), XyDir(Engine.T_FILTER, .9f), XyDir(Engine.T_CRUSH, .8f))),
            XySetup("Bend", true, listOf(XyDir(Engine.T_PITCH, 7f), XyDir(Engine.T_CHORUS, 1f), XyDir(Engine.T_PITCH, -12f), XyDir(Engine.T_WOB, 1f))),
            XySetup("Lo-fi", false, listOf(XyDir(Engine.T_CRUSH, 1f), XyDir(Engine.T_DIRT, 1f), XyDir(), XyDir())),
            XySetup("Scrub", false, listOf(XyDir(Engine.T_START, .9f), XyDir(Engine.T_PITCH, 12f), XyDir(), XyDir())),
        )
    }
}
