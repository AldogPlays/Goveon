package com.goveon.app.lighting

import kotlin.math.ceil

/** Independently encoded from REA observations of the supplied Govee 6.2.01 APK.
 * Protocol/model evidence and remaining hardware checks: docs/ble-protocol.md. */
object GoveeBleProtocol {
    data class Profile(val id: String, val serviceUuid: String, val writeUuid: String, val models: Set<String>, val controls: Set<String>)
    const val SERVICE = "00010203-0405-0607-0809-0a0b0c0d1910"
    const val WRITE = "00010203-0405-0607-0809-0a0b0c0d2b11"
    private val core = setOf("powerSwitch", "brightness")
    // Packaged categories.json maps H6005 to the registered bulb-v2 product type 20.
    val profiles = listOf(
        Profile("bulb-v2", SERVICE, WRITE, setOf("H6005"), core + setOf("colorRgb", "colorTemperatureK")),
        // APK categories product type 64, registered to the bulb-v3 controller family.
        Profile("bulb-v3", SERVICE, WRITE, setOf("H6004", "H6006", "H6008", "H6009", "H600A", "H6010", "H600D"), core + setOf("colorRgb", "colorTemperatureK")),
        Profile("color-0d", SERVICE, WRITE, emptySet(), core + "colorRgb"),
        Profile("common-light", SERVICE, WRITE, emptySet(), core),
        Profile("unclassified", SERVICE, WRITE, emptySet(), emptySet()),
    )
    fun profileFor(model: String): Profile = profiles.firstOrNull { model.uppercase() in it.models } ?: profiles.last()
    fun isKnownProfile(id: String?) = profiles.any { it.id == id }
    fun supports(id: String?, instance: String) = profiles.firstOrNull { it.id == id }?.controls?.contains(instance) == true
    fun temperatureValues(profile: String?): List<Int> = when (profile) {
        "bulb-v2" -> BulbWhiteTemperatureTable.values
        "bulb-v3" -> BulbWhiteTemperatureTable.values.filter { it in 2700..6500 }
        else -> emptyList()
    }
    fun temperatureRange(profile: String?): IntRange? = temperatureValues(profile).takeIf { it.isNotEmpty() }?.let { it.first()..it.last() }
    private fun encodedBrightness(profile: String, percent: Int): Int = if (profile == "bulb-v2")
        (20 + (percent - 1) * (237 / 100.0f)).toInt() else percent
    private fun decodedBrightness(profile: String, raw: Int): Int? = if (profile == "bulb-v2") {
        if (raw in 20..254) (ceil(((raw - 20) / (237 / 100.0f)).toDouble()).toInt() + 1).coerceIn(1, 100) else null
    } else raw.takeIf { it in 0..100 }
    fun modelFromName(name: String): String? = Regex("(?:^|[^A-Z0-9])(H[0-9A-F]{4})(?=$|[^A-Z0-9])", RegexOption.IGNORE_CASE)
        .find(name)?.groupValues?.get(1)?.uppercase()
    fun isCandidate(name: String, services: List<String>): Boolean = name.contains("Govee", true) || name.contains("iHoment", true) || services.any { it.equals(SERVICE, true) }
    /** Additional H6 lighting models must answer BOTH harmless read requests before basic
     * writes become available. An advertisement or UUID resemblance is never enough. */
    fun canProbe(model: String) = model.uppercase().matches(Regex("H6[0-9A-F]{3}"))
    fun probeQueries(): List<ByteArray> = listOf(frame(0xAA, 0x01), frame(0xAA, 0x04))

    /** Zero padding and XOR over bytes 0–18; no payload may overwrite byte 19. */
    fun frame(header: Int, command: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        require(header in 0..255 && command in 0..255 && payload.size <= 17)
        return ByteArray(20).also { bytes ->
            bytes[0] = header.toByte(); bytes[1] = command.toByte()
            payload.copyInto(bytes, 2)
            bytes[19] = checksum(bytes).toByte()
        }
    }
    private fun checksum(bytes: ByteArray): Int = (0 until 19).fold(0) { result, i -> result xor (bytes[i].toInt() and 255) }
    fun valid(bytes: ByteArray) = bytes.size == 20 && checksum(bytes) == (bytes[19].toInt() and 255)
    fun command(profile: String, command: LightCommand): List<ByteArray> {
        require(supports(profile, command.instance())) { "This control is not supported by this Bluetooth profile." }
        val packet = when (command) {
            is LightCommand.Power -> frame(0x33, 0x01, byteArrayOf(if (command.on) 1 else 0))
            is LightCommand.Brightness -> {
                require(command.percent in 1..100) { "Bluetooth brightness must be 1–100%." }
                frame(0x33, 0x04, byteArrayOf(encodedBrightness(profile, command.percent).toByte()))
            }
            is LightCommand.Color -> {
                require(command.rgb in 0..0xFFFFFF) { "Color must contain a 24-bit RGB value." }
                val r = (command.rgb ushr 16).toByte(); val g = (command.rgb ushr 8).toByte(); val b = command.rgb.toByte()
                frame(0x33, 0x05, byteArrayOf(0x0D, r, g, b, 0, 0, 0, 0, 0))
            }
            is LightCommand.Temperature -> {
                require(command.kelvin in temperatureValues(profile)) { "Choose a temperature from this light's supported white range." }
                val white = requireNotNull(BulbWhiteTemperatureTable.rgb(command.kelvin))
                frame(0x33, 0x05, byteArrayOf(0x0D, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
                    (command.kelvin ushr 8).toByte(), command.kelvin.toByte(),
                    (white ushr 16).toByte(), (white ushr 8).toByte(), white.toByte()))
            }
        }
        return listOf(packet)
    }
    fun queries(profile: String): List<ByteArray> = if (supports(profile, "powerSwitch")) probeQueries() + frame(0xAA, 0x05) else emptyList()
    fun initialization(profile: String): List<ByteArray> = emptyList()
    fun matchesColorProfile(packet: ByteArray): Boolean = valid(packet) && (packet[0].toInt() and 255) == 0xAA &&
        (packet[1].toInt() and 255) == 0x05 && (packet[2].toInt() and 255) == 0x0D &&
        (11 until 19).all { packet[it] == 0.toByte() }

    /** Read replies have values at offset 2. Write acknowledgements have a status there,
     * so a 0x33 response must never be presented as a reported power/brightness value. */
    fun parseState(profile: String, packet: ByteArray, now: Long = System.currentTimeMillis()): LightState? {
        if (!valid(packet) || packet[0].toInt() and 255 != 0xAA || !supports(profile, "powerSwitch")) return null
        val value = packet[2].toInt() and 255
        return when (packet[1].toInt() and 255) {
            0x01 -> LightState(power = value != 0, online = true, lastUpdated = now)
            0x04 -> decodedBrightness(profile, value)?.let { LightState(brightness = it, online = true, lastUpdated = now) }
            0x05 -> if (supports(profile, "colorRgb") && matchesColorProfile(packet)) LightState(
                rgb = ((packet[3].toInt() and 255) shl 16) or ((packet[4].toInt() and 255) shl 8) or (packet[5].toInt() and 255),
                temperatureKelvin = ((packet[6].toInt() and 255) shl 8) or (packet[7].toInt() and 255),
                online = true, lastUpdated = now) else null
            else -> null
        }
    }
}
