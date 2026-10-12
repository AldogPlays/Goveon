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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

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
    private val cloud = CloudTransport { apiKey }
    init {
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
    private fun device(id: String) = state.value.devices.firstOrNull { it.id == id } ?: error("This device is no longer saved.")
    fun clearError() = change { it.copy(error = null) }

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
                                list = replaceDevice(list, new.copy(ip = old?.ip, customName = old?.customName, state = old?.state ?: LightState()))
                            }
                            s.copy(devices = list)
                        }
                    } else null
                }
                local.await()?.let { failures.add("LAN: $it") }
                remote.await()?.let { failures.add("Cloud: $it") }
            }
            val ids = state.value.devices.map { it.id }
            dispatchGroup(ids.toSet(), LightCommand.Power(false)) { id, _ -> refresh(id) }
            if (failures.isNotEmpty()) change { it.copy(error = failures.joinToString("\n")) }
        } finally { change { it.copy(isScanning = false) }; scanMutex.unlock() }
    }

    suspend fun refresh(deviceId: String): CommandResult = lockFor(deviceId).withLock {
        try {
            val device = device(deviceId)
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
            require(device.cloudAvailable) { "This device is only available through LAN." }
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
        return dispatchGroup(action.deviceIds, LightCommand.Power(false)) { deviceId, _ ->
            var result = failure(deviceId, "This quick action has no commands.")
            for (command in action.commands) { result = send(deviceId, command); if (!result.successful) break }
            result
        }
    }
    fun renameDevice(id: String, name: String) = change(true) { s -> s.copy(devices = s.devices.map { if (it.id == id) it.copy(customName = name.trim().take(80).takeIf { it.isNotBlank() }) else it }) }
    fun forgetDevice(id: String) = change(true) { s -> s.copy(devices = s.devices.filterNot { it.id == id }, groups = s.groups.map { it.copy(deviceIds = it.deviceIds - id) }, quickActions = s.quickActions.map { it.copy(deviceIds = it.deviceIds - id) }) }
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
    fun pauseNetworking() = lan.pause()
    fun resumeNetworking() = lan.resume()
    fun close() { lan.close(); scope.cancel() }
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
