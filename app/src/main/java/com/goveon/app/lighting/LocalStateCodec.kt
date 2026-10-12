package com.goveon.app.lighting

import org.json.JSONArray
import org.json.JSONObject

/** Versioned, local-only persistence. Isolate damaged records instead of resetting the library. */
internal object LocalStateCodec {
    private const val VERSION = 1
    fun isCurrent(raw: String) = runCatching { JSONObject(raw).optInt("version") == VERSION }.getOrDefault(false)
    fun decode(raw: String): AppState {
        val json = runCatching { JSONObject(raw) }.getOrElse {
            return AppState(error = "The saved library could not be read. Its stored data has been retained.")
        }
        if (json.optInt("version", 0) > VERSION) return AppState(error = "This library requires a newer Goveon version. Its stored data has been retained.")
        var damaged = false
        fun <T> records(key: String, parse: (JSONObject) -> T?): List<T> = json.optJSONArray(key).objects().mapNotNull {
            runCatching { parse(it) }.getOrElse { damaged = true; null }
        }
        val devices = records("devices") { d ->
            val ip = d.optionalText("ip")?.takeIf(::validIpv4)
            val address = d.optionalText("bleAddress")?.takeIf { it.matches(Regex("(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}")) }
            if (ip == null && address == null) return@records null
            LightDevice(d.requiredText("id"), d.requiredText("model"), d.requiredText("name"), ip,
                customName = d.optionalText("customName"), bleAddress = address,
                bleProfile = d.optionalText("bleProfile")?.takeIf(GoveeBleProtocol::isKnownProfile))
        }.distinctBy { it.id }
        val ids = devices.map { it.id }.toSet()
        val groups = records("groups") { g ->
            val members = g.optJSONArray("devices").strings() intersect ids
            if (members.isEmpty()) null else LightGroup(g.requiredText("id"), g.requiredText("name"), members)
        }.distinctBy { it.id }
        val actions = records("actions") { a ->
            val members = a.optJSONArray("devices").strings() intersect ids
            val array = a.optJSONArray("commands") ?: return@records null
            // Never silently execute part of a legacy scene recipe after removing its cloud command.
            val commands = (0 until array.length()).map { readCommand(array.optJSONObject(it) ?: return@records null) ?: return@records null }
            if (members.isEmpty() || commands.isEmpty()) null else QuickAction(a.requiredText("id"), a.requiredText("name"), members, commands)
        }.distinctBy { it.id }
        val settings = json.optJSONObject("settings") ?: JSONObject()
        return AppState(devices.sortedBy { it.displayName.lowercase() }, groups, actions,
            AppSettings(runCatching { ThemeMode.valueOf(settings.optString("theme", "SYSTEM")) }.getOrDefault(ThemeMode.SYSTEM), settings.optBoolean("dynamic", true)),
            error = if (damaged) "Some damaged saved entries could not be restored." else null)
    }
    fun encode(state: AppState): String = JSONObject().put("version", VERSION)
        .put("settings", JSONObject().put("theme", state.settings.theme.name).put("dynamic", state.settings.dynamicColor))
        .put("devices", JSONArray(state.devices.map { d -> JSONObject().put("id", d.id).put("model", d.model).put("name", d.name)
            .put("customName", d.customName ?: "").put("ip", d.ip ?: "").put("bleAddress", d.bleAddress ?: "").put("bleProfile", d.bleProfile ?: "") }))
        .put("groups", JSONArray(state.groups.map { g -> JSONObject().put("id", g.id).put("name", g.name).put("devices", JSONArray(g.deviceIds.toList())) }))
        .put("actions", JSONArray(state.quickActions.map { a -> JSONObject().put("id", a.id).put("name", a.name).put("devices", JSONArray(a.deviceIds.toList())).put("commands", JSONArray(a.commands.map(::writeCommand))) }))
        .toString()
}

private fun JSONObject.requiredText(key: String) = (opt(key) as? String)?.takeIf { it.isNotBlank() } ?: error("Missing saved field.")
private fun JSONObject.optionalText(key: String) = (opt(key) as? String)?.takeIf { it.isNotBlank() }
private fun JSONArray?.strings(): Set<String> = if (this == null) emptySet() else (0 until length()).mapNotNull { (opt(it) as? String)?.takeIf(String::isNotBlank) }.toSet()
internal fun validIpv4(value: String) = value.split('.').let { parts -> parts.size == 4 && parts.all { it.matches(Regex("[0-9]{1,3}")) && it.toInt() in 0..255 } }

internal fun writeCommand(command: LightCommand): JSONObject = when (command) {
    is LightCommand.Power -> JSONObject().put("kind", "power").put("value", command.on)
    is LightCommand.Brightness -> JSONObject().put("kind", "brightness").put("value", command.percent)
    is LightCommand.Color -> JSONObject().put("kind", "color").put("value", command.rgb)
    is LightCommand.Temperature -> JSONObject().put("kind", "temperature").put("value", command.kelvin)
}
internal fun readCommand(json: JSONObject): LightCommand? = when (json.optString("kind")) {
    "power" -> (json.opt("value") as? Boolean)?.let { LightCommand.Power(it) }
    "brightness" -> json.optIntNullable("value")?.takeIf { it in 1..100 }?.let { LightCommand.Brightness(it) }
    "color" -> json.optIntNullable("value")?.takeIf { it in 0..0xFFFFFF }?.let { LightCommand.Color(it) }
    "temperature" -> json.optIntNullable("value")?.takeIf { it in 1800..9000 }?.let { LightCommand.Temperature(it) }
    else -> null
}
