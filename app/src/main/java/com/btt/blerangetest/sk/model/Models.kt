package com.btt.blerangetest.sk.model

/**
 * 扫描到的 BLE 设备信息
 */
data class BleDevice(
    val name: String?,
    val address: String,
    val rssi: Int
)

/**
 * GATT 连接状态
 */
enum class ConnectionState {
    IDLE,           // 空闲
    CONNECTING,     // 连接中
    CONNECTED,      // 已连接
    DISCONNECTED    // 已断开
}

/**
 * 单条 RSSI 采样日志
 */
data class RssiSample(
    val timestampMs: Long,
    val rssi: Int?,
    val status: String,
    val note: String = ""
)

/**
 * 会话摘要（结束测试时展示）
 */
data class SessionSummary(
    val deviceName: String?,
    val deviceAddress: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val disconnectCount: Int,
    val lastRssi: Int?
) {
    val durationMs: Long get() = endTimeMs - startTimeMs
}

/**
 * 采样间隔选项
 */
enum class SampleInterval(val label: String, val ms: Long) {
    HALF_SECOND("0.5s", 500),
    ONE_SECOND("1s", 1000),
    TWO_SECONDS("2s", 2000),
    FIVE_SECONDS("5s", 5000)
}
