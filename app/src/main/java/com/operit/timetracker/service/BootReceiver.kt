package com.operit.timetracker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "设备启动完成，准备启动App监控服务")
            
            // 延迟启动服务，确保系统完全启动
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                // 检查是否启用自动启动
                val dataStore = com.operit.timetracker.data.DataStore(context)
                val state = dataStore.loadMonitorState()
                
                if (state == null || !state.locked) {
                    Log.i(TAG, "启动App监控服务")
                    AppMonitorService.start(context)
                } else {
                    Log.i(TAG, "监控服务处于锁定状态，跳过自动启动")
                }
            }, 5000) // 5秒延迟
        }
    }
}