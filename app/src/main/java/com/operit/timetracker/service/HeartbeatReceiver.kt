package com.operit.timetracker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * AlarmManager 心跳接收器
 * 用于兜底保活：当服务被系统杀死后，通过定期闹钟重启服务
 */
class HeartbeatReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "HeartbeatReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "心跳触发，检查服务状态")

        try {
            // 检查监控是否应该运行
            val dataStore = com.operit.timetracker.data.DataStore(context)
            val state = dataStore.loadMonitorState()
            val shouldMonitor = state == null || !state.locked

            if (shouldMonitor && !AppMonitorService.isRunning(context)) {
                Log.i(TAG, "服务未运行，尝试重启")
                AppLogger.i("HeartbeatReceiver: 服务未运行，尝试重启")
                AppMonitorService.start(context)
            } else {
                Log.d(TAG, "服务运行正常或监控已锁定")
            }
        } catch (e: Exception) {
            Log.e(TAG, "心跳处理异常", e)
            AppLogger.e("HeartbeatReceiver 异常", e)
        }
    }
}
