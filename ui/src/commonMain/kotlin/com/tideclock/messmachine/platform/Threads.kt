package com.tideclock.messmachine.platform

/**
 * The screen's thread (CMP step 4, the Device interface's main-thread poster). A posted function is also its own
 * handle: cancel(f) drops every pending run of that same f, so keep it in a val when it may need cancelling.
 */
interface MainThread {
    fun post(f: () -> Unit)
    fun postDelayed(f: () -> Unit, ms: Long)
    fun cancel(f: () -> Unit)
}

/** One background thread for slow work (saving, loading, baking), run in order (CMP step 4). */
interface Worker {
    fun execute(f: () -> Unit)
}
