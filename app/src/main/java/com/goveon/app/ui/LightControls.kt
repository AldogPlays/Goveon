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
import org.json.JSONObject
import org.json.JSONArray
import org.json.JSONTokener
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

internal val coreCapabilities = setOf("powerSwitch", "brightness", "colorRgb", "colorTemperatureK")

@Composable
internal fun DeviceSheet(device: LightDevice, busy: Boolean, onDismiss: () -> Unit, onRefresh: () -> Unit,
    onScenes: () -> Unit, onSend: (LightCommand) -> Unit, onRename: (String) -> Unit, onForget: () -> Unit,
    onSaveAction: (List<LightCommand>) -> Unit, scenesLoading: Boolean = false, lastResponse: CommandResult? = null, now: Long = System.currentTimeMillis(),
    onConnectBluetooth: () -> Unit = {}, onDisconnectBluetooth: () -> Unit = {}, onBluetoothModel: (String) -> Unit = {}, sessionStatus: BleSessionStatus? = null) {
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
                IconButton(onClick = onRefresh, enabled = !busy && (device.bleAddress == null || device.bleConnection == BleConnection.CONNECTED || device.ip != null || device.cloudAvailable)) { Icon(Icons.Outlined.Refresh, "Refresh light state", Modifier.size(20.dp)) }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!busy && feedbackVisible) lastResponse?.let { response ->
                if (response.successful) Text(commandSummary(response), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else InlineFeedback(commandSummary(response), error = true)
            }
            if (device.bleAddress != null && coreCapabilities.none { device.supports(it) }) {
                if (device.bleConnection == BleConnection.CONNECTED) Text("Controls unavailable for this model.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else ControlPanel(listOf(device), !busy && (device.bleAddress == null || device.bleConnection == BleConnection.CONNECTED || device.ip != null || device.cloudAvailable), onSend)
            val extra = device.capabilities.filter { it.instance !in coreCapabilities }
            if (extra.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Scenes & effects", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (device.cloudAvailable) TextButton(onClick = onScenes, enabled = !busy && !scenesLoading) { Text(if (scenesLoading) "Loading…" else "Refresh") }
                }
            }
            extra.forEach { capability -> CapabilityControl(capability, device.state.values[capability.instance], !busy, onSend) }
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
                    if (device.ip != null && device.capability("colorTemperatureK") == null && GoveeBleProtocol.temperatureValues(device.bleProfile).isEmpty())
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
internal fun GroupSheet(group: LightGroup, devices: List<LightDevice>, busy: Boolean, dismiss: () -> Unit, send: (LightCommand) -> Unit, edit: () -> Unit, loadScenes: () -> Unit, connectBluetooth: () -> Unit,
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
            val ready = devices.any { it.bleAddress == null || it.bleConnection == BleConnection.CONNECTED || it.ip != null || it.cloudAvailable }
            ControlPanel(devices, !busy && ready, send)
            if (devices.all { it.cloudAvailable } && devices.isNotEmpty()) {
                TextButton(onClick = loadScenes, enabled = !busy) { Text("Load shared scenes") }
                commonCapabilities(devices).forEach { CapabilityControl(it, null, !busy, send) }
            }
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
        val min = devices.mapNotNull { it.capability("brightness")?.min }.maxOrNull()?.coerceAtLeast(if (devices.any { it.ip != null || GoveeBleProtocol.supports(it.bleProfile, "brightness") }) 1 else 0) ?: 1
        val max = devices.mapNotNull { it.capability("brightness")?.max }.minOrNull()?.coerceAtMost(100) ?: 100
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
        val advertised = device.capability("colorTemperatureK")
        val ble = GoveeBleProtocol.temperatureRange(device.bleProfile)
        val deviceRanges = listOfNotNull(ble, if (advertised?.min != null && advertised.max != null) advertised.min..advertised.max else null)
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
    val quantized = devices.any { device -> device.capability("colorTemperatureK")?.let { JSONObject(it.parametersJson).optJSONObject("range")?.optInt("precision", 1) ?: 1 }?.let { it > 1 } == true }
    if (bleLists.isEmpty() && !quantized && range.first != range.last) return null
    val candidates = bleLists.firstOrNull() ?: range.toList()
    return candidates.distinct().sorted().filter { kelvin -> kelvin in range && bleLists.all { kelvin in it } && devices.all { device ->
        val cap = device.capability("colorTemperatureK")
        val precision = cap?.let { JSONObject(it.parametersJson).optJSONObject("range")?.optInt("precision", 1) } ?: 1
        precision <= 1 || (kelvin - (cap?.min ?: 0)) % precision == 0
    } }
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
internal fun CapabilityControl(capability: LightCapability, current: String?, enabled: Boolean, send: (LightCommand) -> Unit) {
    var expanded by rememberSaveable(capability.instance) { mutableStateOf(false) }
    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.animateContentSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(prettyCapability(capability.instance), style = MaterialTheme.typography.titleMedium)
                    capability.options.firstOrNull { it.valueJson == current }?.let { Text(it.name, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                }
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "Collapse" else "Expand")
            }
            if (expanded) {
                if (capability.options.isNotEmpty() && JSONObject(capability.parametersJson).optString("dataType").equals("ARRAY", true)) {
                    ArraySelector(capability.options, enabled, commit = true) { send(LightCommand.Capability(capability.instance, it)) }
                } else if (capability.options.isNotEmpty()) {
                    OptionSelector("Select ${prettyCapability(capability.instance)}", capability.options, enabled) { send(LightCommand.Capability(capability.instance, it)) }
                } else if (capability.min != null && capability.max != null && capability.min < capability.max) {
                    ValueSlider(prettyCapability(capability.instance), current?.toIntOrNull() ?: capability.min, capability.min..capability.max, "", enabled) {
                        send(LightCommand.Capability(capability.instance, it.toString()))
                    }
                } else if (capability.fields.isNotEmpty()) StructuredCapability(capability, enabled, send)
                else RawCapability(capability, current, enabled, send)
            }
        }
    }
}

