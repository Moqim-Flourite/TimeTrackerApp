package com.operit.timetracker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "设备启动完成，准备启动App监控服务")
            AppLogger.i("BootReceiver: 设备启动完成")
            
            // 延迟启动服务，确保系统完全启动
            Handler(Looper.getMainLooper()).postDelayed({
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
                    // 失败后再次重试
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            AppMonitorService.start(context)
                        } catch (e2: Exception) {
                            Log.e(TAG, "重试启动仍然失败", e2)
                        }
                    }, 10000) // 10秒后重试
                }
            }, 5000) // 5秒延迟
            
            // 再加一个 30 秒后的兜底检查
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    if (!AppMonitorService.isRunning(context)) {
                        val dataStore = com.operit.timetracker.data.DataStore(context)
                        val state = dataStore.loadMonitorState()
                        if (state == null || !state.locked) {
                            Log.w(TAG, "30秒兜底检查：服务仍未运行，强制重启")
                            AppLogger.i("BootReceiver: 30秒兜底重启")
                            AppMonitorService.start(context)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "兜底检查失败", e)
                }
            }, 30000)
        }
    }
}
