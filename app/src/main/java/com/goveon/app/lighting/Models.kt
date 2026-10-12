package com.goveon.app.lighting

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class BleConnection { DISCONNECTED, CONNECTING, CONNECTED }
data class AppSettings(val theme: ThemeMode = ThemeMode.SYSTEM, val dynamicColor: Boolean = true)
data class LightState(
    val power: Boolean? = null, val brightness: Int? = null, val rgb: Int? = null,
    val temperatureKelvin: Int? = null, val online: Boolean? = null, val lastUpdated: Long = 0,
) {
    fun isStale(now: Long = System.currentTimeMillis()): Boolean = lastUpdated == 0L || now - lastUpdated > 120_000L
    val hasReading: Boolean get() = power != null || brightness != null || rgb != null || temperatureKelvin != null
}
data class LightDevice(
    val id: String, val model: String, val name: String, val ip: String? = null,
    val state: LightState = LightState(), val customName: String? = null,
    val bleAddress: String? = null, val bleConnection: BleConnection = BleConnection.DISCONNECTED,
    val bleRssi: Int? = null, val bleProfile: String? = null,
) {
    val displayName: String get() = customName?.takeIf { it.isNotBlank() } ?: name
    fun supports(instance: String): Boolean = (ip != null && instance in setOf("powerSwitch", "brightness", "colorRgb", "colorTemperatureK")) || GoveeBleProtocol.supports(bleProfile, instance)
}
data class LightGroup(val id: String, val name: String, val deviceIds: Set<String>)
data class QuickAction(val id: String, val name: String, val deviceIds: Set<String>, val commands: List<LightCommand>)
data class AppState(
    val devices: List<LightDevice> = emptyList(), val groups: List<LightGroup> = emptyList(),
    val quickActions: List<QuickAction> = emptyList(), val settings: AppSettings = AppSettings(),
    val isScanning: Boolean = false, val error: String? = null, val isBluetoothScanning: Boolean = false,
)
sealed interface LightCommand {
    data class Power(val on: Boolean) : LightCommand
    data class Brightness(val percent: Int) : LightCommand
    /** Packed 24-bit RGB (0xRRGGBB), without alpha. */
    data class Color(val rgb: Int) : LightCommand
    data class Temperature(val kelvin: Int) : LightCommand
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
