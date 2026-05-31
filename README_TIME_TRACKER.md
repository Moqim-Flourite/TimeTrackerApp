# 时间记录助手 Android App

## 🎯 功能特性

- **任务计时**：开始/停止任务，实时显示持续时间（HH:MM:SS）
- **统计报表**：今日/本周/总计统计，按类别排序显示
- **历史记录**：查看所有时间记录，支持删除
- **快速任务**：预设"工作/学习/休息/运动"快捷按钮
- **数据持久化**：基于 JSON 文件存储，不怕进程重启
- **Material Design 3**：现代化 UI 设计

## 🏗️ 技术架构

- **UI 框架**：Jetpack Compose
- **设计规范**：Material Design 3
- **架构模式**：MVVM (ViewModel + StateFlow)
- **导航**：Navigation Compose
- **数据存储**：JSON 文件（无需 Room/KSP）
- **构建工具**：Gradle 9.1.0 + Kotlin 2.3.10

## 📱 安装使用

### 1. 构建 APK
```bash
./gradlew assembleDebug
```

### 2. 安装到设备
```bash
# 方式 1：使用 adb
adb install app/build/outputs/apk/debug/app-debug.apk

# 方式 2：直接传输到手机安装
```

### 3. 使用说明
1. 打开 App，输入任务名称或点击快捷按钮
2. 点击"开始"按钮开始计时
3. 点击右下角停止按钮结束任务
4. 查看统计页面了解时间分配
5. 历史记录页面查看所有记录

## 📁 项目结构

```
app/src/main/java/com/operit/timetracker/
├── MainActivity.kt          # 主入口
├── data/
│   ├── TimeRecord.kt        # 数据模型
│   └── DataStore.kt         # JSON 文件存储层
├── ui/
│   ├── screens/
│   │   ├── MainScreen.kt    # 主界面（计时 + 快捷任务）
│   │   ├── StatsScreen.kt   # 统计页面
│   │   └── HistoryScreen.kt # 历史记录
│   ├── viewmodel/
│   │   └── TaskViewModel.kt # 业务逻辑 ViewModel
│   ├── navigation/
│   │   └── AppNavigation.kt # 页面导航
│   └── theme/               # Material 3 主题配置
```

## 🔧 开发环境

- **JDK**: 17+
- **Android SDK**: 35
- **Gradle**: 9.1.0
- **Kotlin**: 2.3.10
- **Compose BOM**: 2026.01.01

## 📊 数据存储

数据存储在 App 私有目录：
```
/data/data/com.operit.timetracker/files/timetracker/
├── records.json   # 所有时间记录
└── state.json     # 当前进行中的任务
```

## 🚀 与旧系统对比

| 特性 | Python 脚本版本 | Android App 版本 |
|------|----------------|-----------------|
| 进程稳定性 | 经常被系统杀死 | 稳定运行 |
| 数据存储 | CSV 文件 | JSON 文件 |
| UI 界面 | 命令行 | Material Design 3 |
| 依赖项 | Python + 多个脚本 | 独立 App |
| 自动监控 | 需要后台服务 | 不需要 |
| 离线使用 | 需要终端环境 | 完全离线 |

## 📝 注意事项

1. **数据迁移**：旧的 `time_log.csv` 数据需要手动导入（可开发迁移工具）
2. **自动监控**：此版本不包含 App 自动监控功能，需要手动记录
3. **备份**：建议定期备份 `/data/data/com.operit.timetracker/files/timetracker/` 目录

---

**Happy Time Tracking! ⏱️✨**