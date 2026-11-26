package com.tideclock.messmachine

import android.os.Handler
import android.os.Looper
import androidx.core.os.HandlerCompat
import com.tideclock.messmachine.platform.MainThread
import com.tideclock.messmachine.platform.Worker
import java.util.concurrent.Executors

/** The screen's thread on Android: a Handler on the main Looper; the function itself is the token cancel looks for. */
class AndroidMainThread : MainThread {
    private val h = Handler(Looper.getMainLooper())
    override fun post(f: () -> Unit) { HandlerCompat.postDelayed(h, { f() }, f, 0) }
    override fun postDelayed(f: () -> Unit, ms: Long) { HandlerCompat.postDelayed(h, { f() }, f, ms) }
    override fun cancel(f: () -> Unit) { h.removeCallbacksAndMessages(f) }
}

/** One background thread, as AppState always had (Executors.newSingleThreadExecutor). */
class AndroidWorker : Worker {
    private val e = Executors.newSingleThreadExecutor()
    override fun execute(f: () -> Unit) { e.execute { f() } }
}
