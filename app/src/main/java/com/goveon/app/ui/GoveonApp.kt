@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.goveon.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.goveon.app.GoveonViewModel
import com.goveon.app.lighting.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONObject
import org.json.JSONTokener
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

private enum class Destination(val label: String, val icon: ImageVector) {
    LIGHTS("Lights", Icons.Outlined.Lightbulb), GROUPS("Groups", Icons.Outlined.Workspaces),
    ACTIONS("Actions", Icons.Outlined.Bolt), SETTINGS("Settings", Icons.Outlined.Tune)
}

@Composable
fun GoveonApp(vm: GoveonViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val failureReport by vm.failureReport.collectAsStateWithLifecycle()
    val responses by vm.lastResponses.collectAsStateWithLifecycle()
    val bluetoothSessions by vm.bluetoothSessions.collectAsStateWithLifecycle()
    val now by freshnessClock()
    GoveonTheme(state.settings) {
        val bluetoothAccess = rememberBluetoothAccess()
        val discoverBluetooth = { bluetoothAccess { vm.discoverBluetooth() } }
        val connectBluetooth: (String) -> Unit = { id -> bluetoothAccess { vm.connectBluetooth(id) } }
        var destination by rememberSaveable { mutableStateOf(Destination.LIGHTS) }
        var deviceId by rememberSaveable { mutableStateOf<String?>(null) }
        var groupId by rememberSaveable { mutableStateOf<String?>(null) }
        var editGroup by remember { mutableStateOf<LightGroup?>(null) }
        var groupEditor by rememberSaveable { mutableStateOf(false) }
        var editAction by remember { mutableStateOf<QuickAction?>(null) }
        var actionEditor by rememberSaveable { mutableStateOf(false) }
        var notice by remember { mutableStateOf<String?>(null) }
        var reviewFailures by remember { mutableStateOf(false) }
        LaunchedEffect(vm) { vm.messages.collect { notice = briefNotice(it) } }
        LaunchedEffect(notice) { if (notice != null) { delay(6000); notice = null } }
        val activeControlIds = deviceId?.let { setOf(it) }
            ?: state.groups.firstOrNull { it.id == groupId }?.deviceIds.orEmpty()
        LaunchedEffect(activeControlIds) { vm.setActiveControls(activeControlIds) }
        DisposableEffect(vm) { onDispose { vm.setActiveControls(emptySet()) } }
        Scaffold(
            topBar = {
                TopAppBar(title = { Text("Goveon", fontWeight = FontWeight.Bold) }, actions = {
                    if (destination == Destination.LIGHTS) {
                        IconButton(onClick = discoverBluetooth, enabled = !state.isBluetoothScanning) {
                            Icon(Icons.AutoMirrored.Outlined.BluetoothSearching, "Discover nearby Bluetooth lights")
                        }
                    }
                })
            },
            bottomBar = {
                NavigationBar {
                    Destination.entries.forEach { item ->
                        NavigationBarItem(selected = destination == item, onClick = { destination = item },
                            icon = { Icon(item.icon, null) }, label = { Text(item.label) })
                    }
                }
            },
        ) { insets ->
            Column(Modifier.padding(insets).fillMaxSize()) {
                AnimatedVisibility(notice != null) {
                    Box(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) { notice?.let { InlineFeedback(it, onDismiss = { notice = null }) } }
                }
                failureReport?.takeIf { deviceId == null && groupId == null }?.let { report ->
                    Box(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                        InlineFeedback("${report.failures.size} lights need attention", true, vm::dismissReport) {
                            TextButton(onClick = { reviewFailures = true }) { Text("Review") }
                        }
                    }
                }
                AnimatedContent(destination, modifier = Modifier.weight(1f).fillMaxWidth(), label = "navigation") { screen ->
                    when (screen) {
                        Destination.LIGHTS -> LightsScreen(state, busy, discoverBluetooth, vm::scan, { deviceId = it }, vm::send, now, connectBluetooth)
                        Destination.GROUPS -> GroupsScreen(state, { editGroup = null; groupEditor = true }, { groupId = it }, { editGroup = it; groupEditor = true })
                        Destination.ACTIONS -> ActionsScreen(state, busy, { editAction = null; actionEditor = true }, vm::runAction, { editAction = it; actionEditor = true })
                        Destination.SETTINGS -> SettingsScreen(state, vm::settings)
                    }
                }
            }
        }
        state.devices.firstOrNull { it.id == deviceId }?.let { device ->
            LaunchedEffect(device.id) {
                if (device.bleAddress == null || device.bleConnection == BleConnection.CONNECTED) vm.refresh(device.id)
            }
            DeviceSheet(device, device.id in busy || "bluetooth:${device.id}" in busy, onDismiss = { deviceId = null },
                onRefresh = { vm.refresh(device.id) },
                onSend = { vm.send(device.id, it) }, onRename = { vm.rename(device.id, it) },
                onForget = { vm.forget(device.id); deviceId = null },
                lastResponse = responses[device.id],
                now = now,
                onConnectBluetooth = { bluetoothAccess { vm.retryBluetoothControls() } },
                sessionStatus = bluetoothSessions[device.id],
                onBluetoothModel = { vm.bluetoothModel(device.id, it) },
                onSaveAction = { commands ->
                    editAction = QuickAction("", "${device.displayName} preset", setOf(device.id), commands)
                    actionEditor = true; deviceId = null
                })
        }
        state.groups.firstOrNull { it.id == groupId }?.let { group ->
            GroupSheet(group, state.devices.filter { it.id in group.deviceIds }, "group:${group.id}" in busy,
                { groupId = null }, { vm.sendGroup(group.id, it) }, { editGroup = group; groupEditor = true; groupId = null },
                { bluetoothAccess { vm.retryBluetoothControls() } },
                failureReport?.takeIf { report -> report.results.any { it.deviceId in group.deviceIds } }, { reviewFailures = true }, bluetoothSessions)
        }
        if (groupEditor) GroupEditor(editGroup, state.devices, { groupEditor = false },
            { name, ids -> vm.saveGroup(editGroup?.id, name, ids); groupEditor = false },
            { editGroup?.let { vm.deleteGroup(it.id) }; groupEditor = false })
        if (actionEditor) ActionEditor(editAction, state.devices, { actionEditor = false },
            { name, ids, commands -> vm.saveAction(editAction?.id?.takeIf { it.isNotBlank() }, name, ids, commands); actionEditor = false },
            { editAction?.let { vm.deleteAction(it.id) }; actionEditor = false })
        if (reviewFailures) failureReport?.let { report ->
            AlertDialog(onDismissRequest = { reviewFailures = false }, title = { Text("Lights needing attention") }, text = {
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(report.failures) { result ->
                        Column {
                            Text(state.devices.firstOrNull { it.id == result.deviceId }?.displayName ?: "Unavailable light", fontWeight = FontWeight.SemiBold)
                            Text(commandSummary(result), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { reviewFailures = false; vm.dismissReport() }) { Text("Done") } })
        }
    }
}

@Composable
private fun LightsScreen(state: AppState, busy: Set<String>, discover: () -> Unit, discoverNetwork: () -> Unit, open: (String) -> Unit, send: (String, LightCommand) -> Unit, now: Long, connect: (String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Hero("Your space,\nyour light.", "${state.devices.size} lights · ${state.devices.count { it.bleConnection == BleConnection.CONNECTED }} Bluetooth connections", Icons.Outlined.WbTwilight) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Your lights", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = discover, enabled = !state.isBluetoothScanning) {
                    if (state.isBluetoothScanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.AutoMirrored.Outlined.BluetoothSearching, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp)); Text(if (state.isBluetoothScanning) "Finding…" else "Discover")
                }
            }
        }
        state.error?.let { item { InlineFeedback(failureSummary(it), error = true) } }
        if (state.devices.isEmpty()) item {
            Card(shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Outlined.Lightbulb, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("Let there be light", style = MaterialTheme.typography.headlineSmall)
                    Text("Power on a light, bring your phone nearby, and tap Discover.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Notice("Connect in Goveon. Bluetooth lights generally use a GATT connection rather than pairing in Android Settings. If a light is busy, close its connection in another app first.")
                    Button(onClick = discover, enabled = !state.isBluetoothScanning, modifier = Modifier.fillMaxWidth()) { Icon(Icons.AutoMirrored.Outlined.BluetoothSearching, null); Spacer(Modifier.width(8.dp)); Text("Discover nearby lights") }
                }
            }
        }
        items(state.devices, key = { it.id }) { device ->
            Card(onClick = { open(device.id) }, shape = RoundedCornerShape(24.dp), modifier = Modifier.animateContentSize()) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    LightOrb(device.state.rgb, device.state.power)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(device.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(device.model, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(statusText(device, now), style = MaterialTheme.typography.labelMedium,
                            color = if (device.state.online == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        Text(connectionLabel(device), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (device.id in busy || "bluetooth:${device.id}" in busy || device.bleConnection == BleConnection.CONNECTING) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    else if (device.bleAddress != null && device.bleConnection != BleConnection.CONNECTED) IconButton(onClick = { connect(device.id) }) {
                        Icon(Icons.Outlined.BluetoothConnected, "Connect to ${device.displayName}", tint = MaterialTheme.colorScheme.primary)
                    }
                    else if (device.supports("powerSwitch")) IconButton(onClick = { send(device.id, LightCommand.Power(device.state.power != true)) }) {
                        Icon(Icons.Outlined.PowerSettingsNew, if (device.state.power == true) "Turn ${device.displayName} off" else "Turn ${device.displayName} on",
                            tint = if (device.state.power == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (state.devices.isNotEmpty()) item { Text("State updates when a light reports it. Bluetooth write acknowledgements and LAN delivery may not confirm a physical change; refresh to check the light.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            OutlinedCard(shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("More ways to connect", style = MaterialTheme.typography.titleMedium)
                    Text("Wi-Fi lights: join their network and enable LAN Control in the device's Govee settings. Bluetooth remains available without Wi-Fi.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = discoverNetwork, enabled = !state.isScanning, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.WifiFind, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (state.isScanning) "Finding Wi-Fi lights…" else "Find Wi-Fi lights")
                    }
                }
            }
        }
    }
}

@Composable
private fun Hero(title: String, subtitle: String, icon: ImageVector) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(
        Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.secondaryContainer)))) {
        Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .65f))
        }
    }
}

