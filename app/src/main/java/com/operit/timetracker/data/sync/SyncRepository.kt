package com.operit.timetracker.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.operit.timetracker.data.DataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 同步仓库
 *
 * 核心同步逻辑：
 * 1. 发现服务器（mDNS → 缓存地址 → 手动配置）
 * 2. 查询未同步日期列表
 * 3. 打包每日数据为 JSON
 * 4. POST 到电脑端
 * 5. 更新同步日志
 */
class SyncRepository(private val context: Context) {

    companion object {
        private const val TAG = "SyncRepository"
        private const val SYNC_LIST_ENDPOINT = "/list"
        private const val SYNC_DATA_ENDPOINT = "/sync"
        private const val HEALTH_ENDPOINT = "/health"
    }

    val config = SyncConfig(context)
    private val logStore = SyncLogStore(context)

    /** 清除同步日志（解除配对时调用） */
    fun clearSyncLog() = logStore.clearAll()
    private val nsdDiscovery = NsdDiscovery(context)
    private val dataStore = DataStore(context)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /**
     * 同步结果
     */
    sealed class SyncResult {
        data class Success(val syncedDates: List<String>) : SyncResult()
        data class PartialSuccess(
            val syncedDates: List<String>,
            val failedDates: List<String>,
            val reason: String
        ) : SyncResult()

        data class Error(val message: String) : SyncResult()
        object Disabled : SyncResult()
        object NotPaired : SyncResult()
    }

    /**
     * 发现服务器地址
     *
     * 优先级：
     * 1. 缓存地址（上次成功连接的）→ 健康检查
     * 2. mDNS 发现 → 健康检查 → 缓存
     * 3. 手动配置地址 → 健康检查
     * 4. 全部失败 → null
     */
    suspend fun discoverServer(): Pair<String, Int>? {
        // 1. 尝试缓存地址
        val cachedHost = config.lastConnectedHost
        val cachedPort = config.lastConnectedPort
        if (cachedHost.isNotBlank() && cachedPort > 0) {
            if (nsdDiscovery.checkHealth(cachedHost, cachedPort)) {
                Log.i(TAG, "使用缓存地址: $cachedHost:$cachedPort")
                return cachedHost to cachedPort
            }
        }

        // 2. mDNS 发现
        val discovered = nsdDiscovery.discover()
        if (discovered != null) {
            val (host, port) = discovered
            if (nsdDiscovery.checkHealth(host, port)) {
                config.cacheConnectedAddress(host, port)
                Log.i(TAG, "mDNS 发现成功: $host:$port")
                return discovered
            }
        }

        // 3. 手动配置地址
        val manualHost = config.serverHost
        val manualPort = config.serverPort
        if (manualHost.isNotBlank()) {
            if (nsdDiscovery.checkHealth(manualHost, manualPort)) {
                config.cacheConnectedAddress(manualHost, manualPort)
                Log.i(TAG, "使用手动配置地址: $manualHost:$manualPort")
                return manualHost to manualPort
            }
        }

        Log.w(TAG, "未找到可用服务器")
        return null
    }

    /**
     * 执行完整同步流程
     *
     * @param onProgress 进度回调 (当前第几个, 总数, 当前日期)
     */
    suspend fun syncAll(
        onProgress: ((Int, Int, String) -> Unit)? = null
    ): SyncResult = withContext(Dispatchers.IO) {
        if (!config.enabled) return@withContext SyncResult.Disabled
        if (!config.isPaired) return@withContext SyncResult.NotPaired

        // 发现服务器
        val server = discoverServer()
        if (server == null) {
            return@withContext SyncResult.Error("未找到同步服务器，请检查电脑端是否已启动")
        }
        val (host, port) = server
        val baseUrl = "http://$host:$port"

        // 查找需要同步的日期
        val unsyncedDates = findUnsyncedDates()
        if (unsyncedDates.isEmpty()) {
            Log.i(TAG, "所有日期已同步，无需操作")
            return@withContext SyncResult.Success(emptyList())
        }

        Log.i(TAG, "需要同步 ${unsyncedDates.size} 个日期: $unsyncedDates")

        val syncedDates = mutableListOf<String>()
        val failedDates = mutableListOf<String>()

        for ((index, dateStr) in unsyncedDates.withIndex()) {
            onProgress?.invoke(index + 1, unsyncedDates.size, dateStr)

            try {
                val success = uploadDayData(baseUrl, dateStr)
                if (success) {
                    logStore.markSynced(dateStr)
                    syncedDates.add(dateStr)
                    Log.i(TAG, "同步成功: $dateStr")
                } else {
                    failedDates.add(dateStr)
                    Log.w(TAG, "同步失败: $dateStr")
                }
            } catch (e: Exception) {
                failedDates.add(dateStr)
                Log.e(TAG, "同步异常: $dateStr", e)
            }
        }

        return@withContext when {
            failedDates.isEmpty() -> SyncResult.Success(syncedDates)
            syncedDates.isEmpty() -> SyncResult.Error("所有日期同步失败")
            else -> SyncResult.PartialSuccess(
                syncedDates,
                failedDates,
                "部分日期同步失败"
            )
        }
    }

