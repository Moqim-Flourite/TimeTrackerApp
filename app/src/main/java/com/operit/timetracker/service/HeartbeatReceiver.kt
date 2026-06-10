package com.operit.timetracker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * AlarmManager 心跳接收器
 * 
 * 职责：
 * 1. 检查服务是否存活，不存活则重启（兜底保活）
 * 2. 检测屏幕状态变化（当服务被杀后重启时，弥补动态 receiver 缺失的窗口）
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
            val dataStore = com.operit.timetracker.data.DataStore(context)
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
                AppMonitorService.start(context)
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
                        AppMonitorService.start(context)
                        AppLogger.i("[WATCHDOG] 已重启服务")
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
     * 检测屏幕状态变化
     * 当服务被杀后由心跳重启时，动态 receiver 还没注册，
     * 这里检测屏幕从灭到亮的变化，触发补检测。
     */
    private fun checkScreenState(context: Context) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val isScreenOn = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT_WATCH) {
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
