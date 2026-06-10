package com.operit.timetracker.diary

import android.content.Context
import android.content.pm.PackageManager
import com.operit.timetracker.data.DataStore
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 将一天的时间记录拼接成结构化文本，供 DiaryAnalyzer 消费。
 *
 * 输出示例：
 * 2026-06-10 时间记录：
 * 09:00-10:30 工作（微信）
 * 10:30-11:00 学习（网易云音乐）
 */
class DiaryTextBuilder(private val context: Context) {

    private val dataStore = DataStore(context)
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun buildTextForDate(date: LocalDate): String {
        val startOfDay = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endOfDay = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val records = dataStore.loadRecords()
        val dayRecords = records
            .filter { it.endTime != null }
            .filter { record ->
                val effStart = maxOf(record.startTime, startOfDay)
                val effEnd = minOf(record.endTime!!, endOfDay)
                effStart < effEnd
            }
            .sortedBy { it.startTime }

        if (dayRecords.isEmpty()) return "${date} 暂无时间记录"

        val sb = StringBuilder()
        sb.appendLine("${date} 时间记录：")

        for (record in dayRecords) {
            val effStart = maxOf(record.startTime, startOfDay)
            val effEnd = minOf(record.endTime!!, endOfDay)
            val startTime = formatTime(effStart)
            val endTime = formatTime(effEnd)
            val appName = resolveAppName(record.originalInput)
            sb.appendLine("$startTime-$endTime ${record.category}（$appName）")
        }

        return sb.toString().trimEnd()
    }

    private fun resolveAppName(packageName: String): String {
        if (packageName.isBlank()) return "未知"
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName.substringAfterLast('.')
        }
    }

    private fun formatTime(timestamp: Long): String {
        val time = LocalTime.ofInstant(
            java.time.Instant.ofEpochMilli(timestamp),
            ZoneId.systemDefault()
        )
        return time.format(timeFormatter)
    }
}