@Composable
private fun LightOrb(rgb: Int?, power: Boolean?) {
    val tint = rgb?.let { Color(it or 0xFF000000.toInt()) } ?: MaterialTheme.colorScheme.primary
    Box(Modifier.size(52.dp).clip(CircleShape).background(tint.copy(alpha = if (power == true) .22f else .09f)), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.Lightbulb, null, Modifier.size(28.dp), tint = if (power == true) tint else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun statusText(device: LightDevice, now: Long): String = when {
    device.bleConnection == BleConnection.CONNECTING -> "Opening Bluetooth connection…"
    device.state.online == false -> if (device.state.hasReading) "Offline · last reading" else "No state response"
    device.state.power == true -> "On${device.state.brightness?.let { " · $it%" } ?: ""}"
    device.state.power == false -> "Off"
    else -> "State not yet reported"
}.let { if (device.state.online != false && device.state.hasReading && device.state.isStale(now)) "$it · last reading" else it }

internal fun connectionLabel(device: LightDevice): String = when {
    device.bleConnection == BleConnection.CONNECTED -> "Bluetooth connected"
    device.bleConnection == BleConnection.CONNECTING -> "Bluetooth connecting"
    device.bleAddress != null -> "Bluetooth saved${if (device.ip != null) " · LAN available" else ""}"
    device.ip != null -> "LAN · ${device.ip}"
    else -> "No connection available"
}

@Composable
private fun freshnessClock(): State<Long> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(System.currentTimeMillis(), lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) { value = System.currentTimeMillis(); delay(30_000) }
        }
    }
}

