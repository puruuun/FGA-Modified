package io.github.fate_grand_automata.ui.prefs

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.Pref
import io.github.fate_grand_automata.ui.HeadingButton
import io.github.fate_grand_automata.ui.VectorIcon
import io.github.fate_grand_automata.ui.dialog.FaceThumbnail
import io.github.fate_grand_automata.ui.dialog.FaceThumbnails
import io.github.fate_grand_automata.ui.dialog.FgaDialog
import io.github.fate_grand_automata.ui.dialog.ImageDelete
import io.github.fate_grand_automata.ui.dialog.chipChoiceList
import io.github.fate_grand_automata.ui.icon
import io.github.fate_grand_automata.util.toggle
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val DeleteCloseTag = "deleteModeClose"
const val DeleteActionTag = "deleteModeAction"

@Composable
fun <T> multiSelectListDialog(
    selected: Set<T>,
    onSelectedChange: (Set<T>) -> Unit,
    entries: Map<T, String>,
    title: String,
    dialogAction: (@Composable ((T) -> Unit) -> Unit)? = null,
    thumbnails: FaceThumbnails? = null,
    imageDelete: ImageDelete<T>? = null
): FgaDialog {
    val dialog = FgaDialog()
    val context = LocalContext.current
    // Outlives the dialog: the files and the stored selection must stay in step even if it closes mid-delete.
    val scope = rememberCoroutineScope()

    dialog.build {
        // Deleting changes the stored selection while the dialog is open; the ticks not yet confirmed survive it.
        val carried = remember { arrayOfNulls<Set<T>>(1) }
        var current by remember(selected) { mutableStateOf(carried[0] ?: selected).also { carried[0] = null } }
        // Null is normal mode; a set is delete mode with those entries marked.
        var marked by remember { mutableStateOf<Set<T>?>(null) }
        val confirm = FgaDialog()

        BackHandler(enabled = marked != null) { marked = null }

        fun refuse() {
            Toast.makeText(context, R.string.support_picker_builtin_locked, Toast.LENGTH_SHORT).show()
        }

        fun mark(item: T) {
            if (imageDelete?.isProtected?.invoke(item) == true) return refuse()
            val next = marked.orEmpty().toggle(item)
            marked = next.ifEmpty { null }
        }

        val markedNow = marked
        if (markedNow == null) {
            title(text = title)
        } else {
            DeleteModeTitle(
                count = markedNow.size,
                onClose = { marked = null },
                onDelete = { confirm.show() }
            )
        }

        chipChoiceList(
            items = entries.keys.toList(),
            isSelected = { it in current },
            onClick = { if (marked == null) current = current.toggle(it) else mark(it) },
            onLongClick = imageDelete?.let { { item -> mark(item) } },
            isMarked = markedNow?.let { set -> { it in set } }
        ) { item, isMarked ->
            if (thumbnails != null) FaceThumbnail(thumbnails, item.toString(), marked = isMarked)
            Text(entries[item] ?: "--")
        }

        if (markedNow == null) {
            buttons(
                onSubmit = { onSelectedChange(current) },
                leadingAction = dialogAction?.let { action -> { action { current = current + it } } }
            )
        }

        confirm.build {
            title(stringResource(R.string.battle_config_list_delete_confirm_title))
            message(stringResource(R.string.support_picker_delete_confirm_message, marked?.size ?: 0))
            buttons(
                okLabel = stringResource(R.string.battle_config_list_delete),
                onSubmit = {
                    val requested = marked.orEmpty()
                    val stored = selected
                    scope.launch {
                        // Not cancellable: once a file is gone the stored selection must lose it too.
                        withContext(NonCancellable) {
                            val removed = try {
                                imageDelete?.delete?.invoke(requested).orEmpty()
                            } catch (e: Exception) {
                                emptySet()
                            }
                            if (removed.isNotEmpty()) {
                                carried[0] = current - removed
                                onSelectedChange(stored - removed)
                                current -= removed
                            }
                            if (removed != requested) {
                                Toast.makeText(context, R.string.support_picker_delete_failed, Toast.LENGTH_SHORT).show()
                            }
                            marked = null
                        }
                    }
                }
            )
        }
    }

    return dialog
}

@Composable
private fun DeleteModeTitle(count: Int, onClose: () -> Unit, onDelete: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 8.dp, end = 24.dp, top = 8.dp, bottom = 8.dp)
    ) {
        IconButton(onClick = onClose, modifier = Modifier.testTag(DeleteCloseTag)) {
            Icon(Icons.Default.Close, contentDescription = stringResource(android.R.string.cancel))
        }
        Text(
            stringResource(R.string.support_picker_delete_count, count),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f)
        )
        HeadingButton(
            text = stringResource(R.string.battle_config_list_delete),
            onClick = onDelete,
            isDanger = true,
            icon = icon(Icons.Default.Delete),
            modifier = Modifier.testTag(DeleteActionTag)
        )
    }
}

@Composable
fun <T> Pref<Set<T>>.MultiSelectListPreference(
    title: String,
    summary: @Composable (List<String>) -> String = { it.joinToString() },
    singleLineTitle: Boolean = true,
    icon: VectorIcon? = null,
    entries: Map<T, String>,
    enabled: Boolean = true,
    dialogAction: (@Composable ((T) -> Unit) -> Unit)? = null,
    thumbnails: FaceThumbnails? = null,
    imageDelete: ImageDelete<T>? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    var selected by remember()

    val selectedItemNames = selected
        .map { entries.getOrDefault(it, it.toString()) }

    val dialog = multiSelectListDialog(
        selected = selected,
        onSelectedChange = { selected = it },
        entries = entries,
        title = title,
        dialogAction = dialogAction,
        thumbnails = thumbnails,
        imageDelete = imageDelete
    )

    Preference(
        title = title,
        summary = summary(selectedItemNames),
        singleLineTitle = singleLineTitle,
        icon = icon,
        enabled = enabled,
        onClick = { dialog.show() },
        trailing = trailing
    )
}
