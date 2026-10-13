package dev.ririxi.bunnylock.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.ririxi.bunnylock.ui.theme.dynamicColorAvailable

private val AT_COMMAND_PRESETS = listOf("ATI", "AT+EPBSE?", "AT+EPBSEH?", "AT+EMMCHLCK?", "AT+GOOGGETNV=\"NR.CONFIG.MODE\"")

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ToolsScreen(
    state: UiState,
    vm: MainViewModel,
    contentPadding: PaddingValues,
) {
    var atCommand by rememberSaveable { mutableStateOf("") }
    // The QMI console starts on NAS "get system info" (service 3, message 0x004D), a read-only request.
    var qmiService by rememberSaveable { mutableStateOf("3") }
    var qmiMessage by rememberSaveable { mutableStateOf("004D") }
    var qmiPayload by rememberSaveable { mutableStateOf("") }
    var checkedServicesOpen by rememberSaveable { mutableStateOf(false) }
    val arrowAngle by animateFloatAsState(if (checkedServicesOpen) 180f else 0f, label = "arrow")

    LazyColumn(contentPadding = contentPadding.withScreenMargins(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            SectionHeader("Backend")
            Segmented {
                Backend.entries.forEach { backend ->
                    val selected = state.backend == backend
                    val needsRoot = !state.allows(backend)
                    val hint =
                        when {
                            needsRoot -> "Needs root"
                            state.autoBackend == backend -> "Detected for this device"
                            else -> null
                        }
                    item { shapes ->
                        SegmentedListItem(
                            selected = selected,
                            onClick = { vm.setBackend(backend) },
                            enabled = !needsRoot,
                            shapes = shapes,
                            colors = segmentColors(),
                            supportingContent = if (hint !== null) ({ Text(hint) }) else null,
                            trailingContent = if (selected) ({ Icon(Icons.Rounded.Check, contentDescription = null) }) else null,
                        ) { Text(backend.label) }
                    }
                }
                if (state.backend == Backend.QUALCOMM) {
                    item {
                        SwitchItem(
                            it,
                            "Keep band locks after reboot",
                            "When this is off, locks are cleared on reboot.",
                            checked = state.persist,
                            onCheckedChange = vm::setPersist,
                        )
                    }
                }
            }
        }

        if (dynamicColorAvailable) {
            item {
                SectionHeader("Appearance")
                Segmented {
                    item {
                        SwitchItem(
                            it,
                            "Dynamic color",
                            "Use your wallpaper colors. Off uses the default blue theme.",
                            checked = state.dynamicColor,
                            onCheckedChange = vm::setDynamicColor,
                        )
                    }
                }
            }
        }

        item {
            SectionHeader("Updates")
            Segmented {
                item {
                    SwitchItem(
                        it,
                        "Check for updates",
                        "Looks for a new release on GitHub when the app starts.",
                        checked = state.checkUpdates,
                        onCheckedChange = vm::setCheckUpdates,
                    )
                }
            }
        }

        item {
            SectionHeader("Radio")
            Segmented {
                item {
                    ActionItem(
                        it,
                        "Restart radio",
                        "Turns airplane mode on and off again.",
                        Icons.Rounded.Refresh,
                        enabled = !state.busy,
                        onClick = vm::radioCycle,
                    )
                }
                if (state.backend == Backend.QUALCOMM) {
                    item {
                        ActionItem(
                            it,
                            "Re-attach",
                            "Drops the data connection and attaches again over QMI.",
                            Icons.Rounded.Refresh,
                            enabled = !state.busy,
                            onClick = vm::reattach,
                        )
                    }
                }
                item {
                    ActionItem(
                        it,
                        "Reboot modem",
                        "This restarts the SIM too.",
                        Icons.Rounded.Warning,
                        enabled = !state.busy,
                        onClick = vm::rebootModem,
                    )
                }
                if (state.device?.samsungMenu == true) {
                    item {
                        ActionItem(
                            it,
                            "Samsung band menu",
                            "Opens Samsung's hidden band settings.",
                            Icons.Rounded.Settings,
                            enabled = true,
                            onClick = vm::samsungMenu,
                        )
                    }
                }
            }
        }

        item {
            SectionHeader("AT console")
            Panel {
                OutlinedTextField(
                    state.atNode,
                    vm::setAtNode,
                    label = { Text("AT port") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text(atNodesHint(state.device)) },
                )
                OutlinedTextField(
                    atCommand,
                    { atCommand = it },
                    label = { Text("Command") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(
                            onClick = { if (atCommand.isNotBlank()) vm.sendAt(atCommand.trim()) },
                            enabled = !state.busy && state.atNode.isNotBlank(),
                        ) { Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send") }
                    },
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AT_COMMAND_PRESETS.forEach { preset ->
                        FilledTonalButton(onClick = { atCommand = preset }, shapes = ButtonDefaults.shapes()) { Text(preset) }
                    }
                }
            }
        }

        if (state.backend == Backend.QUALCOMM) {
            item {
                SectionHeader("QMI console")
                Panel {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            qmiService,
                            { qmiService = it.filter(Char::isDigit) },
                            label = { Text("Service") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            qmiMessage,
                            { qmiMessage = it.filter(Char::isLetterOrDigit) },
                            label = { Text("Message (hex)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    OutlinedTextField(
                        qmiPayload,
                        { qmiPayload = it },
                        label = { Text("TLV payload (hex)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("NAS (service 3) talks to the selected SIM.") },
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    val service = qmiService.toIntOrNull()
                                    val message = qmiMessage.toIntOrNull(16)
                                    if (service !== null && message !== null) vm.sendQmi(service, message, qmiPayload)
                                },
                                enabled = !state.busy,
                            ) { Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send") }
                        },
                    )
                }
            }
        }

        if (state.toolOutput.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth()) {
                    SectionHeader("Output", Modifier.weight(1f))
                    TextButton(onClick = vm::clearOutput) { Text("Clear") }
                }
                MonospacePanel(state.toolOutput)
            }
        }

        item {
            val device = state.device
            SectionHeader("Device")
            Segmented {
                item {
                    ActionItem(
                        it,
                        "Copy diagnostics",
                        "Device details, the passthrough check and the log",
                        Icons.Rounded.Share,
                        enabled = true,
                        onClick = vm::copyDiagnostics,
                    )
                }
                item { InfoItem(it, "Access", state.access?.label.orEmpty()) }
                if (device !== null) {
                    item { InfoItem(it, "Modem command passthrough", device.mtkOemHookStatus) }
                    if (device.mtkOemHookChecked.isNotEmpty()) {
                        item { shapes ->
                            val checkedServicesText: @Composable () -> Unit = {
                                Text(device.mtkOemHookChecked.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                            }
                            SegmentedListItem(
                                onClick = { checkedServicesOpen = !checkedServicesOpen },
                                shapes = shapes,
                                colors = segmentColors(),
                                supportingContent = if (checkedServicesOpen) checkedServicesText else null,
                                trailingContent = {
                                    Icon(
                                        Icons.Rounded.KeyboardArrowDown,
                                        contentDescription = if (checkedServicesOpen) "Collapse" else "Expand",
                                        modifier = Modifier.rotate(arrowAngle),
                                    )
                                },
                            ) {
                                Text(
                                    "Services checked (${device.mtkOemHookChecked.size})",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                item { InfoItem(it, "Modem", device?.vendorLabel.orEmpty()) }
                item { InfoItem(it, "SoC", device?.socModel.orEmpty()) }
                item { InfoItem(it, "Platform", device?.platform.orEmpty()) }
                item { InfoItem(it, "RIL", device?.ril.orEmpty()) }
                item { InfoItem(it, "Baseband", device?.baseband.orEmpty()) }
                item { InfoItem(it, "Android SDK", device?.sdk?.toString().orEmpty()) }
                item { InfoItem(it, "QMI over QRTR", if (device?.qrtr == true) "Yes" else "No") }
                item { InfoItem(it, "HAL channel-lock API", if (device?.aospChannels == true) "Present" else "Absent") }
            }
        }

        if (state.log.isNotEmpty()) {
            item {
                SectionHeader("Log")
                MonospacePanel(state.log.joinToString("\n"))
            }
        }

        item {
            SectionHeader("About")
            About()
        }
    }
}

private val BUNNY_FACTS =
    listOf(
        "A rabbit's teeth never stop growing, so they have to keep chewing to wear them down.",
        "A happy rabbit does a \"binky\": a jump with a twist in mid-air.",
        "Rabbits can see almost all the way around themselves. Their one blind spot is right in front of the nose.",
        "Rabbits can't vomit, so they're very careful about what they eat.",
        "A content rabbit \"purrs\" by gently grinding its teeth.",
        "Baby rabbits are called kits.",
        "Rabbits are most active at dawn and dusk.",
        "Those big ears also work as radiators and help a rabbit cool down.",
        "A group of rabbits is sometimes called a fluffle.",
        "A thumping rabbit is warning everyone nearby that something seems off.",
    )

private const val TAPS_FOR_FACT = 5

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun About() {
    val context = LocalContext.current
    val version =
        remember {
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
        }
    var taps by rememberSaveable { mutableIntStateOf(0) }
    val fact = if (taps >= TAPS_FOR_FACT) BUNNY_FACTS[(taps - TAPS_FOR_FACT) % BUNNY_FACTS.size] else null
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LockBadge(locked = fact !== null, size = 56.dp, spin = true)
            Column(Modifier.weight(1f)) {
                Wordmark(
                    style = MaterialTheme.typography.headlineMediumEmphasized,
                    modifier =
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { taps++ },
                )
                Text("by @ririxi (BTSearch.pl developer)", style = MaterialTheme.typography.bodyMedium)
                if (version.isNotEmpty()) {
                    Text(
                        "Version $version",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        AnimatedVisibility(visible = fact !== null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(BunnyIcons.Bunny, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                Text(
                    "Bunny fact: ${fact.orEmpty()}",
                    style = MaterialTheme.typography.bodyMediumEmphasized,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun atNodesHint(device: DeviceInfo?): String {
    val rilPorts = if (device?.mtkOemHook == true) listOf("ril:1 and ril:2 (through the RIL)") else emptyList()
    val nodes = device?.atNodes.orEmpty() + rilPorts
    return if (nodes.isEmpty()) "No AT ports found." else "Found ${andList(nodes)}."
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ActionItem(
    shapes: ListItemShapes,
    title: String,
    subtitle: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    SegmentedListItem(
        onClick = onClick,
        enabled = enabled,
        shapes = shapes,
        colors = segmentColors(),
        leadingContent = { Icon(icon, contentDescription = null) },
        supportingContent = { Text(subtitle) },
    ) { Text(title) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SwitchItem(
    shapes: ListItemShapes,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SegmentedListItem(
        checked = checked,
        onCheckedChange = onCheckedChange,
        shapes = shapes,
        colors = segmentColors(),
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    ) { Text(title) }
}

@Composable
private fun MonospacePanel(text: String) {
    Panel {
        SelectionContainer {
            Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}
