package com.goveon.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** Android prompts happen only after a user taps a discovery or connection action. */
@Composable
@SuppressLint("MissingPermission") // Every adapter operation follows the explicit runtime grant check.
internal fun rememberBluetoothAccess(): (() -> Unit) -> Unit {
    val context = LocalContext.current
    val adapter = remember(context) { context.getSystemService(BluetoothManager::class.java)?.adapter }
    val permissions = remember {
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var permissionDenied by remember { mutableStateOf(false) }
    var locationNeeded by remember { mutableStateOf(false) }

    fun permitted() = permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    fun finish() {
        if (!permitted()) return
        val location = context.getSystemService(LocationManager::class.java)
        val hasLocation = Build.VERSION.SDK_INT >= 31 || if (Build.VERSION.SDK_INT >= 28) location?.isLocationEnabled == true
        else location?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true || location?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
        if (!hasLocation) { locationNeeded = true; return }
        val action = pending
        pending = null
        // Activity-result callbacks can arrive during onStart, before foreground transports resume.
        action?.let { Handler(Looper.getMainLooper()).post { it() } }
    }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Opening Settings does not imply the user changed anything; check again on return.
        finish()
    }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && permitted() && adapter?.isEnabled == true) finish()
        else { pending = null; message = "Bluetooth is off. Tap Discover again when you want to enable it." }
    }
    fun checkBluetooth() {
        if (adapter == null) { pending = null; message = "This device does not have a Bluetooth adapter. You can still discover supported Wi-Fi lights."; return }
        try {
            if (!adapter.isEnabled) enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            else finish()
        } catch (e: SecurityException) { permissionDenied = true }
        catch (e: Exception) { pending = null; message = "Android could not open Bluetooth setup. Enable Bluetooth in Settings and try Discover again." }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (permitted()) checkBluetooth() else { permissionDenied = true }
    }
    val gate: (() -> Unit) -> Unit = { action ->
        pending = action
        if (!permitted()) permissionLauncher.launch(permissions) else checkBluetooth()
    }

    if (permissionDenied) AlertDialog(onDismissRequest = { permissionDenied = false; pending = null },
        title = { Text(if (Build.VERSION.SDK_INT >= 31) "Allow nearby devices" else "Allow Bluetooth discovery") },
        text = { Text(if (Build.VERSION.SDK_INT >= 31) "Android needs Nearby devices permission to find and connect to your lights. Enable it in Goveon's app permissions, then tap Discover again."
            else "Android 8–11 requires Location permission for Bluetooth discovery. Goveon does not collect your location. Enable the permission in app settings, then tap Discover again.") },
        confirmButton = { TextButton(onClick = {
            permissionDenied = false; pending = null
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }) { Text("App settings") } },
        dismissButton = { TextButton(onClick = { permissionDenied = false; pending = null }) { Text("Cancel") } })
    if (locationNeeded) AlertDialog(onDismissRequest = { locationNeeded = false; pending = null }, title = { Text("Enable Location services") },
        text = { Text("Android 8–11 requires the phone's Location switch to be on for Bluetooth scanning. Goveon uses it only to discover lights and does not collect your location.") },
        confirmButton = { TextButton(onClick = { locationNeeded = false; locationLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) { Text("Location settings") } },
        dismissButton = { TextButton(onClick = { locationNeeded = false; pending = null }) { Text("Cancel") } })
    message?.let { text -> AlertDialog(onDismissRequest = { message = null }, title = { Text("Bluetooth discovery") }, text = { Text(text) },
        confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } }) }
    return gate
}
