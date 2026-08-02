package com.btt.blerangetest.sk

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.btt.blerangetest.sk.model.ConnectionState
import com.btt.blerangetest.sk.service.BleMonitorService
import com.btt.blerangetest.sk.ui.AlarmScreen
import com.btt.blerangetest.sk.ui.BleRangeTestTheme
import com.btt.blerangetest.sk.ui.DeviceSelectScreen
import com.btt.blerangetest.sk.ui.MonitorScreen
import com.btt.blerangetest.sk.ui.SessionSummaryScreen

class MainActivity : ComponentActivity() {

    companion object {
        /** 全屏报警通知携带的标志：Activity 从通知启动时强制进入报警界面 */
        const val EXTRA_ALARM_ACTIVE = "extra_alarm_active"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BleRangeTestTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current

    // ---- 运行时权限（首次启动自动申请） ----
    val requiredPermissions = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    LaunchedEffect(Unit) {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    // ---- 服务状态 ----
    val isAlarming by BleMonitorService.isAlarming.collectAsState()
    val sessionSummary by BleMonitorService.sessionSummary.collectAsState()
    val sessionElapsedMs by BleMonitorService.sessionElapsedMs.collectAsState()
    val connectionState by BleMonitorService.connectionState.collectAsState()

    val summary = sessionSummary
    val testActive = sessionElapsedMs > 0L || connectionState != ConnectionState.IDLE

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            // 报警优先全屏展示
            isAlarming -> AlarmScreen()
            summary != null -> SessionSummaryScreen(summary)
            testActive -> MonitorScreen()
            else -> DeviceSelectScreen()
        }
    }
}
