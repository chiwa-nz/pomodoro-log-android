package com.example.pomodorolog

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
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


sealed class BluetoothAction {
    data class BluetoothInitialised(
        val manager: BluetoothManager? = null,
        val adapter: BluetoothAdapter? = null,
        val scanner: BluetoothLeScanner? = null,
    ) : BluetoothAction()
    data class BluetoothDebugMessaged(
        val message: String
    ) : BluetoothAction()
    data class ActivityCreated(
        val activity: Activity
    ) : BluetoothAction()
    data class DeviceScanned(
        val name: String?,
        val mac: String?
    ) : BluetoothAction()
    data class DeviceConnected(
        val device: Device
    ) : BluetoothAction()
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
    val adapter: BluetoothAdapter? = null,
    val scanner: BluetoothLeScanner? = null,
    val devices: List<Device> = listOf(),
    val connectedDevice: Device? = null,
    val message: String = "",
    val activity: Activity? = null,
    val namedOnly: Boolean = true
)

fun getRandomString(length: Int) : String {
    val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
    return (1..length)
        .map { allowedChars.random() }
        .joinToString("")
}

fun getRandomDevice(
    nameLength: Int = 10,
    macLength: Int = 10
) : Device {
    return Device(
        name = getRandomString(nameLength),
        mac = getRandomString(macLength),
    )
}

fun generateRandomDevices(
    count: Int = 10,
    nameLength: Int = 10,
    macLength: Int = 10
) : List<Device> {
    val devices = mutableListOf<Device>()
    repeat (count) {
        devices.add(getRandomDevice(nameLength, macLength))
    }
    return devices.toList()
}

