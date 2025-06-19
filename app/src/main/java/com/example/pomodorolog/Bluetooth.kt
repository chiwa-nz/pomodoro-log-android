package com.example.pomodorolog

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.toggl.komposable.architecture.ReduceResult
import com.toggl.komposable.architecture.Reducer
import com.toggl.komposable.extensions.withoutEffect
import java.util.UUID

const val logTag = "Bluetooth"

sealed class BluetoothAction {
    data class BluetoothInitialised(val manager: BluetoothManager? = null) : BluetoothAction()
    data class BluetoothDebugMessaged(val message: String) : BluetoothAction()
    data class ActivityCreated(val activity: Activity) : BluetoothAction()
    data class DeviceScanned(val name: String?, val mac: String?) : BluetoothAction()
    data class DeviceConnected(val device: Device) : BluetoothAction()
    data object DeviceDisconnected : BluetoothAction()
    data object DevicesRandomised : BluetoothAction()
    data object DevicesReset : BluetoothAction()
    data class NamedOnlyToggled(val enabled: Boolean) : BluetoothAction()
}

data class Device (
    val name: String? = "",
    val mac: String? = "",
    val gatt: BluetoothGatt? = null
)

data class BluetoothState(
    val manager: BluetoothManager? = null,
    val devices: List<Device> = listOf(),
    val connectedDevice: Device? = null,
    val message: String = "",
    val activity: Activity? = null,
    val namedOnly: Boolean = true
)

fun generateRandomDevices(
    count: Int = 10,
    nameLength: Int = 10,
    macLength: Int = 10
) : List<Device> {
    fun getRandomString(length: Int) : String {
        val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        return (1..length).map { allowedChars.random() }.joinToString("")
    }

    fun getRandomDevice(nameLength: Int = 10, macLength: Int = 10) : Device {
        return Device(getRandomString(nameLength), getRandomString(macLength))
    }

    val devices = mutableListOf<Device>()
    repeat (count) { devices.add(getRandomDevice(nameLength, macLength)) }
    return devices.toList()
}

class BluetoothReducer : Reducer<BluetoothState, BluetoothAction> {
    override fun reduce(state: BluetoothState, action: BluetoothAction): ReduceResult<BluetoothState, BluetoothAction> =
        when (action) {
            BluetoothAction.DevicesRandomised -> {
                state.copy(devices = generateRandomDevices()).withoutEffect()
            }
            is BluetoothAction.BluetoothInitialised ->
                state.copy(manager = action.manager).withoutEffect()
            is BluetoothAction.BluetoothDebugMessaged ->
                state.copy(message = action.message).withoutEffect()
            is BluetoothAction.ActivityCreated ->
                state.copy(activity = action.activity).withoutEffect()
            is BluetoothAction.DeviceScanned -> {
                val deviceFound = state.devices.find { it.mac == action.mac }
                if (deviceFound != null) {
                    state.withoutEffect()
                } else {
                    state.copy(devices = state.devices + listOf(
                        Device(
                            mac = action.mac ?: "",
                            name = action.name ?: ""
                        )
                    )).withoutEffect()
                }
            }
            is BluetoothAction.DeviceConnected ->
                state.copy(connectedDevice = action.device).withoutEffect()
            BluetoothAction.DeviceDisconnected ->
                state.copy(connectedDevice = null).withoutEffect()
            BluetoothAction.DevicesReset ->
                state.copy(message = "", devices = listOf()).withoutEffect()
            is BluetoothAction.NamedOnlyToggled ->
                state.copy(namedOnly = action.enabled).withoutEffect()
        }
}

fun checkBluetoothPermissions(activity: Activity?) : Boolean {
    if(activity == null) return false

    fun getRequiredPermissions(
        permissionsToCheck: List<String>
    ): MutableList<String> {
        val requiredPermissions = mutableListOf<String>()
        permissionsToCheck.forEach {
            if (ContextCompat.checkSelfPermission(activity, it) !=
                PackageManager.PERMISSION_GRANTED) requiredPermissions.add(it)
        }
        return requiredPermissions
    }

    val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) permissions += listOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT
    )
    val requiredPermissions = getRequiredPermissions(permissions.toList())
    if(requiredPermissions.isEmpty()) return true
    ActivityCompat.requestPermissions(activity, requiredPermissions.toTypedArray(), 1)
    return false
}

