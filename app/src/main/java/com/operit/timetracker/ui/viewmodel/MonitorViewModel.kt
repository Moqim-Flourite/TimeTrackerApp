package com.operit.timetracker.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.operit.timetracker.data.DataStore
import com.operit.timetracker.data.MonitorState
import com.operit.timetracker.data.TimeRecord
import com.operit.timetracker.service.AppMonitorService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MonitorViewModel(application: Application) : AndroidViewModel(application) {
    
    private val dataStore = DataStore(application)
    
    // 监控服务状态
    private val _monitorState = MutableStateFlow<MonitorState?>(null)
    val monitorState: StateFlow<MonitorState?> = _monitorState.asStateFlow()
    
    // 监控服务是否运行
    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring.asStateFlow()
    
    // 权限状态
    private val _hasUsageStatsPermission = MutableStateFlow(false)
    val hasUsageStatsPermission: StateFlow<Boolean> = _hasUsageStatsPermission.asStateFlow()
    
    // 当前任务（用于显示当前监控的应用）
    private val _currentTask = MutableStateFlow<TimeRecord?>(null)
    val currentTask: StateFlow<TimeRecord?> = _currentTask.asStateFlow()
    
    init {
        checkPermissions()
        loadMonitorState()
    }
    
    fun checkPermissions() {
        viewModelScope.launch {
            // 检查UsageStats权限
            val appOps = getApplication<Application>().getSystemService(android.app.AppOpsManager::class.java)
            val mode = appOps.unsafeCheckOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                getApplication<Application>().packageName
            )
            _hasUsageStatsPermission.value = mode == android.app.AppOpsManager.MODE_ALLOWED
        }
    }
    
    fun requestUsageStatsPermission() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        getApplication<Application>().startActivity(intent)
    }
    
    fun loadMonitorState() {
        viewModelScope.launch {
            // 加载监控状态
            _monitorState.value = dataStore.loadMonitorState()
            
            // 检查监控服务是否运行
            checkMonitoringService()
            
            // 加载当前任务
            _currentTask.value = dataStore.loadCurrentTask()
        }
    }
    
    fun toggleMonitoring() {
        if (_isMonitoring.value) {
            stopMonitoring()
        } else {
            startMonitoring()
        }
    }
    
    fun startMonitoring() {
        AppMonitorService.start(getApplication())
        _isMonitoring.value = true
        
        // 更新监控状态
        val state = _monitorState.value ?: MonitorState()
        dataStore.saveMonitorState(state.copy(locked = false))
        _monitorState.value = state.copy(locked = false)
    }
    
    fun stopMonitoring() {
        AppMonitorService.stop(getApplication())
        _isMonitoring.value = false
        
        // 更新监控状态
        val state = _monitorState.value ?: MonitorState()
        dataStore.saveMonitorState(state.copy(locked = true))
        _monitorState.value = state.copy(locked = true)
    }
    
    fun lockMonitoring(taskName: String, reason: String) {
        val state = MonitorState(
            locked = true,
            taskName = taskName,
            reason = reason,
            lockedAt = System.currentTimeMillis()
        )
        dataStore.saveMonitorState(state)
        _monitorState.value = state
        
        // 停止监控服务
        AppMonitorService.stop(getApplication())
        _isMonitoring.value = false
    }
    
    fun unlockMonitoring() {
        val state = _monitorState.value ?: MonitorState()
        dataStore.saveMonitorState(state.copy(locked = false, lockedAt = 0))
        _monitorState.value = state.copy(locked = false, lockedAt = 0)
        
        // 启动监控服务
        AppMonitorService.start(getApplication())
        _isMonitoring.value = true
    }
    
    private fun checkMonitoringService() {
        // 这里可以检查服务是否真的在运行
        // 简单起见，我们假设如果状态是unlocked，则服务在运行
        val state = _monitorState.value
        _isMonitoring.value = state == null || !state.locked
    }
}