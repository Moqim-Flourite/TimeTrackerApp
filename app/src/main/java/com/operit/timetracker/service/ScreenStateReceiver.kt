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
                // 可以在这里处理熄屏事件
                // 比如记录"熄屏"状态
            }
            Intent.ACTION_SCREEN_ON -> {
                Log.i(TAG, "屏幕亮起")
            }
            Intent.ACTION_USER_PRESENT -> {
                Log.i(TAG, "用户解锁")
                // 用户解锁后可以恢复监控
            }
        }
    }
}