package com.goveon.app.lighting

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LocalStateCodecTest {
    private val bulb = LightDevice("local-bulb", "H6006", "Bulb", customName = "Reading light",
        bleAddress = "00:11:22:33:44:55", bleProfile = "bulb-v3")
    private fun base() = JSONObject(LocalStateCodec.encode(AppState(devices = listOf(bulb),
        groups = listOf(LightGroup("group", "Room", setOf(bulb.id))),
        quickActions = listOf(QuickAction("action", "Evening", setOf(bulb.id), listOf(LightCommand.Power(true), LightCommand.Temperature(2700)))))))

    @Test fun `local customization round trips without fabricating runtime state`() {
        val state = AppState(listOf(bulb.copy(state = LightState(power = true, brightness = 100, online = true, lastUpdated = 99), bleConnection = BleConnection.CONNECTED)),
            settings = AppSettings(ThemeMode.DARK, false))
        val restored = LocalStateCodec.decode(LocalStateCodec.encode(state))
        assertEquals(bulb, restored.devices.single())
        assertEquals(state.settings, restored.settings)
        assertEquals(LightState(), restored.devices.single().state)
        assertEquals(BleConnection.DISCONNECTED, restored.devices.single().bleConnection)
    }
    @Test fun `upgrade retains local members while removing cloud devices and metadata`() {
        val json = base().apply { remove("version") }
        json.getJSONArray("devices").getJSONObject(0).put("cloud", true).put("capabilities", JSONArray().put(JSONObject().put("instance", "lightScene")))
        json.getJSONArray("devices").put(JSONObject().put("id", "retired-device").put("model", "H6006").put("name", "Old remote light").put("cloud", true))
        json.getJSONArray("groups").getJSONObject(0).getJSONArray("devices").put("retired-device")
        val restored = LocalStateCodec.decode(json.toString())
        assertEquals(listOf(bulb), restored.devices)
        assertEquals(setOf(bulb.id), restored.groups.single().deviceIds)
        assertEquals(1, restored.quickActions.size)
        val encoded = LocalStateCodec.encode(restored)
        assertFalse(encoded.contains("cloud")); assertFalse(encoded.contains("capabilities"))
        assertTrue(LocalStateCodec.isCurrent(encoded))
    }
    @Test fun `a mixed legacy scene recipe is removed as a whole`() {
        val json = base()
        json.getJSONArray("actions").getJSONObject(0).getJSONArray("commands").put(JSONObject().put("kind", "capability").put("instance", "lightScene").put("value", "1"))
        val restored = LocalStateCodec.decode(json.toString())
        assertTrue(restored.quickActions.isEmpty())
        assertEquals(1, restored.groups.size); assertEquals(1, restored.devices.size)
    }
    @Test fun `malformed record does not discard unrelated devices groups or actions`() {
        val json = base()
        json.getJSONArray("devices").put(JSONObject().put("id", "bad").put("bleAddress", "00:11:22:33:44:66"))
        val restored = LocalStateCodec.decode(json.toString())
        assertEquals(listOf(bulb), restored.devices)
        assertEquals(1, restored.groups.size); assertEquals(1, restored.quickActions.size)
        assertNotNull(restored.error)
    }
    @Test fun `orphaned groups and actions are dropped`() {
        val json = base().put("devices", JSONArray())
        val restored = LocalStateCodec.decode(json.toString())
        assertTrue(restored.groups.isEmpty()); assertTrue(restored.quickActions.isEmpty())
    }
    @Test fun `corrupt whole library and future format report a retained data error`() {
        assertNotNull(LocalStateCodec.decode("{broken").error)
        assertNotNull(LocalStateCodec.decode(base().put("version", 99).toString()).error)
    }
    @Test fun `commands reject coercion invalid values and unknown kinds`() {
        listOf("""{"kind":"brightness","value":0}""", """{"kind":"brightness","value":101}""",
            """{"kind":"brightness","value":50.5}""", """{"kind":"brightness","value":"50"}""",
            """{"kind":"power","value":1}""", """{"kind":"color","value":16777216}""",
            """{"kind":"temperature","value":9001}""", """{"kind":"unknown","value":1}""")
            .forEach { assertNull(readCommand(JSONObject(it))) }
    }
    @Test fun `malformed recipe is not executed partially`() {
        val json = base()
        json.getJSONArray("actions").getJSONObject(0).getJSONArray("commands").put(JSONObject().put("kind", "brightness").put("value", -1))
        assertTrue(LocalStateCodec.decode(json.toString()).quickActions.isEmpty())
    }
    @Test fun `all retained command types serialize without changing their value`() {
        listOf(LightCommand.Power(false), LightCommand.Brightness(100), LightCommand.Color(0x12ABEF), LightCommand.Temperature(1800))
            .forEach { assertEquals(it, readCommand(writeCommand(it))) }
    }
    @Test fun `saved endpoints cannot become DNS or arbitrary network destinations`() {
        assertTrue(validIpv4("192.168.1.20"))
        listOf("light.example", "256.1.1.1", "1.1.1", "-1.1.1.1", "1.1.1.1:4003").forEach { assertFalse(validIpv4(it)) }
        val json = base()
        json.getJSONArray("devices").getJSONObject(0).put("ip", "light.example")
        assertNull(LocalStateCodec.decode(json.toString()).devices.single().ip)
    }
}
