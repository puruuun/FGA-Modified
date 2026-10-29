package io.github.fate_grand_automata.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import dagger.hilt.android.AndroidEntryPoint
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.enums.GameServers
import io.github.fate_grand_automata.ui.more.GameAreaPreviewManager
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class TapperService : AccessibilityService() {
    @Inject lateinit var gameAreaPreview: GameAreaPreviewManager

    companion object {
        private val mServiceStarted = mutableStateOf(false)
        val serviceStarted: State<Boolean> = mServiceStarted

        var instance: TapperService? = null
            private set(value) {
                field = value
                mServiceStarted.value = value != null
            }
    }

    override fun onServiceConnected() {
        Timber.i("Accessibility Service bound to system")

        // We only want events from FGO
        serviceInfo = serviceInfo.apply {
            packageNames = GameServers.packageNames.keys.toTypedArray()
        }

        gameAreaPreview.hide()
        instance = this

        super.onServiceConnected()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Timber.i("Accessibility Service unbind")
        Toast.makeText(this, "FGA Accessibility stopped", Toast.LENGTH_SHORT).show()
        if (instance === this) {
            gameAreaPreview.hide()
            instance = null
        }

        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) {
            gameAreaPreview.hide()
            instance = null
        }
        super.onDestroy()
    }

    /**
     * The server of the last FGO app seen in the foreground, or null if none has been seen yet.
     * Only packages in [GameServers.packageNames] are detected, so unlisted FGO versions and FGO
     * running inside an app cloner leave this null.
     */
    var detectedFgoServer: GameServer? = null
        private set

    /**
     * This method is called on any subscribed [AccessibilityEvent] in tapper_service.xml.
     *
     * When the app in the foreground changes, this method will check if the foreground app is one
     * of the FGO APKs.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val foregroundAppName = event.packageName?.toString()
                    ?: return

                GameServers.fromPackageName(foregroundAppName)?.let { server ->
                    Timber.d("Detected FGO: $server")

                    detectedFgoServer = server
                }
            }

            else -> {}
        }
    }

    override fun onInterrupt() {}
}
