package dev.ririxi.bunnylock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.ririxi.bunnylock.core.Bands
import dev.ririxi.bunnylock.data.Rat

private val NumberKeyboard = KeyboardOptions(keyboardType = KeyboardType.Number)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CellScreen(
    state: UiState,
    vm: MainViewModel,
    contentPadding: PaddingValues,
) {
    LazyColumn(contentPadding = contentPadding.withScreenMargins(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            SectionHeader("Network (PLMN)")
            Segmented {
                item {
                    LockStatusItem(
                        it,
                        locked = state.anyPlmnLock,
                        title = plmnLockTitle(state),
                        description = registrationText(state),
                    )
                }
                item { Panel(shape = it.shape) { PlmnForm(state, vm) } }
            }
        }

        item {
            SectionHeader("Current cell lock")
            Segmented {
                item {
                    LockStatusItem(
                        it,
                        locked = state.anyCellLock && state.strayChannel === null,
                        title = cellLockTitle(state),
                        description = cellLockDescription(state),
                    )
                }
                state.lteLock?.let { lock -> item { InfoItem(it, "LTE", lock) } }
                state.nrLock?.let { lock -> item { InfoItem(it, "NR", lock) } }
                state.legacyCellLock?.let { lock -> item { InfoItem(it, "2G/3G", lock) } }
                if (state.anyCellLock) {
                    item { shapes ->
                        SegmentedListItem(
                            onClick = vm::unlockCells,
                            enabled = !state.busy,
                            shapes = shapes,
                            colors = segmentColors(),
                            leadingContent = {
                                Icon(BunnyIcons.LockOpen, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            },
                        ) { Text("Clear cell lock", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }

        item {
            SectionHeader("Lock to")
            CellLockForm(state, vm)
        }

        item {
            val lockableCells = state.cells.filter { it.rat == Rat.LTE || it.rat == Rat.NR }
            SectionHeader("Visible cells (tap one to fill in)")
            Segmented {
                if (lockableCells.isEmpty()) item { InfoItem(it, "No cells found", "Needs location") }
                lockableCells.forEach { cell -> item { shapes -> CellItem(shapes, cell) { vm.fillFrom(cell) } } }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LockStatusItem(
    shapes: ListItemShapes,
    locked: Boolean,
    title: String,
    description: String,
) {
    SegmentedListItem(
        shapes = shapes,
        colors = segmentColors(),
        leadingContent = { LockBadge(locked, size = 44.dp) },
        supportingContent = { Text(description) },
    ) { Text(title, style = MaterialTheme.typography.titleMediumEmphasized) }
}

private fun plmnLockTitle(state: UiState): String {
    val lock = state.plmnLock ?: return "Automatic"
    // The lock is a PLMN such as "262 01", or a ready-made phrase when the locked network isn't known.
    return if (lock.firstOrNull()?.isDigit() == true) "Locked to $lock" else lock
}

private fun registrationText(state: UiState): String {
    val plmn = state.registeredPlmn ?: return "Not registered on a network."
    val name = state.registeredName?.let { " ($it)" }.orEmpty()
    return "Connected to ${formatPlmn(plmn)}$name."
}

private fun cellLockTitle(state: UiState): String =
    when {
        state.strayChannel !== null -> "Ignored"
        state.anyCellLock -> "Locked"
        else -> "Not locked"
    }

private fun cellLockDescription(state: UiState): String =
    when {
        state.strayChannel !== null -> "Android accepted the lock, but the phone is on ${state.strayChannel}."
        state.anyCellLock -> "The modem is pinned to a specific cell."
        else -> "The modem picks cells on its own."
    }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlmnForm(
    state: UiState,
    vm: MainViewModel,
) {
    val plmnComplete = state.plmnMcc.length == 3 && state.plmnMnc.length >= 2
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            state.plmnMcc,
            { vm.setPlmnForm(mcc = it) },
            label = { Text("MCC") },
            singleLine = true,
            keyboardOptions = NumberKeyboard,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            state.plmnMnc,
            { vm.setPlmnForm(mnc = it) },
            label = { Text("MNC") },
            singleLine = true,
            keyboardOptions = NumberKeyboard,
            modifier = Modifier.weight(1f),
        )
    }
    if (state.plmnPicks.isNotEmpty()) {
        ToggleGrid(
            options = state.plmnPicks,
            isChecked = { (plmn, _) -> plmn == state.plmnMcc + state.plmnMnc },
            label = { (plmn, name) -> "${formatPlmn(plmn)} $name" },
            onToggle = { (plmn, _) -> vm.pickPlmn(plmn) },
            columns = 2,
        )
    }
    Text(
        if (state.backend == Backend.QUALCOMM) {
            "Set over QMI first. If the modem refuses, Android network selection is used instead."
        } else {
            "Uses Android's manual network selection, which stays after a reboot."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = vm::unlockPlmn,
            enabled = !state.busy && state.anyPlmnLock,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.weight(1f),
        ) { Text("Automatic") }
        Button(
            onClick = vm::lockPlmn,
            enabled = !state.busy && plmnComplete,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.weight(1f),
        ) {
            Icon(BunnyIcons.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Lock")
        }
    }
}

private fun cellLockHint(state: UiState): String =
    when (state.backend) {
        Backend.QUALCOMM -> "Locks LTE and NR to a PCI (the SA PCell or the NSA SCG). Leave the NR PCI empty to lock only the frequency, and separate several PCIs with commas."
        Backend.MEDIATEK -> "Uses AT+EMMCHLCK, which holds one lock at a time. The PCI is optional, and the radio restarts to apply the lock."
        else -> "Locks the frequency (ARFCN) through the radio HAL. This backend can't lock to a PCI, and some modems (stock Qualcomm, for example) only apply the band and ignore the channel."
    }

private fun lteChannelHint(earfcn: Int): String {
    val band = Bands.lteBandOf(earfcn) ?: return "Unknown band"
    return "B$band at %.1f MHz".format(Bands.lteFreqMhz(earfcn))
}

private fun pciInput(text: String): String = text.filter { it.isDigit() || it == ',' || it == ' ' }

/**
 * Applies a typed NR-ARFCN: the chosen band is kept while it still contains the channel, otherwise the first
 * band that does is picked. The subcarrier spacing follows that band's default unless the user picked one by hand.
 */
private fun CellForm.withNrArfcn(text: String): CellForm {
    val digits = text.filter(Char::isDigit)
    val candidates = digits.toIntOrNull()?.let { Bands.nrBandsOf(it) } ?: emptyList()
    val band = nrBand?.takeIf { it in candidates } ?: candidates.firstOrNull()
    val spacing = if (band === null || nrScsPicked) nrScs else Bands.nrDefaultScs(band)
    return copy(nrArfcn = digits, nrBand = band, nrScs = spacing)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CellLockForm(
    state: UiState,
    vm: MainViewModel,
) {
    val form = state.form
    val locksLte = form.rat != LockRat.NR
    val locksNr = form.rat != LockRat.LTE
    Panel {
        ConnectedChoice(
            options = LockRat.entries,
            selected = form.rat,
            label = { it.label },
            onSelect = { selected -> vm.updateForm { copy(rat = selected) } },
        )

        if (locksLte) LteLockFields(state, vm)
        if (locksNr) NrLockFields(state, vm)

        Text(cellLockHint(state), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        val buttonHeight = ButtonDefaults.MediumContainerHeight
        Button(
            onClick = vm::lockCells,
            enabled = !state.busy,
            shapes = ButtonDefaults.shapes(),
            contentPadding = ButtonDefaults.contentPaddingFor(buttonHeight),
            modifier = Modifier.fillMaxWidth().height(buttonHeight).padding(top = 4.dp),
        ) {
            Icon(BunnyIcons.Lock, contentDescription = null, modifier = Modifier.size(ButtonDefaults.iconSizeFor(buttonHeight)))
            Spacer(Modifier.width(10.dp))
            Text("Lock", style = ButtonDefaults.textStyleFor(buttonHeight))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LteLockFields(
    state: UiState,
    vm: MainViewModel,
) {
    val form = state.form
    Text(if (form.rat == LockRat.NSA) "LTE anchor" else "LTE", style = MaterialTheme.typography.titleSmallEmphasized)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            form.lteEarfcn,
            { text -> vm.updateForm { copy(lteEarfcn = text.filter(Char::isDigit)) } },
            label = { Text("EARFCN") },
            singleLine = true,
            keyboardOptions = NumberKeyboard,
            modifier = Modifier.weight(1f),
            supportingText = { form.lteEarfcn.toIntOrNull()?.let { Text(lteChannelHint(it)) } },
        )
        OutlinedTextField(
            form.ltePcis,
            { text -> vm.updateForm { copy(ltePcis = pciInput(text)) } },
            label = { Text("PCIs") },
            singleLine = true,
            modifier = Modifier.weight(1f),
            enabled = state.supportsPciLock,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NrLockFields(
    state: UiState,
    vm: MainViewModel,
) {
    val form = state.form
    Text(if (form.rat == LockRat.NSA) "NR secondary (SCG)" else "5G NR", style = MaterialTheme.typography.titleSmallEmphasized)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            form.nrArfcn,
            { text -> vm.updateForm { withNrArfcn(text) } },
            label = { Text("NR-ARFCN") },
            singleLine = true,
            keyboardOptions = NumberKeyboard,
            modifier = Modifier.weight(1f),
            supportingText = { form.nrArfcn.toIntOrNull()?.let { Text("%.2f MHz".format(Bands.nrFreqMhz(it))) } },
        )
        OutlinedTextField(
            form.nrPcis,
            { text -> vm.updateForm { copy(nrPcis = pciInput(text)) } },
            label = { Text("PCIs") },
            singleLine = true,
            modifier = Modifier.weight(1f),
            enabled = state.supportsPciLock,
        )
    }
    if (form.nrCandidates.size > 1) {
        Text("Band", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ToggleGrid(
            options = form.nrCandidates,
            isChecked = { it == form.nrBand },
            label = { "n$it" },
            onToggle = { band -> vm.updateForm { copy(nrBand = band, nrScs = Bands.nrDefaultScs(band), nrScsPicked = false) } },
            columns = 4,
        )
    }
    if (state.backend == Backend.QUALCOMM) {
        Text("Subcarrier spacing (kHz)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ConnectedChoice(
            options = listOf(15, 30, 60, 120),
            selected = form.nrScs,
            label = { it.toString() },
            onSelect = { spacing -> vm.updateForm { copy(nrScs = spacing, nrScsPicked = true) } },
        )
    }
}
