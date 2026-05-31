package com.operit.timetracker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 屏幕状态接收器（仅通过动态注册使用）
 * 
 * Android 8+ 不再向静态注册的 receiver 投递 SCREEN_ON/OFF 隐式广播，
 * 所以本类只在 AppMonitorService.onCreate 中动态注册，随服务生命周期存在。
 */
class ScreenStateReceiver(
    private val service: AppMonitorService? = null
) : BroadcastReceiver() {
    
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
                // 调用服务的补检测逻辑（5分钟窗口）
                service?.onScreenOn()
            }
            Intent.ACTION_USER_PRESENT -> {
                Log.i(TAG, "用户解锁")
                AppLogger.i("ScreenStateReceiver: 用户解锁")
                // 用户解锁后再补一次，确保拿到正确的前台App
                service?.onScreenOn()
            }
        }
    }
}
