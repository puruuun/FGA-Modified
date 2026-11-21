package io.github.fate_grand_automata.ui.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

const val FaceThumbnailTag = "faceThumbnail"
const val FacePlaceholderTag = "facePlaceholder"

/**
 * Resolves the image of one choice. It runs off the main thread, and `null` means the choice has none.
 * The returned model is decoded by the image loader at the thumbnail size, never at full size.
 */
fun interface FaceThumbnails {
    suspend fun thumbnail(name: String): Any?
}

/**
 * One choice of a multi-select dialog: [content] (an icon of 32dp when the entry has one, then its name),
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

/** A 32dp face that is a neutral placeholder until, and unless, [thumbnails] finds an image for [name]. */
@Composable
fun FaceThumbnail(thumbnails: FaceThumbnails, name: String) {
    val model by produceState<Any?>(null, thumbnails, name) {
        value = thumbnails.thumbnail(name)
    }
    val shape = RoundedCornerShape(6.dp)
    val placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
    val current = model
    if (current == null) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(32.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .testTag(FacePlaceholderTag)
        ) {
            Icon(
                Icons.Default.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
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
                .size(32.dp)
                .clip(shape)
                .testTag(FaceThumbnailTag)
        )
    }
}