class BluetoothReducer : Reducer<BluetoothState, BluetoothAction> {
    override fun reduce(state: BluetoothState, action: BluetoothAction): ReduceResult<BluetoothState, BluetoothAction> =
        when (action) {
            BluetoothAction.DevicesRandomised -> {
                state.copy(devices = generateRandomDevices()).withoutEffect()
            }
            is BluetoothAction.BluetoothInitialised ->
                state.copy(
                    manager = action.manager,
                    adapter = action.adapter,
                    scanner = action.scanner,
                ).withoutEffect()
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

fun checkBlePermissionGranted(
    activity: Activity?
) : Boolean{
    if(activity == null) return false

    val requestPermissions = mutableListOf<String>()

    if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S){
        if(ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_SCAN)!= PackageManager.PERMISSION_GRANTED){
            requestPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if(ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT)!= PackageManager.PERMISSION_GRANTED){
            requestPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    if(ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED){
        requestPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    if(requestPermissions.isNotEmpty()){
        ActivityCompat.requestPermissions(activity, requestPermissions.toTypedArray(), 1)
        return false
    }

    return true
}


const val logTag = "Bluetooth"

fun debugMessage(message: String) {
    bluetoothStore.send(
        BluetoothAction.BluetoothDebugMessaged(
            message = message
        )
    )
}

fun initBluetooth(
    context: Context? = null,
    pm: PackageManager? = null
) {
    Log.d(logTag, "scanBluetooth() called")
    if (context == null) {
        Log.d(logTag, "context is null, cancelling")
        return
    }
    val bluetoothManager: BluetoothManager =
        context.getSystemService(BluetoothManager::class.java)
    val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    val bluetoothLeScanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner
    if (bluetoothAdapter == null) {
        Log.d(logTag, "Failed to enable Bluetooth.")
        return
    }
    Log.d(logTag, "Bluetooth enabled successfully.")

    bluetoothStore.send(
        BluetoothAction.BluetoothInitialised(
            manager = bluetoothManager,
            adapter = bluetoothAdapter,
            scanner = bluetoothLeScanner,
        )
    )

    Log.d(logTag, "Bluetooth initialised successfully.")
    debugMessage("Bluetooth initialised successfully.")

    if (pm != null) {
        val bluetoothAvailable = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
        Log.d(logTag, "Bluetooth availability: $bluetoothAvailable.")

        val bluetoothLEAvailable = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        Log.d(logTag, "BLE availability: $bluetoothLEAvailable.")
    }
}

private val scanCallback = object : ScanCallback() {
    @SuppressLint("MissingPermission")
    override fun onScanResult(callbackType: Int, result: ScanResult?) {
        super.onScanResult(callbackType, result)

//        Log.d(logTag, "onScanResult() called, result = $result")

        if (result == null){
            return
        }

        val parcelUuids = result.scanRecord?.serviceUuids;
        parcelUuids?.forEach {
            Log.d(logTag, "onScanResult() ${result.device.name} ${it.uuid}")
        }

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
    scanPeriod: Long = 1000,
) {
    if (context == null) return
    if (state.manager == null || state.scanner == null ) {
        debugMessage("Failed to scan - Bluetooth not initialised.")
        return
    }
    val handler = Handler(Looper.getMainLooper())
    debugMessage("Scanning for devices...")

    fun stopScan() {
        state.scanner.stopScan(scanCallback)
    }

    state.scanner.startScan(scanCallback)
    // stops scanning after scanPeriod millis
    handler.postDelayed({ stopScan() }, scanPeriod)
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
            }
            BluetoothProfile.STATE_CONNECTING -> {
                TODO()
            }
            BluetoothProfile.STATE_DISCONNECTING -> {
                TODO()
            }
            else -> {
                TODO()
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
        super.onServicesDiscovered(gatt, status)

        val bentoUuid = "0000180f-0000-1000-8000-00805f9b34fb"
        val uuids = arrayOf(
            "0000ffa8-0000-1000-8000-00805f9b34fb",
            "0000FFE1-0000-1000-8000-00805F9B34FB",
            "b128cc10-e5fb-42ec-aebc-3d6673fbfb01",
            "00002ce1-0000-1000-8000-00805f9b34fb",
            "b283dc64-04fd-11ee-be56-0242ac120002",
            "EF01CDAA-D46E-D46E-0128-1616D66ED46E",
            "0000fef1-0000-1000-8000-00805f9b34fb"
        )
        Log.d(logTag, "onServicesDiscovered() 1, ${gatt?.services}, $bentoUuid")

        if (status == BluetoothGatt.GATT_SUCCESS && gatt != null) {
            for (service in gatt.services) {
                val serviceUUID = service.uuid.toString()
                Log.d(logTag, "onServicesDiscovered() 2, $serviceUUID, $bentoUuid")
                if (serviceUUID == bentoUuid) {
                    Log.d(logTag, "onServicesDiscovered() 3")
                    for (characteristicUUID in uuids){
                        Log.d(logTag, "onServicesDiscovered() 4 $characteristicUUID")
                        val characteristic = service.getCharacteristic(UUID.fromString(characteristicUUID))
                        if (characteristic != null) {
                            Log.d(logTag, "onServicesDiscovered() 5 $characteristicUUID")
                            gatt.setCharacteristicNotification(characteristic, true)
                        }
                    }
                }
            }
        } else {
            Log.d(logTag, "hi")
        }
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        Log.d(logTag, "onCharacteristicChanged() value: ${value.toString()}")
        Log.d(logTag, "onCharacteristicChanged() characteristic: ${characteristic.toString()}")
    }
}

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
fun disconnectDevice(
    state: BluetoothState
) {
    debugMessage("Disconnecting from ${state.connectedDevice?.name}")
    try {
        state.connectedDevice?.gatt?.disconnect()
        debugMessage("Successfully disconnected from ${state.connectedDevice?.name}")
    } catch (e: Exception) {
        debugMessage("Failed to disconnect from ${state.connectedDevice?.name}")
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
    debugMessage("Connecting to ${device.name}")
    try {
        state.adapter?.getRemoteDevice(device.mac)?.connectGatt(
            state.activity,
            true,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    } catch (e: Exception) {
        debugMessage("Failed to connect to ${device.name}")
    }
}

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
@Composable
fun BluetoothDevice(
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
    context: Context? = null,
    pm: PackageManager? = null
) {
    if (state.activity != null) checkBlePermissionGranted(state.activity)
    val deviceList =
        if (state.namedOnly) state.devices.filter { it.name != "" }
        else state.devices
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
            Button(
                onClick = { initBluetooth(
                    context = context,
                    pm = pm,
                ) }
            ) {
                Text("Init")
            }
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
                    state.scanner?.stopScan(scanCallback)
                    bluetoothStore.send(BluetoothAction.DevicesReset)
                }
            ) {
                Text("Reset")
            }
            Button(
                modifier = Modifier.padding(start = 8.dp),
                onClick = { bluetoothStore.send(BluetoothAction.DevicesRandomised) }
            ) {
                Text("Test")
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
                    BluetoothDevice(
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