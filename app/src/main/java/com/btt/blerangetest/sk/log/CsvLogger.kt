package com.btt.blerangetest.sk.log

import android.content.Context
import com.btt.blerangetest.sk.model.RssiSample
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * CSV 日志写入器。
 *
 * 文件写入应用外部存储 logs/ 目录（getExternalFilesDir），
 * 通过 FileProvider 分享。行格式：
 * timestamp,rssi,status,note
 */
class CsvLogger(context: Context) {

    private val appContext = context.applicationContext
    private val logDir: File =
        File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "logs").apply { mkdirs() }

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }
    private val fileTimeFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    @Volatile
    var currentFile: File? = null
        private set

    @Volatile
    var sessionStartMs: Long = 0L
        private set

    private val header = "timestamp,rssi,status,note"

    /** 开始新会话：创建新日志文件并写入表头与 session_start 行 */
    fun startSession(deviceName: String?, deviceAddress: String) {
        stopSession()
        val now = System.currentTimeMillis()
        sessionStartMs = now
        val file = File(logDir, "ble_log_${fileTimeFormat.format(Date(now))}.csv")
        currentFile = file
        writeHeader(file)
        val note = "session_start device=${deviceName ?: "Unknown"} addr=${deviceAddress}"
        writeRow(file, RssiSample(now, null, "CONNECTED", note))
    }

    /** 写入一条采样行 */
    fun writeSample(sample: RssiSample) {
        val file = currentFile ?: return
        writeRow(file, sample)
    }

    /** 结束会话：写 alarm_triggered 之外的收尾行，关闭文件引用 */
    fun stopSession() {
        currentFile = null
        sessionStartMs = 0L
    }

    /** 当前会话内最近写入的文件（供分享/汇总用） */
    fun lastFile(): File? = currentFile

    /** 列出所有历史日志文件 */
    fun listLogFiles(): List<File> =
        logDir.listFiles { f -> f.isFile && f.name.startsWith("ble_log_") && f.name.endsWith(".csv") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    private fun writeHeader(file: File) {
        file.writeText(header + "\n", Charsets.UTF_8)
    }

    private fun writeRow(file: File, sample: RssiSample) {
        val ts = timeFormat.format(Date(sample.timestampMs))
        val line = buildString {
            append(ts)
            append(',')
            append(sample.rssi?.toString() ?: "")
            append(',')
            append(sample.status)
            append(',')
            append(sample.note)
            append('\n')
        }
        file.appendText(line, Charsets.UTF_8)
    }
}
