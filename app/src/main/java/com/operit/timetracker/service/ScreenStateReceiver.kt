package com.operit.timetracker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ScreenStateReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "ScreenStateReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_OFF -> {
                Log.i(TAG, "屏幕熄灭")
                AppLogger.i("ScreenStateReceiver: 屏幕熄灭")
            }
            Intent.ACTION_SCREEN_ON -> {
                Log.i(TAG, "屏幕亮起")
                AppLogger.i("ScreenStateReceiver: 屏幕亮起")
                
                // 屏幕亮起时，确保服务在运行
                try {
                    if (!AppMonitorService.isRunning(context)) {
                        Log.i(TAG, "屏幕亮起时服务未运行，重启服务")
                        AppLogger.i("ScreenStateReceiver: 服务未运行，重启")
                        AppMonitorService.start(context)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "屏幕亮起处理异常", e)
                    AppLogger.e("ScreenStateReceiver 异常", e)
                }
            }
            Intent.ACTION_USER_PRESENT -> {
                Log.i(TAG, "用户解锁")
                AppLogger.i("ScreenStateReceiver: 用户解锁")
            }
        }
    }
}
