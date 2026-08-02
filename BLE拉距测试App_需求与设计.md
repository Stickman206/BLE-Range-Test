# BLE蓝牙拉距测试App — 需求与设计文档

## 1. 项目背景

测试软硬结合的BLE蓝牙设备极限通信距离（拉距测试）。测试过程中设备A（手机）原地不动，设备B向任意方向直线行走，直到蓝牙断连。当前痛点：设备A持有者必须全程盯着手机蓝牙设置界面确认连接状态，断连时无法及时感知。

本App目标：**让测试人员不用盯屏幕，断连瞬间手机自动声震报警，同时记录RSSI数据用于测试报告。**

## 2. 使用场景

| 角色 | 操作 |
|------|------|
| 测试人员甲 | 持有手机（设备A），站在原地，手机放口袋或手持均可 |
| 测试人员乙 | 持有设备B，向某方向直线行走，越走越远 |
| 断连发生时 | 手机立即响铃+震动，甲按下停止键，记录当前距离 |

## 3. 功能需求

### 3.1 设备扫描与连接

- 扫描周围BLE设备，列表展示（设备名 + MAC地址 + 实时RSSI）
- 支持手动输入/粘贴设备MAC地址直连（应对每次B设备不同的情况）
- 点击目标设备 → 发起GATT连接
- 连接成功：界面显示"已连接"状态 + 设备信息
- 连接失败：Toast提示失败原因，返回列表

### 3.2 连接状态监控（核心）

- 维持GATT连接，注册 `BluetoothGattCallback.onConnectionStateChange` 监听
- 连接状态实时显示：已连接 / 已断开
- 断连检测延迟要求：**≤1秒**内触发报警（Android系统回调通常200~600ms）

### 3.3 断连报警（核心）

- 触发条件：GATT连接状态变为 `STATE_DISCONNECTED`
- 报警方式：
  - 声音：最大音量持续蜂鸣（使用 `RingtoneManager` 或 `MediaPlayer` 播放警报音）
  - 震动：持续震动模式（长震-短震循环）
  - 屏幕：全屏红色闪烁 + 大字"已断连"
- 停止方式：**仅手动停止**，屏幕显示一个大的"停止报警"按钮
- 息屏/后台时：通过前台服务(Foreground Service)保持监控，断连时亮屏+报警
- 误触防护：报警停止按钮需长按2秒才生效（防止口袋里误触关掉）

### 3.4 RSSI定时采样与日志

- 采样频率：默认每 **1秒** 读取一次RSSI（可配置：0.5s / 1s / 2s / 5s）
- 实现方式：`BluetoothGatt.readRemoteRssi()` 回调获取
- 日志字段：

| 字段 | 说明 | 示例 |
|------|------|------|
| timestamp | 采样时间（毫秒精度） | 2026-08-02 14:23:05.123 |
| rssi | 信号强度(dBm) | -67 |
| status | 当前连接状态 | CONNECTED / DISCONNECTED |

- 断连事件额外记录一行：`timestamp, last_rssi, DISCONNECTED, duration_ms`（本次连接持续时长）
- 存储：写入应用内部存储CSV文件，文件名格式 `ble_log_20260802_142305.csv`
- 导出：支持通过系统分享（Share Intent）导出CSV文件到微信/文件管理器

### 3.5 测试会话管理

- 一次"开始测试"到"结束测试"为一个会话
- 开始测试时：自动创建新日志文件，开始RSSI采样
- 结束测试时：停止采样，保存日志，显示本次测试摘要（总时长、断连次数、最后RSSI）
- 支持同一次测试中多次断连-重连（B走远了断，拿回来重连，继续走）

## 4. 非功能需求

| 项目 | 要求 |
|------|------|
| 最低Android版本 | Android 8.0 (API 26)，前台服务需要 |
| 权限 | BLUETOOTH_SCAN, BLUETOOTH_CONNECT, ACCESS_FINE_LOCATION, VIBRATE, POST_NOTIFICATIONS, FOREGROUND_SERVICE |
| 后台存活 | Foreground Service + 常驻通知，防止系统杀进程 |
| 屏幕常亮 | 测试进行中保持屏幕常亮（`FLAG_KEEP_SCREEN_ON`），可选关闭 |
| 性能 | RSSI采样不卡顿，报警触发无延迟 |
| 体积 | 轻量，无第三方重型框架依赖 |

## 5. 界面设计（共3个页面）

### 5.1 设备选择页（首页）

```
┌─────────────────────────┐
│  BLE拉距测试             │
├─────────────────────────┤
│  [MAC地址输入框] [直连]   │
├─────────────────────────┤
│  扫描结果：              │
│  ┌───────────────────┐  │
│  │ DeviceB_01        │  │
│  │ AA:BB:CC:DD:EE:FF │  │
│  │ RSSI: -45 dBm     │  │
│  └───────────────────┘  │
│  ┌───────────────────┐  │
│  │ Unknown Device    │  │
│  │ 11:22:33:44:55:66 │  │
│  │ RSSI: -72 dBm     │  │
│  └───────────────────┘  │
│                         │
│  [开始扫描]  [停止扫描]  │
└─────────────────────────┘
```

### 5.2 测试监控页（核心页面）

```
┌─────────────────────────┐
│  ● 已连接  DeviceB_01   │
│  MAC: AA:BB:CC:DD:EE:FF │
├─────────────────────────┤
│                         │
│     RSSI: -67 dBm       │
│     ████████░░  信号     │
│                         │
│  ┌───────────────────┐  │
│  │   实时RSSI曲线     │  │
│  │   ~~~/\~~~\___    │  │
│  └───────────────────┘  │
│                         │
│  连接时长: 02:34        │
│  断连次数: 0            │
│  采样间隔: [1s ▼]       │
│                         │
│  [结束测试]             │
└─────────────────────────┘
```

