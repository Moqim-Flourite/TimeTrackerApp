package com.operit.timetracker.data.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 同步 Worker
 *
 * 使用 WorkManager 调度，每天执行一次同步。
 * 约束条件：WiFi 可用时才触发。
 *
 * 两种模式：
 * 1. 定时同步：每天一次，PeriodicWorkRequest
 * 2. 手动触发：OneTimeWorkRequest
 */
class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "SyncWorker"
        const val PERIODIC_WORK_NAME = "daily_sync"
        const val MANUAL_WORK_NAME = "manual_sync"
        const val KEY_SYNC_RESULT = "sync_result"
        const val KEY_SYNCED_COUNT = "synced_count"
        const val KEY_ERROR_MESSAGE = "error_message"

        /**
         * 注册定时同步任务（每天一次）
         */
        fun schedulePeriodicSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED) // WiFi
                .build()

            val workRequest = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints)
                .setInitialDelay(calculateInitialDelay(), TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )

            Log.i(TAG, "定时同步任务已注册")
        }

        /**
         * 取消定时同步
         */
        fun cancelPeriodicSync(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
            Log.i(TAG, "定时同步任务已取消")
        }

        /**
         * 手动触发一次同步
         */
        fun triggerManualSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED) // WiFi
                .build()

            val workRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                MANUAL_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )

            Log.i(TAG, "手动同步已触发")
        }

        /**
         * 检查同步任务是否正在运行
         */
        fun isSyncing(context: Context): Boolean {
            val workInfos = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(MANUAL_WORK_NAME)
                .get()
            return workInfos.any { it.state == WorkInfo.State.RUNNING }
        }

        /**
         * 计算到下一个凌晨 3 点的延迟时间
         */
        private fun calculateInitialDelay(): Long {
            val now = java.util.Calendar.getInstance()
            val target = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 3)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            if (target.before(now)) {
                target.add(java.util.Calendar.DAY_OF_MONTH, 1)
            }
            return target.timeInMillis - now.timeInMillis
        }
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "同步任务开始执行")

        val repository = SyncRepository(applicationContext)

        // 检查是否启用且已配对
        if (!repository.config.enabled) {
            Log.i(TAG, "同步未启用，跳过")
            return Result.success()
        }

        if (!repository.config.isPaired) {
            Log.w(TAG, "未配对，跳过同步")
            return Result.failure()
        }

        // 检查 WiFi
        if (!repository.isOnWifi()) {
            Log.i(TAG, "不在 WiFi 网络下，跳过同步")
            return Result.retry()
        }

        return try {
            when (val result = repository.syncAll()) {
                is SyncRepository.SyncResult.Success -> {
                    Log.i(TAG, "同步完成: ${result.syncedDates.size} 个日期")
                    Result.success()
                }

                is SyncRepository.SyncResult.PartialSuccess -> {
                    Log.w(
                        TAG,
                        "部分同步成功: ${result.syncedDates.size} 成功, ${result.failedDates.size} 失败"
                    )
                    Result.retry()
                }

                is SyncRepository.SyncResult.Error -> {
                    Log.e(TAG, "同步失败: ${result.message}")
                    Result.retry()
                }

                SyncRepository.SyncResult.Disabled,
                SyncRepository.SyncResult.NotPaired -> {
                    Result.success()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "同步异常", e)
            Result.retry()
        }
    }
}
