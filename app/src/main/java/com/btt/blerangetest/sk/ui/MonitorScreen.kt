package com.btt.blerangetest.sk.ui

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btt.blerangetest.sk.log.CsvExporter
import com.btt.blerangetest.sk.model.ConnectionState
import com.btt.blerangetest.sk.service.BleMonitorService
import java.util.Locale

/** 测试监控页：RSSI 实时数值 + 历史曲线 + 会话统计 + 停止测试 */
@Composable
fun MonitorScreen() {
    val context = LocalContext.current

    val connectionState by BleMonitorService.connectionState.collectAsState()
    val targetDevice by BleMonitorService.targetDevice.collectAsState()
    val lastRssi by BleMonitorService.lastRssi.collectAsState()
    val disconnectCount by BleMonitorService.disconnectCount.collectAsState()
    val sessionElapsedMs by BleMonitorService.sessionElapsedMs.collectAsState()
    val rssiHistory by BleMonitorService.rssiHistory.collectAsState()
    val lastError by BleMonitorService.lastError.collectAsState()

    // 测试进行中保持屏幕常亮（配合服务端 PARTIAL_WAKE_LOCK 防止息屏）
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // 测试结束（服务已停止）时自动退回设备选择页
    BackHandler { BleMonitorService.stopTest(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp)
    ) {
        // ---- 设备信息 + 状态 ----
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = targetDevice?.name ?: "未知设备",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = targetDevice?.address ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusBadge(connectionState)
            }
        }
        Spacer(Modifier.height(16.dp))

        // ---- RSSI 大数值 ----
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = lastRssi?.let { "$it" } ?: "--",
                    fontSize = 72.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (lastRssi != null) rssiColor(lastRssi!!) else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "dBm · 实时信号强度",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        // ---- RSSI 曲线 ----
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "信号强度曲线（近 ${rssiHistory.size} 次采样）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                RssiChart(history = rssiHistory)
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- 会话统计 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                label = "会话时长",
                value = formatElapsed(sessionElapsedMs),
                modifier = Modifier.weight(1f)
            )
            StatCard(
                label = "断连次数",
                value = "$disconnectCount",
                modifier = Modifier.weight(1f)
            )
            StatCard(
                label = "采样间隔",
                value = formatInterval(BleMonitorService.currentIntervalMs),
                modifier = Modifier.weight(1f)
            )
        }

        // ---- 错误信息 ----
        if (lastError != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "⚠ ${lastError}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.weight(1f))

        // ---- 操作按钮 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (connectionState == ConnectionState.DISCONNECTED) {
                OutlinedButton(
                    onClick = { BleMonitorService.reconnect(context) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("重新连接")
                }
            } else {
                OutlinedButton(
                    onClick = { CsvExporter.shareLatest(context) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("导出日志")
                }
            }
            Button(
                onClick = { BleMonitorService.stopTest(context) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Filled.Pause, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("停止测试")
            }
        }
    }
}

/** 连接状态徽章 */
@Composable
private fun StatusBadge(state: ConnectionState) {
    val (label, color) = when (state) {
        ConnectionState.IDLE -> "空闲" to Color(0xFF757575)
        ConnectionState.CONNECTING -> "连接中…" to Color(0xFFF57F17)
        ConnectionState.CONNECTED -> "已连接" to Color(0xFF2E7D32)
        ConnectionState.DISCONNECTED -> "已断开" to Color(0xFFC62828)
    }
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}

/** 统计卡片 */
@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** 简易 RSSI 曲线（Canvas 折线图，范围 -30 ~ -110） */
@Composable
private fun RssiChart(history: List<Int>) {
    val gridColor = MaterialTheme.colorScheme.surfaceVariant
    val lineColor = MaterialTheme.colorScheme.primary

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
    ) {
        if (history.size < 2) {
            // 数据不足：画基准线
            drawLine(
                color = gridColor,
                start = Offset(0f, size.height / 2),
                end = Offset(size.width, size.height / 2),
                strokeWidth = 2f
            )
            return@Canvas
        }

        val minRssi = -110f
        val maxRssi = -30f
        val range = maxRssi - minRssi
        val padY = 10f

        // 网格线（-40 / -60 / -80 / -100）
        for (v in intArrayOf(-40, -60, -80, -100)) {
            val y = padY + (maxRssi - v) / range * (size.height - 2 * padY)
            drawLine(
                color = gridColor.copy(alpha = 0.5f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1f
            )
        }

        // 折线路径
        val path = Path()
        val stepX = size.width / (history.size - 1).coerceAtLeast(1)
        history.forEachIndexed { index, rssi ->
            val x = index * stepX
            val y = padY + (maxRssi - rssi.coerceIn(-110, -30)) / range * (size.height - 2 * padY)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 4f, cap = StrokeCap.Round)
        )
    }
}

/** 毫秒 → mm:ss */
internal fun formatElapsed(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return String.format(Locale.ROOT, "%02d:%02d", m, s)
}

/** 毫秒 → 间隔显示 */
internal fun formatInterval(ms: Long): String = when (ms) {
    500L -> "0.5s"
    2000L -> "2s"
    5000L -> "5s"
    else -> "1s"
}

/** 从 Compose LocalContext 找宿主 Activity */
internal fun android.content.Context.findActivity(): android.app.Activity? {
    var ctx = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
