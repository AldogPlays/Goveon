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

internal val coreCapabilities = setOf("powerSwitch", "brightness", "colorRgb", "colorTemperatureK")

@Composable
internal fun DeviceSheet(device: LightDevice, busy: Boolean, onDismiss: () -> Unit, onRefresh: () -> Unit,
    onScenes: () -> Unit, onSend: (LightCommand) -> Unit, onRename: (String) -> Unit, onForget: () -> Unit,
    onSaveAction: (List<LightCommand>) -> Unit, scenesLoading: Boolean = false, lastResponse: CommandResult? = null, now: Long = System.currentTimeMillis()) {
    var rename by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(device.displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text("${device.model} · ${if (device.ip != null) "LAN available" else "Cloud"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { rename = true }) { Icon(Icons.Outlined.Edit, "Rename light") }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Close controls") }
            }
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (busy) "Sending / waiting…" else reportedState(device.state, now), style = MaterialTheme.typography.titleSmall)
                        Text(if (device.state.lastUpdated > 0) "Reported ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(device.state.lastUpdated))}" else "No state response yet", style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = onRefresh, enabled = !busy) { Icon(Icons.Outlined.Refresh, "Refresh light state") }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            lastResponse?.let { response ->
                Surface(shape = RoundedCornerShape(16.dp), color = if (response.successful) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(if (response.status == CommandStatus.CONFIRMED) Icons.Outlined.CheckCircle else Icons.Outlined.Info, null, Modifier.size(20.dp))
                        Text(response.message, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            ControlPanel(listOf(device), !busy, onSend)
            val extra = device.capabilities.filter { it.instance !in coreCapabilities }
            if (device.cloudAvailable) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Scenes & effects", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onScenes, enabled = !busy && !scenesLoading) { Text(if (scenesLoading) "Loading…" else "Load scenes") }
                }
            }
            extra.forEach { capability -> CapabilityControl(capability, device.state.values[capability.instance], !busy, onSend) }
            if (extra.isEmpty()) Text("Additional effects appear when this device reports them through the Developer API. LAN alone exposes basic light controls.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val snapshot = stateCommands(device)
            OutlinedButton(onClick = { onSaveAction(snapshot) }, enabled = snapshot.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Bolt, null); Spacer(Modifier.width(8.dp)); Text("Save reported state as action")
            }
            Text("${device.id}${device.ip?.let { "\n$it" } ?: ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { forgetting = true }, modifier = Modifier.fillMaxWidth()) { Text("Forget this light", color = MaterialTheme.colorScheme.error) }
        }
    }
    if (rename) NameDialog("Rename light", device.displayName, { rename = false }) { onRename(it); rename = false }
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
    else device.state.rgb?.takeIf { device.supports("colorRgb") }?.let { add(LightCommand.Color(it)) }
}

@Composable
internal fun GroupSheet(group: LightGroup, devices: List<LightDevice>, busy: Boolean, dismiss: () -> Unit, send: (LightCommand) -> Unit, edit: () -> Unit, loadScenes: () -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(group.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = edit) { Icon(Icons.Outlined.Edit, "Edit group") }
                IconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, "Close group") }
            }
            Text(devices.joinToString { it.displayName }, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Commands are sent concurrently. Network timing can differ between lights; failures are reported individually.", style = MaterialTheme.typography.bodySmall)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            ControlPanel(devices, !busy, send)
            if (devices.all { it.cloudAvailable } && devices.isNotEmpty()) {
                TextButton(onClick = loadScenes, enabled = !busy) { Text("Load shared scenes") }
                commonCapabilities(devices).forEach { CapabilityControl(it, null, !busy, send) }
            }
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
        val min = devices.mapNotNull { it.capability("brightness")?.min }.maxOrNull()?.coerceAtLeast(if (devices.any { it.ip != null }) 1 else 0) ?: 1
        val max = devices.mapNotNull { it.capability("brightness")?.max }.minOrNull()?.coerceAtMost(100) ?: 100
        if (min < max) ValueSlider("Brightness", current.brightness ?: 50, min..max, "%", enabled) { send(LightCommand.Brightness(it)) }
    }
    if (supports("colorRgb")) ColorControl(current.rgb, enabled) { send(LightCommand.Color(it)) }
    if (supports("colorTemperatureK")) {
        val min = devices.mapNotNull { it.capability("colorTemperatureK")?.min }.maxOrNull() ?: 2000
        val max = devices.mapNotNull { it.capability("colorTemperatureK")?.max }.minOrNull() ?: 9000
        if (min < max) ValueSlider("White temperature", current.temperatureKelvin?.takeIf { it in min..max } ?: min, min..max, " K", enabled) { send(LightCommand.Temperature(it)) }
        if (devices.any { it.capability("colorTemperatureK") == null }) Text("LAN does not report a temperature range. This light may support only part of 2000–9000 K.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (coreCapabilities.none { supports(it) }) Text("No common basic controls. Open individual lights for their supported capabilities.")
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
        Text("Color intensity · ${(value * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
        Slider(value, { value = it }, enabled = enabled, onValueChangeFinished = { send(rgb) }, modifier = Modifier.semantics { contentDescription = "Color intensity" })
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(hex, { hex = it.removePrefix("#").take(6) }, label = { Text("Hex color") }, prefix = { Text("#") }, singleLine = true,
                isError = hex.length != 6 || hex.toIntOrNull(16) == null, modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = { hex.toIntOrNull(16)?.let(send) }, enabled = enabled && hex.length == 6 && hex.toIntOrNull(16) != null) { Text("Apply") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            colors.forEach { color ->
                IconButton(onClick = { send(color) }, enabled = enabled, modifier = Modifier.size(48.dp).clip(CircleShape).background(Color(color or 0xFF000000.toInt()))
                    .semantics { contentDescription = "Set color #%06X".format(color) }) { }
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
                    if (current != null) Text("Reported: $current", style = MaterialTheme.typography.bodySmall, maxLines = 2)
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
