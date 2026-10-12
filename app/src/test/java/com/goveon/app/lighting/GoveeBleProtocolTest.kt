package com.goveon.app.lighting

import org.junit.Assert.*
import org.junit.Test

class GoveeBleProtocolTest {
    private fun hex(packet: ByteArray) = packet.joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun `power packets match the recovered switch controller and XOR builder`() {
        assertEquals("3301010000000000000000000000000000000033", hex(GoveeBleProtocol.command("common-light", LightCommand.Power(true)).single()))
        assertEquals("3301000000000000000000000000000000000032", hex(GoveeBleProtocol.command("common-light", LightCommand.Power(false)).single()))
    }
    @Test fun `read packets have an empty payload and recovered read header`() {
        assertEquals(listOf("aa010000000000000000000000000000000000ab", "aa040000000000000000000000000000000000ae", "aa050000000000000000000000000000000000af"), GoveeBleProtocol.queries("common-light").map(::hex))
    }
    @Test fun `percentage brightness packet uses the recovered one byte payload`() {
        assertEquals("3304320000000000000000000000000000000005", hex(GoveeBleProtocol.command("common-light", LightCommand.Brightness(50)).single()))
    }
    @Test fun `H6005 color frame includes recovered selector RGB and cleared white fields`() {
        assertEquals("bulb-v2", GoveeBleProtocol.profileFor("H6005").id)
        assertEquals("33050dff00800000000000000000000000000044", hex(GoveeBleProtocol.command("bulb-v2", LightCommand.Color(0xFF0080)).single()))
        assertEquals("unclassified", GoveeBleProtocol.profileFor("H6008").id)
        assertFalse(GoveeBleProtocol.supports(GoveeBleProtocol.profileFor("H6008").id, "colorRgb"))
    }
    @Test fun `color read replies use exact mode selector stripping and big endian Kelvin`() {
        val reply = GoveeBleProtocol.frame(0xAA, 0x05, byteArrayOf(0x0D, 0xFE.toByte(), 0x80.toByte(), 1, 0x19, 0x64, 1, 2, 3))
        assertTrue(GoveeBleProtocol.matchesColorProfile(reply))
        val state = GoveeBleProtocol.parseState("color-0d", reply)!!
        assertEquals(0xFE8001, state.rgb)
        assertEquals(6500, state.temperatureKelvin)
        assertNull(GoveeBleProtocol.parseState("common-light", reply))
    }
    @Test fun `scene music unknown modes and extra fields never become a color profile`() {
        for (selector in listOf(4, 5, 10, 0x15)) {
            val reply = GoveeBleProtocol.frame(0xAA, 5, byteArrayOf(selector.toByte(), 1, 2, 3))
            assertFalse(GoveeBleProtocol.matchesColorProfile(reply))
            assertNull(GoveeBleProtocol.parseState("bulb-v2", reply))
        }
        val longer = GoveeBleProtocol.frame(0xAA, 5, ByteArray(10).apply { this[0] = 0x0D; this[9] = 1 })
        assertFalse(GoveeBleProtocol.matchesColorProfile(longer))
        assertFalse(GoveeBleProtocol.matchesColorProfile(GoveeBleProtocol.frame(0x33, 5, byteArrayOf(0x0D, 1, 2, 3))))
    }
    @Test fun `generic read-only probing excludes appliances and unidentified models`() {
        assertTrue(GoveeBleProtocol.canProbe("H6117"))
        assertTrue(GoveeBleProtocol.canProbe("H61A0"))
        assertFalse(GoveeBleProtocol.canProbe("H7131"))
        assertFalse(GoveeBleProtocol.canProbe("H5075"))
        assertFalse(GoveeBleProtocol.canProbe("Unknown BLE model"))
        assertFalse(GoveeBleProtocol.supports(GoveeBleProtocol.profileFor("H7131").id, "powerSwitch"))
    }
    @Test fun `write acknowledgement is never decoded as actual light state`() {
        assertNull(GoveeBleProtocol.parseState("common-light", GoveeBleProtocol.frame(0x33, 0x01, byteArrayOf(1))))
        assertNull(GoveeBleProtocol.parseState("common-light", GoveeBleProtocol.frame(0x33, 0x04, byteArrayOf(0, 75))))
    }
    @Test fun `read state combines recovered payload offset and unsigned bytes`() {
        val power = GoveeBleProtocol.parseState("common-light", GoveeBleProtocol.frame(0xAA, 0x01, byteArrayOf(1)), 123)!!
        val brightness = GoveeBleProtocol.parseState("common-light", GoveeBleProtocol.frame(0xAA, 0x04, byteArrayOf(75)), 124)!!
        assertEquals(true, power.power); assertNull(power.brightness); assertEquals(123L, power.lastUpdated)
        assertEquals(75, brightness.brightness); assertNull(brightness.power)
    }
    @Test fun `corrupt truncated and oversized notifications cannot change state`() {
        val packet = GoveeBleProtocol.frame(0xAA, 0x01, byteArrayOf(1))
        assertNull(GoveeBleProtocol.parseState("common-light", packet.copyOf(19)))
        assertNull(GoveeBleProtocol.parseState("common-light", packet.copyOf(21)))
        packet[2] = 0
        assertNull(GoveeBleProtocol.parseState("common-light", packet))
    }
    @Test fun `unclassified appliances receive no control or initialization packets`() {
        assertTrue(GoveeBleProtocol.queries("unclassified").isEmpty())
        assertTrue(GoveeBleProtocol.initialization("unclassified").isEmpty())
        assertThrows(IllegalArgumentException::class.java) { GoveeBleProtocol.command("unclassified", LightCommand.Power(true)) }
        assertNull(GoveeBleProtocol.parseState("unclassified", GoveeBleProtocol.frame(0xAA, 1, byteArrayOf(1))))
    }
    @Test fun `brightness is bounded before a GATT write and invalid readings stay unknown`() {
        assertThrows(IllegalArgumentException::class.java) { GoveeBleProtocol.command("common-light", LightCommand.Brightness(101)) }
        assertThrows(IllegalArgumentException::class.java) { GoveeBleProtocol.command("common-light", LightCommand.Brightness(-1)) }
        assertNull(GoveeBleProtocol.parseState("common-light", GoveeBleProtocol.frame(0xAA, 4, byteArrayOf(0xFF.toByte()))))
    }
    @Test fun `payload cannot overwrite the checksum and unsupported controls are blocked`() {
        assertThrows(IllegalArgumentException::class.java) { GoveeBleProtocol.frame(0x33, 1, ByteArray(18)) }
        assertThrows(IllegalArgumentException::class.java) { GoveeBleProtocol.command("common-light", LightCommand.Color(0xFF0000)) }
        assertThrows(IllegalArgumentException::class.java) { GoveeBleProtocol.command("common-light", LightCommand.Capability("lightScene", "1")) }
    }
    @Test fun `advertisement matching recognizes legacy names and service-only advertisements`() {
        assertTrue(GoveeBleProtocol.isCandidate("ihoment_H6117_1234", emptyList()))
        assertTrue(GoveeBleProtocol.isCandidate("Govee_H61A0_ABCD", emptyList()))
        assertTrue(GoveeBleProtocol.isCandidate("", listOf(GoveeBleProtocol.SERVICE)))
        assertFalse(GoveeBleProtocol.isCandidate("Keyboard H6117", emptyList()))
        assertEquals("H61A0", GoveeBleProtocol.modelFromName("Govee_H61A0_ABCD"))
        assertNull(GoveeBleProtocol.modelFromName("Govee_H61170"))
    }
}
