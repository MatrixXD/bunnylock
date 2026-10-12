package dev.ririxi.bunnylock.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ririxi.bunnylock.data.Cell
import dev.ririxi.bunnylock.data.Rat
import dev.ririxi.bunnylock.ui.theme.LocalLockGreen

@Composable
fun HomeScreen(
    state: UiState,
    vm: MainViewModel,
    contentPadding: PaddingValues,
    goTo: (Tab) -> Unit,
) {
    LazyColumn(contentPadding = contentPadding.withScreenMargins(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Hero(state, onUnlock = vm::unlockEverything, onBands = { goTo(Tab.BANDS) }, onCell = { goTo(Tab.CELL) }) }

        if (state.sims.size > 1) {
            item {
                SectionHeader("SIM")
                ConnectedChoice(
                    options = state.sims.map { it.slot + 1 },
                    selected = state.sim,
                    label = { simNumber -> state.sims.first { it.slot + 1 == simNumber }.name },
                    onSelect = vm::setSim,
                )
            }
        }

        item {
            val servingCells = state.cells.filter { it.registered }
            val hasNsaSecondaryCell = servingCells.any { it.note == "NSA SCG" }
            SectionHeader(if (hasNsaSecondaryCell) "Serving cells (5G NSA)" else "Serving cell")
            Segmented {
                if (servingCells.isEmpty()) item { InfoItem(it, "Serving cell", "None") }
                servingCells.forEach { cell -> item { shapes -> CellItem(shapes, cell) } }
            }
        }

        item {
            val device = state.device
            val deviceName = "${device?.manufacturer.orEmpty()} ${device?.model.orEmpty()}".trim()
            val platform =
                listOfNotNull(
                    device?.socModel?.ifEmpty { null },
                    device?.platform?.ifEmpty { null },
                ).joinToString(", ")
            SectionHeader("Modem")
            Segmented {
                item { InfoItem(it, "Chipset", device?.vendorLabel.orEmpty()) }
                item { InfoItem(it, "Device", deviceName) }
                item { InfoItem(it, "Platform", platform) }
                item { InfoItem(it, "Baseband", device?.baseband.orEmpty()) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Hero(
    state: UiState,
    onUnlock: () -> Unit,
    onBands: () -> Unit,
    onCell: () -> Unit,
) {
    val locked = state.anyBandLock || state.anyCellLock || state.anyPlmnLock
    val strayLocation =
        state.strayBand?.let { "$it, outside the locked bands" }
            ?: state.strayChannel?.let { "$it, off the locked frequency" }
    val lockHeld = locked && strayLocation === null
    val green = LocalLockGreen.current
    val containerColor by animateColorAsState(
        if (lockHeld) {
            green.copy(alpha = 0.2f).compositeOver(MaterialTheme.colorScheme.surfaceContainerHigh)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        label = "hero",
    )
    Surface(color = containerColor, shape = RoundedCornerShape(36.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            strayLocation !== null -> "Ignored"
                            locked -> "Locked"
                            else -> "Free"
                        },
                        style = MaterialTheme.typography.displaySmallEmphasized.copy(fontWeight = FontWeight.Bold),
                        color =
                            when {
                                strayLocation !== null -> MaterialTheme.colorScheme.error
                                locked -> green
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                    )
                    Text(
                        if (strayLocation !== null) "The phone is on $strayLocation." else pinnedSummary(state),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LockBadge(lockHeld, size = 88.dp, spin = true)
            }

            val lockRows = activeLockRows(state)
            if (lockRows.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    lockRows.forEach { (label, value) ->
                        Row {
                            Text(label, style = MaterialTheme.typography.labelLargeEmphasized, modifier = Modifier.width(96.dp))
                            Text(value, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val buttonHeight = ButtonDefaults.MediumContainerHeight
                if (locked) {
                    Button(
                        onClick = onUnlock,
                        enabled = !state.busy,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.fillMaxWidth().height(buttonHeight),
                        contentPadding = ButtonDefaults.contentPaddingFor(buttonHeight),
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                    ) {
                        Icon(
                            BunnyIcons.LockOpen,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.iconSizeFor(buttonHeight)),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Unlock all", style = ButtonDefaults.textStyleFor(buttonHeight), maxLines = 1)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onBands, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) { Text("Bands") }
                    FilledTonalButton(onClick = onCell, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) { Text("Cell") }
                }
            }
        }
    }
}

private fun pinnedSummary(state: UiState): String {
    val pinned =
        listOfNotNull(
            "bands".takeIf { state.anyBandLock },
            "cell".takeIf { state.anyCellLock },
            "network".takeIf { state.anyPlmnLock },
        )
    val takesSingularVerb = pinned == listOf("cell") || pinned == listOf("network")
    return when {
        pinned.isEmpty() -> "Nothing is pinned."
        takesSingularVerb -> "The ${pinned[0]} is pinned."
        else -> "The ${andList(pinned)} are pinned."
    }
}

private fun activeLockRows(state: UiState): List<Pair<String, String>> =
    buildList {
        state.groups.filter { it.locked }.forEach { bandGroup ->
            val bands = bandGroup.current!!.sorted().joinToString(", ") { bandGroup.group.bandLabel(it) }
            add(bandGroup.group.label to bands)
        }
        state.plmnLock?.let { add("Network" to it) }
        state.lteLock?.let { add("LTE cell" to it) }
        state.nrLock?.let { add("NR cell" to it) }
        state.legacyCellLock?.let { add("2G/3G cell" to it) }
    }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CellItem(
    shapes: ListItemShapes,
    cell: Cell,
    onClick: (() -> Unit)? = null,
) {
    val channelName =
        when (cell.rat) {
            Rat.LTE -> "EARFCN"
            Rat.WCDMA -> "UARFCN"
            else -> "ARFCN"
        }
    val title =
        listOfNotNull(
            if (cell.band !== null) cell.bandLabel else null,
            "$channelName ${cell.arfcn}",
            if (cell.pci >= 0) "PCI ${cell.pci}" else null,
        ).joinToString(", ")
    val details =
        listOfNotNull(
            if (cell.registered) "serving" else null,
            cell.note.takeIf { it.isNotEmpty() && it != "neighbour" },
            if (cell.source == "modem") "from the modem" else null,
        ).joinToString(", ").replaceFirstChar { it.uppercase() }
    val leading: @Composable () -> Unit = { RatBadge(cell.rat) }
    val trailing: @Composable () -> Unit = { cell.rsrp?.let { Text("$it dBm", style = MaterialTheme.typography.labelLargeEmphasized) } }
    val supporting: (@Composable () -> Unit)? = if (details.isEmpty()) null else ({ Text(details) })
    val content: @Composable () -> Unit = { Text(title, style = MaterialTheme.typography.bodyLargeEmphasized) }
    if (onClick !== null) {
        SegmentedListItem(
            onClick = onClick,
            shapes = shapes,
            colors = segmentColors(),
            leadingContent = leading,
            trailingContent = trailing,
            supportingContent = supporting,
            content = content,
        )
    } else {
        SegmentedListItem(
            shapes = shapes,
            colors = segmentColors(),
            leadingContent = leading,
            trailingContent = trailing,
            supportingContent = supporting,
            content = content,
        )
    }
}
