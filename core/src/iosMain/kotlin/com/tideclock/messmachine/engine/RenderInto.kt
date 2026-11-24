@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.tideclock.messmachine.engine

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.memcpy

/*
 * iPhone only (asked for by the iOS chat, Nov 23 2026): render straight into the C ring buffer's memory, so Swift does
 * not copy floats one call at a time. One reused buffer per thread (the engine thread); it only grows, so after the
 * first block nothing is created per block.
 */
@kotlin.native.concurrent.ThreadLocal
private var scratch = FloatArray(0)

/** Fill out with frames of interleaved stereo (frames * 2 floats), like render. */
fun Engine.renderInto(out: CPointer<FloatVar>, frames: Int) {
    val n = frames * 2
    if (scratch.size < n) scratch = FloatArray(n)
    val buf = scratch
    render(buf, frames)
    buf.usePinned { p -> memcpy(out, p.addressOf(0), (n * Float.SIZE_BYTES).toULong()) }
}
