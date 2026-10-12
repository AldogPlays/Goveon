package com.goveon.app.lighting

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.UUID

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
        is LightCommand.Capability -> error("This capability requires the developer API.")
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
            "scan" -> Reply.Discovery(normalizedId(data.getString("device")), data.getString("sku"), senderIp)
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

/** Current official developer API capability formats. No per-model guessed commands. */
object GoveeCloudProtocol {
    fun devicePayload(device: LightDevice): JSONObject = JSONObject().put("sku", device.model).put("device", device.id)
    fun requestEnvelope(payload: JSONObject, requestId: String = UUID.randomUUID().toString()): JSONObject = JSONObject().put("requestId", requestId).put("payload", payload)
    fun controlPayload(device: LightDevice, command: LightCommand): JSONObject {
        val (capability, value) = capabilityValue(device, command)
        return devicePayload(device).put("capability", JSONObject().put("type", capability.type).put("instance", capability.instance).put("value", value))
    }
    fun parseDevices(response: JSONObject): List<LightDevice> = response.optJSONArray("data").objects().mapNotNull { obj ->
        val caps = parseCapabilities(obj.optJSONArray("capabilities"))
        // Do not expose appliance controls; older API examples omit the device type.
        if (obj.optString("type").isNotBlank() && obj.optString("type") != "devices.types.light") return@mapNotNull null
        if (obj.optString("type").isBlank() && caps.none { it.instance in setOf("colorRgb", "brightness", "colorTemperatureK") }) return@mapNotNull null
        val id = obj.optString("device").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        LightDevice(normalizedId(id), obj.getString("sku"), obj.optString("deviceName").ifBlank { obj.getString("sku") }, cloudAvailable = true, capabilities = caps)
    }
    fun parseCapabilities(array: JSONArray?): List<LightCapability> = array.objects().map { cap ->
        val params = cap.optJSONObject("parameters") ?: JSONObject()
        LightCapability(cap.getString("type"), cap.getString("instance"), params.toString(), options(params), params.optJSONArray("fields").objects().map { field ->
            CapabilityField(field.getString("fieldName"), field.optString("dataType"), field.optBoolean("required"), options(field), field.optJSONObject("range")?.optIntNullable("min"), field.optJSONObject("range")?.optIntNullable("max"))
        }, params.optJSONObject("range")?.optIntNullable("min"), params.optJSONObject("range")?.optIntNullable("max"),
            if (cap.has("goveonStaticOptions")) options(JSONObject().put("options", cap.optJSONArray("goveonStaticOptions"))) else options(params))
    }
    private fun options(params: JSONObject) = params.optJSONArray("options").objects().filter { it.has("value") }.map { CapabilityOption(it.optString("name").ifBlank { it.get("value").toString() }, jsonValue(it.get("value"))) }
    fun parseState(response: JSONObject, previous: LightState): LightState {
        val values = response.optJSONObject("payload")?.optJSONArray("capabilities").objects().mapNotNull { item ->
            val value = item.optJSONObject("state")?.opt("value")
            if (value == null || value == JSONObject.NULL) null else item.getString("instance") to jsonValue(value)
        }.toMap()
        fun number(key: String) = values[key]?.toIntOrNull()
        val online = values["online"]?.toBooleanStrictOrNull()
        // Offline API values are historical; retain the previous reading and mark it offline.
        if (online != true) return previous.copy(online = online)
        return LightState(power = number("powerSwitch")?.takeIf { it in 0..1 }?.let { it == 1 }, brightness = number("brightness"),
            rgb = number("colorRgb"), temperatureKelvin = number("colorTemperatureK"),
            online = true, lastUpdated = System.currentTimeMillis(), values = values)
    }
    fun capabilityValue(device: LightDevice, command: LightCommand): Pair<LightCapability, Any> {
        val (instance, value) = when (command) {
            is LightCommand.Power -> "powerSwitch" to if (command.on) 1 else 0
            is LightCommand.Brightness -> "brightness" to command.percent
            is LightCommand.Color -> "colorRgb" to command.rgb
            is LightCommand.Temperature -> "colorTemperatureK" to command.kelvin
            is LightCommand.Capability -> command.instance to JSONTokener(command.valueJson).nextValue()
        }
        val capability = requireNotNull(device.capability(instance)) { "${device.model} does not advertise $instance." }
        validate(JSONObject(capability.parametersJson), value)
        return capability to value
    }
    fun validate(params: JSONObject, value: Any) {
        when (params.optString("dataType").uppercase()) {
            "INTEGER" -> {
                require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toLong().toDouble()) { "An integer is required." }
                val range = params.optJSONObject("range")
                if (range != null) {
                    require(value.toLong() >= range.optLong("min", Long.MIN_VALUE) && value.toLong() <= range.optLong("max", Long.MAX_VALUE)) { "Value is outside the device's supported range." }
                    val precision = range.optLong("precision", 1)
                    require(precision <= 1 || (value.toLong() - range.optLong("min", 0)) % precision == 0L) { "Value does not match device precision." }
                }
            }
            "ENUM" -> require(options(params).any { equivalent(JSONTokener(it.valueJson).nextValue(), value) }) { "Select an option advertised by this device." }
            "STRUCT" -> {
                require(value is JSONObject) { "This capability requires a structured value." }
                val fields = params.optJSONArray("fields").objects()
                val allowed = fields.map { it.getString("fieldName") }.toSet()
                require(value.keys().asSequence().all { it in allowed }) { "Unexpected capability field." }
                fields.forEach { field ->
                    val name = field.getString("fieldName")
                    require(!field.optBoolean("required") || value.has(name)) { "$name is required." }
                    if (value.has(name)) validate(field, value.get(name))
                }
            }
            "ARRAY" -> {
                require(value is JSONArray && value.length() > 0) { "Select at least one segment." }
                val allowed = options(params)
                for (i in 0 until value.length()) require(allowed.isEmpty() || allowed.any { equivalent(JSONTokener(it.valueJson).nextValue(), value.get(i)) }) { "Unsupported segment." }
            }
            else -> error("Unsupported device parameter type: ${params.optString("dataType")}")
        }
    }
    internal fun equivalent(a: Any?, b: Any?): Boolean = when {
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        a is JSONObject && b is JSONObject -> a.keys().asSequence().toSet() == b.keys().asSequence().toSet() && a.keys().asSequence().all { equivalent(a.get(it), b.get(it)) }
        a is JSONArray && b is JSONArray -> a.length() == b.length() && (0 until a.length()).all { equivalent(a.get(it), b.get(it)) }
        else -> a == b
    }
}
internal fun JSONObject.optIntNullable(key: String): Int? {
    val value = opt(key) as? Number ?: return null
    val number = value.toDouble()
    return if (number.isFinite() && number >= Int.MIN_VALUE && number <= Int.MAX_VALUE && number == value.toInt().toDouble()) value.toInt() else null
}
internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
