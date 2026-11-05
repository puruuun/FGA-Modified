package io.github.fate_grand_automata.ui.log

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.capture.GameNameMap
import io.github.fate_grand_automata.capture.IconFileStore
import io.github.fate_grand_automata.capture.signedOutsideCount
import io.github.fate_grand_automata.scripts.capture.AccountDelta
import io.github.fate_grand_automata.scripts.capture.RunGains
import io.github.fate_grand_automata.util.grouped
import java.io.File

internal data class LogResource(
    val type: Int = 2, val id: Long, val count: Long?, val split: Pair<Long, Long>? = null, val holdings: Long? = null,
)

internal val currencyIds = setOf(1L, 2L, 3L, 5L, 6L, 7L, 11L, 16L, 18L, 100L, 101L, 104L, 102L)
internal val iconItemIds = listOf(1L, 2L, 3L, 18L, 100L, 101L, 104L, 102L, 11L, 16L)

/** Zero fields disappear. Fruit usage remains gross usage even when later gains offset it. */
internal fun outsideResources(delta: AccountDelta, fruitUsed: Map<Long, Long>): List<LogResource> = buildList {
    delta.items.filterKeys { it !in RunGains.FRUIT_IDS }.forEach { (id, count) -> if (count != 0L) add(LogResource(id = id, count = count)) }
    listOf(1L to delta.qp, 3L to delta.mana, 18L to delta.rarePri).forEach { (id, count) -> if (count != 0L) add(LogResource(id = id, count = count)) }
    if (delta.freeStone != 0L || delta.chargeStone != 0L) add(LogResource(id = 2, count = delta.freeStone + delta.chargeStone, split = delta.freeStone to delta.chargeStone))
    listOf(100L, 101L, 104L, 102L).forEach { id ->
        val used = fruitUsed[id] ?: 0L
        // Net + gross use reconstructs gains without showing the same loss twice.
        val gained = (delta.items[id] ?: 0L) + used
        if (gained > 0) add(LogResource(id = id, count = gained))
        if (used > 0) add(LogResource(id = id, count = -used))
    }
}

internal fun battleResources(gains: RunGains): List<LogResource> = buildList {
    gains.qp?.takeIf { it != 0L }?.let { add(LogResource(id = 1, count = it)) }
    gains.items.filter { it.gained != 0L }.sortedByDescending { it.gained }.forEach { add(LogResource(it.type, it.objectId, it.gained, holdings = it.holdings)) }
    listOf(100L, 101L, 104L, 102L).forEach { id ->
        val used = gains.fruitUsed[id]
        if (id in gains.fruitUsed && used != 0L) add(LogResource(id = id, count = used?.let { -it }))
    }
}

@Composable
internal fun resourceName(type: Int, id: Long, names: GameNameMap): String = if (type != 2) names.drop(type, id) else when (id) {
    1L, 5L -> stringResource(R.string.capture_qp)
    2L, 6L, 7L -> stringResource(R.string.capture_quartz)
    3L -> stringResource(R.string.capture_mana_prism)
    18L -> stringResource(R.string.capture_rare_prism)
    11L, 16L -> stringResource(R.string.capture_fragments)
    100L -> stringResource(R.string.capture_apples_gold)
    101L -> stringResource(R.string.capture_apples_silver)
    104L -> stringResource(R.string.capture_apples_bronze)
    102L -> stringResource(R.string.capture_apples_copper)
    else -> names.item(id)
}

/** Coil only decodes local files. The permanent store owns downloading and retry policy. */
private object LogIconLoader {
    private var loader: ImageLoader? = null
    private val stores = mutableMapOf<File, IconFileStore>()
    @Synchronized fun files(context: android.content.Context): IconFileStore {
        val directory = File(context.filesDir, "capture/icons")
        return stores.getOrPut(directory) { IconFileStore(directory) }
    }
    @Synchronized fun get(context: android.content.Context): ImageLoader = loader ?: ImageLoader.Builder(context.applicationContext)
        .diskCache(null)
        .build().also { loader = it }
}

/** Tests supply generated local images through this seam, never real captures or a network URL. */
internal val LocalLogIconLoader = staticCompositionLocalOf<ImageLoader?> { null }

@Composable
internal fun ResourceIcon(type: Int, id: Long, names: GameNameMap, onAvailable: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val url = if (type == 2) names.itemIcons[id] else null
    var file by remember(type, id, url) { mutableStateOf<File?>(null) }
    var failed by remember(type, id, url) { mutableStateOf(false) }
    var loaded by remember(type, id, url) { mutableStateOf(false) }
    LaunchedEffect(type, id, url) {
        if (type == 2) file = LogIconLoader.files(context).resolve(id, url)
    }
    LaunchedEffect(file, failed) { onAvailable(file != null && !failed) }
    if (file != null && !failed) AsyncImage(model = file!!.toURI().toString(),
        imageLoader = LocalLogIconLoader.current ?: LogIconLoader.get(context),
        contentDescription = null, modifier = Modifier.size(32.dp).testTag(if (loaded) "log-icon-loaded" else "log-icon-loading"),
        onSuccess = { loaded = true }, onError = { failed = true })
}

@Composable
internal fun ResourceGroups(resources: List<LogResource>, names: GameNameMap) {
    val gains = resources.filter { it.count != null && it.count >= 0 }
    val uses = resources.filter { it.count == null || it.count < 0 }
    listOf(R.string.capture_outside_gained to gains, R.string.capture_outside_used to uses).forEach { (title, group) ->
        if (group.isNotEmpty()) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                group.forEach { ResourceTile(it, names) }
            }
        }
    }
}

@Composable
internal fun ResourceTile(resource: LogResource, names: GameNameMap) {
    val name = resourceName(resource.type, resource.id, names)
    var available by remember(resource.type, resource.id) { mutableStateOf(false) }
    val count = resource.count?.let(::signedOutsideCount) ?: stringResource(R.string.capture_gains_unknown)
    val split = resource.split?.let { (free, paid) -> stringResource(R.string.capture_stone_split, signedOutsideCount(free), signedOutsideCount(paid)) }
    val holdings = resource.holdings?.let { stringResource(R.string.capture_gains_holdings, it.grouped()) }
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.widthIn(max = 160.dp).testTag("resource:${resource.type}:${resource.id}")
            .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(name, count, split, holdings).joinToString(", ") }) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                ResourceIcon(resource.type, resource.id, names) { available = it }
                if (resource.type != 2 || resource.id !in currencyIds || !available) Text(name, style = MaterialTheme.typography.labelMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).widthIn(max = 100.dp))
                Text(count, style = MaterialTheme.typography.bodySmall, maxLines = 1, softWrap = false)
            }
            split?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            holdings?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        }
    }
}
