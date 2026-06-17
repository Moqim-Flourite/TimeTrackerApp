## 📱 时间记录助手 (TimeTracker)

一款 Android 自动时间记录应用，能够自动检测前台 App 并按类别记录使用时长，帮助你了解时间都花在了哪里。

### 📝 更新日志

#### v1.5.0 (2026-06-18)

**新功能：
- 🎵 多 App 并行记录 — 支持同时记录前台 App、后台前台服务（音乐/导航）、小窗 App
- 📊 主次分明记录 — 前台为主任务，后台服务为伴随，小窗为辅助，统计可分开显示
- 🧹 自动清理不活跃任务 — 伴随/辅助 App 不再活跃时自动停止记录

**技术：
- TimeRecord 新增 recordType 字段（PRIMARY/COMPANION/AUXILIARY）
- UsageEvents 解析从取最新一条改为收集所有活跃 App
- 新增 FOREGROUND_SERVICE_START 事件检测

#### v1.4.2 (2026-06-18)

**修复（保活）：
- 🔧 UsageStats 查询三层降级 — queryEvents 被 HyperOS 过滤时自动降级到 queryUsageStats 和 ActivityManager
- 🔧 AlarmManager 心跳改用 setExactAndAllowWhileIdle — 绕过 Doze 模式，心跳不会被系统吞掉
- 🔧 HeartbeatReceiver 加 Android 15 前台服务启动检查 — 避免 ForegroundServiceStartNotAllowedException 静默失败
- 🔧 WorkManager 兜底重启 — 进程被杀后由系统层面调度重启
- 🔧 BootReceiver 加 canScheduleExactAlarms() 检查 — 精确闹钟权限没了会降级到不精确闹钟
- 🔧 WakeLock 续期改 3 分钟 — 留更多余量防止 HyperOS 提前回收
- 🔧 startMonitoring 防重入检查 — 避免多次调用导致监控协程并发竞态条件
- 🔧 屏幕亮着但 API 返回 null 时不再错误切空闲

**新功能：
- 🔋 电池优化白名单引导 — 首次打开提示用户添加到电池优化白名单，防止 HyperOS 冻结服务

#### v1.3.0 (2026-06-11)

**新功能：**
- 📝 日记分析链路 — 自动将每日时间记录拼接为结构化文本，调用 GPT-4o-mini 生成日报摘要+标签+分类
- ⚙️ 设置页面 — 支持输入/保存/清除 OpenAI API Key，本地加密存储
- 📅 按天日报卡片 — ReviewScreen 改为按天聚合，每天一张日报卡，往下滑看历史
- 🔌 可插拔分析器 — 有 API Key 走真实 GPT 分析，无 Key 自动降级到 stub，零配置可用

**技术：**
- 接入 OkHttp + OpenAI Chat Completions API
- EncryptedSharedPreferences 安全存储 API Key（加密不可用时自动降级）
- DiaryTextBuilder 跨日分摊 + PackageManager 应用名解析

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