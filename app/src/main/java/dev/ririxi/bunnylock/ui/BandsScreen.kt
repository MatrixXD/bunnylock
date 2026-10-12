package dev.ririxi.bunnylock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BandsScreen(
    state: UiState,
    vm: MainViewModel,
    contentPadding: PaddingValues,
) {
    LazyColumn(contentPadding = contentPadding.withScreenMargins(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            SectionHeader("Network mode")
            val currentMode = NetMode.of(state.allowedMask)
            Panel {
                ToggleGrid(
                    options = NetMode.entries,
                    isChecked = { it == currentMode },
                    label = { it.label },
                    onToggle = vm::setNetMode,
                    columns = 3,
                    enabled = !state.busy,
                )
            }
        }

        when {
            state.supportsNrMode -> {
                item {
                    SectionHeader("5G mode")
                    ConnectedChoice(
                        options = NrMode.entries,
                        selected = state.nrMode,
                        label = { it.label },
                        onSelect = vm::setNrMode,
                        enabled = !state.busy,
                    )
                }
            }

            state.device?.aospNrDc == true -> {
                item {
                    SectionHeader("5G NSA (EN-DC)")
                    ConnectedChoice(
                        options = listOf(true, false),
                        selected = state.nrDc,
                        label = { nsaOn -> if (nsaOn) "NSA on" else "NSA off" },
                        onSelect = vm::setNrDc,
                        enabled = !state.busy,
                    )
                }
            }
        }

        if (state.device?.samsungMenu == true) {
            item {
                SectionHeader("Samsung")
                Segmented {
                    item { shapes ->
                        SegmentedListItem(
                            onClick = vm::samsungMenu,
                            shapes = shapes,
                            colors = segmentColors(),
                            leadingContent = { Icon(BunnyIcons.Bands, contentDescription = null) },
                            supportingContent = { Text("Samsung's own band lock, built into the phone.") },
                        ) { Text("Open Samsung band menu") }
                    }
                }
            }
        }

        item {
            Text(
                backendHint(state),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        items(state.groups, key = { it.group.name }) { bandGroup ->
            BandGroup(
                bandGroup,
                busy = state.busy,
                onToggle = { vm.toggleBand(bandGroup.group, it) },
                onSelectAll = { vm.selectAll(bandGroup.group, it) },
                onLock = { vm.applyGroup(bandGroup.group) },
                onUnlock = { vm.applyGroup(bandGroup.group, unlock = true) },
            )
        }
    }
}

private fun backendHint(state: UiState): String =
    when (state.backend) {
        Backend.QUALCOMM -> {
            val afterReboot =
                if (state.persist) {
                    "Locks stay after a reboot (you can change that in Tools)."
                } else {
                    "Locks are cleared when you reboot."
                }
            "Changes apply right away over QMI. $afterReboot"
        }

        Backend.MEDIATEK -> {
            "Changes are sent with AT+EPBSE and AT+EPBSEH on ${state.atNode.ifEmpty { "the AT port found automatically" }}."
        }

        Backend.PIXEL -> {
            "Changes are saved to the modem's NV memory, and the radio restarts by itself to apply them."
        }

        Backend.ANDROID, null -> {
            "This uses the radio HAL's system selection channels. Whether the modem follows them depends on the phone maker."
        }
    }

private fun selectionSummary(bandGroup: BandGroupState): String =
    buildString {
        append("${bandGroup.selected.size} of ${bandGroup.supported.size} selected")
        if (bandGroup.stray !== null) {
            append(", locked but the phone is on ${bandGroup.group.bandLabel(bandGroup.stray)}")
        } else if (bandGroup.locked) {
            append(", locked")
        }
        if (bandGroup.dirty) append(", not applied yet")
    }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BandGroup(
    bandGroup: BandGroupState,
    busy: Boolean,
    onToggle: (Int) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onLock: () -> Unit,
    onUnlock: () -> Unit,
) {
    Segmented(Modifier.padding(top = 8.dp)) {
        item { shapes ->
            SegmentedListItem(
                shapes = shapes,
                colors = segmentColors(),
                supportingContent = { Text(selectionSummary(bandGroup)) },
                trailingContent = { LockBadge(bandGroup.locked && bandGroup.stray === null, size = 40.dp) },
            ) {
                Text(bandGroup.group.label, style = MaterialTheme.typography.titleMediumEmphasized)
            }
        }
        item { shapes ->
            Panel(shape = shapes.shape) {
                ToggleGrid(
                    options = bandGroup.supported,
                    isChecked = { it in bandGroup.selected },
                    label = { bandGroup.group.bandLabel(it) },
                    onToggle = onToggle,
                    columns = 4,
                )
            }
        }
        item { shapes ->
            Panel(shape = shapes.shape, padding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onSelectAll(true) }, shapes = ButtonDefaults.shapes()) { Text("All") }
                    TextButton(onClick = { onSelectAll(false) }, shapes = ButtonDefaults.shapes()) { Text("None") }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = onUnlock, enabled = !busy && bandGroup.locked, shapes = ButtonDefaults.shapes()) {
                        Text("Unlock")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onLock, enabled = !busy && bandGroup.selected.isNotEmpty(), shapes = ButtonDefaults.shapes()) {
                        Icon(BunnyIcons.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Lock")
                    }
                }
            }
        }
    }
}
