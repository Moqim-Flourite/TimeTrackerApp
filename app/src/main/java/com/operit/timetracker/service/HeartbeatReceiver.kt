package com.operit.timetracker.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.operit.timetracker.data.DataStore

/**
 * AlarmManager 心跳接收器
 * 
 * 职责：
 * 1. 检查服务是否存活，不存活则重启（兜底保活）
 * 2. 检测屏幕状态变化（当服务被杀后重启时，弥补动态 receiver 缺失的窗口）
 * 3. 重新调度下一次精确闹钟（setExactAndAllowWhileIdle 不支持 repeating）
 */
class HeartbeatReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "HeartbeatReceiver"
        
        // 进程级记录上次屏幕状态，用于检测变化
        @Volatile
        private var lastKnownScreenOn = true
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "心跳触发，检查服务状态")

        try {
            // 立即重新调度下一次精确闹钟（必须在处理逻辑之前，确保链路不断）
            rescheduleAlarm(context)
            
            val dataStore = DataStore(context)
            val state = dataStore.loadMonitorState()
            val shouldMonitor = state == null || !state.locked

            if (!shouldMonitor) {
                Log.d(TAG, "监控已锁定，跳过")
                return
            }

            // 检查屏幕状态变化
            checkScreenState(context)
            
            // 检查服务是否存活
            if (!AppMonitorService.isRunning(context)) {
                Log.i(TAG, "服务未运行，尝试重启")
                AppLogger.i("HeartbeatReceiver: 服务未运行，尝试重启")
                startServiceSafely(context)
            } else {
                Log.d(TAG, "服务运行正常")
                
                // 深度检查：监控循环是否还在跑
                val lastLoop = AppMonitorService.lastMonitorLoopTimeMs
                val cycleCount = AppMonitorService.monitorLoopCycleCount
                val now = System.currentTimeMillis()
                val staleMs = if (lastLoop > 0) now - lastLoop else -1
                
                // 如果超过 30 秒没有更新，说明监控循环卡死了
                if (lastLoop > 0 && staleMs > 30_000) {
                    AppLogger.e("[WATCHDOG] 监控循环疑似卡死！上次更新: ${staleMs}ms前, 总cycles: $cycleCount")
                    Log.e(TAG, "[WATCHDOG] 监控循环疑似卡死！stale=${staleMs}ms, cycles=$cycleCount")
                    
                    // 尝试重启服务
                    try {
                        AppMonitorService.stop(context)
                        Thread.sleep(500)
                        startServiceSafely(context)
                        AppLogger.i("[WATCHDOG] 已重启服务")
                    } catch (e: Exception) {
                        AppLogger.e("[WATCHDOG] 重启服务失败", e)
                    }
                } else if (lastLoop == -1L) {
                    // lastLoop 为 -1 表示监控循环从未运行过，可能是 WakeLock 被禁
                    AppLogger.w("[WATCHDOG] 监控循环从未运行，尝试重启服务")
                    try {
                        AppMonitorService.stop(context)
                        Thread.sleep(500)
                        startServiceSafely(context)
                    } catch (e: Exception) {
                        AppLogger.e("[WATCHDOG] 重启服务失败", e)
                    }
                } else {
                    AppLogger.d("[WATCHDOG] 监控循环正常: cycle=$cycleCount, stale=${staleMs}ms")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "心跳处理异常", e)
            AppLogger.e("HeartbeatReceiver 异常", e)
        }
    }
    
    /**
     * 安全启动前台服务，处理 Android 12+ 后台启动限制
     */
    private fun startServiceSafely(context: Context) {
        try {
            AppMonitorService.start(context)
        } catch (e: Exception) {
            // Android 12+ 从 BroadcastReceiver 启动前台服务可能被拦截
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && 
                e is ForegroundServiceStartNotAllowedException) {
                AppLogger.e("HeartbeatReceiver: Android 12+ 禁止从后台启动前台服务", e)
                Log.e(TAG, "ForegroundServiceStartNotAllowedException: 后台启动前台服务被拦截")
                
                // 降级方案：使用 WorkManager 调度延迟启动
                scheduleWorkManagerRestart(context)
            } else {
                AppLogger.e("HeartbeatReceiver: 启动服务失败", e)
                Log.e(TAG, "启动服务失败: ${e.message}")
            }
        }
    }
    
    /**
     * 降级方案：通过 WorkManager 调度服务重启
     * WorkManager 有更高的优先级，不受后台启动限制
     */
    private fun scheduleWorkManagerRestart(context: Context) {
        try {
            val workRequest = androidx.work.OneTimeWorkRequestBuilder<ServiceRestartWorker>()
                .setInitialDelay(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            androidx.work.WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "service_restart",
                    androidx.work.ExistingWorkPolicy.REPLACE,
                    workRequest
                )
            AppLogger.i("HeartbeatReceiver: 已通过 WorkManager 调度服务重启")
        } catch (e: Exception) {
            AppLogger.e("HeartbeatReceiver: WorkManager 调度失败", e)
        }
    }
    
    /**
     * 重新调度下一次精确闹钟
     * setExactAndAllowWhileIdle 不支持 repeating，必须手动重新调度
     */
    private fun rescheduleAlarm(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = Intent(context, HeartbeatReceiver::class.java).apply {
                action = AppMonitorService.ACTION_HEARTBEAT
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, AppMonitorService.HEARTBEAT_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            val triggerTime = android.os.SystemClock.elapsedRealtime() + AppMonitorService.ALARM_INTERVAL_MS
            alarmManager.setExactAndAllowWhileIdle(
                android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerTime,
                pendingIntent
            )
        } catch (e: Exception) {
            AppLogger.e("HeartbeatReceiver: 重新调度闹钟失败", e)
        }
    }
    
    /**
     * 检测屏幕状态变化
     * 当服务被杀后由心跳重启时，动态 receiver 还没注册，
     * 这里检测屏幕从灭到亮的变化，触发补检测。
     */
    private fun checkScreenState(context: Context) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val isScreenOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                pm.isInteractive
            } else {
                @Suppress("DEPRECATION")
                pm.isScreenOn
            }
            
            // 屏幕从灭变亮 = 用户刚唤醒设备
            if (isScreenOn && !lastKnownScreenOn) {
                Log.i(TAG, "检测到屏幕亮起（心跳内）")
                AppLogger.i("HeartbeatReceiver: 检测到屏幕亮起，触发补检测")
                
                // 服务刚重启时，让服务自己做补检测
                // 由于服务 onStartCommand 会立即 checkCurrentApp，
                // 这里主要确保服务被杀后亮屏时能及时重启
            }
            
            lastKnownScreenOn = isScreenOn
        } catch (e: Exception) {
            Log.e(TAG, "屏幕状态检测异常", e)
        }
    }
}
