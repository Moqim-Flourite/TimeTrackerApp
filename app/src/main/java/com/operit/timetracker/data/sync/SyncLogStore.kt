package com.operit.timetracker.data.sync

import android.content.Context
import android.content.SharedPreferences

/**
 * 同步日志存储
 *
 * 记录每个日期的同步状态，用于补传逻辑。
 * 每个日期记录同步时间戳（0 表示未同步）。
 */
class SyncLogStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 检查某个日期是否已同步
     * @param dateStr 日期字符串，格式 "yyyy-MM-dd"
     */
    fun isSynced(dateStr: String): Boolean {
        return prefs.getLong(dateStr, 0) > 0
    }

    /**
     * 标记某个日期已同步
     * @param dateStr 日期字符串
     * @param timestamp 同步时间戳（毫秒）
     */
    fun markSynced(dateStr: String, timestamp: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(dateStr, timestamp).apply()
    }

    /**
     * 清除某个日期的同步记录（用于重新上传）
     */
    fun clearDate(dateStr: String) {
        prefs.edit().remove(dateStr).apply()
    }

    /**
     * 获取所有已同步日期及其时间戳
     */
    fun getAllSyncedDates(): Map<String, Long> {
        return prefs.all
            .filterValues { it is Long && it > 0 }
            .mapValues { it.value as Long }
    }

    /**
     * 清除所有同步记录
     */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "sync_log"
    }
}