@Composable
private fun Notice(message: String, error: Boolean = false) {
    Surface(color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (error) Icons.Outlined.ErrorOutline else Icons.Outlined.Info, null, Modifier.size(20.dp))
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun GroupsScreen(state: AppState, create: () -> Unit, open: (String) -> Unit, edit: (LightGroup) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Hero("Better together.", "Bring a room into harmony with a single control.", Icons.Outlined.Workspaces) }
        item { Button(onClick = create, enabled = state.devices.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("Create a group") } }
        if (state.groups.isEmpty()) item { Notice(if (state.devices.isEmpty()) "Discover your lights before creating a group." else "Choose any combination of lights. Group controls show settings supported by every member.") }
        items(state.groups, key = { it.id }) { group ->
            Card(onClick = { open(group.id) }, shape = RoundedCornerShape(24.dp)) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Workspaces, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                        Text(group.name, style = MaterialTheme.typography.titleMedium)
                        Text("${group.deviceIds.size} lights", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { edit(group) }) { Icon(Icons.Outlined.Edit, "Edit ${group.name}") }
                }
            }
        }
    }
}

@Composable
private fun ActionsScreen(state: AppState, busy: Set<String>, create: () -> Unit, run: (String) -> Unit, edit: (QuickAction) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Hero("Make it a moment.", "Your favorite lighting, one tap away.", Icons.Outlined.Bolt) }
        item { Button(onClick = create, enabled = state.devices.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("Create a quick action") } }
        if (state.quickActions.isEmpty()) item { Notice(if (state.devices.isEmpty()) "Discover your lights before creating quick actions." else "Create a recipe of power, brightness, color, or temperature commands for your lights. You can also save a device's reported state.") }
        items(state.quickActions, key = { it.id }) { action ->
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(action.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { edit(action) }) { Icon(Icons.Outlined.Edit, "Edit ${action.name}") }
                    }
                    Text("${action.deviceIds.size} lights · ${action.commands.joinToString { commandLabel(it) }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilledTonalButton(onClick = { run(action.id) }, enabled = "action:${action.id}" !in busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.PlayArrow, null); Text(if ("action:${action.id}" in busy) "Sending…" else "Run action")
                    }
                }
            }
        }
    }
}

private fun commandLabel(command: LightCommand): String = when (command) {
    is LightCommand.Power -> if (command.on) "On" else "Off"
    is LightCommand.Brightness -> "${command.percent}%"
    is LightCommand.Color -> "#%06X".format(command.rgb and 0xFFFFFF)
    is LightCommand.Temperature -> "${command.kelvin} K"
}

private fun prettyName(value: String) = value.replace(Regex("([a-z])([A-Z])"), "$1 $2").replace('_', ' ').replaceFirstChar { it.uppercase() }
