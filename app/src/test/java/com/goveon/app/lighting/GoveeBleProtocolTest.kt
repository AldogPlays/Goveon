package com.goveon.app.lighting

import org.junit.Assert.*
import org.junit.Test

class GoveeBleProtocolTest {
    private fun hex(packet: ByteArray) = packet.joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun `H6006 family uses vendor native percent and never inherits the older bulb scale`() {
        for (percent in listOf(1, 50, 100)) {
            val packet = GoveeBleProtocol.command("bulb-v3", LightCommand.Brightness(percent)).single()
            assertEquals(percent, packet[2].toInt() and 255)
            assertEquals(percent, GoveeBleProtocol.parseState("bulb-v3", GoveeBleProtocol.frame(0xAA, 4, byteArrayOf(percent.toByte())))!!.brightness)
        }
    }

    @Test fun `H6006 family gets its own native white range instead of guessed RGB capability`() {
        for (sku in listOf("H6004", "H6006", "H6008", "H6009", "H600A", "H6010", "H600D")) {
            assertEquals("bulb-v3", GoveeBleProtocol.profileFor(sku).id)
        }
        assertEquals((2700..6500 step 100).toList(), GoveeBleProtocol.temperatureValues("bulb-v3"))
        assertEquals("33050dffffff0fa0ffd5a10000000000000000e0", hex(GoveeBleProtocol.command("bulb-v3", LightCommand.Temperature(4000)).single()))
        assertThrows(IllegalArgumentException::class.java) { GoveeBleProtocol.command("bulb-v3", LightCommand.Temperature(2600)) }
        assertThrows(IllegalArgumentException::class.java) { GoveeBleProtocol.command("bulb-v3", LightCommand.Temperature(6600)) }
    }

    @Test fun `bulb brightness percent converts to physical scale instead of writing percent directly`() {
        val vectors = mapOf(1 to 20, 50 to 136, 100 to 254)
        for ((percent, raw) in vectors) {
            val write = GoveeBleProtocol.command("bulb-v2", LightCommand.Brightness(percent)).single()
            assertEquals(raw, write[2].toInt() and 255)
            assertEquals(percent, GoveeBleProtocol.parseState("bulb-v2", GoveeBleProtocol.frame(0xAA, 4, byteArrayOf(raw.toByte())))!!.brightness)
        }
        assertNull(GoveeBleProtocol.parseState("bulb-v2", GoveeBleProtocol.frame(0xAA, 4, byteArrayOf(0xFF.toByte()))))
        assertNull(GoveeBleProtocol.parseState("common-light", GoveeBleProtocol.frame(0xAA, 4, byteArrayOf(136.toByte()))))
    }

    @Test fun `white mode uses actual bulb lookup channels big endian Kelvin and XOR`() {
        assertEquals("33050dffffff0a8cffae54000000000000000047", hex(GoveeBleProtocol.command("bulb-v2", LightCommand.Temperature(2700)).single()))
        assertEquals("33050dffffff0fa0ffd5a10000000000000000e0", hex(GoveeBleProtocol.command("bulb-v2", LightCommand.Temperature(4000)).single()))
        assertEquals("33050dffffff1964fff9fb000000000000000044", hex(GoveeBleProtocol.command("bulb-v2", LightCommand.Temperature(6500)).single()))
    }
    @Test fun `white values are exact table entries and RGB clears the white channel fields`() {
        assertEquals((1800..9000 step 100).toList(), GoveeBleProtocol.temperatureValues("bulb-v2"))
        for (kelvin in GoveeBleProtocol.temperatureValues("bulb-v2")) {
            val packet = GoveeBleProtocol.command("bulb-v2", LightCommand.Temperature(kelvin)).single()
            assertTrue(GoveeBleProtocol.valid(packet))
            assertEquals(kelvin, GoveeBleProtocol.parseState("bulb-v2", GoveeBleProtocol.frame(0xAA, 5, packet.copyOfRange(2, 11)))!!.temperatureKelvin)
        }
        val rgb = GoveeBleProtocol.command("bulb-v2", LightCommand.Color(0x123456)).single()
        assertArrayEquals(ByteArray(5), rgb.copyOfRange(6, 11))
    }
    @Test fun `inferred color mode alone cannot enable white writes or arbitrary Kelvin`() {
        assertFalse(GoveeBleProtocol.supports("color-0d", "colorTemperatureK"))
        assertTrue(GoveeBleProtocol.temperatureValues("color-0d").isEmpty())
        assertNull(GoveeBleProtocol.temperatureRange("common-light"))
        assertThrows(IllegalArgumentException::class.java) { GoveeBleProtocol.command("color-0d", LightCommand.Temperature(4000)) }
        for (kelvin in listOf(1799, 9001, 4050, -1)) {
            assertThrows(IllegalArgumentException::class.java) { GoveeBleProtocol.command("bulb-v2", LightCommand.Temperature(kelvin)) }
        }
    }

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
        assertEquals("bulb-v3", GoveeBleProtocol.profileFor("H6008").id)
        assertEquals("unclassified", GoveeBleProtocol.profileFor("H6002").id)
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
