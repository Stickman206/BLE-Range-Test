package com.btt.blerangetest.sk.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btt.blerangetest.sk.model.ConnectionState
import com.btt.blerangetest.sk.service.BleMonitorService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 断连报警全屏页。
 *
 * - 红色闪烁背景 + 大字「设备已断连」
 * - 长按圆形按钮 2 秒停止报警（防误触，需求 3.3：报警需手动停止）
 * - 提供「重新连接」按钮（断连状态时显示）
 */
@Composable
fun AlarmScreen() {
    val context = LocalContext.current
    val connectionState by BleMonitorService.connectionState.collectAsState()
    val targetDevice by BleMonitorService.targetDevice.collectAsState()
    val lastRssi by BleMonitorService.lastRssi.collectAsState()

    // 禁止返回键关闭报警（必须长按停止）
    BackHandler(enabled = true) { }

    // 红色闪烁动画
    val transition = rememberInfiniteTransition(label = "alarmFlash")
    val flashAlpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "flashAlpha"
    )

    // 长按 2 秒停止
    var holdProgress by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    var holdJob by remember { mutableStateOf<Job?>(null) }

    val holdModifier = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            val startMs = System.nanoTime()
            holdProgress = 0f
            holdJob = scope.launch {
                while (true) {
                    val elapsed = (System.nanoTime() - startMs) / 1_000_000_000f
                    holdProgress = (elapsed / 2f).coerceIn(0f, 1f)
                    if (elapsed >= 2f) {
                        holdProgress = 1f
                        BleMonitorService.stopAlarm(context)
                        break
                    }
                    delay(16)
                }
            }
            // 等待手指抬起或取消
            waitForUpOrCancellation()
            holdJob?.cancel()
            holdJob = null
            holdProgress = 0f
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFB71C1C))
    ) {
        // 闪烁覆盖层
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White.copy(alpha = flashAlpha))
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = Color(0xFFB71C1C),
                modifier = Modifier.size(96.dp)
            )
            Spacer(Modifier.height(16.dp))

            Text(
                text = "设备已断连",
                fontSize = 40.sp,
                fontWeight = FontWeight.Black,
                color = Color(0xFFB71C1C),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))

            Text(
                text = "${targetDevice?.name ?: "未知设备"}\n${targetDevice?.address ?: ""}",
                fontSize = 16.sp,
                color = Color(0xFFB71C1C),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))

            Text(
                text = "断连前 RSSI: ${lastRssi?.let { "$it dBm" } ?: "—"}",
                fontSize = 14.sp,
                color = Color(0xFFB71C1C).copy(alpha = 0.8f)
            )
            Spacer(Modifier.height(40.dp))

            // ---- 长按 2 秒停止按钮 ----
            Box(
                modifier = Modifier
                    .size(180.dp)
                    .then(holdModifier),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    progress = { holdProgress },
                    modifier = Modifier.size(180.dp),
                    color = Color(0xFFB71C1C),
                    trackColor = Color.White.copy(alpha = 0.4f),
                    strokeWidth = 8.dp
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .size(140.dp)
                        .background(Color.White, CircleShape)
                        .padding(8.dp)
                ) {
                    Text(
                        text = "长按",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFB71C1C)
                    )
                    Text(
                        text = "停止报警",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFB71C1C)
                    )
                }
            }
            Spacer(Modifier.height(32.dp))

            // ---- 重新连接按钮（断开状态下显示） ----
            if (connectionState == ConnectionState.DISCONNECTED) {
                Button(
                    onClick = { BleMonitorService.reconnect(context) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFB71C1C),
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("重新连接", fontSize = 18.sp)
                }
            }
        }
    }
}
