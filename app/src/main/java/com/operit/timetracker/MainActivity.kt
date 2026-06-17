package com.operit.timetracker

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.operit.timetracker.ui.MainViewModel
import com.operit.timetracker.ui.navigation.AppNavigation
import com.operit.timetracker.ui.theme.TimeTrackerTheme
import com.operit.timetracker.ui.viewmodel.TaskViewModel
import com.operit.timetracker.service.AppLogger

class MainActivity : ComponentActivity() {
    private var hasCheckedBatteryOptimization = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TimeTrackerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val taskViewModel: TaskViewModel = viewModel()
                    val mainViewModel: MainViewModel = viewModel()
                    AppNavigation(viewModel = taskViewModel, mainViewModel = mainViewModel)
                }
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        // App每次回到前台时，检查监控服务是否存活，不存活则重启
        try {
            val dataStore = com.operit.timetracker.data.DataStore(this)
            val monitorState = dataStore.loadMonitorState()
            val isMonitoring = monitorState == null || !monitorState.locked
            if (isMonitoring && !com.operit.timetracker.service.AppMonitorService.isRunning(this)) {
                android.util.Log.i("MainActivity", "onResume: 监控服务未运行，自动重启")
                AppLogger.i("MainActivity onResume: 监控服务未运行，自动重启")
                com.operit.timetracker.service.AppMonitorService.start(this)
            } else if (isMonitoring) {
                // 服务在运行，触发一次补检测（可能在其他App停留了很久）
                AppLogger.i("MainActivity onResume: 触发补检测")
            }
            
            // 首次打开时检查电池优化白名单和通知权限
            if (!hasCheckedBatteryOptimization) {
                hasCheckedBatteryOptimization = true
                checkBatteryOptimization()
                checkNotificationPermission()
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "onResume检查失败", e)
            AppLogger.e("MainActivity onResume检查失败", e)
        }
    }
    
    /**
     * 检查是否在电池优化白名单中
     * 如果不在，引导用户手动添加，否则 HyperOS 会冻结服务
     */
    private fun checkBatteryOptimization() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                AppLogger.i("电池优化白名单: 已在白名单中")
                return
            }
            
            AppLogger.w("电池优化白名单: 未在白名单中，建议用户添加")
            android.util.Log.w("MainActivity", "未在电池优化白名单中，建议用户添加")
            
            // 引导用户到电池优化设置页
            // 注意：直接弹 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 需要特殊权限审批
            // 这里引导用户到设置页手动操作更安全
            showBatteryOptimizationDialog()
        } catch (e: Exception) {
            AppLogger.e("检查电池优化失败", e)
        }
    }
    
    private fun showBatteryOptimizationDialog() {
        // 使用 AlertDialog 引导用户
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("保持后台运行")
        builder.setMessage("时间记录助手需要在后台持续运行才能准确记录您的时间。\n\n" +
                "当前未在电池优化白名单中，系统可能会冻结服务导致记录中断。\n\n" +
                "请点击\"前往设置\"，找到\"时间记录\"或\"时间记录助手\"，选择\"无限制\"或\"允许\"。")
        builder.setPositiveButton("前往设置") { _, _ ->
            try {
                // 跳转到电池优化设置页
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(intent)
            } catch (e: Exception) {
                AppLogger.e("跳转电池优化设置失败", e)
                // 降级：跳转到应用详情页
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e2: Exception) {
                    AppLogger.e("跳转应用详情页也失败", e2)
                }
            }
        }
        builder.setNegativeButton("稍后再说") { dialog, _ ->
            dialog.dismiss()
        }
        builder.setCancelable(true)
        builder.show()
    }
    
    /**
     * 检查通知权限是否被禁用
     * HyperOS 会自动禁用后台 App 的通知
     */
    private fun checkNotificationPermission() {
        try {
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            
            // 检查通知总开关
            if (!notificationManager.areNotificationsEnabled()) {
                AppLogger.w("通知权限: 通知总开关已关闭")
                showNotificationPermissionDialog()
                return
            }
            
            // 检查通知渠道
            val channel = notificationManager.getNotificationChannel("app_monitor_channel")
            if (channel != null && channel.importance == android.app.NotificationManager.IMPORTANCE_NONE) {
                AppLogger.w("通知权限: 通知渠道被禁用")
                showNotificationPermissionDialog()
                return
            }
            
            AppLogger.i("通知权限: 正常")
        } catch (e: Exception) {
            AppLogger.e("检查通知权限失败", e)
        }
    }
    
    private fun showNotificationPermissionDialog() {
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("通知权限被关闭")
        builder.setMessage("时间记录助手需要通知权限来显示实时记录状态。\n\n" +
                "HyperOS 可能已自动关闭通知。请点击\"前往设置\"，开启通知权限。")
        builder.setPositiveButton("前往设置") { _, _ ->
            try {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
                startActivity(intent)
            } catch (e: Exception) {
                AppLogger.e("跳转通知设置失败", e)
                // 降级：跳转到应用详情页
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e2: Exception) {
                    AppLogger.e("跳转应用详情页也失败", e2)
                }
            }
        }
        builder.setNegativeButton("稍后再说") { dialog, _ ->
            dialog.dismiss()
        }
        builder.setCancelable(true)
        builder.show()
    }
}