fun debugMessage(message: String) {
    bluetoothStore.send(BluetoothAction.BluetoothDebugMessaged(message))
}

fun initBluetooth(
    state: BluetoothState,
    context: Context? = null
) {
    if (context == null) {
        Log.d(logTag, "context is null, cancelling")
        return
    }
    if (state.manager != null) return debugMessage("Bluetooth already initialised.")
    val bluetoothManager: BluetoothManager = context.getSystemService(BluetoothManager::class.java)
    bluetoothStore.send(BluetoothAction.BluetoothInitialised(bluetoothManager))
    debugMessage("Bluetooth initialised successfully.")
}

private val scanCallback = object : ScanCallback() {
    @SuppressLint("MissingPermission")
    override fun onScanResult(callbackType: Int, result: ScanResult?) {
        super.onScanResult(callbackType, result)

        if (result == null) return

        val parcelUuids = result.scanRecord?.serviceUuids
        parcelUuids?.forEach { Log.d(logTag, "onScanResult() ${result.device.name} ${it.uuid}") }

        bluetoothStore.send(BluetoothAction.DeviceScanned(
            name = result.device.name,
            mac = result.device.address,
        ))
    }

    override fun onScanFailed(errorCode: Int) {
        super.onScanFailed(errorCode)

        bluetoothStore.send(BluetoothAction.BluetoothDebugMessaged(
            message = "Scanning failed"
        ))
    }
}

@RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
fun scanDevices(
    state: BluetoothState,
    context: Context? = null,
    scanPeriod: Long = 5000,
) {
    if (context == null) return
    if (state.manager == null) {
        debugMessage("Failed to scan - Bluetooth not initialised.")
        return
    }
    debugMessage("Scanning for devices...")
    val scanner = state.manager.adapter.bluetoothLeScanner
    scanner.startScan(scanCallback)
    Handler(Looper.getMainLooper()).postDelayed({
        debugMessage("Scanning complete.")
        scanner.stopScan(scanCallback)
    }, scanPeriod)
}

