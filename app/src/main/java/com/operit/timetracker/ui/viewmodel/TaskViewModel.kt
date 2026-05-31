package com.operit.timetracker.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.operit.timetracker.data.AppStat
import com.operit.timetracker.data.CategoryStat
import com.operit.timetracker.data.DataStore
import com.operit.timetracker.data.TimeRecord
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class TaskViewModel(application: Application) : AndroidViewModel(application) {
    private val dataStore = DataStore(application)

    // 当前任务状态
    private val _currentTask = MutableStateFlow<TimeRecord?>(null)
    val currentTask: StateFlow<TimeRecord?> = _currentTask.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    // 当前任务持续时间（秒）
    private val _currentDuration = MutableStateFlow(0L)
    val currentDuration: StateFlow<Long> = _currentDuration.asStateFlow()

    private val _currentFormattedTime = MutableStateFlow("00:00:00")
    val currentFormattedTime: StateFlow<String> = _currentFormattedTime.asStateFlow()

    // 统计数据
    private val _dailyStats = MutableStateFlow<List<CategoryStat>>(emptyList())
    val dailyStats: StateFlow<List<CategoryStat>> = _dailyStats.asStateFlow()

    private val _weeklyStats = MutableStateFlow<List<CategoryStat>>(emptyList())
    val weeklyStats: StateFlow<List<CategoryStat>> = _weeklyStats.asStateFlow()

    private val _totalStats = MutableStateFlow<List<CategoryStat>>(emptyList())
    val totalStats: StateFlow<List<CategoryStat>> = _totalStats.asStateFlow()
    
    // App 维度统计
    private val _dailyAppStats = MutableStateFlow<List<AppStat>>(emptyList())
    val dailyAppStats: StateFlow<List<AppStat>> = _dailyAppStats.asStateFlow()
    
    private val _weeklyAppStats = MutableStateFlow<List<AppStat>>(emptyList())
    val weeklyAppStats: StateFlow<List<AppStat>> = _weeklyAppStats.asStateFlow()
    
    private val _totalAppStats = MutableStateFlow<List<AppStat>>(emptyList())
    val totalAppStats: StateFlow<List<AppStat>> = _totalAppStats.asStateFlow()

    // 所有记录
    private val _allRecords = MutableStateFlow<List<TimeRecord>>(emptyList())
    val allRecords: StateFlow<List<TimeRecord>> = _allRecords.asStateFlow()

    // 消息状态
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        // 恢复上次进行中的任务
        val saved = dataStore.loadCurrentTask()
        if (saved != null) {
            _currentTask.value = saved
            _isRunning.value = true
        }

        // 启动时间更新
        startTimeUpdate()
        loadStats()
        loadAllRecords()
    }

    private fun startTimeUpdate() {
        viewModelScope.launch {
            while (true) {
                // 定期从 DataStore 同步当前任务（服务可能已创建新任务）
                val dataTask = dataStore.loadCurrentTask()
                val localTask = _currentTask.value
                
                if (dataTask != null && (localTask == null || localTask.id != dataTask.id)) {
                    // 服务创建了新任务，同步到 UI
                    _currentTask.value = dataTask
                    _isRunning.value = true
                    loadStats()
                    loadAllRecords()
                } else if (dataTask == null && localTask != null) {
                    // 服务停止了任务
                    _currentTask.value = null
                    _isRunning.value = false
                    loadStats()
                    loadAllRecords()
                }
                
                if (_isRunning.value && _currentTask.value != null) {
                    val now = System.currentTimeMillis()
                    val duration = (now - _currentTask.value!!.startTime) / 1000
                    _currentDuration.value = duration
                    _currentFormattedTime.value = formatDuration(duration)
                } else {
                    _currentDuration.value = 0
                    _currentFormattedTime.value = "00:00:00"
                }
                delay(1000)
            }
        }
    }

    fun startTask(taskName: String) {
        viewModelScope.launch {
            try {
                // 先停止当前任务
                stopCurrentTaskInternal()

                val now = System.currentTimeMillis()
                val record = TimeRecord(
                    id = 0, // 会由 DataStore 分配
                    category = taskName,
                    startTime = now,
                    endTime = null,
                    durationSeconds = 0,
                    originalInput = taskName
                )
                val saved = dataStore.addRecord(record)
                dataStore.saveCurrentTask(saved)

                _currentTask.value = saved
                _isRunning.value = true
                _message.value = "✅ 已开始记录：${saved.category}"

                loadStats()
                loadAllRecords()
            } catch (e: Exception) {
                _message.value = "❌ 启动任务失败：${e.message}"
            }
        }
    }

    fun stopCurrentTask() {
        viewModelScope.launch {
            val result = stopCurrentTaskInternal()
            if (result != null) {
                _message.value = "✅ 已结束：${result.category}，用时：${formatDuration(result.durationSeconds)}"
                loadStats()
                loadAllRecords()
            } else {
                _message.value = "⚠️ 没有运行中的任务"
            }
        }
    }

    private fun stopCurrentTaskInternal(): TimeRecord? {
        val current = _currentTask.value ?: return null

        val now = System.currentTimeMillis()
        val durationSeconds = (now - current.startTime) / 1000

        val updated = current.copy(
            endTime = now,
            durationSeconds = durationSeconds
        )
        dataStore.updateRecord(updated)
        dataStore.saveCurrentTask(null)

        _currentTask.value = null
        _isRunning.value = false
        return updated
    }

    fun deleteRecord(record: TimeRecord) {
        viewModelScope.launch {
            try {
                dataStore.deleteRecord(record.id)
                _message.value = "✅ 已删除记录：${record.category}"
                loadStats()
                loadAllRecords()
            } catch (e: Exception) {
                _message.value = "❌ 删除失败：${e.message}"
            }
        }
    }

    fun loadStats() {
        viewModelScope.launch {
            try {
                // 今日
                val cal = Calendar.getInstance()
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val startOfDay = cal.timeInMillis
                val endOfDay = startOfDay + 24 * 60 * 60 * 1000

                _dailyStats.value = dataStore.getCategoryStats(startOfDay, endOfDay)
                _dailyAppStats.value = dataStore.getAppStats(startOfDay, endOfDay)

                // 本周
                val calWeek = Calendar.getInstance()
                calWeek.set(Calendar.DAY_OF_WEEK, calWeek.firstDayOfWeek)
                calWeek.set(Calendar.HOUR_OF_DAY, 0)
                calWeek.set(Calendar.MINUTE, 0)
                calWeek.set(Calendar.SECOND, 0)
                calWeek.set(Calendar.MILLISECOND, 0)
                _weeklyStats.value = dataStore.getCategoryStats(startOfDay = calWeek.timeInMillis)
                _weeklyAppStats.value = dataStore.getAppStats(startOfDay = calWeek.timeInMillis)

                // 总计
                _totalStats.value = dataStore.getCategoryStats()
                _totalAppStats.value = dataStore.getAppStats()
            } catch (e: Exception) {
                _message.value = "❌ 加载统计失败：${e.message}"
            }
        }
    }

    fun loadAllRecords() {
        viewModelScope.launch {
            try {
                _allRecords.value = dataStore.loadRecords().sortedByDescending { it.startTime }
            } catch (e: Exception) {
                _message.value = "❌ 加载记录失败：${e.message}"
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }
    
    fun setMessage(msg: String) {
        _message.value = msg
    }

    fun formatDuration(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, secs)
    }

    fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    }

    fun formatDate(timestamp: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    }

    fun formatDurationText(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return when {
            hours > 0 -> "${hours}小时${minutes}分钟"
            minutes > 0 -> "${minutes}分钟"
            else -> "${secs}秒"
        }
    }
    
    fun getAppName(packageName: String): String {
        return try {
            val pm = getApplication<Application>().packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }
}