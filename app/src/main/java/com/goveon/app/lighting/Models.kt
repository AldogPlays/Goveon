package com.goveon.app.lighting

import org.json.JSONObject

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class BleConnection { DISCONNECTED, CONNECTING, CONNECTED }
data class AppSettings(val theme: ThemeMode = ThemeMode.SYSTEM, val dynamicColor: Boolean = true, val preferLan: Boolean = true)
data class CapabilityOption(val name: String, val valueJson: String)
data class CapabilityField(val name: String, val dataType: String, val required: Boolean, val options: List<CapabilityOption> = emptyList(), val min: Int? = null, val max: Int? = null)
data class LightCapability(
    val type: String, val instance: String, val parametersJson: String,
    val options: List<CapabilityOption> = emptyList(), val fields: List<CapabilityField> = emptyList(),
    val min: Int? = null, val max: Int? = null,
    /** Original device-list options, kept separate so refreshing dynamic catalogs removes retired entries. */
    val staticOptions: List<CapabilityOption> = options,
)
data class LightState(
    val power: Boolean? = null, val brightness: Int? = null, val rgb: Int? = null,
    val temperatureKelvin: Int? = null, val online: Boolean? = null,
    val lastUpdated: Long = 0, val values: Map<String, String> = emptyMap(),
) {
    /** UI should show a last-reading label once data is stale, rather than claiming live status. */
    fun isStale(now: Long = System.currentTimeMillis()): Boolean = lastUpdated == 0L || now - lastUpdated > 120_000L
    val hasReading: Boolean get() = power != null || brightness != null || rgb != null || temperatureKelvin != null
}
data class LightDevice(
    val id: String, val model: String, val name: String, val ip: String? = null,
    val cloudAvailable: Boolean = false, val capabilities: List<LightCapability> = emptyList(),
    val state: LightState = LightState(), val customName: String? = null,
    val bleAddress: String? = null, val bleConnection: BleConnection = BleConnection.DISCONNECTED,
    val bleRssi: Int? = null, val bleProfile: String? = null,
) {
    val displayName: String get() = customName?.takeIf { it.isNotBlank() } ?: name
    fun capability(instance: String): LightCapability? = capabilities.firstOrNull { it.instance == instance }
    fun supports(instance: String): Boolean = capability(instance) != null || (ip != null && instance in setOf("powerSwitch", "brightness", "colorRgb", "colorTemperatureK")) || GoveeBleProtocol.supports(bleProfile, instance)
}
data class LightGroup(val id: String, val name: String, val deviceIds: Set<String>)
data class QuickAction(val id: String, val name: String, val deviceIds: Set<String>, val commands: List<LightCommand>)
data class AppState(
    val devices: List<LightDevice> = emptyList(), val groups: List<LightGroup> = emptyList(),
    val quickActions: List<QuickAction> = emptyList(), val settings: AppSettings = AppSettings(),
    val isScanning: Boolean = false, val hasApiKey: Boolean = false, val error: String? = null,
    val isBluetoothScanning: Boolean = false,
)

sealed interface LightCommand {
    data class Power(val on: Boolean) : LightCommand
    data class Brightness(val percent: Int) : LightCommand
    /** Packed 24-bit RGB (0xRRGGBB), without alpha. */
    data class Color(val rgb: Int) : LightCommand
    data class Temperature(val kelvin: Int) : LightCommand
    /** Exact schema-driven value; scene values come from the API, never invented. */
    data class Capability(val instance: String, val valueJson: String) : LightCommand
}
enum class CommandStatus { CONFIRMED, ACCEPTED, FAILED }
data class CommandResult(val deviceId: String, val status: CommandStatus, val message: String, val transport: String? = null) {
    val successful: Boolean get() = status != CommandStatus.FAILED
}
data class GroupResult(val results: List<CommandResult>) {
    val successful: Boolean get() = results.isNotEmpty() && results.all { it.successful }
    val failures: List<CommandResult> get() = results.filterNot { it.successful }
}

internal fun normalizedId(value: String) = value.trim().uppercase()
internal fun jsonValue(value: Any?): String = JSONObject().put("value", value ?: JSONObject.NULL).toString().let { it.substring(9, it.length - 1) }
