package io.github.fate_grand_automata.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import io.github.fate_grand_automata.runner.ScriptRunnerService
import timber.log.Timber

/**
 * Translucent UI-less activity used to request MediaProjection consent again from
 * [ScriptRunnerService] when screen capture stopped while the overlay stayed alive.
 */
class ProjectionConsentActivity : ComponentActivity() {
    private var resultDelivered = false

    private val startMediaProjection = registerForActivityResult(StartMediaProjection()) { intent ->
        resultDelivered = true
        if (intent != null) {
            ScriptRunnerService.mediaProjectionToken = intent
        } else {
            ScriptRunnerService.onMediaProjectionConsentCancelled(this)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        resultDelivered = savedInstanceState?.getBoolean(KEY_RESULT_DELIVERED) == true

        if (savedInstanceState == null) {
            try {
                startMediaProjection.launch(Unit)
            } catch (e: Exception) {
                resultDelivered = true
                Timber.e(e, "Failed to launch screen capture consent")
                ScriptRunnerService.onMediaProjectionConsentLaunchFailed(this)
                finish()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_RESULT_DELIVERED, resultDelivered)
    }

    override fun onDestroy() {
        if (isFinishing && !isChangingConfigurations && !resultDelivered) {
            ScriptRunnerService.onMediaProjectionConsentAbandoned()
        }
        super.onDestroy()
    }

    companion object {
        private const val KEY_RESULT_DELIVERED = "result_delivered"

        fun createIntent(context: Context): Intent =
            Intent(context, ProjectionConsentActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}
