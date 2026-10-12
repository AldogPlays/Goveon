@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.goveon.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.goveon.app.lighting.*

@Composable
internal fun GroupEditor(group: LightGroup?, devices: List<LightDevice>, dismiss: () -> Unit, save: (String, Set<String>) -> Unit, delete: () -> Unit) {
    var name by rememberSaveable(group?.id) { mutableStateOf(group?.name ?: "") }
    var targets by remember(group?.id) { mutableStateOf(group?.deviceIds ?: emptySet()) }
    var deleting by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            EditorTitle(if (group == null) "Create a group" else "Edit group", dismiss)
            OutlinedTextField(name, { name = it.take(80) }, label = { Text("Group name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Choose lights", style = MaterialTheme.typography.titleMedium)
            DeviceChoices(devices, targets) { id -> targets = if (id in targets) targets - id else targets + id }
            Button(onClick = { save(name.trim(), targets) }, enabled = name.isNotBlank() && targets.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Save group") }
            if (group != null) TextButton(onClick = { deleting = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete group", color = MaterialTheme.colorScheme.error) }
        }
    }
    if (deleting) DeleteDialog("Delete group?", "The lights remain in your library.", { deleting = false }, delete)
}

@Composable
internal fun ActionEditor(action: QuickAction?, devices: List<LightDevice>, dismiss: () -> Unit,
    save: (String, Set<String>, List<LightCommand>) -> Unit, delete: () -> Unit, loadScenes: (Set<String>) -> Unit) {
    var name by rememberSaveable(action?.id) { mutableStateOf(action?.name ?: "") }
    var targets by remember(action?.id) { mutableStateOf(action?.deviceIds ?: emptySet()) }
    var commands by remember(action?.id) { mutableStateOf(action?.commands ?: emptyList()) }
    var deleting by remember { mutableStateOf(false) }
    val selected = devices.filter { it.id in targets }
    val compatible = commands.all { command -> selected.all { it.supports(commandInstance(command)) } }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            EditorTitle(if (action?.id.isNullOrBlank()) "Create an action" else "Edit action", dismiss)
            OutlinedTextField(name, { name = it.take(80) }, label = { Text("Action name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Choose lights", style = MaterialTheme.typography.titleMedium)
            DeviceChoices(devices, targets) { id -> targets = if (id in targets) targets - id else targets + id }
            HorizontalDivider()
            Text("Your recipe", style = MaterialTheme.typography.titleLarge)
            Text("Adjust a control to add its command. Nothing is sent until you run the saved action.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (commands.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                commands.forEach { command -> InputChip(selected = true, onClick = { commands = commands - command },
                    label = { Text(recipeLabel(command)) }, trailingIcon = { Icon(Icons.Outlined.Close, "Remove ${recipeLabel(command)}", Modifier.size(16.dp)) }) }
            }
            if (!compatible) Text("A selected light does not support one of these commands. Remove that command or change your lights.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            val addCommand: (LightCommand) -> Unit = { command ->
                val replace = if (command is LightCommand.Color || command is LightCommand.Temperature) setOf("colorRgb", "colorTemperatureK") else setOf(commandInstance(command))
                commands = commands.filter { commandInstance(it) !in replace } + command
            }
            if (selected.isNotEmpty()) {
                ControlPanel(selected, true, addCommand)
                if (selected.all { it.cloudAvailable }) {
                    TextButton(onClick = { loadScenes(targets) }) { Text("Load scenes & effects") }
                    commonCapabilities(selected).forEach { CapabilityControl(it, null, true, addCommand) }
                }
            } else Text("Select at least one light to choose controls.")
            Button(onClick = { save(name.trim(), targets, commands) }, enabled = name.isNotBlank() && targets.isNotEmpty() && commands.isNotEmpty() && compatible,
                modifier = Modifier.fillMaxWidth()) { Text("Save quick action") }
            if (!action?.id.isNullOrBlank()) TextButton(onClick = { deleting = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete quick action", color = MaterialTheme.colorScheme.error) }
        }
    }
    if (deleting) DeleteDialog("Delete quick action?", "This removes the saved recipe.", { deleting = false }, delete)
}

private fun commandInstance(command: LightCommand) = when (command) {
    is LightCommand.Power -> "powerSwitch"
    is LightCommand.Brightness -> "brightness"
    is LightCommand.Color -> "colorRgb"
    is LightCommand.Temperature -> "colorTemperatureK"
    is LightCommand.Capability -> command.instance
}
private fun recipeLabel(command: LightCommand) = when (command) {
    is LightCommand.Power -> if (command.on) "On" else "Off"
    is LightCommand.Brightness -> "Brightness ${command.percent}%"
    is LightCommand.Color -> "#%06X".format(command.rgb and 0xFFFFFF)
    is LightCommand.Temperature -> "${command.kelvin} K"
    is LightCommand.Capability -> command.instance
}

@Composable
private fun EditorTitle(title: String, dismiss: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        IconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, "Close editor") }
    }
}

