package com.goveon.app.lighting

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

class LightRepository(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = LocalStore(appContext)
    private val keys = ApiKeyStore(appContext)
    @Volatile private var apiKey: String? = null
    private val monitor = Any()
    private val mutableState = MutableStateFlow(store.load())
    val state: StateFlow<AppState> = mutableState.asStateFlow()
    private val devicesMutex = ConcurrentHashMap<String, Mutex>()
    private val scanMutex = Mutex()
    private val lan = LanTransport(appContext, scope, ::discovered, ::receivedState)
    private val ble = BleTransport(appContext, scope, ::discoveredBluetooth, ::bluetoothConnection, ::bluetoothState)
    private val activeControlIds = MutableStateFlow<Set<String>>(emptySet())
    private val actionLeases = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    private val foreground = MutableStateFlow(false)
    private val sessions = BleSessionController(scope, ::connectForControls, ::disconnectForControls)
    val bluetoothSessions: StateFlow<Map<String, BleSessionStatus>> = sessions.status
    private val cloud = CloudTransport { apiKey }
    init {
        scope.launch {
            combine(activeControlIds, actionLeases, foreground, state.map { it.devices }) { pageIds, leases, visible, devices ->
                val ids = pageIds + leases.values.flatten()
                devices.filter { it.id in ids && it.bleAddress != null }.map {
                    BleSessionTarget(it.id, it.bleConnection, failureReason = ble.failureReason(it.bleAddress!!))
                } to visible
            }.distinctUntilChanged().collect { (targets, visible) -> sessions.update(targets, visible) }
        }
        runCatching { keys.read() }.onSuccess { apiKey = it; change { s -> s.copy(hasApiKey = !it.isNullOrBlank()) } }
            .onFailure { change { it.copy(error = "The saved API key could not be decrypted. Please enter it again.") } }
    }
    private fun change(persist: Boolean = false, transform: (AppState) -> AppState) = synchronized(monitor) {
        mutableState.value = transform(mutableState.value)
        if (persist) store.save(mutableState.value)
    }
    private fun discovered(reply: GoveeLanProtocol.Reply.Discovery) = change(true) { s ->
        val existing = s.devices.firstOrNull { it.id == reply.id }
        val device = existing?.copy(ip = reply.ip, model = reply.model, state = existing.state.copy(online = true))
            ?: LightDevice(reply.id, reply.model, "Govee ${reply.model}", reply.ip, state = LightState(online = true))
        s.copy(devices = replaceDevice(s.devices, device))
    }
    private fun receivedState(ip: String, reading: LightState) = change { s -> s.copy(devices = s.devices.map { if (it.ip == ip) it.copy(state = reading.copy(values = it.state.values)) else it }) }
    private fun discoveredBluetooth(advertisement: BleAdvertisement) = change(true) { s ->
        val existing = s.devices.firstOrNull { it.bleAddress == advertisement.address || it.id == advertisement.address }
        val found = existing?.copy(bleAddress = advertisement.address, bleRssi = advertisement.rssi,
            model = advertisement.model ?: existing.model)
            ?: LightDevice("BLE:${advertisement.address}", advertisement.model ?: "Unknown BLE model", advertisement.name,
                bleAddress = advertisement.address, bleRssi = advertisement.rssi)
        s.copy(devices = replaceDevice(s.devices, found))
    }
    private fun bluetoothConnection(address: String, connection: BleConnection, profile: String?) = change(true) { s ->
        s.copy(devices = s.devices.map { if (it.bleAddress == address) it.copy(bleConnection = connection,
            bleProfile = profile ?: it.bleProfile,
            state = if (connection == BleConnection.DISCONNECTED) it.state.copy(online = null, lastUpdated = 0) else it.state) else it })
    }
    private fun bluetoothState(address: String, reading: LightState) = change { s ->
        s.copy(devices = s.devices.map { if (it.bleAddress == address) it.copy(state = it.state.copy(
            power = reading.power ?: it.state.power, brightness = reading.brightness ?: it.state.brightness,
            rgb = reading.rgb ?: it.state.rgb, temperatureKelvin = reading.temperatureKelvin ?: it.state.temperatureKelvin,
            online = true, lastUpdated = reading.lastUpdated)) else it })
    }
    private fun device(id: String) = state.value.devices.firstOrNull { it.id == id } ?: error("This device is no longer saved.")
    fun clearError() = change { it.copy(error = null) }

    suspend fun discoverBluetooth(): Int {
        if (state.value.isBluetoothScanning) return 0
        change { it.copy(isBluetoothScanning = true, error = null) }
        return try { ble.scan() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { Diagnostics.failure("Bluetooth discovery", e); change { it.copy(error = friendly(e)) }; 0 }
        finally { change { it.copy(isBluetoothScanning = false) } }
    }
    private suspend fun establishBluetooth(id: String): CommandResult = lockFor(id).withLock {
            val target = device(id)
            val address = requireNotNull(target.bleAddress) { "Discover this light over Bluetooth first." }
            val profile = ble.connect(address, target.model)
            ble.query(address)
            CommandResult(id, CommandStatus.ACCEPTED,
                if (profile == "unclassified") "Bluetooth connected, but this model's control protocol could not be identified. Unsupported controls stay hidden."
                else "Bluetooth connected. Controls work locally; reported state appears when the light replies.", "Bluetooth")
    }
    suspend fun connectBluetooth(id: String): CommandResult = try { establishBluetooth(id) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { Diagnostics.failure("Bluetooth connection", e); failure(id, friendly(e)) }

    private suspend fun connectForControls(id: String): BleSessionAttempt = try {
        Diagnostics.command(establishBluetooth(id))
        BleSessionAttempt.Connected
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (e: Exception) {
        Diagnostics.failure("Automatic Bluetooth connection", e)
        val reason = when (e) {
            is BleFailure -> e.kind
            is SecurityException -> BleFailureReason.PERMISSION
            is IllegalArgumentException -> BleFailureReason.UNSUPPORTED
            else -> BleFailureReason.RETRYABLE
        }
        if (reason == BleFailureReason.RETRYABLE) BleSessionAttempt.Retryable(friendly(e))
        else BleSessionAttempt.Terminal(friendly(e), reason)
    }
    private fun disconnectForControls(id: String) { state.value.devices.firstOrNull { it.id == id }?.bleAddress?.let(ble::disconnect) }
    fun setActiveControls(ids: Set<String>) { activeControlIds.value = ids }
    fun retryBluetoothControls() { activeControlIds.value.forEach(sessions::retry) }
    fun disconnectBluetooth(id: String) { device(id).bleAddress?.let(ble::disconnect) }

    /** Explicit foreground discovery; no background polling or account authentication. */
    suspend fun scan() {
        if (!scanMutex.tryLock()) return
        change { it.copy(isScanning = true, error = null) }
        try {
            val failures = mutableListOf<String>()
            coroutineScope {
                val local = async { attempt { lan.scan() } }
                val remote = async {
                    if (apiKey != null) attempt {
                        val discovered = cloud.devices()
                        change(true) { s ->
                            val ids = discovered.map { it.id }.toSet()
                            var list = s.devices.map { if (it.cloudAvailable && it.id !in ids) it.copy(cloudAvailable = false) else it }
                            discovered.forEach { new ->
                                val old = list.firstOrNull { it.id == new.id }
                                list = replaceDevice(list, new.copy(ip = old?.ip, customName = old?.customName, state = old?.state ?: LightState(),
                                    bleAddress = old?.bleAddress, bleConnection = old?.bleConnection ?: BleConnection.DISCONNECTED,
                                    bleRssi = old?.bleRssi, bleProfile = old?.bleProfile))
                            }
                            s.copy(devices = list)
                        }
                    } else null
                }
                local.await()?.let { failures.add("LAN: $it") }
                remote.await()?.let { failures.add("Cloud: $it") }
            }
            val ids = state.value.devices.filter { it.ip != null || it.cloudAvailable }.map { it.id }
            dispatchGroup(ids.toSet(), LightCommand.Power(false)) { id, _ -> refresh(id) }
            if (failures.isNotEmpty()) change { it.copy(error = failures.joinToString("\n")) }
        } finally { change { it.copy(isScanning = false) }; scanMutex.unlock() }
    }

    suspend fun refresh(deviceId: String): CommandResult = lockFor(deviceId).withLock {
        try {
            val device = device(deviceId)
            if (device.bleConnection == BleConnection.CONNECTED && device.bleAddress != null) {
                ble.query(device.bleAddress)
                return@withLock CommandResult(deviceId, CommandStatus.ACCEPTED, "Bluetooth state requested. Readings update when the light replies.", "Bluetooth")
            }
            if (device.ip != null && (state.value.settings.preferLan || !device.cloudAvailable || apiKey == null)) {
                val reply = try { lan.query(device.ip) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (e: Exception) { if (!device.cloudAvailable || apiKey == null) throw e else null }
                if (reply != null) return@withLock CommandResult(deviceId, if (reply.hasReading) CommandStatus.CONFIRMED else CommandStatus.ACCEPTED, if (reply.hasReading) "State refreshed." else "Light replied without readable state values.", "LAN")
                if (!device.cloudAvailable || apiKey == null) {
                    change { s -> s.copy(devices = s.devices.map { if (it.id == deviceId) it.copy(state = it.state.copy(online = false)) else it }) }
                    return@withLock failure(deviceId, "No LAN reply. Check Wi-Fi, power, and the device's LAN switch.")
                }
            }
            require(device.cloudAvailable) { if (device.bleAddress != null) "Connect over Bluetooth to read this light." else "This device is only available through LAN." }
            val refreshed = GoveeCloudProtocol.parseState(cloud.state(device), device.state)
            change { s -> s.copy(devices = s.devices.map { if (it.id == deviceId) it.copy(state = refreshed) else it }) }
            when (refreshed.online) {
                false -> failure(deviceId, "Govee reports this device offline.")
                true -> CommandResult(deviceId, CommandStatus.CONFIRMED, "State refreshed.", "Cloud")
                null -> CommandResult(deviceId, CommandStatus.ACCEPTED, "Govee did not provide current online status.", "Cloud")
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { failure(deviceId, friendly(e)) }
    }

    suspend fun loadScenes(deviceId: String): CommandResult = lockFor(deviceId).withLock {
        try {
            val device = device(deviceId)
            require(device.cloudAvailable) { "Scenes and DIY require a supported device and developer API key." }
            // Fetch separately: an unsupported DIY endpoint must not discard real scene results.
            val capabilities = mutableListOf<LightCapability>()
            val errors = mutableListOf<String>()
            for (diy in listOf(false, true)) {
                try { capabilities.addAll(cloud.scenes(device, diy)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (e: Exception) { errors.add("${if (diy) "DIY" else "Scenes"}: ${friendly(e)}") }
            }
            if (capabilities.isNotEmpty()) change(true) { s -> s.copy(devices = s.devices.map { if (it.id == deviceId) it.copy(capabilities = mergeCapabilities(it.capabilities, capabilities)) else it }) }
            if (errors.isNotEmpty()) CommandResult(deviceId, if (capabilities.isEmpty()) CommandStatus.FAILED else CommandStatus.ACCEPTED, errors.joinToString("\n"), "Cloud")
            else CommandResult(deviceId, CommandStatus.CONFIRMED, "Scene catalog loaded.", "Cloud")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { failure(deviceId, friendly(e)) }
    }

    suspend fun send(deviceId: String, command: LightCommand): CommandResult = lockFor(deviceId).withLock {
        try {
            val target = device(deviceId)
            if (target.bleConnection == BleConnection.CONNECTED && target.bleAddress != null && GoveeBleProtocol.supports(target.bleProfile, command.instance())) {
                ble.send(target.bleAddress, command)
                // GATT success acknowledges delivery, not that the light changed physically.
                val refreshError = attempt { ble.query(target.bleAddress) }
                return@withLock CommandResult(deviceId, CommandStatus.ACCEPTED,
                    if (refreshError == null) "Bluetooth command delivered. State requested; readings update when the light replies."
                    else "Bluetooth command delivered, but state refresh failed: $refreshError", "Bluetooth")
            }
            if (target.bleAddress != null && target.ip == null && !target.cloudAvailable)
                throw BleFailure(if (target.bleConnection != BleConnection.CONNECTED) "Connect over Bluetooth before controlling this light." else "This control is not supported by this light's Bluetooth profile yet.")
            val canLan = target.ip != null && command !is LightCommand.Capability
            // Cloud schemas narrow model-specific ranges whenever available.
            if (target.cloudAvailable && command !is LightCommand.Capability && target.capability(command.instance()) != null) GoveeCloudProtocol.capabilityValue(target, command)
            if (canLan && (state.value.settings.preferLan || apiKey == null || !target.cloudAvailable)) {
                val reply = try {
                    lan.control(requireNotNull(target.ip), command)
                    lan.query(target.ip)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (e: Exception) { if (!target.cloudAvailable || apiKey == null) throw e else null }
                if (reply != null && matches(command, reply)) return@withLock CommandResult(deviceId, CommandStatus.CONFIRMED, "Light reported the requested state.", "LAN")
                if (apiKey == null || !target.cloudAvailable) {
                    return@withLock if (reply == null) CommandResult(deviceId, CommandStatus.ACCEPTED, "UDP command sent; the light did not confirm its state.", "LAN")
                    else failure(deviceId, "The light reported a different value. This model may not support the setting.")
                }
            }
            require(target.cloudAvailable) { "This control is not available through the documented LAN protocol." }
            cloud.control(target, command)
            // Cloud acceptance is not physical confirmation; never overwrite readings optimistically.
            CommandResult(deviceId, CommandStatus.ACCEPTED, "Govee accepted the command. Refresh to check the light.", "Cloud")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { failure(deviceId, friendly(e)) }
    }
    suspend fun sendGroup(groupId: String, command: LightCommand): GroupResult {
        val group = state.value.groups.firstOrNull { it.id == groupId } ?: return GroupResult(listOf(failure(groupId, "This group is no longer saved.")))
        return dispatchGroup(group.deviceIds, command, ::send)
    }
    suspend fun runQuickAction(id: String): GroupResult {
        val action = state.value.quickActions.firstOrNull { it.id == id } ?: return GroupResult(listOf(failure(id, "This quick action is no longer saved.")))
        val bluetoothRequired = state.value.devices.filter { it.id in action.deviceIds && it.bleAddress != null && it.ip == null && (!it.cloudAvailable || apiKey == null) }.map { it.id }.toSet()
        val lease = UUID.randomUUID().toString()
        actionLeases.update { it + (lease to bluetoothRequired) }
        bluetoothRequired.filter { bluetoothSessions.value[it]?.phase == BleSessionPhase.STOPPED }.forEach(sessions::retry)
        try {
            return dispatchGroup(action.deviceIds, LightCommand.Power(false)) { deviceId, _ ->
                if (deviceId in bluetoothRequired) {
                    // Each member sends as soon as ready; an unavailable member does not block others.
                    val ready = withTimeoutOrNull(65_000) {
                        combine(bluetoothSessions, foreground) { statuses, visible -> statuses[deviceId] to visible }
                            .first { (status, visible) -> !visible || status?.phase in setOf(BleSessionPhase.CONNECTED, BleSessionPhase.STOPPED) }
                    }
                    if (ready?.second == false) throw CancellationException("Quick action paused in background.")
                    if (ready?.first?.phase != BleSessionPhase.CONNECTED) return@dispatchGroup failure(deviceId, "Bluetooth couldn't connect. Check the light and retry.")
                }
                var result = failure(deviceId, "This quick action has no commands.")
                for (command in action.commands) { result = send(deviceId, command); if (!result.successful) break }
                result
            }
        } finally {
            // Independent lease keys preserve connections still owned by another action or page.
            actionLeases.update { it - lease }
        }
    }
    fun renameDevice(id: String, name: String) = change(true) { s -> s.copy(devices = s.devices.map { if (it.id == id) it.copy(customName = name.trim().take(80).takeIf { it.isNotBlank() }) else it }) }
    fun setBluetoothModel(id: String, model: String) {
        val sku = model.trim().uppercase()
        require(GoveeBleProtocol.canProbe(sku)) { "Enter the H6xxx lighting model printed on the light or its controller." }
        val target = device(id)
        require(target.bleAddress != null) { "This device was not discovered over Bluetooth." }
        ble.disconnect(target.bleAddress)
        change(true) { s -> s.copy(devices = s.devices.map { if (it.id == id) it.copy(model = sku, bleProfile = null, state = LightState()) else it }) }
        sessions.retry(id)
    }
    fun forgetDevice(id: String) { state.value.devices.firstOrNull { it.id == id }?.bleAddress?.let(ble::disconnect); change(true) { s -> s.copy(devices = s.devices.filterNot { it.id == id }, groups = s.groups.map { it.copy(deviceIds = it.deviceIds - id) }, quickActions = s.quickActions.map { it.copy(deviceIds = it.deviceIds - id) }) } }
    fun saveGroup(group: LightGroup) {
        require(group.name.isNotBlank() && group.deviceIds.isNotEmpty()) { "Choose a group name and at least one light." }
        change(true) { s -> s.copy(groups = s.groups.filterNot { it.id == group.id } + group.copy(name = group.name.trim().take(80))) }
    }
    fun deleteGroup(id: String) = change(true) { s -> s.copy(groups = s.groups.filterNot { it.id == id }) }
    fun saveQuickAction(action: QuickAction) {
        require(action.name.isNotBlank() && action.deviceIds.isNotEmpty() && action.commands.isNotEmpty()) { "Choose a name, lights, and at least one control." }
        change(true) { s -> s.copy(quickActions = s.quickActions.filterNot { it.id == action.id } + action.copy(name = action.name.trim().take(80))) }
    }
    fun deleteQuickAction(id: String) = change(true) { s -> s.copy(quickActions = s.quickActions.filterNot { it.id == id }) }
    fun setSettings(settings: AppSettings) = change(true) { it.copy(settings = settings) }
    suspend fun setApiKey(value: String) = withContext(Dispatchers.IO) {
        keys.save(value)
        apiKey = value.trim().takeIf { it.isNotBlank() }
        change { it.copy(hasApiKey = apiKey != null, error = null) }
    }
    /** Call when UI goes to background to release the multicast lock. */
    fun pauseNetworking() { foreground.value = false; sessions.update(emptyList(), false); lan.pause(); ble.pause() }
    fun resumeNetworking() { lan.resume(); ble.resume(); foreground.value = true }
    fun close() { sessions.close(); lan.close(); ble.close(); scope.cancel() }
    private fun lockFor(id: String) = devicesMutex.getOrPut(id) { Mutex() }
}

internal fun replaceDevice(devices: List<LightDevice>, device: LightDevice): List<LightDevice> = (devices.filterNot { it.id == device.id } + device).sortedBy { it.displayName.lowercase() }
internal fun mergeCapabilities(old: List<LightCapability>, incoming: List<LightCapability>): List<LightCapability> {
    val merged = old.toMutableList()
    incoming.forEach { cap ->
        val previous = merged.firstOrNull { it.instance == cap.instance }
        merged.removeAll { it.instance == cap.instance }
        // Keep static scenes plus dynamic options; use exact advertised values and type.
        val base = previous?.staticOptions.orEmpty()
        val options = (base + cap.options).distinctBy { it.valueJson }
        val params = org.json.JSONObject(cap.parametersJson)
        if (options.isNotEmpty()) params.put("options", org.json.JSONArray(options.map { org.json.JSONObject().put("name", it.name).put("value", org.json.JSONTokener(it.valueJson).nextValue()) }))
        merged.add(cap.copy(parametersJson = params.toString(), options = options, staticOptions = base))
    }
    return merged
}
internal fun LightCommand.instance(): String = when (this) { is LightCommand.Power -> "powerSwitch"; is LightCommand.Brightness -> "brightness"; is LightCommand.Color -> "colorRgb"; is LightCommand.Temperature -> "colorTemperatureK"; is LightCommand.Capability -> instance }
internal fun matches(command: LightCommand, state: LightState): Boolean = when (command) { is LightCommand.Power -> state.power == command.on; is LightCommand.Brightness -> state.brightness == command.percent; is LightCommand.Color -> state.rgb == command.rgb && (state.temperatureKelvin == null || state.temperatureKelvin == 0); is LightCommand.Temperature -> state.temperatureKelvin == command.kelvin; is LightCommand.Capability -> false }
internal fun failure(id: String, message: String) = CommandResult(id, CommandStatus.FAILED, message)
internal fun friendly(e: Exception): String = when (e) { is java.net.SocketTimeoutException -> "The connection timed out. Check your network and retry."; is java.net.UnknownHostException -> "Could not reach Govee. Check your internet connection."; is java.net.BindException -> "LAN response port 4002 is busy. Close other LAN light controllers and retry."; is java.io.IOException -> "Network connection failed. Check Wi-Fi and retry."; else -> e.message ?: "The request failed." }
internal suspend fun attempt(block: suspend () -> Unit): String? = try { block(); null } catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) { friendly(e) }
/** Best-effort concurrent fan-out, retaining every device's error. Lights have no shared clock. */
internal suspend fun dispatchGroup(ids: Set<String>, command: LightCommand, sender: suspend (String, LightCommand) -> CommandResult): GroupResult = coroutineScope {
    val limit = Semaphore(8)
    GroupResult(ids.map { id -> async { limit.withPermit { try { sender(id, command) } catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) { failure(id, friendly(e)) } } } }.awaitAll())
}
