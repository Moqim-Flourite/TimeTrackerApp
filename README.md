## 📱 时间记录助手 (TimeTracker)

一款 Android 自动时间记录应用，能够自动检测前台 App 并按类别记录使用时长，帮助你了解时间都花在了哪里。

### 📝 更新日志

#### v1.2.0 (2026-06-01)

**新功能：**
- 🧠 同义词/意图识别 — 输入“干活”自动归到“工作”，支持模糊匹配
- 🧹 乌龙任务自动清理 — 持续不到 1 分钟的相似任务自动删除
- 🔒 任务锁定机制 — 手动开始“吃饭”/“睡觉”后暂停自动监控
- 🛡️ 辅助应用白名单 — 睡觉时打开音乐/宝可梦睡眠不触发切换
- 🌙 睡眠周期制 — 以“睡觉”为一天分界线，统计更符合真实作息
- 📊 跨日任务分摊 — 跨越午夜的任务按时长按天分摊统计
- 💤 空闲时间记录 — 熄屏自动记录空闲，解锁后恢复，统计无缺口

**修复：**
- 🔧 Android 15/16 开机自启崩溃 — 前台服务类型从 dataSync 改为 specialUse
- 🔧 屏幕状态监听 — 仅动态注册，修复 Android 8+ 收不到 SCREEN_ON/OFF
- 🔧 WakeLock 超时 — 带 10 分钟超时 + 定期续期，防止 ROM 强制回收
- 🔧 饼图改扇形 — 实心扇形替代环形，更直观
- 🔧 自动更新缓存 — 用 HEAD 请求比对文件大小，避免缓存到损坏的 APK

#### v1.1.0 (2026-05-31)

- 初始公开版本
- 自动前台 App 监控、智能分类、统计报表、在线更新

---

### ✨ 核心功能

- **🔄 自动前台 App 监控** — 基于 UsageStatsManager 自动检测当前使用的 App
- **🏷️ 智能 App 分类** — 180+ 个 App 预设分类映射（社交/娱乐/工作/学习等）
- **📺 通知栏实时显示** — 状态栏常驻通知，实时显示当前任务、App 名称和使用时长
- **🔒 熄屏检测** — 监听屏幕开关事件
- **🚀 开机自启** — 支持开机自动启动监控服务
- **📊 统计分析** — 按类别查看今日/本周使用统计
- **📋 监控日志** — 内置日志系统，方便排查问题
- **🔄 在线更新** — App 内检查 GitHub Release 更新并一键安装

### 📂 任务分类

| 分类 | 示例 App |
|------|---------|
| 社交 | 微信、QQ、微博、小红书、Telegram |
| 娱乐 | 抖音、B站、YouTube、网易云音乐 |
| 阅读 | 番茄小说、微信读书、阅读(Legado) |
| 游戏 | 王者荣耀、原神、Steam |
| AI | ChatGPT、豆包、元宝、Operit |
| 工作 | 钉钉、飞书、腾讯会议、WPS |
| 学习 | 多邻国、欧路词典、墨墨背单词 |
| 购物 | 淘宝、京东、拼多多、闲鱼 |
| 生活 | 支付宝、米家、天气 |
| 交通 | 高德地图、航旅纵横 |
| 金融 | 招商银行、云闪付 |
| 工具 | Chrome、ZArchiver、MT管理器 |
| 系统/忽略 | 桌面、设置、输入法等（不记录） |

未在映射表中的 App 会自动使用应用名称作为分类。

### 🛠️ 技术栈

- **语言**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **架构**: MVVM + StateFlow
- **存储**: JSON 文件（无数据库依赖）
- **监控**: UsageStatsManager API
- **服务**: Foreground Service + Notification
- **最低版本**: Android 8.0 (API 26)

### 📦 安装

1. 从 [Releases](../../releases) 下载最新 APK
2. 安装后授予「使用情况访问权限」
3. 打开监控服务开关

### 🔧 从源码构建

```bash
# 克隆仓库
git clone https://github.com/Moqim-Flourite/time_tracker.git
cd time_tracker

# 构建 Debug APK
chmod +x gradlew
./gradlew assembleDebug

# APK 输出位置
ls app/build/outputs/apk/debug/app-debug.apk
```

### 📋 权限说明

| 权限 | 用途 |
|------|------|
| `FOREGROUND_SERVICE` | 前台服务保活 |
| `PACKAGE_USAGE_STATS` | 检测前台 App（需手动授权） |
| `POST_NOTIFICATIONS` | 显示通知栏 |
| `RECEIVE_BOOT_COMPLETED` | 开机自启 |
| `QUERY_ALL_PACKAGES` | 获取 App 名称 |

### 📄 License

MIT License