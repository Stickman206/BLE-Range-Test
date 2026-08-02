package com.btt.blerangetest.sk.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.DeviceHub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.btt.blerangetest.sk.BuildConfig
import com.btt.blerangetest.sk.log.CsvExporter
import com.btt.blerangetest.sk.model.BleDevice
import com.btt.blerangetest.sk.model.SampleInterval
import com.btt.blerangetest.sk.service.BleMonitorService
import java.util.Locale

/** 设备选择页：扫描列表 / MAC 直连 / 采样间隔 / 导出日志 */
@Composable
fun DeviceSelectScreen() {
    val context = LocalContext.current
    val scanning by BleMonitorService.scanning.collectAsState()
    val scannedDevices by BleMonitorService.scannedDevices.collectAsState()
    val bluetoothOff by BleMonitorService.bluetoothOff.collectAsState()

    var macInput by rememberSaveable { mutableStateOf("") }
    var intervalMs by remember { mutableLongStateOf(BleMonitorService.currentIntervalMs) }
    var showAbout by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp)
    ) {
        // ---- 标题 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "BLE 拉距测试",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "选择测试设备",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { showAbout = true }) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = "关于",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- 蓝牙关闭警告 ----
        if (bluetoothOff) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "蓝牙已关闭，请先开启蓝牙",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // ---- 扫描控制 + 导出 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    if (scanning) BleMonitorService.stopScan(context)
                    else BleMonitorService.startScan(context)
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    if (scanning) Icons.Filled.Stop else Icons.Filled.Search,
                    contentDescription = null
                )
                Spacer(Modifier.width(6.dp))
                Text(if (scanning) "停止扫描" else "开始扫描")
            }
            OutlinedButton(
                onClick = { CsvExporter.shareLatest(context) },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("导出日志")
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- MAC 直连 ----
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "MAC 直连（设备未出现在列表时使用）",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = macInput,
                        onValueChange = { macInput = it },
                        placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val mac = macInput.trim().uppercase(Locale.ROOT)
                            if (isValidMac(mac)) {
                                BleMonitorService.startTestByAddress(context, mac)
                            }
                        },
                        enabled = isValidMac(macInput.trim().uppercase(Locale.ROOT))
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- 已配对设备（独立区块） ----
        var bondedDevices by remember { mutableStateOf(BleMonitorService.getBondedDevices(context)) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "已配对设备",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = {
                    bondedDevices = BleMonitorService.getBondedDevices(context)
                }
            ) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "刷新已配对设备",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(18.dp).height(18.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        if (bondedDevices.isEmpty()) {
            Text(
                "暂无已配对设备（可在系统蓝牙设置中配对后刷新）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(bondedDevices, key = { it.address }) { device ->
                    BondedDeviceCard(device = device, onClick = {
                        BleMonitorService.startTest(context, device)
                    })
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- 采样间隔 ----
        Text(
            "采样间隔",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SampleInterval.entries.forEach { interval ->
                val selected = interval.ms == intervalMs
                Box(
                    modifier = Modifier
                        .background(
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable {
                            intervalMs = interval.ms
                            BleMonitorService.setInterval(context, interval.ms)
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = interval.label,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- 设备列表 ----
        if (scanning) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .width(18.dp)
                        .height(18.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "正在扫描... 共 ${scannedDevices.size} 个设备",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Text(
                "发现 ${scannedDevices.size} 个设备",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))

        if (scannedDevices.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.BluetoothSearching,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .width(64.dp)
                            .height(64.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (scanning) "正在扫描周边 BLE 设备..." else "暂无设备，点击「开始扫描」",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(scannedDevices, key = { it.address }) { device ->
                    DeviceCard(device = device, onClick = {
                        BleMonitorService.startTest(context, device)
                    })
                }
            }
        }
    }

    // ---- 关于对话框 ----
    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("关于", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("BLE 拉距测试 v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "用于 BLE 设备拉距测试：RSSI 采样、断连报警、CSV 日志导出。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("开发者：Stickman", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "GitHub：https://github.com/Stickman",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) { Text("确定") }
            }
        )
    }
}

/** 单个设备卡片：名称 + 地址 + RSSI */
@Composable
private fun DeviceCard(device: BleDevice, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.DeviceHub,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name ?: "未知设备",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "${device.rssi} dBm",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = rssiColor(device.rssi)
            )
        }
    }
}

/** 已配对设备卡片（横向区块，无 RSSI 值，显示"已配对"标记） */
@Composable
private fun BondedDeviceCard(device: BleDevice, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .width(200.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.DeviceHub,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name ?: "未知设备",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "配对",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** RSSI 信号强度配色 */
internal fun rssiColor(rssi: Int): Color = when {
    rssi >= -60 -> Color(0xFF2E7D32)  // 好
    rssi >= -80 -> Color(0xFFF57F17)  // 中
    else -> Color(0xFFC62828)         // 差
}

/** MAC 地址格式校验：AA:BB:CC:DD:EE:FF 或 AA-BB-CC-DD-EE-FF */
internal fun isValidMac(mac: String): Boolean =
    Regex("^([0-9A-F]{2}[:-]){5}[0-9A-F]{2}$", RegexOption.IGNORE_CASE).matches(mac)
