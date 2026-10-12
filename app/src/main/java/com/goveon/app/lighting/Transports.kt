package com.goveon.app.lighting

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.BindException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

internal class LanTransport(private val context: Context, private val scope: CoroutineScope, private val onDiscovery: (GoveeLanProtocol.Reply.Discovery) -> Unit, private val onState: (String, LightState) -> Unit) {
    private val startMutex = Mutex()
    private val lifecycleMonitor = Any()
    private var socket: MulticastSocket? = null
    private var receiver: Job? = null
    private var lock: WifiManager.MulticastLock? = null
    private var socketNetwork: Network? = null
    @Volatile private var receiveFailure: LanFailure? = null
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<LightState>>()
    private val operations = CommandLocks()
    @Volatile private var allowed = true

    private suspend fun start() = startMutex.withLock {
        check(allowed) { "LAN discovery paused while the app is in the background." }
        withContext(Dispatchers.IO) {
            val binding = lanOperation("Select Wi-Fi network") { wifiBinding() }
            synchronized(lifecycleMonitor) {
                if (socket?.isClosed == false && socketNetwork == binding.network) return@withContext
                close()
            }
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: error("Wi-Fi is unavailable on this Android device.")
            val multicastLock = lanOperation("Enable Wi-Fi multicast reception") {
                wifi.createMulticastLock("goveon-discovery").apply { setReferenceCounted(false); acquire() }
            }
            try {
                val s = createLanSocket { candidate ->
                    lanOperation("Bind LAN socket to Wi-Fi") { binding.network.bindSocket(candidate) }
                    lanOperation("Join Wi-Fi multicast group") {
                        candidate.networkInterface = binding.iface
                        candidate.joinGroup(InetSocketAddress(GoveeLanProtocol.MULTICAST_ADDRESS, GoveeLanProtocol.RESPONSE_PORT), binding.iface)
                    }
                }
                synchronized(lifecycleMonitor) {
                    if (!allowed) { s.close(); error("LAN discovery paused while the app is in the background.") }
                    socket = s
                    socketNetwork = binding.network
                    receiveFailure = null
                    lock = multicastLock
                    receiver = scope.launch(Dispatchers.IO) {
                    val buffer = ByteArray(8192)
                    while (isActive && !s.isClosed) {
                        try {
                            val packet = DatagramPacket(buffer, buffer.size)
                            s.receive(packet)
                            val ip = packet.address.hostAddress ?: continue
                            val reply = GoveeLanProtocol.parse(String(packet.data, packet.offset, packet.length, Charsets.UTF_8), ip)
                            synchronized(lifecycleMonitor) {
                                // A packet received by an old socket must not restore online state after pause/rebind.
                                if (socket === s && allowed && !s.isClosed) when (reply) {
                                    is GoveeLanProtocol.Reply.Discovery -> onDiscovery(reply)
                                    is GoveeLanProtocol.Reply.Status -> { onState(ip, reply.state); waiting.remove(ip)?.complete(reply.state) }
                                    null -> Unit
                                }
                            }
                        } catch (_: SocketTimeoutException) { /* Periodic cancellation check. */ }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: IOException) {
                            synchronized(lifecycleMonitor) {
                                if (socket === s && !s.isClosed) {
                                    receiveFailure = LanFailure("Receive LAN replies", failure)
                                    waiting.values.forEach { it.completeExceptionally(requireNotNull(receiveFailure)) }
                                    waiting.clear()
                                    s.close()
                                    if (multicastLock.isHeld) multicastLock.release()
                                }
                            }
                            break
                        }
                    }
                    }
                }
            } catch (failure: Exception) { if (multicastLock.isHeld) multicastLock.release(); throw failure }
        }
    }
    suspend fun scan() {
        start()
        repeat(3) { send(GoveeLanProtocol.MULTICAST_ADDRESS, GoveeLanProtocol.DISCOVERY_PORT, GoveeLanProtocol.scan()); delay(700) }
        delay(900)
        receiveFailure?.let { throw it }
    }
    suspend fun controlAndQuery(ip: String, command: LightCommand): LightState? = operations.withLock(ip) {
        // Validate before opening networking; one address owns the entire command/refresh exchange.
        val packet = GoveeLanProtocol.command(command)
        start()
        send(ip, GoveeLanProtocol.CONTROL_PORT, packet)
        queryUnlocked(ip)
    }
    suspend fun query(ip: String): LightState? = operations.withLock(ip) { queryUnlocked(ip) }
    private suspend fun queryUnlocked(ip: String): LightState? {
        start()
        val answer = CompletableDeferred<LightState>()
        waiting[ip] = answer
        return try { send(ip, GoveeLanProtocol.CONTROL_PORT, GoveeLanProtocol.query()); withTimeoutOrNull(1600) { answer.await() }.also { receiveFailure?.let { failure -> throw failure } } }
        finally { waiting.remove(ip, answer) }
    }
    private suspend fun send(ip: String, port: Int, text: String) = withContext(Dispatchers.IO) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        receiveFailure?.let { throw it }
        lanOperation("Send LAN datagram") {
            val current = synchronized(lifecycleMonitor) { socket }
                ?: error("LAN networking stopped. Reopen discovery and retry.")
            current.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(ip), port))
        }
    }
    private data class WifiBinding(val network: Network, val iface: NetworkInterface)
    private fun wifiBinding(): WifiBinding {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val candidates = manager.allNetworks.filter { network ->
            val caps = manager.getNetworkCapabilities(network)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }.sortedByDescending { it == manager.activeNetwork }
        check(candidates.isNotEmpty()) { "Connect to the lights' Wi-Fi network before LAN discovery. Mobile data and VPN interfaces cannot receive these multicast replies." }
        for (network in candidates) {
            val links = manager.getLinkProperties(network) ?: continue
            val ipv4 = links.linkAddresses.firstOrNull { it.address is Inet4Address }?.address ?: continue
            val iface = links.interfaceName?.let { NetworkInterface.getByName(it) }
                ?: NetworkInterface.getByInetAddress(ipv4) ?: continue
            if (iface.isUp && !iface.isLoopback) return WifiBinding(network, iface)
        }
        error("Wi-Fi has no ready IPv4 interface. Reconnect to the lights' Wi-Fi network and retry.")
    }
    fun close() = synchronized(lifecycleMonitor) {
        receiver?.cancel(); receiver = null; socket?.close(); socket = null; socketNetwork = null; receiveFailure = null
        lock?.let { if (it.isHeld) it.release() }; lock = null
        // A network transition is a per-light failure, not cancellation of an entire group recipe.
        val failure = LanFailure("Read LAN state", IOException("The Wi-Fi connection closed or changed."))
        waiting.values.forEach { it.completeExceptionally(failure) }; waiting.clear()
    }
    fun pause() = synchronized(lifecycleMonitor) { allowed = false; close() }
    fun resume() = synchronized(lifecycleMonitor) { allowed = true }
}

