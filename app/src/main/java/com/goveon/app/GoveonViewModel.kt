package com.goveon.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.goveon.app.lighting.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import java.util.UUID

class GoveonViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = LightRepository(application)
    val state = repository.state
    val bluetoothSessions = repository.bluetoothSessions
    fun setActiveControls(ids: Set<String>) = repository.setActiveControls(ids)
    fun retryBluetoothControls() = repository.retryBluetoothControls()
    private val active = MutableStateFlow<Set<String>>(emptySet())
    val busy = active.asStateFlow()
    private val notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = notices.asSharedFlow()
    private val report = MutableStateFlow<GroupResult?>(null)
    val failureReport = report.asStateFlow()
    fun dismissReport() { report.value = null }
    private val responses = MutableStateFlow<Map<String, CommandResult>>(emptyMap())
    val lastResponses = responses.asStateFlow()
    init {
        viewModelScope.launch {
            bluetoothSessions.map { statuses -> statuses.filterValues { it.phase == BleSessionPhase.CONNECTING }.keys }
                .distinctUntilChanged().collect { ids ->
                    responses.update { previous -> previous.filterNot { (id, result) -> id in ids && result.status == CommandStatus.FAILED } }
                }
        }
        viewModelScope.launch {
            state.map { it.error }.distinctUntilChanged().filterNotNull().collect { Diagnostics.detail("Discovery", it) }
        }
    }

    private fun work(key: String, block: suspend () -> Unit) {
        if (key in active.value) return
        active.update { it + key }
        // Recipe/catalog serialization and preference edits also belong off the UI thread.
        viewModelScope.launch(Dispatchers.IO) {
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                Diagnostics.failure("Action", e)
                notices.emit("That action couldn't finish. Check the connection and try again.")
            } finally { active.update { it - key } }
        }
    }
    fun scan() = work("scan") { repository.scan() }
    fun discoverBluetooth() = work("bluetooth-scan") {
        val count = repository.discoverBluetooth()
        if (state.value.error == null) notices.emit(if (count == 0) "No lights found. Move closer and try again."
            else "$count nearby ${if (count == 1) "light" else "lights"} found.")
    }
    fun connectBluetooth(id: String) = work("bluetooth:$id") {
        val result = repository.connectBluetooth(id)
        responses.update { it + (id to result) }
        Diagnostics.command(result)
    }
    fun disconnectBluetooth(id: String) = work("bluetooth:$id") {
        repository.disconnectBluetooth(id)
    }
    fun connectBluetoothGroup(groupId: String) = work("group-connect:$groupId") {
        val group = state.value.groups.firstOrNull { it.id == groupId } ?: error("This group is no longer saved.")
        val devices = state.value.devices.filter { it.id in group.deviceIds && it.bleAddress != null && it.bleConnection != BleConnection.CONNECTED }
        if (devices.isEmpty()) return@work
        // Establish GATT links one at a time; group commands fan out to established sessions.
        val results = devices.map { device ->
            repository.connectBluetooth(device.id).also { result -> responses.update { it + (device.id to result) }; Diagnostics.command(result) }
        }
        val result = GroupResult(results)
        report.value = result.takeIf { it.failures.isNotEmpty() }
    }
    fun refresh(id: String) = work(id) {
        val result = repository.refresh(id)
        responses.update { it + (id to result) }
        Diagnostics.command(result)
    }
    fun scenes(id: String) = work("scenes:$id") {
        val result = repository.loadScenes(id)
        if (!result.successful) responses.update { it + (id to result) }
        Diagnostics.command(result)
    }
    fun pauseNetworking() = repository.pauseNetworking()
    fun resumeNetworking() = repository.resumeNetworking()
    override fun onCleared() { repository.close(); super.onCleared() }
    fun send(id: String, command: LightCommand) = work(id) {
        val result = repository.send(id, command)
        responses.update { it + (id to result) }
        Diagnostics.command(result)
    }
    fun sendGroup(id: String, command: LightCommand) = work("group:$id") {
        report.value = null
        val result = repository.sendGroup(id, command)
        responses.update { it + result.results.associateBy(CommandResult::deviceId) }
        result.results.forEach(Diagnostics::command)
        report.value = result.takeIf { it.failures.isNotEmpty() }
    }
    fun rename(id: String, name: String) = work("edit:$id") { repository.renameDevice(id, name) }
    fun bluetoothModel(id: String, model: String) = work("edit:$id") { repository.setBluetoothModel(id, model) }
    fun forget(id: String) = work("edit:$id") { repository.forgetDevice(id) }
    fun saveGroup(id: String?, name: String, targets: Set<String>) = work("save-group") {
        repository.saveGroup(LightGroup(id ?: UUID.randomUUID().toString(), name.trim(), targets))
    }
    fun deleteGroup(id: String) = work("delete-group") { repository.deleteGroup(id) }
    fun saveAction(id: String?, name: String, targets: Set<String>, commands: List<LightCommand>) = work("save-action") {
        repository.saveQuickAction(QuickAction(id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(), name.trim(), targets, commands))
    }
    fun deleteAction(id: String) = work("delete-action") { repository.deleteQuickAction(id) }
    fun runAction(id: String) = work("action:$id") {
        report.value = null
        val result = repository.runQuickAction(id)
        responses.update { it + result.results.associateBy(CommandResult::deviceId) }
        result.results.forEach(Diagnostics::command)
        report.value = result.takeIf { it.failures.isNotEmpty() }
        if (result.failures.isEmpty()) notices.emit("Quick action sent.")
    }
    fun settings(settings: AppSettings) = work("settings") { repository.setSettings(settings) }
    fun apiKey(key: String) = work("api-key") {
        repository.setApiKey(key.trim())
        notices.emit(if (key.isBlank()) "API key removed." else "API key saved.")
    }
}