### 5.3 报警页（断连触发，覆盖全屏）

```
┌─────────────────────────┐
│ ███████████████████████ │
│ ██                   ██ │
│ ██    ⚠ 已断连！     ██ │  ← 全屏红色背景
│ ██                   ██ │
│ ██  最后RSSI: -93dBm ██ │
│ ██  时间: 14:23:05   ██ │
│ ██  持续: 02:34      ██ │
│ ██                   ██ │
│ ██  [长按停止报警]    ██ │  ← 长按2秒停止
│ ██                   ██ │
│ ███████████████████████ │
└─────────────────────────┘
```

## 6. 技术架构

```
┌────────────────────────────────────┐
│           UI Layer                 │
│  (Compose / XML，取决于模板)        │
│  设备列表 │ 监控面板 │ 报警全屏     │
├────────────────────────────────────┤
│        Service Layer               │
│  BleMonitorService (Foreground)    │
│  ├── GATT连接管理                  │
│  ├── RSSI定时采样 (Handler/Timer)  │
│  ├── 断连检测 → 报警触发           │
│  └── 日志写入 (CSV)               │
├────────────────────────────────────┤
│        Alarm Module                │
│  RingtoneManager + Vibrator        │
│  WakeLock (亮屏)                   │
├────────────────────────────────────┤
│        Android BLE API             │
│  BluetoothAdapter / BluetoothGatt  │
│  BluetoothGattCallback             │
└────────────────────────────────────┘
```

### 关键技术点

| 模块 | 实现方案 |
|------|----------|
| BLE扫描 | `BluetoothLeScanner.startScan()` + ScanCallback |
| GATT连接 | `device.connectGatt(context, false, gattCallback)` |
| 断连监听 | `onConnectionStateChange(gatt, status, STATE_DISCONNECTED)` |
| RSSI读取 | `gatt.readRemoteRssi()` → `onReadRemoteRssi` 回调，Handler定时循环 |
| 前台服务 | `startForeground()` + 常驻通知"测试进行中" |
| 报警声音 | `RingtoneManager.getRingtone(TYPE_ALARM)` 或 `MediaPlayer` 循环播放 |
| 震动 | `VibrationEffect.createWaveform(pattern, repeat)` |
| 亮屏 | `WakeLock(FULL_WAKE_LOCK)` + `FLAG_SHOW_WHEN_LOCKED` |
| 日志 | 直接写CSV到 `getExternalFilesDir()`，无需数据库 |
| 导出 | `FileProvider` + `ACTION_SEND` 分享CSV |

## 7. 状态机

```
[空闲] ──扫描/直连──→ [连接中] ──成功──→ [监控中]
                        │                    │
                      失败                 断连
                        │                    │
                        ↓                    ↓
                    [空闲+提示]         [报警中] ──长按停止──→ [已断连/待重连]
                                                                  │
                                                               重连成功
                                                                  │
                                                                  ↓
                                                              [监控中]
```

- 监控中 → 用户点"结束测试" → 停止采样 → 保存日志 → 回到空闲
- 已断连状态可选择：重新连接同一设备 / 返回设备列表

## 8. 权限声明（AndroidManifest.xml）

```xml
<!-- Android 12+ (API 31) -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<!-- Android 11及以下 -->
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<!-- 报警相关 -->
<uses-permission android:name="android.permission.VIBRATE" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<!-- 后台服务类型声明 (Android 14+) -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
```

## 9. 边界情况与注意事项

| 场景 | 处理方式 |
|------|----------|
| 测试中手机来电 | 前台服务保活，报警不受影响；来电结束后恢复监控 |
| 系统杀后台 | Foreground Service + START_STICKY，被杀后自动重启服务 |
| 蓝牙被手动关闭 | 监听 ACTION_STATE_CHANGED，提示"蓝牙已关闭"并停止测试 |
| RSSI读取失败 | 记录为 null，不中断采样循环 |
| 多次快速断连重连 | 每次断连都触发报警+记录，重连后继续采样 |
| 设备B超出范围后走回 | 不自动重连（autoConnect=false），由测试人员手动操作 |
| 存储空间不足 | CSV极小（1次/秒 ≈ 50字节/行，1小时≈180KB），无需担心 |

## 10. 日志文件示例

文件名：`ble_log_20260802_142305.csv`

```csv
timestamp,rssi,status,note
2026-08-02 14:23:05.001,-45,CONNECTED,session_start
2026-08-02 14:23:06.012,-47,CONNECTED,
2026-08-02 14:23:07.008,-52,CONNECTED,
2026-08-02 14:23:08.015,-58,CONNECTED,
...
2026-08-02 14:25:31.003,-91,CONNECTED,
2026-08-02 14:25:32.104,-93,DISCONNECTED,duration_ms=147103
2026-08-02 14:25:32.105,,,alarm_triggered
```

## 11. 验收标准

- [ ] 能扫描到周围BLE设备并列表展示
- [ ] 能通过MAC地址直连指定设备
- [ ] 连接后实时显示RSSI并按设定间隔记录
- [ ] 断连后1秒内触发持续蜂鸣+震动+全屏红色提示
- [ ] 报警仅通过长按按钮停止，不会自动停
- [ ] 息屏/切后台后仍能正常监控和报警
- [ ] 测试结束后可导出CSV日志文件
- [ ] 多次断连-重连循环正常工作
