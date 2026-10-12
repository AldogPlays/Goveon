package com.goveon.app.lighting

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.MulticastSocket

class LanTransportTest {
    @Test fun `unconnected response socket receives real UDP discovery replies`() {
        createLanSocket(responsePort = 0) { socket ->
            assertTrue(socket.isBound)
            assertFalse(socket.isConnected)
        }.use { response ->
            response.soTimeout = 2_000
            val body = """{"msg":{"cmd":"scan","data":{"device":"aa:bb","sku":"HTEST"}}}""".toByteArray()
            DatagramSocket().use { sender ->
                sender.send(DatagramPacket(body, body.size, InetAddress.getByName("127.0.0.1"), response.localPort))
            }
            val packet = DatagramPacket(ByteArray(512), 512)
            response.receive(packet)
            val reply = GoveeLanProtocol.parse(String(packet.data, packet.offset, packet.length, Charsets.UTF_8), packet.address.hostAddress!!)
                as GoveeLanProtocol.Reply.Discovery
            assertEquals("AA:BB", reply.id)
            assertEquals("127.0.0.1", reply.ip)
            assertFalse(response.isConnected)
        }
    }

    @Test fun `network binding failure closes response socket and survives friendly error mapping`() {
        var opened: MulticastSocket? = null
        val original = IOException("bindSocket failed: ENONET (Network is not available)")
        val failure = assertThrows(LanFailure::class.java) {
            createLanSocket(responsePort = 0) { socket ->
                opened = socket
                lanOperation("Bind LAN socket to Wi-Fi") { throw original }
            }
        }
        assertTrue(opened!!.isClosed)
        assertSame(original, failure.cause)
        val message = friendly(failure)
        assertTrue(message.contains("Bind LAN socket to Wi-Fi"))
        assertTrue(message.contains("ENONET"))
        assertTrue(message.contains("Reconnect"))
        assertFalse(message.startsWith("Network connection failed"))
    }

    @Test fun `permission failure includes nested native cause and LAN access guidance`() {
        val failure = LanFailure("Join Wi-Fi multicast group", IOException("join failed", IOException("EPERM (Operation not permitted)")))
        assertTrue(friendly(failure).contains("EPERM"))
        assertTrue(friendly(failure).contains("Allow LAN access"))
        assertTrue(friendly(failure).contains("Join Wi-Fi multicast group"))
    }

    @Test fun `cancelled setup releases its socket without disguising cancellation as network failure`() {
        var opened: MulticastSocket? = null
        val cancellation = CancellationException("App left foreground")
        val thrown = assertThrows(CancellationException::class.java) {
            createLanSocket(responsePort = 0) { socket ->
                opened = socket
                lanOperation("Bind LAN socket to Wi-Fi") { throw cancellation }
            }
        }
        assertSame(cancellation, thrown)
        assertTrue(opened!!.isClosed)
    }
}
