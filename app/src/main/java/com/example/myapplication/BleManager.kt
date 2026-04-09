package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class BleManager(
    private val context: Context,
    private val myId: String,
    private val myPseudo: String,
    private val myGroupId: String,
    private val onBleMessageReceived: (MeshPacket, String) -> Unit,
    private val onBlePeerDiscovered: (String, String, String, Int) -> Unit
) {
    private val TAG = "BleManager"
    private val gson = Gson()
    private val SERVICE_UUID = UUID.fromString("12345678-1234-1234-1234-123456789abc")
    private val CHARACTERISTIC_UUID = UUID.fromString("abcdef01-2345-6789-abcd-ef0123456789")

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private var bluetoothGattServer: BluetoothGattServer? = null

    private val connectedDevices = ConcurrentHashMap<String, BluetoothGatt>()
    private val serverConnections = ConcurrentHashMap<String, BluetoothDevice>()
    private var scanCallback: ScanCallback? = null
    private val executor = Executors.newSingleThreadExecutor()

    // --- CALLBACKS ---
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.i(TAG, "✅ BLE Advertising réussi")
        }
        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "❌ BLE Advertising échec: $errorCode")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                serverConnections[device.address] = device
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                serverConnections.remove(device.address)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, characteristic.value)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "🔗 BLE Client connecté à ${gatt.device.address}")
                connectedDevices[gatt.device.address] = gatt
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedDevices.remove(gatt.device.address)
                gatt.close()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
                if (characteristic != null) {
                    gatt.setCharacteristicNotification(characteristic, true)
                    val descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                    descriptor?.let {
                        it.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                        gatt.writeDescriptor(it)
                    }
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            characteristic.value?.let { data ->
                try {
                    val packet = gson.fromJson(String(data), MeshPacket::class.java)
                    onBleMessageReceived(packet, gatt.device.address)
                } catch (e: Exception) {
                    Log.e(TAG, "Erreur parsing BLE packet", e)
                }
            }
        }
    }

    // --- INITIALISATION ---
    init {
        setupBluetooth()
    }

    private fun setupBluetooth() {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Log.e(TAG, "BLE non supporté")
            return
        }
        bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter != null && bluetoothAdapter!!.isEnabled) {
            bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
            bluetoothLeAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser

            if (hasBlePermissions()) {
                startAdvertising()
                startScan()
                startGattServer()
            }
        } else {
            Log.e(TAG, "Bluetooth est désactivé")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        val advertiser = bluetoothLeAdvertiser ?: return
        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceData(ParcelUuid(SERVICE_UUID), "$myId|$myPseudo|$myGroupId".toByteArray())
            .build()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        advertiser.startAdvertising(settings, advertiseData, advertiseCallback)
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val scanner = bluetoothLeScanner ?: return
        val scanFilter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val scanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.let {
                    val device = it.device
                    val data = it.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID))
                    if (data != null) {
                        val parts = String(data).split("|")
                        if (parts.size >= 3 && parts[0] != myId) {
                            onBlePeerDiscovered(device.address, parts[1], parts[2], it.rssi)
                            connectToDevice(device)
                        }
                    }
                }
            }
        }
        scanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        if (connectedDevices.containsKey(device.address)) return
        device.connectGatt(context, false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    fun startGattServer() {
        val server = bluetoothManager?.openGattServer(context, gattServerCallback) ?: return
        bluetoothGattServer = server

        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val characteristic = BluetoothGattCharacteristic(
            CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or
                    BluetoothGattCharacteristic.PROPERTY_INDICATE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(characteristic)
        server.addService(service)
    }

    /**
     * Envoie un paquet à un appareil BLE spécifique (soit en tant que client,
     * soit en tant que serveur).
     */
    @SuppressLint("MissingPermission")
    fun sendPacket(packet: MeshPacket, targetDeviceAddress: String) {
        executor.execute {
            val data = gson.toJson(packet).toByteArray()
            connectedDevices[targetDeviceAddress]?.let { gatt ->
                val char = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID)
                char?.let {
                    it.value = data
                    gatt.writeCharacteristic(it)
                }
            } ?: serverConnections[targetDeviceAddress]?.let { device ->
                val char = bluetoothGattServer?.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID)
                char?.let {
                    it.value = data
                    bluetoothGattServer?.notifyCharacteristicChanged(device, it, false)
                }
            }
        }
    }

    /**
     * Diffusion à tous les appareils BLE actuellement connectés (client + serveur).
     * Utile pour les broadcasts "TOUS".
     */
    @SuppressLint("MissingPermission")
    fun broadcastToAll(packet: MeshPacket) {
        val data = gson.toJson(packet).toByteArray()
        // Clients
        connectedDevices.values.forEach { gatt ->
            val char = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID)
            char?.let {
                it.value = data
                gatt.writeCharacteristic(it)
            }
        }
        // Serveur (appareils connectés à nous)
        serverConnections.values.forEach { device ->
            val char = bluetoothGattServer?.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID)
            char?.let {
                it.value = data
                bluetoothGattServer?.notifyCharacteristicChanged(device, it, false)
            }
        }
    }

    private fun hasBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (hasBlePermissions()) {
            bluetoothLeScanner?.stopScan(scanCallback)
            bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        }
        bluetoothGattServer?.close()
        connectedDevices.values.forEach { it.close() }
        connectedDevices.clear()
        serverConnections.clear()
    }
}