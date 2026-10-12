package com.goveon.app.lighting

import org.json.JSONArray
import org.json.JSONObject

/** Public protocol only: https://app-h5.govee.com/user-manual/wlan-guide */
object GoveeLanProtocol {
    const val MULTICAST_ADDRESS = "239.255.255.250"
    const val DISCOVERY_PORT = 4001
    const val RESPONSE_PORT = 4002
    const val CONTROL_PORT = 4003
    fun scan(): String = envelope("scan", JSONObject().put("account_topic", "reserve"))
    fun query(): String = envelope("devStatus", JSONObject())
    fun command(command: LightCommand): String = when (command) {
        is LightCommand.Power -> envelope("turn", JSONObject().put("value", if (command.on) 1 else 0))
        is LightCommand.Brightness -> { require(command.percent in 1..100) { "Brightness must be 1–100%." }; envelope("brightness", JSONObject().put("value", command.percent)) }
        is LightCommand.Color -> { require(command.rgb in 0..0xffffff) { "RGB must be a 24-bit color." }; color(command.rgb, 0) }
        is LightCommand.Temperature -> { require(command.kelvin in 2000..9000) { "LAN temperature must be 2000–9000 K." }; color(0, command.kelvin) }
    }
    private fun color(rgb: Int, kelvin: Int) = envelope("colorwc", JSONObject().put("color", JSONObject().put("r", rgb shr 16 and 255).put("g", rgb shr 8 and 255).put("b", rgb and 255)).put("colorTemInKelvin", kelvin))
    private fun envelope(cmd: String, data: JSONObject) = JSONObject().put("msg", JSONObject().put("cmd", cmd).put("data", data)).toString()
    sealed interface Reply {
        data class Discovery(val id: String, val model: String, val ip: String) : Reply
        data class Status(val state: LightState) : Reply
    }
    fun parse(text: String, senderIp: String): Reply? = runCatching {
        val msg = JSONObject(text).getJSONObject("msg")
        val data = msg.getJSONObject("data")
        when (msg.getString("cmd")) {
            "scan" -> {
                val id = normalizedId(data.getString("device"))
                val model = data.getString("sku").trim()
                if (id.isBlank() || id.length > 128 || model.isBlank() || model.length > 32 || !validIpv4(senderIp)) null
                else Reply.Discovery(id, model, senderIp)
            }
            "devStatus" -> {
                val color = data.optJSONObject("color")
                val rgb = color?.let {
                    val channels = listOf(it.optIntNullable("r"), it.optIntNullable("g"), it.optIntNullable("b"))
                    if (channels.all { channel -> channel != null && channel in 0..255 }) (channels[0]!! shl 16) or (channels[1]!! shl 8) or channels[2]!! else null
                }
                Reply.Status(LightState(power = data.optIntNullable("onOff")?.takeIf { it in 0..1 }?.let { it == 1 }, brightness = data.optIntNullable("brightness")?.takeIf { it in 1..100 }, rgb = rgb, temperatureKelvin = data.optIntNullable("colorTemInKelvin")?.takeIf { it == 0 || it in 2000..9000 }, online = true, lastUpdated = System.currentTimeMillis()))
            }
            else -> null
        }
    }.getOrNull()
}

internal fun JSONObject.optIntNullable(key: String): Int? {
    val value = opt(key) as? Number ?: return null
    val number = value.toDouble()
    return if (number.isFinite() && number >= Int.MIN_VALUE && number <= Int.MAX_VALUE && number == value.toInt().toDouble()) value.toInt() else null
}
internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
