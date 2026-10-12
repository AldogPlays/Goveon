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
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import java.util.UUID

class GoveonViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = LightRepository(application)
    val state = repository.state
    private val active = MutableStateFlow<Set<String>>(emptySet())
    val busy = active.asStateFlow()
    private val notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = notices.asSharedFlow()
    private val report = MutableStateFlow<GroupResult?>(null)
    val failureReport = report.asStateFlow()
    fun dismissReport() { report.value = null }
    private val responses = MutableStateFlow<Map<String, CommandResult>>(emptyMap())
    val lastResponses = responses.asStateFlow()

    private fun work(key: String, block: suspend () -> Unit) {
        if (key in active.value) return
        active.update { it + key }
        // Recipe/catalog serialization and preference edits also belong off the UI thread.
        viewModelScope.launch(Dispatchers.IO) {
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                notices.emit(e.message ?: "That action could not be completed.")
            } finally { active.update { it - key } }
        }
    }
    fun scan() = work("scan") { repository.scan() }
    fun discoverBluetooth() = work("bluetooth-scan") {
        val count = repository.discoverBluetooth()
        if (state.value.error == null) notices.emit(if (count == 0) "No Govee Bluetooth advertisements found. Power on the light, move closer, and close other active light connections."
            else "$count nearby Bluetooth devices found. Connect to identify supported controls.")
    }
    fun connectBluetooth(id: String) = work("bluetooth:$id") {
        val result = repository.connectBluetooth(id)
        responses.update { it + (id to result) }
        notices.emit(result.message)
    }
    fun disconnectBluetooth(id: String) = work("bluetooth:$id") {
        repository.disconnectBluetooth(id)
        notices.emit("Bluetooth connection closed. The light remains saved.")
    }
    fun connectBluetoothGroup(groupId: String) = work("group-connect:$groupId") {
        val group = state.value.groups.firstOrNull { it.id == groupId } ?: error("This group is no longer saved.")
        val devices = state.value.devices.filter { it.id in group.deviceIds && it.bleAddress != null && it.bleConnection != BleConnection.CONNECTED }
        if (devices.isEmpty()) { notices.emit("The Bluetooth members are already connected."); return@work }
        // Establish GATT links one at a time; group commands fan out to established sessions.
        val results = devices.map { device ->
            repository.connectBluetooth(device.id).also { result -> responses.update { it + (device.id to result) } }
        }
        val result = GroupResult(results)
        if (result.failures.isNotEmpty()) report.value = result
        notices.emit("Connected ${results.count { it.successful }}/${results.size} Bluetooth members.${if (result.failures.isNotEmpty()) " Review the results." else ""}")
    }
    fun refresh(id: String) = work(id) {
        val result = repository.refresh(id)
        responses.update { it + (id to result) }
        if (!result.successful) notices.emit(result.message)
    }
    fun scenes(id: String) = work("scenes:$id") {
        val result = repository.loadScenes(id)
        if (!result.successful) responses.update { it + (id to result) }
        if (!result.successful) notices.emit(result.message)
    }
    fun pauseNetworking() = repository.pauseNetworking()
    fun resumeNetworking() = repository.resumeNetworking()
    override fun onCleared() { repository.close(); super.onCleared() }
    fun send(id: String, command: LightCommand) = work(id) {
        val result = repository.send(id, command)
        responses.update { it + (id to result) }
        notices.emit(result.message)
    }
    fun sendGroup(id: String, command: LightCommand) = work("group:$id") {
        val result = repository.sendGroup(id, command)
        val accepted = result.results.count { it.successful }
        val confirmed = result.results.count { it.status == CommandStatus.CONFIRMED }
        if (result.failures.isNotEmpty()) report.value = result
        notices.emit(if (result.failures.isEmpty()) {
            if (confirmed == accepted) "$confirmed lights reported the requested state."
            else "$accepted lights accepted the command; $confirmed confirmed state. Refresh to check the others."
        }
            else "$accepted/${result.results.size} lights accepted. Review the failures.")
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
        val result = repository.runQuickAction(id)
        if (result.failures.isNotEmpty()) report.value = result
        notices.emit(if (result.failures.isEmpty()) "Quick action accepted by ${result.results.size} lights."
            else "Some commands failed. Review the results.")
    }
    fun settings(settings: AppSettings) = work("settings") { repository.setSettings(settings) }
    fun apiKey(key: String) = work("api-key") {
        repository.setApiKey(key.trim())
        notices.emit(if (key.isBlank()) "API key removed." else "API key saved securely. Discover to load cloud devices.")
    }
}
