package io.github.fate_grand_automata.ui.main

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import dagger.hilt.android.AndroidEntryPoint
import io.github.fate_grand_automata.capture.CaptureController
import io.github.fate_grand_automata.ui.pref_support.SupportViewModel
import io.github.fate_grand_automata.ui.prefs.initializeApplicationLanguage
import io.github.fate_grand_automata.util.CutoutManager
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject
    lateinit var cutoutManager: CutoutManager

    val vm: MainScreenViewModel by viewModels()
    val supportVm: SupportViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeApplicationLanguage()
        // A recreated activity keeps its original intent, which already ran once.
        vm.widgetRequests.consume(intent, restored = savedInstanceState != null)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            FgaApp(
                vm = vm,
                supportVm = supportVm
            )
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        cutoutManager.applyCutout(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        vm.widgetRequests.consume(intent)
    }

    override fun onStart() {
        super.onStart()
        vm.activityStarted()
        CaptureController.ensureProxy(this)
    }
}
