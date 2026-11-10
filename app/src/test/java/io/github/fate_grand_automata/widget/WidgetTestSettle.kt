package io.github.fate_grand_automata.widget

import android.os.AsyncTask
import android.os.Looper
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.ThreadPoolExecutor

/**
 * Runs the main looper until the platform's background work for it is finished.
 * RemoteViews apply collection and bitmap work on the AsyncTask pool and post the result to the main looper,
 * so one idle is not enough. This waits for the pool to drain, not for a fixed time, then idles again until
 * a full pass finds nothing left. The long bound only turns a hang into a failure.
 */
@Suppress("DEPRECATION")
internal fun settleMainLooperAndAsyncTasks() {
    val pool = AsyncTask.THREAD_POOL_EXECUTOR as ThreadPoolExecutor
    val deadline = System.nanoTime() + 120_000_000_000L
    while (true) {
        shadowOf(Looper.getMainLooper()).idle()
        var busy = false
        while (pool.activeCount > 0 || pool.queue.isNotEmpty()) {
            busy = true
            check(System.nanoTime() < deadline) { "AsyncTask pool did not drain" }
            Thread.sleep(1)
        }
        if (!busy) {
            shadowOf(Looper.getMainLooper()).idle()
            if (pool.activeCount == 0 && pool.queue.isEmpty()) return
        }
    }
}