/** Keep the UDP socket unconnected: replies arrive from multiple light addresses. */
internal fun createLanSocket(responsePort: Int = GoveeLanProtocol.RESPONSE_PORT, configureNetwork: (MulticastSocket) -> Unit): MulticastSocket {
    val socket = lanOperation("Create LAN response socket") { MulticastSocket(null) }
    try {
        lanOperation("Bind LAN response port $responsePort") {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), responsePort))
            socket.soTimeout = 500
            socket.timeToLive = 1
        }
        configureNetwork(socket)
        return socket
    } catch (failure: Exception) { socket.close(); throw failure }
}

internal class LanFailure(operation: String, cause: Exception) : IllegalStateException(lanFailureMessage(operation, cause), cause)

private fun lanFailureMessage(operation: String, cause: Exception): String {
    val details = generateSequence<Throwable>(cause) { it.cause }.mapNotNull { it.message?.trim()?.takeIf(String::isNotBlank) }
        .distinct().joinToString("; ").replace(Regex("\\s+"), " ").take(300).ifBlank { cause.javaClass.simpleName }
    val advice = when {
        cause is BindException -> "Close other LAN light controllers using UDP port 4002 and retry."
        cause is SecurityException || details.contains("EPERM", true) || details.contains("EACCES", true) ->
            "Android or a VPN denied local-network access. Allow LAN access in your VPN/network settings and retry."
        listOf("ENETUNREACH", "ENONET", "ENODEV", "EADDRNOTAVAIL").any { details.contains(it, true) } ->
            "The Wi-Fi interface is unavailable or changed. Reconnect to the lights' Wi-Fi and retry."
        else -> "Check Wi-Fi and LAN access in your VPN, then retry."
    }
    return "$operation failed: $details. $advice"
}

internal inline fun <T> lanOperation(operation: String, block: () -> T): T = try { block() }
catch (cancelled: CancellationException) { throw cancelled }
catch (failure: LanFailure) { throw failure }
catch (failure: Exception) { throw LanFailure(operation, failure) }
