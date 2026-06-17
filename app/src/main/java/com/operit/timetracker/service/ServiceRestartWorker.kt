package com.operit.timetracker.service

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * WorkManager 兜底重启服务
 * 
 * 当 HeartbeatReceiver 因 Android 12+ 后台启动限制无法直接启动前台服务时，
 * 通过 WorkManager 调度此 Worker 来重启服务。
 * 
 * WorkManager 有更高的系统优先级，不受后台启动前台服务的限制。
 */
class ServiceRestartWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    companion object {
        private const val TAG = "ServiceRestartWorker"
    }

    override fun doWork(): Result {
        Log.i(TAG, "WorkManager 触发服务重启")
        AppLogger.i("ServiceRestartWorker: WorkManager 触发服务重启")
        
        return try {
            if (!AppMonitorService.isRunning(applicationContext)) {
                AppMonitorService.start(applicationContext)
                Log.i(TAG, "服务已通过 WorkManager 重启")
                AppLogger.i("ServiceRestartWorker: 服务已重启")
            } else {
                Log.d(TAG, "服务已在运行，跳过重启")
            }
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "WorkManager 重启服务失败: ${e.message}")
            AppLogger.e("ServiceRestartWorker: 重启失败", e)
            Result.retry()
        }
    }
}
