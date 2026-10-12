@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.goveon.app.ui

import androidx.compose.animation.animateContentSize
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
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.goveon.app.lighting.*
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

internal val coreCapabilities = setOf("powerSwitch", "brightness", "colorRgb", "colorTemperatureK")

@Composable
internal fun DeviceSheet(device: LightDevice, busy: Boolean, onDismiss: () -> Unit, onRefresh: () -> Unit,
    onSend: (LightCommand) -> Unit, onRename: (String) -> Unit, onForget: () -> Unit,
    onSaveAction: (List<LightCommand>) -> Unit, lastResponse: CommandResult? = null, now: Long = System.currentTimeMillis(),
    onConnectBluetooth: () -> Unit = {}, onBluetoothModel: (String) -> Unit = {}, sessionStatus: BleSessionStatus? = null) {
    var rename by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf(false) }
    var editingModel by remember { mutableStateOf(false) }
    var more by rememberSaveable(device.id) { mutableStateOf(false) }
    var about by rememberSaveable(device.id) { mutableStateOf(false) }
    var feedbackVisible by remember(device.id, lastResponse) { mutableStateOf(lastResponse != null) }
    LaunchedEffect(device.id, lastResponse) {
        if (lastResponse?.successful == true) { delay(2500); feedbackVisible = false }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(device.displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(device.model, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { rename = true }) { Icon(Icons.Outlined.Edit, "Rename light") }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Close controls") }
            }
            if (device.bleAddress != null) {
                BluetoothSessionFeedback(sessionStatus, device.bleConnection == BleConnection.CONNECTED, onConnectBluetooth)
                if ((device.bleProfile == null || device.bleProfile == "unclassified") && device.bleConnection == BleConnection.CONNECTED)
                    TextButton(onClick = { editingModel = true }, enabled = !busy) { Text("Set model") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (busy) "Updating…" else reportedState(device.state, now), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                IconButton(onClick = onRefresh, enabled = !busy && (device.bleAddress == null || device.bleConnection == BleConnection.CONNECTED || device.ip != null)) { Icon(Icons.Outlined.Refresh, "Refresh light state", Modifier.size(20.dp)) }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!busy && feedbackVisible) lastResponse?.let { response ->
                if (response.successful) Text(commandSummary(response), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else InlineFeedback(commandSummary(response), error = true)
            }
            if (device.bleAddress != null && coreCapabilities.none { device.supports(it) }) {
                if (device.bleConnection == BleConnection.CONNECTED) Text("Controls unavailable for this model.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else ControlPanel(listOf(device), !busy && (device.bleAddress == null || device.bleConnection == BleConnection.CONNECTED || device.ip != null), onSend)
            HorizontalDivider()
            TextButton(onClick = { more = !more }, modifier = Modifier.fillMaxWidth()) {
                Text("More"); Spacer(Modifier.width(8.dp)); Icon(if (more) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            }
            if (more) {
                val snapshot = stateCommands(device)
                OutlinedButton(onClick = { onSaveAction(snapshot) }, enabled = snapshot.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Bolt, null); Spacer(Modifier.width(8.dp)); Text("Save as quick action")
                }
                TextButton(onClick = { about = !about }, modifier = Modifier.fillMaxWidth()) { Text("About this light"); Spacer(Modifier.width(8.dp)); Icon(if (about) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null) }
                if (about) {
                    Text("Model ${device.model}", style = MaterialTheme.typography.bodySmall)
                    if (device.bleAddress != null) Text("Nearby Bluetooth control without an account.", style = MaterialTheme.typography.bodySmall)
                    device.bleRssi?.let { Text("Last scan signal · $it dBm", style = MaterialTheme.typography.bodySmall) }
                    if (device.state.lastUpdated > 0) Text("Last reported ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(device.state.lastUpdated))}", style = MaterialTheme.typography.bodySmall)
                    if (device.ip != null && GoveeBleProtocol.temperatureValues(device.bleProfile).isEmpty())
                        Text("Available white temperatures vary by model.", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { forgetting = true }, modifier = Modifier.fillMaxWidth()) { Text("Forget this light", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (rename) NameDialog("Rename light", device.displayName, { rename = false }) { onRename(it); rename = false }
    if (editingModel) NameDialog("Lighting model from label", device.model.takeIf(GoveeBleProtocol::canProbe) ?: "", { editingModel = false }) { onBluetoothModel(it); editingModel = false }
    if (forgetting) AlertDialog(onDismissRequest = { forgetting = false }, title = { Text("Forget ${device.displayName}?") },
        text = { Text("This removes the saved device and its group memberships. Discovery may find it again.") },
        confirmButton = { TextButton(onClick = { onForget(); forgetting = false }) { Text("Forget") } },
        dismissButton = { TextButton(onClick = { forgetting = false }) { Text("Cancel") } })
}

private fun reportedState(state: LightState, now: Long) = when {
    state.online == false -> if (state.hasReading) "Offline · last reading" else "No state response"
    state.power == true -> "On${state.brightness?.let { " · $it%" } ?: ""}"
    state.power == false -> "Off"
    else -> "State unknown"
}.let { if (state.online != false && state.hasReading && state.isStale(now)) "$it · last reading" else it }

internal fun stateCommands(device: LightDevice): List<LightCommand> = buildList {
    device.state.power?.takeIf { device.supports("powerSwitch") }?.let { add(LightCommand.Power(it)) }
    device.state.brightness?.takeIf { device.supports("brightness") }?.let { add(LightCommand.Brightness(it)) }
    if (device.state.temperatureKelvin != null && device.state.temperatureKelvin > 0 && device.supports("colorTemperatureK")) add(LightCommand.Temperature(device.state.temperatureKelvin))
    else if (device.state.temperatureKelvin == null || device.state.temperatureKelvin == 0) device.state.rgb?.takeIf { device.supports("colorRgb") }?.let { add(LightCommand.Color(it)) }
}

@Composable
internal fun GroupSheet(group: LightGroup, devices: List<LightDevice>, busy: Boolean, dismiss: () -> Unit, send: (LightCommand) -> Unit, edit: () -> Unit, connectBluetooth: () -> Unit,
    failureReport: GroupResult? = null, reviewFailures: () -> Unit = {}, sessions: Map<String, BleSessionStatus> = emptyMap()) {
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(group.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = edit) { Icon(Icons.Outlined.Edit, "Edit group") }
                IconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, "Close group") }
            }
            Text("${devices.size} lights", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val bluetoothMembers = devices.filter { it.bleAddress != null }
            if (bluetoothMembers.isNotEmpty()) {
                Text("Bluetooth · ${bluetoothMembers.count { it.bleConnection == BleConnection.CONNECTED }}/${bluetoothMembers.size} connected", style = MaterialTheme.typography.titleSmall)
                val stopped = bluetoothMembers.mapNotNull { sessions[it.id] }.filter { it.phase == BleSessionPhase.STOPPED }
                val needsSetup = stopped.any { it.reason in setOf(BleFailureReason.PERMISSION, BleFailureReason.RADIO_UNAVAILABLE) }
                if (needsSetup || stopped.any { it.reason == BleFailureReason.RETRYABLE }) FilledTonalButton(onClick = connectBluetooth, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.BluetoothConnected, null); Spacer(Modifier.width(8.dp)); Text(if (needsSetup) "Enable Bluetooth" else "Retry unavailable lights")
                } else if (bluetoothMembers.any { sessions[it.id]?.phase in setOf(BleSessionPhase.CONNECTING, BleSessionPhase.RETRYING) })
                    Text("Connecting nearby lights…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            failureReport?.let { report -> InlineFeedback("${report.failures.size} lights need attention", error = true) {
                TextButton(onClick = reviewFailures) { Text("Review") }
            } }
            val ready = devices.any { it.bleAddress == null || it.bleConnection == BleConnection.CONNECTED || it.ip != null }
            ControlPanel(devices, !busy && ready, send)
        }
    }
}

@Composable
private fun BluetoothSessionFeedback(status: BleSessionStatus?, connected: Boolean, retry: () -> Unit) {
    when {
        status?.phase == BleSessionPhase.STOPPED -> {
            val message = when (status.reason) {
                BleFailureReason.PERMISSION -> "Allow Bluetooth access to connect."
                BleFailureReason.RADIO_UNAVAILABLE -> "Bluetooth is unavailable. Turn it on to connect."
                BleFailureReason.UNSUPPORTED -> "Bluetooth control is unavailable for this model."
                BleFailureReason.AUTHENTICATION -> "This model's connection method is not supported yet."
                else -> "Couldn't reconnect. Check the light's power and move closer."
            }
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (status.reason !in setOf(BleFailureReason.UNSUPPORTED, BleFailureReason.AUTHENTICATION)) FilledTonalButton(onClick = retry) {
                Text(if (status.reason in setOf(BleFailureReason.PERMISSION, BleFailureReason.RADIO_UNAVAILABLE)) "Enable Bluetooth" else "Retry connection")
            }
        }
        connected -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.BluetoothConnected, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Connected", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(if (status?.phase == BleSessionPhase.RETRYING) "Reconnecting…" else "Connecting…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ControlPanel(devices: List<LightDevice>, enabled: Boolean, send: (LightCommand) -> Unit) {
    fun supports(instance: String) = devices.isNotEmpty() && devices.all { it.supports(instance) }
    val current = devices.singleOrNull()?.state ?: LightState()
    if (supports("powerSwitch")) {
        Text("Power", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = { send(LightCommand.Power(true)) }, enabled = enabled, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.PowerSettingsNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("On")
            }
            OutlinedButton(onClick = { send(LightCommand.Power(false)) }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("Off") }
        }
    }
    if (supports("brightness")) {
        val min = 1
        val max = 100
        if (min < max) ValueSlider("Brightness", current.brightness ?: 50, min..max, "%", enabled) { send(LightCommand.Brightness(it)) }
    }
    val colorSupported = supports("colorRgb")
    val whiteSupported = supports("colorTemperatureK")
    val range = if (whiteSupported) sharedTemperatureRange(devices) else null
    val whiteValues = if (range != null) sharedTemperatureValues(devices, range) else null
    val whiteAvailable = range != null && (whiteValues == null || whiteValues.isNotEmpty())
    val controlIdentity = devices.map { it.id }.sorted().joinToString()
    var savedRgb by rememberSaveable(controlIdentity) { mutableIntStateOf(current.rgb?.takeIf { (current.temperatureKelvin ?: 0) == 0 } ?: 0xFFFFFF) }
    var savedWhite by rememberSaveable(controlIdentity) { mutableIntStateOf(current.temperatureKelvin?.takeIf { it > 0 } ?: 4000) }
    LaunchedEffect(controlIdentity, current.rgb, current.temperatureKelvin) {
        current.rgb?.takeIf { (current.temperatureKelvin ?: 0) == 0 }?.let { savedRgb = it }
        current.temperatureKelvin?.takeIf { it > 0 }?.let { savedWhite = it }
    }
    var whiteMode by rememberSaveable(controlIdentity, colorSupported, whiteAvailable) {
        mutableStateOf(whiteAvailable && (!colorSupported || (current.temperatureKelvin ?: 0) > 0))
    }
    var modeTouched by rememberSaveable(controlIdentity) { mutableStateOf(false) }
    LaunchedEffect(current.temperatureKelvin, whiteAvailable) {
        if (!modeTouched && current.temperatureKelvin != null)
            whiteMode = whiteAvailable && (!colorSupported || current.temperatureKelvin > 0)
    }
    fun selectedWhite(): Int {
        val desired = savedWhite
        return whiteValues?.minByOrNull { kotlin.math.abs(it - desired) } ?: desired.coerceIn(requireNotNull(range))
    }
    if (colorSupported && whiteAvailable) {
        Text("Light mode", style = MaterialTheme.typography.titleMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(selected = !whiteMode, enabled = enabled, shape = SegmentedButtonDefaults.itemShape(0, 2),
                onClick = { if (whiteMode) { modeTouched = true; whiteMode = false; send(LightCommand.Color(savedRgb)) } },
                icon = { SegmentedButtonDefaults.Icon(!whiteMode) { Icon(Icons.Outlined.Palette, null, Modifier.size(18.dp)) } }) { Text("RGB") }
            SegmentedButton(selected = whiteMode, enabled = enabled, shape = SegmentedButtonDefaults.itemShape(1, 2),
                onClick = { if (!whiteMode) { modeTouched = true; whiteMode = true; send(LightCommand.Temperature(selectedWhite())) } },
                icon = { SegmentedButtonDefaults.Icon(whiteMode) { Icon(Icons.Outlined.WbSunny, null, Modifier.size(18.dp)) } }) { Text("White") }
        }
    }
    if (whiteAvailable && (whiteMode || !colorSupported)) {
        WhiteControl(selectedWhite(), requireNotNull(range), whiteValues, enabled) { savedWhite = it; send(LightCommand.Temperature(it)) }
    } else if (colorSupported) ColorControl(savedRgb, enabled) { savedRgb = it; send(LightCommand.Color(it)) }
    if (coreCapabilities.none { supports(it) }) Text("These lights don't share supported controls.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun sharedTemperatureRange(devices: List<LightDevice>): IntRange? {
    val ranges = devices.map { device ->
        val deviceRanges = listOfNotNull(GoveeBleProtocol.temperatureRange(device.bleProfile))
        if (deviceRanges.isEmpty()) {
            if (device.ip != null) 2000..9000 else return null
        } else deviceRanges.maxOf { it.first }..deviceRanges.minOf { it.last }
    }
    if (ranges.isEmpty()) return null
    val min = ranges.maxOf { it.first }
    val max = ranges.minOf { it.last }
    return (min..max).takeIf { min <= max }
}

private fun sharedTemperatureValues(devices: List<LightDevice>, range: IntRange): List<Int>? {
    val bleLists = devices.map { GoveeBleProtocol.temperatureValues(it.bleProfile) }.filter { it.isNotEmpty() }
    if (bleLists.isEmpty() && range.first != range.last) return null
    val candidates = bleLists.firstOrNull() ?: range.toList()
    return candidates.distinct().sorted().filter { kelvin -> kelvin in range && bleLists.all { kelvin in it } }
}

@Composable
private fun WhiteControl(initial: Int, range: IntRange, values: List<Int>?, enabled: Boolean, send: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("White temperature", style = MaterialTheme.typography.titleMedium)
        Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(16.dp)).background(
            Brush.horizontalGradient(listOf(Color(0xFFFFBD72), Color(0xFFFFF1D9), Color(0xFFE3EEFF)))))
        if (values != null && values.size > 1) {
            var index by remember(initial, values) { mutableIntStateOf(values.indices.minBy { kotlin.math.abs(values[it] - initial) }) }
            Row { Text("Warm", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium); Text("${values[index]} K", color = MaterialTheme.colorScheme.primary); Text("Cool", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, textAlign = androidx.compose.ui.text.style.TextAlign.End) }
            Slider(index.toFloat(), { index = it.roundToInt().coerceIn(values.indices) }, valueRange = 0f..values.lastIndex.toFloat(),
                steps = (values.size - 2).coerceAtLeast(0), enabled = enabled, onValueChangeFinished = { send(values[index]) },
                modifier = Modifier.semantics { contentDescription = "White temperature" })
        } else if (values?.size == 1) Text("${values.first()} K", style = MaterialTheme.typography.titleMedium)
        else ValueSlider("Temperature", initial, range, " K", enabled, send)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Warm" to 2700, "Balanced" to 4000, "Cool" to 6500).forEach { (name, desired) ->
                val kelvin = values?.minByOrNull { kotlin.math.abs(it - desired) } ?: desired.coerceIn(range)
                OutlinedButton(onClick = { send(kelvin) }, enabled = enabled, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 12.dp)) { Text(name, maxLines = 1) }
            }
        }
    }
}