private fun prettyCapability(value: String) = value.replace(Regex("([a-z])([A-Z])"), "$1 $2").replaceFirstChar { it.uppercase() }

@Composable
private fun OptionSelector(label: String, options: List<CapabilityOption>, enabled: Boolean, choose: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(selected ?: label, modifier = Modifier.weight(1f)); Icon(Icons.Outlined.ArrowDropDown, null)
        }
    }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(label) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            val filtered = remember(options, query) { options.filter { it.name.contains(query, ignoreCase = true) } }
            LazyColumn(Modifier.fillMaxWidth().height(280.dp)) {
                if (filtered.isEmpty()) item { Text("No matching options") }
                items(filtered) { option -> ListItem(headlineContent = { Text(option.name) }, modifier = Modifier.clickable {
                    selected = option.name; open = false; choose(option.valueJson)
                }) }
            }
        }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } })
}

/** Only expose advanced values that every chosen model advertises identically or shares safely. */
internal fun commonCapabilities(devices: List<LightDevice>): List<LightCapability> {
    if (devices.isEmpty()) return emptyList()
    return devices.first().capabilities.filter { it.instance !in coreCapabilities }.mapNotNull { first ->
        val caps = devices.map { it.capability(first.instance) ?: return@mapNotNull null }
        if (caps.any { it.type != first.type }) return@mapNotNull null
        if (caps.all { it.parametersJson == first.parametersJson }) return@mapNotNull first
        if (first.options.isNotEmpty() && caps.all { it.options.isNotEmpty() }) {
            val shared = first.options.filter { option -> caps.all { cap -> cap.options.any { it.valueJson == option.valueJson } } }
            if (shared.isEmpty()) null else first.copy(options = shared)
        } else if (first.fields.isEmpty() && caps.all { it.min != null && it.max != null && JSONObject(it.parametersJson).optJSONObject("range")?.optInt("precision", 1) == 1 }) {
            val minimum = caps.maxOf { it.min!! }
            val maximum = caps.minOf { it.max!! }
            if (minimum >= maximum) null else first.copy(min = minimum, max = maximum)
        } else null
    }
}

