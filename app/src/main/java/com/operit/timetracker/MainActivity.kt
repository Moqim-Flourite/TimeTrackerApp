package com.operit.timetracker

import android.os.Bundle
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
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "onResume检查失败", e)
            AppLogger.e("MainActivity onResume检查失败", e)
        }
    }
}