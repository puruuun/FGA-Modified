package io.github.fate_grand_automata.ui

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R

/**
 * Pops the current screen, or null when there is nothing to go back to. `FgaApp` provides it
 * from the navigation back stack, so a screen gets a back arrow without having to know that it
 * is navigable. A screen that passes its own [Heading] `leading` overrides it.
 */
val LocalNavigateBack = compositionLocalOf<(() -> Unit)?> { null }

/**
 * The app-wide screen header. [leading] and [trailing] sit on the title's row, [subheading]
 * wraps below it. [leading] defaults to a back arrow wherever [LocalNavigateBack] is set.
 */
@Composable
fun Heading(
    text: String,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    subheading: (@Composable () -> Unit)? = null
) {
    val navigateBack = LocalNavigateBack.current
    val start = leading ?: navigateBack?.let { back ->
        @Composable {
            IconButton(onClick = back) {
                Icon(
                    Icons.AutoMirrored.Default.ArrowBack,
                    contentDescription = stringResource(R.string.navigate_back)
                )
            }
        }
    }

    Column(
        modifier = Modifier
            .padding(vertical = 16.dp)
    ) {
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                // An icon button brings its own touch padding, so the title needs less of its own.
                .padding(start = if (start != null) 4.dp else 16.dp, end = 4.dp)
        ) {
            start?.invoke()

            Text(
                if (landscape) text.uppercase() else text,
                style = if (landscape) MaterialTheme.typography.titleSmall else MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f)
            )

            trailing?.invoke()
        }

        if (subheading != null) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.Start),
                modifier = Modifier
                    .padding(
                        top = if (landscape) 7.dp else 16.dp,
                        start = 16.dp,
                        end = 16.dp
                    ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                subheading()
            }
        }
    }
}

/**
 * A header action: a compact outlined button, 36dp high (the header row is crowded at 360dp), with an optional leading [icon].
 * [isDanger] swaps the outline and content to the error role for destructive actions.
 */
@Composable
fun HeadingButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: VectorIcon? = null,
    isDanger: Boolean = false
) {
    val accent = if (isDanger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = accent),
        border = BorderStroke(1.dp, if (isDanger) accent else MaterialTheme.colorScheme.outline),
        contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = modifier.height(36.dp)
    ) {
        if (icon != null) {
            // The label names the action, so the icon is decoration for screen readers.
            Icon(
                icon.asPainter(),
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.size(6.dp))
        }

        Text(
            text.uppercase(),
            style = MaterialTheme.typography.titleSmall
        )
    }
}
