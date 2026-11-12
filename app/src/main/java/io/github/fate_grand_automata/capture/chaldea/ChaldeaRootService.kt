package io.github.fate_grand_automata.capture.chaldea

import android.content.Intent
import android.os.IBinder
import androidx.annotation.Keep
import com.topjohnwu.superuser.ipc.RootService

/**
 * Runs [ChaldeaSyncService] as uid 0 (issue #362). libsu starts this class itself in a root process, so it is not
 * declared in the manifest. It is not a daemon: the process exits when the last client unbinds, and [onUnbind]
 * returns false so nothing asks to be rebound.
 */
@Keep
class ChaldeaRootService : RootService() {
    override fun onBind(intent: Intent): IBinder = ChaldeaSyncService()

    override fun onUnbind(intent: Intent): Boolean = false
}
