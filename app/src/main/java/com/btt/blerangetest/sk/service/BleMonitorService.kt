package com.btt.blerangetest.sk.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.btt.blerangetest.sk.MainActivity
import com.btt.blerangetest.sk.R
import com.btt.blerangetest.sk.alarm.AlarmManager
import com.btt.blerangetest.sk.ble.BleManager
import com.btt.blerangetest.sk.log.CsvLogger
import com.btt.blerangetest.sk.model.BleDevice
import com.btt.blerangetest.sk.model.ConnectionState
import com.btt.blerangetest.sk.model.RssiSample
import com.btt.blerangetest.sk.model.SampleInterval
import com.btt.blerangetest.sk.model.SessionSummary
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
 * BLE 拉距测试前台服务。
 *
 * 职责：
 * - 持有 BleManager（扫描 / GATT 连接 / RSSI 采样 / 断连检测）
 * - 断连 → 触发 AlarmManager 报警 + 记录 CSV
 * - 常驻通知 "测试进行中"，START_STICKY 防杀
 * - 蓝牙被关闭 → 提示并停止测试
 *
 * UI 通过 companion 静态方法通信，通过 companion StateFlow 观察状态。
 * companion StateFlow 是唯一状态源，服务实例直接更新它们。
 */
class BleMonitorService : LifecycleService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var bleManager: BleManager
    private lateinit var csvLogger: CsvLogger
    private lateinit var alarmManager: AlarmManager

    private var monitorWakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var testActive = false

    @Volatile
    private var sessionStartMs = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
        serviceRunning.value = true
        createMonitorChannel()
        bleManager = BleManager(this)
        csvLogger = CsvLogger(this)
        alarmManager = AlarmManager(this)
        wireBleCallbacks()
        registerBluetoothStateReceiver()
        // 扫描状态桥接到 companion
        serviceScope.launch {
            bleManager.isScanning.collect { s -> scanning.value = s }
        }
        serviceScope.launch {
            bleManager.scannedDevices.collect { d -> scannedDevices.value = d }
        }
        serviceScope.launch {
            bleManager.connectionState.collect { c -> connectionState.value = c }
        }
        serviceScope.launch {
            bleManager.connectedDevice.collect { d ->
                connectedDevice.value = d
                if (d != null) targetDevice.value = d
            }
        }
        serviceScope.launch {
            bleManager.lastRssi.collect { r -> lastRssi.value = r }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START_TEST -> handleStartTest(intent)
            ACTION_START_SCAN -> bleManager.startScan()
            ACTION_STOP_SCAN -> bleManager.stopScan()
            ACTION_STOP_TEST -> handleStopTest()
            ACTION_STOP_ALARM -> handleStopAlarm()
            ACTION_RECONNECT -> {
                val device = targetDevice.value
                if (device != null) {
                    connectAndBegin(device)
                }
            }
            ACTION_DISCONNECT -> {
                bleManager.disconnect(manual = true)
                connectionState.value = ConnectionState.IDLE
                connectedDevice.value = null
                isAlarming.value = false
                alarmManager.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            ACTION_SET_INTERVAL -> {
                bleManager.sampleIntervalMs =
                    intent.getLongExtra(EXTRA_INTERVAL_MS, SampleInterval.ONE_SECOND.ms)
                currentIntervalMs = bleManager.sampleIntervalMs
            }
        }
        return START_STICKY
    }

    // ---- 测试会话 ----

    private fun handleStartTest(intent: Intent) {
        val name = intent.getStringExtra(EXTRA_DEVICE_NAME)
        val rssi = intent.getIntExtra(EXTRA_DEVICE_RSSI, Int.MIN_VALUE)
        val mac = intent.getStringExtra(EXTRA_MAC) ?: ""
        val intervalMs = intent.getLongExtra(EXTRA_INTERVAL_MS, SampleInterval.ONE_SECOND.ms)
        val byAddress = intent.getBooleanExtra(EXTRA_BY_ADDRESS, false)

        val device = if (mac.isNotEmpty()) BleDevice(name, mac, rssi) else null

        // 开始测试：创建会话、启动前台、常亮
        testActive = true
        sessionStartMs = System.currentTimeMillis()
        isAlarming.value = false
        sessionSummary.value = null
        disconnectCount.value = 0
        rssiHistory.value = emptyList()
        lastRssi.value = null
        lastError.value = null

        bleManager.sampleIntervalMs = intervalMs
        currentIntervalMs = intervalMs

        startForeground(NOTIFICATION_ID_MONITOR, buildMonitorNotification())
        acquireMonitorWakeLock()

        // 连接（直连或从扫描结果）
        if (byAddress) {
            bleManager.connectByAddress(mac)
            csvLogger.startSession(null, mac)
        } else if (device != null) {
            connectAndBegin(device)
        }

        // 会话计时器
        serviceScope.launch {
            while (testActive) {
                sessionElapsedMs.value = System.currentTimeMillis() - sessionStartMs
                delay(500)
            }
        }
    }

    private fun connectAndBegin(device: BleDevice) {
        targetDevice.value = device
        connectedDevice.value = device
        csvLogger.startSession(device.name, device.address)
        bleManager.connect(device)
    }

    private fun handleStopTest() {
        testActive = false
        bleManager.stopSampling()
        bleManager.disconnect(manual = true)

        val endMs = System.currentTimeMillis()
        val device = targetDevice.value
        sessionSummary.value = SessionSummary(
            deviceName = device?.name,
            deviceAddress = device?.address ?: "",
            startTimeMs = sessionStartMs,
            endTimeMs = endMs,
            disconnectCount = disconnectCount.value,
            lastRssi = lastRssi.value
        )
        csvLogger.stopSession()

        if (isAlarming.value) {
            alarmManager.stop()
            isAlarming.value = false
        }
        releaseMonitorWakeLock()
        connectionState.value = ConnectionState.IDLE
        connectedDevice.value = null
        targetDevice.value = null
        sessionElapsedMs.value = 0L
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun handleStopAlarm() {
        if (!isAlarming.value) return
        alarmManager.stop()
        isAlarming.value = false
        // 保持 DISCONNECTED 状态，由 UI 决定重连或返回
    }

    // ---- BLE 回调 ----

    private fun wireBleCallbacks() {
        bleManager.onConnectionStateChanged = { state ->
            if (state == ConnectionState.CONNECTED && testActive) {
                bleManager.startSampling(bleManager.sampleIntervalMs)
                updateMonitorNotification()
            }
        }

        bleManager.onDisconnected = { _, _ ->
            handleUnexpectedDisconnect()
        }

        bleManager.onRssiRead = { rssi ->
            lastRssi.value = rssi
            // 维护曲线数据（最多保留200点）
            val hist = rssiHistory.value.toMutableList()
            hist.add(rssi)
            if (hist.size > 200) hist.removeAt(0)
            rssiHistory.value = hist
            if (testActive) {
                csvLogger.writeSample(
                    RssiSample(System.currentTimeMillis(), rssi, "CONNECTED", "")
                )
            }
        }

        bleManager.onRssiReadFailed = {
            if (testActive) {
                csvLogger.writeSample(
                    RssiSample(System.currentTimeMillis(), null, "CONNECTED", "rssi_read_failed")
                )
            }
        }

        bleManager.onConnectFailed = { msg ->
            connectionState.value = ConnectionState.IDLE
            lastError.value = msg
            if (testActive) {
                csvLogger.writeSample(
                    RssiSample(System.currentTimeMillis(), null, "CONNECT_FAILED", msg)
                )
            }
        }
    }

    private fun handleUnexpectedDisconnect() {
        if (!testActive) return

        val now = System.currentTimeMillis()
        val durationMs = if (sessionStartMs > 0) now - sessionStartMs else 0L

        disconnectCount.value = disconnectCount.value + 1

        // CSV：断连事件行
        csvLogger.writeSample(
            RssiSample(now, lastRssi.value, "DISCONNECTED", "duration_ms=$durationMs")
        )
        // CSV：报警触发行
        csvLogger.writeSample(
            RssiSample(now, null, "", "alarm_triggered")
        )

        // 触发报警（声音+震动+全屏）
        if (!isAlarming.value) {
            isAlarming.value = true
            alarmManager.start(lastRssi.value)
        }
    }

    // ---- 通知 ----

    private fun createMonitorChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_MONITOR,
            getString(R.string.notification_channel_monitor),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "测试进行中的常驻通知"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildMonitorNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_MONITOR)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notification_monitor_title))
            .setContentText(
                getString(R.string.notification_monitor_text) +
                    (targetDevice.value?.let { " · ${it.name ?: it.address}" } ?: "")
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateMonitorNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID_MONITOR, buildMonitorNotification())
    }

    // ---- 亮屏兜底 ----

    private fun acquireMonitorWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BleRangeTest::MonitorWakeLock")
        wl.acquire()
        monitorWakeLock = wl
    }

    private fun releaseMonitorWakeLock() {
        monitorWakeLock?.takeIf { it.isHeld }?.release()
        monitorWakeLock = null
    }

    // ---- 蓝牙状态监听 ----

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(
                android.bluetooth.BluetoothAdapter.EXTRA_STATE,
                android.bluetooth.BluetoothAdapter.ERROR
            )
            if (state == android.bluetooth.BluetoothAdapter.STATE_OFF) {
                bluetoothOff.value = true
                connectionState.value = ConnectionState.IDLE
                if (testActive) {
                    csvLogger.writeSample(
                        RssiSample(System.currentTimeMillis(), null, "BLUETOOTH_OFF", "")
                    )
                    handleStopTest()
                }
            } else if (state == android.bluetooth.BluetoothAdapter.STATE_ON) {
                bluetoothOff.value = false
            }
        }
    }

    private fun registerBluetoothStateReceiver() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                bluetoothStateReceiver,
                IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(
                bluetoothStateReceiver,
                IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
            )
        }
    }

    override fun onDestroy() {
        instance = null
        serviceRunning.value = false
        try {
            unregisterReceiver(bluetoothStateReceiver)
        } catch (_: Exception) {
        }
        serviceScope.cancel()
        if (::bleManager.isInitialized) bleManager.destroy()
        if (::alarmManager.isInitialized) alarmManager.stop()
        releaseMonitorWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    // ---- companion：UI 通信接口（唯一状态源） ----

    companion object {
        const val ACTION_START_TEST = "com.btt.blerangetest.sk.START_TEST"
        const val ACTION_START_SCAN = "com.btt.blerangetest.sk.START_SCAN"
        const val ACTION_STOP_SCAN = "com.btt.blerangetest.sk.STOP_SCAN"
        const val ACTION_STOP_TEST = "com.btt.blerangetest.sk.STOP_TEST"
        const val ACTION_STOP_ALARM = "com.btt.blerangetest.sk.STOP_ALARM"
        const val ACTION_RECONNECT = "com.btt.blerangetest.sk.RECONNECT"
        const val ACTION_DISCONNECT = "com.btt.blerangetest.sk.DISCONNECT"
        const val ACTION_SET_INTERVAL = "com.btt.blerangetest.sk.SET_INTERVAL"

        const val EXTRA_DEVICE = "extra_device"
        const val EXTRA_DEVICE_NAME = "extra_device_name"
        const val EXTRA_DEVICE_RSSI = "extra_device_rssi"
        const val EXTRA_MAC = "extra_mac"
        const val EXTRA_INTERVAL_MS = "extra_interval_ms"
        const val EXTRA_BY_ADDRESS = "extra_by_address"

        const val CHANNEL_MONITOR = "ble_monitor_channel"
        const val NOTIFICATION_ID_MONITOR = 1001

        @Volatile
        private var instance: BleMonitorService? = null

        // ---- 对外状态 ----
        val connectionState = MutableStateFlow(ConnectionState.IDLE)
        val connectedDevice = MutableStateFlow<BleDevice?>(null)
        val targetDevice = MutableStateFlow<BleDevice?>(null)
        val isAlarming = MutableStateFlow(false)
        val lastRssi = MutableStateFlow<Int?>(null)
        val disconnectCount = MutableStateFlow(0)
        val sessionElapsedMs = MutableStateFlow(0L)
        val sessionSummary = MutableStateFlow<SessionSummary?>(null)
        val scanning = MutableStateFlow(false)
        val scannedDevices = MutableStateFlow<List<BleDevice>>(emptyList())
        val rssiHistory = MutableStateFlow<List<Int>>(emptyList())
        val bluetoothOff = MutableStateFlow(false)
        val lastError = MutableStateFlow<String?>(null)
        val serviceRunning = MutableStateFlow(false)

        @Volatile
        var currentIntervalMs: Long = SampleInterval.ONE_SECOND.ms

        fun isServiceRunning(): Boolean = serviceRunning.value

        /** 关闭会话摘要（UI 在摘要页点击"返回"时调用） */
        fun dismissSessionSummary() {
            sessionSummary.value = null
        }

        /** 查询当前正在连接的 BLE 设备（供设备选择页展示独立区块） */
        fun getConnectedDevices(context: Context): List<BleDevice> {
            val mgr = instance?.let { it.bleManager } ?: BleManager(context)
            return mgr.getConnectedDevices()
        }

        // ---- UI 调用入口 ----

        fun startTest(context: Context, device: BleDevice?) {
            val intent = Intent(context, BleMonitorService::class.java).apply {
                action = ACTION_START_TEST
                device?.let {
                    putExtra(EXTRA_DEVICE_NAME, it.name)
                    putExtra(EXTRA_DEVICE_RSSI, it.rssi)
                }
                putExtra(EXTRA_MAC, device?.address ?: "")
                putExtra(EXTRA_INTERVAL_MS, currentIntervalMs)
            }
            startFgService(context, intent)
        }

        fun startTestByAddress(context: Context, mac: String) {
            val intent = Intent(context, BleMonitorService::class.java).apply {
                action = ACTION_START_TEST
                putExtra(EXTRA_MAC, mac)
                putExtra(EXTRA_BY_ADDRESS, true)
                putExtra(EXTRA_INTERVAL_MS, currentIntervalMs)
            }
            startFgService(context, intent)
        }

        fun startScan(context: Context) {
            // 扫描无需前台通知，普通 startService（App 前台时合法）
            context.startService(Intent(context, BleMonitorService::class.java).apply {
                action = ACTION_START_SCAN
            })
        }

        fun stopScan(context: Context) {
            instance?.let {
                context.startService(Intent(context, BleMonitorService::class.java).apply {
                    action = ACTION_STOP_SCAN
                })
            }
        }

        fun stopTest(context: Context) {
            context.startService(Intent(context, BleMonitorService::class.java).apply {
                action = ACTION_STOP_TEST
            })
        }

        fun stopAlarm(context: Context) {
            context.startService(Intent(context, BleMonitorService::class.java).apply {
                action = ACTION_STOP_ALARM
            })
        }

        fun reconnect(context: Context) {
            context.startService(Intent(context, BleMonitorService::class.java).apply {
                action = ACTION_RECONNECT
            })
        }

        fun disconnect(context: Context) {
            context.startService(Intent(context, BleMonitorService::class.java).apply {
                action = ACTION_DISCONNECT
            })
        }

        fun setInterval(context: Context, intervalMs: Long) {
            currentIntervalMs = intervalMs
            instance?.let {
                context.startService(Intent(context, BleMonitorService::class.java).apply {
                    action = ACTION_SET_INTERVAL
                    putExtra(EXTRA_INTERVAL_MS, intervalMs)
                })
            }
        }

        /** 测试启动必须 startForegroundService + 立即 startForeground */
        private fun startFgService(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
