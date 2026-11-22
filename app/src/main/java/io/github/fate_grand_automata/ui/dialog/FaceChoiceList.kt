package io.github.fate_grand_automata.ui.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

const val FaceThumbnailTag = "faceThumbnail"
const val FacePlaceholderTag = "facePlaceholder"
const val FaceDeleteMarkTag = "faceDeleteMark"

/**
 * Resolves the image of one choice. It runs off the main thread, and `null` means the choice has none.
 * The returned model is decoded by the image loader at the thumbnail size, never at full size.
 */
fun interface FaceThumbnails {
    suspend fun thumbnail(name: String): Any?
}

/**
 * Deletes the support image entries a picker lists. [isProtected] entries can never be marked.
 * [delete] returns the entries that are really gone; an entry it leaves out is still there.
 */
class ImageDelete<T>(
    val isProtected: (T) -> Boolean,
    val delete: suspend (Set<T>) -> Set<T>
)

/**
 * One choice of the materials dialog: [content] (an icon of 32dp, then its name),
 * and a selection circle at the end. The whole row toggles and is at least 48dp tall.
 */
@Composable
fun FaceChoiceRow(
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .toggleable(value = isSelected, role = Role.Checkbox, onValueChange = { onClick() })
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f),
            content = content
        )

        if (isSelected) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        } else {
            Box(
                Modifier
                    .size(24.dp)
                    .padding(2.dp)
                    .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
            )
        }
    }
}

/**
 * One choice of a chip picker: [content] (a 24dp face when the entry has one, then its name).
 * The chip is at least 40dp high inside a 48dp touch target. A selected chip is filled and has no border.
 * [isMarked] is `null` outside delete mode. In delete mode a tap marks instead of selecting, so the mark is what
 * gets the error border and what a screen reader reports.
 */
@Composable
fun FaceChip(
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    isMarked: Boolean? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    val checked = isMarked ?: isSelected
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .heightIn(min = 48.dp)
            .combinedClickable(role = Role.Checkbox, onClick = onClick, onLongClick = onLongClick)
            .semantics { toggleableState = ToggleableState(checked) }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .heightIn(min = 40.dp)
                .background(if (isSelected) colors.secondaryContainer else Color.Transparent, shape)
                .then(
                    when {
                        isMarked == true -> Modifier.border(2.dp, colors.error, shape)
                        isSelected -> Modifier
                        else -> Modifier.border(1.dp, colors.outlineVariant, shape)
                    }
                )
                .padding(horizontal = 12.dp, vertical = 4.dp),
            content = content
        )
    }
}

/** Lays chips out in as many lines as they need; a long name may take a whole line. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipFlow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth(),
        content = { content() }
    )
}

/**
 * The chips of a multi-select dialog. [isMarked] is `null` outside delete mode, [onLongClick] `null` when the
 * entries cannot be deleted. The list is not lazy, which a flow layout cannot be.
 */
@Composable
fun <T> FgaDialog.chipChoiceList(
    items: List<T>,
    isSelected: (T) -> Boolean,
    onClick: (T) -> Unit,
    onLongClick: ((T) -> Unit)? = null,
    isMarked: ((T) -> Boolean)? = null,
    template: @Composable RowScope.(T, Boolean) -> Unit
) {
    constrained { modifier ->
        Column(
            modifier = modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            ChipFlow {
                items.forEach { item ->
                    key(item) {
                        val marked = isMarked?.invoke(item)
                        FaceChip(
                            isSelected = isSelected(item),
                            onClick = { onClick(item) },
                            onLongClick = onLongClick?.let { { it(item) } },
                            isMarked = marked
                        ) {
                            template(item, marked == true)
                        }
                    }
                }
            }
        }
    }
}

/**
 * A 24dp face that is a neutral placeholder until, and unless, [thumbnails] finds an image for [name].
 * [marked] draws the delete mark of the support image delete mode on it.
 */
@Composable
fun FaceThumbnail(thumbnails: FaceThumbnails, name: String, marked: Boolean = false) {
    val model by produceState<Any?>(null, thumbnails, name) {
        value = thumbnails.thumbnail(name)
    }
    val shape = RoundedCornerShape(4.dp)
    val placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
    val current = model
    Box(contentAlignment = Alignment.BottomEnd) {
        if (current == null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(24.dp)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .testTag(FacePlaceholderTag)
            ) {
                Icon(
                    Icons.Default.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        } else {
            AsyncImage(
                model = current,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                placeholder = placeholder,
                error = placeholder,
                modifier = Modifier
                    .size(24.dp)
                    .clip(shape)
                    .testTag(FaceThumbnailTag)
            )
        }
        if (marked) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(14.dp)
                    .background(MaterialTheme.colorScheme.error, CircleShape)
                    .testTag(FaceDeleteMarkTag)
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.size(10.dp)
                )
            }
        }
    }
}
