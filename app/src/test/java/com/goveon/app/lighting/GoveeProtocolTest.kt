package com.goveon.app.lighting

import kotlinx.coroutines.runBlocking
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
    @Test fun `malformed discovery identity never becomes a saved light`() {
        assertNull(GoveeLanProtocol.parse("""{"msg":{"cmd":"scan","data":{"device":" ","sku":"H6006"}}}""", "192.168.1.10"))
        assertNull(GoveeLanProtocol.parse("""{"msg":{"cmd":"scan","data":{"device":"test-light","sku":""}}}""", "192.168.1.10"))
    }
    @Test fun `group failures retain the successful members result`() = runBlocking {
        val result = dispatchGroup(setOf("one", "two"), LightCommand.Power(true)) { id, _ ->
            if (id == "two") throw java.io.IOException("Unavailable")
            CommandResult(id, CommandStatus.ACCEPTED, "Sent")
        }
        assertEquals(2, result.results.size)
        assertEquals("two", result.failures.single().deviceId)
        assertTrue(result.results.first { it.deviceId == "one" }.successful)
    }
    @Test fun `group cancellation remains cancellation`() = runBlocking {
        try { dispatchGroup(setOf("one"), LightCommand.Power(true)) { _, _ -> throw kotlinx.coroutines.CancellationException("Background") }; fail() }
        catch (_: kotlinx.coroutines.CancellationException) { }
    }
}
