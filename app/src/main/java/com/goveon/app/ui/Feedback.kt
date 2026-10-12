package com.goveon.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.goveon.app.lighting.CommandResult
import com.goveon.app.lighting.CommandStatus

/** Product feedback remains short. Transport details belong in diagnostic logs. */
internal fun commandSummary(result: CommandResult): String = when (result.status) {
    CommandStatus.CONFIRMED -> if (result.message.contains("connect", true)) "Bluetooth connected" else "Light updated"
    CommandStatus.ACCEPTED -> if (result.message.contains("connect", true)) "Bluetooth connected" else "Command sent"
    CommandStatus.FAILED -> failureSummary(result.message)
}

internal fun failureSummary(message: String): String = when {
    message.contains("permission", true) -> "Allow Bluetooth access to connect."
    message.contains("bluetooth", true) && (message.contains("off", true) || message.contains("disabled", true)) -> "Enable Bluetooth to connect."
    message.contains("authentication", true) -> "This model's connection method is not supported yet."
    message.contains("unsupported", true) || message.contains("not supported", true) -> "This setting is unavailable for this light."
    message.contains("connect", true) -> "Reconnect the light and try again."
    message.contains("offline", true) || message.contains("no LAN reply", true) -> "The light is unavailable. Check its power and connection."
    message.contains("range", true) || message.contains("precision", true) -> "Choose a value within this light's supported range."
    message.contains("API key", true) -> "Add an API key for optional cloud control."
    else -> "Couldn't complete that action. Try again."
}

internal fun briefNotice(message: String): String = if (message.length <= 120 && !message.contains('\n')) message
    else if (message.contains("failed", true) || message.contains("error", true)) "Couldn't complete that action. Check the light's connection."
    else "Action completed."

@Composable
internal fun InlineFeedback(message: String, error: Boolean = false, onDismiss: (() -> Unit)? = null, action: (@Composable () -> Unit)? = null) {
    Surface(color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = if (onDismiss == null) 14.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(if (error) Icons.Outlined.Info else Icons.Outlined.CheckCircle, null, Modifier.size(18.dp),
                tint = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.primary)
            Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 3)
            action?.invoke()
            onDismiss?.let { dismiss -> IconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, "Dismiss message", Modifier.size(18.dp)) } }
        }
    }
}
