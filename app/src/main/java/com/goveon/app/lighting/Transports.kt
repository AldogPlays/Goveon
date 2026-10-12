package com.goveon.app.lighting

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
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
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection

internal class LanTransport(private val context: Context, private val scope: CoroutineScope, private val onDiscovery: (GoveeLanProtocol.Reply.Discovery) -> Unit, private val onState: (String, LightState) -> Unit) {
    private val startMutex = Mutex()
    private val lifecycleMonitor = Any()
    private var socket: MulticastSocket? = null
    private var receiver: Job? = null
    private var lock: WifiManager.MulticastLock? = null
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<LightState>>()
    @Volatile private var allowed = true

    private suspend fun start() = startMutex.withLock {
        check(allowed) { "LAN discovery paused while the app is in the background." }
        if (socket?.isClosed == false) return@withLock
        withContext(Dispatchers.IO) {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val multicastLock = wifi.createMulticastLock("goveon-discovery").apply { setReferenceCounted(false); acquire() }
            try {
                val s = MulticastSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(GoveeLanProtocol.RESPONSE_PORT)); soTimeout = 500; timeToLive = 1 }
                try {
                    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    val wifiNetwork = manager.allNetworks.firstOrNull { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
                    wifiNetwork?.bindSocket(s)
                    val iface = wifiNetwork?.let { manager.getLinkProperties(it)?.interfaceName }?.let { NetworkInterface.getByName(it) }
                    if (iface != null) { s.networkInterface = iface; s.joinGroup(InetSocketAddress(GoveeLanProtocol.MULTICAST_ADDRESS, GoveeLanProtocol.RESPONSE_PORT), iface) }
                    else { @Suppress("DEPRECATION") s.joinGroup(InetAddress.getByName(GoveeLanProtocol.MULTICAST_ADDRESS)) }
                } catch (failure: Exception) { s.close(); throw failure }
                synchronized(lifecycleMonitor) {
                    if (!allowed) { s.close(); error("LAN discovery paused while the app is in the background.") }
                    socket = s
                    lock = multicastLock
                    receiver = scope.launch(Dispatchers.IO) {
                    while (isActive && !s.isClosed) {
                        try {
                            val buffer = ByteArray(8192)
                            val packet = DatagramPacket(buffer, buffer.size)
                            s.receive(packet)
                            val ip = packet.address.hostAddress ?: continue
                            when (val reply = GoveeLanProtocol.parse(String(packet.data, packet.offset, packet.length, Charsets.UTF_8), ip)) {
                                is GoveeLanProtocol.Reply.Discovery -> onDiscovery(reply)
                                is GoveeLanProtocol.Reply.Status -> { onState(ip, reply.state); waiting.remove(ip)?.complete(reply.state) }
                                null -> Unit
                            }
                        } catch (_: SocketTimeoutException) { /* Periodic cancellation check. */ }
                        catch (_: Exception) { if (!s.isClosed) delay(100) }
                    }
                    }
                }
            } catch (failure: Exception) { multicastLock.release(); throw failure }
        }
    }
    suspend fun scan() {
        start()
        repeat(3) { send(GoveeLanProtocol.MULTICAST_ADDRESS, GoveeLanProtocol.DISCOVERY_PORT, GoveeLanProtocol.scan()); delay(700) }
        delay(900)
    }
    suspend fun control(ip: String, command: LightCommand) { start(); send(ip, GoveeLanProtocol.CONTROL_PORT, GoveeLanProtocol.command(command)) }
    suspend fun query(ip: String): LightState? {
        start()
        val answer = CompletableDeferred<LightState>()
        waiting[ip] = answer
        return try { send(ip, GoveeLanProtocol.CONTROL_PORT, GoveeLanProtocol.query()); withTimeoutOrNull(1600) { answer.await() } }
        finally { waiting.remove(ip, answer) }
    }
    private suspend fun send(ip: String, port: Int, text: String) = withContext(Dispatchers.IO) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        requireNotNull(socket).send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(ip), port))
    }
    fun close() = synchronized(lifecycleMonitor) { receiver?.cancel(); socket?.close(); socket = null; lock?.let { if (it.isHeld) it.release() }; lock = null; waiting.values.forEach { it.cancel() }; waiting.clear() }
    fun pause() = synchronized(lifecycleMonitor) { allowed = false; close() }
    fun resume() = synchronized(lifecycleMonitor) { allowed = true }
}

internal class CloudTransport(private val apiKey: () -> String?) {
    private val throttle = Mutex()
    private var lastRequest = 0L
    private val deviceRequests = mutableMapOf<String, Long>()
    suspend fun devices() = GoveeCloudProtocol.parseDevices(request("/user/devices"))
    suspend fun state(device: LightDevice) = request("/device/state", GoveeCloudProtocol.devicePayload(device), device.id)
    suspend fun scenes(device: LightDevice, diy: Boolean) = GoveeCloudProtocol.parseCapabilities(request(if (diy) "/device/diy-scenes" else "/device/scenes", GoveeCloudProtocol.devicePayload(device), device.id).optJSONObject("payload")?.optJSONArray("capabilities")).map { it.copy(staticOptions = emptyList()) }
    suspend fun control(device: LightDevice, command: LightCommand) {
        request("/device/control", GoveeCloudProtocol.controlPayload(device, command), device.id)
    }
    private suspend fun request(path: String, payload: JSONObject? = null, deviceId: String? = null): JSONObject = withContext(Dispatchers.IO) {
        val key = requireNotNull(apiKey()?.takeIf { it.isNotBlank() }) { "Add your Govee developer API key in Settings." }
        throttle.withLock {
            val now = SystemClock.elapsedRealtime()
            val wait = maxOf(0L, 100 - (now - lastRequest), if (deviceId != null) 550 - (now - (deviceRequests[deviceId] ?: 0)) else 0)
            if (wait > 0) delay(wait)
            lastRequest = SystemClock.elapsedRealtime()
            if (deviceId != null) deviceRequests[deviceId] = lastRequest
        }
        val connection = URL("https://openapi.api.govee.com/router/api/v1$path").openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 10_000; connection.readTimeout = 10_000
            connection.setRequestProperty("Govee-API-Key", key)
            connection.setRequestProperty("Content-Type", "application/json")
            if (payload != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                val body = GoveeCloudProtocol.requestEnvelope(payload).toString()
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            require(code in 200..299) { when (code) { 401, 403 -> "Govee rejected the API key."; 429 -> "Govee rate limit reached. Wait before retrying."; else -> "Govee API returned HTTP $code." } }
            val text = connection.inputStream.bufferedReader().use { reader ->
                val result = StringBuilder(); val buffer = CharArray(4096)
                while (true) { val count = reader.read(buffer); if (count < 0) break; require(result.length + count <= 4_000_000) { "Govee response is too large." }; result.append(buffer, 0, count) }
                result.toString()
            }
            val response = JSONObject(text)
            require(response.optInt("code", 200) == 200) { "Govee API did not accept this request (code ${response.optInt("code")})." }
            response
        } finally { connection.disconnect() }
    }
}
