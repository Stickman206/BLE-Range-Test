package com.btt.blerangetest.sk.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.btt.blerangetest.sk.MainActivity
import com.btt.blerangetest.sk.R

/**
 * 断连报警模块。
 *
 * - 声音：TYPE_ALARM 铃声循环播放（最高音量）
 * - 震动：长震-短震循环
 * - 亮屏：WakeLock + 全屏高优先级通知（锁屏界面也显示）
 * - 停止：仅由 UI 长按按钮触发 stop()
 */
class AlarmManager(private val context: Context) {

    private val appContext = context.applicationContext

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    var isActive: Boolean = false
        private set

    init {
        createNotificationChannels()
    }

    @Synchronized
    fun start(lastRssi: Int?) {
        if (isActive) return
        isActive = true

        startSound()
        startVibration()
        acquireWakeLock()
        showFullScreenNotification(lastRssi)
    }

    @Synchronized
    fun stop() {
        if (!isActive) return
        isActive = false

        stopSound()
        stopVibration()
        releaseWakeLock()
        cancelNotifications()
    }

    private fun startSound() {
        val uri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val r = RingtoneManager.getRingtone(appContext, uri)
        // 循环播放
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            r.isLooping = true
        }
        r.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        r.play()
        ringtone = r
    }

    private fun startVibration() {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        // 长震-短震循环: 震800ms 停200ms 震200ms 停200ms
        val pattern = longArrayOf(0, 800, 200, 200, 200)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(pattern, 0)
        }
        vibrator = v
    }

    private fun acquireWakeLock() {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "BleRangeTest::AlarmWakeLock"
        )
        wl.acquire(5 * 60 * 1000L) // 最多5分钟自动释放防止无限耗电
        wakeLock = wl
    }

    private fun createNotificationChannels() {
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val alarmChannel = NotificationChannel(
            CHANNEL_ALARM,
            appContext.getString(R.string.notification_channel_alarm),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "断连报警通知"
            enableVibration(true)
        }
        nm.createNotificationChannel(alarmChannel)
    }

    /** 全屏高优先级通知，息屏时也能亮屏显示 */
    private fun showFullScreenNotification(lastRssi: Int?) {
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ALARM_ACTIVE, true)
        }
        val pi = PendingIntent.getActivity(
            appContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(appContext, CHANNEL_ALARM)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(appContext.getString(R.string.notification_alarm_title))
            .setContentText(
                appContext.getString(R.string.notification_alarm_text) +
                    (lastRssi?.let { " 最后RSSI: ${it}dBm" } ?: "")
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(false)
            .setOngoing(true)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true) // Android 10+ 上息屏时全屏唤起

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setFullScreenIntent(pi, true) // 再次设置以确保 USE_FULL_SCREEN_INTENT 权限行为
        }

        nm.notify(NOTIFICATION_ID_ALARM, builder.build())
    }

    private fun stopSound() {
        ringtone?.stop()
        ringtone = null
    }

    private fun stopVibration() {
        vibrator?.cancel()
        vibrator = null
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun cancelNotifications() {
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID_ALARM)
    }

    fun cancelAlarmNotificationOnly() {
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID_ALARM)
    }

    companion object {
        const val CHANNEL_ALARM = "ble_alarm_channel"
        const val NOTIFICATION_ID_ALARM = 2001
    }
}