@Composable
private fun StructuredCapability(capability: LightCapability, enabled: Boolean, send: (LightCommand) -> Unit) {
    val fields = remember(capability.instance) { mutableStateMapOf<String, String>() }
    var error by remember { mutableStateOf<String?>(null) }
    capability.fields.forEach { field ->
        Text("${prettyCapability(field.name)}${if (field.required) " *" else ""}", style = MaterialTheme.typography.labelLarge)
        when {
            field.dataType.equals("ARRAY", true) && field.options.isNotEmpty() -> ArraySelector(field.options, enabled) { fields[field.name] = it }
            field.options.isNotEmpty() -> OptionSelector("Choose ${field.name}", field.options, enabled) { fields[field.name] = it }
            field.min != null && field.max != null && field.min < field.max -> {
                val initial = fields[field.name]?.toIntOrNull() ?: field.min
                LaunchedEffect(field.name) { if (field.required && field.name !in fields) fields[field.name] = field.min.toString() }
                ValueSlider(prettyCapability(field.name), initial, field.min..field.max, "", enabled) { fields[field.name] = it.toString() }
            }
            else -> OutlinedTextField(fields[field.name] ?: "", { fields[field.name] = it }, label = { Text("${field.name} (${field.dataType})") },
                supportingText = { Text(if (field.dataType.contains("Array", true)) "JSON array, for example [0,1]" else "Value from this device's schema") }, modifier = Modifier.fillMaxWidth(), enabled = enabled)
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    FilledTonalButton(onClick = {
        try {
            val json = JSONObject()
            capability.fields.forEach { field ->
                val raw = fields[field.name]?.trim().orEmpty()
                require(!field.required || raw.isNotEmpty()) { "Choose ${field.name}." }
                if (raw.isNotEmpty()) json.put(field.name, if (field.dataType.equals("STRING", true)) raw.removeSurrounding("\"") else JSONTokener(raw).nextValue())
            }
            send(LightCommand.Capability(capability.instance, json.toString())); error = null
        } catch (e: Exception) { error = e.message ?: "Check the values." }
    }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Apply ${prettyCapability(capability.instance)}") }
    RawCapability(capability, null, enabled, send)
}

@Composable
private fun ArraySelector(options: List<CapabilityOption>, enabled: Boolean, commit: Boolean = false, choose: (String) -> Unit) {
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { option -> FilterChip(selected = option.valueJson in selected, enabled = enabled, onClick = {
            selected = if (option.valueJson in selected) selected - option.valueJson else selected + option.valueJson
            val array = JSONArray()
            selected.forEach { array.put(JSONTokener(it).nextValue()) }
            if (!commit) choose(array.toString())
        }, label = { Text(option.name) }) }
    }
    if (commit) FilledTonalButton(onClick = {
        val array = JSONArray()
        selected.forEach { array.put(JSONTokener(it).nextValue()) }
        choose(array.toString())
    }, enabled = enabled && selected.isNotEmpty()) { Text("Apply selected") }
}

@Composable
private fun RawCapability(capability: LightCapability, current: String?, enabled: Boolean, send: (LightCommand) -> Unit) {
    var open by rememberSaveable(capability.instance) { mutableStateOf(false) }
    var raw by remember(capability.instance) { mutableStateOf(current ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    TextButton(onClick = { open = !open }) { Text(if (open) "Hide advanced JSON" else "Advanced JSON value") }
    if (open) {
        Text("Supply the value described by this device's capability schema.", style = MaterialTheme.typography.bodySmall)
        Text(capability.parametersJson, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(raw, { raw = it }, label = { Text("JSON value") }, isError = error != null, supportingText = { error?.let { Text(it) } }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        OutlinedButton(onClick = {
            try {
                require(raw.isNotBlank()) { "Enter a JSON value." }
                JSONTokener(raw).nextValue()
                send(LightCommand.Capability(capability.instance, raw)); error = null
            } catch (e: Exception) { error = "Enter valid JSON: ${e.message}" }
        }, enabled = enabled) { Text("Send value") }
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
