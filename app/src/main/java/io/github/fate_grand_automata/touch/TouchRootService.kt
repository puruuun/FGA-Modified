package io.github.fate_grand_automata.touch

import android.content.Intent
import android.os.IBinder
import androidx.annotation.Keep
import com.topjohnwu.superuser.ipc.RootService

/**
 * Runs [TouchInjector] as uid 0 (issue #387). Like [io.github.fate_grand_automata.capture.chaldea.ChaldeaRootService],
 * libsu starts this class directly in a root process and exits when the last client unbinds.
 */
@Keep
class TouchRootService : RootService() {
    private val injector = TouchInjector()

    override fun onBind(intent: Intent): IBinder = TouchInjectorService(injector, onDestroyProcess = {})

    override fun onUnbind(intent: Intent): Boolean = false
}
