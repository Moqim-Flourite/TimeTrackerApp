# 时间记录助手 - 安装指南

## 🎉 构建成功！

APK 已生成：
```
/data/user/0/com.ai.assistance.operit/files/workspace/07589cbe-21d4-43d2-9cf5-7acbe33b6dac/app/build/outputs/apk/debug/app-debug.apk
大小: 12MB
```

## 📲 安装到手机

### 方法 1：使用 ADB（推荐）
```bash
# 1. 连接手机到电脑，开启USB调试
# 2. 安装APK
adb install app/build/outputs/apk/debug/app-debug.apk

# 3. 如果需要覆盖安装
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 方法 2：直接传输安装
1. 将 APK 文件复制到手机存储
2. 在手机上打开文件管理器
3. 找到 `app-debug.apk` 并点击安装
4. 需要开启"未知来源"安装权限

### 方法 3：使用 Operit 安装
```bash
# 在 Operit 终端中执行
pm install /data/user/0/com.ai.assistance.operit/files/workspace/07589cbe-21d4-43d2-9cf5-7acbe33b6dac/app/build/outputs/apk/debug/app-debug.apk
```

## 🚀 首次使用

1. 打开 App，主界面显示当前任务状态
2. 输入任务名称（如"写代码"）或点击快捷按钮（工作/学习/休息/运动）
3. 点击"开始"按钮开始计时
4. 点击右下角停止按钮结束任务
5. 使用顶部导航查看统计和历史记录

## 📊 核心功能

### 主界面
- 实时计时器（HH:MM:SS 格式）
- 快捷任务按钮（可自定义）
- 今日统计摘要

### 统计页面
- 今日/本周/总计统计
- 按类别排序显示
- 百分比和时长显示

### 历史记录
- 查看所有时间记录
- 支持删除单条记录
- 显示开始/结束时间和持续时间

## 🔧 技术说明

### 为什么选择 JSON 而不是 Room？
1. **避免 KSP 依赖问题**：Kotlin 2.3.10 的 KSP 版本不稳定
2. **简化构建**：不需要注解处理器
3. **数据可读性**：JSON 文件可以直接编辑
4. **迁移方便**：未来可以轻松切换到 Room

### 数据存储位置
```
/data/data/com.operit.timetracker/files/timetracker/
├── records.json   # 所有时间记录
└── state.json     # 当前进行中的任务
```

## 📈 后续改进建议

### 短期优化
1. **自定义快捷任务**：允许用户添加/编辑快捷按钮
2. **数据导入导出**：支持从旧 CSV 迁移数据
3. **通知提醒**：长时间无操作提醒
4. **深色模式**：支持系统深色模式

### 中期功能
1. **目标设定**：每日/每周时间目标
2. **图表统计**：饼图/柱状图显示时间分配
3. **数据同步**：云同步或多设备同步
4. **Widget**：桌面小组件快速操作

### 长期规划
1. **自动监控**：集成 App 使用监控（类似旧系统）
2. **智能分类**：AI 自动识别任务类型
3. **报告生成**：生成周报/月报
4. **团队协作**：多人时间管理

## ⚠️ 注意事项

1. **数据备份**：定期备份 `/data/data/com.operit.timetracker/files/timetracker/` 目录
2. **权限要求**：无特殊权限需求，完全离线运行
3. **兼容性**：支持 Android 7.0+ (API 24+)
4. **存储空间**：建议预留 50MB 空间

## 🔄 更新日志

### v1.0.0 (2026-05-28)
- ✅ 基础计时功能
- ✅ Material Design 3 UI
- ✅ 今日/本周/总计统计
- ✅ 历史记录管理
- ✅ JSON 文件存储
- ✅ 快捷任务按钮
- ✅ 3 页面导航（主页/统计/历史）

---

**构建环境**: Operit Android 项目模板  
**构建时间**: 2026-05-27 18:22  
**Gradle 版本**: 9.1.0  
**Kotlin 版本**: 2.3.10  
**Compose BOM**: 2026.01.01