package com.goveon.app.lighting

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal data class BleAdvertisement(val address: String, val name: String, val model: String?, val rssi: Int)
internal class BleFailure(message: String) : Exception(message)

/** Foreground BLE central. Each connection serializes GATT operations and owns its callbacks.
 * Never enables the radio, bonds a device, or sends Wi-Fi/account provisioning packets. */
@SuppressLint("MissingPermission") // Every entry point checks the applicable runtime permissions.
internal class BleTransport(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onDiscovery: (BleAdvertisement) -> Unit,
    private val onConnection: (String, BleConnection, String?) -> Unit,
    private val onState: (String, LightState) -> Unit,
) {
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val connections = ConcurrentHashMap<String, Connection>()
    private val scanMutex = Mutex()
    private val lifecycle = Any()
    @Volatile private var foreground = true
    @Volatile private var scanCallback: ScanCallback? = null
    @Volatile private var scanDone: CompletableDeferred<Unit>? = null
    @Volatile private var scanner: BluetoothLeScanner? = null

    private fun checkPermissions(scanning: Boolean) {
        val permissions = if (Build.VERSION.SDK_INT >= 31) {
            if (scanning) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else listOf(Manifest.permission.BLUETOOTH_CONNECT)
        } else if (scanning) listOf(Manifest.permission.ACCESS_FINE_LOCATION) else emptyList()
        if (permissions.any { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED })
            throw BleFailure("Allow ${if (Build.VERSION.SDK_INT >= 31) "Nearby devices" else "Location"} permission to use Bluetooth.")
        val location = context.getSystemService(LocationManager::class.java)
        val locationEnabled = if (Build.VERSION.SDK_INT >= 28) location?.isLocationEnabled == true
            else location?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true || location?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
        if (scanning && Build.VERSION.SDK_INT < 31 && !locationEnabled)
            throw BleFailure("Android requires Location services to be enabled for Bluetooth scanning on this version. Goveon does not collect your location.")
        if (adapter == null) throw BleFailure("This Android device does not support Bluetooth.")
        if (adapter?.isEnabled != true) throw BleFailure("Turn on Bluetooth to find nearby lights.")
        if (!foreground) throw BleFailure("Bluetooth is paused while Goveon is in the background.")
    }

    suspend fun scan(): Int = scanMutex.withLock {
        checkPermissions(true)
        val bleScanner = adapter?.bluetoothLeScanner ?: throw BleFailure("Bluetooth scanner is unavailable. Turn Bluetooth off and on, then retry.")
        val done = CompletableDeferred<Unit>()
        val seen = ConcurrentHashMap<String, Pair<Long, Int>>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = receive(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach(::receive) }
            override fun onScanFailed(errorCode: Int) {
                done.completeExceptionally(BleFailure(when (errorCode) {
                    SCAN_FAILED_ALREADY_STARTED -> "A Bluetooth scan is already running. Wait and retry."
                    SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Android could not register the Bluetooth scan. Restart Bluetooth and retry."
                    SCAN_FAILED_FEATURE_UNSUPPORTED -> "This device does not support the requested Bluetooth scan."
                    else -> "Android stopped Bluetooth scanning (code $errorCode). Wait a few seconds and retry."
                }))
            }
            private fun receive(result: ScanResult) {
                if (scanCallback !== this) return
                val record = result.scanRecord
                val name = record?.deviceName ?: runCatching { result.device.name }.getOrNull().orEmpty()
                val services = record?.serviceUuids.orEmpty().map { it.uuid.toString() }
                if (!GoveeBleProtocol.isCandidate(name, services)) return
                val address = try { result.device.address.uppercase() } catch (_: SecurityException) {
                    done.completeExceptionally(BleFailure("Bluetooth permission was revoked. Allow Nearby devices and retry discovery."))
                    return
                }
                val time = android.os.SystemClock.elapsedRealtime()
                val previous = seen[address]
                if (previous != null && (time - previous.first < 1_500 || kotlin.math.abs(previous.second - result.rssi) < 5)) return
                seen[address] = time to result.rssi
                onDiscovery(BleAdvertisement(address, name.ifBlank { "Govee Bluetooth light" }, GoveeBleProtocol.modelFromName(name), result.rssi))
            }
        }
        synchronized(lifecycle) {
            check(foreground) { "Bluetooth is paused while Goveon is in the background." }
            scanner = bleScanner; scanDone = done; scanCallback = callback
            // Unfiltered foreground scan: some Govee models advertise a name but no service UUID.
        }
        try {
            synchronized(lifecycle) {
                if (!foreground || scanCallback !== callback) throw BleFailure("Bluetooth scan was cancelled.")
                bleScanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            }
            withTimeoutOrNull(12_000) { done.await() }
        }
        finally { stopScan() }
        seen.size
    }

    private fun stopScan() = synchronized(lifecycle) {
        val callback = scanCallback
        scanCallback = null
        if (callback != null) runCatching { scanner?.stopScan(callback) }
        scanner = null
        scanDone?.complete(Unit); scanDone = null
    }

    suspend fun connect(address: String, model: String): String {
        checkPermissions(false)
        connections[address]?.let { if (it.ready) return it.profile!!.id }
        stopScan()
        val connection = Connection(address, model)
        synchronized(lifecycle) {
            if (!foreground) throw BleFailure("Bluetooth is paused while Goveon is in the background.")
            connections.put(address, connection)?.close()
            onConnection(address, BleConnection.CONNECTING, null)
        }
        try {
            withTimeout(18_000) {
                withContext(Dispatchers.Main.immediate) {
                    synchronized(lifecycle) {
                        if (!foreground || connection.closed) throw BleFailure("Bluetooth connection was cancelled.")
                        val handle = adapter!!.getRemoteDevice(address).connectGatt(context, false, connection.callback, BluetoothDevice.TRANSPORT_LE,
                            BluetoothDevice.PHY_LE_1M_MASK, android.os.Handler(android.os.Looper.getMainLooper()))
                            ?: throw BleFailure("Android could not open the Bluetooth connection.")
                        if (connection.closed || connections[address] !== connection) {
                            runCatching { handle.disconnect() }; runCatching { handle.close() }
                            throw BleFailure("Bluetooth connection was cancelled during setup.")
                        }
                        connection.gatt = handle
                    }
                }
                connection.services.await()
                connection.configure()
            }
            synchronized(lifecycle) {
                if (!foreground || connection.closed || connections[address] !== connection) throw BleFailure("The light disconnected during setup.")
                connection.ready = true
                onConnection(address, BleConnection.CONNECTED, connection.profile!!.id)
                connection.startRefresh()
            }
            return connection.profile!!.id
        } catch (cancelled: CancellationException) {
            disconnectOwned(address, connection)
            if (cancelled is TimeoutCancellationException) throw BleFailure("Bluetooth connection timed out. Move closer, power-cycle the light, and close other apps connected to it.")
            throw cancelled
        } catch (failure: Exception) { disconnectOwned(address, connection); throw failure }
    }

    suspend fun send(address: String, command: LightCommand) {
        checkPermissions(false)
        val connection = connections[address]?.takeIf { it.ready && !it.closed }
            ?: throw BleFailure("Connect to the light over Bluetooth first.")
        val frames = GoveeBleProtocol.command(connection.profile!!.id, command)
        connection.operations.withLock {
            for (frame in frames) { connection.write(frame); delay(80) }
        }
    }

    suspend fun query(address: String) {
        checkPermissions(false)
        val connection = connections[address]?.takeIf { it.ready && !it.closed }
            ?: throw BleFailure("Connect to the light over Bluetooth first.")
        connection.operations.withLock {
            for (frame in GoveeBleProtocol.queries(connection.profile!!.id)) { connection.write(frame); delay(80) }
        }
    }

    fun disconnect(address: String) = synchronized(lifecycle) {
        connections.remove(address)?.close()
        onConnection(address, BleConnection.DISCONNECTED, null)
    }
    private fun disconnectOwned(address: String, owner: Connection) = synchronized(lifecycle) {
        owner.close()
        if (connections.remove(address, owner)) onConnection(address, BleConnection.DISCONNECTED, null)
    }
    fun pause() = synchronized(lifecycle) { foreground = false; stopScan(); connections.keys.toList().forEach(::disconnect) }
    fun resume() = synchronized(lifecycle) { foreground = true }
    fun close() = pause()

    private inner class Connection(val address: String, val model: String) {
        @Volatile var gatt: BluetoothGatt? = null
        @Volatile var closed = false
        @Volatile var ready = false
        @Volatile var profile: GoveeBleProtocol.Profile? = null
        @Volatile private var probing = false
        @Volatile private var readPower = false
        @Volatile private var readBrightness = false
        private val probe = CompletableDeferred<Unit>()
        private var refreshJob: Job? = null
        private var writeCharacteristic: BluetoothGattCharacteristic? = null
        private val subscribed = ConcurrentHashMap.newKeySet<UUID>()
        val services = CompletableDeferred<Unit>()
        val operations = Mutex()
        @Volatile private var pendingWrite: CompletableDeferred<Int>? = null
        @Volatile private var pendingDescriptor: CompletableDeferred<Int>? = null
        private fun current(g: BluetoothGatt) = !closed && connections[address] === this && (gatt == null || gatt === g)
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (!current(g)) { runCatching { g.close() }; return }
                if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                    val error = BleFailure(if (status == 5 || status == 15) "The light requires Bluetooth authentication. This model's pairing handshake is not supported yet."
                        else "Bluetooth disconnected (GATT status $status). Move closer and close other apps connected to the light.")
                    services.completeExceptionally(error)
                    pendingWrite?.completeExceptionally(error); pendingDescriptor?.completeExceptionally(error)
                    disconnectOwned(address, this@Connection)
                } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                    try {
                        if (!g.discoverServices()) services.completeExceptionally(BleFailure("Android could not start Bluetooth service discovery."))
                    } catch (_: SecurityException) {
                        services.completeExceptionally(BleFailure("Bluetooth permission was revoked. Allow Nearby devices and reconnect."))
                        disconnectOwned(address, this@Connection)
                    }
                }
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (!current(g)) return
                if (status == BluetoothGatt.GATT_SUCCESS) services.complete(Unit)
                else services.completeExceptionally(BleFailure("Bluetooth service discovery failed (GATT status $status)."))
            }
            override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (current(g)) pendingDescriptor?.complete(status)
            }
            override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (current(g) && characteristic.uuid == writeCharacteristic?.uuid) pendingWrite?.complete(status)
            }
            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                if (current(g)) receive(characteristic, value)
            }
            @Deprecated("Pre-Android 13 callback")
            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT < 33 && current(g)) {
                    @Suppress("DEPRECATION")
                    val value = characteristic.value?.copyOf() ?: return
                    receive(characteristic, value)
                }
            }
        }
        private fun receive(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            var p = profile ?: return
            if (characteristic.uuid !in subscribed) return
            if (p.id == "common-light" && GoveeBleProtocol.matchesColorProfile(value)) {
                // A real current-mode reply corroborates the recovered 0D color layout.
                // Discovery names alone never enable an inferred RGB write profile.
                synchronized(lifecycle) {
                    if (closed || connections[address] !== this) return
                    p = GoveeBleProtocol.profiles.first { it.id == "color-0d" }
                    profile = p
                    if (ready) onConnection(address, BleConnection.CONNECTED, p.id)
                }
            }
            GoveeBleProtocol.parseState(if (probing) "common-light" else p.id, value)?.let {
                if (probing) {
                    if (it.power != null) readPower = true
                    if (it.brightness != null) readBrightness = true
                    if (readPower && readBrightness) probe.complete(Unit)
                }
                onState(address, it)
            }
        }
        fun startRefresh() {
            if (!GoveeBleProtocol.supports(profile?.id, "powerSwitch")) return
            refreshJob = scope.launch {
                try {
                    while (isActive) {
                        delay(20_000)
                        checkPermissions(false)
                        operations.withLock {
                            if (closed || !ready || connections[address] !== this@Connection) return@launch
                            for (packet in GoveeBleProtocol.queries(profile!!.id)) { write(packet); delay(80) }
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { disconnectOwned(address, this@Connection) }
            }
        }
        @Suppress("DEPRECATION")
        suspend fun configure() = operations.withLock {
            val g = gatt ?: throw BleFailure("Bluetooth connection was closed.")
            val p = GoveeBleProtocol.profileFor(model)
            if (g.getService(UUID.fromString(p.serviceUuid))?.getCharacteristic(UUID.fromString(p.writeUuid)) == null)
                throw BleFailure("This device exposes an unsupported Bluetooth service. No control packets were sent.")
            profile = p
            val service = g.getService(UUID.fromString(p.serviceUuid))
            val writer = service.getCharacteristic(UUID.fromString(p.writeUuid))
            val writable = writer.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            if (!writable) throw BleFailure("The Govee control characteristic is not writable.")
            writeCharacteristic = writer
            // The shipped client enables notifications across the communication service.
            // Discover actual notify/indicate properties instead of inventing a separate UUID.
            val notifications = service.characteristics.filter {
                it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 &&
                    it.getDescriptor(UUID.fromString(CCCD)) != null
            }
            if (notifications.isEmpty()) throw BleFailure("The Govee notification characteristic or descriptor is missing. No control packets were sent.")
            for (notify in notifications) {
                if (!g.setCharacteristicNotification(notify, true)) throw BleFailure("Android could not enable Bluetooth notifications.")
                val cccd = notify.getDescriptor(UUID.fromString(CCCD))
                val answer = CompletableDeferred<Int>(); pendingDescriptor = answer
                val bytes = if (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                try {
                    val started = if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(cccd, bytes) == BluetoothStatusCodes.SUCCESS
                    else { cccd.value = bytes; g.writeDescriptor(cccd) }
                    if (!started) throw BleFailure("Android refused the Bluetooth notification setup.")
                    val status = withTimeout(5_000) { answer.await() }
                    if (status != BluetoothGatt.GATT_SUCCESS) throw BleFailure("Bluetooth notification setup failed (GATT status $status).")
                    subscribed.add(notify.uuid)
                } finally { pendingDescriptor = null }
            }
            // Profile-specific initialization only; no speculative authorization or provisioning.
            for (frame in GoveeBleProtocol.initialization(p.id)) { write(frame); delay(80) }
            if (p.id == "unclassified" && GoveeBleProtocol.canProbe(model)) {
                probing = true
                try {
                    for (packet in GoveeBleProtocol.probeQueries()) { write(packet); delay(80) }
                    if (withTimeoutOrNull(2_500) { probe.await(); true } == true) {
                        profile = GoveeBleProtocol.profiles.first { it.id == "common-light" }
                    }
                } finally { probing = false }
            }
        }
        @Suppress("DEPRECATION")
        suspend fun write(bytes: ByteArray) {
            if (closed) throw BleFailure("Bluetooth connection was closed.")
            val g = gatt ?: throw BleFailure("Bluetooth connection was closed.")
            val writer = writeCharacteristic ?: throw BleFailure("Bluetooth control is not ready.")
            val type = if (writer.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            val answer = CompletableDeferred<Int>(); pendingWrite = answer
            try {
                val started = if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(writer, bytes, type) == BluetoothStatusCodes.SUCCESS
                else { writer.value = bytes; writer.writeType = type; g.writeCharacteristic(writer) }
                if (!started) throw BleFailure("Android refused the Bluetooth write. Reconnect and retry.")
                val status = withTimeout(5_000) { answer.await() }
                if (status != BluetoothGatt.GATT_SUCCESS) throw BleFailure(if (status == 5 || status == 15) "This light requires an unsupported Bluetooth authentication handshake." else "Bluetooth write failed (GATT status $status).")
            } catch (cancelled: CancellationException) {
                // A late callback must never complete the next operation's deferred.
                disconnectOwned(address, this)
                if (cancelled is TimeoutCancellationException) throw BleFailure("Bluetooth write timed out. Reconnect and retry.")
                throw cancelled
            } catch (failure: Exception) { disconnectOwned(address, this); throw failure }
            finally { pendingWrite = null }
        }
        fun close() {
            closed = true; ready = false
            refreshJob?.cancel(); refreshJob = null
            services.completeExceptionally(BleFailure("Bluetooth connection was closed."))
            probe.completeExceptionally(BleFailure("Bluetooth connection was closed."))
            pendingWrite?.completeExceptionally(BleFailure("Bluetooth connection was closed."))
            pendingDescriptor?.completeExceptionally(BleFailure("Bluetooth connection was closed."))
            val handle = gatt; gatt = null
            handle?.let { runCatching { it.disconnect() }; runCatching { it.close() } }
        }
    }
    companion object { private const val CCCD = "00002902-0000-1000-8000-00805f9b34fb" }
}
