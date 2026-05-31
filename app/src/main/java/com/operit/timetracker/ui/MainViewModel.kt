package com.operit.timetracker.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.operit.timetracker.data.DataStore
import com.operit.timetracker.data.MonitorState
import com.operit.timetracker.data.TimeRecord
import com.operit.timetracker.service.AppLogger
import com.operit.timetracker.service.AppMonitorService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.*

class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    private val dataStore = DataStore(application)
    
    // 当前任务状态
    private val _currentTask = MutableStateFlow<TimeRecord?>(null)
    val currentTask: StateFlow<TimeRecord?> = _currentTask.asStateFlow()
    
    // 监控服务状态
    private val _monitorState = MutableStateFlow<MonitorState?>(null)
    val monitorState: StateFlow<MonitorState?> = _monitorState.asStateFlow()
    
    // 监控服务是否运行
    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring.asStateFlow()
    
    // 权限状态
    private val _hasUsageStatsPermission = MutableStateFlow(false)
    val hasUsageStatsPermission: StateFlow<Boolean> = _hasUsageStatsPermission.asStateFlow()
    
    // 最近记录
    private val _recentRecords = MutableStateFlow<List<TimeRecord>>(emptyList())
    val recentRecords: StateFlow<List<TimeRecord>> = _recentRecords.asStateFlow()
    
    // 统计数据
    private val _todayStats = MutableStateFlow<Map<String, Long>>(emptyMap())
    val todayStats: StateFlow<Map<String, Long>> = _todayStats.asStateFlow()
    
    private val _weekStats = MutableStateFlow<Map<String, Long>>(emptyMap())
    val weekStats: StateFlow<Map<String, Long>> = _weekStats.asStateFlow()
    
    init {
        loadData()
        checkPermissions()
    }
    
    fun loadData() {
        viewModelScope.launch {
            // 加载当前任务
            _currentTask.value = dataStore.loadCurrentTask()
            
            // 加载监控状态
            _monitorState.value = dataStore.loadMonitorState()
            
            // 检查监控服务是否运行
            checkMonitoringService()
            
            // 加载最近记录
            val records = dataStore.loadRecords()
            _recentRecords.value = records.takeLast(20).reversed()
            
            // 计算今日统计
            val calendar = Calendar.getInstance()
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            val todayStart = calendar.timeInMillis
            
            val todayStats = mutableMapOf<String, Long>()
            for (record in records) {
                if (record.startTime >= todayStart && record.endTime != null) {
                    val current = todayStats[record.category] ?: 0
                    todayStats[record.category] = current + record.durationSeconds
                }
            }
            _todayStats.value = todayStats
            
            // 计算本周统计
            calendar.set(Calendar.DAY_OF_WEEK, calendar.firstDayOfWeek)
            val weekStart = calendar.timeInMillis
            
            val weekStats = mutableMapOf<String, Long>()
            for (record in records) {
                if (record.startTime >= weekStart && record.endTime != null) {
                    val current = weekStats[record.category] ?: 0
                    weekStats[record.category] = current + record.durationSeconds
                }
            }
            _weekStats.value = weekStats
        }
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
    
    fun addManualRecord(category: String, startTime: Long, endTime: Long) {
        val durationSeconds = (endTime - startTime) / 1000
        val record = TimeRecord(
            id = 0,
            category = category,
            startTime = startTime,
            endTime = endTime,
            durationSeconds = durationSeconds,
            originalInput = "手动添加",
            createdAt = System.currentTimeMillis()
        )
        
        dataStore.addRecord(record)
        loadData() // 刷新数据
    }
    
    private fun checkMonitoringService() {
        // 检查服务是否真的在运行
        val state = _monitorState.value
        _isMonitoring.value = state == null || !state.locked
        
        // 如果状态显示在监控但服务已停止，自动重启
        if (_isMonitoring.value && !AppMonitorService.isRunning(getApplication())) {
            AppLogger.i("ViewModel: 监控状态为运行但服务已停止，自动重启")
            AppMonitorService.start(getApplication())
        }
    }
    
    /**
     * App重新进入前台时调用，确保服务和通知正常
     */
    fun onAppResumed() {
        if (_isMonitoring.value) {
            if (!AppMonitorService.isRunning(getApplication())) {
                AppLogger.i("App resumed: 服务未运行，重新启动")
                AppMonitorService.start(getApplication())
            }
        }
        loadData()
    }
}