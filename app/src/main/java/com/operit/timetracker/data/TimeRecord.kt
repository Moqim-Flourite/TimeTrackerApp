package com.operit.timetracker.data

data class TimeRecord(
    val id: Long = 0,
    val category: String,
    val startTime: Long,
    val endTime: Long? = null,
    val durationSeconds: Long = 0,
    val originalInput: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

data class CategoryStat(
    val category: String,
    val totalDuration: Long
)