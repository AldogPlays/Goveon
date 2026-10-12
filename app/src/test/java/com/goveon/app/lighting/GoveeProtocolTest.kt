package com.goveon.app.lighting

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GoveeProtocolTest {
    @Test fun `LAN RGB clears white temperature and packs channels exactly`() {
        val message = JSONObject(GoveeLanProtocol.command(LightCommand.Color(0x123456))).getJSONObject("msg")
        assertEquals("colorwc", message.getString("cmd"))
        val data = message.getJSONObject("data")
        assertEquals(0, data.getInt("colorTemInKelvin"))
        assertEquals(18, data.getJSONObject("color").getInt("r"))
        assertEquals(52, data.getJSONObject("color").getInt("g"))
        assertEquals(86, data.getJSONObject("color").getInt("b"))
        val white = JSONObject(GoveeLanProtocol.command(LightCommand.Temperature(4000))).getJSONObject("msg").getJSONObject("data")
        assertEquals(4000, white.getInt("colorTemInKelvin"))
    }
    @Test fun `LAN protocol bounds are rejected before network use`() {
        listOf(LightCommand.Brightness(0), LightCommand.Brightness(101), LightCommand.Color(-1), LightCommand.Color(0x1000000), LightCommand.Temperature(1999), LightCommand.Temperature(9001)).forEach {
            assertTrue(runCatching { GoveeLanProtocol.command(it) }.isFailure)
        }
        assertTrue(runCatching { GoveeLanProtocol.command(LightCommand.Brightness(1)) }.isSuccess)
        assertTrue(runCatching { GoveeLanProtocol.command(LightCommand.Brightness(100)) }.isSuccess)
    }
    @Test fun `discovery trusts packet source and normalizes stable identity`() {
        val reply = GoveeLanProtocol.parse("""{"msg":{"cmd":"scan","data":{"device":"aa:bb:cc:dd","sku":"HTEST","ip":"9.9.9.9"}}}""", "192.168.1.10") as GoveeLanProtocol.Reply.Discovery
        assertEquals("AA:BB:CC:DD", reply.id)
        assertEquals("192.168.1.10", reply.ip)
        assertNull(GoveeLanProtocol.parse("not json", "192.168.1.10"))
        assertNull(GoveeLanProtocol.parse("""{"msg":{"cmd":"unknown","data":{}}}""", "192.168.1.10"))
    }
    @Test fun `status nullable missing readings remain unknown`() {
        val reply = GoveeLanProtocol.parse("""{"msg":{"cmd":"devStatus","data":{"onOff":1,"brightness":72,"color":{"r":255,"g":0,"b":128},"colorTemInKelvin":0}}}""", "192.168.1.10") as GoveeLanProtocol.Reply.Status
        assertEquals(true, reply.state.power)
        assertEquals(72, reply.state.brightness)
        assertEquals(0xff0080, reply.state.rgb)
        val partial = GoveeLanProtocol.parse("""{"msg":{"cmd":"devStatus","data":{}}}""", "192.168.1.10") as GoveeLanProtocol.Reply.Status
        assertNull(partial.state.power)
        assertNull(partial.state.brightness)
    }
    @Test fun `cloud discovers actual light capabilities and preserves scene objects`() {
        val response = JSONObject("""{"data":[{"sku":"HTEST","device":"aa:bb","type":"devices.types.light","deviceName":"Desk","capabilities":[{"type":"devices.capabilities.dynamic_scene","instance":"lightScene","parameters":{"dataType":"ENUM","options":[{"name":"Aurora","value":{"paramId":19,"id":55}}]}}]},{"sku":"HSENSOR","device":"cc:dd","type":"devices.types.sensor","capabilities":[]}]}""")
        val device = GoveeCloudProtocol.parseDevices(response).single()
        assertEquals("AA:BB", device.id)
        val option = device.capabilities.single().options.single()
        val (cap, value) = GoveeCloudProtocol.capabilityValue(device, LightCommand.Capability("lightScene", """{"id":55,"paramId":19}"""))
        assertEquals("lightScene", cap.instance)
        assertEquals(55, (value as JSONObject).getInt("id"))
        assertEquals("Aurora", option.name)
        assertTrue(runCatching { GoveeCloudProtocol.capabilityValue(device, LightCommand.Capability("lightScene", "123")) }.isFailure)
    }
    @Test fun `cloud offline responses never present historical values as current`() {
        val previous = LightState(power = true, brightness = 88, lastUpdated = 123)
        val response = JSONObject("""{"payload":{"capabilities":[{"instance":"online","state":{"value":false}},{"instance":"brightness","state":{"value":15}},{"instance":"powerSwitch","state":{"value":0}}]}}""")
        val reading = GoveeCloudProtocol.parseState(response, previous)
        assertEquals(false, reading.online)
        assertEquals(true, reading.power)
        assertEquals(88, reading.brightness)
        assertEquals(123L, reading.lastUpdated)
    }
    @Test fun `cloud missing online or unsupported query leaves current values unknown`() {
        val previous = LightState(power = true, brightness = 88, lastUpdated = 123)
        val unknown = GoveeCloudProtocol.parseState(JSONObject("""{"payload":{"capabilities":[{"instance":"brightness","state":{"value":15}}]}}"""), previous)
        assertNull(unknown.online)
        assertEquals(123L, unknown.lastUpdated)
        assertEquals(88, unknown.brightness)
        val online = GoveeCloudProtocol.parseState(JSONObject("""{"payload":{"capabilities":[{"instance":"online","state":{"value":true}},{"instance":"powerSwitch","state":{"value":1}},{"instance":"brightness","state":{"value":null}}]}}"""), previous)
        assertEquals(true, online.power)
        assertNull(online.brightness)
    }
    @Test fun `invalid LAN state values do not become invented readings`() {
        val partial = GoveeLanProtocol.parse("""{"msg":{"cmd":"devStatus","data":{"onOff":7,"brightness":"bad","color":{"r":-1,"g":255,"b":255},"colorTemInKelvin":1}}}""", "192.168.1.10") as GoveeLanProtocol.Reply.Status
        assertFalse(partial.state.hasReading)
    }
    @Test fun `schema validates range precision required struct fields and segment selections`() {
        val schema = JSONObject("""{"dataType":"STRUCT","fields":[{"fieldName":"segment","dataType":"Array","required":true,"options":[{"value":0},{"value":1}]},{"fieldName":"brightness","dataType":"INTEGER","required":true,"range":{"min":10,"max":100,"precision":10}}]}""")
        GoveeCloudProtocol.validate(schema, JSONObject().put("segment", JSONArray(listOf(0, 1))).put("brightness", 50))
        listOf("""{"segment":[7],"brightness":50}""", """{"segment":[0],"brightness":55}""", """{"brightness":50}""", """{"segment":[0],"brightness":50,"unknown":1}""").forEach { assertTrue(runCatching { GoveeCloudProtocol.validate(schema, JSONObject(it)) }.isFailure) }
    }
    @Test fun `cloud model range controls temperature instead of assumed LAN maximum`() {
        val cap = GoveeCloudProtocol.parseCapabilities(JSONArray("""[{"type":"devices.capabilities.color_setting","instance":"colorTemperatureK","parameters":{"dataType":"INTEGER","range":{"min":2700,"max":6500}}}]""")).single()
        val device = LightDevice("AA", "HTEST", "Desk", capabilities = listOf(cap))
        assertTrue(runCatching { GoveeCloudProtocol.capabilityValue(device, LightCommand.Temperature(2000)) }.isFailure)
        assertEquals(4000, GoveeCloudProtocol.capabilityValue(device, LightCommand.Temperature(4000)).second)
    }
    @Test fun `group dispatch retains successful members and every failure`() = runBlocking {
        val result = dispatchGroup(setOf("a", "b", "c"), LightCommand.Power(true)) { id, _ ->
            when (id) { "a" -> CommandResult(id, CommandStatus.CONFIRMED, "ok"); "b" -> failure(id, "offline"); else -> throw IllegalStateException("unsupported") }
        }
        assertFalse(result.successful)
        assertEquals(3, result.results.size)
        assertEquals(setOf("b", "c"), result.failures.map { it.deviceId }.toSet())
        assertTrue(result.results.first { it.deviceId == "a" }.successful)
        assertFalse(dispatchGroup(emptySet(), LightCommand.Power(true)) { _, _ -> error("must not run") }.successful)
    }
    @Test fun `merge preserves static and dynamic scenes without duplicates`() {
        fun cap(options: String) = GoveeCloudProtocol.parseCapabilities(JSONArray("""[{"type":"devices.capabilities.dynamic_scene","instance":"lightScene","parameters":{"dataType":"ENUM","options":$options}}]"""))
        val merged = mergeCapabilities(cap("""[{"name":"Static","value":1}]"""), cap("""[{"name":"Static","value":1},{"name":"Dynamic","value":{"id":2,"paramId":3}}]""")).single()
        assertEquals(2, merged.options.size)
        val device = LightDevice("AA", "HTEST", "Desk", capabilities = listOf(merged))
        assertTrue(runCatching { GoveeCloudProtocol.capabilityValue(device, LightCommand.Capability("lightScene", "1")) }.isSuccess)
    }
    @Test fun `quick action command persistence retains exact scene payload`() {
        val commands = listOf(LightCommand.Power(true), LightCommand.Brightness(35), LightCommand.Color(0x123456), LightCommand.Temperature(4000), LightCommand.Capability("lightScene", """{"id":2,"paramId":3}"""))
        commands.forEach { assertEquals(it, readCommand(writeCommand(it))) }
    }
    @Test fun `actual cloud request builder follows documented envelope and exact capability value`() {
        val cap = GoveeCloudProtocol.parseCapabilities(JSONArray("""[{"type":"devices.capabilities.dynamic_scene","instance":"lightScene","parameters":{"dataType":"ENUM","options":[{"name":"Actual API option","value":{"id":55,"paramId":19}}]}}]""")).single()
        val device = LightDevice("AA:BB", "HTEST", "Desk", capabilities = listOf(cap))
        val envelope = GoveeCloudProtocol.requestEnvelope(GoveeCloudProtocol.controlPayload(device, LightCommand.Capability("lightScene", cap.options.single().valueJson)), "request-for-test")
        assertEquals("request-for-test", envelope.getString("requestId"))
        val payload = envelope.getJSONObject("payload")
        assertEquals("HTEST", payload.getString("sku"))
        assertEquals("AA:BB", payload.getString("device"))
        assertFalse(payload.has("deviceName"))
        val capability = payload.getJSONObject("capability")
        assertEquals("devices.capabilities.dynamic_scene", capability.getString("type"))
        assertEquals("lightScene", capability.getString("instance"))
        assertEquals(19, capability.getJSONObject("value").getInt("paramId"))
        assertEquals(55, capability.getJSONObject("value").getInt("id"))
    }
    @Test fun `refreshing real scene catalog removes retired dynamic options and retains static`() {
        fun cap(options: String) = GoveeCloudProtocol.parseCapabilities(JSONArray("""[{"type":"devices.capabilities.dynamic_scene","instance":"lightScene","parameters":{"dataType":"ENUM","options":$options}}]"""))
        val base = cap("""[{"name":"Static","value":1}]""")
        val first = mergeCapabilities(base, cap("""[{"name":"Retired","value":2}]""").map { it.copy(staticOptions = emptyList()) })
        val refreshed = mergeCapabilities(first, cap("""[{"name":"Current","value":3}]""").map { it.copy(staticOptions = emptyList()) }).single()
        assertEquals(listOf("Static", "Current"), refreshed.options.map { it.name })
        assertFalse(refreshed.options.any { it.valueJson == "2" })
    }
}
