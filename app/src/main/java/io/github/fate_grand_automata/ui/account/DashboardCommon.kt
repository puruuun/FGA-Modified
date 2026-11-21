package io.github.fate_grand_automata.ui.account

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.fate_grand_automata.ui.log.LocalLogIconLoader
import io.github.fate_grand_automata.ui.log.LogIconLoader
import io.github.fate_grand_automata.util.servantClassRes
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * The legacy card ids. They stay as the `dashboard-section:<id>` test tags and as the values of `initialSection`,
 * so callers and tests keep addressing a block even though blocks now live inside navigation sections.
 */
internal object DashboardBlockId {
    const val Profile = 0
    const val Resources = 1
    const val Storage = 2
    const val Servants = 3
    const val Items = 4
    const val MysticCodes = 5
    const val Recent = 6
    const val SupportFormation = 7
    const val GrandFormation = 8
    const val PresentBox = 10
    const val CommandCodes = 12
    const val ClassScore = 13
    const val Summons = 14
}

internal class DashboardTarget(val blockId: Int, val requester: BringIntoViewRequester)

/** The block `initialSection` asks to see; the block with that id attaches the requester. */
internal val LocalDashboardTarget = staticCompositionLocalOf<DashboardTarget?> { null }

/** A titled group inside a section: a small `primary` title over a thin divider, without a card around it. */
@Composable
internal fun DashboardBlock(blockId: Int, title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val target = LocalDashboardTarget.current
    Column(
        modifier.fillMaxWidth().testTag("dashboard-section:$blockId")
            .then(if (target != null && target.blockId == blockId) Modifier.bringIntoViewRequester(target.requester) else Modifier),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        content()
    }
}

internal fun date(seconds: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(seconds * 1000))

@Composable internal fun CompactResourceTile(
    kind: String?,
    id: Long,
    name: String,
    iconUrl: String?,
    countText: String?,
    tag: String,
) {
    val summary = listOfNotNull(name, countText).joinToString(", ")
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.widthIn(max = 160.dp).testTag(tag).semantics(mergeDescendants = true) {
            contentDescription = summary
        },
    ) {
        Row(
            Modifier.height(48.dp).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (kind != null) AtlasIcon(kind, id, iconUrl, size = 32.dp, reserveSpace = false)
            Row(
                Modifier.weight(1f, fill = false),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    name,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).widthIn(max = 100.dp),
                )
                if (countText != null) {
                    Text(countText, style = MaterialTheme.typography.bodySmall, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/** Per-kind directories prevent mystic code IDs colliding with item IDs. */
@Composable internal fun AtlasIcon(kind: String, id: Long, url: String?, size: Dp = 40.dp, reserveSpace: Boolean = true) {
    val context = LocalContext.current
    val store = remember(context, kind) { LogIconLoader.files(context, kind) }
    val loader = LocalLogIconLoader.current ?: LogIconLoader.get(context)
    var file by remember(kind, id, url) { mutableStateOf<File?>(null) }
    LaunchedEffect(kind, id, url) { file = store.resolve(id, url) }
    if (file != null || reserveSpace) {
        Box(Modifier.size(size)) {
            if (file != null) AsyncImage(
                file!!.toURI().toString(),
                contentDescription = null,
                imageLoader = loader,
                modifier = Modifier.fillMaxSize().testTag("dashboard-icon-ready"),
            )
        }
    }
}

internal fun dashboardClassRes(name: String): Int? = servantClassRes(name, matchBeastSubstring = false)

@Composable internal fun dashboardClass(name: String): String =
    dashboardClassRes(name)?.let { stringResource(it) } ?: name
