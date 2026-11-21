package io.github.fate_grand_automata.ui.dialog

import android.annotation.SuppressLint
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.ui.DimmedIcon
import io.github.fate_grand_automata.ui.VectorIcon
import io.github.fate_grand_automata.util.toggle
import java.util.*

// Simplified form of https://github.com/vanpra/compose-material-dialogs

@SuppressLint("ComposableNaming")
class FgaDialog private constructor(initialVisibility: Boolean = false) {
    companion object {

        fun saver(): Saver<FgaDialog, *> = Saver(
            save = {
                it.visible
            },
            restore = {
                FgaDialog(it)
            }
        )
        @Composable
        operator fun invoke(initialValue: Boolean = false) = rememberSaveable(saver = saver()) {
            FgaDialog(initialValue)
        }
    }

    private var visible by mutableStateOf(initialVisibility)

    fun show() {
        visible = true
    }

    fun hide() {
        visible = false
    }

    @Composable
    fun title(text: String, icon: VectorIcon? = null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(24.dp, 16.dp)
        ) {
            if (icon != null) {
                DimmedIcon(
                    icon,
                    contentDescription = "heading icon",
                    modifier = Modifier
                        .padding(end = 16.dp)
                )
            }

            Text(
                text,
                style = MaterialTheme.typography.titleLarge
            )
        }
    }

    @Composable
    fun message(text: String) {
        Text(
            text,
            modifier = Modifier
                .padding(24.dp, 16.dp)
                .padding(bottom = 12.dp)
        )
    }

    @Composable
    fun buttons(
        onSubmit: () -> Unit,
        onCancel: () -> Unit = {},
        showOk: Boolean = true,
        showCancel: Boolean = true,
        okEnabled: Boolean = true,
        okLabel: String = stringResource(android.R.string.ok),
        cancelLabel: String = stringResource(android.R.string.cancel),
        leadingAction: (@Composable () -> Unit)? = null
    ) {
        Row(
            horizontalArrangement = Arrangement.End,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp, 5.dp)
        ) {
            if (leadingAction != null) {
                leadingAction()
                Spacer(Modifier.weight(1f))
            }
            if (showCancel) {
                TextButton(
                    onClick = {
                        hide()
                        onCancel()
                    }
                ) {
                    Text(cancelLabel.uppercase())
                }
            }

            if (showOk) {
                TextButton(
                    onClick = {
                        onSubmit()
                        hide()
                    },
                    enabled = okEnabled
                ) {
                    Text(okLabel.uppercase())
                }
            }
        }
    }

    @Composable
    fun build(
        shape: Shape = MaterialTheme.shapes.medium,
        color: Color = MaterialTheme.colorScheme.surface,
        contentColor: Color = contentColorFor(color),
        onDismiss: () -> Unit = {},
        content: @Composable FgaDialog.() -> Unit,
    ) {
        if (visible) {
            ThemedDialog(
                onDismiss = {
                    hide()
                    onDismiss()
                }
            ) {
                Surface(
                    shape = shape,
                    color = color,
                    contentColor = contentColor,
                    modifier = Modifier
                        .padding(16.dp)
                        .widthIn(max = 450.dp)
                        .fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        this@FgaDialog.content()
                    }
                }
            }
        }
    }

    @Composable
    fun constrained(
        content: @Composable FgaDialog.(Modifier) -> Unit
    ) {
        BoxWithConstraints {
            val modifier = Modifier
                .heightIn(max = maxHeight * 0.6f)
                .padding(bottom = 8.dp)

            this@FgaDialog.content(modifier)
        }
    }
}

@Composable
@SuppressLint("ComposableNaming")
fun <T> FgaDialog.multiChoiceList(
    selected: Set<T>,
    items: List<T>,
    prioritySelected: Boolean = false,
    onSelectedChange: (Set<T>) -> Unit,
    template: @Composable RowScope.(T) -> Unit = {
        Text(it.toString())
    }
) {
    constrained { modifier ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp, 0.dp),
            modifier = modifier
                .fillMaxWidth()
        ) {
            val rearrangeItems = when (prioritySelected) {
                true -> items.sortedByDescending {
                    it in selected
                }

                false -> items
            }
            items(rearrangeItems) {
                ChoiceListItem(
                    isSelected = it in selected,
                    onClick = { onSelectedChange(selected.toggle(it)) },
                    multiple = true,
                    modifier = Modifier.animateItem(
                        spring(
                            stiffness = Spring.StiffnessMedium
                        )
                    )
                ) {
                    template(it)
                }
            }
        }
    }
}

/**
 * One choice of a dialog list: a radio button, or a checkbox when [multiple], and the content.
 * The whole row is the touch target, so the button itself carries no click of its own.
 */
@Composable
fun ChoiceListItem(
    modifier: Modifier = Modifier,
    isSelected: Boolean,
    onClick: () -> Unit,
    multiple: Boolean = false,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(
                if (multiple) Modifier.toggleable(value = isSelected, role = Role.Checkbox, onValueChange = { onClick() })
                else Modifier.selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick)
            )
            .padding(end = 8.dp)
    ) {
        if (multiple) Checkbox(checked = isSelected, onCheckedChange = null)
        else RadioButton(selected = isSelected, onClick = null)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp)
        ) {
            content()
        }
    }
}

@Composable
@SuppressLint("ComposableNaming")
fun <T> FgaDialog.singleChoiceList(
    selected: T,
    items: List<T>,
    onSelectedChange: (T) -> Unit,
    template: @Composable RowScope.(T) -> Unit = {
        Text(it.toString())
    }
) {
    constrained { modifier ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp, 0.dp),
            modifier = modifier
                .fillMaxWidth()
                .fillMaxHeight()
        ) {
            items(items) {
                ChoiceListItem(
                    isSelected = it == selected,
                    onClick = { onSelectedChange(it) }
                ) {
                    template(it)
                }
            }
        }
    }
}