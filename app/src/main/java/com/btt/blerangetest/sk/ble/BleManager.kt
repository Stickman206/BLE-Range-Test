package com.btt.blerangetest.sk.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.btt.blerangetest.sk.model.BleDevice
import com.btt.blerangetest.sk.model.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * BLE 核心管理类。
 *
 * 职责：
 * - 扫描周围 BLE 设备（回调在主线程，通过 StateFlow 暴露）
 * - GATT 连接 / 断开
 * - RSSI 定时采样
 * - 断连检测（onConnectionStateChange → STATE_DISCONNECTED）
 * - 通过回调接口向外通知事件（供前台服务/UI 使用）
 */
@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    private val appContext = context.applicationContext
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val adapter: BluetoothAdapter? = bluetoothManager.adapter

    private val scanHandler = Handler(Looper.getMainLooper())
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val bleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---- 扫描状态 ----
    private val _scannedDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    val scannedDevices: StateFlow<List<BleDevice>> = _scannedDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    // ---- 连接状态 ----
    private val _connectionState = MutableStateFlow(ConnectionState.IDLE)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _connectedDevice = MutableStateFlow<BleDevice?>(null)
    val connectedDevice: StateFlow<BleDevice?> = _connectedDevice.asStateFlow()

    private val _lastRssi = MutableStateFlow<Int?>(null)
    val lastRssi: StateFlow<Int?> = _lastRssi.asStateFlow()

    @Volatile
    var gatt: BluetoothGatt? = null
        private set

    private var scanCallback: ScanCallback? = null

    // 采样间隔（毫秒）
    @Volatile
    var sampleIntervalMs: Long = 1000L

    @Volatile
    private var samplingActive = false

    // ---- 事件回调接口 ----
    var onConnectionStateChanged: ((ConnectionState) -> Unit)? = null
    var onDisconnected: ((BluetoothGatt, Int) -> Unit)? = null
    var onRssiRead: ((Int) -> Unit)? = null
    var onRssiReadFailed: (() -> Unit)? = null

    // ---- 扫描 ----

    fun startScan() {
        val btAdapter = adapter ?: return
        if (!hasScanPermission()) return
        if (_isScanning.value) return

        _scannedDevices.value = emptyList()
        _isScanning.value = true

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name
                val address = result.device.address
                val rssi = result.rssi
                val device = BleDevice(name, address, rssi)
                // 按地址去重，保留最新 RSSI
                val current = _scannedDevices.value.toMutableList()
                val idx = current.indexOfFirst { it.address == address }
                if (idx >= 0) {
                    current[idx] = device
                } else {
                    current.add(device)
                }
                current.sortByDescending { it.rssi }
                _scannedDevices.value = current
            }

            override fun onScanFailed(errorCode: Int) {
                _isScanning.value = false
                onScanFailed?.invoke(errorCode)
            }
        }
        scanCallback = callback

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val scanner = btAdapter.bluetoothLeScanner
        scanner.startScan(null, settings, callback)
    }

    var onScanFailed: ((Int) -> Unit)? = null

    // ---- 已配对设备 ----

    /**
     * 读取手机系统已配对的 BLE 设备（独立于扫描结果）。
     * API 31+ 需要 BLUETOOTH_CONNECT 权限，权限缺失时返回空列表。
     */
    fun getBondedDevices(): List<BleDevice> {
        if (!hasConnectPermission()) return emptyList()
        val bonded = adapter?.bondedDevices ?: return emptyList()
        return bonded
            .filter { it.type == BluetoothDevice.DEVICE_TYPE_LE || it.type == BluetoothDevice.DEVICE_TYPE_DUAL }
            .map { BleDevice(it.name, it.address, Int.MIN_VALUE) }
            .sortedBy { it.name ?: it.address }
    }

    /** 检查某个 MAC 是否是已配对设备 */
    fun isBonded(address: String): Boolean {
        if (!hasConnectPermission()) return false
        return adapter?.bondedDevices?.any { it.address.equals(address, ignoreCase = true) } == true
    }

    fun stopScan() {
        val btAdapter = adapter ?: return
        if (!_isScanning.value) return
        if (!hasScanPermission()) {
            _isScanning.value = false
            return
        }
        btAdapter.bluetoothLeScanner?.stopScan(scanCallback)
        _isScanning.value = false
        scanCallback = null
    }

    // ---- GATT 连接 ----

    /**
     * 连接指定设备。autoConnect=false（不自动重连，由测试人员手动操作）。
     */
    fun connect(device: BleDevice) {
        val btAdapter = adapter ?: return
        if (!hasConnectPermission()) return

        stopScan()
        _connectionState.value = ConnectionState.CONNECTING
        _connectedDevice.value = device

        val bluetoothDevice: BluetoothDevice? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.R &&
                device.address.isNotEmpty()
            ) {
                // 支持带分号与冒号格式
                btAdapter.getRemoteDevice(device.address.replace('-', ':'))
            } else {
                btAdapter.getRemoteDevice(device.address)
            }

        if (bluetoothDevice == null) {
            _connectionState.value = ConnectionState.IDLE
            onConnectFailed?.invoke("无效的设备地址")
            return
        }

        gatt = bluetoothDevice.connectGatt(appContext, false, gattCallback)
        // Android 某些设备 connectGatt 返回 null 表示连接失败
        if (gatt == null) {
            _connectionState.value = ConnectionState.IDLE
            onConnectFailed?.invoke("连接失败：无法建立 GATT 连接")
        }
    }

    var onConnectFailed: ((String) -> Unit)? = null

    /** 直接通过 MAC 地址连接（应对每次 B 设备不同的情况） */
    fun connectByAddress(address: String) {
        val clean = address.trim().uppercase().replace(":", "-")
        connect(BleDevice(null, clean, Int.MIN_VALUE))
    }

    /**
     * 主动断开连接（不触发报警）。
     */
    fun disconnect(manual: Boolean) {
        if (manual) stopSampling()
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
        if (manual) {
            _connectionState.value = ConnectionState.IDLE
            _connectedDevice.value = null
        }
    }

    // ---- RSSI 采样 ----

    /**
     * 启动定时 RSSI 采样循环（Handler 在主线程，间隔可配）。
     */
    fun startSampling(intervalMs: Long) {
        sampleIntervalMs = intervalMs
        samplingActive = true
        scheduleNextRssiRead()
    }

    fun stopSampling() {
        samplingActive = false
        scanHandler.removeCallbacksAndMessages(null)
    }

    private fun scheduleNextRssiRead() {
        if (!samplingActive) return
        scanHandler.postDelayed({
            if (!samplingActive) return@postDelayed
            readRssiOnce()
            scheduleNextRssiRead()
        }, sampleIntervalMs)
    }

    private fun readRssiOnce() {
        val g = gatt ?: return
        if (_connectionState.value != ConnectionState.CONNECTED) return
        val ok = try {
            g.readRemoteRssi()
        } catch (_: Exception) {
            false
        }
        if (!ok) {
            onRssiReadFailed?.invoke()
        }
    }

    // ---- GATT 回调 ----

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.value = ConnectionState.CONNECTED
                    // 连接后立即读一次 RSSI，随后由采样循环接管
                    _lastRssi.value = null
                    onConnectionStateChanged?.invoke(ConnectionState.CONNECTED)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    // 区分主动断开与意外断连
                    val wasConnecting = _connectionState.value == ConnectionState.CONNECTING
                    _connectionState.value = ConnectionState.DISCONNECTED
                    try { gatt.close() } catch (_: Exception) {}
                    this@BleManager.gatt = null
                    if (wasConnecting) {
                        onConnectFailed?.invoke("连接失败（status=$status）")
                    } else {
                        onConnectionStateChanged?.invoke(ConnectionState.DISCONNECTED)
                        onDisconnected?.invoke(gatt, status)
                    }
                }
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _lastRssi.value = rssi
                onRssiRead?.invoke(rssi)
            } else {
                onRssiReadFailed?.invoke()
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            // 无需订阅特征，忽略
        }
    }

    // ---- 权限检查 ----

    fun hasScanPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        }
        // API 26-30 需要定位权限
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun hasConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    // ---- 清理 ----

    fun destroy() {
        stopSampling()
        stopScan()
        disconnect(manual = true)
        mainScope.cancel()
        bleScope.cancel()
    }
}