@Composable
internal fun ValueSlider(title: String, initial: Int, range: IntRange, unit: String, enabled: Boolean, change: (Int) -> Unit) {
    var value by remember(initial, range.first, range.last) { mutableFloatStateOf(initial.coerceIn(range).toFloat()) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row { Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)); Text("${value.roundToInt()}$unit", color = MaterialTheme.colorScheme.primary) }
        Slider(value, { value = it }, enabled = enabled, valueRange = range.first.toFloat()..range.last.toFloat(),
            onValueChangeFinished = { change(value.roundToInt()) }, modifier = Modifier.semantics { contentDescription = title })
    }
}

@Composable
private fun ColorControl(initial: Int?, enabled: Boolean, send: (Int) -> Unit) {
    val hsv = remember(initial) { FloatArray(3).also { android.graphics.Color.colorToHSV((initial ?: 0xA078FF) or 0xFF000000.toInt(), it) } }
    var hue by remember(initial) { mutableFloatStateOf(hsv[0]) }
    var saturation by remember(initial) { mutableFloatStateOf(hsv[1]) }
    var value by remember(initial) { mutableFloatStateOf(hsv[2]) }
    val rgb = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)) and 0xFFFFFF
    var hex by remember(rgb) { mutableStateOf("%06X".format(rgb)) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val colors = listOf(0xFFAF45, 0xFF5276, 0xB16CFF, 0x557BFF, 0x42D7EF, 0x60DB9A)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Color", style = MaterialTheme.typography.titleMedium)
        Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(16.dp)).background(Color(rgb or 0xFF000000.toInt()))
            .semantics { contentDescription = "Selected color #%06X".format(rgb) })
        Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Brush.horizontalGradient(
            listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red))))
        Text("Hue", style = MaterialTheme.typography.labelLarge)
        Slider(hue, { hue = it }, enabled = enabled, valueRange = 0f..360f, onValueChangeFinished = { send(rgb) }, modifier = Modifier.semantics { contentDescription = "Color hue" })
        Text("Saturation · ${(saturation * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
        Slider(saturation, { saturation = it }, enabled = enabled, onValueChangeFinished = { send(rgb) }, modifier = Modifier.semantics { contentDescription = "Color saturation" })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            colors.forEach { color ->
                IconButton(onClick = { send(color) }, enabled = enabled, modifier = Modifier.size(48.dp).clip(CircleShape).background(Color(color or 0xFF000000.toInt()))
                    .semantics { contentDescription = "Set color #%06X".format(color) }) { }
            }
        }
        TextButton(onClick = { advanced = !advanced }) { Text("Advanced color"); Spacer(Modifier.width(8.dp)); Icon(if (advanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null) }
        if (advanced) {
            Text("Color intensity · ${(value * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
            Slider(value, { value = it }, enabled = enabled, onValueChangeFinished = { send(rgb) }, modifier = Modifier.semantics { contentDescription = "Color intensity" })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(hex, { hex = it.removePrefix("#").take(6) }, label = { Text("Hex color") }, prefix = { Text("#") }, singleLine = true,
                    isError = hex.length != 6 || hex.toIntOrNull(16) == null, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = { hex.toIntOrNull(16)?.let(send) }, enabled = enabled && hex.length == 6 && hex.toIntOrNull(16) != null) { Text("Apply") }
            }
        }
    }
}

@Composable
internal fun NameDialog(title: String, initial: String, dismiss: () -> Unit, save: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) },
        text = { OutlinedTextField(name, { name = it.take(80) }, label = { Text("Name") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { save(name.trim()) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
