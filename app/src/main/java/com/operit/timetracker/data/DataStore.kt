package com.operit.timetracker.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 基于 JSON 文件的数据存储层
 * 替代 Room 数据库，避免 KSP 依赖问题
 */
class DataStore(context: Context) {
    private val dataDir = File(context.filesDir, "timetracker")
    private val recordsFile = File(dataDir, "records.json")
    private val stateFile = File(dataDir, "state.json")
    
    init {
        if (!dataDir.exists()) {
            dataDir.mkdirs()
        }
    }
    
    // ========== 记录操作 ==========
    
    fun loadRecords(): MutableList<TimeRecord> {
        if (!recordsFile.exists()) return mutableListOf()
        
        return try {
            val text = recordsFile.readText()
            if (text.isBlank()) return mutableListOf()
            
            val array = JSONArray(text)
            val records = mutableListOf<TimeRecord>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                records.add(
                    TimeRecord(
                        id = obj.getLong("id"),
                        category = obj.getString("category"),
                        startTime = obj.getLong("startTime"),
                        endTime = if (obj.isNull("endTime")) null else obj.getLong("endTime"),
                        durationSeconds = obj.getLong("durationSeconds"),
                        originalInput = obj.optString("originalInput", ""),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }
            records
        } catch (e: Exception) {
            mutableListOf()
        }
    }
    
    fun saveRecords(records: List<TimeRecord>) {
        val array = JSONArray()
        for (record in records) {
            val obj = JSONObject().apply {
                put("id", record.id)
                put("category", record.category)
                put("startTime", record.startTime)
                put("endTime", if (record.endTime != null) record.endTime else JSONObject.NULL)
                put("durationSeconds", record.durationSeconds)
                put("originalInput", record.originalInput)
                put("createdAt", record.createdAt)
            }
            array.put(obj)
        }
        recordsFile.writeText(array.toString(2))
    }
    
    fun addRecord(record: TimeRecord): TimeRecord {
        val records = loadRecords()
        val newId = if (records.isEmpty()) 1L else (records.maxOf { it.id } + 1)
        val newRecord = record.copy(id = newId)
        records.add(newRecord)
        saveRecords(records)
        return newRecord
    }
    
    fun updateRecord(record: TimeRecord) {
        val records = loadRecords()
        val index = records.indexOfFirst { it.id == record.id }
        if (index >= 0) {
            records[index] = record
            saveRecords(records)
        }
    }
    
    fun deleteRecord(id: Long) {
        val records = loadRecords()
        records.removeAll { it.id == id }
        saveRecords(records)
    }
    
    // ========== 当前任务状态 ==========
    
    fun saveCurrentTask(record: TimeRecord?) {
        val obj = if (record != null) {
            JSONObject().apply {
                put("id", record.id)
                put("category", record.category)
                put("startTime", record.startTime)
                put("originalInput", record.originalInput)
                put("createdAt", record.createdAt)
            }
        } else {
            JSONObject.NULL
        }
        stateFile.writeText(obj.toString())
    }
    
    fun loadCurrentTask(): TimeRecord? {
        if (!stateFile.exists()) return null
        
        return try {
            val text = stateFile.readText()
            if (text == "null" || text.isBlank()) return null
            
            val obj = JSONObject(text)
            TimeRecord(
                id = obj.getLong("id"),
                category = obj.getString("category"),
                startTime = obj.getLong("startTime"),
                originalInput = obj.optString("originalInput", ""),
                createdAt = obj.optLong("createdAt", System.currentTimeMillis())
            )
        } catch (e: Exception) {
            null
        }
    }
    
    // ========== 统计查询 ==========
    
    fun getCategoryStats(startOfDay: Long? = null, endOfDay: Long? = null): List<CategoryStat> {
        val records = loadRecords()
        val stats = mutableMapOf<String, Long>()
        
        for (record in records) {
            if (record.endTime == null) continue
            
            // 跨日分摊：如果任务跨越了统计边界，按比例分摊
            val effectiveStart = if (startOfDay != null) maxOf(record.startTime, startOfDay) else record.startTime
            val effectiveEnd = if (endOfDay != null) minOf(record.endTime, endOfDay) else record.endTime
            
            if (effectiveStart >= effectiveEnd) continue // 完全在范围外
            
            val durationSeconds = (effectiveEnd - effectiveStart) / 1000
            if (durationSeconds <= 0) continue
            
            val current = stats[record.category] ?: 0
            stats[record.category] = current + durationSeconds
        }
        
        return stats.map { CategoryStat(it.key, it.value) }
            .sortedByDescending { it.totalDuration }
    }
    
    /**
     * 按 App（包名）统计使用时长
     */
    fun getAppStats(startOfDay: Long? = null, endOfDay: Long? = null): List<AppStat> {
        val records = loadRecords()
        val stats = mutableMapOf<String, Long>()
        
        for (record in records) {
            if (record.endTime == null) continue
            if (record.originalInput.isBlank()) continue
            
            // 跨日分摊
            val effectiveStart = if (startOfDay != null) maxOf(record.startTime, startOfDay) else record.startTime
            val effectiveEnd = if (endOfDay != null) minOf(record.endTime, endOfDay) else record.endTime
            
            if (effectiveStart >= effectiveEnd) continue
            
            val durationSeconds = (effectiveEnd - effectiveStart) / 1000
            if (durationSeconds <= 0) continue
            
            val current = stats[record.originalInput] ?: 0
            stats[record.originalInput] = current + durationSeconds
        }
        
        return stats.map { AppStat(it.key, it.value) }
            .sortedByDescending { it.totalDuration }
    }
    
    /**
     * 找到当前睡眠周期的起始时间
     * 以「睡觉」事件作为一天的分界线：
     * - 凌晨 0-5 点：找昨晚 21 点后的睡觉
     * - 白天 5-21 点：找今天凌晨的睡觉，没有则找昨晚的
     * - 晚上 21-24 点：找今天凌晨的睡觉
     * 
     * @return 睡眠周期起始时间戳，如果没找到睡觉记录返回 null
     */
    fun findSleepCycleStart(): Long? {
        val records = loadRecords()
        val now = System.currentTimeMillis()
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = now
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        
        // 定义夜间时段
        val nightStart = 21 // 晚上 9 点
        val nightEnd = 5    // 凌晨 5 点
        
        // 找最近的「睡觉」记录
        val sleepRecords = records
            .filter { it.category == "睡觉" && it.endTime != null }
            .sortedByDescending { it.startTime }
        
        if (sleepRecords.isEmpty()) return null
        
        // 根据当前时间确定查找范围
        val todayMidnight = cal.apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        
        return when {
            hour < nightEnd -> {
                // 凌晨 0-5 点：找昨晚 21 点后的睡觉
                val lastNightStart = todayMidnight - (24 - nightStart) * 3600 * 1000L
                sleepRecords.firstOrNull { it.startTime >= lastNightStart }?.startTime
            }
            hour < nightStart -> {
                // 白天 5-21 点：先找今天凌晨的，再找昨晚的
                val todaySleep = sleepRecords.firstOrNull { it.startTime >= todayMidnight }
                if (todaySleep != null) {
                    todaySleep.startTime
                } else {
                    val lastNightStart = todayMidnight - (24 - nightStart) * 3600 * 1000L
                    sleepRecords.firstOrNull { it.startTime >= lastNightStart }?.startTime
                }
            }
            else -> {
                // 晚上 21-24 点：找今天凌晨的睡觉
                sleepRecords.firstOrNull { it.startTime >= todayMidnight }?.startTime
            }
        }
    }
    
    // ========== 监控状态管理 ==========
    
    private val monitorStateFile = File(dataDir, "monitor_state.json")
    private val appCategoryMapFile = File(dataDir, "app_category_map.json")
    
    fun saveMonitorState(state: MonitorState) {
        val obj = JSONObject().apply {
            put("locked", state.locked)
            put("taskName", state.taskName)
            put("reason", state.reason)
            put("lockedAt", state.lockedAt)
        }
        monitorStateFile.writeText(obj.toString())
    }
    
    fun loadMonitorState(): MonitorState? {
        if (!monitorStateFile.exists()) return null
        
        return try {
            val text = monitorStateFile.readText()
            if (text.isBlank()) return null
            
            val obj = JSONObject(text)
            MonitorState(
                locked = obj.optBoolean("locked", false),
                taskName = obj.optString("taskName", ""),
                reason = obj.optString("reason", ""),
                lockedAt = obj.optLong("lockedAt", 0)
            )
        } catch (e: Exception) {
            null
        }
    }
    
    fun saveAppCategoryMap(map: Map<String, String>) {
        val obj = JSONObject()
        for ((key, value) in map) {
            obj.put(key, value)
        }
        appCategoryMapFile.writeText(obj.toString(2))
    }
    
    fun loadAppCategoryMap(): Map<String, String> {
        if (!appCategoryMapFile.exists()) return emptyMap()
        
        return try {
            val text = appCategoryMapFile.readText()
            if (text.isBlank()) return emptyMap()
            
            val obj = JSONObject(text)
            val map = mutableMapOf<String, String>()
            for (key in obj.keys()) {
                map[key] = obj.getString(key)
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }
}

// ========== 监控状态 ==========

data class MonitorState(
    val locked: Boolean = false,
    val taskName: String = "",
    val reason: String = "",
    val lockedAt: Long = 0
)