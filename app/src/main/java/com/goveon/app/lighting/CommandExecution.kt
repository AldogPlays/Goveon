package com.goveon.app.lighting

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Independent targets run concurrently; commands for the same target preserve order. */
internal class CommandLocks {
    private val locks = ConcurrentHashMap<String, Mutex>()
    suspend fun <T> withLock(id: String, block: suspend () -> T): T = locks.getOrPut(id) { Mutex() }.withLock { block() }
}

internal fun validateRecipe(devices: List<LightDevice>, commands: List<LightCommand>) {
    require(devices.isNotEmpty() && commands.isNotEmpty()) { "Choose lights and at least one control." }
    for (device in devices) for (command in commands) {
        require(device.supports(command.instance())) { "A selected light does not support this control." }
        if (GoveeBleProtocol.supports(device.bleProfile, command.instance())) GoveeBleProtocol.command(requireNotNull(device.bleProfile), command)
        else GoveeLanProtocol.command(command)
    }
}

/** Missing status fields do not establish that a delivered setting was rejected. */
internal fun lanCommandResult(id: String, command: LightCommand, reading: LightState?): CommandResult {
    val relevant = when (command) {
        is LightCommand.Power -> reading?.power
        is LightCommand.Brightness -> reading?.brightness
        is LightCommand.Color -> reading?.rgb
        is LightCommand.Temperature -> reading?.temperatureKelvin
    }
    return when {
        relevant == null -> CommandResult(id, CommandStatus.ACCEPTED, "UDP command sent; the light did not confirm this setting.", "LAN")
        matches(command, requireNotNull(reading)) -> CommandResult(id, CommandStatus.CONFIRMED, "Light reported the requested state.", "LAN")
        else -> CommandResult(id, CommandStatus.FAILED, "The light reported a different value. This model may not support the setting.", "LAN")
    }
}

/** The repository holds its device lock for the whole recipe, preserving command order. */
internal suspend fun executeRecipe(id: String, commands: List<LightCommand>, send: suspend (String, LightCommand) -> CommandResult): CommandResult {
    var result = failure(id, "This quick action has no commands.")
    for (command in commands) {
        result = send(id, command)
        if (!result.successful) break
    }
    return result
}
