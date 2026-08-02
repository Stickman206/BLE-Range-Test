# BLE 拉距测试 App

基于 Android（Kotlin + Jetpack Compose）的 BLE 拉距测试工具，用于评估蓝牙设备在不同距离下的信号表现：实时监控 RSSI、定时采样、断连报警、CSV 日志导出。

## 功能特性

- **BLE 设备扫描**：实时扫描周边 BLE 设备，按信号强度排序展示名称 / MAC / RSSI
- **当前已连接设备快捷选择**：独立区块展示当前正在连接的 BLE 设备（非历史配对记录），一键直连
- **MAC 直连**：设备未出现在扫描列表时，可手动输入 MAC 地址连接
- **定时 RSSI 采样**：支持 0.5s / 1s / 2s / 5s 四种采样间隔
- **实时信号曲线**：监控页展示实时 RSSI 大数值 + 最近 200 次采样折线图
- **断连报警**：连接断开 ≤1s 触发铃声 + 震动 + 全屏红色报警页，长按 2 秒停止（防误触）
- **息屏/后台监控**：前台服务常驻（START_STICKY），息屏与后台状态下持续采样与报警
- **CSV 日志**：每次会话生成独立 CSV 文件（时间戳 / RSSI / 状态 / 备注），系统分享导出
- **会话统计**：测试结束后展示时长、断连次数、最后 RSSI 摘要

## 技术栈

| 组件 | 版本 |
| --- | --- |
| Kotlin | 2.3.0 |
| Jetpack Compose | 1.10.0-beta02 |
| Material 3 | 1.5.0-alpha04 |
| AGP | 9.0.0 |
| Gradle | 9.4.0 |
| minSdk / targetSdk | 26 / 36 |

## 环境要求

- **JDK 17+**（推荐使用 Android Studio 自带的 JBR）
- **Android Studio**（最新稳定版）
- Android SDK Platform 36
- 真机调试（BLE 功能依赖真机蓝牙硬件）

## 构建与安装

### 命令行构建

```bash
# Windows (PowerShell)
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug

# macOS / Linux
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
```

APK 输出路径：`app/build/outputs/apk/debug/app-debug.apk`

### Android Studio

1. 用 Android Studio 打开项目根目录
2. 等待 Gradle 同步完成
3. Run ▶ 选择真机设备运行

## 使用说明

1. **首次启动**：授予蓝牙扫描 / 连接、定位（Android 11 及以下）、通知权限
2. **选择设备**：从「当前已连接设备」区块或扫描列表中选择目标 BLE 设备；也可在「MAC 直连」输入框手动填写地址
3. **设置采样间隔**：点击间隔选项（0.5s / 1s / 2s / 5s）
4. **开始测试**：点击设备卡片开始连接与 RSSI 监控；测试期间屏幕保持常亮
5. **断连报警**：连接异常断开时触发全屏报警（铃声 + 震动），长按圆形按钮 2 秒停止；也可点「重新连接」立即恢复测试
6. **停止测试**：监控页点击「停止测试」，查看会话摘要并导出 CSV 日志

### CSV 日志格式

```
timestamp,rssi,status,note
2026-08-02 19:10:05.123,-67,CONNECTED,session_start device=xxx addr=AA:BB:CC:DD:EE:FF
2026-08-02 19:10:06.124,-69,CONNECTED,
2026-08-02 19:10:07.125,DISCONNECTED,duration_ms=3000
```

日志文件位于应用外部存储 `Android/data/com.btt.blerangetest.sk/files/logs/` 目录。

## 权限说明

| 权限 | 用途 |
| --- | --- |
| BLUETOOTH_SCAN / BLUETOOTH_CONNECT | BLE 扫描与 GATT 连接（Android 12+） |
| ACCESS_FINE_LOCATION | BLE 扫描（Android 11 及以下） |
| POST_NOTIFICATIONS | 前台服务通知与报警通知（Android 13+） |
| FOREGROUND_SERVICE | 常驻监控前台服务 |
| VIBRATE / WAKE_LOCK | 断连报警震动与亮屏 |

## 项目结构

```
app/src/main/java/com/btt/blerangetest/sk/
├── MainActivity.kt              # 入口：权限申请 + 页面路由 + 报警全屏覆盖
├── ble/BleManager.kt            # BLE 核心：扫描 / GATT 连接 / RSSI 采样 / 断连检测
├── service/BleMonitorService.kt # 前台监控服务（唯一状态源，UI 通过 StateFlow 通信）
├── alarm/AlarmManager.kt        # 断连报警：铃声 / 震动 / 亮屏 / 全屏通知
├── log/CsvLogger.kt             # CSV 日志写入
├── log/CsvExporter.kt           # 日志系统分享导出
├── model/Models.kt              # 数据模型：BleDevice / RssiSample / SessionSummary
└── ui/                          # Compose 界面：设备选择 / 监控 / 报警 / 摘要
```

## 架构说明

- **前台服务为唯一状态源**：`BleMonitorService` 的 companion StateFlow 向 UI 暴露扫描、连接、采样、报警等全部状态
- **UI 通过静态方法驱动服务**：`startTest` / `stopTest` / `reconnect` / `stopAlarm` 等
- **服务进程被杀可自恢复**（START_STICKY），报警使用全屏高优先级通知在息屏时唤起

## 常见问题

- **扫描不到设备**：确认已授予定位 / 蓝牙扫描权限，且目标设备处于可广播状态
- **后台被系统杀死**：部分厂商 ROM 需在系统设置中允许应用自启动 / 后台运行
- **Android 14+ 前台服务**：已声明 `foregroundServiceType="connectedDevice"`

## License

[MIT License](LICENSE) — Copyright (c) 2026 **Stickman**

本项目采用 MIT 协议开源，允许自由使用、修改与再分发（含商用），需保留版权声明。

## 作者

**Stickman** — https://github.com/Stickman206
