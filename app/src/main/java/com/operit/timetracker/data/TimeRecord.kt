package com.operit.timetracker.data

/**
 * 记录类型：
 * - PRIMARY: 前台主任务（用户正在使用的 app）
 * - COMPANION: 伴随任务（后台有前台服务的 app，如音乐、导航）
 * - AUXILIARY: 辅助任务（小窗运行的 app）
 */
enum class RecordType {
    PRIMARY,    // 前台主任务
    COMPANION,  // 伴随任务（后台前台服务）
    AUXILIARY   // 辅助任务（小窗）
}

data class TimeRecord(
    val id: Long = 0,
    val category: String,
    val startTime: Long,
    val endTime: Long? = null,
    val durationSeconds: Long = 0,
    val originalInput: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val recordType: RecordType = RecordType.PRIMARY // 默认为主任务
)

data class CategoryStat(
    val category: String,
    val totalDuration: Long
)

data class AppStat(
    val packageName: String,
    val totalDuration: Long
)