@Composable
private fun DeviceChoices(devices: List<LightDevice>, targets: Set<String>, toggle: (String) -> Unit) {
    devices.forEach { device ->
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(Modifier.fillMaxWidth().toggleable(device.id in targets, role = Role.Checkbox, onValueChange = { toggle(device.id) }).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(device.id in targets, onCheckedChange = null)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(device.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(device.model, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun DeleteDialog(title: String, message: String, dismiss: () -> Unit, delete: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = delete) { Text("Delete") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
internal fun SettingsScreen(state: AppState, busy: Set<String>, set: (AppSettings) -> Unit, saveKey: (String) -> Unit) {
    // Credentials deliberately use transient composition state, never saved instance state.
    var apiKey by remember { mutableStateOf("") }
    var removeKey by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Make yourself at home.", style = MaterialTheme.typography.headlineMedium)
        Text("Appearance", style = MaterialTheme.typography.titleLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ThemeMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(selected = state.settings.theme == mode, onClick = { set(state.settings.copy(theme = mode)) },
                    shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size)) {
                    Text(mode.name.lowercase().replaceFirstChar { it.uppercase() })
                }
            }
        }
        SettingToggle("Wallpaper colors", "Material You dynamic colors on Android 12+", state.settings.dynamicColor) { set(state.settings.copy(dynamicColor = it)) }
        HorizontalDivider()
        Text("Connection", style = MaterialTheme.typography.titleLarge)
        SettingToggle("Prefer local control", "Use Wi-Fi LAN where supported; cloud is available as a fallback.", state.settings.preferLan) { set(state.settings.copy(preferLan = it)) }
        Card(shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Key, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text("Optional Developer API", style = MaterialTheme.typography.titleMedium)
                }
                Text(if (state.hasApiKey) "A key is saved securely on this device." else "Add your Govee Developer API key for cloud devices, scenes, and model-specific controls.", style = MaterialTheme.typography.bodyMedium)
                Text("Request a key in Govee Home → Settings → Apply for API Key. Goveon does not need your Govee account password.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(apiKey, { apiKey = it }, label = { Text(if (state.hasApiKey) "Replace API key" else "API key") },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { saveKey(apiKey); apiKey = "" }, enabled = apiKey.isNotBlank() && "api-key" !in busy, modifier = Modifier.fillMaxWidth()) { Text("Save key") }
                if (state.hasApiKey) TextButton(onClick = { removeKey = true }, modifier = Modifier.fillMaxWidth()) { Text("Remove API key") }
            }
        }
        HorizontalDivider()
        Text("Goveon · 0.1.0", style = MaterialTheme.typography.titleMedium)
        Text("An independent, lightweight light controller. Your names, groups, and quick actions stay on this device. Device support depends on LAN availability and the capabilities returned by Govee's Developer API.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Local changes may be accepted without acknowledgement. Refresh a light to check its reported state. Groups send concurrently, but exact hardware synchronization is not guaranteed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (removeKey) AlertDialog(onDismissRequest = { removeKey = false }, title = { Text("Remove API key?") },
        text = { Text("Cloud control requires a key. LAN devices remain available.") },
        confirmButton = { TextButton(onClick = { saveKey(""); removeKey = false }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { removeKey = false }) { Text("Cancel") } })
}

@Composable
private fun SettingToggle(title: String, detail: String, checked: Boolean, set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = set).heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = null)
    }
}
