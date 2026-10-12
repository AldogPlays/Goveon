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
import kotlinx.coroutines.sync.withPermit
import java.util.UUID

class LightRepository(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = LocalStore(appContext)
    private val monitor = Any()
    private val mutableState = MutableStateFlow(store.load())
    val state: StateFlow<AppState> = mutableState.asStateFlow()
    private val commandLocks = CommandLocks()
    private val scanMutex = Mutex()
    private val lan = LanTransport(appContext, scope, ::discovered, ::receivedState)
    private val ble = BleTransport(appContext, scope, ::discoveredBluetooth, ::bluetoothConnection, ::bluetoothState)
    private val activeControlIds = MutableStateFlow<Set<String>>(emptySet())
    private val actionLeases = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    private val foreground = MutableStateFlow(false)
    private val sessions = BleSessionController(scope, ::connectForControls, ::disconnectForControls)
    val bluetoothSessions: StateFlow<Map<String, BleSessionStatus>> = sessions.status
    init {
        scope.launch {
            combine(activeControlIds, actionLeases, foreground, state.map { it.devices }) { pageIds, leases, visible, devices ->
                val ids = pageIds + leases.values.flatten()
                devices.filter { it.id in ids && it.bleAddress != null }.map {
                    BleSessionTarget(it.id, it.bleConnection, failureReason = ble.failureReason(it.bleAddress!!))
                } to visible
            }.distinctUntilChanged().collect { (targets, visible) -> sessions.update(targets, visible) }
        }
        scope.launch { store.removeLegacyCredentials() }
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
    private fun receivedState(ip: String, reading: LightState) = change { s -> s.copy(devices = s.devices.map { if (it.ip == ip) it.copy(state = reading) else it }) }
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
    private suspend fun establishBluetooth(id: String): CommandResult = commandLocks.withLock(id) {
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
            val error = attempt { lan.scan() }
            if (error != null) change { it.copy(error = error) }
            else dispatchGroup(state.value.devices.filter { it.ip != null }.map { it.id }.toSet(), LightCommand.Power(false)) { id, _ -> refresh(id) }
        } finally { change { it.copy(isScanning = false) }; scanMutex.unlock() }
    }

    suspend fun refresh(deviceId: String): CommandResult = commandLocks.withLock(deviceId) {
        try {
            val device = device(deviceId)
            if (device.bleConnection == BleConnection.CONNECTED && device.bleAddress != null) {
                ble.query(device.bleAddress)
                return@withLock CommandResult(deviceId, CommandStatus.ACCEPTED, "Bluetooth state requested. Readings update when the light replies.", "Bluetooth")
            }
            val ip = device.ip ?: return@withLock failure(deviceId, "Connect over Bluetooth to read this light.")
            val reply = lan.query(ip)
            if (reply == null) {
                change { s -> s.copy(devices = s.devices.map { if (it.id == deviceId) it.copy(state = it.state.copy(online = false)) else it }) }
                return@withLock failure(deviceId, "No LAN reply. Check Wi-Fi, power, and the device's LAN switch.")
            }
            CommandResult(deviceId, if (reply.hasReading) CommandStatus.CONFIRMED else CommandStatus.ACCEPTED,
                if (reply.hasReading) "State refreshed." else "Light replied without readable state values.", "LAN")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { failure(deviceId, friendly(e)) }
    }

    suspend fun send(deviceId: String, command: LightCommand): CommandResult = commandLocks.withLock(deviceId) { sendUnlocked(deviceId, command) }
    private suspend fun sendUnlocked(deviceId: String, command: LightCommand): CommandResult = try {
        val target = device(deviceId)
        if (target.bleConnection == BleConnection.CONNECTED && target.bleAddress != null && GoveeBleProtocol.supports(target.bleProfile, command.instance())) {
            ble.send(target.bleAddress, command)
            // Delivery acknowledgement is distinct from physical state confirmation.
            val refreshError = attempt { ble.query(target.bleAddress) }
            CommandResult(deviceId, CommandStatus.ACCEPTED,
                if (refreshError == null) "Bluetooth command delivered. State requested; readings update when the light replies."
                else "Bluetooth command delivered, but state refresh failed: $refreshError", "Bluetooth")
        } else {
            val ip = target.ip ?: throw BleFailure(if (target.bleConnection != BleConnection.CONNECTED)
                "Connect over Bluetooth before controlling this light." else "This control is not supported by this light's Bluetooth profile yet.")
            lanCommandResult(deviceId, command, lan.controlAndQuery(ip, command))
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (e: Exception) { failure(deviceId, friendly(e)) }
    suspend fun sendGroup(groupId: String, command: LightCommand): GroupResult {
        val group = state.value.groups.firstOrNull { it.id == groupId } ?: return GroupResult(listOf(failure(groupId, "This group is no longer saved.")))
        return dispatchGroup(group.deviceIds, command, ::send)
    }
    suspend fun runQuickAction(id: String): GroupResult {
        val action = state.value.quickActions.firstOrNull { it.id == id } ?: return GroupResult(listOf(failure(id, "This quick action is no longer saved.")))
        // Validate every retained member before any recipe sends, including changed device profiles.
        try { validateRecipe(action.deviceIds.map(::device), action.commands) }
        catch (e: Exception) { return GroupResult(action.deviceIds.map { failure(it, friendly(e)) }) }
        val bluetoothRequired = state.value.devices.filter { it.id in action.deviceIds && it.bleAddress != null && it.ip == null }.map { it.id }.toSet()
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
                commandLocks.withLock(deviceId) { executeRecipe(deviceId, action.commands, ::sendUnlocked) }
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
        require(group.deviceIds.all { id -> state.value.devices.any { it.id == id } }) { "A selected light is no longer saved." }
        change(true) { s -> s.copy(groups = s.groups.filterNot { it.id == group.id } + group.copy(name = group.name.trim().take(80))) }
    }
    fun deleteGroup(id: String) = change(true) { s -> s.copy(groups = s.groups.filterNot { it.id == id }) }
    fun saveQuickAction(action: QuickAction) {
        require(action.name.isNotBlank() && action.deviceIds.isNotEmpty() && action.commands.isNotEmpty()) { "Choose a name, lights, and at least one control." }
        validateRecipe(action.deviceIds.map(::device), action.commands)
        change(true) { s -> s.copy(quickActions = s.quickActions.filterNot { it.id == action.id } + action.copy(name = action.name.trim().take(80))) }
    }
    fun deleteQuickAction(id: String) = change(true) { s -> s.copy(quickActions = s.quickActions.filterNot { it.id == id }) }
    fun setSettings(settings: AppSettings) = change(true) { it.copy(settings = settings) }
    /** Call when UI goes to background to release the multicast lock. */
    fun pauseNetworking() { foreground.value = false; sessions.update(emptyList(), false); lan.pause(); ble.pause() }
    fun resumeNetworking() { lan.resume(); ble.resume(); foreground.value = true }
    fun close() { sessions.close(); lan.close(); ble.close(); scope.cancel() }
}

internal fun replaceDevice(devices: List<LightDevice>, device: LightDevice): List<LightDevice> = (devices.filterNot { it.id == device.id } + device).sortedBy { it.displayName.lowercase() }
internal fun LightCommand.instance(): String = when (this) { is LightCommand.Power -> "powerSwitch"; is LightCommand.Brightness -> "brightness"; is LightCommand.Color -> "colorRgb"; is LightCommand.Temperature -> "colorTemperatureK" }
internal fun matches(command: LightCommand, state: LightState): Boolean = when (command) { is LightCommand.Power -> state.power == command.on; is LightCommand.Brightness -> state.brightness == command.percent; is LightCommand.Color -> state.rgb == command.rgb && (state.temperatureKelvin == null || state.temperatureKelvin == 0); is LightCommand.Temperature -> state.temperatureKelvin == command.kelvin }
internal fun failure(id: String, message: String) = CommandResult(id, CommandStatus.FAILED, message)
internal fun friendly(e: Exception): String = when (e) { is java.net.SocketTimeoutException -> "The connection timed out. Check your network and retry."; is java.net.UnknownHostException -> "Could not reach the light. Check Wi-Fi and retry."; is java.net.BindException -> "LAN response port 4002 is busy. Close other LAN light controllers and retry."; is java.io.IOException -> "Network connection failed. Check Wi-Fi and retry."; else -> e.message ?: "The request failed." }
internal suspend fun attempt(block: suspend () -> Unit): String? = try { block(); null } catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) { friendly(e) }
/** Best-effort concurrent fan-out, retaining every device's error. Lights have no shared clock. */
internal suspend fun dispatchGroup(ids: Set<String>, command: LightCommand, sender: suspend (String, LightCommand) -> CommandResult): GroupResult = coroutineScope {
    val limit = Semaphore(8)
    GroupResult(ids.map { id -> async { limit.withPermit { try { sender(id, command) } catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) { failure(id, friendly(e)) } } } }.awaitAll())
}
