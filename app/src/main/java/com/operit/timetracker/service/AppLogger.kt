package com.operit.timetracker.service

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object AppLogger {
    private const val TAG = "TimeTracker"
    private var logFile: File? = null
    
    fun init(logDir: File) {
        logFile = File(logDir, "monitor_log.txt")
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
        i("=== 日志系统初始化 ===")
        i("日志文件: ${logFile?.absolutePath}")
    }
    
    fun i(message: String) {
        Log.i(TAG, message)
        writeLog("I", message)
    }
    
    fun d(message: String) {
        Log.d(TAG, message)
        writeLog("D", message)
    }
    
    fun e(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
        writeLog("E", message + (throwable?.let { "\n${it.stackTraceToString()}" } ?: ""))
    }
    
    fun w(message: String) {
        Log.w(TAG, message)
        writeLog("W", message)
    }
    
    private fun writeLog(level: String, message: String) {
        try {
            val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            val timestamp = sdf.format(Date())
            val line = "$timestamp [$level] $message\n"
            logFile?.appendText(line)
        } catch (e: Exception) {
            Log.e(TAG, "写入日志失败", e)
        }
    }
    
    fun getRecentLogs(lines: Int = 100): String {
        return try {
            val file = logFile ?: return "日志文件未初始化"
            if (!file.exists()) return "日志文件不存在"
            
            val allLines = file.readLines()
            val recentLines = if (allLines.size > lines) {
                allLines.takeLast(lines)
            } else {
                allLines
            }
            recentLines.joinToString("\n")
        } catch (e: Exception) {
            "读取日志失败: ${e.message}"
        }
    }
    
    fun clearLogs() {
        try {
            logFile?.writeText("")
            i("日志已清空")
        } catch (e: Exception) {
            Log.e(TAG, "清空日志失败", e)
        }
    }
}