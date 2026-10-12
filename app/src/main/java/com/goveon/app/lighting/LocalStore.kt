package com.goveon.app.lighting

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** App-private customization. Device readings are intentionally not restored as live state. */
internal class LocalStore(context: Context) {
    private val preferences = context.getSharedPreferences("goveon_local", Context.MODE_PRIVATE)
    fun load(): AppState = runCatching {
        val json = JSONObject(preferences.getString("state", "{}") ?: "{}")
        val settings = json.optJSONObject("settings") ?: JSONObject()
        AppState(
            devices = json.optJSONArray("devices").objects().map { d -> LightDevice(d.getString("id"), d.getString("model"), d.getString("name"), d.optString("ip").takeIf { it.isNotBlank() }, d.optBoolean("cloud"), GoveeCloudProtocol.parseCapabilities(d.optJSONArray("capabilities")), customName = d.optString("customName").takeIf { it.isNotBlank() }) },
            groups = json.optJSONArray("groups").objects().map { LightGroup(it.getString("id"), it.getString("name"), strings(it.optJSONArray("devices"))) },
            quickActions = json.optJSONArray("actions").objects().map { QuickAction(it.getString("id"), it.getString("name"), strings(it.optJSONArray("devices")), it.optJSONArray("commands").objects().mapNotNull(::readCommand)) },
            settings = AppSettings(runCatching { ThemeMode.valueOf(settings.optString("theme", "SYSTEM")) }.getOrDefault(ThemeMode.SYSTEM), settings.optBoolean("dynamic", true), settings.optBoolean("lan", true)),
        )
    }.getOrDefault(AppState())
    fun save(state: AppState) {
        val json = JSONObject().put("settings", JSONObject().put("theme", state.settings.theme.name).put("dynamic", state.settings.dynamicColor).put("lan", state.settings.preferLan))
            .put("devices", JSONArray(state.devices.map { d -> JSONObject().put("id", d.id).put("model", d.model).put("name", d.name).put("customName", d.customName ?: "").put("ip", d.ip ?: "").put("cloud", d.cloudAvailable).put("capabilities", JSONArray(d.capabilities.map { JSONObject().put("type", it.type).put("instance", it.instance).put("parameters", JSONObject(it.parametersJson)).put("goveonStaticOptions", JSONArray(it.staticOptions.map { option -> JSONObject().put("name", option.name).put("value", org.json.JSONTokener(option.valueJson).nextValue()) })) })) }))
            .put("groups", JSONArray(state.groups.map { JSONObject().put("id", it.id).put("name", it.name).put("devices", JSONArray(it.deviceIds.toList())) }))
            .put("actions", JSONArray(state.quickActions.map { JSONObject().put("id", it.id).put("name", it.name).put("devices", JSONArray(it.deviceIds.toList())).put("commands", JSONArray(it.commands.map(::writeCommand))) }))
        preferences.edit().putString("state", json.toString()).apply()
    }
    private fun strings(array: JSONArray?) = if (array == null) emptySet() else (0 until array.length()).map { array.getString(it) }.toSet()
}

internal fun writeCommand(command: LightCommand): JSONObject = when (command) {
    is LightCommand.Power -> JSONObject().put("kind", "power").put("value", command.on)
    is LightCommand.Brightness -> JSONObject().put("kind", "brightness").put("value", command.percent)
    is LightCommand.Color -> JSONObject().put("kind", "color").put("value", command.rgb)
    is LightCommand.Temperature -> JSONObject().put("kind", "temperature").put("value", command.kelvin)
    is LightCommand.Capability -> JSONObject().put("kind", "capability").put("instance", command.instance).put("value", command.valueJson)
}
internal fun readCommand(json: JSONObject): LightCommand? = when (json.optString("kind")) {
    "power" -> LightCommand.Power(json.getBoolean("value"))
    "brightness" -> LightCommand.Brightness(json.getInt("value"))
    "color" -> LightCommand.Color(json.getInt("value"))
    "temperature" -> LightCommand.Temperature(json.getInt("value"))
    "capability" -> LightCommand.Capability(json.getString("instance"), json.getString("value"))
    else -> null
}

/** Ciphertext only is stored. The AES key never leaves Android Keystore. Exclude backup. */
internal class ApiKeyStore(context: Context) {
    private val preferences = context.getSharedPreferences("goveon_credentials", Context.MODE_PRIVATE)
    private val alias = "goveon.developer-key.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(): String? {
        val raw = preferences.getString("ciphertext", null) ?: return null
        val iv = preferences.getString("iv", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(raw, Base64.NO_WRAP)), Charsets.UTF_8)
    }
    fun save(value: String) {
        if (value.isBlank()) { check(preferences.edit().clear().commit()) { "Could not remove API key." }; return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).putString("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) { "Could not save API key." }
    }
}