private val gattCallback = object : BluetoothGattCallback() {
    @SuppressLint("MissingPermission")
    override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
        super.onConnectionStateChange(gatt, status, newState)

        val device = Device(
            name = gatt?.device?.name,
            mac = gatt?.device?.address,
            gatt = gatt
        )

        when(newState){
            BluetoothProfile.STATE_CONNECTED -> {
                bluetoothStore.send(BluetoothAction.DeviceConnected(device))
                debugMessage("Successfully connected to ${device.name}")
                gatt?.discoverServices()
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                bluetoothStore.send(BluetoothAction.DeviceDisconnected)
                debugMessage("Successfully disconnected from ${device.name}")
            }
            BluetoothProfile.STATE_CONNECTING -> {
                debugMessage("Connecting to ${device.name}")
            }
            BluetoothProfile.STATE_DISCONNECTING -> {
                debugMessage("Disconnecting from ${device.name}")
            }
            else -> {
                TODO()
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
        super.onServicesDiscovered(gatt, status)

        val bentoUUID = "00001ae1-0000-1000-8000-00805f9b34fb"
        val characteristicUUID = "00002ce1-0000-1000-8000-00805f9b34fb"

        if (status != BluetoothGatt.GATT_SUCCESS || gatt == null) return

        for (service in gatt.services) {
            val serviceUUID = service.uuid.toString()
            if (serviceUUID != bentoUUID) continue

            val characteristic = service.getCharacteristic(UUID.fromString(characteristicUUID))
            if (characteristic == null) continue

            gatt.setCharacteristicNotification(characteristic, true)
        }
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        if (value.contentEquals(byteArrayOf(1))) {
            // primary button has been pressed
            timerStore.send(TimerAction.MainButtonTapped)
        } else if (value.contentEquals(byteArrayOf(2))) {
            // F1 button has been pressed
            timerStore.send(TimerAction.ResetButtonTapped)
        } else if (value.contentEquals(byteArrayOf(4))) {
            timerStore.send(TimerAction.LoopingToggled)
            return
        } else if (value.contentEquals(byteArrayOf(8))) {
            timerStore.send(TimerAction.AnimationToggled)
            return
        } else if (value.contentEquals(byteArrayOf(0))) {
            // any button has been released
            return
        }
    }
}

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
fun disconnectDevice(
    state: BluetoothState
) {
    try {
        state.connectedDevice?.gatt?.disconnect()
    } catch (e: Exception) {
        debugMessage("Failed to disconnect from ${state.connectedDevice?.name}, error: $e")
    }
}

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
fun connectToDevice(
    state: BluetoothState,
    device: Device
) {
    if (device.name == "") {
        debugMessage("Cannot connect to specified device")
        return
    }
    try {
        state.manager?.adapter?.getRemoteDevice(device.mac)?.connectGatt(
            state.activity,
            true,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    } catch (e: Exception) {
        debugMessage("Failed to connect to ${device.name}, error: $e")
    }
}

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
@Composable
fun BluetoothDeviceCard(
    state: BluetoothState,
    device: Device,
    index: Int,
    isConnected: Boolean
) {
    Spacer(modifier = Modifier.size(16.dp))
    Card (
        modifier = Modifier
            .fillMaxWidth()
    ) {
        Row (
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "#${index + 1}",
                modifier = Modifier
                    .padding(16.dp)
            )
            Column (
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text("Name: ${device.name}")
                Text("Mac: ${device.mac}")
                Text(if (isConnected) "Connected" else "Not connected")
            }
            Button (
                modifier = Modifier
                    .padding(end = 16.dp),
                onClick = {
                    if (isConnected) disconnectDevice(state)
                    else connectToDevice(state, device)
                },
                content = {
                    Text(if (isConnected) "Disconnect" else "Connect")
                }
            )
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun BluetoothMain (
    state: BluetoothState,
    modifier: Modifier = Modifier,
    context: Context? = null
) {
    checkBluetoothPermissions(state.activity)
    val deviceList = state.devices.filter { it.name != if (state.namedOnly) "" else null }
    Column (
        modifier = modifier
    ){
        Text("Debug message: ${state.message}")
        Text("Debug devices.size: ${deviceList.size}")
        Text("Bluetooth Initialised? ${state.manager != null}")
        Row (
            horizontalArrangement = Arrangement.SpaceBetween
        ){
            Column {
                Text("Device name: ${state.connectedDevice?.name}")
                Text("Device mac: ${state.connectedDevice?.mac}")
            }
            if (state.connectedDevice != null) {
                Button(
                    modifier = Modifier.padding(start = 8.dp),
                    onClick = { disconnectDevice(state) }
                ) {
                    Text("Clear")
                }
            }
        }
        Row {
            if (state.manager == null) {
                Button(
                    onClick = { initBluetooth(
                        state = state,
                        context = context
                    ) }
                ) {
                    Text("Initialise Bluetooth")
                }
            } else {
                Button(
                    modifier = Modifier.padding(start = 8.dp),
                    onClick = { scanDevices(
                        context = context,
                        state = state
                    ) }
                ) {
                    Text("Scan")
                }
                Button(
                    modifier = Modifier.padding(start = 8.dp),
                    onClick = {
                        state.manager.adapter.bluetoothLeScanner?.stopScan(scanCallback)
                        bluetoothStore.send(BluetoothAction.DevicesReset)
                    }
                ) {
                    Text("Reset")
                }
                Button(
                    modifier = Modifier.padding(start = 8.dp),
                    onClick = { bluetoothStore.send(BluetoothAction.DevicesRandomised) }
                ) {
                    Text("Random Devices")
                }
            }
        }
        Row (
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Named devices only")
            Switch(
                modifier = Modifier
                    .padding(start = 8.dp),
                checked = state.namedOnly,
                onCheckedChange = {
                    bluetoothStore.send(BluetoothAction.NamedOnlyToggled(it))
                }
            )
        }
        LazyColumn(modifier = Modifier.padding(start = 6.dp)) {
            for ((index, device) in deviceList.withIndex()) {
                item(key = device.mac) {
                    BluetoothDeviceCard(
                        state = state,
                        device = device,
                        index = index,
                        isConnected = device.mac == state.connectedDevice?.mac
                    )
                }
            }
        }
    }
}