    /**
     * 查找所有未同步的日期
     *
     * 从数据库中遍历所有记录，提取唯一的日期，
     * 过滤掉已同步的。
     */
    private fun findUnsyncedDates(): List<String> {
        val records = dataStore.loadRecords()
        val calendar = Calendar.getInstance()

        val allDates = records.map { record ->
            calendar.timeInMillis = record.startTime
            dateFormat.format(calendar.time)
        }.distinct().sorted()

        // 加上今天（即使没有记录也要同步，表示今天无数据）
        val today = dateFormat.format(System.currentTimeMillis())
        val datesWithToday = (allDates + today).distinct().sorted()

        return datesWithToday.filter { !logStore.isSynced(it) }
    }

    /**
     * 上传某天的数据到服务器
     *
     * JSON 格式：
     * {
     *   "date": "2026-06-13",
     *   "syncedAt": 1718284800000,
     *   "records": [...],
     *   "summary": { "category": totalSeconds, ... }
     * }
     */
    private fun uploadDayData(baseUrl: String, dateStr: String): Boolean {
        val dayData = packageDayData(dateStr)
        val jsonBody = dayData.toString(2)

        val request = Request.Builder()
            .url("$baseUrl$SYNC_DATA_ENDPOINT")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer ${config.authToken}")
            .addHeader("X-Date", dateStr)
            .build()

        val response = client.newCall(request).execute()
        val success = response.isSuccessful
        if (!success) {
            Log.w(TAG, "上传失败 HTTP ${response.code}: ${response.body?.string()}")
        }
        response.close()
        return success
    }

    /**
     * 打包某天的数据
     */
    private fun packageDayData(dateStr: String): JSONObject {
        val records = dataStore.loadRecords()
        val calendar = Calendar.getInstance()

        // 计算当天的起止时间
        val dayStart = dateFormat.parse(dateStr)?.time ?: 0
        val dayEnd = dayStart + 24 * 60 * 60 * 1000L

        // 过滤当天记录
        val dayRecords = records.filter { record ->
            record.startTime < dayEnd && (record.endTime ?: System.currentTimeMillis()) > dayStart
        }

        val recordsArray = JSONArray()
        val summary = mutableMapOf<String, Long>()

        for (record in dayRecords) {
            // 跨日分摊
            val effectiveStart = maxOf(record.startTime, dayStart)
            val effectiveEnd = minOf(record.endTime ?: System.currentTimeMillis(), dayEnd)
            val effectiveDuration = (effectiveEnd - effectiveStart) / 1000

            if (effectiveDuration <= 0) continue

            val recordObj = JSONObject().apply {
                put("id", record.id)
                put("category", record.category)
                put("startTime", effectiveStart)
                put("endTime", effectiveEnd)
                put("durationSeconds", effectiveDuration)
                put("originalInput", record.originalInput)
            }
            recordsArray.put(recordObj)

            summary[record.category] = (summary[record.category] ?: 0) + effectiveDuration
        }

        val summaryObj = JSONObject()
        for ((category, seconds) in summary) {
            summaryObj.put(category, seconds)
        }

        return JSONObject().apply {
            put("app_id", "timetracker")
            put("date", dateStr)
            put("syncedAt", System.currentTimeMillis())
            put("records", recordsArray)
            put("summary", summaryObj)
        }
    }

    /**
     * 检查是否在 WiFi 网络下
     */
    fun isOnWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * 配对（设置 token）
     */
    fun pair(token: String, host: String, port: Int) {
        config.authToken = token
        config.serverHost = host
        config.serverPort = port
        config.enabled = true
        config.cacheConnectedAddress(host, port)
    }

    /**
     * 获取同步状态摘要（用于 UI 显示）
     */
    fun getSyncStatusSummary(): String {
        if (!config.enabled) return "同步未启用"
        if (!config.isPaired) return "未配对"

        val allDates = getAllRecordDates()
        val syncedCount = allDates.count { logStore.isSynced(it) }
        val totalCount = allDates.size
        val unsyncedCount = totalCount - syncedCount

        return if (unsyncedCount == 0) {
            "全部已同步 ($totalCount 天)"
        } else {
            "已同步 $syncedCount/$totalCount 天，$unsyncedCount 天待同步"
        }
    }

    /**
     * 获取所有记录日期
     */
    private fun getAllRecordDates(): List<String> {
        val records = dataStore.loadRecords()
        val calendar = Calendar.getInstance()
        return records.map { record ->
            calendar.timeInMillis = record.startTime
            dateFormat.format(calendar.time)
        }.distinct().sorted()
    }
}
