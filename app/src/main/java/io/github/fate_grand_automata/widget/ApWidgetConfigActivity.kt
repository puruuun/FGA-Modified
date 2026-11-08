package io.github.fate_grand_automata.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.capture.CaptureAccount
import io.github.fate_grand_automata.capture.CaptureTracker
import io.github.fate_grand_automata.ui.FgaScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Configuration screen of the account AP widget: pick one stored account, which is then bound to
 * this appWidgetId. Without log collection or accounts it explains why and returns RESULT_CANCELED,
 * so the host does not add the widget.
 */
class ApWidgetConfigActivity : ComponentActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appWidgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Cancelled unless an account is chosen, e.g. when the user backs out.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            FgaScreen {
                val context = LocalContext.current
                val choices by produceState<ApWidgetChoices?>(null, context) {
                    value = withContext(Dispatchers.IO) {
                        val enabled = captureEnabled(context)
                        if (!enabled || Build.VERSION.SDK_INT < 29) {
                            apWidgetChoices(enabled, Build.VERSION.SDK_INT, emptyList())
                        } else {
                            CaptureTracker.initSummaries(context)
                            apWidgetChoices(enabled, Build.VERSION.SDK_INT, CaptureTracker.accountSummaries(System.currentTimeMillis() / 1000))
                        }
                    }
                }
                ApWidgetConfigContent(choices, onChoose = ::choose, onClose = ::finish)
            }
        }
    }

    private fun choose(account: CaptureAccount) {
        ApWidgetBindings(this).bind(appWidgetId, account.id)
        ApWidgetRefresh.schedule(this)
        // Widgets with a configuration screen get no initial update from the host.
        ApWidgetRefresh.requestUpdate(this)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        finish()
    }
}

@Composable
fun ApWidgetConfigContent(
    choices: ApWidgetChoices?,
    onChoose: (CaptureAccount) -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.widget_ap_config_title), style = MaterialTheme.typography.titleLarge)
        when (choices) {
            null -> Unit
            ApWidgetChoices.Unavailable -> {
                Text(stringResource(R.string.widget_ap_config_unavailable), Modifier.testTag("widgetConfigMessage"))
                Button(onClick = onClose) { Text(stringResource(R.string.widget_ap_config_close)) }
            }

            is ApWidgetChoices.Accounts -> LazyColumn {
                items(choices.accounts, key = { it.id }) { account ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onChoose(account) }.padding(vertical = 14.dp)
                            .testTag("widgetAccount:${account.id}"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(account.displayName, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium)
                        Text(account.server, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
