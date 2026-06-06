package com.operit.timetracker.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootReceiver"
        private const val REQUEST_CODE_DELAYED_START = 10001
        private const val REQUEST_CODE_FALLBACK = 10002
        private const val DELAYED_START_MS = 5_000L   // 5秒后启动
        private const val FALLBACK_CHECK_MS = 30_000L // 30秒兜底
        
        const val ACTION_DELAYED_START = "com.operit.timetracker.ACTION_DELAYED_START"
        const val ACTION_FALLBACK_CHECK = "com.operit.timetracker.ACTION_FALLBACK_CHECK"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.i(TAG, "设备启动完成，准备启动App监控服务")
                AppLogger.i("BootReceiver: 设备启动完成")
                
                // 用 AlarmManager 调度延迟启动，比 Handler.postDelayed 可靠
                // Handler 依赖进程存活，onReceive 返回后进程可能被杀（尤其 HyperOS）
                // AlarmManager 由系统调度，跨进程可靠
                scheduleDelayedStart(context)
                scheduleFallbackCheck(context)
            }
            ACTION_DELAYED_START -> {
                Log.i(TAG, "延迟启动触发")
                AppLogger.i("BootReceiver: 延迟启动触发")
                tryStartService(context)
            }
            ACTION_FALLBACK_CHECK -> {
                Log.i(TAG, "30秒兜底检查触发")
                AppLogger.i("BootReceiver: 30秒兜底检查")
                try {
                    if (!AppMonitorService.isRunning(context)) {
                        val dataStore = com.operit.timetracker.data.DataStore(context)
                        val state = dataStore.loadMonitorState()
                        if (state == null || !state.locked) {
                            Log.w(TAG, "兜底检查：服务仍未运行，强制启动")
                            AppLogger.i("BootReceiver: 兜底强制启动")
                            AppMonitorService.start(context)
                        }
                    } else {
                        Log.i(TAG, "兜底检查：服务运行正常")
                        AppLogger.i("BootReceiver: 兜底检查通过")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "兜底检查失败", e)
                    AppLogger.e("BootReceiver: 兜底检查失败", e)
                }
            }
        }
    }
    
    private fun scheduleDelayedStart(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, BootReceiver::class.java).apply {
            action = ACTION_DELAYED_START
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, REQUEST_CODE_DELAYED_START, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val triggerAt = SystemClock.elapsedRealtime() + DELAYED_START_MS
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            triggerAt,
            pendingIntent
        )
        Log.i(TAG, "延迟启动已调度: ${DELAYED_START_MS}ms 后")
        AppLogger.i("BootReceiver: 延迟启动已调度 ${DELAYED_START_MS}ms")
    }
    
    private fun scheduleFallbackCheck(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, BootReceiver::class.java).apply {
            action = ACTION_FALLBACK_CHECK
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, REQUEST_CODE_FALLBACK, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val triggerAt = SystemClock.elapsedRealtime() + FALLBACK_CHECK_MS
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            triggerAt,
            pendingIntent
        )
        Log.i(TAG, "兜底检查已调度: ${FALLBACK_CHECK_MS}ms 后")
        AppLogger.i("BootReceiver: 兜底检查已调度 ${FALLBACK_CHECK_MS}ms")
    }
    
    private fun tryStartService(context: Context) {
        try {
            val dataStore = com.operit.timetracker.data.DataStore(context)
            val state = dataStore.loadMonitorState()
            
            if (state == null || !state.locked) {
                Log.i(TAG, "启动App监控服务")
                AppLogger.i("BootReceiver: 启动App监控服务")
                AppMonitorService.start(context)
            } else {
                Log.i(TAG, "监控服务处于锁定状态，跳过自动启动")
                AppLogger.i("BootReceiver: 监控已锁定，跳过")
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动服务失败", e)
            AppLogger.e("BootReceiver: 启动服务失败", e)
        }
    }
}
